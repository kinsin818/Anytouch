package com.anytouch.app

import kotlinx.coroutines.flow.MutableStateFlow

/** 一次任务注入请求：id 用纳秒保证同内容重复提交也是新请求。 */
data class TaskRequest(
    val id: Long,
    val json: String,
    val submittedAtMs: Long = System.currentTimeMillis(),
)

/** 任务总线策略常量。 */
object TaskPolicy {
    /**
     * 注入到执行的存活窗口：超时未被消费即作废。
     * 无 TTL 时设备实测复现"注入 4 分钟无人值守 → 服务重绑后旧任务自动开跑"（StateFlow 头重放），
     * 违背"用户意图驱动执行"的红线语义，宁可丢单不偷跑。
     */
    const val TTL_MS = 60_000L
}

/** 无障碍服务连接状态与任务总线的唯一事实源，UI 与执行器都从这里读写。 */
object AppState {
    val serviceConnected = MutableStateFlow(false)

    /** UI/adb 写入，服务收集执行。null = 无待执行请求。 */
    val taskRequests = MutableStateFlow<TaskRequest?>(null)

    val running = MutableStateFlow(false)

    /** 最近一次执行的 RunReport JSON（人读 + 归因数据源）。 */
    val lastRunReport = MutableStateFlow<String?>(null)

    fun submit(json: String) {
        taskRequests.value = TaskRequest(System.nanoTime(), json)
    }

    fun isExpired(request: TaskRequest, nowMs: Long = System.currentTimeMillis()): Boolean =
        nowMs - request.submittedAtMs > TaskPolicy.TTL_MS

    /** 执行完毕/过期/忙中丢弃时作废请求（仅当队列头仍是该请求）：StateFlow 重放语义会在服务重绑时把旧任务再执行一次。 */
    fun consume(request: TaskRequest) {
        if (taskRequests.value?.id == request.id) taskRequests.value = null
    }
}
