package com.anytouch.app.platform

/**
 * 安全前置门禁（军令 L2 + 红线 E：全程零 `SYSTEM_ALERT_WINDOW`）。
 *
 * 纯函数、零平台依赖，为的是让"未连接即拒绝"这类 fail-closed 判断**在 JVM 里就能被锁住**——
 * 此前它散在 Compose 的 `enabled=` 表达式里（S1 集成时的实现），单测覆盖不到，
 * 而"能开录但录不到东西"的静默空录正是第 8 雷形态，必须可测。
 *
 * 三个输入各自对应一条设备实证过的失败面：
 * - [serviceConnected]=false：无障碍服务未绑定/被 ROM 收回（MIUI force-stop 清绑定=第 13 雷）。
 *   此时采集钩子根本不会触发，开录=录一段空会话并"成功"，属假通过，禁。
 * - [running]=true：执行器正在跑任务。此时录制球被显式收起（看不见球的录制不允许开始），
 *   更要紧的是录进去的会是执行器自己的手——把机器动作伪装成用户意图，假绿形态。
 * - [ballAttached]=false：录制球挂不上（L1 原判据"挂不上球=拒绝开始"，老板 09-23 生效版派单写死）。
 *   球是录制期唯一的可见状态与急停入口；挂不上即禁。
 */
enum class RecordGate {
    READY,

    /** 无障碍服务不在场：既不采集也不执行。 */
    SERVICE_OFF,

    /** 执行中：球收起且录进去的是执行器自己的手。 */
    RUNNING,

    /** 服务在场但球挂不上：拒绝开录（执行侧同类门禁见 `SAFETY_BALL_UNAVAILABLE`，第 10 雷）。 */
    BALL_UNAVAILABLE,
}

/** 门禁判定：唯一真值是 READY，任何缺项一律拒（默认拒绝，无"部分可用"档）。 */
fun recordGateOf(serviceConnected: Boolean, running: Boolean, ballAttached: Boolean): RecordGate = when {
    !serviceConnected -> RecordGate.SERVICE_OFF
    running -> RecordGate.RUNNING
    !ballAttached -> RecordGate.BALL_UNAVAILABLE
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
}
