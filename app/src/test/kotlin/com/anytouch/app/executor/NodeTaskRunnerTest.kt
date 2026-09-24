package com.anytouch.app.executor

import com.anytouch.app.locator.LocatorMiss
import com.anytouch.app.locator.TestUi
import com.anytouch.app.locator.UiNode
import com.anytouch.app.locator.ui
import com.anytouch.app.locator.preOrder
import com.anytouch.app.platform.NodeActions
import com.anytouch.app.safety.HighRiskCategory
import com.anytouch.app.safety.HighRiskMatcher
import com.anytouch.app.safety.HighRiskRule
import com.anytouch.app.safety.HighRiskRuleSource
import com.anytouch.app.safety.KillSwitch
import com.anytouch.app.safety.SafetyVerdict
import com.anytouch.contracts.Action
import com.anytouch.contracts.ContractJson
import com.anytouch.pipeline.PipelineStopCode
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer

/**
 * 主窗窄口集成层行为锁：NodeTaskRunner 全链路（kill / action 门禁 / 定位 / 安全阀 / 回执映射）。
 * 假设备注入，JVM 直跑；定位超时与沉降时延归零，防单测变慢。
 */
class NodeTaskRunnerTest {

    private class FakeDevice(var root: UiNode?) : NodeActions {
        val performed = mutableListOf<String>()
        var succeed = true
        var mutateTree = true
        var pasteWorks = false
        var setTextWorksAfterFirst = false
        var staleSwapOnType = false
        private var setTextCalls = 0

        override suspend fun root(): UiNode? = root

        override fun click(node: UiNode): Boolean {
            performed += "click:${(node as TestUi).text}"
            return succeed
        }

        override fun scroll(node: UiNode, forward: Boolean): Boolean {
            performed += "scroll:$forward"
            return succeed
        }

        override fun setText(node: UiNode, text: String): Boolean {
            performed += "setText:$text"
            setTextCalls++
            if (staleSwapOnType) {
                // API 35 搜索页实测形态：字落进过渡后重建的新输入节点，派发用的旧句柄文本永不更新——
                // 复核只认句柄会把真落字报成假红，扫树自证边就是为它装的。
                root?.let { r ->
                    r.preOrder().firstOrNull {
                        it !== node && it.className?.contains("EditText") == true
                    }?.let { (it as TestUi).text = text }
                }
            } else if (mutateTree || (setTextWorksAfterFirst && setTextCalls >= 2)) {
                // 仿真如真实设备成功路径：字要落进树——执行器落字复核会把只回 true 的假设备判为虚报。
                // setTextWorksAfterFirst=过渡态仿真：首派谎 true 不落字，重试派发才落（API 35 搜索页实测形态）。
                (node as? TestUi)?.text = text
            }
            return succeed
        }

        override fun pasteText(node: UiNode, text: String): Boolean {
            if (!pasteWorks) return false
            performed += "paste:$text"
            (node as? TestUi)?.text = text
            return true
        }
    }

    private fun settingsTree(): TestUi = ui(
        clazz = "android.widget.FrameLayout",
        children = listOf(
            ui(
                id = "android:id/list",
                clazz = "android.widget.ListView",
                scrollable = true,
                children = listOf(
                    ui(marker = "Network & internet", clickable = true),
                    ui(marker = "System", clickable = true),
                    ui(marker = "Display", clickable = true),
                ),
            ),
        ),
    )

    private fun decode(json: String): List<Action> =
        ContractJson.instance.decodeFromString(ListSerializer(Action.serializer()), json)

    private fun click(id: String, clueJson: String): String =
        """{"action_id":"$id","type":"click","source":"node","value":$clueJson,"safety":{"viewport_ok":true,"click_enabled":true}}"""

    private fun runnerFor(
        device: NodeActions,
        matcher: HighRiskMatcher = HighRiskMatcher.default(),
        confirmer: suspend (SafetyVerdict.RequiresSecondConfirm) -> Boolean = { false },
    ): NodeTaskRunner = NodeTaskRunner(
        device = device,
        matcher = matcher,
        confirmer = confirmer,
        locateTimeoutMs = 0,
        locatePollMs = 10,
        settleMs = 0,
        landedTimeoutMs = 0,
        confirmTimeoutMs = 200,
    )

    private fun payloadString(cmd: com.anytouch.contracts.Command, key: String): String =
        cmd.payload.getValue(key).toString().removeSurrounding("\"")

    @BeforeTest
    fun resetKill() {
        KillSwitch.reset()
    }

    @AfterTest
    fun clearKill() {
        KillSwitch.reset()
    }

    @Test
    fun `三步click链全绿并留痕定位信息`() = runBlocking {
        val device = FakeDevice(settingsTree())
        val report = runnerFor(device).run(
            decode(
                """[
                  ${click("a1", """{"text":"System"}""")},
                  ${click("a2", """{"resource_id":"android:id/list"}""")},
                  ${click("a3", """{"path":"FrameLayout>clickable=true[2]"}""")}
                ]""",
            ),
        )
        assertFalse(report.stopped)
        assertEquals(listOf("click:System", "click:null", "click:Display"), device.performed)
        assertTrue(report.results.all { it.ok })
        assertEquals("TEXT", report.results[0].details["level"].toString().removeSurrounding("\""))
        assertTrue(report.results[2].details["index_path"].toString().contains("root/0/2"))
    }

    @Test
    fun `click与wait与type_text混合队列按序执行`() = runBlocking {
        val device = FakeDevice(settingsTree())
        val report = runnerFor(device).run(
            decode(
                """[
                  ${click("c1", """{"text":"Display"}""")},
                  {"action_id":"w1","type":"wait","source":"node","value":{"ms":1},"safety":{"viewport_ok":false,"click_enabled":false}},
                  {"action_id":"t1","type":"type_text","source":"node","value":{"resource_id":"android:id/list","input":"hi"},"safety":{"viewport_ok":true,"click_enabled":true}}
                ]""",
            ),
        )
        assertFalse(report.stopped)
        assertEquals(3, report.results.size)
        assertTrue(report.results.all { it.ok })
        assertEquals(listOf("click:Display", "setText:hi"), device.performed)
    }

    @Test
    fun `执行前kill已置位则整队不执行并产USER_STOP回执`() = runBlocking {
        KillSwitch.stop(reason = "ball", source = "stop_ball")
        val device = FakeDevice(settingsTree())
        val report = runnerFor(device).run(decode("""[${click("a1", """{"text":"System"}""")}]"""))
        assertTrue(report.stopped)
        assertEquals(0, report.results.size)
        val cmd = report.stopCommand!!
        assertEquals("kill_switch", cmd.source)
        assertEquals("USER_STOP", payloadString(cmd, "stop_code"))
        assertEquals("ball", payloadString(cmd, "stop_reason"))
        assertEquals("stopped", cmd.confirm)
        assertTrue(cmd.commandId.startsWith("kill-switch-stop-a1-0"))
    }

    @Test
    fun `队列中途kill立即中止剩余步骤`() = runBlocking {
        val inner = FakeDevice(settingsTree())
        val killer = object : NodeActions by inner {
            override fun click(node: UiNode): Boolean {
                val ok = inner.click(node)
                KillSwitch.stop()
                return ok
            }
        }
        val report = runnerFor(killer).run(
            decode(
                """[
                  ${click("s1", """{"text":"Display"}""")},
                  ${click("s2", """{"text":"System"}""")}
                ]""",
            ),
        )
        assertTrue(report.stopped)
        assertEquals(1, report.results.size)
        assertEquals("kill_switch", report.stopCommand!!.source)
        assertTrue(report.stopCommand!!.commandId.contains("s2-1"))
    }

    @Test
    fun `高危节点确认后执行且details带second_confirmed`() = runBlocking {
        val root = ui(
            clazz = "FrameLayout",
            children = listOf(ui(marker = "支付", id = "com.shop:id/pay_now", clickable = true)),
        )
        val device = FakeDevice(root)
        val report = runnerFor(device, confirmer = { true }).run(
            decode("""[${click("pay", """{"text":"支付"}""")}]"""),
        )
        assertFalse(report.stopped)
        assertTrue(report.results.single().ok)
        assertEquals("true", report.results.single().details["second_confirmed"].toString())
    }

    @Test
    fun `高危节点无人确认则SAFETY_GATE中止且后续步不执行`() = runBlocking {
        val root = ui(
            clazz = "FrameLayout",
            children = listOf(ui(marker = "删除账户", id = "acct_delete", clickable = true)),
        )
        val device = FakeDevice(root)
        val report = runnerFor(device, confirmer = { false }).run(
            decode(
                """[
                  ${click("del", """{"text":"删除账户"}""")},
                  ${click("next", """{"text":"System"}""")}
                ]""",
            ),
        )
        assertTrue(report.stopped)
        assertEquals(PipelineStopCode.SAFETY_GATE_BLOCKED, payloadString(report.stopCommand!!, "stop_code"))
        assertEquals(1, report.results.size)
        assertFalse(report.results.first().ok)
        assertEquals(listOf(), device.performed)
    }

    @Test
    fun `高危确认超时视为拒绝`() = runBlocking {
        val root = ui(
            clazz = "FrameLayout",
            children = listOf(ui(marker = "转账", id = "xfer", clickable = true)),
        )
        val device = FakeDevice(root)
        val report = runnerFor(device, confirmer = {
            delay(1000) // 超出 confirmTimeoutMs=200
            true
        }).run(decode("""[${click("t", """{"text":"转账"}""")}]"""))
        assertTrue(report.stopped)
        assertEquals(0, device.performed.size)
    }

    @Test
    fun `词表装载失败时任何节点默认拒绝`() = runBlocking {
        val broken = HighRiskMatcher.from(HighRiskRuleSource { throw IllegalStateException("boom") })
        for (label in listOf("支付", "System")) {
            KillSwitch.reset()
            val root = ui(clazz = "FrameLayout", children = listOf(ui(marker = label, clickable = true)))
            val device = FakeDevice(root)
            val report = runnerFor(device, matcher = broken).run(
                decode("""[${click("p", """{"text":"$label"}""")}]"""),
            )
            assertTrue(report.stopped, "$label 应被默认拒绝")
            assertTrue(payloadString(report.stopCommand!!, "stop_reason").contains("RULES_LOAD_FAILED"))
            assertEquals(0, device.performed.size)
        }
    }

    @Test
    fun `定位失败产NODE_NOT_FOUND并带三阶attempts证据后中止`() = runBlocking {
        val device = FakeDevice(settingsTree())
        val report = runnerFor(device).run(
            decode(
                """[
                  ${click("gone", """{"text":"不存在的项"}""")},
                  ${click("later", """{"text":"Display"}""")}
                ]""",
            ),
        )
        assertTrue(report.stopped)
        val rec = report.results.single().recovery!!
        assertEquals(LocatorMiss.NODE_NOT_FOUND, rec.code)
        assertEquals(3, payloadAttempts(rec.evidence["attempts"].toString()))
        assertEquals(1, report.results.size)
        assertEquals(listOf(), device.performed)
    }

    private fun payloadAttempts(raw: String): Int = "\"outcome\"".toRegex().findAll(raw).count()

    @Test
    fun `action门禁未放行则设备零调用`() = runBlocking {
        val device = FakeDevice(settingsTree())
        val report = runnerFor(device).run(
            decode(
                """[{"action_id":"g1","type":"click","source":"node","value":{"text":"System"},"safety":{"viewport_ok":true,"click_enabled":false}}]""",
            ),
        )
        assertTrue(report.stopped)
        assertEquals(listOf(), device.performed)
        assertTrue(report.stopCommand!!.commandId.startsWith("safety-gate-stop-g1"))
        assertEquals(PipelineStopCode.SAFETY_GATE_BLOCKED, payloadString(report.stopCommand!!, "stop_code"))
    }

    @Test
    fun `不支持的动作类型以EXECUTOR_ERROR中止`() = runBlocking {
        val device = FakeDevice(settingsTree())
        val report = runnerFor(device).run(
            decode("""[{"action_id":"u1","type":"teleport","source":"node","safety":{"viewport_ok":true,"click_enabled":true}}]"""),
        )
        assertTrue(report.stopped)
        assertEquals("EXECUTOR_ERROR", report.results.single().recovery!!.code)
    }

    @Test
    fun `type_text虚报 performAction为true但未落字 回执翻失败并停机`() = runBlocking {
        // 模拟器实测雷：Compose 输入框 ACTION_SET_TEXT 返回 true 却不落字——performAction 布尔不可作为成功凭据。
        val device = FakeDevice(settingsTree()).apply { mutateTree = false }
        val report = runnerFor(device).run(
            decode("""[{"action_id":"t1","type":"type_text","source":"node","value":{"resource_id":"android:id/list","input":"hi"},"safety":{"viewport_ok":true,"click_enabled":true}}]"""),
        )
        assertEquals(listOf("setText:hi", "setText:hi"), device.performed, "谎报一次→整步重试一次封顶；重试仍虚报必须收红，不得无限重派")
        assertTrue(report.stopped)
        val recovery = report.results.single().recovery!!
        assertEquals(false, report.results.single().ok)
        assertEquals("EXECUTOR_ERROR", recovery.code)
        assertTrue("未落字" in recovery.message, "消息必须点破虚报性质: ${recovery.message}")
        assertEquals("set_text_unverified", payloadString(report.stopCommand!!, "stop_reason"))
    }

    @Test
    fun `type_text 过渡态谎报后整步重试落字则判成功`() = runBlocking {
        // API 35 搜索页实测：过渡动画中 SET_TEXT 谎 true 不落字；重试派发落到重建后的活节点即成功。
        val device = FakeDevice(settingsTree()).apply { mutateTree = false; setTextWorksAfterFirst = true }
        val report = runnerFor(device).run(
            decode("""[{"action_id":"t1","type":"type_text","source":"node","value":{"resource_id":"android:id/list","input":"hi"},"safety":{"viewport_ok":true,"click_enabled":true}}]"""),
        )
        assertEquals(listOf("setText:hi", "setText:hi"), device.performed)
        assertFalse(report.stopped)
        assertEquals(true, report.results.single().ok)
    }

    @Test
    fun `type_text 句柄过期但字已落进换新输入节点 扫树自证不误红`() = runBlocking {
        // AVD API 35 搜索页设备实证：SET_TEXT 派发给过渡前句柄后 Compose 整节点换新，
        // 字已落进新 EditText（uiautomator dump 亲见 text="password"）而旧句柄读不到——
        // 只认句柄活读会把真成功报成 set_text_unverified 假红。
        val tree = ui(
            clazz = "android.widget.FrameLayout",
            children = listOf(
                ui(marker = "Search settings", clazz = "android.widget.EditText"),
                ui(marker = "", clazz = "android.widget.EditText"),
            ),
        )
        val device = FakeDevice(tree).apply { mutateTree = false; staleSwapOnType = true }
        val report = runnerFor(device).run(
            decode("""[{"action_id":"t1","type":"type_text","source":"node","value":{"text":"Search settings","input":"hi"},"safety":{"viewport_ok":true,"click_enabled":true}}]"""),
        )
        assertEquals(listOf("setText:hi"), device.performed, "扫树自证后不得再整步重派")
        assertFalse(report.stopped)
        assertEquals(true, report.results.single().ok)
    }

    @Test
    fun `type_text SET_TEXT虚报后paste兜底落字 成功且details记route`() = runBlocking {
        val device = FakeDevice(settingsTree()).apply { mutateTree = false; pasteWorks = true }
        val report = runnerFor(device).run(
            decode("""[{"action_id":"t1","type":"type_text","source":"node","value":{"resource_id":"android:id/list","input":"hi"},"safety":{"viewport_ok":true,"click_enabled":true}}]"""),
        )
        assertEquals(listOf("setText:hi", "paste:hi"), device.performed, "先 SET_TEXT 复核未落字，才允许派发兜底")
        assertFalse(report.stopped)
        val result = report.results.single()
        assertTrue(result.ok)
        assertEquals("paste_fallback", (result.details["route"] as? kotlinx.serialization.json.JsonPrimitive)?.content)
    }

    @Test
    fun `缺少定位线索以INVALID_INPUT中止不猜测`() = runBlocking {
        val device = FakeDevice(settingsTree())
        val report = runnerFor(device).run(
            decode("""[{"action_id":"n1","type":"click","source":"node","safety":{"viewport_ok":true,"click_enabled":true}}]"""),
        )
        assertTrue(report.stopped)
        assertEquals(PipelineStopCode.INVALID_INPUT, payloadString(report.stopCommand!!, "stop_code"))
        assertTrue(payloadString(report.stopCommand!!, "stop_reason").contains("missing_locator"))
        assertEquals(listOf(), device.performed)
    }

    @Test
    fun `performAction返回false以EXECUTOR_ERROR中止`() = runBlocking {
        val device = FakeDevice(settingsTree()).apply { succeed = false }
        val report = runnerFor(device).run(decode("""[${click("f1", """{"text":"System"}""")}]"""))
        assertTrue(report.stopped)
        assertEquals("EXECUTOR_ERROR", report.results.single().recovery!!.code)
        assertEquals(1, report.results.size)
    }

    @Test
    fun `type_text明示拒绝边重派一次封顶仍拒收perform_failed`() = runBlocking {
        // MarvisPhone 设备实证：SET_TEXT+PASTE 双双 false 可能只是字段重建瞬间的派发被拒（false=没执行过，
        // 重派零副作用）；但重试必须封顶一次，不得对明示拒绝无限重派（K40/MIUI 雷 12 是持久拒绝）。
        val device = FakeDevice(settingsTree()).apply { succeed = false }
        val report = runnerFor(device).run(
            decode("""[{"action_id":"t1","type":"type_text","source":"node","value":{"resource_id":"android:id/list","input":"hi"},"safety":{"viewport_ok":true,"click_enabled":true}}]"""),
        )
        assertEquals(
            listOf("setText:hi", "setText:hi"),
            device.performed,
            "首派 SET_TEXT 拒（PASTE 通道未开不记流水）→整步重定位再派一次封顶，随后必须收 perform_failed",
        )
        assertTrue(report.stopped)
        assertEquals("perform_failed", payloadString(report.stopCommand!!, "stop_reason"))
    }

    @Test
    fun `wait步在任何设备拒答下也绝不产perform_failed`() = runBlocking {
        // A1 接线把原来的两个 if 换成了 while(true)+redispatchPlan。主窗核得：唯一会因此分叉的输入
        // 是「WAIT 走进 runNodeStep」——而 run() 的 when(action.type) 只把 CLICK/SCROLL/TYPE_TEXT 送进
        // runNodeStep，WAIT 走 delay 分支，所以该输入不可达、环与原判据在可达输入上逐格同形。
        // 这条用例锁住那个前提：WAIT 连一次派发都不产生（设备全体拒答时也不收 perform_failed）。
        // 若将来给 WAIT 补派发分支或把它接进 runNodeStep，这里必须红（届时须重开偏差）。
        val device = FakeDevice(settingsTree()).apply { succeed = false }
        val report = runnerFor(device).run(
            decode(
                """[
                  {"action_id":"w1","type":"wait","source":"node","value":{"ms":1},"safety":{"viewport_ok":false,"click_enabled":false}},
                  {"action_id":"w2","type":"wait","source":"node","value":{"ms":1,"resource_id":"android:id/list","text":"System","input":"hi"},"safety":{"viewport_ok":true,"click_enabled":true}}
                ]""",
            ),
        )
        assertEquals(listOf(), device.performed, "WAIT 从不派发：A1 的重派环对它不可达")
        assertFalse(report.stopped)
        assertEquals(2, report.results.size)
        assertTrue(report.results.all { it.ok }, "WAIT 步不得被判成 perform_failed：${report.results.map { it.recovery }}")
    }

    @Test
    fun `多类同中主判取声明序最高类别`() = runBlocking {
        val rules = HighRiskMatcher.from(
            HighRiskRuleSource {
                listOf(
                    HighRiskRule("D:drop", HighRiskCategory.DELETE, "drop"),
                    HighRiskRule("P:pay", HighRiskCategory.PAYMENT, "pay"),
                )
            },
        )
        val root = ui(children = listOf(ui(marker = "drop_and_pay", clickable = true)))
        val device = FakeDevice(root)
        var seenRule: String? = null
        val report = runnerFor(device, matcher = rules, confirmer = {
            seenRule = it.matchedRule.ruleId
            true
        }).run(decode("""[${click("m", """{"text":"drop_and_pay"}""")}]"""))
        assertEquals("P:pay", seenRule)
        assertFalse(report.stopped)
    }

    @Test
    fun `停止回执Command与ClosedLoop形状同字段无损`() = runBlocking {
        KillSwitch.stop(reason = "r1", source = "ball1")
        val report = runnerFor(FakeDevice(settingsTree())).run(
            decode("""[${click("k1", """{"text":"System"}""")}]"""),
        )
        val cmd = report.stopCommand!!
        assertEquals("stop", cmd.type)
        val keys = cmd.payload.keys
        assertTrue(
            keys.containsAll(
                setOf("step_index", "step_id", "stop_code", "stop_reason", "stop_source"),
            ),
            cmd.payload.toString(),
        )
        assertEquals("0", payloadString(cmd, "step_index"))
        assertEquals("r1", payloadString(cmd, "stop_reason"))
        assertEquals("ball1", payloadString(cmd, "stop_source"))
    }

    @Test
    fun `root为空时按miss归因且队列中止`() = runBlocking {
        val device = FakeDevice(null)
        val report = runnerFor(device).run(decode("""[${click("e1", """{"text":"System"}""")}]"""))
        assertTrue(report.stopped)
        assertEquals(LocatorMiss.NODE_NOT_FOUND, report.results.single().recovery!!.code)
    }

    /* ---- 停止球响应性：长等待三处（定位轮询 / 二次确认 / 落字复核）必须 ≤poll 周期内中止 ---- */

    private fun slowRunner(
        device: NodeActions,
        confirmer: suspend (SafetyVerdict.RequiresSecondConfirm) -> Boolean = { false },
    ) = NodeTaskRunner(
        device = device,
        matcher = HighRiskMatcher.default(),
        confirmer = confirmer,
        locateTimeoutMs = 30_000,
        locatePollMs = 10,
        settleMs = 0,
        landedTimeoutMs = 30_000,
        confirmTimeoutMs = 30_000,
    )

    @Test
    fun `定位轮询期间kill立即中止并产USER_STOP回执而非NODE_NOT_FOUND`() = runBlocking {
        val device = FakeDevice(settingsTree())
        val runner = slowRunner(device)
        val killer = launch { delay(60); KillSwitch.stop(reason = "user_stop", source = "stop_ball") }
        val started = System.currentTimeMillis()
        val report = runner.run(decode("""[${click("l1", """{"text":"不存在的项"}""")}]"""))
        killer.join()
        val elapsed = System.currentTimeMillis() - started
        assertTrue(elapsed < 10_000, "kill 必须在轮询周期内打断 30s 定位，实测 ${elapsed}ms")
        assertTrue(report.stopped)
        val cmd = report.stopCommand!!
        assertEquals("kill_switch", cmd.source)
        assertEquals("USER_STOP", payloadString(cmd, "stop_code"))
        assertEquals("user_stop", payloadString(cmd, "stop_reason"))
        assertEquals("stop_ball", payloadString(cmd, "stop_source"))
        assertEquals("USER_STOP", report.results.single().recovery!!.code)
    }

    @Test
    fun `二次确认等待期间kill取消面板等待并产USER_STOP回执`() = runBlocking {
        val root = ui(
            clazz = "FrameLayout",
            children = listOf(ui(marker = "转账", id = "xfer", clickable = true)),
        )
        val device = FakeDevice(root)
        val runner = slowRunner(device, confirmer = { delay(30_000); true })
        val killer = launch { delay(60); KillSwitch.stop() }
        val started = System.currentTimeMillis()
        val report = runner.run(decode("""[${click("k2", """{"text":"转账"}""")}]"""))
        killer.join()
        val elapsed = System.currentTimeMillis() - started
        assertTrue(elapsed < 10_000, "kill 必须打断 30s 确认等待，实测 ${elapsed}ms")
        assertTrue(report.stopped)
        val cmd = report.stopCommand!!
        assertEquals("kill_switch", cmd.source)
        assertEquals("USER_STOP", payloadString(cmd, "stop_code"))
        assertEquals(listOf(), device.performed, "被停止的高危步绝不派发")
    }

    @Test
    fun `落字复核期间kill中止轮询且不误报set_text_unverified`() = runBlocking {
        val device = FakeDevice(settingsTree()).apply { mutateTree = false }
        val runner = slowRunner(device)
        val killer = launch { delay(60); KillSwitch.stop() }
        val started = System.currentTimeMillis()
        val report = runner.run(
            decode("""[{"action_id":"t9","type":"type_text","source":"node","value":{"resource_id":"android:id/list","input":"hi"},"safety":{"viewport_ok":true,"click_enabled":true}}]"""),
        )
        killer.join()
        val elapsed = System.currentTimeMillis() - started
        assertTrue(elapsed < 10_000, "kill 必须打断 30s 落字复核，实测 ${elapsed}ms")
        assertTrue(report.stopped)
        val cmd = report.stopCommand!!
        assertEquals("kill_switch", cmd.source)
        assertEquals("USER_STOP", payloadString(cmd, "stop_code"))
        assertTrue(
            payloadString(cmd, "stop_reason") != "set_text_unverified",
            "停止原因不得被未落字误报抢占",
        )
    }

    @Test
    fun `末步执行中kill在步界补发回执_队列不以stopped_false收官`() = runBlocking {
        val inner = FakeDevice(settingsTree())
        val killer = object : NodeActions by inner {
            override fun click(node: UiNode): Boolean {
                val ok = inner.click(node)
                KillSwitch.stop() // 模拟末步派发完成后的沉降窗口内按下停止
                return ok
            }
        }
        val report = runnerFor(killer).run(decode("""[${click("last", """{"text":"Display"}""")}]"""))
        assertTrue(report.stopped, "末步后无步首检查点，必须在此补发")
        assertEquals("kill_switch", report.stopCommand!!.source)
        assertEquals("USER_STOP", payloadString(report.stopCommand!!, "stop_code"))
        assertEquals(1, report.results.size)
        assertTrue(report.results.single().ok, "该步确实完成了，回执表达的是停止而非失败")
    }
}
