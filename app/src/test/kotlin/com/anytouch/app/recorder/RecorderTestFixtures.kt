package com.anytouch.app.recorder

import com.anytouch.app.locator.TestUi
import com.anytouch.app.locator.UiNode
import com.anytouch.app.locator.ui
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal const val TARGET_PKG = "com.target.app"
internal const val OTHER_PKG = "com.other.app"

/** 按子下标链走树。 */
internal fun UiNode.at(vararg childPath: Int): UiNode =
    childPath.fold(this) { node, index -> node.children[index] }

/** 从真实树节点生成快照：字段取节点自述，indexPath 即走过的子下标链。 */
internal fun snapshotOf(root: UiNode, indexPath: List<Int>, pkg: String = TARGET_PKG): NodeSnapshot {
    val node = root.at(*indexPath.toIntArray())
    return NodeSnapshot(
        resourceId = node.resourceId,
        text = node.text,
        contentDesc = node.contentDesc,
        className = node.className,
        pkg = pkg,
        indexPath = indexPath,
    )
}

internal fun clickAt(
    root: UiNode,
    ts: Long,
    indexPath: List<Int>,
    confirmed: Boolean = true,
    pkg: String = TARGET_PKG,
): NodeAction = NodeAction(
    kind = NodeActionKind.CLICK,
    snapshot = snapshotOf(root, indexPath, pkg),
    timestampMs = ts,
    confirmed = confirmed,
)

internal fun otherPkgClick(snapshot: NodeSnapshot, ts: Long = 10): NodeAction =
    NodeAction(NodeActionKind.CLICK, snapshot.copy(pkg = OTHER_PKG), timestampMs = ts, confirmed = true)

internal fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.content

/** 三层树：root 下两个带文本的叶子 + ListView（[0]=带文本 pad，[1]=无任何定位线索的裸节点）。 */
internal fun tree(): TestUi = ui(
    clazz = "android.view.Window",
    children = listOf(
        ui(marker = "A", clazz = "android.widget.TextView"),
        ui(marker = "B", clazz = "android.widget.TextView"),
        ui(
            clazz = "android.widget.ListView",
            children = listOf(
                ui(marker = "pad", clazz = "android.widget.TextView"),
                ui(clazz = "android.widget.FrameLayout"),
            ),
        ),
    ),
)
