package com.anytouch.app.executor

import com.anytouch.app.locator.LocatorHit
import com.anytouch.app.locator.LocatorMiss
import com.anytouch.app.locator.LocatorRequest
import com.anytouch.app.locator.LocatorResult
import com.anytouch.app.locator.NodeTreeLocator
import com.anytouch.app.platform.NodeActions
import com.anytouch.app.safety.HighRiskMatcher
import com.anytouch.app.safety.KillSwitch
import com.anytouch.app.safety.SafetyVerdict
import com.anytouch.app.safety.SimpleNodeProbe
import com.anytouch.app.safety.StopReceipt
import com.anytouch.contracts.Action
import com.anytouch.contracts.ActionResult
import com.anytouch.contracts.ActionType
import com.anytouch.contracts.Command
import com.anytouch.contracts.StopCode
import com.anytouch.contracts.StopReason
import com.anytouch.contracts.StopSeverity
import com.anytouch.pipeline.PipelineStopCode
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * 真设备任务驱动器：Action 序列 -> 每步 ①查 KillSwitch ②三门禁（viewport/click 显式放行）
 * ③对活树定位（轮询到超时，页面切换自愈）④安全阀检查（高危挂起等二次确认，超时/无人确认=拒绝）
 * ⑤performAction。任一步 STOP 级失败即中止整条队列——与 STAGE-02 ClosedLoop 语义同形。
 *
 * 零 Android import（设备访问全走 [NodeActions] 注入），JVM 可测。
 * 停止回执在此无损映射回 contracts 的 Command（worker 侧 StopReceipt 用 Map 保形）。
 */
class NodeTaskRunner(
    private val device: NodeActions,
    private val matcher: HighRiskMatcher = HighRiskMatcher.default(),
    private val confirmer: suspend (SafetyVerdict.RequiresSecondConfirm) -> Boolean = { false },
    private val killSwitch: KillSwitch = KillSwitch,
    private val locateTimeoutMs: Long = 4000,
    private val locatePollMs: Long = 250,
    private val confirmTimeoutMs: Long = 15_000,
    private val settleMs: Long = 350,
) {

    data class Report(
        val results: List<ActionResult>,
        val stopped: Boolean,
        val stopCommand: Command? = null,
    )

    private val locator = NodeTreeLocator()

    suspend fun run(actions: List<Action>): Report {
        val results = ArrayList<ActionResult>(actions.size)
        var stopCommand: Command? = null

        for ((index, action) in actions.withIndex()) {
            val kill = killSwitch.snapshot()
            if (kill != null) {
                stopCommand = killReceipt(action, index, kill).toCommand()
                break
            }

            val gateViolations = buildList {
                if (!action.safety.viewportOk) add("viewport_ok")
                if (!action.safety.clickEnabled) add("click_enabled")
            }
            if (action.type != ActionType.WAIT && gateViolations.isNotEmpty()) {
                results += failure(
                    action,
                    StopReason(
                        code = PipelineStopCode.SAFETY_GATE_BLOCKED,
                        severity = StopSeverity.STOP,
                        message = "safety gate blocked: ${gateViolations.joinToString(",")}",
                        evidence = buildJsonObject { put("action_id", action.actionId) },
                    ),
                )
                stopCommand =
                    gateReceipt(action, index, "action_gates:${gateViolations.joinToString(",")}").toCommand()
                break
            }

            val outcome = when (action.type) {
                ActionType.WAIT -> {
                    delay(longParam(action.value, "ms", 500))
                    StepOutcome(
                        success(action) {
                            put("mock", false)
                            put("waited_ms", longParam(action.value, "ms", 500))
                        },
                    )
                }

                ActionType.CLICK, ActionType.SCROLL, ActionType.TYPE_TEXT -> runNodeStep(action, index)

                else -> StepOutcome(
                    failure(
                        action,
                        StopReason(
                            code = StopCode.EXECUTOR_ERROR,
                            severity = StopSeverity.STOP,
                            message = "unsupported action type: ${action.type}",
                        ),
                    ),
                    gateReceipt(action, index, "unsupported_type:${action.type}", StopCode.EXECUTOR_ERROR).toCommand(),
                )
            }
            results += outcome.result
            stopCommand = outcome.stopCommand
            if (stopCommand != null) break
        }

        return Report(results, stopped = stopCommand != null, stopCommand = stopCommand)
    }

    /* ---- 单步：定位 -> 安全阀 -> 执行 ---- */

    private class StepOutcome(val result: ActionResult, val stopCommand: Command? = null)

    private suspend fun runNodeStep(action: Action, index: Int): StepOutcome {
        val request = action.value.toLocatorRequest()
        if (request == null) {
            return StepOutcome(
                failure(
                    action,
                    StopReason(
                        code = PipelineStopCode.INVALID_INPUT,
                        severity = StopSeverity.STOP,
                        message = "action ${action.actionId} 缺少定位线索（value 需含 resource_id/text/content_desc/path 之一）",
                    ),
                ),
                gateReceipt(action, index, "missing_locator", PipelineStopCode.INVALID_INPUT).toCommand(),
            )
        }

        val located = locateWithRetry(request)
        val hit = located as? LocatorHit
        if (hit == null) {
            val miss = located as LocatorMiss
            return StepOutcome(
                failure(
                    action,
                    StopReason(
                        code = miss.code,
                        severity = StopSeverity.STOP,
                        message = miss.summary,
                        evidence = buildJsonObject {
                            put("action_id", action.actionId)
                            putJsonArray("attempts") {
                                miss.attempts.forEach { a ->
                                    add(
                                        buildJsonObject {
                                            put("level", a.level.order)
                                            put("outcome", a.outcome.name)
                                            put("detail", a.detail)
                                        },
                                    )
                                }
                            }
                        },
                    ),
                ),
                gateReceipt(action, index, miss.code).toCommand(),
            )
        }

        val probe = SimpleNodeProbe(
            resourceId = hit.node.resourceId,
            text = hit.node.text,
            contentDesc = hit.node.contentDesc,
        )
        var secondConfirmed = false
        when (val verdict = matcher.inspect(probe)) {
            SafetyVerdict.Clear -> Unit

            is SafetyVerdict.RequiresSecondConfirm -> {
                val confirmed = withTimeoutOrNull(confirmTimeoutMs) { confirmer(verdict) } ?: false
                if (!confirmed) {
                    return StepOutcome(
                        failure(
                            action,
                            StopReason(
                                code = PipelineStopCode.SAFETY_GATE_BLOCKED,
                                severity = StopSeverity.STOP,
                                message = "高危命中未获二次确认: ${verdict.matchedRule.ruleId}",
                                evidence = buildJsonObject { put("rule", verdict.matchedRule.ruleId) },
                            ),
                        ),
                        gateReceipt(action, index, verdict.matchedRule.ruleId).toCommand(),
                    )
                }
                secondConfirmed = true
            }

            is SafetyVerdict.Denied -> {
                return StepOutcome(
                    failure(
                        action,
                        StopReason(
                            code = PipelineStopCode.SAFETY_GATE_BLOCKED,
                            severity = StopSeverity.STOP,
                            message = "安全阀拒绝: ${verdict.reason.name}",
                            evidence = buildJsonObject { put("reason", verdict.reason.name) },
                        ),
                    ),
                    gateReceipt(action, index, "denied:${verdict.reason.name}").toCommand(),
                )
            }
        }

        val performed = when (action.type) {
            ActionType.CLICK -> device.click(hit.node)
            ActionType.SCROLL -> device.scroll(
                hit.node,
                (action.value?.string("direction") ?: "forward") != "backward",
            )

            ActionType.TYPE_TEXT -> device.setText(hit.node, action.value?.string("input") ?: "")
            else -> false
        }
        if (!performed) {
            return StepOutcome(
                failure(
                    action,
                    StopReason(
                        code = StopCode.EXECUTOR_ERROR,
                        severity = StopSeverity.STOP,
                        message = "performAction 失败: ${action.type} @ ${hit.nodeRef.indexPath}",
                        evidence = buildJsonObject { put("index_path", hit.nodeRef.indexPath) },
                    ),
                ),
                gateReceipt(action, index, "perform_failed").toCommand(),
            )
        }
        if (action.type == ActionType.CLICK || action.type == ActionType.SCROLL) {
            delay(settleMs) // 页面切换沉降；下一次定位自带轮询，不在此等待特定节点
        }
        return StepOutcome(
            success(action) {
                put("mock", false)
                put("level", hit.level.name)
                put("matched_by", hit.matchedBy)
                put("index_path", hit.nodeRef.indexPath)
                if (secondConfirmed) put("second_confirmed", true)
            },
        )
    }

    /** 页面切换期定位轮询：超时前每 [locatePollMs] 重取一次活树；root 未就绪时定位器自身记 miss。 */
    private suspend fun locateWithRetry(request: LocatorRequest): LocatorResult {
        val deadline = System.currentTimeMillis() + locateTimeoutMs
        var last: LocatorResult
        while (true) {
            last = locator.locate(device.root(), request)
            if (last is LocatorHit) return last
            if (System.currentTimeMillis() >= deadline) return last
            delay(locatePollMs)
        }
    }

    /* ---- 回执构造与映射 ---- */

    private fun killReceipt(action: Action, index: Int, kill: KillSwitch.KillSignal) = StopReceipt(
        commandId = "kill-switch-stop-${action.actionId}-$index",
        source = "kill_switch",
        payload = mapOf(
            "step_index" to index.toString(),
            "step_id" to action.actionId,
            "stop_code" to StopCode.USER_STOP,
            "stop_reason" to kill.reason,
            "stop_source" to kill.source,
        ),
    )

    private fun gateReceipt(action: Action, index: Int, reason: String, code: String = PipelineStopCode.SAFETY_GATE_BLOCKED) = StopReceipt(
        commandId = "safety-gate-stop-${action.actionId}-$index",
        source = "safety_gate",
        payload = mapOf(
            "step_index" to index.toString(),
            "step_id" to action.actionId,
            "stop_code" to code,
            "stop_reason" to reason,
        ),
    )

    /** worker 侧 Map 载荷 -> contracts JsonObject，字段无损（主窗窄口承诺）。 */
    private fun StopReceipt.toCommand(): Command = Command(
        commandId = commandId,
        type = type,
        source = source,
        payload = buildJsonObject {
            payload.forEach { (k, v) -> put(k, v) }
        },
        confirm = confirm,
    )

    private fun success(action: Action, details: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) =
        ActionResult(
            ok = true,
            status = "success",
            actionId = action.actionId,
            backend = BACKEND,
            landed = action.target,
            recovery = null,
            details = buildJsonObject(details),
        )

    private fun failure(action: Action, reason: StopReason) = ActionResult(
        ok = false,
        status = "failed",
        actionId = action.actionId,
        backend = BACKEND,
        recovery = reason,
    )

    companion object {
        const val BACKEND = "accessibility"
    }
}

/* ---- Action.value 线索解码（键名即任务 JSON DSL 的对外口径） ---- */

private fun JsonObject?.toLocatorRequest(): LocatorRequest? {
    this ?: return null
    val request = LocatorRequest(
        resourceId = string("resource_id"),
        text = string("text"),
        contentDesc = string("content_desc"),
        path = string("path"),
        instance = int("instance"),
    )
    return request.takeIf {
        it.resourceId != null || it.text != null || it.contentDesc != null || it.path != null
    }
}

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.int(key: String): Int =
    (this[key] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0

private fun longParam(value: JsonObject?, key: String, default: Long): Long =
    (value?.get(key) as? JsonPrimitive)?.content?.toLongOrNull() ?: default
