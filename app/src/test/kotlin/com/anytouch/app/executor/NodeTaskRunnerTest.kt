package com.anytouch.app.executor

import com.anytouch.app.locator.LocatorMiss
import com.anytouch.app.locator.TestUi
import com.anytouch.app.locator.UiNode
import com.anytouch.app.locator.ui
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
            return succeed
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
}
