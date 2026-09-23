package com.anytouch.app.recorder.capture

import com.anytouch.app.recorder.NodeAction
import com.anytouch.app.recorder.NodeActionKind
import com.anytouch.app.recorder.NodeSnapshot
import com.anytouch.app.recorder.RecEvent
import com.anytouch.app.recorder.WindowChanged

/**
 * AccessibilityEvent → RecEvent 采集适配器（S2-ONDEVICE L0：真事件采集，纯 JVM 可测）。
 *
 * 职责边界：
 * - 本类只做"翻译+采集期瘦身"，不做安全终审——targetPkg 过滤、半步拦截、去抖合并
 *   仍由冻结的 RecorderCompiler 终审（采集面拒收 + 编译面拒收，双侧留痕）。
 * - confirmed 恒 true 且有意为之：录制事件=用户亲为的物理操作（二次确认天然已满足），
 *   高危词命中**不**在录制期枪毙——那会静默改变用户录到的东西；高危由回放期
 *   SafetyQueueRunner 二次确认门把关（"无确认不派发"在执行侧兑现，闭环不双扣）。
 * - 连续同节点打字（每键一发 TEXT_CHANGED）折叠为终态一条：回放语义是"最终输入"，
 *   逐键重放既慢又把中间态当步骤。被折叠的旧事件列入 [staleEvents]，供导出剔除。
 * - 无位移证据的滚动不入流（见 SCROLLED 分支），但逐条记入 [drainNotes]：采集面拒收必须可见，
 *   由会话层写日志回执，判据面才能区分"用户没滚"与"我们不敢记"。
 * - 自家 App（主窗/悬浮球/确认面板）事件永不入流（含其窗口态切换）。
 */
class CaptureAdapter(
    val targetPkg: String,
    private val selfPkg: String,
) {
    /** 已受理事件的有序账本（含将被编译器丢弃的，采集层无权静默丢）。 */
    private val accepted = ArrayList<RecEvent>()
    private val stale = LinkedHashSet<RecEvent>()
    private val newlyStaled = ArrayList<RecEvent>()
    private val notes = ArrayList<String>()
    private var lastTextEvent: NodeAction? = null
    private var lastTextKey: String? = null
    private var lastScrollByNode = HashMap<String, Int>()
    /** 同手势滚动合并：一次慢拖（设备实证 500px/900ms）框架连发 3 条 viewScrolled，逐条成步=回放滚过头。 */
    private var lastGesture: Triple<String, Boolean, Long>? = null

    val acceptedEvents: List<RecEvent> get() = accepted.toList()
    val staleEvents: Set<RecEvent> get() = stale.toSet()

    /** 取走"本轮新被折叠掉"的事件（打字折叠等），调用方据此把会话账与编译账对齐。 */
    fun drainStaled(): List<RecEvent> = newlyStaled.toList().also { newlyStaled.clear() }

    /** 采集期"不成事件"的账（噪声拒收/手势合并明细）：调用方取走即清空，每条必须落成日志痕。 */
    fun drainNotes(): List<String> = notes.toList().also { notes.clear() }

    /** 导出送进会话的有效事件序列（打字折叠后的终态视图）。 */
    fun exportEventList(): List<RecEvent> = accepted.filterNot { it in stale }

    fun onCapture(event: CaptureEvent): RecEvent? {
        val raw = event.snapshot
        if (raw.pkg == selfPkg) return null
        val ts = event.timestampMs
        return when (raw.hint) {
            CaptureHint.WINDOW_CHANGED -> {
                resetTextChain()
                WindowChanged(raw.pkg, windowTitle = null, timestampMs = ts)
            }

            CaptureHint.CLICK ->
                appendNodeAction(raw, NodeActionKind.CLICK, text = null, ts = ts)

            // 长按无回放语义（执行器只支持节点 click）：以 UNKNOWN 入流，编译期 DroppedUnsupportedKind 留痕
            CaptureHint.LONG_CLICK ->
                appendNodeAction(raw, NodeActionKind.UNKNOWN, text = null, ts = ts)

            CaptureHint.TEXT_CHANGED -> {
                val input = raw.text ?: return null
                val key = nodeKey(raw)
                lastTextEvent?.takeIf { lastTextKey == key }?.let { previous ->
                    stale += previous
                    newlyStaled += previous
                }
                lastTextKey = key
                appendNodeAction(raw, NodeActionKind.SET_TEXT, text = input, ts = ts)
                    .also { if (it is NodeAction) lastTextEvent = it }
            }

            CaptureHint.SCROLLED -> {
                // 位移证据三档（全用事件语义量，零坐标参与定位）：
                // ① 事件自带 signed delta 直给；② 同节点绝对位置前后比较；③ 记录里有可滚范围。
                // 三档皆零（设备实证：Settings 页面切换后的布局重排上报 delta=0/pos=0/max=0，
                // 每次点击后必发一条）=通道噪声非用户动作，与 typeWindowContentChanged 同类：
                // 在"是否成事件"这层拒，但记入 [captureNotes] 由会话层留痕——静默吞步骤=第 8 雷形态。
                val key = nodeKey(raw)
                val pos = scrollPos(raw)
                val previous = lastScrollByNode[key]
                val forward = when {
                    raw.scrollDeltaY != 0 -> raw.scrollDeltaY > 0
                    raw.scrollDeltaX != 0 -> raw.scrollDeltaX > 0
                    previous != null && previous != pos -> pos > previous
                    raw.maxScrollY > 0 || raw.maxScrollX > 0 -> !atFarEnd(raw)
                    else -> null
                }
                if (forward == null) {
                    notes += "viewScrolled 无位移证据 pkg=${raw.pkg} id=${raw.resourceId} " +
                        "delta=${raw.scrollDeltaX}x${raw.scrollDeltaY} pos=${raw.scrollX}x${raw.scrollY} " +
                        "max=${raw.maxScrollX}x${raw.maxScrollY}（布局重排，不成步）"
                    return null
                }
                lastScrollByNode[key] = pos
                // 同手势合并：设备实证一次慢拖（500px/900ms）框架连发 3 条 viewScrolled，
                // 逐条成步会把"滚一下"回放成"滚三下"。同节点+同向+间隔在手势窗内=同一次拖拽，
                // 只留首条；合并计数进 notes（回放语义与"用户做了几次滚动"对得上，账也看得见）。
                val gesture = lastGesture
                if (gesture != null && gesture.first == key && gesture.second == forward &&
                    ts - gesture.third in 0 until GESTURE_WINDOW_MS
                ) {
                    lastGesture = Triple(key, forward, ts)
                    notes += "viewScrolled 同手势合并（node=$key dir=${if (forward) "forward" else "backward"} " +
                        "gap=${ts - gesture.third}ms）"
                    return null
                }
                lastGesture = Triple(key, forward, ts)
                appendNodeAction(
                    raw,
                    if (forward) NodeActionKind.SCROLL_FORWARD else NodeActionKind.SCROLL_BACKWARD,
                    text = null,
                    ts = ts,
                )
            }

            CaptureHint.UNKNOWN ->
                appendNodeAction(raw, NodeActionKind.UNKNOWN, text = null, ts = ts)
        }
    }

    private fun appendNodeAction(
        raw: RawNodeSnapshot,
        kind: NodeActionKind,
        text: String?,
        ts: Long,
    ): RecEvent {
        if (kind != NodeActionKind.SET_TEXT) resetTextChain()
        val event = NodeAction(
            kind = kind,
            snapshot = NodeSnapshot(
                resourceId = raw.resourceId,
                text = raw.text,
                contentDesc = raw.contentDesc,
                className = raw.className,
                pkg = raw.pkg,
                indexPath = raw.indexPath,
            ),
            text = text,
            // 用户亲为=已确认；高危不枪毙（见类注释），回放期二次确认门负责拦
            confirmed = true,
            timestampMs = ts,
        )
        accepted += event
        return event
    }

    private fun resetTextChain() {
        lastTextKey = null
        lastTextEvent = null
    }

    /** 纵向可滚优先（设置页语境几乎全是纵滚），否则取横向。 */
    private fun scrollPos(raw: RawNodeSnapshot): Int =
        if (raw.maxScrollY > 0 || raw.scrollY != 0) raw.scrollY else raw.scrollX

    private fun atFarEnd(raw: RawNodeSnapshot): Boolean =
        (raw.maxScrollY > 0 && raw.scrollY == raw.maxScrollY) ||
            (raw.maxScrollX > 0 && raw.scrollX == raw.maxScrollX)

    private fun nodeKey(raw: RawNodeSnapshot): String =
        raw.resourceId?.takeIf { it.isNotBlank() } ?: ("${raw.pkg}/" + raw.indexPath.joinToString(">"))

    private companion object {
        /** 同一次拖拽的事件间隔上限（设备实证：慢拖 900ms 内连发 3 条，条间 ~300ms）。 */
        const val GESTURE_WINDOW_MS = 500L
    }
}

/** 采集事件入参：时间戳由调用方（service/测试假钟）注入，适配器自取时钟=不可测。 */
data class CaptureEvent(
    val snapshot: RawNodeSnapshot,
    val timestampMs: Long,
)

/**
 * 文档序 indexPath 计算（纯 JVM 半区，泛型由平台侧以 AccessibilityNodeInfo 实例化）：
 * 叶子 → root 逐段记"自己是父节点第 k 个直接子节点"，回放端按同序解析。
 * [isRoot] 由调用方裁决"父链走到头这个节点到底是不是根"——设备实证 source 的 parent 会无缘由
 * 返回 null（节点其实深在树中），此时算出的短路径指向另一个节点，回放即误点，判 null 交调用方另证。
 */
fun <T> computeIndexPath(
    node: T,
    parentOf: (T) -> T?,
    childCountOf: (T) -> Int,
    indexOfChild: (T, T) -> Int,
    isRoot: (T) -> Boolean = { true },
): List<Int>? {
    val path = ArrayList<Int>()
    var current: T? = node
    while (current != null) {
        val parent = parentOf(current)
            ?: return if (isRoot(current)) path else null // 终点必须真的是根，否则这串下标指向别的节点
        val index = indexOfChild(parent, current)
        if (index < 0) return listOf(-1) // 父链不可解析（源节点不在我们可遍历的窗）：交编译期 DroppedInvalidPath 留痕，不伪造路径
        path.add(0, index)
        current = parent
    }
    return path // node 本身为 null 的防御口径：空路径=根
}
