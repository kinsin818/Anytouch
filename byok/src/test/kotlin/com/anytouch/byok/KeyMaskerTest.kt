package com.anytouch.byok

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Key 脱敏（S3-B 判据 2）：全仓第一条网络路径的兜底闸门。
 * 军令"Key 绝不打印"不能靠自觉——异常消息、HTTP 响应体、模型回显都可能自带密钥，
 * 所以识别面必须是"所有密钥形态"，而不是某一家前缀（原 host 侧只认 nvapi- 就是这条的漏口）。
 */
class KeyMaskerTest {

    private val nvapi = "nvapi-" + "A" .repeat(40)
    private val openai = "sk-" + "bC3dE5fG7hI9jK1lM3nO".repeat(2)

    @Test
    fun `已知字面量 Key 无论形态都从文本里消失`() {
        listOf(nvapi, openai).forEach { key ->
            val masked = KeyMasker.mask("请求失败 authorization: Bearer $key 请重试", key)
            assertFalse(masked.contains(key), "Key 原样残留：$masked")
            assertFalse(masked.contains("Bearer $key"))
            assertTrue(masked.contains(KeyMasker.MASK), "该处应留掩码痕迹：$masked")
        }
    }

    @Test
    fun `没给字面量时也要靠形态认出各家前缀 Key`() {
        // 模拟"响应体里回显了别人的 Key"这种没拿到字面量的场景
        val text = "echoed: $nvapi and also $openai"
        val masked = KeyMasker.mask(text)
        assertFalse(nvapi in masked)
        assertFalse(openai in masked)
    }

    @Test
    fun `json 字段与键值对形态的凭据一并打码`() {
        val text = """{"api_key":"$nvapi","access_token":"abcdefghij1234567890","x":1}"""
        val masked = KeyMasker.mask(text)
        assertFalse(nvapi in masked)
        assertFalse("abcdefghij1234567890" in masked)
    }

    @Test
    fun `普通文本不被误伤 长哈希才被吞`() {
        val plain = "HTTP 401 Unauthorized 请检查 Key"
        assertEquals(plain, KeyMasker.mask(plain))
        val withHash = "trace a9f3b7c2d1e0f4a5b6c7d8e9f0a1b2c3 end"
        assertEquals("trace *** end", KeyMasker.mask(withHash))
    }

    @Test
    fun `展示形态只给尾四位 短串整段打码`() {
        assertEquals(KeyMasker.MASK + "abcd", KeyMasker.maskForDisplay(nvapi + "abcd"))
        assertEquals(KeyMasker.MASK + "wxyz", KeyMasker.maskForDisplay("sk-abcdefghijkwxyz"))
        assertEquals(KeyMasker.MASK, KeyMasker.maskForDisplay("abc123")) // 短于 8 位不给尾巴
        assertNull(KeyMasker.maskForDisplay(null))
        assertNull(KeyMasker.maskForDisplay("   "))
    }

    @Test
    fun `掩码后的串不可能再被拼进日志泄露`() {
        val out = KeyMasker.mask("Authorization: Bearer $nvapi", nvapi) + " " +
            KeyMasker.maskForDisplay(nvapi).orEmpty()
        assertFalse(nvapi in out)
        assertFalse(nvapi.takeLast(12) in out) // 展示只给尾 4 位，第 5 位起不许出现
    }
}
