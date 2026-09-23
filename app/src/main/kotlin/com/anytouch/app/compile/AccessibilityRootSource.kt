package com.anytouch.app.compile

import com.anytouch.app.locator.UiNode

/**
 * 屏上下文的**唯一**取树口（设备缝，JVM 覆盖 0）：词表的那棵树由这里给，别处不许另找一棵。
 *
 * 根集合口径就此钉死（军令 §4-6 待裁项的主窗自裁）：**上行面 = 执行面的 `NodeActions.root()`**——
 * 同一个函数、同一个线程规格（主线程）、同一棵返回来的树。为什么必须一模一样：
 * 回放面只认这一棵活动窗（活动窗优先，取不到才按焦点/层级取唯一一个应用窗），上行面若多扫一个窗
 * （输入法候选窗、状态栏、自家浮窗），模型就会拿到屏上不可点的词，编出的步骤回放必 `NO_MATCH`——
 * 这正是雷 18 的族谱（"分母必须等于真正被扫的那棵树"），只是这次错的是上行面而不是采集面。
 *
 * 代价明说：输入法候选窗 / 系统弹窗上的词模型看不见，用户看得见却编不出来。
 * 这是"宁缺勿多"的自觉取舍，不是缺陷；话术层会显式告诉用户本次上行几条。
 *
 * 服务没接线时 [snapshot] 恒 null（fail-closed）：没有树就没有词表，绝不退化成"猜一棵"。
 */
object AccessibilityRootSource {

    /** 由无障碍服务在 onServiceConnected 挂、onDestroy 摘；必须在主线程调用（a11y 连接绑主 Looper）。 */
    @Volatile
    var provider: (suspend () -> UiNode?)? = null

    suspend fun snapshot(): UiNode? = runCatching { provider?.invoke() }.getOrNull()
}

/**
 * 取一棵"可以上行"的树：自家窗口不参与（用户在面板上按编译时，活动窗就是我们自己——
 * 那屏上的词是我们的按钮文案，模型拿去只会编出"点自己的界面"，回放"成功"而目标 App 一步没动）。
 * 判据是纯函数，JVM 锁得住；取树本身在 [AccessibilityRootSource]。
 */
fun uplinkRootOf(root: UiNode?, selfPkg: String?): UiNode? {
    val pkg = root?.packageName
    if (root != null && selfPkg != null && pkg == selfPkg) return null
    // 包名拿不到（null）的树保留：那不是"疑似自家"，执行面同样看得见它，这里无权偷偷裁掉。
    return root
}
