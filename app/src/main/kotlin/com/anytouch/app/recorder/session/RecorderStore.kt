package com.anytouch.app.recorder.session

import android.util.Log
import com.anytouch.app.recorder.RecEvent
import com.anytouch.app.recorder.RecorderCompiler
import com.anytouch.app.recorder.RecorderOutput
import com.anytouch.app.recorder.WindowChanged
import com.anytouch.app.recorder.capture.CaptureAdapter
import com.anytouch.app.recorder.capture.CaptureEvent
import com.anytouch.app.recorder.capture.RawNodeSnapshot
import com.anytouch.app.recorder.encodeActions
import kotlinx.coroutines.flow.MutableStateFlow

/** 编译产物 + 序列化后的任务 JSON（可直接进 AppState 任务总线）。 */
data class CompileResult(
    val output: RecorderOutput,
    val taskJson: String,
)

/**
 * 录制会话编排（S2-ONDEVICE 主窗窄口）：会话生命周期的唯一写方。
 * - 控制边全留痕：start/inject/stop/拒绝 每条边都写 S2SMOKE 日志回执（对齐注入总线"必留痕"纪律，
 *   静默失踪=黑洞，与虚报同罪）；
 * - UI 与 adb 双通道走同一入口，会话在内存不落盘（跨 App 内容落盘=隐私红线，持久化另案待裁）；
 * - 编译只调冻结的 RecorderCompiler，本层零终审逻辑（丢弃归因由产物自带）。
 */
object RecorderStore {

    private const val TAG = "AnytouchRun"

    /** 冒烟与默认目标（S2 阶段唯一稳定可钻取的应用；包名选择器 UI 属后续另案）。 */
    const val DEFAULT_TARGET_PKG = "com.android.settings"

    var session: RecorderSession? = null
        private set

    /** 服务采集钩子唯一读取点：非空且 RECORDING 才转译入流。 */
    val activeSession = MutableStateFlow<RecorderSession?>(null)

    /** UI 消费的一次性建议：编译完成时写入，用户手改任务框即清空（不夺用户已敲的字）。 */
    val suggestedTaskJson = MutableStateFlow<String?>(null)

    /**
     * 录制目标包：主窗输入框写入，悬浮球开录取此值。
     * S2 阶段默认设置 App（唯一在 CI/冒烟里可稳定三级钻取的目标）；包名选择器 UI 属后续另案。
     */
    @Volatile
    var targetPkg: String = DEFAULT_TARGET_PKG

    /** 编译产物（步序账）：步骤编辑页（删步/改名/移序）与 UI 展示的单一数据源，随 start 作废。 */
    val compiledActions = MutableStateFlow<List<com.anytouch.contracts.Action>>(emptyList())

    val isRecording: Boolean get() = session?.state == SessionState.RECORDING

    fun start(targetPkg: String = this.targetPkg): SessionOutcome {
        val fresh = RecorderSession(targetPkg = targetPkg)
        val outcome = fresh.start()
        return when (outcome) {
            is SessionOutcome.Accepted -> {
                session = fresh
                this.targetPkg = targetPkg
                boundSession = null
                boundAdapter = null
                foldedAway.clear()
                compiledActions.value = emptyList()
                activeSession.value = fresh
                // 开窗基线：窗态事件只在"换窗"瞬间下发，直接开始录制时会话内将无任何窗口态，
                // 编译器按冻结口径把全部动作判 DroppedByWindowDrift。用户显式选 targetPkg 开录
                // = "开窗即在目标包"这一事实的陈述，补记为会话首个事件（windowTitle 标注来源，账目诚实）。
                fresh.append(
                    WindowChanged(targetPkg, windowTitle = "session-open", timestampMs = fresh.nextTimestampMs()),
                )
                Log.i(TAG, "S2SMOKE record start target=$targetPkg")
                outcome
            }
            // 理论上不可达（新建会话恒 IDLE），防御口径：拒绝即不留半启动态
            is SessionOutcome.Rejected -> {
                Log.w(TAG, "S2SMOKE record start rejected: ${outcome.detail}")
                outcome
            }
        }
    }

    /** adb 预置会话注入（不经 UI，S2-ONDEVICE 冒烟通道）：注入即 STOPPED 只读档，回放前无须再录。 */
    fun injectSerialized(json: String): SessionRestore {
        val restore = RecorderSession.deserialize(json)
        when (restore) {
            is SessionRestore.Loaded -> {
                session = restore.session
                activeSession.value = restore.session
                Log.i(TAG, "S2SMOKE record inject accepted events=${restore.session.eventList().size} target=${restore.session.targetPkg}")
            }
            is SessionRestore.Failed ->
                Log.w(TAG, "S2SMOKE record inject rejected stage=CORRUPT_ARCHIVE detail=${restore.detail}")
        }
        return restore
    }

    /** 自家包名（service onServiceConnected 注入；null 前采集边一律不开工，fail-closed）。 */
    @Volatile
    var selfPkg: String? = null

    private var boundSession: RecorderSession? = null
    private var boundAdapter: CaptureAdapter? = null

    /** 采集期被折叠的中间态（打字逐键→终态）：会话账保留、编译账剔除，两侧各自可查。 */
    private val foldedAway = LinkedHashSet<RecEvent>()

    /** 采集桥投递口（Android 侧唯一调用方=service 钩子）。适配器随会话新建；注入档非 RECORDING 不收。 */
    fun bridgeDeliver(raw: RawNodeSnapshot) {
        val current = activeSession.value ?: return
        if (current.state != SessionState.RECORDING) return
        val adapter = synchronized(this) {
            if (boundSession !== current) {
                val self = selfPkg ?: run {
                    Log.w(TAG, "S2SMOKE capture skipped: selfPkg 未绑定（服务未完成接线）")
                    return
                }
                boundSession = current
                boundAdapter = CaptureAdapter(current.targetPkg, self)
            }
            boundAdapter ?: return
        }
        val event = adapter.onCapture(CaptureEvent(raw, current.nextTimestampMs()))
        // 采集面的两类"账外事件"逐条落痕：噪声拒收（无位移证据的滚动等）+ 被折叠的中间态。
        // 折叠件仍留在会话账里（RecorderSession 是"用户做过的每一动"的完整账本），
        // 只在编译前剔除——两套账目各自可查，谁都不许静默蒸发。
        adapter.drainNotes().forEach { Log.w(TAG, "S2SMOKE capture note: $it") }
        adapter.drainStaled().forEach { foldedAway += it }
        if (event == null) return
        appendToSession(current, event)
    }

    /**
     * 服务消亡边（onDestroy 调用）：采集钩子无人驱动，摘钩留痕。
     * 会话本体**保留**——已录事件对用户仍有价值，UI 可"停止并编译"拿到已录部分；
     * 若直接抹掉=用户录制无声蒸发（第 8 雷形态：无痕丢失与虚报同罪）。
     */
    fun abandonRecording() {
        val current = session
        if (current != null && current.state == SessionState.RECORDING) {
            Log.w(TAG, "S2SMOKE record abandoned by service lifecycle, 已录事件保留可编译（不静默蒸发）")
        }
        activeSession.value = null
        synchronized(this) {
            boundSession = null
            boundAdapter = null
        }
    }

    /**
     * 服务接线/重连边（onServiceConnected 调用）：录制会话仍在 RECORDING 则重新挂钩续采。
     * 设备实证：`uiautomator dump` 注册的 UiTestAutomationService 会把自家服务挤下线再回来；
     * 真实场景里 ROM 重启无障碍服务同样会发生——"半途掉线=整段录制静默作废"不可接受，
     * 但也不假装无缝：重连痕必须可见，掉线期间漏掉的动作用户自己判断。
     */
    fun onServiceReconnected() {
        val current = session ?: return
        if (current.state == SessionState.RECORDING) {
            activeSession.value = current
            Log.w(TAG, "S2SMOKE record rebound: 服务重连后续采（掉线窗口内的事件已漏，见服务生命周期日志）")
        }
    }

    private fun appendToSession(current: RecorderSession, event: RecEvent) {
        val outcome = current.append(event)
        if (outcome is SessionOutcome.Rejected) {
            // 拒收必留痕：overflow/非法态事件已计入 rejectedEvents，日志给归因，禁静默丢
            Log.w(TAG, "S2SMOKE append rejected: ${outcome.detail}")
        }
    }

    /**
     * 停止并编译（RECORDING→STOPPED 或直接编译只读注入档）。空动作数组**不**写建议——
     * 把"录制全被丢弃"静默变成"空任务可回放"是假绿形态；归因走日志明细。
     */
    fun stopAndCompile(): CompileResult {
        val current = session
        if (current == null) {
            Log.w(TAG, "S2SMOKE record stop rejected: 无会话")
            return CompileResult(RecorderOutput(emptyList(), emptyList(), 0), "[]")
        }
        if (current.state == SessionState.RECORDING || current.state == SessionState.PAUSED) {
            current.stop()
        }
        val sessionEvents = current.eventList()
        val events = sessionEvents.filterNot { it in foldedAway }
        if (foldedAway.isNotEmpty()) {
            Log.i(TAG, "S2SMOKE folded ${foldedAway.size}/${sessionEvents.size} 中间态事件（打字逐键→终态），编译用 ${events.size} 条")
        }
        val output = RecorderCompiler().compile(events, current.targetPkg)
        val json = encodeActions(output.actions)
        activeSession.value = null
        Log.i(
            TAG,
            "S2SMOKE compiled ok actions=${output.actions.size} drops=${output.drops.size} " +
                "merged=${output.merged} events=${events.size} target=${current.targetPkg}",
        )
        output.drops.forEach { drop ->
            Log.i(TAG, "S2SMOKE-DETAIL drop index=${drop.eventIndex} reason=${drop.reason} detail=${drop.detail}")
        }
        // 步序逐条留痕：录出来的东西必须可审计（哪一步、凭什么线索定位），也是测试通道的取数口
        output.actions.forEachIndexed { i, action ->
            Log.i(TAG, "S2SMOKE-STEP index=$i type=${action.type} value=${action.value}")
        }
        if (output.actions.isEmpty()) {
            Log.w(TAG, "S2SMOKE compiled EMPTY task, 不写回放建议（丢弃归因见 DETAIL）")
        } else {
            compiledActions.value = output.actions
            suggestedTaskJson.value = json
            // 测试通道取件口：脚本据此把"录出来的步骤"回注执行，验证录→编→放闭环
            Log.i(TAG, "S2SMOKE-TASK $json")
        }
        return CompileResult(output, json)
    }
}
