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
     * AI 编译这一跑还在路上：录制面两个入口（开录 / 停止并编译）此刻都不许动步序账
     * （裁决 S3-R4-1）。与 [RUNNING] 同族——都是"另一个状态机正持有账本"，只不过持有者是编译那一跑。
     */
    COMPILING,
}

/**
 * 编译持有对**停止并编译**入口的判定（裁决 S3-R4-1）。判据只住这一个函数：开录面（[recordGateOf]）
 * 直接调它，不另抄一遍 `if (compileBusy)`——两个入口若各写一份，改一处漏一处就是"开录拒、停止放"的串状态。
 * 这里刻意只判这一条：会话既然已经在了，服务/球此刻在不在都不影响"把已录的编出来"，
 * 多判几项等于自创第二套门禁。
 */
fun stopCompileGateOf(compileBusy: Boolean): RecordGate =
    if (compileBusy) RecordGate.COMPILING else RecordGate.READY

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
    // 一句话同时服务两个入口：话术单源（ui/MainActivity 与 adb 通道都不许各抄一份）
    RecordGate.COMPILING ->
        "AI 编译还在路上，录制此刻不动：这一跑回来会把整本步序账换掉，期间开录或点「停止并编译」，" +
            "屏上留下的到底是哪一份产物就说不清了。请等编译结论上屏（成功、失败都会说话）后再操作。"
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
