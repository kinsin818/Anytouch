package com.anytouch.pipeline

/**
 * 本包新增的停止码常量 —— 军令禁区不允许改动 Constants.kt，
 * SAFETY_GATE_BLOCKED / INVALID_INPUT 在此以只增方式补充；
 * severity 仍复用 contracts 的 StopSeverity。
 */
object PipelineStopCode {
    const val SAFETY_GATE_BLOCKED = "SAFETY_GATE_BLOCKED"
    const val INVALID_INPUT = "INVALID_INPUT"
    /** 在跑任务被服务生命周期（系统重启/解绑无障碍服务）取消——非用户停止、非任务失败。 */
    const val SERVICE_INTERRUPTED = "SERVICE_INTERRUPTED"
    /** 注入超 TTL 未被消费即作废（防重绑偷跑：宁可丢单不偷跑）。 */
    const val REQUEST_EXPIRED = "REQUEST_EXPIRED"
    /** 已有任务在执行时新注入即弃（单执行器语义）。 */
    const val REQUEST_BUSY = "REQUEST_BUSY"
    /** 悬浮停止球挂不上=全局急停手段缺席，fail-closed 拒绝开始执行。 */
    const val SAFETY_BALL_UNAVAILABLE = "SAFETY_BALL_UNAVAILABLE"
}
