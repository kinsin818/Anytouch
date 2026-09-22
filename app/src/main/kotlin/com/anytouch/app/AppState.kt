package com.anytouch.app

import kotlinx.coroutines.flow.MutableStateFlow

/** 一次任务注入请求：id 用纳秒保证同内容重复提交也是新请求。 */
data class TaskRequest(
    val id: Long,
    val json: String,
)

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
}
