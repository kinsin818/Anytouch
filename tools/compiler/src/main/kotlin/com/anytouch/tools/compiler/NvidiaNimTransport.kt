package com.anytouch.tools.compiler

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * NVIDIA NIM OpenAI 兼容端点传输层 —— **仅 host 侧 T2 测量工具使用**。
 * 测试通道豁免留痕：设备产品路径（core/ 与 app/src/main）零网络红线不变，
 * 本文件的网络调用不参与任何 APK。Key 只从环境变量/参数进入内存，绝不落盘、绝不打印。
 */
class NvidiaNimTransport(
    private val apiKey: String,
    private val model: String,
    private val baseUrl: String = DEFAULT_BASE,
    private val timeoutSec: Long = 120,
    private val temperature: Double = 0.0,
    private val maxTokens: Int = 2000,
) : LlmTransport {

    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(20))
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    override fun complete(systemPrompt: String, userPrompt: String): String {
        val body = buildJsonObject {
            put("model", model)
            put("temperature", temperature)
            put("max_tokens", maxTokens)
            putJsonArray("messages") {
                add(buildJsonObject { put("role", "system"); put("content", systemPrompt) })
                add(buildJsonObject { put("role", "user"); put("content", userPrompt) })
            }
        }.toString()
        val request = HttpRequest.newBuilder(URI("$baseUrl/chat/completions"))
            .timeout(Duration.ofSeconds(timeoutSec))
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() / 100 != 2) {
            // 只透出状态码与脱敏后的响应体前 200 字符——兜底防任何 nvapi-* 形态串进日志
            val masked = response.body().replace(Regex("nvapi-[A-Za-z0-9_-]+"), "nvapi-***")
            error("HTTP ${response.statusCode()}: ${masked.take(200)}")
        }
        val root = json.parseToJsonElement(response.body()).jsonObject
        return root["choices"]!!.jsonArray[0].jsonObject["message"]!!.jsonObject["content"]!!.jsonPrimitive.content
    }

    companion object {
        const val DEFAULT_BASE = "https://integrate.api.nvidia.com/v1"
    }
}
