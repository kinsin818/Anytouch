package com.anytouch.app.recorder

/**
 * 录制事件流输入结构 —— 字段名钉死于军令 ANYTOUCH-S2-FRAMEWORK-20260922 L2。
 * 硬约束（L1）：本包任何结构不含坐标字段（x/y/bounds 均不得出现），定位词汇只走节点引用。
 */
sealed class RecEvent {
    abstract val timestampMs: Long
}

/** 窗口切换：编译器以此维护"当前窗口包名"，targetPkg 过滤的窗口态来源。 */
data class WindowChanged(
    val pkg: String,
    val windowTitle: String? = null,
    override val timestampMs: Long,
) : RecEvent()

/** 节点动作类型。UNKNOWN 为军令 L2-8 预留的未知子类型位（真适配器遇到不认识的事件落这里）。 */
enum class NodeActionKind { CLICK, SET_TEXT, SCROLL_FORWARD, SCROLL_BACKWARD, UNKNOWN }

/** 动作目标的节点快照：路径兜底只有叶子 className + 父链文档序可用（祖先类名不在结构内）。 */
data class NodeSnapshot(
    val resourceId: String? = null,
    val text: String? = null,
    val contentDesc: String? = null,
    val className: String? = null,
    val pkg: String,
    val indexPath: List<Int>,
)

/** confirmed 缺省 false = 默认拒绝，与 S1 SafetyGate fail-closed 口径对齐（军令 L2-3）。 */
data class NodeAction(
    val kind: NodeActionKind,
    val snapshot: NodeSnapshot,
    val text: String? = null,
    val confirmed: Boolean = false,
    override val timestampMs: Long,
) : RecEvent()
