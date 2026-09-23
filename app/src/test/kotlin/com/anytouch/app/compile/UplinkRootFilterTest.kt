package com.anytouch.app.compile

import com.anytouch.app.locator.UiBounds
import com.anytouch.app.locator.UiNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlinx.coroutines.runBlocking

/**
 * 上行面根集合的 JVM 锁（S3-D，军令 §4-6 主窗自裁的那一条）：
 * **自家窗不上行，别家窗整棵上行，包名不明的树保留**。
 *
 * 为什么这条值得锁：上行面一旦比回放面宽，模型就拿到屏上不可点的词，编出的步骤回放必 `NO_MATCH`
 * （雷 18 同族）。用户在面板上按「AI 编译」时活动窗正是我们自己——那一屏按钮文案如果被上行，
 * 模型会编出"点自己的界面"，回放全程"成功"而目标 App 一步没动，是最难看的假绿形态。
 */
class UplinkRootFilterTest {

    private class Fake(
        override val packageName: String? = null,
        override val text: String? = null,
        override val children: List<UiNode> = emptyList(),
    ) : UiNode {
        override val resourceId: String? get() = null
        override val contentDesc: String? get() = null
        override val className: String? get() = null
        override val clickable: Boolean get() = false
        override val scrollable: Boolean get() = false
        override val bounds: UiBounds? get() = null
    }

    private val self = "com.anytouch.app"

    @Test
    fun `自家活动窗不参与上行`() {
        assertNull(uplinkRootOf(Fake(packageName = self, text = "AI 编译"), self))
    }

    @Test
    fun `目标窗整棵保留`() {
        val settings = Fake(packageName = "com.android.settings", text = "Bluetooth")
        assertSame(settings, uplinkRootOf(settings, self))
    }

    @Test
    fun `包名取不到的树不裁 因为回放面同样看得见它`() {
        val unknown = Fake(packageName = null, text = "Bluetooth")
        assertSame(unknown, uplinkRootOf(unknown, self))
    }

    @Test
    fun `自家包名还没绑定时一律按别家窗处理 fail-closed 在取数侧而不是裁树侧`() {
        // 服务尚未 onServiceConnected 时 selfPkg=null：此时不猜"这棵是谁的"，交给词表构造与零节点话术说破
        val any = Fake(packageName = self, text = "Bluetooth")
        assertSame(any, uplinkRootOf(any, null))
    }

    @Test
    fun `取树钩子缺席时就是没有树`() {
        val saved = AccessibilityRootSource.provider
        try {
            AccessibilityRootSource.provider = null
            assertNull(runBlocking { AccessibilityRootSource.snapshot() })
        } finally {
            AccessibilityRootSource.provider = saved
        }
    }

    @Test
    fun `钩子抛错不冒泡 按没有树处理`() {
        val saved = AccessibilityRootSource.provider
        try {
            AccessibilityRootSource.provider = { throw IllegalStateException("服务已解绑") }
            assertNull(uplinkRootOf(runBlocking { AccessibilityRootSource.snapshot() }, self))
        } finally {
            AccessibilityRootSource.provider = saved
        }
    }
}
