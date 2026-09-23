package com.anytouch.byok

import com.anytouch.contracts.Action
import com.anytouch.contracts.ActionType
import com.anytouch.contracts.ContractJson
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 创建期编译器（S3 BYOK 的判据核心，**一份两处用**：设备 `:app` 与 host 探针 `:tools:compiler` 共用本类）。
 * 自然语言意图 -> 模型 -> Action JSON -> fail-closed 校验。
 *
 * 本模块是纯 JVM 工件（零 android 依赖），因此：
 * - 设备端红线 C（`app/src/main` 零网络关键字）字面不动——网络代码住在 `:byok`，app 只调本类的 `compile`；
 * - 执行路径（executor/locator/service/safety/recorder/platform）被红线 G 禁止 import 本模块，
 *   所以"编译期联网、执行期零网络"是结构事实而不是注释里的承诺（军令 `orders/ANYTOUCH-S3-byok-ORDER.md` §2）。
 * 校验必须跑在**原始 JSON 文本**上（`ContractJson` 的 ignoreUnknownKeys 会静默吞掉模型偷塞的坐标键）。
 */

interface LlmTransport {
    fun complete(systemPrompt: String, userPrompt: String): String
}

sealed interface CompileResult {
    data class Ok(val actions: List<Action>, val actionsJson: String) : CompileResult
    /**
     * stage: transport|parse|decode|validate；detail 人读归因。模型输出永远不直接进执行器。
     * [kind] 是机器可读的失败档（S3-B 失败话术落点）：UI 按它选文案，绝不拿 detail 文本做分支。
     */
    data class Reject(
        val stage: String,
        val detail: String,
        val kind: ByokErrorKind? = null,
    ) : CompileResult
}

object CompilerPrompt {
    val SYSTEM = """
        你是 Anytouch 的任务编译器：把用户的自然语言意图编译成 Android UI 自动化 Action JSON 数组。
        硬性规则：
        1) 只输出一个 JSON 数组，不要解释、不要 markdown 代码块；
        2) 每个元素字段：action_id（短英文小写id，数组内唯一）、type、source、value、safety；
        3) type 只允许 "click" | "type_text" | "scroll" | "key"；source 只允许 "node"；
        4) value：click 用 {"text":"屏幕上可见的目标文本"}；
           type_text 用 {"text":"输入框可见文本","input":"要输入的字符串"}；
           scroll 用 {"resource_id":"容器id","direction":"forward|backward"}；
           key 用 {"key":"back|home|enter"}；
        5) safety 固定 {"viewport_ok":true,"click_enabled":true}；
        6) 严禁出现 target/x/y 等任何坐标字段——定位一律靠节点文本/id；
        7) 意图中出现的英文界面词就是屏幕上的可见文本，直接使用它们；
        8) 例：意图「进蓝牙页」→ [{"action_id":"cd","type":"click","source":"node","value":{"text":"Connected devices"},"safety":{"viewport_ok":true,"click_enabled":true}}]
    """.trimIndent()
}

class DslCompiler(private val transport: LlmTransport) {

    fun compile(intent: String): CompileResult {
        val raw = try {
            transport.complete(CompilerPrompt.SYSTEM, intent)
        } catch (e: TransportFailure) {
            // 传输层已把失败分成不可达/鉴权/限速/服务端/超时几档，这里只做搬运，不降级成一个字符串
            return CompileResult.Reject("transport", e.safeDetail, e.kind)
        } catch (e: Exception) {
            return CompileResult.Reject("transport", e.message ?: e.javaClass.simpleName, ByokErrorKind.UNREACHABLE)
        }
        val snippet = extractJsonArray(raw)
            ?: return reject("模型输出中无 JSON 数组: ${raw.take(160)}", ByokErrorKind.BAD_RESPONSE, stage = "parse")
        val actions = try {
            ContractJson.instance.decodeFromString(ListSerializer(Action.serializer()), snippet)
        } catch (e: Exception) {
            return reject((e.message ?: e.javaClass.simpleName).take(300), ByokErrorKind.BAD_RESPONSE, stage = "decode")
        }
        validate(snippet)?.let { return it }
        return CompileResult.Ok(actions = actions, actionsJson = snippet)
    }

    /** 校验必须看原始 JSON（ContractJson ignoreUnknownKeys=true，解码会静默吞掉未知键）。 */
    private fun validate(snippet: String): CompileResult.Reject? {
        val root = try {
            ContractJson.instance.parseToJsonElement(snippet).jsonArray
        } catch (e: Exception) {
            return reject("二次解析失败: ${e.message}", ByokErrorKind.BAD_RESPONSE, stage = "decode")
        }
        if (root.isEmpty()) return reject("空动作数组（模型拒编或意图不可编译）", ByokErrorKind.EMPTY_ACTIONS)
        val ids = HashSet<String>()
        for ((i, el) in root.withIndex()) {
            val obj = el as? JsonObject ?: return reject("action[$i] 不是对象")
            if (obj.containsKey("target")) return reject("action[$i] 出现坐标字段 target——产品红线禁止")
            val id = obj["action_id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: return reject("action[$i] 缺 action_id")
            if (!ids.add(id)) return reject("action_id 重复: $id")
            val type = obj["type"]?.jsonPrimitive?.contentOrNull
            if (type !in ALLOWED_TYPES) return reject("action[$i] type=$type 不在白名单 ${ALLOWED_TYPES}")
            if (obj["source"]?.jsonPrimitive?.contentOrNull != "node") return reject("action[$i] source 必须为 node")
            val safety = obj["safety"] as? JsonObject ?: return reject("action[$i] 缺 safety")
            if (safety["viewport_ok"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() != true)
                return reject("action[$i] safety.viewport_ok 未显式放行")
            if (type == "click" && safety["click_enabled"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() != true)
                return reject("action[$i] click 未显式 click_enabled")
            val value = obj["value"] as? JsonObject ?: return reject("action[$i] 缺 value")
            value.keys.intersect(FORBIDDEN_VALUE_KEYS).let {
                if (it.isNotEmpty()) return reject("action[$i] value 含坐标类键 $it")
            }
            when (type) {
                "click" -> if (value["text"].strOrNull().isNullOrBlank()) return reject("action[$i] click 缺可见文本")
                "type_text" -> {
                    if (value["text"].strOrNull().isNullOrBlank()) return reject("action[$i] type_text 缺输入框文本")
                    if (value["input"].strOrNull() == null) return reject("action[$i] type_text 缺 input")
                }
                "scroll" -> if (value["direction"]?.jsonPrimitive?.contentOrNull !in setOf("forward", "backward"))
                    return reject("action[$i] scroll direction 非法")
                "key" -> if (value["key"]?.jsonPrimitive?.contentOrNull !in setOf("back", "home", "enter"))
                    return reject("action[$i] key 值非法")
            }
        }
        return null
    }

    private fun JsonElement?.strOrNull(): String? =
        (this as? JsonPrimitive)?.contentOrNull

    /** 拒绝口：一档一因。校验类拒绝天然都是 COMPILE_REJECT，只有跨档的（空数组/坏应答/传输）必须显式给。 */
    private fun reject(
        detail: String,
        kind: ByokErrorKind = ByokErrorKind.COMPILE_REJECT,
        stage: String = "validate",
    ): CompileResult.Reject = CompileResult.Reject(stage, detail, kind)

    companion object {
        val ALLOWED_TYPES = setOf(ActionType.CLICK, ActionType.TYPE_TEXT, ActionType.SCROLL, ActionType.KEY)
        val FORBIDDEN_VALUE_KEYS = setOf("x", "y", "point", "coordinate", "coordinates", "bounds", "offset", "offset_x", "offset_y")

        /** 容忍模型裹 ```json 围栏或前后寒暄：取第一个平衡的 [...] 片段。 */
        fun extractJsonArray(raw: String): String? {
            val start = raw.indexOf('[').takeIf { it >= 0 } ?: return null
            var depth = 0
            var inString = false
            var escaped = false
            for (i in start until raw.length) {
                val c = raw[i]
                if (inString) {
                    when {
                        escaped -> escaped = false
                        c == '\\' -> escaped = true
                        c == '"' -> inString = false
                    }
                } else {
                    when (c) {
                        '"' -> inString = true
                        '[' -> depth++
                        ']' -> {
                            depth--
                            if (depth == 0) return raw.substring(start, i + 1)
                        }
                    }
                }
            }
            return null
        }
    }
}
