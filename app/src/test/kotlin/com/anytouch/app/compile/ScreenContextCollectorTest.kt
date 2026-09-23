package com.anytouch.app.compile

import com.anytouch.app.locator.UiBounds
import com.anytouch.app.locator.UiNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 走树取数的 JVM 锁（S3-C）：树遍历、字段搬运、开关短路三件事。
 * Android 侧旗标取法（[accessibilityFlagsOf]）是设备缝，本文件用假旗标函数替代。
 */
class ScreenContextCollectorTest {

    private class Fake(
        override val text: String? = null,
        override val resourceId: String? = null,
        override val className: String? = null,
        override val children: List<UiNode> = emptyList(),
    ) : UiNode {
        override val contentDesc: String? get() = null
        override val packageName: String? get() = null
        override val clickable: Boolean get() = false
        override val scrollable: Boolean get() = false
        override val bounds: UiBounds? get() = null
    }

    /** 假件只给屏外事实两个布尔；"是不是输入框"由 :byok 按类名判。 */
    private fun flagsByClass(node: UiNode): ScreenNodeFlags = ScreenNodeFlags(
        passwordLike = node.text?.contains("password", ignoreCase = true) == true,
        visibleToUser = node.className?.contains("Hidden") != true,
    )

    @Test
    fun `整棵树的节点都被扫到 父先子后`() {
        val root = Fake(
            text = "Settings",
            children = listOf(
                Fake(text = "Network", children = listOf(Fake(text = "Wi-Fi"))),
                Fake(text = "Apps"),
            ),
        )
        val facts = ScreenContextCollector.facts(root, ::flagsByClass)
        assertEquals(listOf("Settings", "Network", "Wi-Fi", "Apps"), facts.map { it.text })
    }

    @Test
    fun `根缺席时零条而不是空对象`() {
        assertEquals(0, ScreenContextCollector.facts(null as UiNode?, ::flagsByClass).size)
        val ctx = ScreenContextCollector.collect(null as UiNode?, ::flagsByClass, enabled = true)
        assertTrue(ctx.lines.isEmpty())
        assertEquals(0, ctx.nodesSeen)
    }

    @Test
    fun `开关关闭时屏上有字也零条上行`() {
        val root = Fake(text = "Bluetooth", children = listOf(Fake(resourceId = "com.a:id/toggle", className = "Switch")))
        val ctx = ScreenContextCollector.collect(root, ::flagsByClass, enabled = false)
        assertEquals(0, ctx.uploadedCount)
        assertEquals(2, ctx.nodesSeen, "屏上几个节点这件事要说真话，但一条都不上行")
        assertTrue(ctx.render().isEmpty())
    }

    @Test
    fun `取数层不判输入框 类名像输入框就不上行其内容`() {
        val root = Fake(text = "q1234", className = "android.widget.EditText", resourceId = "com.a:id/search")
        val facts = ScreenContextCollector.facts(root, ::flagsByClass)
        assertEquals(1, facts.size)
        assertFalse(facts[0].editable, "假件没给平台旗标——此时必须由 :byok 的类名兜底")
        val ctx = ScreenContextCollector.collect(root, ::flagsByClass, enabled = true)
        assertEquals(listOf("input_field(id=search)"), ctx.lines)
        assertFalse(ctx.lines.joinToString().contains("q1234"), "输入框内容被取数层带上行了")
    }

    @Test
    fun `平台旗标说可编辑时 类名不像输入框也照样只报存在`() {
        // 自绘输入框：className 是厂商自定义，只有 isEditable 认得它。
        val root = Fake(text = "card-4242", className = "com.vendor.SecureInputView", resourceId = "com.a:id/card_no")
        val ctx = ScreenContextCollector.collect(root, { ScreenNodeFlags(editable = true) }, enabled = true)
        assertEquals(listOf("input_field(id=card_no)"), ctx.lines)
        assertFalse(ctx.lines.joinToString().contains("card-4242"))
    }

    @Test
    fun `多窗口根并扫 同词只上行一次`() {
        val dialogs = Fake(text = "Allow", children = listOf(Fake(text = "Deny")))
        val app = Fake(text = "Allow", children = listOf(Fake(text = "Bluetooth")))
        val ctx = ScreenContextCollector.collect(listOf(app, dialogs), ::flagsByClass, enabled = true)
        assertEquals(listOf("text=\"Allow\"", "text=\"Bluetooth\"", "text=\"Deny\""), ctx.lines)
        assertEquals(1, ctx.droppedDuplicate)
    }
}
