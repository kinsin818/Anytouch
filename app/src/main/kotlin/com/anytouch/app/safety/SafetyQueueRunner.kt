package com.anytouch.app.safety

import com.anytouch.contracts.StopCode
import com.anytouch.pipeline.PipelineStopCode

/**
 * Command 风格停止回执（军令 L2-3：复用 STAGE-02 ClosedLoop 的语义形状）。
 *
 * 为何不直接构造 contracts 的 Command：contracts 对 kotlinx-serialization-json
 * 是 implementation 依赖，JsonObject 类型不向 :app 暴露；在 :app 补该依赖违反
 * S1 零新增依赖白名单。故以纯 Kotlin 同字段形状（command_id/type/source/payload/confirm）
 * 产出回执，主窗收口时可在窄口适配层无损映射回 Command。
 */
data class StopReceipt(
    val commandId: String,
    val type: String = "stop",
    val source: String,
    val payload: Map<String, String>,
    val confirm: String = "stopped",
)

/** 队列中的一步：步 id + 供安全阀检查的节点三字段。 */
data class GuardedStep(
    val stepId: String,
    val probe: NodeTextProbe,
)

/** 单步执行记录。status ∈ {executed, blocked}。 */
data class StepOutcome(
    val stepId: String,
    val index: Int,
    val status: String,
    val detail: String? = null,
)

/**
 * 安全队列执行器：任意 step 前 ①查 [KillSwitch]，STOP 即中止并产停止回执；
 * ②过 [HighRiskMatcher]，RequiresSecondConfirm 无人工确认（默认拒绝放行）或
 * Denied（词表坏）→ 以 SAFETY_GATE_BLOCKED 中止整条队列。
 *
 * 设计取向：门禁不过 = 中止队列而非跳过该步继续 —— 高危上下文里"剩下的步骤"
 * 同样可疑，与 ClosedLoop 遇 STOP 级即断的风格对齐。
 */
class SafetyQueueRunner(
    private val killSwitch: KillSwitch = KillSwitch,
    private val matcher: HighRiskMatcher = HighRiskMatcher.default(),
) {

    class Outcome(
        val steps: List<StepOutcome>,
        val stopped: Boolean,
        val stopReceipt: StopReceipt? = null,
    )

    fun run(
        steps: List<GuardedStep>,
        executor: (GuardedStep) -> Unit,
        confirmer: (SafetyVerdict.RequiresSecondConfirm) -> Boolean = { false },
    ): Outcome {
        val outcomes = ArrayList<StepOutcome>(steps.size)
        for ((index, step) in steps.withIndex()) {
            val kill = killSwitch.snapshot()
            if (kill != null) {
                return Outcome(
                    outcomes,
                    stopped = true,
                    stopReceipt = StopReceipt(
                        commandId = "kill-switch-stop-${step.stepId}-$index",
                        source = "kill_switch",
                        payload = mapOf(
                            "step_index" to index.toString(),
                            "step_id" to step.stepId,
                            "stop_code" to StopCode.USER_STOP,
                            "stop_reason" to kill.reason,
                            "stop_source" to kill.source,
                        ),
                    ),
                )
            }

            when (val verdict = matcher.inspect(step.probe)) {
                SafetyVerdict.Clear -> {
                    executor(step)
                    outcomes += StepOutcome(step.stepId, index, STATUS_EXECUTED)
                }

                is SafetyVerdict.RequiresSecondConfirm -> {
                    if (confirmer(verdict)) {
                        executor(step)
                        outcomes += StepOutcome(step.stepId, index, STATUS_EXECUTED, "second_confirmed")
                    } else {
                        return stopAtGate(outcomes, index, step, verdict.matchedRule.ruleId)
                    }
                }

                is SafetyVerdict.Denied -> {
                    return stopAtGate(outcomes, index, step, "denied:${verdict.reason.name}")
                }
            }
        }
        return Outcome(outcomes, stopped = false)
    }

    private fun stopAtGate(
        outcomes: MutableList<StepOutcome>,
        index: Int,
        step: GuardedStep,
        detail: String,
    ): Outcome {
        outcomes += StepOutcome(step.stepId, index, STATUS_BLOCKED, detail)
        return Outcome(
            outcomes,
            stopped = true,
            stopReceipt = StopReceipt(
                commandId = "safety-gate-stop-${step.stepId}-$index",
                source = "safety_gate",
                payload = mapOf(
                    "step_index" to index.toString(),
                    "step_id" to step.stepId,
                    "stop_code" to PipelineStopCode.SAFETY_GATE_BLOCKED,
                    "stop_reason" to detail,
                ),
            ),
        )
    }

    companion object {
        const val STATUS_EXECUTED = "executed"
        const val STATUS_BLOCKED = "blocked"
    }
}
