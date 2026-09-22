package com.anytouch.app.safety

/**
 * STAGE-12 自有的窄输入接口：只暴露高危判定需要的三个文本字段。
 * 不依赖 STAGE-11 的 UiNode，不触碰 Android 类，JVM 可直接测。
 * 主窗适配器负责把 AccessibilityNodeInfo 的字段映射进来。
 */
interface NodeTextProbe {
    val resourceId: String?
    val text: String?
    val contentDesc: String?
}

data class SimpleNodeProbe(
    override val resourceId: String? = null,
    override val text: String? = null,
    override val contentDesc: String? = null,
) : NodeTextProbe
