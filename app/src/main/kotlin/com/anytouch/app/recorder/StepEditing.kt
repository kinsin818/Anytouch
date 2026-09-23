package com.anytouch.app.recorder

import com.anytouch.contracts.Action
import com.anytouch.contracts.ActionType
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 步骤编辑请求（军令 L2-9 的调用侧描述）。
 * UI 行内按钮与 adb 注入通道都只构造这个类型，一起送进 RecorderStore 的唯一写口——
 * 门禁落在入口而不是按钮上（S2 实测教训：置灰不是门禁，注入通道绕过按钮）。
 */
sealed class StepEdit {
    abstract val index: Int

    data class Remove(override val index: Int) : StepEdit()

    /** 改名为改 actionId（步序账里唯一用户可改字段；定位线索改了=改录出来的事实，另案）。 */
    data class Rename(override val index: Int, val newActionId: String) : StepEdit()

    /** index = 源位（from），to = 目标位。 */
    data class Move(override val index: Int, val to: Int) : StepEdit()
}

/**
 * 编辑门禁判定（纯函数、零平台依赖，JVM 可锁）。
 * 各档拒因对应的都是"静默蒸发"形态，故一律 fail-closed 而非容错继续：
 * - [RUNNING]：执行中改账本=回放依据与执行现场脱节，且改完的那份账再也不是刚跑过的那份（老板 09-23 裁决落地）。
 * - [EMPTY_LEDGER]：无编译产物即无步骤可编。放行=用户点了删，任务却照常可放（空任务假绿）。
 * - [OUT_OF_RANGE]：列表已变短而句柄仍指旧下标（重组合与点击之间的竞态）。冻结原语此处会 `require` 抛异常
 *   ——崩在 UI 线程等于无痕丢失，必须在入口拒并留痕。
 * - [BLANK_NAME]：空 actionId 会让任务 JSON 出现无名步骤，回放归因无从挂起。
 */
enum class StepEditGate {
    READY,

    /** 执行中禁编辑（老板 09-23 裁决 2：以禁编辑消解停止球与"改名"钮的重叠区，不挪球）：见三参 [stepEditGateOf]。 */
    RUNNING,
    EMPTY_LEDGER,
    OUT_OF_RANGE,
    BLANK_NAME,
}

fun stepEditGateOf(actions: List<Action>, edit: StepEdit): StepEditGate = when {
    actions.isEmpty() -> StepEditGate.EMPTY_LEDGER
    edit.index !in actions.indices -> StepEditGate.OUT_OF_RANGE
    edit is StepEdit.Move && edit.to !in actions.indices -> StepEditGate.OUT_OF_RANGE
    edit is StepEdit.Rename && edit.newActionId.isBlank() -> StepEditGate.BLANK_NAME
    else -> StepEditGate.READY
}

/**
 * 带执行态的完整判定（唯一写口用这一条）。RUNNING 排在最前：执行中账本不许动，
 * 至于"账是不是空、下标越不越界"都是次一级问题，先拒了再说。
 */
fun stepEditGateOf(actions: List<Action>, edit: StepEdit, running: Boolean): StepEditGate =
    if (running) StepEditGate.RUNNING else stepEditGateOf(actions, edit)

/**
 * 编辑拒因的过期边（与 [startRejectionAfterStateChange] 同一条纪律）：只有 RUNNING 这一档是
 * **纯状态档**——它描述的是"此刻正在执行"，执行一结束话术就失去依据，留在屏上就是假红。
 * 其余三档（空账/越界/空名）都绑在用户那一次请求上，由下一次请求覆盖，不许被状态跃迁悄悄抹掉。
 */
fun editRejectionAfterStateChange(current: StepEditGate?, running: Boolean): StepEditGate? =
    if (current == StepEditGate.RUNNING && !running) null else current

/** 用户向话术（L2-③：错误必显示，禁静默禁用）。 */
fun StepEditGate.userCopy(): String? = when (this) {
    StepEditGate.READY -> null
    StepEditGate.RUNNING -> "任务执行中不能改步骤：账本一边跑一边改，回放依据就对不上执行现场了。" +
        "请等本轮结束（或点悬浮球停止）后再删改。"
    StepEditGate.EMPTY_LEDGER -> "当前没有可编辑的步骤：请先录制并点「停止并编译」，有步骤后才能增删改。"
    StepEditGate.OUT_OF_RANGE -> "该步骤已不存在（列表刚刚变动过）：请刷新查看当前步骤，不要按旧序号操作。"
    StepEditGate.BLANK_NAME -> "步骤名不能为空：留空会让这一步在回放报告里无法归因。"
}

/** 编辑后生效的步序账（供展示与序列化）。 */
fun applyStepEdit(actions: List<Action>, edit: StepEdit): List<Action> = when (edit) {
    is StepEdit.Remove -> removeStep(actions, edit.index)
    is StepEdit.Rename -> renameStep(actions, edit.index, edit.newActionId.trim())
    is StepEdit.Move -> moveStep(actions, edit.index, edit.to)
}

/** 留痕用的操作名（日志里必须看得见做的是哪一种编辑）。 */
fun StepEdit.opName(): String = when (this) {
    is StepEdit.Remove -> "remove"
    is StepEdit.Rename -> "rename"
    is StepEdit.Move -> "move"
}

/**
 * 编辑后是否下发回放建议：null=不发。
 * 空步序账不得伪装成"可回放的成品"（与 stopAndCompile 同一条禁律）。规则写成纯函数是为了
 * 让这条负向判据在 JVM 里锁住——它若只活在带 Log 的 RecorderStore 里就测不到，等于没锁。
 */
fun suggestionAfterEdit(actions: List<Action>): String? =
    if (actions.isEmpty()) null else encodeActions(actions)

private fun JsonObject.clueText(key: String): String? = this[key]?.jsonPrimitive?.content

/**
 * 步骤人读标题（纯函数）：只读编译产物自带的定位线索，不新增任何真值来源。
 * 词表与执行器解码口径一致（resource_id / text / content_desc / path / input / direction）——
 * UI 上看到的定位依据必须就是回放时用的那一条，否则编辑页成"看起来对"的第二套账（雷18 同族）。
 * type_text 同时显示"定位依据 + 输入内容"：只显输入会把用户引到"这步改的是字"上，
 * 而回放真正依赖的是那个字段线索（改名只改 actionId，动不到它）。
 */
fun stepLabel(action: Action): String {
    val value = action.value
    if (action.type == ActionType.SCROLL) {
        return "${action.type} · ${value?.clueText(RecorderCompiler.DIRECTION_KEY) ?: "无方向"}"
    }
    val clue = value?.clueText(RecorderCompiler.TEXT_KEY)?.ellipsize(24)
        ?: value?.clueText(RecorderCompiler.CONTENT_DESC_KEY)?.ellipsize(24)
        ?: value?.clueText(RecorderCompiler.RESOURCE_ID_KEY)?.substringAfterLast('/')?.ellipsize(24)
        ?: value?.clueText(RecorderCompiler.PATH_KEY)?.ellipsize(24)
        ?: "无线索"
    val input = value?.clueText(RecorderCompiler.INPUT_KEY)?.ellipsize(20)?.let { "输入 $it" }
    return listOfNotNull(action.type, clue, input).joinToString(" · ")
}

private fun String.ellipsize(limit: Int): String =
    if (length <= limit) this else take(limit) + "…"
