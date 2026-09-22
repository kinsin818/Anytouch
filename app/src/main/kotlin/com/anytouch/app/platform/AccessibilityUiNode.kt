package com.anytouch.app.platform

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.anytouch.app.locator.UiBounds
import com.anytouch.app.locator.UiNode

/**
 * AccessibilityNodeInfo -> [UiNode] 快照适配器（军令 L2-STAGE-11 预留的主窗窄口）。
 *
 * 取"急切快照"而非懒访问：定位链用对象恒等（===）求 indexPath 并回放命中，
 * 而 getChild() 每次返回新实例——children 若每次访问重建，恒等即碎、路径必炸。
 * 故字段与子列表在构造时固化一次；一棵树一次遍历，无坐标参与任何匹配。
 *
 * [info] 为真实节点句柄，仅供执行器 performAction，不回渗进定位/安全阀。
 */
class AccessibilityUiNode private constructor(
    val info: AccessibilityNodeInfo,
    private val parent: AccessibilityUiNode?,
) : UiNode {

    constructor(root: AccessibilityNodeInfo) : this(root, null)

    override val resourceId: String? = info.viewIdResourceName
    override val text: String? = info.text?.toString()
    override val contentDesc: String? = info.contentDescription?.toString()
    override val className: String? = info.className?.toString()
    override val packageName: String? = info.packageName?.toString()
    override val clickable: Boolean = info.isClickable
    override val scrollable: Boolean = info.isScrollable
    override val bounds: UiBounds? = Rect().let { r ->
        info.getBoundsInScreen(r)
        UiBounds(r.left, r.top, r.right, r.bottom)
    }
    override val children: List<UiNode> = (0 until info.childCount)
        .mapNotNull { info.getChild(it) }
        .map { AccessibilityUiNode(it, this) }

    /** 命中行内 TextView 等非可点节点时，向上取最近可点祖先。恒等链取自快照父引用，不重访系统树。 */
    fun nearestClickableSelfOrAncestor(): AccessibilityUiNode? =
        generateSequence(this as AccessibilityUiNode?) { it.parent }.firstOrNull { it.clickable }

    fun nearestScrollableSelfOrAncestor(): AccessibilityUiNode? =
        generateSequence(this as AccessibilityUiNode?) { it.parent }.firstOrNull { it.scrollable }
}

/**
 * 执行器对真实设备的全部依赖面。JVM 单测注入假实现即可驱动 [com.anytouch.app.executor.NodeTaskRunner]。
 */
interface NodeActions {
    suspend fun root(): UiNode?
    fun click(node: UiNode): Boolean
    fun scroll(node: UiNode, forward: Boolean): Boolean
    fun setText(node: UiNode, text: String): Boolean
}

class AccessibilityDevice(private val service: AccessibilityService) : NodeActions {

    override suspend fun root(): UiNode? {
        service.rootInActiveWindow?.let { return AccessibilityUiNode(it) }
        // 模拟器实测：个别窗口 rootInActiveWindow 为 null 但窗口列表含应用窗口——按焦点/层级扫描兜底。
        // 该 ROM 上仍有"窗口在场但 root 恒 null"的页面（Settings Internet 页、permissioncontroller 角色页），
        // 系系统侧 a11y 树未下发；已在 accessibility_config 补 flagReportViewIds/flagIncludeNotImportantViews
        // （否则 viewIdResourceName 全 null、非重要节点被裁，模拟器实测）。真机口径属 T3。
        val windows = service.windows ?: return null
        val ordered = windows
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .sortedWith(
                compareByDescending<AccessibilityWindowInfo> { it.isFocused }
                    .thenByDescending { it.isActive }
                    .thenByDescending { it.layer },
            )
        for (w in ordered) w.root?.let { return AccessibilityUiNode(it) }
        return null
    }

    override fun click(node: UiNode): Boolean {
        val target = (node as? AccessibilityUiNode)?.nearestClickableSelfOrAncestor() ?: return false
        return target.info.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    override fun scroll(node: UiNode, forward: Boolean): Boolean {
        val target = (node as? AccessibilityUiNode)?.nearestScrollableSelfOrAncestor() ?: return false
        val action = if (forward) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        } else {
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        }
        return target.info.performAction(action)
    }

    override fun setText(node: UiNode, text: String): Boolean {
        val target = node as? AccessibilityUiNode ?: return false
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return target.info.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }
}
