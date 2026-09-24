package com.anytouch.app.compile

import com.anytouch.app.compile.ByokPreflight.ByokPlan
import com.anytouch.app.compile.ByokPreflight.Verdict
import com.anytouch.app.recorder.session.RecorderStore
import com.anytouch.byok.ByokErrorKind
import com.anytouch.byok.CompilerPrompt
import com.anytouch.byok.DslCompiler
import com.anytouch.byok.LlmTransport
import com.anytouch.byok.ScreenContext
import com.anytouch.byok.TransportFailure
import com.anytouch.contracts.Action
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * AI 编译链路的 JVM 锁（S3-D）：整条判定顺序在纯类里跑通，设备侧一行判据都不许有。
 *
 * 最要紧的一条是**"没走通就不落账"**：预检拒、传输失败、校验拒、空数组四种情况下
 * [RecorderStore.acceptModelActions] 一次都不许被调——落半条产物就是屏上多一套真值，
 * 用户会在"看不见的步骤"上继续编辑（雷 18 同族）。
 */
class ByokCompileControllerTest {

    private class Recorder(private val reply: String) : LlmTransport {
        var system: String? = null
        var user: String? = null
        override fun complete(systemPrompt: String, userPrompt: String): String {
            system = systemPrompt
            user = userPrompt
            return reply
        }
    }

    private val okJson =
        """[{"action_id":"bt","type":"click","source":"node","value":{"text":"Bluetooth"},""" +
            """"safety":{"viewport_ok":true,"click_enabled":true}}]"""

    private val plan = ByokPlan("https://a.example/v1", "a.example", "m", "nvapi-SecretValue0123456789")

    private val published = mutableListOf<List<Action>>()
    private var ledger: RecorderStore.ModelLedger = RecorderStore.ModelLedger.Written(steps = 1, replaced = 0)

    private fun controller(
        reply: String = okJson,
        pre: Verdict = Verdict.Ready(plan),
        transport: LlmTransport? = null,
    ): ByokCompileController {
        val speaker = transport ?: Recorder(reply)
        return ByokCompileController(
            preflightOf = { pre },
            compilerOf = { DslCompiler(speaker) },
            publish = { actions -> published += actions; ledger },
        )
    }

    private fun ctx(enabled: Boolean = true, seen: Int = 3, lines: List<String> = listOf("text=\"Bluetooth\"")) =
        ScreenContext(enabled, lines, seen, 0, 0, 0, 0, 0)

    @Test
    fun `词表档拒收时点名第几步与supported集合并与执行中档分开`() {
        ledger = RecorderStore.ModelLedger.RefusedUnsupportedType(
            index = 1,
            type = "key",
            supportedTypes = setOf("wait", "click", "scroll", "type_text"),
        )
        val r = controller().compile("进蓝牙页", ctx())
        assertFalse(r.published, "写口整本没收下却报成功=虚报")
        assertEquals(0, r.steps, "步数只报落账步数")
        assertNull(r.gate, "这不是预检档（预检已经过了才走到落账口），档位身份在 ModelLedger 自己身上")
        assertEquals("ledger", r.stage)
        assertEquals(ByokErrorKind.COMPILE_REJECT, r.errorKind)
        assertTrue(r.userCopy.contains("Step 2"), r.userCopy)
        assertTrue(r.userCopy.contains("key"), r.userCopy)
        assertTrue(r.userCopy.contains("type_text"), "屏上必须说清现在跑得动的是哪几个：${r.userCopy}")
        assertTrue(r.userCopy.contains("whole ledger"), r.userCopy)
        // 两档各说各话（派单书 §4-2"各自一条、不并档"）：并成一句就等于用户不知道该改说法还是该停任务
        ledger = RecorderStore.ModelLedger.RefusedRunning
        val running = controller().compile("进蓝牙页", ctx())
        assertNotEquals(running.userCopy, r.userCopy, "词表档与执行中档给了同一句话=两档并档")
        assertEquals(ByokPreflight.Gate.RUNNING, running.gate, "执行中档的预检身份不许被新档带跑")
        assertTrue(running.userCopy.contains("not written"), running.userCopy)
        assertFalse(running.userCopy.contains("type_text"), "执行中档不该报词表内容：${running.userCopy}")
    }

    @Test
    fun `预检拒时不落账 也不碰模型`() {
        var asked = false
        val c = ByokCompileController(
            preflightOf = { Verdict.Blocked(ByokPreflight.Gate.NO_CREDENTIAL, "本机还没有保存好的凭据") },
            compilerOf = { throw AssertionError("预检没过就不该构造 transport") },
            publish = { asked = true; ledger },
        )
        val r = c.compile("进蓝牙页", ctx())
        assertFalse(r.published)
        assertEquals(ByokPreflight.Gate.NO_CREDENTIAL, r.gate)
        assertTrue(r.userCopy.contains("本机还没有保存好的凭据"))
        assertFalse(asked)
        assertNull(r.context, "预检就拒了这一跑，压根还没看屏——不许把词表账一起报出去")
    }

    @Test
    fun `模型编出坐标时校验拒 一步都不落账`() {
        val reply =
            """[{"action_id":"bt","type":"click","source":"node","value":{"x":10,"y":20},""" +
                """"safety":{"viewport_ok":true,"click_enabled":true}}]"""
        val r = controller(reply = reply).compile("进蓝牙页", ctx())
        assertFalse(r.published)
        assertEquals(ByokErrorKind.COMPILE_REJECT, r.errorKind)
        assertEquals("validate", r.stage)
        assertTrue(r.userCopy.contains("safety check"))
        assertTrue(published.isEmpty(), "被校验拒掉的产物写进了步序账=第二套真值")
    }

    @Test
    fun `传输失败按档给话术 归因里不许有 Key`() {
        val c = ByokCompileController(
            preflightOf = { Verdict.Ready(plan) },
            compilerOf = {
                DslCompiler(object : LlmTransport {
                    override fun complete(systemPrompt: String, userPrompt: String): String =
                        throw TransportFailure(ByokErrorKind.UNAUTHORIZED, "HTTP 401 Authorization: Bearer ${plan.apiKey}")
                })
            },
            publish = { ledger },
        )
        val r = c.compile("进蓝牙页", ctx())
        assertFalse(r.published)
        assertEquals(ByokErrorKind.UNAUTHORIZED, r.errorKind)
        assertTrue(r.userCopy.startsWith(ByokErrorKind.UNAUTHORIZED.userCopy()))
        assertFalse(r.userCopy.contains(plan.apiKey), "失败话术把 Key 印到屏上了：${r.userCopy}")
        assertTrue(r.userCopy.contains("***"), "归因片段没走脱敏：${r.userCopy}")
    }

    @Test
    fun `模型交回空数组时按空档给话术`() {
        val r = controller(reply = "[]").compile("进蓝牙页", ctx())
        assertFalse(r.published)
        assertEquals(ByokErrorKind.EMPTY_ACTIONS, r.errorKind)
        assertTrue(published.isEmpty())
    }

    @Test
    fun `成功时按写口给的步数说话`() {
        ledger = RecorderStore.ModelLedger.Written(steps = 1, replaced = 4)
        val r = controller().compile("进蓝牙页", ctx())
        assertTrue(r.published)
        assertEquals(1, r.steps)
        assertEquals(4, r.replaced)
        assertTrue(r.userCopy.contains("1 step") && r.userCopy.contains("discarded 4 stale"))
        assertEquals("Bluetooth", published.single().single().value!!["text"]!!.toString().trim('"'))
    }

    @Test
    fun `写口拒收时明说这一步没有写进去`() {
        ledger = RecorderStore.ModelLedger.RefusedRunning
        val r = controller().compile("进蓝牙页", ctx())
        assertFalse(r.published, "写口没收下却报成功=虚报")
        assertEquals(ByokPreflight.Gate.RUNNING, r.gate)
        assertTrue(r.userCopy.contains("not written"))
        assertEquals(0, r.steps, "steps 只报**落账**步数：没落账还报 1 步，屏上就出现了一个像成功的数字")
    }

    @Test
    fun `词表开着却一个节点没采到时必须说破`() {
        val warned = controller().compile("进蓝牙页", ctx(seen = 0, lines = emptyList()))
        assertTrue(warned.userCopy.contains("not a single node was captured"), warned.userCopy)
        val normal = controller().compile("进蓝牙页", ctx(seen = 12))
        assertFalse(normal.userCopy.contains("not a single node was captured"))
        assertEquals(12, normal.context!!.nodesSeen, "条数账要跟着结论走，UI 才有东西可显示")
    }

    @Test
    fun `开关关闭时不追加零节点提醒`() {
        val r = controller().compile("进蓝牙页", ctx(enabled = false, seen = 0, lines = emptyList()))
        assertFalse(r.userCopy.contains("not a single node was captured"), "用户自己关的开关，不该被说成采不到")
    }

    @Test
    fun `词表随意图一起上行 SYSTEM 提示词一字不改`() {
        val speaker = Recorder(okJson)
        controller(transport = speaker).compile("进蓝牙页", ctx(lines = listOf("text=\"Bluetooth\"")))
        assertTrue(speaker.user!!.startsWith("进蓝牙页"))
        assertTrue(speaker.user!!.contains("【当前屏幕可见词表】"))
        assertEquals(CompilerPrompt.SYSTEM, speaker.system, "接线不许改提示词：那是切片 C 锁过的原文")
    }

    @Test
    fun `transport 之外的未知异常不崩链路 按不可达档上屏`() {
        val c = ByokCompileController(
            preflightOf = { Verdict.Ready(plan) },
            compilerOf = { throw IllegalStateException("Keystore 抽风") },
            publish = { ledger },
        )
        val r = c.compile("进蓝牙页", ctx())
        assertFalse(r.published)
        assertEquals(ByokErrorKind.UNREACHABLE, r.errorKind)
        assertEquals("compile", r.stage, "没问到模型这一步炸的，归因不许写成 transport（那是网络档）")
        assertTrue(published.isEmpty())
    }
}
