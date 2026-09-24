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
    /**
     * 规则 3 那一半句动作词表**由真源拼出**（`ExecutorVocabulary.kt`，S3-F/F2-2）。
     * 旧版手抄成 `"click" | "type_text" | "scroll" | "key"`，于是"提示词写一套、校验器认另一套"
     * 只需改一侧就能发生——现在它抄不出来。JVM 用例再锁一道（提示词出现的 type 名集合 == 真源）。
     */
    val SYSTEM = """
        你是 Anytouch 的任务编译器：把用户的自然语言意图编译成 Android UI 自动化 Action JSON 数组。
        硬性规则：
        1) 只输出一个 JSON 数组，不要解释、不要 markdown 代码块；
        2) 每个元素字段：action_id（短英文小写id，数组内唯一）、type、source、value、safety；
        3) ${executorSupportedTypesPromptClause()}；source 只允许 "node"；
        4) value：click 用 {"text":"屏幕上可见的目标文本"}；
           type_text 用 {"text":"输入框可见文本","input":"要输入的字符串"}；
           scroll 用 {"resource_id":"容器id","direction":"forward|backward"}；
           wait 用 {"ms":"等待毫秒数，可省略（省略即 500）"}；
        5) safety 固定 {"viewport_ok":true,"click_enabled":true}；
        6) 严禁出现 target/x/y 等任何坐标字段——定位一律靠节点文本/id；
        7) 意图中出现的英文界面词就是屏幕上的可见文本，直接使用它们；
        8) 例：意图「进蓝牙页」→ [{"action_id":"cd","type":"click","source":"node","value":{"text":"Connected devices"},"safety":{"viewport_ok":true,"click_enabled":true}}]
        9) 若意图后附了【当前屏幕可见词表】：click/type_text 的 text、scroll 的 resource_id 只能从该词表里取，
           词表里没有就说明它不在屏上——不依赖文本的动作只剩 scroll 一条（它的 resource_id 同样只能取自词表），
           那就宁可少编一步，绝不许多编一步、也绝不许自己编一个词；
           input_field(...) 一行只代表"这里有个输入框"，它的内容没有上行，不许猜测框里现在是什么。
    """.trimIndent()

    /** 上下文块拼在意图之后：无上下文时逐字等于意图本身（切片 A 的老链路零漂移）。 */
    fun userMessage(intent: String, context: ScreenContext = ScreenContext.disabled()): String {
        val block = context.render()
        return if (block.isEmpty()) intent else "$intent\n\n$block"
    }
}

class DslCompiler(private val transport: LlmTransport) {

    /**
     * [context] 默认关闭——屏上下文必须显式构造并传入才有内容（军令 §3-5：开关关=零条上行）。
     * 校验判据与上下文无关：模型即便拿到词表也仍可能编出坐标或凭空的词，validate 一档不松。
     */
    fun compile(intent: String, context: ScreenContext = ScreenContext.disabled()): CompileResult {
        val raw = try {
            transport.complete(CompilerPrompt.SYSTEM, CompilerPrompt.userMessage(intent, context))
        } catch (e: TransportFailure) {
            // 传输层已把失败分成不可达/鉴权/限速/服务端/超时几档，这里只做搬运，不降级成一个字符串
            return CompileResult.Reject("transport", e.safeDetail, e.kind)
        } catch (e: Exception) {
            return CompileResult.Reject("transport", e.message ?: e.javaClass.simpleName, ByokErrorKind.UNREACHABLE)
        }
        val snippet = extractJsonArray(raw)
            ?: return reject("no JSON array in model output: ${raw.take(160)}", ByokErrorKind.BAD_RESPONSE, stage = "parse")
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
            return reject("re-parse failed: ${e.message}", ByokErrorKind.BAD_RESPONSE, stage = "decode")
        }
        if (root.isEmpty()) return reject("empty action array (the model declined or the intent is not compilable)", ByokErrorKind.EMPTY_ACTIONS)
        val ids = HashSet<String>()
        for ((i, el) in root.withIndex()) {
            val obj = el as? JsonObject ?: return reject("action[$i] is not an object")
            if (obj.containsKey("target")) return reject("action[$i] carries the coordinate field target — banned by product red line")
            val id = obj["action_id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: return reject("action[$i] is missing action_id")
            if (!ids.add(id)) return reject("duplicate action_id: $id")
            val type = obj["type"]?.jsonPrimitive?.contentOrNull
            if (type !in ALLOWED_TYPES)
                return reject("action[$i] type=$type cannot run on the executor: allowed vocabulary=$ALLOWED_TYPES (single source :byok ExecutorVocabulary, tightening = ruling S31-B3)")
            if (obj["source"]?.jsonPrimitive?.contentOrNull != "node") return reject("action[$i] source must be node")
            val safety = obj["safety"] as? JsonObject ?: return reject("action[$i] is missing safety")
            if (safety["viewport_ok"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() != true)
                return reject("action[$i] safety.viewport_ok is not explicitly allowed")
            if (type == ActionType.CLICK && safety["click_enabled"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() != true)
                return reject("action[$i] click is missing an explicit click_enabled")
            val value = obj["value"] as? JsonObject ?: return reject("action[$i] is missing value")
            value.keys.intersect(FORBIDDEN_VALUE_KEYS).let {
                if (it.isNotEmpty()) return reject("action[$i] value carries coordinate-like keys $it")
            }
            // 逐类型的 value 形状判据一律引 ActionType 常量（S31-B5 同一条纪律：比较不写字面量）。
            // 旧版这里有一条 `"key" -> …`：词表收紧后 key 在上面那档就被拒了，形状判据跟着一起消失，
            // 不留"授权说不行、形状却又认得"的第三种话。
            when (type) {
                ActionType.CLICK -> if (value["text"].strOrNull().isNullOrBlank()) return reject("action[$i] click is missing visible text")
                ActionType.TYPE_TEXT -> {
                    if (value["text"].strOrNull().isNullOrBlank()) return reject("action[$i] type_text is missing the input-field text")
                    if (value["input"].strOrNull() == null) return reject("action[$i] type_text is missing input")
                }
                ActionType.SCROLL -> if (value["direction"]?.jsonPrimitive?.contentOrNull !in setOf("forward", "backward"))
                    return reject("action[$i] scroll direction is invalid")
                ActionType.WAIT -> {
                    // 省略 ms 是合法的：执行器按默认 500ms 走（NodeTaskRunner 的 longParam 口径），
                    // 但给了就必须是个非负整数——否则"wait 一步"在屏上是几秒说不清。
                    val raw = value["ms"]
                    if (raw != null) {
                        val ms = (raw as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
                        if (ms == null || ms < 0) return reject("action[$i] wait ms must be a non-negative integer (omitted means 500)")
                    }
                }
                // fail-closed：真源若添了新成员而这里没有形状判据，宁可当场拒，也不放行一条"没人核对过形状"的动作
                // （今天不可达——真源四个成员上面各有一条，这条锁的是"改真源忘了改这里"）。
                else -> return reject("action[$i] type=$type exists in the single source but has no shape check here (source and validator out of sync — add the check, do not pass it through)")
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
        /**
         * 授权词表**等于**执行器真源（S3-F/F2-2，裁决 S31-B3"收紧词表"）：这里不再列成员，只转引。
         * 旧版是手抄的 `setOf(CLICK, TYPE_TEXT, SCROLL, `**`KEY`**`)`——比执行面宽一个 key，
         * 那一个 key 就是 `[E1-*]` 轮抓到的间歇红正身。JVM 用例锁"两者逐字相等"（ExecutorVocabularyTest）。
         */
        val ALLOWED_TYPES: Set<String> get() = executorSupportedActionTypes
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
