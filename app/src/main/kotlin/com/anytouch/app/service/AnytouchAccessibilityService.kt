package com.anytouch.app.service

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.anytouch.app.AppState
import com.anytouch.app.executor.NodeTaskRunner
import com.anytouch.app.platform.AccessibilityDevice
import com.anytouch.app.safety.KillSwitch
import com.anytouch.contracts.Action
import com.anytouch.contracts.ActionResult
import com.anytouch.contracts.Command
import com.anytouch.contracts.ContractJson
import com.anytouch.contracts.StopSeverity
import com.anytouch.pipeline.PipelineStopCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 执行器宿主服务（S1 主窗收口接线）：
 * - 监听 AppState.taskRequests（UI 按钮或 adb intent 注入的任务 JSON）；
 * - 每条任务：KillSwitch 复位 -> 挂悬浮球 -> NodeTaskRunner 驱动全队列 -> 回执 JSON 入 AppState；
 * - 服务销毁 = 能力丧失，主动触发全局停止，绝不留悬挂队列。
 * 录制事件流仍不处理（S2，门票门禁外不开工）。
 */
class AnytouchAccessibilityService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var overlay: OverlayUi? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        AppState.serviceConnected.value = true
        val ui = OverlayUi(applicationContext)
        overlay = ui
        scope.launch {
            AppState.taskRequests.collect { request ->
                if (request == null) return@collect
                if (AppState.running.value) {
                    Log.w(TAG, "S1SMOKE busy, request ${request.id} ignored")
                    return@collect
                }
                runTask(request.json, ui)
            }
        }
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        AppState.serviceConnected.value = false
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // S1 骨架：不处理。录制事件流属 S2（门票门禁外，不开工）
    }

    override fun onInterrupt() {
        AppState.serviceConnected.value = false
    }

    override fun onDestroy() {
        KillSwitch.stop(reason = "service_destroyed", source = "accessibility_service")
        overlay?.dispose()
        overlay = null
        scope.cancel()
        AppState.serviceConnected.value = false
        super.onDestroy()
    }

    private suspend fun runTask(json: String, ui: OverlayUi) {
        KillSwitch.reset()
        AppState.running.value = true
        ui.showStopBall { KillSwitch.stop() }
        val report = try {
            val actions = ContractJson.instance.decodeFromString(ListSerializer(Action.serializer()), json)
            NodeTaskRunner(
                device = AccessibilityDevice(this),
                confirmer = { verdict -> ui.awaitSecondConfirm(verdict) },
            ).run(actions)
        } catch (e: Exception) {
            invalidTaskReport(e)
        }
        AppState.lastRunReport.value = encodeReport(report)
        Log.i(
            TAG,
            "S1SMOKE ok=${report.results.count { it.ok }} total=${report.results.size} " +
                "stopped=${report.stopped} stop=${report.stopCommand?.payload?.get("stop_reason") ?: "-"}",
        )
        ui.hideStopBall()
        AppState.running.value = false
    }

    private fun invalidTaskReport(e: Exception) = NodeTaskRunner.Report(
        results = emptyList(),
        stopped = true,
        stopCommand = Command(
            commandId = "invalid-task-${System.nanoTime()}",
            type = "stop",
            source = "task_decoder",
            payload = buildJsonObject {
                put("stop_code", PipelineStopCode.INVALID_INPUT)
                put("stop_reason", e.message ?: e.javaClass.simpleName)
            },
            confirm = "stopped",
        ),
    )

    private fun encodeReport(report: NodeTaskRunner.Report): String = buildJsonObject {
        put("stopped", report.stopped)
        put(
            "results",
            ContractJson.instance.encodeToJsonElement(ListSerializer(ActionResult.serializer()), report.results),
        )
        report.stopCommand?.let {
            put("stop_command", ContractJson.instance.encodeToJsonElement(Command.serializer(), it))
        }
    }.toString()

    private companion object {
        const val TAG = "AnytouchRun"
    }
}
