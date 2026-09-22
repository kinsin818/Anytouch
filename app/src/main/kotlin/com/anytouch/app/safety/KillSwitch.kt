package com.anytouch.app.safety

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 全局停止单例（军令 L2-3）：基于 StateFlow 的停止信号源。
 * - 任意 step 前经 [isStopped]/[snapshot] 查询；
 * - [stop] 幂等，保留首个停止信号（后到的重复 stop 不覆盖现场）；
 * - [reset] 仅供新一轮任务开始/测试复位使用。
 * 悬浮球 StopBall（主窗 UI）与 adb 注入都只需调用 [stop]，本类不依赖 Android。
 */
object KillSwitch {

    data class KillSignal(
        val reason: String,
        val source: String,
        val sequence: Long,
    )

    private var sequence: Long = 0

    private val _state = MutableStateFlow<KillSignal?>(null)

    /** 停止信号流：null = 运行中；非 null = 已停止（携带现场）。 */
    val state: StateFlow<KillSignal?> = _state.asStateFlow()

    fun isStopped(): Boolean = _state.value != null

    fun snapshot(): KillSignal? = _state.value

    /**
     * 触发全局停止。返回 true 表示本次调用是首个停止信号。
     * 加锁保证 sequence 与"首信号"判定在多线程下一致。
     */
    @Synchronized
    fun stop(reason: String = "user_stop", source: String = "stop_ball"): Boolean {
        if (_state.value != null) return false
        sequence += 1
        _state.value = KillSignal(reason, source, sequence)
        return true
    }

    @Synchronized
    fun reset() {
        _state.value = null
    }
}
