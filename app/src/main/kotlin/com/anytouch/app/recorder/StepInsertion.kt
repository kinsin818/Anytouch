package com.anytouch.app.recorder

import com.anytouch.contracts.Action
import com.anytouch.contracts.ActionSafety
import com.anytouch.contracts.ActionSource
import com.anytouch.contracts.ActionType
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 手动补步骤的调用侧形状（S5-e 要求 3；老板 09-24 三裁③把军令原句"填坐标/文字/时长"改判为
 * "第一版只允许加节点/输入/等待类步骤，不开放坐标点击入口，坐标容易不准，后面再补"）。
 *
 * 三处刻意的设计，逐条留理由：
 * 1) **结构上没有坐标字段**。`Action.target: Point?` 在契约里确实存在，但执行路径从不读它定位
 *    （`NodeTaskRunner` 只把落点写进 `landed` 回执），手动面若开一格 x/y，就是给"节点树引用"这条红线
 *    留一个绕过口——而且那一格填了也不生效，比不给更坏（用户会以为坐标生效了）。所以这里不是
 *    "记得传 null"，是**压根无法表达**（JVM 锁：产物 `target == null` 且 `value` 键集落在白名单内）。
 * 2) **类型只有三条**（click / type_text / wait）。`scroll` 执行器跑得动，但第一版手动面按三裁③收窄，
 *    等老板放开再扩，此处不猜、不"顺手支持一下"。
 * 3) **字段全是 String**。UI 文本框与 adb extra 交出来的就是字符串；"abc 是不是合法毫秒数"这类判断
 *    必须住在下面这个纯函数里、且只住在这一处（判据不住接线，接线只转调）。
 *
 * 与所选类型无关的字段在非法组合下会被**忽略**（如 wait 带 text）：那些键不写进 `value`，
 * 账本里因此不留"看着像依据、执行器其实读不到"的第二套表述（雷 18 同族）。
 */
data class ManualStep(
    val type: String,
    val actionId: String,
    val resourceId: String = "",
    val text: String = "",
    val contentDesc: String = "",
    val path: String = "",
    val instance: String = "",
    val input: String = "",
    val waitMs: String = "",
)

/** 多命中时取第几个（0 基）。读取口=`NodeTaskRunner` 的 `int("instance")`，缺省 0。 */
const val INSTANCE_KEY = "instance"

/** wait 的时长（毫秒）。读取口=`NodeTaskRunner` 的 `longParam(value, "ms", 500)`——空白即缺省 500。 */
const val WAIT_MS_KEY = "ms"

/**
 * 上面那条缺省值的**显示侧镜像**：账里没写 ms 时执行器等 500，标题也必须说 500，
 * 否则编辑页与回放差一秒（"看起来对的第二套账"）。JVM 锁拿执行器源文件对拍这一格。
 */
const val DEFAULT_WAIT_MS = 500L

/**
 * 手动可插的类型（三裁③三条对成三个 `ActionType` 常量，不写字面量——比较侧同一条纪律）。
 * 注意这**不是**"执行器跑得动的集合"（那一份住 `:byok/ExecutorVocabulary`，四条含 scroll）：
 * 手动面比它窄是老板裁的第一版边界，两份集合各有各的判据，谁也不抄谁。
 */
val manualInsertableTypes: Set<String> = setOf(ActionType.CLICK, ActionType.TYPE_TEXT, ActionType.WAIT)

/**
 * 手动步 `value` 允许出现的键集（构建侧唯一的键清单，也是 JVM 锁的对拍对象）。
 * 四个定位线索即执行器解码的那一组（`toLocatorRequest`），`instance` 是同一条请求的取序，
 * `input`／`ms` 是 type_text／wait 的载荷——一列不加。
 */
val manualStepValueKeys: Set<String> = setOf(
    RecorderCompiler.RESOURCE_ID_KEY,
    RecorderCompiler.TEXT_KEY,
    RecorderCompiler.CONTENT_DESC_KEY,
    RecorderCompiler.PATH_KEY,
    INSTANCE_KEY,
    RecorderCompiler.INPUT_KEY,
    WAIT_MS_KEY,
)

/** 至少一条节点线索：判据等于执行器那条（四阶全空即 `toLocatorRequest()` 返回 null、当场 INVALID_INPUT）。 */
fun manualStepHasClue(step: ManualStep): Boolean =
    step.resourceId.isNotBlank() || step.text.isNotBlank() || step.contentDesc.isNotBlank() || step.path.isNotBlank()

/**
 * 手动步**本体**是否成形（与账本无关的那四档；空白名字不在这里——那是 [StepEditGate.BLANK_NAME]，
 * 与改名同一档，同一件事不许有两个归因）。null=成形。
 *
 * 顺序即归因优先级：类型不对时谈线索没意义（`scroll` 的线索另有一套规矩）；wait 不需要线索，
 * 所以线索档只对 click／type_text 判；数字档放最后——它可能同时出现在两个字段上，先说"缺了什么"再说"填错了什么"。
 */
fun manualStepGateOf(step: ManualStep): StepEditGate? = when {
    step.type !in manualInsertableTypes -> StepEditGate.INSERT_TYPE
    step.type != ActionType.WAIT && !manualStepHasClue(step) -> StepEditGate.INSERT_CLUE
    step.type == ActionType.TYPE_TEXT && step.input.isBlank() -> StepEditGate.INSERT_EMPTY_INPUT
    step.instance.isNotBlank() && !nonNegativeInt(step.instance) -> StepEditGate.INSERT_BAD_NUMBER
    step.waitMs.isNotBlank() && !nonNegativeLong(step.waitMs) -> StepEditGate.INSERT_BAD_NUMBER
    else -> null
}

/**
 * 把手动步落成一条 `Action`（唯一构建口：UI 与注入通道都经 [applyStepEdit] 走到这里，
 * 于是"禁坐标""safety 怎么放行"这类形状判据只有一份）。
 *
 * `safety` 两门显式放行与编译器产物一字不差（`RecorderCompiler.step` 同一条）：执行器对非 wait 步
 * 先查 `viewport_ok`／`click_enabled`，缺省 false=这一步直接 SAFETY_GATE_BLOCKED。人工写的节点步
 * 与录出来的节点步在这一点上没有任何区别，因此不给面板开格让用户自己勾——放行的是"用节点引用点一下"
 * 这个动作类别，高危与否由**运行时命中节点过词表**决定（判据 §3-6 照样弹二次确认，此处绕不过）。
 */
fun buildManualStep(step: ManualStep): Action {
    require(step.actionId.isNotBlank()) { "manual step actionId must not be blank" }
    require(manualStepGateOf(step) == null) { "manual step refused by gate ${manualStepGateOf(step)}: $step" }
    val value = buildJsonObject {
        if (step.type != ActionType.WAIT) {
            if (step.resourceId.isNotBlank()) put(RecorderCompiler.RESOURCE_ID_KEY, step.resourceId.trim())
            if (step.text.isNotBlank()) put(RecorderCompiler.TEXT_KEY, step.text.trim())
            if (step.contentDesc.isNotBlank()) put(RecorderCompiler.CONTENT_DESC_KEY, step.contentDesc.trim())
            if (step.path.isNotBlank()) put(RecorderCompiler.PATH_KEY, step.path.trim())
            // 0 与"省略"在执行器同形（`int("instance")` 缺省即 0），只写用户真填的那个值
            val instance = step.instance.trim().toIntOrNull()
            if (instance != null && instance > 0) put(INSTANCE_KEY, instance)
        }
        when (step.type) {
            // 输入内容**不 trim**：用户要往字段里敲的空格是内容的一部分（线索字段才是标识符）
            ActionType.TYPE_TEXT -> put(RecorderCompiler.INPUT_KEY, step.input)
            ActionType.WAIT -> {
                val ms = step.waitMs.trim().toLongOrNull()
                if (ms != null) put(WAIT_MS_KEY, ms)
            }
        }
    }
    return Action(
        actionId = step.actionId.trim(),
        type = step.type,
        target = null,
        value = value,
        source = ActionSource.NODE,
        safety = ActionSafety(viewportOk = true, clickEnabled = true, requiresTransition = false),
    )
}

private fun nonNegativeInt(raw: String): Boolean = raw.trim().toIntOrNull()?.let { it >= 0 } == true

private fun nonNegativeLong(raw: String): Boolean = raw.trim().toLongOrNull()?.let { it >= 0 } == true
