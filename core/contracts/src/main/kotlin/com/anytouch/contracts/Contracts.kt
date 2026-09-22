package com.anytouch.contracts

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

/**
 * 坐标点。对应契约中 `target` / `landed` 的 `{"x":..,"y":..}`。
 */
@Serializable
data class Point(
    val x: Int,
    val y: Int,
)

internal fun emptyJsonObject(): JsonObject = buildJsonObject { }

/**
 * 观察结果 —— 契约源: D:\SuperMa-LeftRight\docs\module_contracts.md § Observation
 * questions/buttons 样例为空数组、元素结构未在契约中定义，透传为 JsonObject 列表。
 */
@Serializable
data class Observation(
    val ok: Boolean,
    val status: String,
    @SerialName("page_id") val pageId: String,
    @SerialName("page_type") val pageType: String,
    val confidence: Double,
    val questions: List<JsonObject> = emptyList(),
    val buttons: List<JsonObject> = emptyList(),
    val evidence: ObservationEvidence = ObservationEvidence(),
    val warnings: List<String> = emptyList(),
)

/**
 * Observation.evidence 六路证据源；各源内容未在契约中定义，透传 JsonObject。
 */
@Serializable
data class ObservationEvidence(
    val vision: JsonObject? = null,
    val ocr: JsonObject? = null,
    val yolo: JsonObject? = null,
    val template: JsonObject? = null,
    val cache: JsonObject? = null,
    @SerialName("training_bank") val trainingBank: JsonObject? = null,
)

/**
 * 行动计划 —— 契约源 § ActionPlan
 */
@Serializable
data class ActionPlan(
    val ok: Boolean,
    val status: String,
    @SerialName("plan_id") val planId: String,
    @SerialName("page_id") val pageId: String,
    val actions: List<Action> = emptyList(),
    @SerialName("stop_reason") val stopReason: String = "",
    @SerialName("fallback_policy") val fallbackPolicy: JsonObject = emptyJsonObject(),
)

/**
 * 单个动作 —— 契约源 § Action
 * type / source 为字符串字段，配套常量对象见 ActionType / ActionSource（非严格枚举，留扩展位）。
 */
@Serializable
data class Action(
    @SerialName("action_id") val actionId: String,
    val type: String,
    val target: Point? = null,
    val value: JsonObject? = null,
    val source: String,
    val safety: ActionSafety = ActionSafety(),
)

/**
 * Action.safety 三门禁 —— 缺省全 false：未显式放行的动作不可执行。
 */
@Serializable
data class ActionSafety(
    @SerialName("viewport_ok") val viewportOk: Boolean = false,
    @SerialName("click_enabled") val clickEnabled: Boolean = false,
    @SerialName("requires_transition") val requiresTransition: Boolean = false,
)

/**
 * 动作执行结果 —— 契约源 § ActionResult
 * recovery 样例为 null；其语义是恢复时引用的停止原因，建模为可空 StopReason。
 */
@Serializable
data class ActionResult(
    val ok: Boolean,
    val status: String,
    @SerialName("action_id") val actionId: String,
    val backend: String,
    val landed: Point? = null,
    val recovery: StopReason? = null,
    val details: JsonObject = emptyJsonObject(),
)

/**
 * 控制命令 —— 契约源 § Command
 */
@Serializable
data class Command(
    @SerialName("command_id") val commandId: String,
    val type: String,
    val source: String,
    val payload: JsonObject = emptyJsonObject(),
    val confirm: String = "",
)

/**
 * 停止原因 —— 契约源 § StopReason
 * code / severity 为字符串字段，配套常量对象见 StopCode / StopSeverity。
 */
@Serializable
data class StopReason(
    val code: String,
    val severity: String,
    val message: String,
    val evidence: JsonObject = emptyJsonObject(),
)
