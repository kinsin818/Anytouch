package com.anytouch.app.compile

import com.anytouch.app.locator.UiNode
import com.anytouch.app.platform.AccessibilityUiNode

/**
 * 设备缝：屏上下文需要的三个布尔取自 AccessibilityNodeInfo，**不读任何文本内容**。
 *
 * "是不是输入框"两条判据并存：这里给平台事实 `isEditable`（android-36 的 jar 里就这个名字，
 * 无 `isTextEditable`——初版按记忆写错、编译期即 Unresolved），`:byok` 另按类名兜一层，
 * 两条"或"关系：自绘输入框类名不像 EditText、类名像 EditText 的也可能旗标没置上，
 * 任一条命中就按输入框对待——宁可少上行一条词表，也不冒上行用户内容的风险。
 *
 * 本函数单独成文件：`AccessibilityUiNode` 是 Android 类型，JVM 装不上，
 * 判据全留在 [ScreenContextCollector]（纯函数）里锁，这里只有搬运，JVM 覆盖 0。
 * 非无障碍快照节点（假件/别的树源）按"无旗标"处理。
 */
fun accessibilityFlagsOf(node: UiNode): ScreenNodeFlags {
    val info = (node as? AccessibilityUiNode)?.info ?: return ScreenNodeFlags()
    return ScreenNodeFlags(
        editable = info.isEditable,
        passwordLike = info.isPassword,
        visibleToUser = info.isVisibleToUser,
    )
}
