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
        assertTrue(r.userCopy.contains("安全校验"))
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
        assertTrue(r.userCopy.contains("1 步") && r.userCopy.contains("作废旧账 4 步"))
        assertEquals("Bluetooth", published.single().single().value!!["text"]!!.toString().trim('"'))
    }

    @Test
    fun `写口拒收时明说这一步没有写进去`() {
        ledger = RecorderStore.ModelLedger.RefusedRunning
        val r = controller().compile("进蓝牙页", ctx())
        assertFalse(r.published, "写口没收下却报成功=虚报")
        assertEquals(ByokPreflight.Gate.RUNNING, r.gate)
        assertTrue(r.userCopy.contains("没有写进去"))
        assertEquals(0, r.steps, "steps 只报**落账**步数：没落账还报 1 步，屏上就出现了一个像成功的数字")
    }

    @Test
    fun `词表开着却一个节点没采到时必须说破`() {
        val warned = controller().compile("进蓝牙页", ctx(seen = 0, lines = emptyList()))
        assertTrue(warned.userCopy.contains("一个节点都没采到"), warned.userCopy)
        val normal = controller().compile("进蓝牙页", ctx(seen = 12))
        assertFalse(normal.userCopy.contains("一个节点都没采到"))
        assertEquals(12, normal.context!!.nodesSeen, "条数账要跟着结论走，UI 才有东西可显示")
    }

    @Test
    fun `开关关闭时不追加零节点提醒`() {
        val r = controller().compile("进蓝牙页", ctx(enabled = false, seen = 0, lines = emptyList()))
        assertFalse(r.userCopy.contains("一个节点都没采到"), "用户自己关的开关，不该被说成采不到")
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
