package com.anytouch.app.recorder.capture

/**
 * 采集层对"被操作节点"的最小读形（军令 L1：本结构不得含任何坐标字段——
 * 节点 bounds 一概不进快照；滚动量取自 AccessibilityEvent/Record 的滚动载荷，
 * 那是"用户朝哪个方向滚了多少"的事件语义，不参与也不构成任何定位匹配）。
 */
data class RawNodeSnapshot(
    val resourceId: String?,
    val text: String?,
    val contentDesc: String?,
    val className: String?,
    val pkg: String,
    val indexPath: List<Int>,
    /** 仅 SCROLLED 事件有意义：本次滚动量（有符号，API23+ 直给）。 */
    val scrollDeltaX: Int = 0,
    val scrollDeltaY: Int = 0,
    /** 滚动后的绝对位置与可达上限（同节点相邻两次比较/到底判定用）。 */
    val scrollX: Int = 0,
    val scrollY: Int = 0,
    val maxScrollX: Int = 0,
    val maxScrollY: Int = 0,
    /** 源节点自身是否可滚（SCROLLED 判噪用：不可滚节点上报的"滚动"=布局重排噪声）。 */
    val scrollable: Boolean = false,
    val hint: CaptureHint = CaptureHint.UNKNOWN,
)

/** 事件子类型提示。 */
enum class CaptureHint { WINDOW_CHANGED, CLICK, LONG_CLICK, TEXT_CHANGED, SCROLLED, UNKNOWN }
