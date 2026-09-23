package com.anytouch.byok

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 服务地址政策（S3-B 判据 1）：用户手输的 baseUrl 是 BYOK 的第一个入口，也是全仓第一条
 * "外部输入决定网络去向"的路径。fail-closed 口径：政策不过 = 不拨号，且离线也能拒（不做 DNS）。
 */
class BaseUrlPolicyTest {

    private fun accepted(raw: String) = assertIs<BaseUrlPolicy.Ok>(BaseUrlPolicy.check(raw)).accepted

    private fun reason(raw: String) = (BaseUrlPolicy.check(raw) as BaseUrlPolicy.Fail).rejected.reason

    @Test
    fun `https 公网地址通过且回显只有主机名`() {
        val a = accepted("https://integrate.api.nvidia.com/v1")
        assertEquals("https://integrate.api.nvidia.com/v1", a.httpsUrl)
        assertEquals("integrate.api.nvidia.com", a.hostEcho)
    }

    @Test
    fun `放行时必须给出 Key 去向回显 且只含主机名`() {
        val a = accepted("https://gw.mycompany.co.uk:8443/v1")
        val notice = a.keyDestination()
        assertTrue("gw.mycompany.co.uk" in notice, notice)
        assertTrue("/v1" !in notice, "回显只给主机名，路径不进用户眼前：$notice")
    }

    @Test
    fun `尾部斜杠被归一 端口与子路径保留`() {
        assertEquals("https://api.example.com:8443/openai/v1", accepted("https://api.example.com:8443/openai/v1///").httpsUrl)
    }

    @Test
    fun `明文 http 一律拒（Key 不许裸奔）`() {
        assertEquals(BaseUrlPolicy.Reason.BAD_SCHEME, reason("http://api.example.com/v1"))
        assertEquals(BaseUrlPolicy.Reason.BAD_SCHEME, reason("HTTP://api.example.com/v1"))
        assertEquals(BaseUrlPolicy.Reason.NOT_ABSOLUTE, reason("api.example.com/v1"))
        assertEquals(BaseUrlPolicy.Reason.NOT_ABSOLUTE, reason("//api.example.com/v1"))
    }

    @Test
    fun `内嵌凭据 查询参数 片段 一律拒`() {
        assertEquals(BaseUrlPolicy.Reason.HAS_USERINFO, reason("https://user:pass@api.example.com/v1"))
        assertEquals(BaseUrlPolicy.Reason.HAS_QUERY, reason("https://api.example.com/v1?key=abc"))
        assertEquals(BaseUrlPolicy.Reason.HAS_FRAGMENT, reason("https://api.example.com/v1#x"))
    }

    @Test
    fun `本机 内网 链路本地元数据段全拒`() {
        listOf(
            "https://localhost/v1" to BaseUrlPolicy.Reason.PRIVATE_ADDRESS,
            "https://127.0.0.1:8000/v1" to BaseUrlPolicy.Reason.PRIVATE_ADDRESS,
            "https://10.1.2.3/v1" to BaseUrlPolicy.Reason.PRIVATE_ADDRESS,
            "https://192.168.0.7/v1" to BaseUrlPolicy.Reason.PRIVATE_ADDRESS,
            "https://172.16.5.5/v1" to BaseUrlPolicy.Reason.PRIVATE_ADDRESS,
            "https://169.254.169.254/latest/meta-data" to BaseUrlPolicy.Reason.PRIVATE_ADDRESS,
            "https://[::1]/v1" to BaseUrlPolicy.Reason.PRIVATE_ADDRESS,
            "https://[::ffff:127.0.0.1]/v1" to BaseUrlPolicy.Reason.PRIVATE_ADDRESS,
            "https://0.0.0.0/v1" to BaseUrlPolicy.Reason.PRIVATE_ADDRESS,
        ).forEach { (raw, expected) ->
            val got = runCatching { reason(raw) }.getOrNull()
            assertEquals(expected, got, "不该按预期方式处理：$raw")
        }
    }

    @Test
    fun `公网 IP 字面量与 100 段之外公网地址放行`() {
        assertEquals("93.184.216.34", accepted("https://93.184.216.34/v1").hostEcho)
        assertEquals("100.128.0.1", accepted("https://100.128.0.1/v1").hostEcho) // 100.64.0.0/10 之外
        assertEquals("172.15.5.5", accepted("https://172.15.5.5/v1").hostEcho) // 172.16/12 之外
    }

    @Test
    fun `空串与不可解析串拒 且每档都有人话`() {
        assertEquals(BaseUrlPolicy.Reason.BLANK, reason("   "))
        assertEquals(BaseUrlPolicy.Reason.MALFORMED, reason("https://exa mple.com/v1"))
        listOf(
            BaseUrlPolicy.Reason.BLANK,
            BaseUrlPolicy.Reason.NOT_ABSOLUTE,
            BaseUrlPolicy.Reason.BAD_SCHEME,
            BaseUrlPolicy.Reason.NO_HOST,
            BaseUrlPolicy.Reason.HAS_USERINFO,
            BaseUrlPolicy.Reason.PRIVATE_ADDRESS,
            BaseUrlPolicy.Reason.HAS_QUERY,
            BaseUrlPolicy.Reason.HAS_FRAGMENT,
            BaseUrlPolicy.Reason.MALFORMED,
        ).map { BaseUrlPolicy.userCopyFor(it, "x") }.let { copies ->
            assertTrue(copies.all { it.isNotBlank() }, "每档都得有话术")
            assertEquals(copies.size, copies.toSet().size, "九档话术互不雷同")
        }
    }

    @Test
    fun `政策消息里不回显 Key 形态片段`() {
        val out = BaseUrlPolicy.check("https://user:nvapi-secretsecretsecret@api.example.com/v1")
        assertIs<BaseUrlPolicy.Fail>(out)
        assertTrue("secret" !in out.message, "拒因里不得带出地址内嵌的凭据：${out.message}")
    }
}
