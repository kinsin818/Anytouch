package com.anytouch.app.recorder

import com.anytouch.contracts.Action
import com.anytouch.contracts.ActionSafety
import com.anytouch.contracts.ActionSource
import com.anytouch.contracts.ActionType
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 转换产物：可执行步骤 + 全量丢弃归因 + 去抖合并计数（军令 L2-1/4）。 */
data class RecorderOutput(
    val actions: List<Action>,
    val drops: List<DropRecord>,
    val merged: Int,
) {
    fun droppedCount(reason: DropReason): Int = drops.count { it.reason == reason }
}

/**
 * 录制事件流 → S1 执行器任务步骤（军令 L0）。纯 JVM；输出 value 键名即
 * NodeTaskRunner.toLocatorRequest 的解码口径：resource_id / text / content_desc / path / input / direction。
 *
 * 定位词汇优先级（L2-5）：resourceId 独占 → text/contentDesc（trim 全等）→ 层级路径兜底。
 */
class RecorderCompiler(private val debounceMs: Long = DEFAULT_DEBOUNCE_MS) {

    fun compile(events: List<RecEvent>, targetPkg: String): RecorderOutput {
        val actions = ArrayList<Action>()
        val drops = ArrayList<DropRecord>()
        var merged = 0
        var windowPkg: String? = null
        var lastClickPath: List<Int>? = null
        var lastClickTs = Long.MIN_VALUE

        for ((index, event) in events.withIndex()) {
            when (event) {
                is WindowChanged -> {
                    windowPkg = event.pkg
                    lastClickPath = null
                }
                is NodeAction -> {
                    val drop = classify(event, index, targetPkg, windowPkg)
                    if (drop != null) {
                        drops += drop
                        lastClickPath = null
                        continue
                    }
                    when (event.kind) {
                        NodeActionKind.CLICK -> {
                            val path = event.snapshot.indexPath
                            val last = lastClickPath
                            val delta = event.timestampMs - lastClickTs
                            if (last != null && last == path && delta >= 0 && delta < debounceMs) {
                                // 合并进已产步骤：时间戳与 actionId 保持首次出现（L2-4，计数而非替换）
                                merged++
                            } else {
                                actions += step(event, index, ActionType.CLICK) { }
                                lastClickPath = path
                                lastClickTs = event.timestampMs
                            }
                        }
                        NodeActionKind.SET_TEXT -> {
                            actions += step(event, index, ActionType.TYPE_TEXT) { put(INPUT_KEY, event.text!!) }
                            lastClickPath = null
                        }
                        NodeActionKind.SCROLL_FORWARD -> {
                            actions += step(event, index, ActionType.SCROLL) { put(DIRECTION_KEY, "forward") }
                            lastClickPath = null
                        }
                        NodeActionKind.SCROLL_BACKWARD -> {
                            actions += step(event, index, ActionType.SCROLL) { put(DIRECTION_KEY, "backward") }
                            lastClickPath = null
                        }
                        NodeActionKind.UNKNOWN -> {
                            // classify 已拦截并计数；此处兜底保持"不产半步"不变式
                            lastClickPath = null
                        }
                    }
                }
            }
        }
        return RecorderOutput(actions, drops, merged)
    }

    /** 判定顺序即归因优先级：跨包 → 窗口态 → 未知子类型 → 安全默认拒绝 → 半步 → 非法路径。 */
    private fun classify(event: NodeAction, index: Int, targetPkg: String, windowPkg: String?): DropRecord? {
        val ts = event.timestampMs
        return when {
            event.snapshot.pkg != targetPkg -> DroppedCrossPackage(
                index, ts, "事件包 ${event.snapshot.pkg} != 目标包 $targetPkg",
            )
            windowPkg != targetPkg -> DroppedByWindowDrift(
                index, ts, "当前窗口包 ${windowPkg ?: "<无窗口事件>"} != 目标包 $targetPkg",
            )
            event.kind == NodeActionKind.UNKNOWN -> DroppedUnsupportedKind(
                index, ts, "未知事件子类型 ${event.kind}，丢弃不崩（L2-8）",
            )
            !event.confirmed -> DroppedBySafety(
                index, ts, "confirmed=false 默认拒绝，高危未获二次确认（L2-3）",
            )
            event.kind == NodeActionKind.SET_TEXT && event.text == null -> DroppedHalfStep(
                index, ts, "SET_TEXT 未携带输入文本，不产半步（L2-7）",
            )
            event.snapshot.indexPath.any { it < 0 } -> DroppedInvalidPath(
                index, ts, "indexPath 含负数下标 ${event.snapshot.indexPath}，层级路径回放必失败",
            )
            else -> null
        }
    }

    private fun step(event: NodeAction, eventIndex: Int, type: String, extras: JsonObjectBuilder.() -> Unit): Action {
        val value = buildJsonObject {
            putLocatorClues(event.snapshot)
            extras()
        }
        return Action(
            actionId = "$ACTION_ID_PREFIX${"%04d".format(eventIndex)}",
            type = type,
            target = null,
            value = value,
            source = ActionSource.NODE,
            // S1 执行器三门禁缺省全 false = 不可执行；录制步骤是用户自主动作且已过 confirmed 闸，
            // 故显式放行 click 门（viewport_ok 对节点定位链无坐标语义、requires_transition 只标记不拦截）。
            safety = ActionSafety(viewportOk = true, clickEnabled = true, requiresTransition = false),
        )
    }

    companion object {
        const val DEFAULT_DEBOUNCE_MS = 300L
        const val ACTION_ID_PREFIX = "rec-"
        const val RESOURCE_ID_KEY = "resource_id"
        const val TEXT_KEY = "text"
        const val CONTENT_DESC_KEY = "content_desc"
        const val PATH_KEY = "path"
        const val INPUT_KEY = "input"
        const val DIRECTION_KEY = "direction"
    }
}

/**
 * 层级路径串生成（军令 L2-5 三阶词汇，L3 算法自决）。
 *
 * 形如 `*[i0+1]>*[i1]>…>*[ik]`（'>' 分隔即 PathPatternParser 语法）。不采用
 * `className[i]` 段的原因：NodeSnapshot 只带叶子类名、无祖先链类名，且解析器的 [n]
 * 是"同类候选集内序号"而非子节点文档序——用子节点下标填 className 段在带同名兄弟的
 * 真实树上必 miss。AnyNode 段 '*' 的候选集 = 锚点子树层序（父先、兄弟按子序），
 * 子节点 k 的 rank 恒等于 k；唯首段候选集含 root 自身，故子下标整体 +1。零转义风险
 * （不嵌入任何字符串值），可被 NodeTreeLocator 无损回放（往返用例证明）。
 */
internal fun indexPathToPathString(indexPath: List<Int>): String {
    require(indexPath.all { it >= 0 }) { "indexPath must be non-negative but was $indexPath" }
    if (indexPath.isEmpty()) return "$ANY_NODE_SEGMENT[0]" // 目标即根节点：*[0] 的候选集首元就是 root
    return indexPath.mapIndexed { k, childIndex ->
        val rank = if (k == 0) childIndex + FIRST_SEGMENT_OFFSET else childIndex
        "$ANY_NODE_SEGMENT[$rank]"
    }.joinToString(SEGMENT_SEPARATOR)
}

private const val ANY_NODE_SEGMENT = "*"
private const val FIRST_SEGMENT_OFFSET = 1
private const val SEGMENT_SEPARATOR = ">"

/** 词汇优先级直传：id 独占；否则 trim 全等 text/desc；否则层级路径。 */
internal fun JsonObjectBuilder.putLocatorClues(snapshot: NodeSnapshot) {
    snapshot.resourceId?.takeIf { it.isNotBlank() }?.let {
        put(RecorderCompiler.RESOURCE_ID_KEY, it)
        return
    }
    val text = snapshot.text?.trim()?.takeIf { it.isNotEmpty() }
    val desc = snapshot.contentDesc?.trim()?.takeIf { it.isNotEmpty() }
    if (text != null) put(RecorderCompiler.TEXT_KEY, text)
    if (desc != null) put(RecorderCompiler.CONTENT_DESC_KEY, desc)
    if (text == null && desc == null) put(RecorderCompiler.PATH_KEY, indexPathToPathString(snapshot.indexPath))
}
