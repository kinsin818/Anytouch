package com.anytouch.byok

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 设备侧传输层（S3-B 判据 4）。
 *
 * JVM 里不建真 socket：假连接注入 [OpenAiCompatTransport.connect]，把"状态码/异常 -> 失败档"
 * 这条映射逐档锁死。真网络那一跑留给切片 E 的真机真 Key（军令：一次真机真 Key，不在 CI 里联网）。
 */
class OpenAiCompatTransportTest {

    private class Fake(
        private val code: Int = 200,
        private val body: String = okBody("hello"),
        private val errorBody: String = "",
        private val failure: (() -> IOException)? = null,
    ) : HttpURLConnection(URI("https://api.example.com/v1/chat/completions").toURL()) {
        val sentHeaders = HashMap<String, String>()
        val sentBody = ByteArrayOutputStream()
        var disconnected = false
        var redirectsAllowed = true

        override fun setRequestProperty(key: String, value: String) {
            sentHeaders[key] = value
        }

        override fun getRequestProperty(key: String): String? = sentHeaders[key]

        override fun getOutputStream(): OutputStream = sentBody

        override fun getResponseCode(): Int {
            failure?.invoke()?.let { throw it }
            return code
        }

        override fun getInputStream(): ByteArrayInputStream = ByteArrayInputStream(body.toByteArray())

        override fun getErrorStream(): ByteArrayInputStream = ByteArrayInputStream(errorBody.toByteArray())

        override fun getInstanceFollowRedirects(): Boolean = redirectsAllowed

        override fun setInstanceFollowRedirects(follow: Boolean) {
            redirectsAllowed = follow
        }

        override fun disconnect() {
            disconnected = true
        }

        override fun connect() = Unit

        override fun usingProxy(): Boolean = false
    }

    private fun completeWith(
        fake: HttpURLConnection,
        config: ByokConfig = ByokConfig("https://api.example.com/v1", "glm-5.3"),
        key: String = TEST_KEY,
        systemPrompt: String = "sys",
        userPrompt: String = "user",
    ): OpenAiCompatTransport = object : OpenAiCompatTransport(config, { key }) {
        override fun connect(url: String): HttpURLConnection = fake
    }.also { it.complete(systemPrompt, userPrompt) }

    @Test
    fun `端点拼接 已有 chat completions 不双拼`() {
        assertEquals(
            "https://api.example.com/v1/chat/completions",
            OpenAiCompatTransport.endpointOf("https://api.example.com/v1"),
        )
        assertEquals(
            "https://api.example.com/v1/chat/completions",
            OpenAiCompatTransport.endpointOf("https://api.example.com/v1/chat/completions"),
        )
    }

    @Test
    fun `请求体只有模型与提示词 Key 不在体内`() {
        val body = OpenAiCompatTransport.requestBody(
            ByokConfig("https://api.example.com/v1", "glm-5.3"),
            "系统提示",
            "用户意图",
        )
        assertTrue("\"model\":\"glm-5.3\"" in body)
        assertTrue("系统提示" in body && "用户意图" in body)
        assertFalse(TEST_KEY in body, "Key 只能走请求头：$body")
    }

    @Test
    fun `成功路径取回 content 且 Key 只出现在头里`() {
        val fake = Fake(body = okBody("[{\"action_id\":\"a\"}]"))
        val t = object : OpenAiCompatTransport(ByokConfig("https://api.example.com/v1", "m"), { TEST_KEY }) {
            override fun connect(url: String): HttpURLConnection = fake
        }
        val out = t.complete("s", "u")
        assertEquals("[{\"action_id\":\"a\"}]", out)
        assertEquals("Bearer $TEST_KEY", fake.sentHeaders["Authorization"])
        assertTrue(TEST_KEY !in fake.sentBody.toString(Charsets.UTF_8))
        assertTrue(fake.disconnected, "连接必须显式关闭，不留悬吊句柄")
        assertFalse(fake.redirectsAllowed, "不跟随跳转：地址去向由政策定，不由服务端定")
    }

    @Test
    fun `非 2xx 按状态码分档且细节脱敏截断`() {
        listOf(
            401 to ByokErrorKind.UNAUTHORIZED,
            403 to ByokErrorKind.UNAUTHORIZED,
            429 to ByokErrorKind.RATE_LIMITED,
            503 to ByokErrorKind.SERVER_ERROR,
            302 to ByokErrorKind.BAD_RESPONSE,
        ).forEach { (code, expected) ->
            val f = assertFailsWith<TransportFailure> {
                completeWith(Fake(code = code, errorBody = """{"error":{"api_key":"$TEST_KEY"}}"""))
            }
            assertEquals(expected, f.kind, "$code 档错配")
            assertTrue(TEST_KEY !in f.safeDetail, "$code 的失败细节泄露 Key：${f.safeDetail}")
            assertTrue(f.safeDetail.length <= 210, "细节必须截断：${f.safeDetail.length}")
        }
    }

    @Test
    fun `IO 异常按可分诊断分档`() {
        val cases = listOf<Pair<() -> IOException, ByokErrorKind>>(
            { SocketTimeoutException("read timed out") } to ByokErrorKind.TIMEOUT,
            { UnknownHostException("no such host") } to ByokErrorKind.UNREACHABLE,
            { java.net.ConnectException("connection refused") } to ByokErrorKind.UNREACHABLE,
            { SSLHandshakeException("cert path failed") } to ByokErrorKind.UNREACHABLE,
            { IOException("broken pipe") } to ByokErrorKind.UNREACHABLE,
        )
        cases.forEach { (thrower, expected) ->
            val f = assertFailsWith<TransportFailure> { completeWith(Fake(failure = thrower)) }
            assertEquals(expected, f.kind)
        }
    }

    @Test
    fun `鉴权门户的 HTML 与缺字段应答都归坏应答 不崩调用方`() {
        listOf(
            "<html><body>请先登录</body></html>",
            """{"choices":[]}""",
            """{"data":"ok"}""",
            "",
        ).forEach { raw ->
            val f = assertFailsWith<TransportFailure> { completeWith(Fake(body = raw)) }
            assertEquals(ByokErrorKind.BAD_RESPONSE, f.kind, "坏应答未归位：$raw")
        }
    }

    @Test
    fun `应答正文成功时不脱敏 打码会破坏待编译的 JSON`() {
        val json = """{"choices":[{"message":{"content":"[{\"action_id\":\"a1234567890123456789012\"}]"}}]}"""
        val fake = Fake(body = json)
        val t = object : OpenAiCompatTransport(ByokConfig("https://api.example.com/v1", "m"), { TEST_KEY }) {
            override fun connect(url: String): HttpURLConnection = fake
        }
        assertEquals("[{\"action_id\":\"a1234567890123456789012\"}]", t.complete("s", "u"))
    }

    private companion object {
        const val TEST_KEY = "nvapi-0123456789abcdef0123456789abcdef"

        fun okBody(content: String): String =
            """{"choices":[{"message":{"content":${quote(content)}}}]}"""

        /** content 是字符串字段，手写转义避免把测试夹具写成第二套 JSON 库。 */
        fun quote(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    }
}
