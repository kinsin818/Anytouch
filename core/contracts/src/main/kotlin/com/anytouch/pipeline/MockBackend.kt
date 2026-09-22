package com.anytouch.pipeline

import com.anytouch.contracts.Action
import com.anytouch.contracts.ActionResult
import com.anytouch.contracts.Point
import com.anytouch.contracts.StopCode
import com.anytouch.contracts.StopReason
import com.anytouch.contracts.StopSeverity
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Mock 执行器：纯内存回显，不调用任何真实系统 API。
 * 安全门禁：viewport_ok 与 click_enabled 任一为 false 即拒绝执行，
 * 返回 ok=false 的 ActionResult 并在 recovery 中携带 STOP 级 StopReason。
 */
class MockBackend(private val backendName: String = "mock") : ExecutorBackend {

    override fun act(action: Action): ActionResult {
        val violatedGates = buildList {
            if (!action.safety.viewportOk) add("viewport_ok")
            if (!action.safety.clickEnabled) add("click_enabled")
        }
        if (violatedGates.isNotEmpty()) {
            return ActionResult(
                ok = false,
                status = "failed",
                actionId = action.actionId,
                backend = backendName,
                landed = null,
                recovery = StopReason(
                    code = PipelineStopCode.SAFETY_GATE_BLOCKED,
                    severity = StopSeverity.STOP,
                    message = "safety gate blocked: ${violatedGates.joinToString(",")}",
                    evidence = buildJsonObject {
                        put("action_id", action.actionId)
                        put("gates", violatedGates.joinToString(","))
                    },
                ),
                details = emptyDetails(),
            )
        }
        if (action.type !in SUPPORTED_TYPES) {
            return ActionResult(
                ok = false,
                status = "failed",
                actionId = action.actionId,
                backend = backendName,
                recovery = StopReason(
                    code = StopCode.EXECUTOR_ERROR,
                    severity = StopSeverity.STOP,
                    message = "unsupported action type: ${action.type}",
                ),
            )
        }
        return ActionResult(
            ok = true,
            status = "success",
            actionId = action.actionId,
            backend = backendName,
            landed = action.target ?: Point(0, 0),
            recovery = null,
            details = buildJsonObject {
                put("mock", true)
                put("action_type", action.type)
                if (action.safety.requiresTransition) put("transition_required", true)
            },
        )
    }

    private companion object {
        val SUPPORTED_TYPES = setOf("click", "move", "scroll", "type_text", "key", "wait", "submit")
        fun emptyDetails() = buildJsonObject { }
    }
}
