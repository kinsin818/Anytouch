package com.anytouch.byok

import com.anytouch.contracts.ContractJson
import java.io.IOException
import java.net.HttpURLConnection
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * 一次 BYOK 请求的配置（不含 Key：Key 经 [OpenAiCompatTransport.keyProvider] 每次现取，
 * 不落进这个可被 toString 的数据类——data class 的默认 toString 就是一条泄露路径）。
 */
data class ByokConfig(
    val baseUrl: String,
    val model: String,
    val temperature: Double = 0.0,
    val maxTokens: Int = 2000,
    val connectTimeoutMs: Int = 20_000,
    val readTimeoutMs: Int = 120_000,
)

/**
 * 设备侧 OpenAI 兼容传输层（S3-B：BYOK 接进 APP 的那一次网络调用）。
 *
 * 用 [HttpURLConnection] 而不是 java.net.http.HttpClient——后者要 API 33+ 且 Android 上不可用；
 * 全仓零第三方网络依赖（不引 okhttp/retrofit）也是纪律：多一个依赖就多一条未知出口。
 *
 * 三条硬约束：
 * 1) 地址必须已过 [BaseUrlPolicy]（https-only、拒本机/内网/元数据段）——本类不重复判，
 *    但把"政策与拨号分离"写在签名上：喂进来的 baseUrl 由调用方保证；
 * 2) Key 只进请求头，绝不进 URL、绝不进任何消息文本；
 * 3) 任何失败细节（响应体、异常消息）先过 [KeyMasker] 再拼进 [TransportFailure.safeDetail]，
 *    截断到 200 字符。成功路径**不脱敏**——那是待编译的 Action JSON，打码会破坏判据。
 */
open class OpenAiCompatTransport(
    private val config: ByokConfig,
    private val keyProvider: () -> String,
) : LlmTransport {

    override fun complete(systemPrompt: String, userPrompt: String): String {
        val body = requestBody(config, systemPrompt, userPrompt)
        val url = endpointOf(config.baseUrl)
        val attempt = try {
            val conn = connect(url)
            conn.requestMethod = "POST"
            conn.instanceFollowRedirects = false
            conn.connectTimeout = config.connectTimeoutMs
            conn.readTimeout = config.readTimeoutMs
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("Authorization", "Bearer ${keyProvider()}")
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code / 100 == 2) conn.inputStream else conn.errorStream
            val raw = stream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            conn.disconnect()
            code to raw
        } catch (e: SSLException) {
            throw TransportFailure(ByokErrorKind.UNREACHABLE, detail(e, "TLS 握手失败（证书未通过）"))
        } catch (e: SocketTimeoutException) {
            throw TransportFailure(ByokErrorKind.TIMEOUT, detail(e, "超时"))
        } catch (e: UnknownHostException) {
            throw TransportFailure(ByokErrorKind.UNREACHABLE, detail(e, "域名解析失败"))
        } catch (e: ConnectException) {
            throw TransportFailure(ByokErrorKind.UNREACHABLE, detail(e, "连接被拒"))
        } catch (e: IOException) {
            throw TransportFailure(ByokErrorKind.UNREACHABLE, detail(e, "IO 失败"))
        }
        val (code, raw) = attempt
        httpKindOf(code)?.let { kind ->
            throw TransportFailure(kind, "HTTP $code ${KeyMasker.mask(raw).take(200)}")
        }
        return contentOf(raw)
    }

    /** 独立出来给单测注入假连接：真网络由切片 E 的真机真 Key 那一跑负责，不在 JVM 里建 socket。 */
    protected open fun connect(url: String): HttpURLConnection =
        URI(url).toURL().openConnection() as HttpURLConnection

    companion object {

        /** baseUrl 已由政策去过尾斜杠；用户直接把整条 /chat/completions 贴进来也不双拼。 */
        fun endpointOf(baseUrl: String): String {
            val trimmed = baseUrl.trim().trimEnd('/')
            return if (trimmed.endsWith(BaseUrlPolicy.CHAT_COMPLETIONS_PATH)) trimmed
            else trimmed + BaseUrlPolicy.CHAT_COMPLETIONS_PATH
        }

        fun requestBody(config: ByokConfig, systemPrompt: String, userPrompt: String): String =
            buildJsonObject {
                put("model", config.model)
                put("temperature", config.temperature)
                put("max_tokens", config.maxTokens)
                put("stream", false)
                putJsonArray("messages") {
                    add(buildJsonObject { put("role", "system"); put("content", systemPrompt) })
                    add(buildJsonObject { put("role", "user"); put("content", userPrompt) })
                }
            }.toString()

        /** choices[0].message.content；结构不符一律 BAD_RESPONSE（鉴权门户的 HTML 就走这里）。 */
        fun contentOf(rawBody: String): String {
            val content = try {
                ContractJson.instance.parseToJsonElement(rawBody).jsonObject["choices"]?.jsonArray
                    ?.firstOrNull()?.jsonObject?.get("message")?.jsonObject?.get("content")?.jsonPrimitive
            } catch (e: Exception) {
                null
            }
            val text = content?.content
            if (text.isNullOrBlank()) {
                throw TransportFailure(
                    ByokErrorKind.BAD_RESPONSE,
                    "应答缺 choices[0].message.content: ${KeyMasker.mask(rawBody).take(200)}",
                )
            }
            return text
        }

        /** 异常消息本身可能带 URL（含 userinfo）或 Key：先脱敏再截断。 */
        private fun detail(e: Exception, label: String): String =
            "$label ${KeyMasker.mask(e.message.orEmpty()).take(200)}".trim()
    }
}
