package com.anytouch.app.executor

import com.anytouch.contracts.ActionType

/**
 * 执行器三条真实判据的 android-free 纯函数面（STAGE-31-B：A1 重派计划 / A2 文本派发边 / A3 兜底扫树）。
 *
 * 三条判据原来住在 `NodeTaskRunner.kt` 的**派发边与复核边**里（JVM 覆盖=0 的最后一大块盲区），
 * 现在**整体搬进**本文件：平台侧只做"把活树/布尔摊成参数"与"转调"，一条判据分支都不留在平台侧
 * （雷 18 同族的既有教训：两处实现只有坏的那条会被看见）。
 *
 * 本文件 android-free：不接 `AccessibilityNodeInfo` / `AccessibilityEvent` / `Context` / `android.util.Log`，
 * 也不写日志；参数与返回值只有布尔、整数、字符串和下面两个最小数据形态。
 *
 * **动作派发本身不在这里**（军令 §1 A1 点名的假下沉形态）：`performAction`、`delay()`、设备句柄
 * 一律留在平台侧。本文件只回答"该不该再派 / 这一路算不算被接收 / 全树里有没有真落字"三个判断。
 */

/* ========================= A1：明示拒绝后的重派计划 ========================= */

/** [redispatchPlan] 的三档结论。三档落点互不相同，调用方不必猜 null 也不必自己数轮数。 */
enum class Redispatch {
    /** 不必重派：派发已被接收，或该类型压根不参与重派。 */
    Skip,

    /** 按原线索沉降后重定位一次，再派一次。 */
    RetryOnce,

    /** 封顶已过：收 `perform_failed`，绝不再派。 */
    GiveUp,
}

/** 重派封顶轮数——"恰好一次"这半条判据就住在这里，平台侧不另数一遍。 */
private const val MAX_REDISPATCH_ROUNDS = 1

/**
 * A1：派发被**明示拒绝**（[performed] = false）且该类型参与重派时，沉降后按原线索重定位再派一次；
 * 仍 false 才收 `perform_failed`。**封顶恰好一次**：[attempt] 一到 [MAX_REDISPATCH_ROUNDS] 即 [Redispatch.GiveUp]，
 * 无循环是这条判据的一半（K40/MIUI 雷 12 是**持久**拒绝，放宽就会对同一台机器无限重派）。
 *
 * 为什么"再派一次"允许（不是装饰）：`false` 的含义是动作**根本没执行过**——线索未被消耗、无输入落盘，
 * 所以这一次重定位+重派是零副作用自证（设备实证：AVD 三档矩阵收口轮 C1/C7、MarvisPhone 重启后 C7
 * 的"句柄陈旧假红"形态）；而 `true` 之后的任何再派都是重复点击/重复输入，一档都不给。
 *
 * WAIT 不参与：它在 `NodeTaskRunner.run` 里走 `delay` 分支、从不派发，这里的排除只是把原判据的
 * `action.type != WAIT` 那一层照搬进来。
 *
 * @param type `Action.type` 的字符串值。contracts 侧 `ActionType` 是常量对象而非枚举
 * （`core/contracts/src/main/kotlin/com/anytouch/contracts/Constants.kt:21`，"非严格枚举，留扩展位"），
 * 故形参类型是 `String`；比较仍写 `ActionType.WAIT`，判据语义与平台侧原字面一致。
 * @param attempt 已经重派过几轮（首派后为 0）。
 */
fun redispatchPlan(performed: Boolean, type: String, attempt: Int): Redispatch {
    if (performed) return Redispatch.Skip
    if (type == ActionType.WAIT) return Redispatch.Skip
    if (attempt >= MAX_REDISPATCH_ROUNDS) return Redispatch.GiveUp
    return Redispatch.RetryOnce
}

/* ========================= A2：TYPE_TEXT 的两通道派发边 ========================= */

/**
 * A2：`TYPE_TEXT` 的派发边 = SET_TEXT 与 PASTE 两条通道的**合计被接收**。真值表四格锁死"两通道各自与合计"：
 * 任一通道被接收即为 true，双双**明示拒绝**才是 false（雷 12 的对称面：K40/MIUI 搜索框 SET_TEXT 会直接
 * 返回 false，兜底通道必须在这条边也上场，而非只治"虚报 true"）。
 *
 * **成败终裁不在这里**：true 只代表"派发被接收"，落字与否由 A3 那一侧判——模拟器实测两条通道都会
 * 虚报 true 而不落字（`performAction=true` 不作数）。
 *
 * 短路（何时真去调 `device.pasteText`）留在平台侧：那是一次**有副作用的设备调用**，
 * 把它表达进本函数就等于要求平台侧无条件派发 PASTE，反而制造语义变化。
 */
fun textDispatchRoute(setTextOk: Boolean, pasteOk: Boolean): Boolean = setTextOk || pasteOk

/* ========================= A3：句柄活读读不到时的兜底扫树 ========================= */

/**
 * [landedViaTreeScan] 的最小输入形态：只带判据要读的两个字段，由平台侧从活树按文档序摊平。
 * 刻意不外引 `UiNode`，也**不**新造公共节点抽象（越界=改契约面）。
 */
data class LandedNodeFact(
    val className: String?,
    val text: String?,
)

/** 可编辑类的类名标记（框架类名里的子串，如 `android.widget.EditText`）。 */
private const val EDITABLE_CLASS_MARKER = "EditText"

/**
 * A3：落字复核的**兜底扫树**判据。
 *
 * 复核以派发句柄活读为准（那是 `device.textOf`→refresh 的活读，属取数，不是本函数的输入）；
 * **句柄读不到**才走到这里扫全树（AVD API 35 搜索页实证：过渡期派发给旧句柄后 Compose 整节点换新，
 * 字已落进新输入框而旧句柄永远读不到——不扫树就把"真落字"报成 `set_text_unverified` 假红）。
 *
 * 匹配键 = **输入本身**（不是原线索：线索文本会被输入改掉，按线索重定位会配到别的节点造成假阴性）。
 * 命中必须**同时**满足两条件，缺一不可：
 * ① `className` 含 [EDITABLE_CLASS_MARKER]（限定可编辑类，防搜索结果列表那种 `TextView` 含同词的假阳性）；
 * ② `text` 含 [want]（子串包含，与句柄活读一侧同口径，故节点文本首尾带空格仍须命中）。
 *
 * 另：[want] 为空白时**不得短路成命中**——`"任意文本".contains("")` 在 Kotlin 里恒真，
 * 空白输入不构成落字凭据（原判据外层那句 `if (want.isNotEmpty())` 一并搬进来，平台侧不留）。
 *
 * @return 文档序第一个满足两条件的节点（调用方自己把 `text` 读回去）；无命中返回 null。
 */
fun landedViaTreeScan(nodes: List<LandedNodeFact>, want: String): LandedNodeFact? {
    if (want.isBlank()) return null
    val key = want.trim()
    return nodes.firstOrNull {
        it.className?.contains(EDITABLE_CLASS_MARKER) == true && it.text?.contains(key) == true
    }
}
