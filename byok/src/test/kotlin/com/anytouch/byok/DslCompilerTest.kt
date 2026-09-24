package com.anytouch.byok

import com.anytouch.contracts.ContractJson
import kotlinx.serialization.builtins.ListSerializer
import com.anytouch.contracts.Action
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 编译链 fail-closed 校验层（无网络，FakeTransport 注入）。
 * 军令口径：模型输出永远不直接进执行器——任何坐标残留/白名单外 type/缺 safety 都必须被拒。
 */
class DslCompilerTest {

    private class Fake(val reply: String) : LlmTransport {
        override fun complete(systemPrompt: String, userPrompt: String): String = reply
    }

    private val good = """
        [{"action_id":"cd","type":"click","source":"node","value":{"text":"Connected devices"},"safety":{"viewport_ok":true,"click_enabled":true}},
         {"action_id":"bt","type":"click","source":"node","value":{"text":"Bluetooth"},"safety":{"viewport_ok":true,"click_enabled":true}}]
    """.trimIndent()

    private fun compile(raw: String): CompileResult = DslCompiler(Fake(raw)).compile("任意意图")

    @Test
    fun `合法输出通过校验且解码为契约 Action`() {
        val r = compile(good)
        assertIs<CompileResult.Ok>(r)
        assertEquals(2, r.actions.size)
        assertEquals("click", r.actions[0].type)
        // 产物可直接喂设备注入通道（与 NodeTaskRunner 消费格式一致）
        val redecoded = ContractJson.instance.decodeFromString(ListSerializer(Action.serializer()), r.actionsJson)
        assertEquals(r.actions, redecoded)
    }

    @Test
    fun `markdown 围栏与寒暄包裹的数组被提取`() {
        val wrapped = "好的，编译结果如下：\n```json\n$good\n```\n希望有帮助"
        assertIs<CompileResult.Ok>(compile(wrapped))
    }

    @Test
    fun `坐标字段 target 残留必须拒`() {
        val r = compile("""[{"action_id":"a","type":"click","source":"node","target":{"x":1,"y":2},"value":{"text":"x"},"safety":{"viewport_ok":true,"click_enabled":true}}]""")
        assertIs<CompileResult.Reject>(r)
        assertEquals("validate", r.stage)
        assertTrue(r.detail.contains("target"))
    }

    @Test
    fun `value 内藏坐标键 x 必须拒（ignoreUnknownKeys 静默吞不掉校验层）`() {
        val r = compile("""[{"action_id":"a","type":"click","source":"node","value":{"text":"x","x":100},"safety":{"viewport_ok":true,"click_enabled":true}}]""")
        assertIs<CompileResult.Reject>(r)
        assertEquals("validate", r.stage)
    }

    @Test
    fun `白名单外 type 必须拒并点名授权词表`() {
        // 断言由"含白名单"改为"含真源词表 + 逐条列出支持类型"：S3-F/F2-2 把 reject 话术接到真源上，
        // 原来那句只锁"有一句话"，锁不到"说的是哪几个跑得动"（用户拿到它就不知道该改成什么）。
        val r = compile("""[{"action_id":"a","type":"swipe","source":"node","value":{"text":"x"},"safety":{"viewport_ok":true,"click_enabled":true}}]""")
        assertIs<CompileResult.Reject>(r)
        assertTrue(r.detail.contains("swipe"), "没点名被拒的那一条 type：${r.detail}")
        for (type in executorSupportedActionTypes) {
            assertTrue(r.detail.contains(type), "拒因没列出跑得动的 type=$type：${r.detail}")
        }
        assertTrue(r.detail.contains("ExecutorVocabulary"), "拒因没指向真源：${r.detail}")
    }

    @Test
    fun `safety 未显式放行必须拒`() {
        val r = compile("""[{"action_id":"a","type":"click","source":"node","value":{"text":"x"},"safety":{"viewport_ok":false,"click_enabled":true}}]""")
        assertIs<CompileResult.Reject>(r)
        assertTrue(r.detail.contains("viewport_ok"))
    }

    @Test
    fun `source 非 node 必须拒`() {
        val r = compile("""[{"action_id":"a","type":"click","source":"vlm","value":{"text":"x"},"safety":{"viewport_ok":true,"click_enabled":true}}]""")
        assertIs<CompileResult.Reject>(r)
    }

    @Test
    fun `action_id 重复必须拒`() {
        val r = compile("""[{"action_id":"a","type":"click","source":"node","value":{"text":"x"},"safety":{"viewport_ok":true,"click_enabled":true}},{"action_id":"a","type":"click","source":"node","value":{"text":"y"},"safety":{"viewport_ok":true,"click_enabled":true}}]""")
        assertIs<CompileResult.Reject>(r)
        assertTrue(r.detail.contains("重复"))
    }

    @Test
    fun `空数组走拒绝而非假绿`() {
        val r = compile("[]")
        assertIs<CompileResult.Reject>(r)
        assertTrue(r.detail.contains("空动作数组"))
    }

    @Test
    fun `非 JSON 胡话走 parse 拒绝`() {
        assertIs<CompileResult.Reject>(compile("抱歉，我无法编译这个任务。"))
    }

    @Test
    fun `transport 抛异常走 transport 拒绝不崩调用方`() {
        val r = DslCompiler(object : LlmTransport {
            override fun complete(systemPrompt: String, userPrompt: String): String = throw IllegalStateException("boom")
        }).compile("x")
        assertIs<CompileResult.Reject>(r)
        assertEquals("transport", r.stage)
    }

    @Test
    fun `type_text 缺 input 必须拒`() {
        val r = compile("""[{"action_id":"a","type":"type_text","source":"node","value":{"text":"框"},"safety":{"viewport_ok":true,"click_enabled":true}}]""")
        assertIs<CompileResult.Reject>(r)
        assertTrue(r.detail.contains("input"))
    }
}
