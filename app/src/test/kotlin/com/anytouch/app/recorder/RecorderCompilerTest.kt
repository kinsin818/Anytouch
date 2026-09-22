package com.anytouch.app.recorder

import com.anytouch.app.executor.NodeTaskRunner
import com.anytouch.app.locator.LocatorHit
import com.anytouch.app.locator.LocatorLevel
import com.anytouch.app.locator.LocatorMiss
import com.anytouch.app.locator.LocatorRequest
import com.anytouch.app.locator.NodeTreeLocator
import com.anytouch.app.locator.PathPatternParser
import com.anytouch.app.locator.SegmentPredicate
import com.anytouch.app.locator.TestUi
import com.anytouch.app.locator.UiNode
import com.anytouch.app.locator.ui
import com.anytouch.app.platform.NodeActions
import com.anytouch.contracts.Action
import com.anytouch.contracts.ActionSafety
import com.anytouch.contracts.ActionSource
import com.anytouch.contracts.ActionType
import com.anytouch.contracts.ContractJson
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * 军令 ANYTOUCH-S2-FRAMEWORK-20260922 L2 十条的验收用例集（编译转换部分）。
 * 全部 JVM 直跑：输入 RecEvent、输出 contracts Action，零 Android。
 */
class RecorderCompilerTest {

    private val compiler = RecorderCompiler()

    private val windowOpen = WindowChanged(TARGET_PKG, "MainActivity", timestampMs = 0)

    private fun clickSnapshot(pkg: String = OTHER_PKG, ts: Long = 10): NodeAction = NodeAction(
        kind = NodeActionKind.CLICK,
        snapshot = NodeSnapshot(resourceId = "com.x:id/gone", pkg = pkg, indexPath = listOf(0)),
        timestampMs = ts,
        confirmed = true,
    )

    /* ============ L2-1 targetPkg 过滤 ============ */

    @Test
    fun `L2-1 只保留目标包且窗口在目标包的动作，跨包丢弃并计数归因`() {
        val tree = tree()
        val events = listOf(
            windowOpen,
            clickAt(tree, 10, listOf(0)),
            otherPkgClick(snapshotOf(tree, listOf(1))),
            WindowChanged(OTHER_PKG, timestampMs = 20),
            clickAt(tree, 30, listOf(1)), // snapshot 是目标包，但当前窗口已漂走
            clickSnapshot(),
        )

        val out = compiler.compile(events, TARGET_PKG)

        assertEquals(1, out.actions.size, "仅第一条 CLICK 存活")
        assertEquals(3, out.drops.size)
        assertEquals(2, out.droppedCount(DropReason.CROSS_PACKAGE))
        assertEquals(1, out.droppedCount(DropReason.WINDOW_CONTEXT_MISMATCH))
        assertEquals(0, out.droppedCount(DropReason.SAFETY_UNCONFIRMED))
        // 归因可追溯：eventIndex 指回输入下标
        assertEquals(listOf(2, 4, 5), out.drops.map { it.eventIndex })
        assertEquals(ReceiptFailureClass.OTHER, out.drops.first { it.reason == DropReason.CROSS_PACKAGE }.receiptClass)
        assertEquals(
            ReceiptFailureClass.PAGE_DRIFT,
            out.drops.first { it.reason == DropReason.WINDOW_CONTEXT_MISMATCH }.receiptClass,
        )
    }

    /* ============ L2-2 SET_TEXT 保真 ============ */

    @Test
    fun `L2-2 SET_TEXT 原文不 trim 不转义丢失，JSON 往返后全等`() {
        val raw = "  两空格 换行\n制表\t 引号\"反斜杠\\尖括号<>与冒号: 尾巴😀  "
        val snap = NodeSnapshot(resourceId = "com.x:id/remark", pkg = TARGET_PKG, indexPath = listOf(1))
        val events = listOf(
            windowOpen,
            NodeAction(NodeActionKind.SET_TEXT, snap, text = raw, timestampMs = 5, confirmed = true),
        )

        val out = compiler.compile(events, TARGET_PKG)
        val action = out.actions.single()
        assertEquals(raw, action.value!!.str(RecorderCompiler.INPUT_KEY))

        // 序列化往返后仍全等（契约 Json 配置）
        val revived = decodeActions(encodeActions(out.actions)).single()
        assertEquals(action, revived)
        assertEquals(raw, revived.value!!.str(RecorderCompiler.INPUT_KEY))
        // 逐字节等值（排除 JsonPrimitive 内容再丢失）
        assertEquals(
            ContractJson.instance.encodeToString(ListSerializer(Action.serializer()), out.actions),
            encodeActions(listOf(revived)),
        )
    }

    /* ============ L2-3 默认拒绝 ============ */

    @Test
    fun `L2-3 confirmed=false 不产步骤，产 DroppedBySafety 记录（fail-closed）`() {
        val events = listOf(
            windowOpen,
            clickAt(tree(), 10, listOf(1), confirmed = false),
            NodeAction(
                kind = NodeActionKind.SET_TEXT,
                snapshot = snapshotOf(tree(), listOf(1)),
                text = "1",
                timestampMs = 20,
                confirmed = false,
            ),
        )

        val out = compiler.compile(events, TARGET_PKG)

        assertTrue(out.actions.isEmpty(), "未确认动作不得进步骤队列")
        assertEquals(2, out.drops.size)
        val first = out.drops[0]
        assertSame(DropReason.SAFETY_UNCONFIRMED, first.reason)
        assertEquals(DroppedBySafety(eventIndex = 1, timestampMs = 10, detail = first.detail), first)
        assertEquals(ReceiptFailureClass.PERMISSION_INTERRUPTED, first.receiptClass)
        assertEquals(
            ReceiptFailureClass.PERMISSION_INTERRUPTED,
            out.drops[1].receiptClass,
            "SET_TEXT 未确认同样默认拒绝（安全归因优先于半步判定）",
        )
    }

    /* ============ L2-4 去抖 ============ */

    @Test
    fun `L2-4 同 indexPath 连续 CLICK 小于300ms 合并为一步，merged 计数`() {
        val tree = tree()
        val events = listOf(
            windowOpen,
            clickAt(tree, 1000, listOf(2, 1)),
            clickAt(tree, 1100, listOf(2, 1)),
            clickAt(tree, 1200, listOf(2, 1)),
            clickAt(tree, 1299, listOf(2, 1)),
        )

        val out = compiler.compile(events, TARGET_PKG)

        assertEquals(1, out.actions.size)
        assertEquals(3, out.merged)
        assertEquals("rec-0001", out.actions.single().actionId, "合并保留首次出现")
    }

    @Test
    fun `L2-4 去抖边界 恰好300ms不合并，299ms合并`() {
        val tree = tree()
        val exact = compiler.compile(
            listOf(windowOpen, clickAt(tree, 0, listOf(0)), clickAt(tree, 300, listOf(0))),
            TARGET_PKG,
        )
        assertEquals(2, exact.actions.size, "间隔==debounceMs 不算小于，不合并")
        assertEquals(0, exact.merged)

        val inside = compiler.compile(
            listOf(windowOpen, clickAt(tree, 0, listOf(0)), clickAt(tree, 299, listOf(0))),
            TARGET_PKG,
        )
        assertEquals(1, inside.actions.size)
        assertEquals(1, inside.merged)
    }

    @Test
    fun `L2-4 合并判定用时间戳与索引路径，异路径或被其他动作打断不合并`() {
        val tree = tree()
        // 异路径夹心：A A B A —— 最后一个 A 距首个 A 已超链，不并入
        val sandwich = compiler.compile(
            listOf(
                windowOpen,
                clickAt(tree, 100, listOf(0)),
                clickAt(tree, 150, listOf(0)),
                clickAt(tree, 200, listOf(1)),
                clickAt(tree, 250, listOf(0)),
            ),
            TARGET_PKG,
        )
        assertEquals(3, sandwich.actions.size)
        assertEquals(1, sandwich.merged)
        // 时间倒序也要防负数穿透：后发时间戳更早视为独立点击（不满足 0<=间隔<300）
        val rewound = compiler.compile(
            listOf(windowOpen, clickAt(tree, 500, listOf(0)), clickAt(tree, 300, listOf(0))),
            TARGET_PKG,
        )
        assertEquals(2, rewound.actions.size)

        // 被 SET_TEXT 打断：CLICK ts=100,150 相邻，但 SET_TEXT ts=120 夹在中间时不并
        val interrupted = compiler.compile(
            listOf(
                windowOpen,
                clickAt(tree, 100, listOf(0)),
                NodeAction(
                    kind = NodeActionKind.SET_TEXT,
                    snapshot = snapshotOf(tree, listOf(2, 0)),
                    text = "x",
                    timestampMs = 120,
                    confirmed = true,
                ),
                clickAt(tree, 150, listOf(0)),
            ),
            TARGET_PKG,
        )
        assertEquals(3, interrupted.actions.size, "两步 CLICK + 一步 SET_TEXT，无合并")
        assertEquals(0, interrupted.merged)
    }

    /* ============ L2-5 词汇优先级 ============ */

    @Test
    fun `L2-5 有 resourceId 时步骤 value 只用 resource_id，其余词汇一概不进`() {
        val snap = NodeSnapshot(
            resourceId = "com.x:id/wifi",
            text = "WLAN",
            contentDesc = "无线局域网",
            className = "android.widget.Switch",
            pkg = TARGET_PKG,
            indexPath = listOf(0, 1),
        )

        val action = compiler.compile(listOf(windowOpen, NodeAction(NodeActionKind.CLICK, snap, timestampMs = 1, confirmed = true)), TARGET_PKG)
            .actions.single()

        assertEquals(setOf(RecorderCompiler.RESOURCE_ID_KEY), action.value!!.keys)
        assertEquals("com.x:id/wifi", action.value!!.str(RecorderCompiler.RESOURCE_ID_KEY))
    }

    @Test
    fun `L2-5 无 id（含空白 id）用 trim 全等 text 与 desc，仍无才落层级路径`() {
        val withTextAndDesc = NodeSnapshot(
            resourceId = "   ", // 空白 id：S1 定位器会记 INVALID_QUERY 并停在 L1，直传反造回放失败 → 按缺失处理
            text = "  保存  ",
            contentDesc = "提交按钮",
            pkg = TARGET_PKG,
            indexPath = listOf(2),
        )
        val v1 = compiler.compile(listOf(windowOpen, NodeAction(NodeActionKind.CLICK, withTextAndDesc, timestampMs = 1, confirmed = true)), TARGET_PKG)
            .actions.single().value!!
        assertEquals(setOf(RecorderCompiler.TEXT_KEY, RecorderCompiler.CONTENT_DESC_KEY), v1.keys)
        assertEquals("保存", v1.str(RecorderCompiler.TEXT_KEY), "二阶词汇与 S1 一致：trim 后全等")
        assertEquals("提交按钮", v1.str(RecorderCompiler.CONTENT_DESC_KEY))

        val onlyBlank = NodeSnapshot(text = " \t", contentDesc = "  ", pkg = TARGET_PKG, indexPath = listOf(0))
        val v2 = compiler.compile(listOf(windowOpen, NodeAction(NodeActionKind.CLICK, onlyBlank, timestampMs = 1, confirmed = true)), TARGET_PKG)
            .actions.single().value!!
        assertEquals(setOf(RecorderCompiler.PATH_KEY), v2.keys, "text/desc 全空白 → 视同无线索，落路径")
    }

    @Test
    fun `L2-5 仅路径词汇：产物路径串可被 PathPatternParser 解析且 NodeTreeLocator 回放命中同一节点`() {
        val tree = tree()
        val bareNode = tree.at(2, 1) // 无任何 id/text/desc 的深层裸节点
        val snap = snapshotOf(tree, listOf(2, 1))
        assertEquals(null, snap.resourceId)
        assertEquals(null, snap.text)
        assertEquals(null, snap.contentDesc)

        val action = compiler.compile(listOf(windowOpen, NodeAction(NodeActionKind.CLICK, snap, timestampMs = 1, confirmed = true)), TARGET_PKG)
            .actions.single()

        val path = action.value!!.str(RecorderCompiler.PATH_KEY)!!
        assertEquals("*[3]>*[1]", path, "首段候选集含 root 自身故子下标+1，后续段 rank==子下标")

        // 语法兼容：段结构、AnyNode 谓词、[n] 序号
        val segments = PathPatternParser.parse(path)
        assertEquals(2, segments.size)
        assertEquals(listOf(3, 1), segments.map { it.index })
        assertTrue(segments.all { it.predicates.single() is SegmentPredicate.AnyNode })

        // 语义往返：三阶定位真实命中裸节点对象本身（非构造树可证的伪往返）
        val located = NodeTreeLocator().locate(tree, LocatorRequest(path = path))
        val hit = located as LocatorHit
        assertEquals(LocatorLevel.PATH, hit.level)
        assertSame(bareNode, hit.node)

        // 根节点自身（空 indexPath）与单层多兄弟（子下标 3）两种边界
        val rootSnap = snapshotOf(tree, emptyList())
        val rootPath = compiler.compile(listOf(windowOpen, NodeAction(NodeActionKind.CLICK, rootSnap, timestampMs = 1, confirmed = true)), TARGET_PKG)
            .actions.single().value!!.str(RecorderCompiler.PATH_KEY)!!
        assertEquals("*[0]", rootPath)
        assertSame(tree, (NodeTreeLocator().locate(tree, LocatorRequest(path = rootPath)) as LocatorHit).node)
    }

    /* ============ L2-6 输出即契约 ============ */

    @Test
    fun `L2-6 产物为 contracts Action 列表 JSON，decode 回读等值且键名符合执行器解码口径`() {
        val tree = tree()
        val events = listOf(
            windowOpen,
            clickAt(tree, 10, listOf(0)),
            NodeAction(NodeActionKind.SET_TEXT, snapshotOf(tree, listOf(2, 0)), text = "蓝牙", timestampMs = 20, confirmed = true),
            NodeAction(NodeActionKind.SCROLL_FORWARD, snapshotOf(tree, listOf(2)), timestampMs = 30, confirmed = true),
            NodeAction(NodeActionKind.SCROLL_BACKWARD, snapshotOf(tree, listOf(2)), timestampMs = 40, confirmed = true),
            NodeAction(NodeActionKind.SET_TEXT, snapshotOf(tree, listOf(1)), text = "改", timestampMs = 50, confirmed = true),
        )

        val out = compiler.compile(events, TARGET_PKG)
        assertEquals(encodeActions(out.actions), encodeActions(decodeActions(encodeActions(out.actions))))
        assertEquals(out.actions, decodeActions(encodeActions(out.actions)))

        val decoded = decodeActions(encodeActions(out.actions))
        assertEquals(listOf(ActionType.CLICK, ActionType.TYPE_TEXT, ActionType.SCROLL, ActionType.SCROLL, ActionType.TYPE_TEXT), decoded.map { it.type })
        assertTrue(decoded.all { it.source == ActionSource.NODE }, "定位链来源一律 node")
        assertEquals("forward", decoded[2].value!!.str("direction"))
        assertEquals("backward", decoded[3].value!!.str("direction"))
        assertEquals("蓝牙", decoded[1].value!!.str("input"))
        // 三门禁与 S1 执行器兼容：缺省全 false 不可执行，录制步骤显式放行 click 门
        assertEquals(ActionSafety(viewportOk = true, clickEnabled = true, requiresTransition = false), out.actions.single { it.type == ActionType.CLICK }.safety)
        assertTrue(out.actions.all { it.target == null }, "坐标零进入：target 恒 null")
        assertEquals(listOf("rec-0001", "rec-0002", "rec-0003", "rec-0004", "rec-0005"), out.actions.map { it.actionId })
    }

    /* ============ L2-7 空与全丢弃 ============ */

    @Test
    fun `L2-7 空事件流产出空步骤与零归因，不抛异常`() {
        val out = compiler.compile(emptyList(), TARGET_PKG)
        assertEquals(emptyList(), out.actions)
        assertEquals(emptyList(), out.drops)
        assertEquals(0, out.merged)
    }

    @Test
    fun `L2-7 全丢弃时零步骤但归因完整，半步与非法路径也在此族`() {
        val tree = tree()
        val events = listOf(
            windowOpen,
            clickSnapshot(), // 目标包窗口下混入别家动作
            NodeAction(NodeActionKind.SET_TEXT, snapshotOf(tree, listOf(1)), text = null, timestampMs = 3, confirmed = true),
            clickAt(tree, 4, listOf(0), confirmed = false),
            NodeAction(
                kind = NodeActionKind.SCROLL_FORWARD,
                snapshot = NodeSnapshot(pkg = TARGET_PKG, indexPath = listOf(-1)),
                timestampMs = 5,
                confirmed = true,
            ),
        )

        val out = compiler.compile(events, TARGET_PKG)

        assertEquals(0, out.actions.size, "不产半步、不产脏步")
        assertEquals(4, out.drops.size)
        assertEquals(1, out.droppedCount(DropReason.CROSS_PACKAGE))
        assertEquals(1, out.droppedCount(DropReason.HALF_STEP), "SET_TEXT 无输入文本")
        assertEquals(1, out.droppedCount(DropReason.SAFETY_UNCONFIRMED))
        assertEquals(1, out.droppedCount(DropReason.INVALID_INDEX_PATH))
        assertEquals(ReceiptFailureClass.NODE_NOT_FOUND, out.drops.last().receiptClass)
        assertEquals(listOf(1, 2, 3, 4), out.drops.map { it.eventIndex })
    }

    /* ============ L2-8 未知子类型 ============ */

    @Test
    fun `L2-8 未知事件子类型丢弃并计数，不崩不产步骤`() {
        val tree = tree()
        val events = listOf(
            windowOpen,
            NodeAction(NodeActionKind.UNKNOWN, snapshotOf(tree, listOf(0)), timestampMs = 1, confirmed = true),
            clickAt(tree, 2, listOf(0)),
        )

        val out = compiler.compile(events, TARGET_PKG)

        assertEquals(1, out.actions.size, "未知子类型不吞掉后续正常事件")
        assertEquals(1, out.drops.size)
        val drop = out.drops.single()
        assertEquals(DropReason.UNSUPPORTED_KIND, drop.reason)
        assertEquals(1, drop.eventIndex)
        assertEquals(ReceiptFailureClass.OTHER, drop.receiptClass)
    }

    /* ============ L2-10 回执 6 类映射 ============ */

    @Test
    fun `L2-10 丢弃原因枚举 total 映射到 STAGE-02 回执 6 类，稳定码与冻结文件对齐`() {
        assertEquals(6, ReceiptFailureClass.entries.size, "回执 6 类：未找到节点/页面漂移/弹窗/权限中断/超时/其他")
        assertEquals(
            setOf("NODE_NOT_FOUND", "PAGE_DRIFT", "POPUP_INTERCEPTED", "PERMISSION_INTERRUPTED", "TIMEOUT", "OTHER"),
            ReceiptFailureClass.entries.map { it.code }.toSet(),
        )
        // 与 S1 冻结文件 LocatorMiss.NODE_NOT_FOUND 稳定码同值（归因链路不断）
        assertEquals(LocatorMiss.NODE_NOT_FOUND, ReceiptFailureClass.NODE_NOT_FOUND.code)

        val expected = mapOf(
            DropReason.CROSS_PACKAGE to ReceiptFailureClass.OTHER,
            DropReason.WINDOW_CONTEXT_MISMATCH to ReceiptFailureClass.PAGE_DRIFT,
            DropReason.UNSUPPORTED_KIND to ReceiptFailureClass.OTHER,
            DropReason.SAFETY_UNCONFIRMED to ReceiptFailureClass.PERMISSION_INTERRUPTED,
            DropReason.HALF_STEP to ReceiptFailureClass.OTHER,
            DropReason.INVALID_INDEX_PATH to ReceiptFailureClass.NODE_NOT_FOUND,
        )
        assertEquals(expected, DropReason.entries.associateWith { it.receiptClass }, "原因枚举必须全量在册")
    }

    /* ============ L1 坐标禁入 ============ */

    @Test
    fun `L1 输入结构与产物零坐标字段`() {
        val forbidden = setOf(
            "x", "y", "bounds", "point", "coordinate", "coordinates",
            "getx", "gety", "getbounds", "getpoint", "getcoordinate", "getcenter",
        )
        val classes = listOf(
            RecEvent::class.java,
            WindowChanged::class.java,
            NodeAction::class.java,
            NodeSnapshot::class.java,
            Action::class.java, // 产物同查：坐标只可能经 target 进入，而 target 必须恒 null（L2-6 用例断言）
        )
        for (clazz in classes) {
            for (field in clazz.declaredFields) {
                assertTrue(field.name.lowercase() !in forbidden, "${clazz.simpleName}.${field.name} 是坐标字段")
            }
            for (method in clazz.declaredMethods) {
                assertTrue(method.name.lowercase() !in forbidden, "${clazz.simpleName}.${method.name}() 是坐标访问器")
            }
        }
    }

    /* ============ 与 S1 执行器的真实回放（金链） ============ */

    @Test
    fun `金链 compile到encode到decode到NodeTaskRunner 全绿且SET_TEXT原文送达设备`() = runBlocking {
        val tree = ui(
            clazz = "android.view.Window",
            children = listOf(
                ui(
                    id = "com.x:id/list",
                    clazz = "android.widget.ListView",
                    scrollable = true,
                    children = listOf(
                        ui(marker = "WLAN", id = "com.x:id/wlan_switch", clazz = "android.widget.Switch", clickable = true),
                        ui(marker = "蓝牙", id = "com.x:id/bt_switch", clazz = "android.widget.Switch", clickable = true),
                    ),
                ),
                ui(id = "com.x:id/remark", clazz = "android.widget.EditText"),
            ),
        )
        val raw = "  备注：保留 空白\n与\"引号\"😀  "
        val events = listOf(
            WindowChanged(TARGET_PKG, timestampMs = 0),
            clickAt(tree, 10, listOf(0, 0)),
            NodeAction(NodeActionKind.SET_TEXT, snapshotOf(tree, listOf(1)), text = raw, timestampMs = 20, confirmed = true),
            NodeAction(NodeActionKind.SCROLL_FORWARD, snapshotOf(tree, listOf(0)), timestampMs = 30, confirmed = true),
            otherPkgClick(snapshotOf(tree, listOf(0, 1))),
            clickAt(tree, 40, listOf(0, 1)),
            NodeAction(NodeActionKind.SCROLL_BACKWARD, snapshotOf(tree, listOf(0)), timestampMs = 50, confirmed = true),
        )

        val out = compiler.compile(events, TARGET_PKG)
        assertEquals(5, out.actions.size)
        assertEquals(1, out.droppedCount(DropReason.CROSS_PACKAGE))

        val device = FakeRecorderDevice(tree)
        val report = NodeTaskRunner(
            device = device,
            locateTimeoutMs = 0,
            locatePollMs = 10,
            settleMs = 0,
        ).run(decodeActions(encodeActions(out.actions)))

        assertEquals(5, report.results.size)
        assertTrue(report.results.all { it.ok }, "执行器回执必须全绿: ${report.results.map { it.status to it.recovery?.code }}")
        assertEquals(false, report.stopped)
        assertEquals(
            listOf("click:WLAN", "setText:$raw", "scroll:forward", "click:蓝牙", "scroll:backward"),
            device.performed,
            "顺序、保真、方向与录制事件一致",
        )
    }

    private class FakeRecorderDevice(private val root: UiNode) : NodeActions {
        val performed = mutableListOf<String>()

        override suspend fun root(): UiNode? = root

        override fun click(node: UiNode): Boolean {
            performed += "click:${node.text}"
            return true
        }

        override fun scroll(node: UiNode, forward: Boolean): Boolean {
            performed += "scroll:${if (forward) "forward" else "backward"}"
            return true
        }

        override fun setText(node: UiNode, text: String): Boolean {
            performed += "setText:$text"
            (node as? TestUi)?.text = text // 字落进树，配合执行器的落字复核（真机实测：只回 true 会被判虚报）
            return true
        }
    }
}
