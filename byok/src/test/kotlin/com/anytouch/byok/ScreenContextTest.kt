package com.anytouch.byok

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 屏上下文最小化（S3-C，裁 2 + 军令 §3-5）的判据锁。
 * 核心不是"能拼出词表"，而是"关了就零条、输入框内容永不上行、密码类整条不上行"。
 */
class ScreenContextTest {

    private val builder = ScreenContextBuilder()

    private fun texts(facts: List<ScreenNodeFact>, enabled: Boolean = true): List<String> =
        builder.build(facts, enabled).lines

    @Test
    fun `关闭开关时零条上行且渲染为空串`() {
        val facts = listOf(
            ScreenNodeFact(text = "Bluetooth"),
            ScreenNodeFact(text = "Wi-Fi"),
        )
        val off = builder.build(facts, enabled = false)
        assertTrue(off.lines.isEmpty(), "关闭后必须零条，实际=${off.lines}")
        assertEquals(0, off.uploadedCount)
        assertEquals("", off.render(), "关闭时渲染必须是空串（一个字都不上行）")
        assertTrue(off.notice().contains("Screen context: off"))
        assertFalse(off.notice().contains("2 visible text line(s) sent"), "关闭话术里不许出现条数冒充上行")
    }

    @Test
    fun `输入框只上行存在这一事实 其内容一个字都不上行`() {
        val secret = "my-password-is-hunter2"
        val lines = texts(
            listOf(
                ScreenNodeFact(text = secret, resourceId = "com.app:id/search_box", className = "android.widget.EditText", editable = true),
            ),
        )
        assertEquals(listOf("input_field(id=search_box)"), lines)
        val blob = lines.joinToString("|")
        assertFalse(blob.contains(secret), "输入框内容泄露：$blob")
        assertFalse(blob.contains("hunter2"))
    }

    @Test
    fun `密码类节点整条剔除 连资源id都不上行`() {
        val ctx = builder.build(
            listOf(
                ScreenNodeFact(text = "Password", resourceId = "com.app:id/pwd_field", className = "android.widget.EditText", editable = true, passwordLike = true),
                ScreenNodeFact(text = "Sign in"),
            ),
        )
        assertEquals(listOf("text=\"Sign in\""), ctx.lines)
        assertEquals(1, ctx.droppedPassword)
        val blob = ctx.lines.joinToString("|")
        assertFalse(blob.contains("pwd"), "密码框的资源名也不许上行：$blob")
    }

    @Test
    fun `resource-id 只上行 entry 段 不带包名前缀`() {
        val lines = texts(listOf(ScreenNodeFact(text = null, resourceId = "com.samsung.android.settings:id/wifi_toggle")))
        assertEquals(listOf("id=wifi_toggle"), lines)
        assertFalse(lines[0].contains("com.samsung"))
    }

    @Test
    fun `上行行只有白名单三种形态`() {
        val lines = texts(
            listOf(
                ScreenNodeFact(text = "Bluetooth"),
                ScreenNodeFact(text = null, resourceId = "com.a:id/row"),
                ScreenNodeFact(text = "abc", className = "android.widget.EditText", editable = true),
            ),
        )
        val whitelist = Regex("^(text=\".+\"|id=[A-Za-z0-9_./-]+|input_field\\([A-Za-z0-9_./-]+\\))$")
        lines.forEach { assertTrue(whitelist.matches(it), "上行行越出白名单：$it") }
    }

    @Test
    fun `重复词只上行一条`() {
        val ctx = builder.build(
            listOf(
                ScreenNodeFact(text = "Settings"),
                ScreenNodeFact(text = "Settings"),
                ScreenNodeFact(text = " Settings "),
            ),
        )
        assertEquals(listOf("text=\"Settings\""), ctx.lines)
        assertEquals(2, ctx.droppedDuplicate, "三条同词应记两条重复，实际=${ctx.droppedDuplicate}")
    }

    @Test
    fun `封顶默认40条 超出部分计入账而不是静默丢弃`() {
        val facts = (1..60).map { ScreenNodeFact(text = "item-$it") }
        val ctx = builder.build(facts)
        assertEquals(40, ctx.lines.size)
        assertEquals(20, ctx.droppedOverCap)
        assertEquals("text=\"item-1\"", ctx.lines.first())
        assertEquals("text=\"item-40\"", ctx.lines.last())
    }

    @Test
    fun `不可见节点不占上行名额 且名额优先给可见项`() {
        val ctx = builder.build(
            listOf(
                ScreenNodeFact(text = "offscreen", visibleToUser = false),
                ScreenNodeFact(text = "onscreen"),
            ),
        )
        assertEquals(listOf("text=\"onscreen\""), ctx.lines)
        assertEquals(1, ctx.droppedInvisible)
    }

    @Test
    fun `排序稳定 可见项按文档序`() {
        val lines = texts(
            listOf(
                ScreenNodeFact(text = "A"),
                ScreenNodeFact(text = "Z", visibleToUser = false),
                ScreenNodeFact(text = "B"),
            ),
        )
        assertEquals(listOf("A", "B").map { "text=\"$it\"" }, lines)
    }

    @Test
    fun `无文本无id的纯容器不产出一行`() {
        val ctx = builder.build(
            listOf(
                ScreenNodeFact(text = "   ", resourceId = null, className = "android.view.ViewGroup"),
                ScreenNodeFact(text = null, resourceId = "  "),
            ),
        )
        assertTrue(ctx.lines.isEmpty(), "空文本容器不该上行：${ctx.lines}")
    }

    @Test
    fun `话术里的条数必须等于真实上行条数`() {
        val ctx = builder.build(
            listOf(
                ScreenNodeFact(text = "Bluetooth"),
                ScreenNodeFact(text = "super-secret-typed", className = "EditText", editable = true),
                ScreenNodeFact(text = "Sign in"),
            ),
        )
        assertEquals(3, ctx.uploadedCount, "输入框也占一行（只是那行没有内容）")
        assertTrue(ctx.notice().contains("${ctx.uploadedCount} visible text line(s) sent"), ctx.notice())
        assertTrue(
            ctx.notice().contains("password lines dropped") || ctx.notice().contains("input fields reported as present only"),
            ctx.notice(),
        )
    }

    @Test
    fun `取数层漏给 editable 旗标时按类名兜住 内容照样不上行`() {
        // 真机上 isTextEditable 拿不到（假件/别的树源）——只有类名可依据，此时必须宁缺勿上行。
        val lines = texts(
            listOf(
                ScreenNodeFact(text = "card-4242", className = "android.widget.AutoCompleteTextView", resourceId = "com.a:id/card"),
                ScreenNodeFact(text = "5000", className = "android.widget.TextView"),
            ),
        )
        assertEquals(listOf("input_field(id=card)", "text=\"5000\""), lines)
        assertFalse(lines.joinToString().contains("card-4242"))
    }

    @Test
    fun `事实类的 toString 不回显节点文本`() {
        val s = ScreenNodeFact(text = "hunter2", resourceId = "com.a:id/b").toString()
        assertFalse(s.contains("hunter2"), "toString 泄露内容：$s")
    }

    @Test
    fun `cap 非法直接拒 不允许 0 或负数伪装成关闭`() {
        assertFalse(runCatching { ScreenContextBuilder(0) }.isSuccess)
        assertFalse(runCatching { ScreenContextBuilder(-1) }.isSuccess)
    }
}

/** 上下文进 prompt 的那一段：老链路必须逐字不变，新链路必须真把词表带上去。 */
class ScreenContextPromptTest {

    private class Recorder(private val reply: String) : LlmTransport {
        var system: String? = null
        var user: String? = null
        override fun complete(systemPrompt: String, userPrompt: String): String {
            system = systemPrompt
            user = userPrompt
            return reply
        }
    }

    @Test
    fun `无上下文时用户消息逐字等于意图`() {
        assertEquals(
            "打开蓝牙页",
            CompilerPrompt.userMessage("打开蓝牙页"),
            "切片 A 的老链路不许因为本批改动而漂移",
        )
    }

    @Test
    fun `带上下文时意图在前 词表在后 且 SYSTEM 未被动过`() {
        val ctx = ScreenContextBuilder().build(listOf(ScreenNodeFact(text = "Connected devices")))
        val msg = CompilerPrompt.userMessage("进蓝牙页", ctx)
        assertTrue(msg.startsWith("进蓝牙页\n"), msg)
        assertTrue(msg.contains("text=\"Connected devices\""), msg)
        val rec = Recorder("[]")
        DslCompiler(rec).compile("进蓝牙页", ctx)
        assertEquals(CompilerPrompt.SYSTEM, rec.system)
        assertTrue(rec.user!!.contains("【当前屏幕可见词表】"))
    }

    @Test
    fun `关闭态编译时用户消息里没有任何屏幕词`() {
        // 夹具由 key/back 改成 click：S3-F/F2 收紧词表后 key 已不在授权集里（裁 S31-B3），
        // 本条判的是"开关关着时上行里不含屏幕词"，与被拒的词表无关——留着 key 只会让这条测成"编译拒了"。
        val rec = Recorder("""[{"action_id":"a","type":"click","source":"node","value":{"text":"Settings"},"safety":{"viewport_ok":true,"click_enabled":true}}]""")
        val off = ScreenContext.disabled()
        val result = DslCompiler(rec).compile("打开设置", off)
        assertTrue(result is CompileResult.Ok)
        assertEquals("打开设置", rec.user)
    }

    @Test
    fun `带上词表不削弱校验 模型仍编坐标照样拒`() {
        val ctx = ScreenContextBuilder().build(listOf(ScreenNodeFact(text = "Bluetooth")))
        val rec = Recorder("""[{"action_id":"a","type":"click","source":"node","target":{"x":10,"y":20},"value":{"text":"Bluetooth"},"safety":{"viewport_ok":true,"click_enabled":true}}]""")
        val result = DslCompiler(rec).compile("点蓝牙", ctx)
        assertTrue(result is CompileResult.Reject && result.stage == "validate", "坐标必须照拒：$result")
    }
}
