package com.anytouch.app.locator

/**
 * 节点树定位的坐标盒。仅用于描述节点自身范围，
 * 定位链任何一阶都不得读取它来做匹配（军令 L1：禁止坐标定位）。
 */
data class UiBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val centerX: Int get() = left + (right - left) / 2
    val centerY: Int get() = top + (bottom - top) / 2

    companion object {
        fun ofSize(width: Int, height: Int): UiBounds = UiBounds(0, 0, width, height)
    }
}

/**
 * 执行器可见节点的抽象。Android 的 AccessibilityNodeInfo 不进入引擎，
 * 由主窗适配器实现本接口（军令 L2-STAGE-11 输入抽象），保证 JVM 单测可跑。
 */
interface UiNode {
    val resourceId: String? get() = null
    val text: String? get() = null
    val contentDesc: String? get() = null
    val className: String? get() = null
    val packageName: String? get() = null
    val clickable: Boolean get() = false
    val scrollable: Boolean get() = false
    val bounds: UiBounds? get() = null
    val children: List<UiNode> get() = emptyList()
}

/** 类名短名：`android.widget.ListView` -> `ListView`；无点号时原样返回。 */
internal val UiNode.shortClassName: String?
    get() = className?.substringAfterLast('.')

/** 资源 id 的 entry 名：`com.x:id/wifi_toggle` -> `wifi_toggle`。 */
internal val UiNode.resourceIdEntryName: String?
    get() = resourceId?.substringAfter('/')

/** 文档序（深度优先、父先子后、同层按子序号）——instance 取序的确定性来源。 */
internal fun UiNode.preOrder(): List<UiNode> {
    val out = ArrayList<UiNode>()
    fun visit(node: UiNode) {
        out += node
        node.children.forEach(::visit)
    }
    visit(this)
    return out
}

/** 自身 + 全部后代，按层序（浅的先、同层按子序号）。 */
internal fun UiNode.subtreeBreadthFirst(): List<UiNode> {
    val out = ArrayList<UiNode>()
    val queue = ArrayDeque<UiNode>()
    queue.add(this)
    while (queue.isNotEmpty()) {
        val node = queue.removeFirst()
        out += node
        queue.addAll(node.children)
    }
    return out
}
