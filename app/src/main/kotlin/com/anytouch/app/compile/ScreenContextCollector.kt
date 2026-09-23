package com.anytouch.app.compile

import com.anytouch.app.locator.UiNode
import com.anytouch.app.locator.preOrder
import com.anytouch.byok.ScreenContext
import com.anytouch.byok.ScreenContextBuilder
import com.anytouch.byok.ScreenNodeFact

/**
 * 走树取数：把一棵 [UiNode] 变成上行词表。
 *
 * 三个屏外事实（可编辑/密码类/对用户可见）**必须由调用方给**——它们是 AccessibilityNodeInfo 的布尔，
 * 不在 [UiNode] 接口上。把取法做成参数，是为了让"只读布尔、一个字都不读内容"这件事本身可测；
 * 真实取法见 [accessibilityFlagsOf]（本包唯一碰 Android 的几行，属设备缝，JVM 覆盖 0）。
 * 旗标只是"或"判据的一条：`:byok` 还会按类名兜一层，两条都不像输入框才上行其文本。
 *
 * 本文件在 `compile/` 下：红线 G 允许创建期 import `com.anytouch.byok` 的只有 UI 面与 compile 面，
 * 执行路径（executor/locator/service/safety/platform/recorder）依旧看不见编译模块。
 */
object ScreenContextCollector {

    fun facts(roots: List<UiNode>, flagsOf: (UiNode) -> ScreenNodeFlags): List<ScreenNodeFact> =
        roots.flatMap { root ->
            root.preOrder().map { node ->
                val flags = flagsOf(node)
                ScreenNodeFact(
                    text = node.text,
                    resourceId = node.resourceId,
                    className = node.className,
                    editable = flags.editable,
                    passwordLike = flags.passwordLike,
                    visibleToUser = flags.visibleToUser,
                )
            }
        }

    fun facts(root: UiNode?, flagsOf: (UiNode) -> ScreenNodeFlags): List<ScreenNodeFact> =
        if (root == null) emptyList() else facts(listOf(root), flagsOf)

    fun collect(
        roots: List<UiNode>,
        flagsOf: (UiNode) -> ScreenNodeFlags,
        enabled: Boolean,
        cap: Int = ScreenContextBuilder.DEFAULT_CAP,
    ): ScreenContext = ScreenContextBuilder(cap).build(facts(roots, flagsOf), enabled)

    fun collect(
        root: UiNode?,
        flagsOf: (UiNode) -> ScreenNodeFlags,
        enabled: Boolean,
        cap: Int = ScreenContextBuilder.DEFAULT_CAP,
    ): ScreenContext = collect(listOfNotNull(root), flagsOf, enabled, cap)
}

/** 只有布尔，没有任何文本——多带一个字段就是多一条泄露面。 */
data class ScreenNodeFlags(
    /** 平台事实（AccessibilityNodeInfo.isEditable）；类名那层兜底在 `:byok` 里，两条是"或"。 */
    val editable: Boolean = false,
    val passwordLike: Boolean = false,
    val visibleToUser: Boolean = true,
)
