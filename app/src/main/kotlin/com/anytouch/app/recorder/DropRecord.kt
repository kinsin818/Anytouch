package com.anytouch.app.recorder

/**
 * STAGE-02 回执 6 类（军令 L2-10）：未找到节点/页面漂移/弹窗/权限中断/超时/其他。
 * 稳定码约定：NODE_NOT_FOUND 与冻结文件 LocatorMiss.NODE_NOT_FOUND 同值；
 * 编译期真实产出的类只有 未找到节点/页面漂移/权限中断/其他 四类——
 * 弹窗与超时是执行期失败类，录制转换器不产、但映射表必须全覆盖（回执归因侧一套枚举）。
 */
enum class ReceiptFailureClass(val code: String) {
    NODE_NOT_FOUND("NODE_NOT_FOUND"),
    PAGE_DRIFT("PAGE_DRIFT"),
    POPUP_INTERCEPTED("POPUP_INTERCEPTED"),
    PERMISSION_INTERRUPTED("PERMISSION_INTERRUPTED"),
    TIMEOUT("TIMEOUT"),
    OTHER("OTHER"),
}

/** 丢弃原因（编译器内部口径），每条经 [receiptClass] 归入 6 类回执。 */
enum class DropReason(val receiptClass: ReceiptFailureClass) {
    /** snapshot.pkg 非 targetPkg —— 跨 App 事件 */
    CROSS_PACKAGE(ReceiptFailureClass.OTHER),

    /** snapshot.pkg 是 targetPkg 但当前窗口不在 targetPkg（含窗口事件缺失） */
    WINDOW_CONTEXT_MISMATCH(ReceiptFailureClass.PAGE_DRIFT),

    /** 未知事件子类型（L2-8 预留枚举位） */
    UNSUPPORTED_KIND(ReceiptFailureClass.OTHER),

    /** confirmed=false 高危动作，默认拒绝（L2-3） */
    SAFETY_UNCONFIRMED(ReceiptFailureClass.PERMISSION_INTERRUPTED),

    /** SET_TEXT 缺输入文本等"半步"（L2-7 不产半步） */
    HALF_STEP(ReceiptFailureClass.OTHER),

    /** indexPath 含负数，层级路径回放必失败（L2-10 之"未找到节点"） */
    INVALID_INDEX_PATH(ReceiptFailureClass.NODE_NOT_FOUND),
}

/** 一条丢弃归因：eventIndex 指回输入列表下标，可追溯。 */
sealed interface DropRecord {
    val eventIndex: Int
    val timestampMs: Long
    val reason: DropReason
    val detail: String

    val receiptClass: ReceiptFailureClass get() = reason.receiptClass
}

data class DroppedCrossPackage(
    override val eventIndex: Int,
    override val timestampMs: Long,
    override val detail: String,
) : DropRecord {
    override val reason: DropReason get() = DropReason.CROSS_PACKAGE
}

data class DroppedByWindowDrift(
    override val eventIndex: Int,
    override val timestampMs: Long,
    override val detail: String,
) : DropRecord {
    override val reason: DropReason get() = DropReason.WINDOW_CONTEXT_MISMATCH
}

data class DroppedUnsupportedKind(
    override val eventIndex: Int,
    override val timestampMs: Long,
    override val detail: String,
) : DropRecord {
    override val reason: DropReason get() = DropReason.UNSUPPORTED_KIND
}

data class DroppedBySafety(
    override val eventIndex: Int,
    override val timestampMs: Long,
    override val detail: String,
) : DropRecord {
    override val reason: DropReason get() = DropReason.SAFETY_UNCONFIRMED
}

data class DroppedHalfStep(
    override val eventIndex: Int,
    override val timestampMs: Long,
    override val detail: String,
) : DropRecord {
    override val reason: DropReason get() = DropReason.HALF_STEP
}

data class DroppedInvalidPath(
    override val eventIndex: Int,
    override val timestampMs: Long,
    override val detail: String,
) : DropRecord {
    override val reason: DropReason get() = DropReason.INVALID_INDEX_PATH
}
