package com.anytouch.pipeline

import com.anytouch.contracts.Action
import com.anytouch.contracts.ActionResult
import com.anytouch.contracts.Command
import com.anytouch.contracts.ContractJson
import com.anytouch.contracts.StopSeverity
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Mock 闭环驱动器：JSON 指令数组 -> 逐个过 ExecutorBackend 执行 -> List<ActionResult> JSON。
 * 遇 STOP 级 StopReason 立即中断，并产出一条 Command 风格的停止回执。
 * 纯内存实现，不触碰任何真实系统 API。
 */
object ClosedLoop {

    class Outcome(
        val resultsJson: String,
        val stopped: Boolean,
        val stopReceipt: Command? = null,
    )

    fun run(inputJson: String, backend: ExecutorBackend = MockBackend()): Outcome {
        val actions = decodeActions(inputJson)
        val results = ArrayList<ActionResult>(actions.size)
        var stopReceipt: Command? = null

        for ((index, action) in actions.withIndex()) {
            val result = backend.act(action)
            results += result
            val reason = result.recovery
            if (reason != null && reason.severity == StopSeverity.STOP) {
                stopReceipt = Command(
                    commandId = "closed-loop-stop-${action.actionId}-$index",
                    type = "stop",
                    source = "closed_loop",
                    payload = buildJsonObject {
                        put("action_index", index)
                        put("stop_reason", encodeStopReason(reason))
                    },
                    confirm = "stopped",
                )
                break
            }
        }

        return Outcome(
            resultsJson = ContractJson.instance.encodeToString(ListSerializer(ActionResult.serializer()), results),
            stopped = stopReceipt != null,
            stopReceipt = stopReceipt,
        )
    }

    private fun decodeActions(inputJson: String): List<Action> {
        try {
            return ContractJson.instance.decodeFromString(ListSerializer(Action.serializer()), inputJson)
        } catch (e: SerializationException) {
            throw IllegalArgumentException("invalid action list json: ${e.message}", e)
        }
    }

    private fun encodeStopReason(reason: com.anytouch.contracts.StopReason): JsonElement =
        ContractJson.instance.encodeToJsonElement(
            com.anytouch.contracts.StopReason.serializer(),
            reason,
        )
}
