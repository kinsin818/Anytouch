package com.anytouch.app.locator

/** JVM 单测用的 [UiNode] 实现：主窗的 AccessibilityNodeInfo 适配器不会出现在这里。 */
class TestUi(
    override val resourceId: String? = null,
    override var text: String? = null,
    override val contentDesc: String? = null,
    override val className: String? = null,
    override val packageName: String? = null,
    override val clickable: Boolean = false,
    override val scrollable: Boolean = false,
    override val bounds: UiBounds? = null,
    override val children: List<UiNode> = emptyList(),
) : UiNode {
    override fun toString(): String = NodeLabel.of(this)
}

internal fun ui(
    marker: String? = null,
    id: String? = null,
    clazz: String? = null,
    desc: String? = null,
    pkg: String? = null,
    clickable: Boolean = false,
    scrollable: Boolean = false,
    bounds: UiBounds? = null,
    children: List<UiNode> = emptyList(),
): TestUi = TestUi(
    resourceId = id,
    text = marker,
    contentDesc = desc,
    className = clazz,
    packageName = pkg,
    clickable = clickable,
    scrollable = scrollable,
    bounds = bounds,
    children = children,
)

/** 深层嵌套：depth 层单子链，叶子为 leaf。 */
internal fun deepChain(depth: Int, leaf: UiNode): UiNode {
    var node = leaf
    repeat(depth) { node = ui(clazz = "android.view.FrameLayout", children = listOf(node)) }
    return node
}

internal fun LocatorResult.attempt(level: LocatorLevel): LocatorAttempt =
    attempts.first { it.level == level }

internal fun LocatorResult.hit(): LocatorHit = this as LocatorHit

internal fun LocatorResult.miss(): LocatorMiss = this as LocatorMiss
