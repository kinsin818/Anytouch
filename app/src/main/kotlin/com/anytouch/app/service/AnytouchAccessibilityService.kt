package com.anytouch.app.service

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.ServiceInfo
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.anytouch.app.AppState
import com.anytouch.app.TaskRequest
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
        // 必须用服务自身 context：TYPE_ACCESSIBILITY_OVERLAY 的窗口 token 挂在 AccessibilityService
        // 的 WindowManager 上，applicationContext 加视图必失败（token null），面板/悬浮球将永远不可见。
        val ui = OverlayUi(this)
        overlay = ui
        scope.launch {
            AppState.taskRequests.collect { request ->
                if (request == null) return@collect
                if (AppState.isExpired(request)) {
                    // 陈旧注入即弃：绝不因"服务恰好重绑"而偷跑用户早已放弃的任务（设备实测复现过）
                    Log.w(TAG, "S1SMOKE stale request ${request.id} expired, dropped")
                    AppState.consume(request)
                    return@collect
                }
                if (AppState.running.value) {
                    Log.w(TAG, "S1SMOKE busy, request ${request.id} dropped")
                    AppState.consume(request) // 忙中丢弃也要作废，否则滞留队列头会在重绑时重放
                    return@collect
                }
                runTask(request, ui)
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

    private suspend fun runTask(request: TaskRequest, ui: OverlayUi) {
        val json = request.json
        KillSwitch.reset()
        AppState.running.value = true
        // 执行期挂前台服务：cached 进程会被 doze 冻结，定位轮询将停摆（模拟器实测复现）
        startForegroundCompat()
        ui.showStopBall { KillSwitch.stop() }
        try {
            val report = try {
                val actions = ContractJson.instance.decodeFromString(ListSerializer(Action.serializer()), json)
                NodeTaskRunner(
                    device = AccessibilityDevice(this),
                    confirmer = { verdict -> ui.awaitSecondConfirm(verdict) },
                ).run(actions)
            } catch (e: kotlinx.coroutines.CancellationException) {
                // 取消不是任务失败，不伪造 S1SMOKE 回执——但必须留痕（设备实证：无痕取消曾把
                // "服务被系统重启"伪装成无事发生，running 悬挂吞掉后续全部任务）。
                Log.w(TAG, "S1SMOKE run cancelled by service lifecycle (${e.message ?: e.javaClass.simpleName}), 回执缺席以此行为准")
                throw e
            } catch (e: Exception) {
                invalidTaskReport(e)
            }
            AppState.lastRunReport.value = encodeReport(report)
            Log.i(
                TAG,
                "S1SMOKE ok=${report.results.count { it.ok }} total=${report.results.size} " +
                    "stopped=${report.stopped} stop=${report.stopCommand?.payload?.get("stop_reason") ?: "-"}",
            )
            report.results.lastOrNull()?.recovery?.let { rec ->
                Log.i(TAG, "S1SMOKE-DETAIL code=${rec.code} msg=${rec.message.take(300)}")
            }
        } finally {
            // 收尾必须无条件执行：悬浮球/前台态/running 头寸/队列消费一个都不能随取消失踪
            ui.hideStopBall()
            AppState.running.value = false
            stopForegroundCompat()
            // 消费完毕即清空：StateFlow 重放语义会在服务重绑时把旧任务再执行一次（模拟器实测）
            AppState.consume(request)
        }
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "任务执行", NotificationManager.IMPORTANCE_LOW))
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Anytouch 正在执行任务")
            .setContentText("悬浮球可随时全局停止")
            .setSmallIcon(android.R.drawable.sym_def_app_icon)
            .setOngoing(true)
            .build()
        // 34 起带 type 的三参形式优先；ROM 不接受 specialUse 时退回两参（仅失去类型细分，不失去前台态）
        runCatching {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        }.onFailure {
            runCatching { startForeground(NOTIF_ID, notification) }
        }
    }

    private fun stopForegroundCompat() {
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
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
        const val CHANNEL_ID = "executor"
        const val NOTIF_ID = 1
    }
}
