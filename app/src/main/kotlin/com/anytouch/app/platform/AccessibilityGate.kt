package com.anytouch.app.platform

/**
 * 安全前置门禁（军令 L2 + 红线 E：全程零 `SYSTEM_ALERT_WINDOW`）。
 *
 * 纯函数、零平台依赖，为的是让"未连接即拒绝"这类 fail-closed 判断**在 JVM 里就能被锁住**——
 * 此前它散在 Compose 的 `enabled=` 表达式里（S1 集成时的实现），单测覆盖不到，
 * 而"能开录但录不到东西"的静默空录正是第 8 雷形态，必须可测。
 *
 * 四个输入各自对应一条设备实证过的失败面：
 * - [serviceConnected]=false：无障碍服务未绑定/被 ROM 收回（MIUI force-stop 清绑定=第 13 雷）。
 *   此时采集钩子根本不会触发，开录=录一段空会话并"成功"，属假通过，禁。
 * - [running]=true：执行器正在跑任务。此时录制球被显式收起（看不见球的录制不允许开始），
 *   更要紧的是录进去的会是执行器自己的手——把机器动作伪装成用户意图，假绿形态。
 * - [ballAttached]=false：录制球挂不上（L1 原判据"挂不上球=拒绝开始"，老板 09-23 生效版派单写死）。
 *   球是录制期唯一的可见状态与急停入口；挂不上即禁。
 * - [compileBusy]=true：AI 编译那一跑还在路上（裁决 S3-R4-1）。放行=录制产物与模型产物前后压进
 *   同一格步序账，用户事后分不清哪一条是自己录的——与"执行中禁编辑"同一条状态机互斥逻辑。
 *   裁决 S31-B2 把这一格从"录制两面"扩到"录制两面 + 执行任务 + 步骤编辑"四面：
 *   扩面靠的是同一格判据（[stopCompileGateOf]）被四个入口转调，不是四处各写一份 `if (compileBusy)`。
 */
enum class RecordGate {
    READY,

    /** 无障碍服务不在场：既不采集也不执行。 */
    SERVICE_OFF,

    /** 执行中：球收起且录进去的是执行器自己的手。 */
    RUNNING,

    /** 服务在场但球挂不上：拒绝开录（执行侧同类门禁见 `SAFETY_BALL_UNAVAILABLE`，第 10 雷）。 */
    BALL_UNAVAILABLE,

    /**
     * AI 编译这一跑还在路上：现在覆盖**四个**入口（开录 / 停止并编译 / 执行任务 / 步骤编辑，
     * 裁决 S3-R4-1 起于录制两面，S31-B2 扩到改账与执行两面）。放行=录制产物或模型产物前后压进
     * 同一格步序账、或账在正在跑的那一执行底下被换掉，用户事后分不清哪一条是哪一份的。
     * 与 [RUNNING] 同族——都是"另一个状态机正持有账本"，只不过持有者是编译那一跑。
     * 判据只住 [stopCompileGateOf] 一格，四个入口都从它取（各写一份就是串状态）。
     */
    COMPILING,
}

/**
 * 编译持有档的**共用头一句**（裁决 S31-B2 对话术的硬要求："要说清 AI 编译中，改账与执行此刻不动"）。
 * 四个入口（开录 / 停止并编译 / 执行任务 / 步骤编辑）的头一句都从这里取——
 * 头一句若各写一份，改一处漏一处就是"这一面说了不动、那一面没说要不动"（话术面的雷 18）。
 * 每个入口**在头一句之后**各接自己那一句细节（改账说的是步序账，执行说的是派发排队），
 * 细节按入口不同是应该的，头一句不同就不应该。
 */
const val COMPILE_HOLD_HEADLINE = "AI 编译中，改账与执行此刻不动"

/**
 * 编译持有对**停止并编译**入口的判定（裁决 S3-R4-1）。判据只住这一个函数：开录面（[recordGateOf]）、
 * 执行面（[runGateOf]）、编辑面（`stepEditGateOf` 四参版）都调它，不另抄一遍 `if (compileBusy)`——
 * 两个入口若各写一份，改一处漏一处就是"开录拒、停止放"的串状态。
 * 这里刻意只判这一条：会话既然已经在了，服务/球此刻在不在都不影响"把已录的编出来"，
 * 多判几项等于自创第二套门禁。
 */
fun stopCompileGateOf(compileBusy: Boolean): RecordGate =
    if (compileBusy) RecordGate.COMPILING else RecordGate.READY

/**
 * 「执行任务」入口的真值表（裁决 S31-B2，S3-F/F1）：把 `compileBusy` 与既有 RUNNING 档收进**同一张表**，
 * 而不是在派发口再写一份 `if (compileBusy)`。编译那一跑回来会把整本步序账换掉，
 * 此刻放行执行＝账在正在跑的那一跑底下被换掉（真缺陷形态：`acceptModelActions` 随后收 RefusedRunning，
 * 而屏上步骤已经和用户按下去时看到的那份不是一回事）。
 *
 * 输入只有两条状态（不像 [recordGateOf] 还要服务/球）：派发不需要球，服务在不在由总线那头判；
 * 多判几项等于自创第二套门禁（同 [stopCompileGateOf] 的理由）。
 * 编译档仍从 [stopCompileGateOf] 取，本函数只负责把 RUNNING 排在它前面。
 */
fun runGateOf(running: Boolean, compileBusy: Boolean): RecordGate =
    if (running) RecordGate.RUNNING else stopCompileGateOf(compileBusy)

/** 门禁判定：唯一真值是 READY，任何缺项一律拒（默认拒绝，无"部分可用"档）。 */
fun recordGateOf(
    serviceConnected: Boolean,
    running: Boolean,
    ballAttached: Boolean,
    compileBusy: Boolean,
): RecordGate = when {
    !serviceConnected -> RecordGate.SERVICE_OFF
    running -> RecordGate.RUNNING
    !ballAttached -> RecordGate.BALL_UNAVAILABLE
    // 编译档排在球之后：球挂不上是**长期缺项**（编译结束也不会自己好），先说它——
    // 过期边只在"整门判 READY"时才撤字，若先说持有档，编译一回来屏上那句话就失去依据而仍挂着。
    stopCompileGateOf(compileBusy) == RecordGate.COMPILING -> RecordGate.COMPILING
    else -> RecordGate.READY
}

/** 用户向话术（L2 要求：必现"请立即开启"并说明被拒原因，不是静默禁用）。 */
fun RecordGate.userCopy(): String? = when (this) {
    RecordGate.READY -> null
    RecordGate.SERVICE_OFF ->
        "无障碍服务未连接：请立即到 设置 → 无障碍 → 已下载的服务 开启 Anytouch；未开启不能开始录制或执行。"
    RecordGate.RUNNING ->
        "任务执行中不能开录：执行器的手会被录成你的意图。请等本轮结束（或点悬浮球停止）后再录。"
    RecordGate.BALL_UNAVAILABLE ->
        "录制球无法显示：录制全靠这颗球开录与急停，看不见球就不允许开始。请重新开启无障碍服务后重试" +
            "（小米/红米机型被强行停止后绑定会被清掉，见装机引导 G2）。"
    // 一句话现在服务**四个**入口（开录/停止并编译/执行任务/步骤编辑，裁 S31-B2 扩了两面）：
    // 话术单源（ui/MainActivity 与 adb 通道都不许各抄一份），头一句取自 COMPILE_HOLD_HEADLINE。
    RecordGate.COMPILING ->
        "$COMPILE_HOLD_HEADLINE：这一跑回来会把整本步序账换掉，期间开录、点「停止并编译」、点「执行任务」、" +
            "删改步骤都不许动——屏上留下的到底是哪一份产物就说不清了。请等编译结论上屏（成功、失败都会说话）后再操作。"
}

/**
 * 「执行任务」入口的话术（S3-F/F1-2）。**只有 RUNNING 这一档按入口改写**：
 * [userCopy] 表里的 RUNNING 说的是"执行中不能开录（录进去的是执行器自己的手）"，
 * 挂在派发口上就是把另一件事说成这件事——那是话术面的串状态。
 * 其余档（含 COMPILING）一律回落到同一份表，派发口不另写一句"编译中"。
 */
fun RecordGate.runUserCopy(): String? = when (this) {
    RecordGate.RUNNING ->
        "任务执行中，本次派发不动：已有任务在跑，这一条只会排在它之后（单执行器语义），" +
            "而当前那一跑的产物会覆写屏上这份报告。要真停下来请点悬浮停止球。"
    else -> userCopy()
}

/**
 * 拒因话术的过期边（老板 09-23 裁决 V-2）：门禁已转为 READY 时，上一次被拒留下的红字必须作废。
 *
 * 为什么需要这条：`startRejection` 原本只有"下一次开录（成功或失败）"一条覆盖边。设备实证过
 * 空闲态屏上仍挂着"任务执行中不能开录"（`evidence/S2/img/recui-probe-A-idle-stale-red.png`），
 * 而同刻注入 `record_start` 被门禁放行——**门禁说可以、话术说不行，假红**，与"假红与假绿同罪"同族。
 * 规则写成纯函数：判据要能在 JVM 里锁住，且状态跃迁的接线（服务侧 collect）不该自带第二份判据。
 *
 * @param current 屏上正在显示的开录拒因（null=无）。
 * @param gate 以当前三项真值重算出的门禁。
 * @return 过期后的话术：READY 即 null（作废），否则原样保留（仍在拒，不许悄悄抹掉）。
 */
fun startRejectionAfterStateChange(current: String?, gate: RecordGate): String? =
    if (current != null && gate == RecordGate.READY) null else current

/**
 * **停止并编译**拒因的过期边（裁决 S3-R4-1，与 [startRejectionAfterStateChange] / `editRejectionAfterStateChange`
 * 同一条纪律）：这一面只有 [RecordGate.COMPILING] 一档，它是**纯状态档**——说的是"此刻编译在跑"，
 * 编译一回来话术就失去依据，留在屏上就是假红（假红与假绿同罪）。
 * 传入档位而不是文本比对：拒因身份存枚举，改一个字面都不会让过期逻辑静默失效。
 */
fun stopRejectionAfterStateChange(current: RecordGate?, compileBusy: Boolean): RecordGate? =
    if (current == RecordGate.COMPILING && !compileBusy) null else current

/**
 * 「执行任务」拒因的过期边（S3-F/F1-3，与 [stopRejectionAfterStateChange]、[startRejectionAfterStateChange]
 * 同一条纪律）：派发面挂的红字每一条都是**纯状态档**（RUNNING=此刻在跑、COMPILING=此刻编译在跑），
 * 状态一归位话术就失去依据，留在屏上就是假红。
 *
 * "归位"用 [runGateOf] 现算，不逐档比文本：门禁说可以、话术说不行，正是 V-2 那颗雷的形态
 * （设备实证过空闲态仍挂着"任务执行中不能开录"，见 `evidence/S2/img/recui-probe-A-idle-stale-red.png`）。
 * 传档位而非文本：拒因身份存枚举，改一个字面都不会让过期逻辑静默失效。
 *
 * @param current 屏上那条派发拒因**当时判的档**；null=没有红字，或那次拒是**请求绑定**的档
 *   （V-3 的"框账不符"属于这一类：它绑用户那一次点击，由下一次派发覆盖，不许被状态跃迁悄悄抹掉，
 *   所以调用方对那种拒因存 null 而不是存一个档位）。
 */
fun taskRejectionAfterStateChange(current: RecordGate?, running: Boolean, compileBusy: Boolean): RecordGate? =
    if (current != null && runGateOf(running, compileBusy) == RecordGate.READY) null else current
