package com.anytouch.app.locator

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * 军令 L2-STAGE-11 的验收用例集：每阶正反例、多命中取序、深层嵌套、miss 全记录。
 * 全部在 JVM 内跑抽象 [UiNode]，不触碰任何 Android 类。
 */
class NodeTreeLocatorTest {

    private val locator = NodeTreeLocator()

    /* ================= 一阶：resource-id ================= */

    @Test
    fun `L1 唯一 resource-id 命中并留下 nodeRef 与活句柄`() {
        val target = ui("WLAN", id = "com.x:id/wifi", clazz = "android.widget.Switch", bounds = UiBounds(0, 0, 100, 50))
        val root = ui(id = "com.x:id/root", clazz = "android.view.Window", children = listOf(
            ui("list", clazz = "List", children = listOf(target)),
        ))

        val result = locator.locate(root, LocatorRequest(resourceId = "com.x:id/wifi")).hit()

        assertEquals(LocatorLevel.RESOURCE_ID, result.level)
        assertSame(target, result.node)
        assertEquals("resource-id='com.x:id/wifi'", result.matchedBy)
        assertEquals("root/0/0", result.nodeRef.indexPath)
        assertEquals("com.x:id/wifi", result.nodeRef.resourceId)
        assertEquals("android.widget.Switch", result.nodeRef.className)
        assertEquals(50, result.nodeRef.bounds?.centerX)
        assertEquals(25, result.nodeRef.bounds?.centerY)
        assertEquals(1, result.attempts.size)
        assertEquals(AttemptOutcome.HIT, result.attempts.single().outcome)
        assertEquals(1, result.attempts.single().candidateCount)
    }

    @Test
    fun `L1 多命中按 instance 取文档序`() {
        val root = ui(clazz = "Window", children = listOf(
            ui("A", id = "com.x:id/item"),
            ui("B", id = "com.x:id/item"),
            ui("C", id = "com.x:id/item"),
        ))

        val request = LocatorRequest(resourceId = "com.x:id/item")
        assertEquals("A", locator.locate(root, request).hit().node.text)
        assertEquals("B", locator.locate(root, request.copy(instance = 1)).hit().node.text)
        assertEquals("C", locator.locate(root, request.copy(instance = 2)).hit().node.text)
    }

    @Test
    fun `L1 instance 越界只报 miss 不降级`() {
        val root = ui(clazz = "Window", children = listOf(
            ui("A", id = "com.x:id/item"),
            ui("B", id = "com.x:id/item"),
            ui("C", id = "com.x:id/item"),
        ))

        val miss = locator.locate(root, LocatorRequest(resourceId = "com.x:id/item", instance = 3)).miss()

        assertEquals(LocatorMiss.NODE_NOT_FOUND, miss.code)
        val attempt = miss.attempt(LocatorLevel.RESOURCE_ID)
        assertEquals(AttemptOutcome.INDEX_OUT_OF_RANGE, attempt.outcome)
        assertEquals(3, attempt.candidateCount)
        assertTrue("out of range" in attempt.detail, attempt.detail)
        assertEquals(AttemptOutcome.NOT_ATTEMPTED, miss.attempt(LocatorLevel.TEXT).outcome)
        assertEquals(AttemptOutcome.NOT_ATTEMPTED, miss.attempt(LocatorLevel.PATH).outcome)
    }

    @Test
    fun `L1 resource-id 整串全等，同名 entry 跨包不算命中`() {
        val root = ui(clazz = "Window", children = listOf(ui("WLAN", id = "com.other:id/wifi")))

        val miss = locator.locate(root, LocatorRequest(resourceId = "com.x:id/wifi")).miss()
        assertEquals(AttemptOutcome.NO_MATCH, miss.attempt(LocatorLevel.RESOURCE_ID).outcome)
    }

    @Test
    fun `L1 可命中根节点自身`() {
        val root = ui("rootMarker", id = "com.x:id/root", clazz = "Window")

        val hit = locator.locate(root, LocatorRequest(resourceId = "com.x:id/root")).hit()
        assertEquals("root", hit.nodeRef.indexPath)
        assertSame(root, hit.node)
    }

    @Test
    fun `L1 定位不读坐标，bounds 相同的两节点靠 resource-id 分辨`() {
        val same = UiBounds(0, 0, 1080, 1920)
        val root = ui(clazz = "Window", children = listOf(
            ui("全屏遮罩", id = "com.x:id/scrim", bounds = same),
            ui("真按钮", id = "com.x:id/real", clickable = true, bounds = same),
        ))

        val hit = locator.locate(root, LocatorRequest(resourceId = "com.x:id/real")).hit()
        assertEquals("真按钮", hit.node.text)
        assertEquals("root/1", hit.nodeRef.indexPath)
    }

    @Test
    fun `空串 resource-id 记为非法而非静默跳过`() {
        val root = ui(clazz = "Window", children = listOf(ui("A", id = "com.x:id/item")))

        val miss = locator.locate(root, LocatorRequest(resourceId = "   ")).miss()
        assertEquals(AttemptOutcome.INVALID_QUERY, miss.attempt(LocatorLevel.RESOURCE_ID).outcome)
    }

    /* ================= 二阶：text 与 contentDescription ================= */

    @Test
    fun `一阶落空自动走二阶，尝试链留两阶记录`() {
        val root = ui(clazz = "Window", children = listOf(ui("WLAN", clazz = "Switch", clickable = true)))

        val hit = locator.locate(root, LocatorRequest(resourceId = "com.x:id/gone", text = "WLAN")).hit()
        assertEquals(LocatorLevel.TEXT, hit.level)
        assertEquals(AttemptOutcome.NO_MATCH, hit.attempts[0].outcome)
        assertEquals("text='WLAN'", hit.matchedBy)
    }

    @Test
    fun `L2 trim 后全等命中`() {
        val root = ui(clazz = "Window", children = listOf(ui("  保存  ", clickable = true)))

        val hit = locator.locate(root, LocatorRequest(text = "保存")).hit()
        assertEquals(LocatorLevel.TEXT, hit.level)
        assertEquals("text='保存'", hit.matchedBy)
        // nodeRef 忠实快照节点原值，不做二次 trim
        assertEquals("  保存  ", hit.nodeRef.text)
    }

    @Test
    fun `L2 禁止 contains 式模糊命中`() {
        val root = ui(clazz = "Window", children = listOf(ui("保存到云端")))

        val miss = locator.locate(root, LocatorRequest(text = "保存")).miss()
        assertEquals(AttemptOutcome.NO_MATCH, miss.attempt(LocatorLevel.TEXT).outcome)
        assertTrue("not contains" in miss.summary, miss.summary)
    }

    @Test
    fun `L2 contentDescription 等值命中并在 matchedBy 留痕`() {
        val root = ui(clazz = "Window", children = listOf(ui(desc = "  移动数据 ", clickable = true)))

        val hit = locator.locate(root, LocatorRequest(contentDesc = "移动数据")).hit()
        assertEquals("contentDescription='移动数据'", hit.matchedBy)
    }

    @Test
    fun `L2 text 与 desc 同时给出时按节点先后取命中`() {
        val root = ui(clazz = "Window", children = listOf(
            ui("WLAN", desc = "无线局域网", clickable = true),
            ui(desc = "蓝牙", clickable = true),
        ))

        val byText = locator.locate(root, LocatorRequest(text = "WLAN", contentDesc = "蓝牙")).hit()
        assertEquals("WLAN", byText.node.text)
        assertEquals("text='WLAN'", byText.matchedBy)

        val byDesc = locator.locate(root, LocatorRequest(text = "不存在", contentDesc = "蓝牙")).hit()
        assertEquals("蓝牙", byDesc.node.contentDesc)
        assertEquals("contentDescription='蓝牙'", byDesc.matchedBy)
    }

    @Test
    fun `L2 多命中按文档序深度优先取 instance`() {
        val root = ui(clazz = "Window", children = listOf(
            ui("WLAN", children = listOf(ui("WLAN", children = listOf(ui("WLAN"))))),
            ui("WLAN"),
        ))
        val request = LocatorRequest(text = "WLAN")

        assertEquals("root/0", locator.locate(root, request).hit().nodeRef.indexPath)
        assertEquals("root/0/0", locator.locate(root, request.copy(instance = 1)).hit().nodeRef.indexPath)
        assertEquals("root/0/0/0", locator.locate(root, request.copy(instance = 2)).hit().nodeRef.indexPath)
        assertEquals("root/1", locator.locate(root, request.copy(instance = 3)).hit().nodeRef.indexPath)
        assertEquals(4, locator.locate(root, request).hit().attempts.last().candidateCount)
        assertEquals(
            AttemptOutcome.INDEX_OUT_OF_RANGE,
            locator.locate(root, request.copy(instance = 4)).miss().attempt(LocatorLevel.TEXT).outcome,
        )
    }

    @Test
    fun `L2 空串线索记为非法`() {
        val root = ui(clazz = "Window", children = listOf(ui("WLAN")))

        val miss = locator.locate(root, LocatorRequest(text = "", contentDesc = "  ")).miss()
        assertEquals(AttemptOutcome.INVALID_QUERY, miss.attempt(LocatorLevel.TEXT).outcome)
    }

    @Test
    fun `L2 深层嵌套可命中`() {
        val root = ui(clazz = "Window", children = listOf(deepChain(12, ui("版本", clickable = true))))

        val hit = locator.locate(root, LocatorRequest(text = "版本")).hit()
        assertEquals(LocatorLevel.TEXT, hit.level)
        assertEquals(14, hit.nodeRef.indexPath.split('/').size)
    }

    /* ================= 三阶：层级路径 ================= */

    /** 军令原例结构：Window 下一个 ListView，其内两个可点 Switch、第三个可点节点埋在更深一层。 */
    private fun settingsTree(): UiNode = ui(clazz = "android.view.Window", pkg = "com.android.settings", children = listOf(
        ui(id = "com.android.settings:id/list", clazz = "android.widget.ListView", scrollable = true, children = listOf(
            ui("WLAN", clazz = "android.widget.Switch", clickable = true),
            ui("蓝牙", clazz = "android.widget.Switch", clickable = true),
            ui("移动数据", clazz = "android.widget.LinearLayout", clickable = false, children = listOf(
                ui("更多", clazz = "android.widget.Button", clickable = true),
            )),
        )),
    ))

    @Test
    fun `L3 军令原例三段路径命中深层第三可点节点`() {
        val root = settingsTree()

        val hit = locator.locate(root, LocatorRequest(path = "Window>ListView>clickable=true[2]")).hit()
        assertEquals(LocatorLevel.PATH, hit.level)
        assertEquals("更多", hit.node.text)
        assertEquals("root/0/2/0", hit.nodeRef.indexPath)
        assertEquals("path='Window>ListView>clickable=true[2]'", hit.matchedBy)
        assertEquals(3, hit.attempts.last().candidateCount)
        assertTrue("last segment 'clickable=true[2]'" in hit.attempts.last().detail, hit.attempts.last().detail)
        assertTrue("hit 3, taking [2]" in hit.attempts.last().detail, hit.attempts.last().detail)
    }

    @Test
    fun `L3 段内无序号取第一个可点节点且不受 instance 影响`() {
        val root = settingsTree()

        assertEquals("WLAN", locator.locate(root, LocatorRequest(path = "Window>ListView>clickable=true")).hit().node.text)
        assertEquals("WLAN", locator.locate(root, LocatorRequest(path = "Window>ListView>clickable=true", instance = 2)).hit().node.text)
    }

    @Test
    fun `L3 段内序号越界精确到段`() {
        val root = settingsTree()

        val miss = locator.locate(root, LocatorRequest(path = "Window>ListView>clickable=true[7]")).miss()
        val attempt = miss.attempt(LocatorLevel.PATH)
        assertEquals(AttemptOutcome.INDEX_OUT_OF_RANGE, attempt.outcome)
        assertEquals(3, attempt.candidateCount)
        assertTrue("segment 3" in attempt.detail, attempt.detail)
    }

    @Test
    fun `L3 中间段落空时精确报告段号与扫描范围`() {
        val root = settingsTree()

        val miss = locator.locate(root, LocatorRequest(path = "Window>ScrollView>clickable=true")).miss()
        val attempt = miss.attempt(LocatorLevel.PATH)
        assertEquals(AttemptOutcome.NO_MATCH, attempt.outcome)
        assertTrue("segment 2" in attempt.detail, attempt.detail)
        assertTrue("'ScrollView'" in attempt.detail, attempt.detail)
        assertTrue("scope starts at ListView#list, 5 node(s)" in attempt.detail, attempt.detail)
    }

    @Test
    fun `L3 前段命中为叶子时后段无范围可扫`() {
        val root = settingsTree()

        val miss = locator.locate(root, LocatorRequest(path = "Window>ListView>clickable=true[2]>Button>TextView")).miss()
        val attempt = miss.attempt(LocatorLevel.PATH)
        assertEquals(AttemptOutcome.NO_MATCH, attempt.outcome)
        assertTrue("segment 4" in attempt.detail, attempt.detail)
        assertTrue("0 node(s)" in attempt.detail, attempt.detail)
    }

    @Test
    fun `L3 谓词组合：class 短名与全名、id entry、package 与 text 与 scrollable`() {
        val root = settingsTree()

        assertEquals("WLAN", locator.locate(root, LocatorRequest(path = "ListView>clickable=true&text=WLAN")).hit().node.text)
        assertEquals("WLAN", locator.locate(root, LocatorRequest(path = "android.widget.ListView>Switch[0]")).hit().node.text)
        assertEquals(
            "WLAN",
            locator.locate(root, LocatorRequest(path = "package=com.android.settings>id=list>clickable=true[0]")).hit().node.text,
        )
        assertEquals(
            "com.android.settings:id/list",
            locator.locate(root, LocatorRequest(path = "Window>id=com.android.settings:id/list")).hit().node.resourceId,
        )
        assertEquals(
            "WLAN",
            locator.locate(root, LocatorRequest(path = "Window>scrollable=true>Switch[0]")).hit().node.text,
        )
    }

    @Test
    fun `L3 后代深度可变，段在锚点子树内按层序就近取`() {
        val root = ui(clazz = "Window", children = listOf(
            ui("架子", clazz = "Frame", children = listOf(
                ui("浅按钮", clazz = "Button"),
                ui("再架", clazz = "Frame", children = listOf(ui("深按钮", clazz = "Button"))),
            )),
            ui("直接子按钮", clazz = "Button"),
        ))

        assertEquals("直接子按钮", locator.locate(root, LocatorRequest(path = "Window>Button")).hit().node.text)
        assertEquals("浅按钮", locator.locate(root, LocatorRequest(path = "Window>Button[1]")).hit().node.text)
        assertEquals("深按钮", locator.locate(root, LocatorRequest(path = "Window>Button[2]")).hit().node.text)
    }

    @Test
    fun `L3 通配段与空格分隔容错`() {
        val root = settingsTree()

        assertEquals("更多", locator.locate(root, LocatorRequest(path = "*>ListView>clickable=true[2]")).hit().node.text)
        assertEquals("更多", locator.locate(root, LocatorRequest(path = " Window > ListView > clickable=true[2] ")).hit().node.text)
    }

    @Test
    fun `L3 路径语法非法一律记 INVALID_QUERY 不猜`() {
        val root = settingsTree()
        val bad = listOf(
            "Window>ListView>unknown=1",
            "Window>ListView>clickable=maybe",
            "Window>>ListView",
            "Window>ListView]",
            "Window>ListView[a]",
            "Window>&text=x",
            "text=",
            "  ",
        )
        for (path in bad) {
            val miss = locator.locate(root, LocatorRequest(path = path)).miss()
            val attempt = miss.attempt(LocatorLevel.PATH)
            assertEquals(AttemptOutcome.INVALID_QUERY, attempt.outcome, "path=[$path]")
            assertTrue("invalid syntax" in attempt.detail, "path=[$path] -> ${attempt.detail}")
            assertEquals(path, attempt.query, "path=[$path]")
        }
    }

    @Test
    fun `L3 前两阶落空由路径兜住且三阶尝试链完整`() {
        val root = settingsTree()

        val hit = locator.locate(
            root,
            LocatorRequest(resourceId = "com.x:id/gone", text = "不存在", path = "Window>ListView>Switch[1]"),
        ).hit()
        assertEquals(LocatorLevel.PATH, hit.level)
        assertEquals("蓝牙", hit.node.text)
        assertEquals(listOf(LocatorLevel.RESOURCE_ID, LocatorLevel.TEXT, LocatorLevel.PATH), hit.attempts.map { it.level })
        assertEquals(listOf(AttemptOutcome.NO_MATCH, AttemptOutcome.NO_MATCH, AttemptOutcome.HIT), hit.attempts.map { it.outcome })
    }

    /* ================= miss 全记录 ================= */

    @Test
    fun `三阶线索齐备全空时逐级留痕`() {
        val root = settingsTree()

        val miss = locator.locate(
            root,
            LocatorRequest(
                resourceId = "com.x:id/gone",
                text = "飞行模式",
                contentDesc = "没有",
                path = "Window>RecyclerView>Button",
            ),
        ).miss()

        assertEquals(3, miss.attempts.size)
        assertEquals(listOf(1, 2, 3), miss.attempts.map { it.level.order })
        assertTrue(miss.attempts.all { it.outcome == AttemptOutcome.NO_MATCH })
        assertTrue(miss.attempts.all { it.query != null })
        assertTrue("L1 NO_MATCH" in miss.summary, miss.summary)
        assertTrue("L3 NO_MATCH" in miss.summary, miss.summary)
        assertEquals(LocatorMiss.NODE_NOT_FOUND, miss.code)
    }

    @Test
    fun `二阶落空后三阶语法非法，两阶记录都在 miss 里`() {
        val root = settingsTree()

        val miss = locator.locate(root, LocatorRequest(text = "不存在的文案", path = "Window>ListView>nope=1")).miss()
        assertEquals(listOf(1, 2, 3), miss.attempts.map { it.level.order })
        assertEquals(AttemptOutcome.NOT_ATTEMPTED, miss.attempt(LocatorLevel.RESOURCE_ID).outcome)
        assertEquals(AttemptOutcome.NO_MATCH, miss.attempt(LocatorLevel.TEXT).outcome)
        assertEquals(AttemptOutcome.INVALID_QUERY, miss.attempt(LocatorLevel.PATH).outcome)
    }

    @Test
    fun `根节点为空时三阶一律 NOT_ATTEMPTED`() {
        val miss = locator.locate(null, LocatorRequest(resourceId = "com.x:id/item", text = "WLAN", path = "Window")).miss()

        assertEquals(3, miss.attempts.size)
        assertTrue(miss.attempts.all { it.outcome == AttemptOutcome.NOT_ATTEMPTED })
        assertTrue(miss.attempts.all { "root is null" in it.detail })
    }

    @Test
    fun `无任何线索的请求产出三阶 NOT_ATTEMPTED 而非猜测`() {
        val root = settingsTree()

        val miss = locator.locate(root, LocatorRequest()).miss()
        assertEquals(3, miss.attempts.size)
        assertTrue(miss.attempts.all { it.outcome == AttemptOutcome.NOT_ATTEMPTED })
        assertTrue(miss.attempts.all { it.query == null })
    }
}
