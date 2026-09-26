package com.anytouch.byok

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 激活传输层的 JVM 锁（S5-g 判据 9 的"形状与口径"那一半；真连接归设备面判据 1/4/8）。
 *
 * 这里能钉住的三件事，恰好都是"上线以后改一个字就悄悄失效"的那类：
 * 1. **fail-closed 的映射表**：所有"没答上"的形态（429/5xx/HTML/空体/缺字段/认不出的 reason）
 *    必须一律落 [ActivationOutcome.UNREACHABLE]，一条都不许漏进 ALLOWED——
 *    漏一条就是"服务器根本没点头却解了锁"，整套额度锁当场归零；
 * 2. **设备标识的形状**：16 位小写十六进制、确定性、空白输入一律 null（宁可拒激活也不共用一格额度）；
 * 3. **指纹固定的下限**：短于 16 位、含非十六进制字符的"pin"必须不接受——
 *    否则这条锁只是装饰（4 位前缀 2^16 次就能凑出来）。
 */
class ActivationTransportTest {

    private val code = "ANY-PRO9-FAN8-B1CE"
    private val hash = "0123456789abcdef"

    // ---- 1. fail-closed 映射 ----

    @Test
    fun `ok 为真即通过 额度数字缺席也不否决（服务器认的是 ok 那一格）`() {
        val full = ActivationTransport.verdictOf(200, """{"ok":true,"seats_used":1,"seats_total":2}""")
        assertEquals(ActivationOutcome.ALLOWED, full.outcome)
        assertEquals(1, full.seatsUsed)
        assertEquals(2, full.seatsTotal)

        val bare = ActivationTransport.verdictOf(200, """{"ok":true}""")
        assertEquals(ActivationOutcome.ALLOWED, bare.outcome, "缺 seats 字段就把已通过的激活判失败=把已付款的人关在门外")
        assertNull(bare.seatsUsed)
    }

    @Test
    fun `两种服务器拒绝各归各档 不许混成一档`() {
        // 买家听得懂的两件事完全不同："这码不对" 与 "这码被两台机器用满了"
        assertEquals(
            ActivationOutcome.INVALID,
            ActivationTransport.verdictOf(200, """{"ok":false,"reason":"invalid"}""").outcome,
        )
        assertEquals(
            ActivationOutcome.SEATS_FULL,
            ActivationTransport.verdictOf(200, """{"ok":false,"reason":"seats_full","seats_total":2}""").outcome,
        )
    }

    @Test
    fun `凡是没答上的形态一律 UNREACHABLE 其中没有任何一条能通过`() {
        val nasty = listOf(
            // 认不出的 reason：不猜它想说什么，fail-closed
            200 to """{"ok":false,"reason":"oops"}""",
            200 to """{"ok":false}""",
            200 to """{"ok":null}""",
            200 to """{"result":"true"}""",
            200 to "",
            200 to "not json at all",
            200 to """[{"ok":true}]""",
            // 网关/反代与限流页最爱回的东西
            429 to """{"ok":false,"reason":"rate_limited"}""",
            403 to "<html>Forbidden</html>",
            404 to "Not Found",
            500 to """{"ok":true}""",
            502 to "<html>Bad Gateway</html>",
        )
        nasty.forEach { (status, body) ->
            val verdict = ActivationTransport.verdictOf(status, body)
            assertEquals(
                ActivationOutcome.UNREACHABLE,
                verdict.outcome,
                "HTTP $status body=[$body] 竟然没落 UNREACHABLE：fail-closed 漏了一条",
            )
        }
    }

    @Test
    fun `5xx 里那一条带 ok 字样的响应也不作数（远程 500 不是解锁凭据）`() {
        assertEquals(
            ActivationOutcome.UNREACHABLE,
            ActivationTransport.verdictOf(500, """{"ok":true,"seats_used":1,"seats_total":2}""").outcome,
            "只有 2xx 才有资格谈通过——否则一次网关故障就能白解锁",
        )
    }

    @Test
    fun `safeDetail 只带档位与状态 完整码与完整设备标识都不在里面`() {
        // 钉的是合成口 checkOf（verify 真走的那一条）：只测 verdictOf 就等于没测"出门前擦没擦"
        listOf(
            200 to """{"ok":false,"reason":"invalid"}""",
            429 to """{"echo":"$code and $hash"}""",
            500 to "$code $hash",
            200 to """{"ok":true,"note":"$code/$hash"}""",
        ).forEach { (status, body) ->
            val check = ActivationTransport.checkOf(status, body, code, hash)
            assertFalse(check.safeDetail.contains(code), "整枚能解锁的串进了日志片段：${check.safeDetail}")
            assertFalse(check.safeDetail.contains(hash), "完整设备标识进了日志片段：${check.safeDetail}")
        }
        // 反向自证：擦除这一步确实动过手，不是"响应里本来就没有"的假绿
        assertTrue(ActivationTransport.redact("echo $code", code, hash).contains("<code>"))
        assertEquals("abc", ActivationTransport.redact("abc", "", ""), "空敏感串不许把整段日志切成字")
    }

    // ---- 2. 设备标识 ----

    @Test
    fun `设备标识是 16 位小写十六进制 且同一输入永远同一个`() {
        val a = ActivationTransport.deviceHashOf("c9a0e1f2b3d4e5f6")
        val b = ActivationTransport.deviceHashOf("c9a0e1f2b3d4e5f6")
        assertEquals(a, b, "同一台机器每次算出不同标识=每次重装都白占一格额度")
        val produced = a!!
        assertEquals(16, produced.length, "标识长度必须与服务端 HASH_RE 的取值口径一致")
        assertTrue(produced.all { it in '0'..'9' || it in 'a'..'f' }, "标识必须是小写十六进制：$produced")
        assertFalse(produced == ActivationTransport.deviceHashOf("ffffffffffffffff"), "不同输入撞出同一枚标识")
    }

    @Test
    fun `空 空白 缺失的 ANDROID_ID 一律给不出标识 绝不退化成公共哈希`() {
        listOf(null, "", "   ", "\t\n").forEach { raw ->
            assertNull(
                ActivationTransport.deviceHashOf(raw),
                "[$raw] 竟然出了标识：把所有读不到 ID 的机器哈希成同一个值=别人替他们占额度",
            )
        }
    }

    // ---- 3. 指纹固定的下限 ----

    @Test
    fun `仓内那枚固定值是 16 位小写十六进制 且端点过得住地址政策`() {
        assertEquals(16, ActivationTransport.SPKI_PIN.length, "固定值截断位数由 MIN_PIN_HEX 钉，不许手滑改短")
        assertTrue(ActivationTransport.SPKI_PIN.all { it in '0'..'9' || it in 'a'..'f' })
        assertTrue(
            BaseUrlPolicy.check(ActivationTransport.ENDPOINT) is BaseUrlPolicy.Ok,
            "激活端点必须走与 BYOK 同一道地址政策（https-only、拒本机/内网/元数据段）",
        )
        assertTrue(ActivationTransport.ENDPOINT.endsWith("/api/activate"), "端点路径写错就是打到别人的服务上")
    }

    @Test
    fun `指纹接受面 完整前缀才认 短于下限与非十六进制一律不认`() {
        val actual = "3d7146a142f8d08fd507599eb426f8368224ed4e4414f0a2c0f6365bca18afa6"
        assertTrue(ActivationTransport.pinAccepts(actual, "3d7146a142f8d08f"))
        assertTrue(ActivationTransport.pinAccepts(actual, "3D7146A142F8D08F"), "大小写手滑不该把这条锁变成断线")
        assertFalse(ActivationTransport.pinAccepts(actual, "0000000000000000"), "别的证书必须断开（判据 8 的 JVM 半边）")
        assertFalse(ActivationTransport.pinAccepts(actual, "3d7146a142f8d08e"), "最后一位不同也必须断开")
        assertFalse(ActivationTransport.pinAccepts(actual, "3d71"), "4 位前缀 2^16 次就能凑=装饰，不是固定")
        assertFalse(ActivationTransport.pinAccepts(actual, "zzzzzzzzzzzzzzzz"), "非十六进制的 pin 不许被当成匹配")
        assertFalse(ActivationTransport.pinAccepts(actual, ""), "空 pin 必须拒")
    }

    @Test
    fun `超时口径按军令钉死 连接读取各不超八秒 整链不超十秒`() {
        assertTrue(ActivationTransport.CONNECT_TIMEOUT_MS <= 8_000, "连接超时越过 8 秒=买家对着转圈的屏幕等")
        assertTrue(ActivationTransport.READ_TIMEOUT_MS <= 8_000)
        assertTrue(ActivationTransport.OVERALL_BUDGET_MS <= 10_000)
        assertTrue(
            ActivationTransport.OVERALL_BUDGET_MS >= ActivationTransport.CONNECT_TIMEOUT_MS,
            "整链预算比连接超时还短=还没拨号就先超时，那是永远发不出去的死配置",
        )
    }

    @Test
    fun `请求体只带那两枚字段 码不进 URL 也不带任何多余字段`() {
        val body = ActivationTransport.requestBody(code, hash)
        assertTrue(body.contains("\"code\":\"$code\""), body)
        assertTrue(body.contains("\"device_hash\":\"$hash\""), body)
        assertEquals(2, body.split(':').size - 1, "请求体字段数变了=与服务器接口漂了：$body")
    }
}
