package com.anytouch.byok

import com.anytouch.contracts.ContractJson
import java.io.IOException
import java.io.InputStream
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSession
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 服务器对一次激活请求的四种回答（S5-g 军令 §1 + 老板裁 1/裁 2）。
 *
 * 只有 [ALLOWED] 一档能让 APP 写本机激活标志，其余三档一律**不解锁**——
 * 这一条不是文档措辞，是 [ActivationStore.submit] 那侧的判据，由 JVM 用例逐条锁住。
 */
enum class ActivationOutcome {
    /** 码在白名单里，且本机占得住一格（或本来就占着那一格）。 */
    ALLOWED,

    /** 码不在白名单里（含格式合法但从没发出去过的码）。 */
    INVALID,

    /** 这枚码已经绑满 [ActivationCheck.seatsTotal] 台，且本机不在列表里。 */
    SEATS_FULL,

    /** 连不上 / 超时 / 证书指纹对不上 / 回答不是预期形状。**fail-closed：这一档等于拒绝**，绝不当通过处理。 */
    UNREACHABLE,
}

/**
 * 一次校验的结论。[safeDetail] 是**唯一**允许进日志的附加信息：它由本模块拼装，
 * 只含状态码、拒因档名与脱敏后的片段，永不含完整激活码与完整设备标识（红线 H 的精神面）。
 */
data class ActivationCheck(
    val outcome: ActivationOutcome,
    val seatsUsed: Int? = null,
    val seatsTotal: Int? = null,
    val safeDetail: String = "",
)

/**
 * 激活校验的传输层——**全产品唯一的第二处联网代码**（第一处是创建期的 BYOK 编译，[OpenAiCompatTransport]）。
 *
 * 为什么住在 `:byok`：红线 F 把"能联网的代码"关在 byok 与 tools 两处，红线 G 又禁
 * `platform/`、`executor/` 等执行路径 import 本模块。于是激活的联网步只许挂在 UI 面
 * （MainActivity），落盘与判据仍留在 `app/activation/` 那一侧——"执行期零网络"这条红线
 * 因此一字未动：只有用户在自己手机上按「Unlock」这一件事需要网。
 *
 * 三件硬约束：
 * 1) **SPKI 指纹固定**（裁 2）：证书是自签的、没有可公共验证的签发链，也没有能匹配的主机名，
 *    所以"信谁"这件事由指纹承担。信任链校验没有被关掉，而是**换了判据**：
 *    [PinningTrustManager] 与 [PinningHostnameVerifier] 各自独立比一次指纹，任何一处对不上就断开。
 *    指纹本身公开在公仓里无妨——它不是秘密，公开的只是"信任哪一枚证书"。
 * 2) **fail-closed**（自钉 2）：任何异常形态（DNS、连接、TLS、429、5xx、答非所问）一律落
 *    [ActivationOutcome.UNREACHABLE]，屏上给那句"需要联网"的归因话术，绝不写成"码无效"误导买家；
 * 3) **零自动重试、零后台重发**：整链预算 [OVERALL_BUDGET_MS]，超时即放弃，重发由用户手按。
 *    开机、进首页、每次点击都不主动复核（已激活的机器离线照用，判据 5）。
 */
object ActivationTransport {

    /** 激活端点：只此一个地址，且必须过 [BaseUrlPolicy]（https-only、拒本机/内网/元数据段）。 */
    const val ENDPOINT = "https://45.32.63.177:8443/api/activate"

    /**
     * 服务器证书 **SPKI SHA-256** 的前 16 位十六进制（全值 `3d7146a142f8d08fd507599eb426f8368224ed4e4414f0a2c0f6365bca18afa6`，
     * 证书 CN=anytouch-activate、SAN 含 IP:45.32.63.177，有效至 2036-09-22）。
     *
     * 口径自钉（老板可覆）：**截断到 16 位十六进制**而非军令字面的"设备 MD5"——
     * 不把 MD5 引进这条含安全审计的链路（`orders/RULINGS-20260922.md` S5-R14）。
     * 16 位＝64 bit，凑一枚前缀匹配的公钥要 2^64 次尝试，够用；再短就不够了，于是有 [MIN_PIN_HEX]。
     */
    const val SPKI_PIN = "3d7146a142f8d08f"

    /** 指纹下限：短于 16 位十六进制的"固定"是装饰（4 位只有 65536 个候选），当场拒开这个连接。 */
    const val MIN_PIN_HEX = 16

    const val CONNECT_TIMEOUT_MS = 8_000
    const val READ_TIMEOUT_MS = 8_000

    /** 整链预算：连接+读取各自不超过 8 秒，且两者加起来过了 10 秒就不再等下去。 */
    const val OVERALL_BUDGET_MS = 10_000

    /** 响应体读取上限：再多一个字节的响应都不要（挡住"用一个巨型响应换一次内存"）。 */
    private const val MAX_RESPONSE_BYTES = 8192

    /**
     * 打一次激活请求。**同步**函数：调用方负责把它挪出 UI 线程（MainActivity 那条 `anytouch-activate` 串行线）。
     *
     * @param code 用户输入的 18 位激活码（本地格式判据已过或未过都由调用方决定要不要来这一步）
     * @param deviceHash [deviceHashOf] 出来的 16 位十六进制
     */
    fun verify(code: String, deviceHash: String, nowMs: () -> Long = System::currentTimeMillis): ActivationCheck {
        val deadline = nowMs() + OVERALL_BUDGET_MS
        val body = requestBody(code, deviceHash)
        val attempt = try {
            val conn = open(ENDPOINT)
            val connectBudget = remaining(deadline, nowMs, CONNECT_TIMEOUT_MS)
            if (connectBudget <= 0L) {
                return ActivationCheck(ActivationOutcome.UNREACHABLE, safeDetail = "budget spent before dial")
            }
            conn.requestMethod = "POST"
            conn.instanceFollowRedirects = false
            conn.connectTimeout = connectBudget.toInt()
            conn.readTimeout = remaining(deadline, nowMs, READ_TIMEOUT_MS).coerceAtLeast(1L).toInt()
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "application/json")
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val status = conn.responseCode
            val stream = if (status / 100 == 2) conn.inputStream else conn.errorStream
            val raw = stream?.use { readCapped(it) }.orEmpty()
            conn.disconnect()
            status to raw
        } catch (e: SSLException) {
            // 指纹对不上时 JSSE 把 CertificateException 包进握手异常——这一档就是判据 8 的反向锁形态
            return ActivationCheck(ActivationOutcome.UNREACHABLE, safeDetail = detail("TLS pin check failed", e))
        } catch (e: SocketTimeoutException) {
            return ActivationCheck(ActivationOutcome.UNREACHABLE, safeDetail = detail("timed out", e))
        } catch (e: UnknownHostException) {
            return ActivationCheck(ActivationOutcome.UNREACHABLE, safeDetail = detail("DNS lookup failed", e))
        } catch (e: IOException) {
            return ActivationCheck(ActivationOutcome.UNREACHABLE, safeDetail = detail("IO failure", e))
        }
        return checkOf(attempt.first, attempt.second, code, deviceHash)
    }

    /** 独立出来给单测注入假连接：JVM 里不建 socket，真链路由设备面（判据 1/4/8）负责。 */
    internal fun open(url: String): HttpsURLConnection {
        val policy = BaseUrlPolicy.check(url)
        require(policy is BaseUrlPolicy.Ok) { "activation endpoint failed the address policy: $url" }
        val conn = URI(url).toURL().openConnection() as HttpsURLConnection
        applyPinning(conn, SPKI_PIN)
        return conn
    }

    /**
     * 映射 + 抹敏的**合成口**：[verify] 真走的就是这一条，单测也钉这一条。
     * 分成两个函数各测各的会漏掉真正要紧的那件事——"日志片段出门之前有没有被擦过"。
     */
    fun checkOf(status: Int, rawBody: String, code: String, deviceHash: String): ActivationCheck {
        val verdict = verdictOf(status, rawBody)
        if (verdict.safeDetail.isEmpty()) return verdict
        return verdict.copy(safeDetail = redact(verdict.safeDetail, code, deviceHash))
    }

    /**
     * 纯映射：HTTP 状态 + 响应体 → 四档结论。**这是整套 fail-closed 语义的落点**，所以单独成函数锁在 JVM：
     * - 2xx 且 `ok:true` → ALLOWED（`seats_used`/`seats_total` 缺席也不否决——服务器认的是 ok 这一格）；
     * - 2xx 且 `ok:false` → 照 `reason` 分 INVALID / SEATS_FULL，认不出的 reason 落 UNREACHABLE（不猜）；
     * - 其余（429/403/404/5xx/非 JSON）→ UNREACHABLE。
     * 任何一条路都不会把"没答上"算成"通过"。
     */
    fun verdictOf(status: Int, rawBody: String): ActivationCheck {
        if (status / 100 != 2) {
            return ActivationCheck(
                ActivationOutcome.UNREACHABLE,
                safeDetail = "HTTP $status ${KeyMasker.mask(rawBody).take(120)}".trim(),
            )
        }
        val obj = try {
            ContractJson.instance.parseToJsonElement(rawBody).jsonObject
        } catch (e: Exception) {
            return ActivationCheck(ActivationOutcome.UNREACHABLE, safeDetail = "response is not a JSON object")
        }
        val ok = obj["ok"]?.jsonPrimitive?.booleanOrNull
        if (ok == null) {
            return ActivationCheck(ActivationOutcome.UNREACHABLE, safeDetail = "response has no boolean ok field")
        }
        val used = obj["seats_used"]?.jsonPrimitive?.intOrNull
        val total = obj["seats_total"]?.jsonPrimitive?.intOrNull
        if (ok) return ActivationCheck(ActivationOutcome.ALLOWED, used, total, "HTTP $status ok")
        return when (obj["reason"]?.jsonPrimitive?.content) {
            "invalid" -> ActivationCheck(ActivationOutcome.INVALID, used, total, "reason=invalid")
            "seats_full" -> ActivationCheck(ActivationOutcome.SEATS_FULL, used, total, "reason=seats_full")
            else -> ActivationCheck(
                ActivationOutcome.UNREACHABLE,
                used,
                total,
                "refused with an unrecognized reason ${KeyMasker.mask(rawBody).take(120)}".trim(),
            )
        }
    }

    /** 请求体：只有这两枚字段，且码不进 URL（URL 会进服务器访问日志与中间件日志）。 */
    fun requestBody(code: String, deviceHash: String): String = buildJsonObject {
        put("code", code)
        put("device_hash", deviceHash)
    }.toString()

    /**
     * 设备标识：`ANDROID_ID` → SHA-256 → **前 16 位十六进制**（裁 1）。
     *
     * 三件事按字面登记，不洗：① 军令那句"设备硬件 MD5"在 Android 10+ 的第三方应用里**根本取不到**
     * （系统限制，不是本窗挑活），故按裁 1 改 ANDROID_ID 哈希；② 它**不是硬件绑定**——恢复出厂、
     * 换签名重装都会变，所以对外文案只准写"每台设备一个标识，重装或刷机可能需要重新激活"；
     * ③ 空白 ANDROID_ID 一律返回 null：若把空串哈希成一个固定值，全市场没 ID 的机器就会共用一格额度，
     * 那是"没买过的人替别人占位"，比拒绝激活严重。
     */
    fun deviceHashOf(androidId: String?): String? {
        val raw = androidId?.trim().orEmpty()
        if (raw.isEmpty()) return null
        return sha256Hex(raw.toByteArray(Charsets.UTF_8)).substring(0, 16)
    }

    /** 证书公钥（SubjectPublicKeyInfo DER）的 SHA-256 全值，小写十六进制。 */
    fun spkiPinOf(certificate: X509Certificate): String = sha256Hex(certificate.publicKey.encoded)

    /**
     * 指纹是否接受：actual 必须是本模块自己产出的小写 64 位十六进制，expected 短于 [MIN_PIN_HEX]
     * 或含非十六进制字符一律**不接受**（宁可断线，也不让一条手滑写短的常量把固定变成摆设）。
     */
    fun pinAccepts(actual: String, expected: String): Boolean {
        val pin = expected.lowercase()
        if (pin.length < MIN_PIN_HEX) return false
        if (!pin.all { it in '0'..'9' || it in 'a'..'f' }) return false
        return actual.startsWith(pin)
    }

    /** 把"只信这一枚证书"装到连接上：信任管理器与主机名校验器**各判一次**，不是二选一。 */
    private fun applyPinning(conn: HttpsURLConnection, expectedPin: String) {
        val context = SSLContext.getInstance("TLS")
        context.init(null, arrayOf<TrustManager>(PinningTrustManager(expectedPin)), SecureRandom())
        conn.sslSocketFactory = context.socketFactory
        conn.hostnameVerifier = PinningHostnameVerifier(expectedPin)
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun remaining(deadline: Long, nowMs: () -> Long, capMs: Int): Long =
        (deadline - nowMs()).coerceAtMost(capMs.toLong())

    private fun readCapped(stream: InputStream): String {
        val bytes = stream.readNBytes(MAX_RESPONSE_BYTES)
        return String(bytes, Charsets.UTF_8)
    }

    /** 异常消息本身可能带 URL：先脱敏再截断（与 [OpenAiCompatTransport] 同一条律）。 */
    private fun detail(label: String, e: Exception): String =
        "$label ${KeyMasker.mask(e.message.orEmpty()).take(120)}".trim()

    /**
     * 日志片段的最后一道闸：本模块知道的两枚敏感串——完整激活码、完整设备标识——
     * 无论出现在谁的响应体里，都在出门前抹掉（判据 7 的另一半：屏上与日志只准回显尾四位）。
     * 空串不参与替换：`"".replace` 会把整段文本切成字，那是自家把日志打烂的形态。
     */
    internal fun redact(text: String, code: String, deviceHash: String): String {
        var out = text
        if (code.isNotEmpty()) out = out.replace(code, "<code>")
        if (deviceHash.isNotEmpty()) out = out.replace(deviceHash, "<device>")
        return out
    }

    private class PinningTrustManager(private val expectedPin: String) : X509TrustManager {
        /** 本 APP 只当客户端，服务端不做双向认证；没有东西需要验客户端证书。 */
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            val leaf = chain.firstOrNull() ?: throw CertificateException("empty certificate chain")
            val actual = spkiPinOf(leaf)
            if (!pinAccepts(actual, expectedPin)) {
                throw CertificateException(
                    "SPKI pin mismatch: got ${actual.take(MIN_PIN_HEX)}…, expected prefix ${expectedPin.take(MIN_PIN_HEX)}…",
                )
            }
        }

        /** 不带系统根证书进来：白名单为空＝除固定指纹那枚之外谁的证书都不认（自签本就不在系统根里）。 */
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    /**
     * 自签证书没有能匹配 `45.32.63.177` 的 DNS 名，默认主机名校验必然失败。
     * 这里**不是**"返回 true 跳过校验"：它独立再比一次对端公钥指纹，与 [PinningTrustManager] 同判据。
     * 两处任一不同意指纹都握不上手——这就是裁 2 要的"由指纹承担主机名那一格"，不是把校验关掉。
     */
    private class PinningHostnameVerifier(private val expectedPin: String) : javax.net.ssl.HostnameVerifier {
        override fun verify(hostname: String, session: SSLSession): Boolean = try {
            val leaf = session.peerCertificates.filterIsInstance<X509Certificate>().firstOrNull()
                ?: return false
            pinAccepts(spkiPinOf(leaf), expectedPin)
        } catch (e: SSLPeerUnverifiedException) {
            false
        } catch (e: RuntimeException) {
            false
        }
    }
}
