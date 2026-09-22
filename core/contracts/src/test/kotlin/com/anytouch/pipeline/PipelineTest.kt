package com.anytouch.pipeline

import com.anytouch.contracts.ActionResult
import com.anytouch.contracts.ContractJson
import com.anytouch.contracts.PipelineFixtures
import kotlinx.serialization.builtins.ListSerializer
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * STAGE-02 Mock 闭环测试。
 * 指令 JSON 全部手写（不用代码生成），以同时钉死 SerialName 字段名的解码正确性。
 */
class PipelineTest {

    private fun decodeResults(json: String): List<ActionResult> =
        ContractJson.instance.decodeFromString(ListSerializer(ActionResult.serializer()), json)

    @Test
    fun `normal chain executes all actions and echoes landed`() {
        val input = """
            [
              {"action_id":"a1","type":"click","target":{"x":10,"y":20},"source":"node",
               "safety":{"viewport_ok":true,"click_enabled":true,"requires_transition":false}},
              {"action_id":"a2","type":"move","target":{"x":30,"y":40},"source":"ocr",
               "safety":{"viewport_ok":true,"click_enabled":true,"requires_transition":true}},
              {"action_id":"a3","type":"wait","source":"cache",
               "safety":{"viewport_ok":true,"click_enabled":true,"requires_transition":false}}
            ]
        """.trimIndent()

        val outcome = ClosedLoop.run(input)

        assertTrue(!outcome.stopped, "normal chain must not stop")
        assertNull(outcome.stopReceipt)
        val results = decodeResults(outcome.resultsJson)
        assertEquals(3, results.size)
        assertEquals(listOf("a1", "a2", "a3"), results.map { it.actionId })
        assertTrue(results.all { it.ok }, "all actions should succeed")
        assertTrue(results.all { it.backend == "mock" })
        assertEquals(10 to 20, results[0].landed?.let { it.x to it.y })
        assertEquals(30 to 40, results[1].landed?.let { it.x to it.y })
        // requires_transition 只作标记，不拦截
        assertTrue(results[1].recovery == null)
    }

    @Test
    fun `safety gate viewport blocks chain with STOP receipt`() {
        val input = """
            [
              {"action_id":"a1","type":"click","target":{"x":1,"y":2},"source":"node",
               "safety":{"viewport_ok":true,"click_enabled":true,"requires_transition":false}},
              {"action_id":"a2","type":"click","target":{"x":3,"y":4},"source":"vlm",
               "safety":{"viewport_ok":false,"click_enabled":true,"requires_transition":false}},
              {"action_id":"a3","type":"click","target":{"x":5,"y":6},"source":"node",
               "safety":{"viewport_ok":true,"click_enabled":true,"requires_transition":false}}
            ]
        """.trimIndent()

        val outcome = ClosedLoop.run(input)

        assertTrue(outcome.stopped, "viewport_ok=false must trigger STOP")
        val results = decodeResults(outcome.resultsJson)
        assertEquals(2, results.size, "third action must not be executed")
        assertTrue(results[0].ok)
        val blocked = results[1]
        assertTrue(!blocked.ok)
        assertEquals("failed", blocked.status)
        val reason = blocked.recovery
        assertTrue(reason != null, "blocked action must carry StopReason")
        assertEquals("SAFETY_GATE_BLOCKED", reason!!.code)
        assertEquals("STOP", reason.severity)
        assertTrue(reason.message.contains("viewport_ok"))
        val receipt = outcome.stopReceipt
        assertTrue(receipt != null)
        assertEquals("stop", receipt!!.type)
        assertEquals("closed_loop", receipt.source)
        assertEquals("stopped", receipt.confirm)
        assertTrue(receipt.commandId.contains("a2"))
    }

    @Test
    fun `safety gate click disabled blocks first action only`() {
        val input = """
            [
              {"action_id":"b1","type":"click","target":{"x":7,"y":8},"source":"node",
               "safety":{"viewport_ok":true,"click_enabled":false,"requires_transition":false}}
            ]
        """.trimIndent()

        val outcome = ClosedLoop.run(input)

        assertTrue(outcome.stopped)
        val results = decodeResults(outcome.resultsJson)
        assertEquals(1, results.size)
        assertTrue(!results[0].ok)
        assertEquals("SAFETY_GATE_BLOCKED", results[0].recovery?.code)
        // 被拦截动作不得回显落点
        assertNull(results[0].landed)
    }

    @Test
    fun `empty input yields empty result list without stopping`() {
        val outcome = ClosedLoop.run("[]")
        assertTrue(!outcome.stopped)
        assertEquals("[]", outcome.resultsJson)
    }

    @Test
    fun `dirty input fails loudly instead of faking success`() {
        // 非法 JSON 语法
        assertFailsWith<IllegalArgumentException>("truncated json") {
            ClosedLoop.run("""[{"action_id":"a1"""")
        }
        // 合法 JSON 但缺必填字段（action_id/type/source）
        assertFailsWith<IllegalArgumentException>("missing required fields") {
            ClosedLoop.run("""[{"type":"click"}]""")
        }
        // 合法 JSON 但顶层不是数组
        assertFailsWith<IllegalArgumentException>("not an array") {
            ClosedLoop.run("""{"action_id":"a1"}""")
        }
        // 合法 JSON 但字段类型错误
        assertFailsWith<IllegalArgumentException>("wrong field type") {
            ClosedLoop.run(
                """[{"action_id":"a1","type":"click","source":"node","target":"not-a-point"}]"""
            )
        }
    }

    @Test
    fun `mock backend directly honors gates and echo`() {
        val backend = MockBackend()
        val ok = backend.act(PipelineFixtures.action(actionId = "x1", viewportOk = true, clickEnabled = true))
        assertTrue(ok.ok)
        val blocked = backend.act(PipelineFixtures.action(actionId = "x2", viewportOk = true, clickEnabled = false))
        assertTrue(!blocked.ok)
        assertEquals("SAFETY_GATE_BLOCKED", blocked.recovery?.code)
    }
}
