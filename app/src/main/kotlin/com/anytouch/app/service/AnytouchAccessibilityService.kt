package com.anytouch.app.service

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.ServiceInfo
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.anytouch.app.AppState
import com.anytouch.app.TaskPolicy
import com.anytouch.app.TaskRequest
import com.anytouch.app.executor.NodeTaskRunner
import com.anytouch.app.platform.AccessibilityDevice
import com.anytouch.app.recorder.capture.AndroidCaptureBridge
import com.anytouch.app.recorder.capture.CaptureBridge
import com.anytouch.app.recorder.session.RecorderStore
import com.anytouch.app.recorder.session.SessionState
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.buildJsonArray
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

    /**
     * 采集桥（薄桥）。rootProvider=当前可遍历的窗口根集合，与执行器 AccessibilityDevice.root() 同口径：
     * 录进任务的 indexPath 只有相对"回放时同一把锚点"才有意义（设备实证父链会无端断裂，须能回溯到根）。
     */
    private val captureBridge: CaptureBridge = AndroidCaptureBridge { snapshotRoots() }

    private fun snapshotRoots(): List<AccessibilityNodeInfo> {
        // 转场进行中原子查询会空返回（设备实证：点击落下那一瞬 roots 直接为空，
        // 事后同一棵树完好）。采证前有限重试，绝不因此丢用户一步；上限 2×80ms，不拖成 ANR。
        repeat(ROOT_RETRY_TIMES) {
            val roots = collectRoots()
            if (roots.isNotEmpty()) return roots
            runCatching { Thread.sleep(ROOT_RETRY_DELAY_MS) }
        }
        return collectRoots()
    }

    /**
     * 采集根集合必须与执行器 [com.anytouch.app.platform.AccessibilityDevice.root] **同一词表**：
     * 那边只认一个根（活动窗优先，取不到才按焦点/层级取应用窗），所以这里也只给一个根。
     * 设备实证（雷 18）：原实现 `active + 全部应用窗` 会把焦点应用窗**数两遍**
     * （日志正证 `roots=2` 且两个根的 window hash 相同），于是"窗口内唯一线索"被自己的双计判成
     * `desc 命中=2` 歧义 → 整步不入会话（8 步链补跑 10 轮里两轮各少录 1 步）。
     * 采集面判"唯一"的分母，必须是回放面真正会扫的那棵树，多一个根就是假歧义。
     */
    private fun collectRoots(): List<AccessibilityNodeInfo> {
        rootInActiveWindow?.let { return listOf(it) }
        val windows = runCatching {
            (windows ?: emptyList())
                .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                .sortedWith(
                    compareByDescending<AccessibilityWindowInfo> { it.isFocused }
                        .thenByDescending { it.isActive }
                        .thenByDescending { it.layer },
                )
                .mapNotNull { runCatching { it.root }.getOrNull() }
        }.getOrDefault(emptyList())
        return listOfNotNull(windows.firstOrNull())
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        AppState.serviceConnected.value = true
        RecorderStore.selfPkg = packageName
        RecorderStore.onServiceReconnected()
        // 必须用服务自身 context：TYPE_ACCESSIBILITY_OVERLAY 的窗口 token 挂在 AccessibilityService
        // 的 WindowManager 上，applicationContext 加视图必失败（token null），面板/悬浮球将永远不可见。
        val ui = OverlayUi(this)
        overlay = ui
        scope.launch {
            AppState.taskRequests.collect { request ->
                if (request == null) return@collect
                if (AppState.isExpired(request)) {
                    // 陈旧注入即弃：绝不因"服务恰好重绑"而偷跑用户早已放弃的任务（设备实测复现过）
                    // 丢弃也必须留痕（第 9 项）：无声吞注入=报告层黑洞，与虚报成功同罪。
                    val receipt = droppedRunReport(
                        PipelineStopCode.REQUEST_EXPIRED,
                        "注入超过 ${TaskPolicy.TTL_MS / 1000}s 未被消费即作废（防重绑偷跑）",
                    )
                    AppState.lastRunReport.value = receipt
                    Log.w(TAG, "S1SMOKE stale request ${request.id} expired, dropped receipt=$receipt")
                    AppState.consume(request)
                    return@collect
                }
                if (AppState.running.value) {
                    // 设备实测：正常架构下此分支不可达——执行中新注入被 StateFlow conflation 并队，
                    // 首任务完成后串行执行；能走到这里说明 running 已泄漏（第 7 颗雷形态），防线即弃+留痕。
                    val receipt = droppedRunReport(
                        PipelineStopCode.REQUEST_BUSY,
                        "已有任务在执行，新注入即弃（单执行器语义；执行中任务稍后会覆写本报告）",
                    )
                    AppState.lastRunReport.value = receipt
                    Log.w(TAG, "S1SMOKE busy, request ${request.id} dropped receipt=$receipt")
                    AppState.consume(request) // 忙中丢弃也要作废，否则滞留队列头会在重绑时重放
                    return@collect
                }
                runTask(request, ui)
            }
        }
        // 服务重连窗口内不沿用上一次的"球已挂上"事实：门禁只认本轮挂载结果（fail-closed）
        RecorderStore.recordBallAttached = false
        watchRecordBall(ui)
    }

    /**
     * 录制开关球（军令 S2-ONDEVICE L0：悬浮球开录）。跟随"会话态 + 执行态"两流刷新：
     * 执行期收起——开录录进去的会是执行器自己的手，把机器动作伪装成用户意图（假绿形态）。
     * 球的两次点击都走 RecorderStore 同一入口（与主窗按钮、adb 通道同一留痕口径）。
     *
     * 这里的返回值同时是 L1 门禁的唯一事实源（[RecorderStore.recordBallAttached]）：
     * 挂不上球就拒绝开录，不做"看不见球也能录"的降级。执行期收起是有意状态，
     * 不覆写该事实（门禁先判 running 并单独给话术，别让"收起"伪装成"挂不上"）。
     */
    private fun watchRecordBall(ui: OverlayUi) {
        scope.launch {
            combine(
                RecorderStore.activeSession,
                AppState.running,
                AppState.serviceConnected,
            ) { session, running, connected ->
                Triple(session?.state == SessionState.RECORDING, running, connected)
            }.collect { (recording, running, _) ->
                if (running) ui.hideRecordBall()
                else RecorderStore.recordBallAttached = ui.showRecordBall(recording) { toggleRecording(ui) }
                // V-2（老板 09-23 裁决）：状态跃迁后立刻复核陈旧拒因——本轮 collect 覆盖
                // 会话态/执行态/连接态三股流，正是"执行中不能开录"这类话术失去依据的那些时刻。
                // 判据在纯函数里（JVM 锁），此处只负责"什么时候量一次"。
                RecorderStore.revalidateStartRejection()
            }
        }
    }

    private fun toggleRecording(ui: OverlayUi) {
        if (AppState.running.value) {
            Log.w(TAG, "S2SMOKE record ball refused: 执行中不开录（见 watchRecordBall）")
            return
        }
        if (RecorderStore.isRecording) RecorderStore.stopAndCompile() else RecorderStore.start()
        ui.setRecordBallRecording(RecorderStore.isRecording)
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        AppState.serviceConnected.value = false
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // S2-ONDEVICE 采集钩子：仅录制会话在场时翻译入流（薄桥，语义全在纯 JVM 适配器/编译器）。
        // 零派发、零网络；录制期外的普通事件与 S1 骨架同——不处理。
        if (event == null) return
        val session = RecorderStore.activeSession.value ?: return
        if (session.state != SessionState.RECORDING) return
        runCatching { captureBridge.capture(event) }
            .onFailure { Log.w(TAG, "S2SMOKE capture bridge failed (event dropped with trace): ${it.message}", it) }
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
        // 采集半边随服务消亡：钩子已无人驱动，会话置空防"UI 显示在录但永不进事件"的假开录态
        RecorderStore.abandonRecording()
        super.onDestroy()
    }

    private suspend fun runTask(request: TaskRequest, ui: OverlayUi) {
        val json = request.json
        KillSwitch.reset()
        AppState.running.value = true
        // 执行期挂前台服务：cached 进程会被 doze 冻结，定位轮询将停摆（模拟器实测复现）
        startForegroundCompat()
        // 安全模型前提显式化：停止球是唯一全局急停手段，挂不上就绝不开跑
        // （此前 addView 失败被吞——无急停状态下静默执行，违背 fail-closed 精神）。
        // 注意：拒跑 return 也必须在 try 内——finally 收尾（running 落位/消费队列）对早退同样成立。
        try {
            if (!ui.showStopBall { KillSwitch.stop() }) {
                val receipt = droppedRunReport(
                    PipelineStopCode.SAFETY_BALL_UNAVAILABLE,
                    "悬浮停止球挂不上，全局急停手段缺席",
                    "任务未开始执行（fail-closed 拒跑）",
                )
                AppState.lastRunReport.value = receipt
                Log.w(TAG, "S1SMOKE stop ball unavailable, refuse to run receipt=$receipt")
                return
            }
            val report = try {
                val actions = ContractJson.instance.decodeFromString(ListSerializer(Action.serializer()), json)
                NodeTaskRunner(
                    device = AccessibilityDevice(this),
                    confirmer = { verdict -> ui.awaitSecondConfirm(verdict) },
                ).run(actions)
            } catch (e: kotlinx.coroutines.CancellationException) {
                // 取消不是任务失败，不伪造 S1SMOKE 回执——但必须留痕（设备实证：无痕取消曾把
                // "服务被系统重启"伪装成无事发生，running 悬挂吞掉后续全部任务）。
                // 回执层同罪：不写中断回执的话，用户界面停留在上一条陈旧报告上，丢单无痕。
                val receipt = interruptedRunReport(e.message ?: e.javaClass.simpleName)
                AppState.lastRunReport.value = receipt
                Log.w(TAG, "S1SMOKE run cancelled by service lifecycle (${e.message ?: e.javaClass.simpleName}), 回执缺席以此行为准 receipt=$receipt")
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
            runCatching { startForeground(NOTIF_ID, notification) }.onFailure { e2 ->
                // 双形态皆败=进程可被 doze 冻结（第 1 颗雷形态），轮询可能停摆——必须留痕可归因
                Log.w(TAG, "startForeground failed in both forms, doze-freeze risk, task continues", e2)
            }
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

        /** 采集根缺席时的有限重试（转场中原子查询空返回，见 snapshotRoots）。 */
        const val ROOT_RETRY_TIMES = 3
        const val ROOT_RETRY_DELAY_MS = 80L
    }
}

/**
 * 注入总线每条"任务未走正常执行收尾"的退出边（生命周期取消/过期丢弃/忙中丢弃）都必须写回执——
 * 与 encodeReport 同构，UI/测试通道统一消费。results 一律为空：中断场景派发状态不可知，
 * 丢弃场景根本未开始执行；note 讲清语义，避免空 results 被误读为"执行过且零步成功"。
 * 顶层函数（非类成员），JVM 单测可直接调用。
 */
internal fun droppedRunReport(stopCode: String, reason: String, note: String = "任务未走正常执行收尾；results 缺失，不代表已执行/未执行内容"): String =
    buildJsonObject {
        put("stopped", true)
        put("results", buildJsonArray { })
        put(
            "stop_command",
            ContractJson.instance.encodeToJsonElement(
                Command.serializer(),
                Command(
                    commandId = "dropped-${stopCode.lowercase()}-${System.nanoTime()}",
                    type = "stop",
                    source = "accessibility_service",
                    payload = buildJsonObject {
                        put("stop_code", stopCode)
                        put("stop_reason", reason)
                        put("note", note)
                    },
                    confirm = "stopped",
                ),
            ),
        )
    }.toString()

internal fun interruptedRunReport(reason: String): String =
    droppedRunReport(PipelineStopCode.SERVICE_INTERRUPTED, reason)
