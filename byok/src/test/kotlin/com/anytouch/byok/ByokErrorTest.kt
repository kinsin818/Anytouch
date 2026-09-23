package com.anytouch.byok

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 失败分档（S3-B 判据 3）：全仓第一条联网路径的"错误必显示"口径。
 * 八档话术必须互不雷同——"网络错误请重试"这种统一文案会把 Key 错、地址错、没网三件事混成一件，
 * 用户第一次配置失败就被误导，海外 BYOK 产品没有第二次机会。
 */
class ByokErrorTest {

    @Test
    fun `八档齐全 每档有话术且互不雷同`() {
        val copies = ByokErrorKind.entries.map { it.userCopy() }
        assertEquals(8, ByokErrorKind.entries.size)
        assertTrue(copies.all { it.isNotBlank() && it.length >= 12 }, "每档都得给人能看懂的一句话")
        assertEquals(copies.size, copies.toSet().size, "八档话术互不雷同")
    }

    @Test
    fun `话术里不出现密钥形态与地址片段`() {
        ByokErrorKind.entries.forEach { kind ->
            val copy = kind.userCopy()
            assertTrue("nvapi" !in copy && "Bearer" !in copy, "$kind 的话术带了凭据字样：$copy")
            assertTrue("://" !in copy, "$kind 的话术带了完整 URL 字面量：$copy")
        }
    }

    @Test
    fun `状态码分档 2xx 放行 401与403 429 5xx 各自归位 其余落坏应答`() {
        assertNull(httpKindOf(200))
        assertNull(httpKindOf(204))
        assertEquals(ByokErrorKind.UNAUTHORIZED, httpKindOf(401))
        assertEquals(ByokErrorKind.UNAUTHORIZED, httpKindOf(403))
        assertEquals(ByokErrorKind.RATE_LIMITED, httpKindOf(429))
        assertEquals(ByokErrorKind.SERVER_ERROR, httpKindOf(500))
        assertEquals(ByokErrorKind.SERVER_ERROR, httpKindOf(503))
        // 重定向与冷门口径：客户端不追跳转（防被服务端导向任意地址），一律坏应答
        assertEquals(ByokErrorKind.BAD_RESPONSE, httpKindOf(302))
        assertEquals(ByokErrorKind.BAD_RESPONSE, httpKindOf(404))
        assertEquals(ByokErrorKind.BAD_RESPONSE, httpKindOf(0))
    }

    @Test
    fun `传输失败的 message 就是安全细节 不藏请求内容`() {
        val f = assertFailsWith<TransportFailure> {
            throw TransportFailure(ByokErrorKind.UNAUTHORIZED, "HTTP 401 ${KeyMasker.mask("Bearer nvapi-topsecrettopsecrettopsecret")}")
        }
        assertEquals(ByokErrorKind.UNAUTHORIZED, f.kind)
        assertEquals(f.safeDetail, f.message)
        assertTrue("topsecret" !in f.message.orEmpty())
    }

    @Test
    fun `编译器各拒绝档都带上机器可读档 UI 不必猜字符串`() {
        val cases = listOf(
            "[]" to ByokErrorKind.EMPTY_ACTIONS,
            """[{"action_id":"a","type":"click","source":"node","target":{"x":1,"y":2},"value":{"text":"x"},"safety":{"viewport_ok":true,"click_enabled":true}}]""" to
                ByokErrorKind.COMPILE_REJECT,
            "抱歉，我做不到" to ByokErrorKind.BAD_RESPONSE,
        )
        cases.forEach { (raw, expected) ->
            val r = DslCompiler(FakeTransport(raw)).compile("任意意图")
            val reject = assertIs<CompileResult.Reject>(r)
            assertEquals(expected, reject.kind, "档位错配：$raw -> ${reject.detail}")
            assertNotNull(reject.kind)
        }
    }

    @Test
    fun `传输层带类型的失败原样透传到编译结果`() {
        val r = DslCompiler(object : LlmTransport {
            override fun complete(systemPrompt: String, userPrompt: String): String =
                throw TransportFailure(ByokErrorKind.RATE_LIMITED, "HTTP 429")
        }).compile("x")
        val reject = assertIs<CompileResult.Reject>(r)
        assertEquals("transport", reject.stage)
        assertEquals(ByokErrorKind.RATE_LIMITED, reject.kind)
    }

    @Test
    fun `非受控异常兜底为不可达而不是无档`() {
        val r = DslCompiler(object : LlmTransport {
            override fun complete(systemPrompt: String, userPrompt: String): String = throw IllegalStateException("boom")
        }).compile("x")
        assertEquals(ByokErrorKind.UNREACHABLE, assertIs<CompileResult.Reject>(r).kind)
    }
}

private class FakeTransport(val raw: String) : LlmTransport {
    override fun complete(systemPrompt: String, userPrompt: String): String = raw
}
