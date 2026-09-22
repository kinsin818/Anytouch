package com.anytouch.app

import kotlinx.coroutines.flow.MutableStateFlow

/** 无障碍服务连接状态的唯一事实源，UI 与执行器都从这里读。 */
object AppState {
    val serviceConnected = MutableStateFlow(false)
}
