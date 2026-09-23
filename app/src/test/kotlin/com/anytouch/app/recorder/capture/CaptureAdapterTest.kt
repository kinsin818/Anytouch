package com.anytouch.app.recorder.capture

import com.anytouch.app.recorder.NodeAction
import com.anytouch.app.recorder.NodeActionKind
import com.anytouch.app.recorder.WindowChanged
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureAdapterTest {

    private fun raw(
        hint: CaptureHint,
        pkg: String = TARGET,
        id: String? = null,
        text: String? = null,
        desc: String? = null,
        path: List<Int> = listOf(0, 1),
        scrollY: Int = 0,
        maxScrollY: Int = 0,
        deltaY: Int = 0,
    ) = RawNodeSnapshot(
        resourceId = id, text = text, contentDesc = desc, className = null,
        pkg = pkg, indexPath = path, scrollY = scrollY, maxScrollY = maxScrollY,
        scrollDeltaY = deltaY, hint = hint,
    )

    private fun adapter() = CaptureAdapter(targetPkg = TARGET, selfPkg = SELF)

    @Test
    fun `click 快照翻译为已确认节点动作`() {
        val a = adapter()
        val event = a.onCapture(CaptureEvent(raw(CaptureHint.CLICK, id = "x:id/bt", text = "Bluetooth"), timestampMs = 1000))
        val action = checkNotNull(event) as NodeAction
        assertEquals(NodeActionKind.CLICK, action.kind)
        assertTrue("用户亲为=已确认（高危由回放期二次确认门把关）", action.confirmed)
        assertEquals("x:id/bt", action.snapshot.resourceId)
        assertEquals(listOf(0, 1), action.snapshot.indexPath)
    }

    @Test
    fun `自家 App 事件永不入流`() {
        val a = adapter()
        assertNull(a.onCapture(CaptureEvent(raw(CaptureHint.CLICK, pkg = SELF), timestampMs = 1)))
        assertNull(a.onCapture(CaptureEvent(raw(CaptureHint.WINDOW_CHANGED, pkg = SELF), timestampMs = 2)))
        assertTrue(a.acceptedEvents.isEmpty())
    }

    @Test
    fun `窗态切换翻译为 WindowChanged`() {
        val a = adapter()
        val event = a.onCapture(CaptureEvent(raw(CaptureHint.WINDOW_CHANGED, pkg = "com.other"), timestampMs = 7))
        assertEquals(WindowChanged("com.other", null, 7), event)
    }

    @Test
    fun `连续同节点打字折叠为终态一条`() {
        val a = adapter()
        val id = "x:id/input"
        a.onCapture(CaptureEvent(raw(CaptureHint.TEXT_CHANGED, id = id, text = "w"), timestampMs = 1))
        a.onCapture(CaptureEvent(raw(CaptureHint.TEXT_CHANGED, id = id, text = "wi"), timestampMs = 2))
        val last = a.onCapture(CaptureEvent(raw(CaptureHint.TEXT_CHANGED, id = id, text = "wifi"), timestampMs = 3))
        assertEquals(3, a.acceptedEvents.size)
        assertEquals(2, a.staleEvents.size)
        val action = last as NodeAction
        assertEquals("wifi", action.text)
        assertEquals(listOf(action), a.exportEventList().filterIsInstance<NodeAction>())
    }

    @Test
    fun `跨节点打字不互相折叠`() {
        val a = adapter()
        a.onCapture(CaptureEvent(raw(CaptureHint.TEXT_CHANGED, id = "x:id/a", text = "w"), timestampMs = 1))
        a.onCapture(CaptureEvent(raw(CaptureHint.TEXT_CHANGED, id = "x:id/b", text = "p"), timestampMs = 2))
        assertTrue(a.staleEvents.isEmpty())
        assertEquals(2, a.exportEventList().size)
    }

    @Test
    fun `滚动方向按位置变化判定`() {
        val a = adapter()
        val id = "x:id/list"
        val first = a.onCapture(
            CaptureEvent(raw(CaptureHint.SCROLLED, id = id, scrollY = 100, maxScrollY = 900), timestampMs = 1),
        ) as NodeAction
        assertEquals(NodeActionKind.SCROLL_FORWARD, first.kind)
        val back = a.onCapture(
            CaptureEvent(raw(CaptureHint.SCROLLED, id = id, scrollY = 40, maxScrollY = 900), timestampMs = 2),
        ) as NodeAction
        assertEquals(NodeActionKind.SCROLL_BACKWARD, back.kind)
    }

    @Test
    fun `首见滚动到底时判回退否则判前进`() {
        val a = adapter()
        val atEnd = a.onCapture(
            CaptureEvent(raw(CaptureHint.SCROLLED, id = "x:id/list", scrollY = 900, maxScrollY = 900), timestampMs = 1),
        ) as NodeAction
        assertEquals(NodeActionKind.SCROLL_BACKWARD, atEnd.kind)
        val fresh = a.onCapture(
            CaptureEvent(raw(CaptureHint.SCROLLED, id = "x:id/other", scrollY = 0, maxScrollY = 900), timestampMs = 2),
        ) as NodeAction
        assertEquals(NodeActionKind.SCROLL_FORWARD, fresh.kind)
    }

    @Test
    fun `零位移证据的滚动判噪不成事件但留痕`() {
        // 设备实证：Settings 页面切换后的布局重排每次必发 delta=0/pos=0/max=0 的 viewScrolled，
        // 旧口径的"到底启发式"把它记成 forward 滚动步 → 回放期 perform_failed 假红（3 步录成 6 步）。
        val a = adapter()
        assertNull(
            a.onCapture(CaptureEvent(raw(CaptureHint.SCROLLED, id = "x:id/list"), timestampMs = 1)),
        )
        assertTrue(a.acceptedEvents.isEmpty())
        val notes = a.drainNotes()
        assertEquals(1, notes.size)
        assertTrue("留痕须含归因关键词", notes[0].contains("无位移证据"))
        assertTrue("留痕须指回节点", notes[0].contains("x:id/list"))
        assertTrue("取走即清账", a.drainNotes().isEmpty())
    }

    @Test
    fun `signed delta 优先于位置比较定方向`() {
        val a = adapter()
        val event = a.onCapture(
            CaptureEvent(raw(CaptureHint.SCROLLED, id = "x:id/list", deltaY = -220, scrollY = 0, maxScrollY = 900), timestampMs = 1),
        ) as NodeAction
        assertEquals(NodeActionKind.SCROLL_BACKWARD, event.kind)
    }

    @Test
    fun `长按以 UNKNOWN 入流交编译期留痕丢弃`() {
        val a = adapter()
        val event = a.onCapture(CaptureEvent(raw(CaptureHint.LONG_CLICK, id = "x:id/item"), timestampMs = 1))
            as NodeAction
        assertEquals(NodeActionKind.UNKNOWN, event.kind)
    }

    @Test
    fun `高危词命中不枪毙：录制保留，回放期二次确认负责`() {
        val a = adapter()
        val event = a.onCapture(
            CaptureEvent(raw(CaptureHint.CLICK, id = "x:id/reset_password", text = "Reset password"), timestampMs = 1),
        ) as NodeAction
        assertTrue(event.confirmed)
        assertEquals(NodeActionKind.CLICK, event.kind)
    }

    @Test
    fun `computeIndexPath 自叶到根记录子下标`() {
        val root = FakeNode("root")
        val a = FakeNode("a").apply { parent = root }
        val b = FakeNode("b").apply { parent = root }
        val target = FakeNode("t").apply { parent = b }
        root.children = listOf(a, b)
        b.children = listOf(target)
        assertEquals(
            listOf(1, 0),
            computeIndexPath(
                target,
                parentOf = { it.parent },
                childCountOf = { it.children.size },
                indexOfChild = { parent, child -> parent.children.indexOf(child) },
            ),
        )
    }

    @Test
    fun `computeIndexPath 父链断裂返回负路径交编译期丢弃`() {
        val orphan = FakeNode("orphan").apply { parent = FakeNode("detached-unfindable") }
        orphan.parent!!.children = listOf(FakeNode("someone-else"))
        assertEquals(
            listOf(-1),
            computeIndexPath(
                orphan,
                parentOf = { it.parent },
                childCountOf = { it.children.size },
                indexOfChild = { parent, child -> parent.children.indexOf(child) },
            ),
        )
    }

    @Test
    fun `computeIndexPath 根节点得空路径`() {
        val root = FakeNode("root")
        assertEquals(
            emptyList<Int>(),
            computeIndexPath(
                root,
                parentOf = { it.parent },
                childCountOf = { it.children.size },
                indexOfChild = { parent, child -> parent.children.indexOf(child) },
            ),
        )
    }

    @Test
    fun `computeIndexPath 父链终点非根时判不可信`() {
        // 设备实证：event.source 的 parent 会无缘由返回 null，节点其实深在树中——
        // 照单采信就录出 *[2] 这种指向别的控件的"路径"，回放=误点（比失败更糟）。
        val detached = FakeNode("detached").apply { children = listOf(FakeNode("x")) }
        val target = FakeNode("t").apply { parent = detached }
        detached.children = listOf(target)
        assertNull(
            computeIndexPath(
                target,
                parentOf = { it.parent },
                childCountOf = { it.children.size },
                indexOfChild = { parent, child -> parent.children.indexOf(child) },
                isRoot = { false }, // 系统告诉我们"这不是根"
            ),
        )
        assertEquals(listOf(0), computeIndexPath(
            target,
            parentOf = { it.parent },
            childCountOf = { it.children.size },
            indexOfChild = { parent, child -> parent.children.indexOf(child) },
            isRoot = { it.name == "detached" }, // 承认为根才出路径
        ))
    }

    @Test
    fun `一次慢拖的多条滚动事件只成一步`() {
        // 设备实证：500px/900ms 的一次慢拖，框架连发 3 条 viewScrolled（条间 ~300ms）。
        // 逐条成步会把"滚一下"回放成"滚三下"；超窗后的下一次独立手势必须照常成步。
        val a = adapter()
        val id = "x:id/list"
        assertNotNull(a.onCapture(CaptureEvent(raw(CaptureHint.SCROLLED, id = id, scrollY = 100, maxScrollY = 900), timestampMs = 1000)))
        assertNull(a.onCapture(CaptureEvent(raw(CaptureHint.SCROLLED, id = id, scrollY = 180, maxScrollY = 900), timestampMs = 1300)))
        assertNull(a.onCapture(CaptureEvent(raw(CaptureHint.SCROLLED, id = id, scrollY = 260, maxScrollY = 900), timestampMs = 1500)))
        assertEquals(1, a.acceptedEvents.size)
        val notes = a.drainNotes()
        assertEquals(2, notes.count { it.contains("同手势合并") })
        assertNotNull("隔了 1s 的第二次手势应另成一步",
            a.onCapture(CaptureEvent(raw(CaptureHint.SCROLLED, id = id, scrollY = 400, maxScrollY = 900), timestampMs = 2500)))
        assertEquals(2, a.acceptedEvents.size)
    }

    @Test
    fun `被折叠的打字中间态可由会话层取走`() {
        val a = adapter()
        val id = "x:id/input"
        a.onCapture(CaptureEvent(raw(CaptureHint.TEXT_CHANGED, id = id, text = "w"), timestampMs = 1))
        a.onCapture(CaptureEvent(raw(CaptureHint.TEXT_CHANGED, id = id, text = "wi"), timestampMs = 2))
        a.onCapture(CaptureEvent(raw(CaptureHint.TEXT_CHANGED, id = id, text = "wifi"), timestampMs = 3))
        assertEquals(2, a.drainStaled().size)
        assertTrue("取走即清账", a.drainStaled().isEmpty())
    }

    private class FakeNode(val name: String) {
        var parent: FakeNode? = null
        var children: List<FakeNode> = emptyList()
    }

    private companion object {
        const val TARGET = "com.android.settings"
        const val SELF = "com.anytouch.app"
    }
}
