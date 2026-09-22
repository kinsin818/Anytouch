package com.anytouch.app.service

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import com.anytouch.app.AppState

/**
 * S1 骨架桩：仅证明"服务可连接、事件流可达"。
 * 节点树定位（STAGE-11）与全局停止/二次确认（STAGE-12）在此文件之外扩展。
 */
class AnytouchAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        AppState.serviceConnected.value = true
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        AppState.serviceConnected.value = false
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // S1 骨架：不处理。录制事件流属 S2（门票门禁外，不开工）
    }

    override fun onInterrupt() {
        AppState.serviceConnected.value = false
    }
}
