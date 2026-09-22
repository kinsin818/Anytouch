package com.anytouch.app.recorder

import com.anytouch.app.locator.LocatorHit
import com.anytouch.app.locator.LocatorRequest
import com.anytouch.app.locator.NodeTreeLocator
import com.anytouch.app.locator.ui
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * 主窗裁决锁定用例（结案后补案，Order: 质询处置 2026-09-22）。
 *
 * 锁定两条已生效裁决，防止后续重构悄悄翻案：
 * 1) 空白 resourceId 按缺失落阶（军令 L2-5 读作"非空白 resourceId 独占"）；
 * 2) 层级路径词汇 = AnyNode 链 `*[i0+1]>*[i1]>…`，className 段形态作废（路径不嵌入任何字符串）。
 */
class RecorderAdjudicationLockTest {

    private val compiler = RecorderCompiler()

    private fun valueOf(snapshot: NodeSnapshot) = compiler
        .compile(
            listOf(
                WindowChanged(TARGET_PKG, timestampMs = 0),
                NodeAction(NodeActionKind.CLICK, snapshot, timestampMs = 1, confirmed = true),
            ),
            TARGET_PKG,
        )
        .actions.single().value!!

    /* ---- 裁决 1：空白 resourceId 按缺失 ---- */

    @Test
    fun `裁决1锁定 空白resourceId不进一阶 按缺失落text或路径`() {
        val blankIdWithText = valueOf(
            NodeSnapshot(resourceId = " \t ", text = " 确定 ", pkg = TARGET_PKG, indexPath = listOf(0)),
        )
        assertEquals(
            setOf(RecorderCompiler.TEXT_KEY),
            blankIdWithText.keys,
            "空白 id 视同缺失（军令字面'有 resourceId 只用 resourceId'不适用于空白串）",
        )
        assertEquals("确定", blankIdWithText.str(RecorderCompiler.TEXT_KEY))

        val blankIdNoClues = valueOf(
            NodeSnapshot(resourceId = "   ", text = "", contentDesc = null, pkg = TARGET_PKG, indexPath = listOf(1)),
        )
        assertEquals(setOf(RecorderCompiler.PATH_KEY), blankIdNoClues.keys, "空白 id + 无文本 → 落三阶路径")
    }

    @Test
    fun `裁决1锁定 非空白resourceId独占且产物永不携带空白串id`() {
        val v = valueOf(
            NodeSnapshot(
                resourceId = "com.x:id/keep",
                text = "有文本也不给",
                contentDesc = "有desc也不给",
                pkg = TARGET_PKG,
                indexPath = listOf(0, 1),
            ),
        )
        assertEquals(setOf(RecorderCompiler.RESOURCE_ID_KEY), v.keys)
        assertEquals("com.x:id/keep", v.str(RecorderCompiler.RESOURCE_ID_KEY))

        // 反向保险：混合事件流批量编译后，任何步骤的 resource_id 值必非空白——
        // 空白串直传会让 S1 定位器记 INVALID_QUERY 停在 L1 不降级，此为该回放毒药的唯一入口封堵。
        val mixed = listOf(
            NodeSnapshot(resourceId = "  ", text = "甲", pkg = TARGET_PKG, indexPath = listOf(0)),
            NodeSnapshot(resourceId = "\n\t", pkg = TARGET_PKG, indexPath = listOf(1)),
            NodeSnapshot(resourceId = "com.x:id/z", pkg = TARGET_PKG, indexPath = listOf(2)),
        ).map { NodeAction(NodeActionKind.CLICK, it, timestampMs = 10, confirmed = true) }
        val actions = compiler.compile(listOf(WindowChanged(TARGET_PKG, timestampMs = 0)) + mixed, TARGET_PKG).actions
        assertEquals(3, actions.size)
        assertTrue(
            actions.mapNotNull { it.value?.str(RecorderCompiler.RESOURCE_ID_KEY) }.all { it.isNotBlank() },
            "凡进一阶词汇的 resource_id 必须非空白",
        )
    }

    /* ---- 裁决 2：AnyNode 路径链形态 ---- */

    private val anyNodeChain = Regex("""^\*\[\d+](>\*\[\d+])*$""")

    @Test
    fun `裁决2锁定 路径形态为首段加一后续等子下标的AnyNode链 不嵌入className`() {
        val root = ui(
            clazz = "android.view.Window",
            children = listOf(
                ui(marker = "s0", clazz = "android.widget.TextView"),
                ui(marker = "s1", clazz = "android.widget.TextView"),
                ui(marker = "s2", clazz = "android.widget.TextView"),
                ui(
                    marker = "s3",
                    clazz = "android.widget.ListView",
                    children = listOf(
                        ui(marker = "c0", clazz = "android.widget.TextView"),
                        ui(
                            marker = "c1",
                            clazz = "android.widget.FrameLayout",
                            children = listOf(
                                ui(clazz = "android.widget.Button"),
                                ui(clazz = "android.widget.CheckBox"),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val cases: List<Pair<List<Int>, String>> = listOf(
            emptyList<Int>() to "*[0]", // 根自身：候选集首元即 root
            listOf(0) to "*[1]", // 首段 = 子下标+1（BFS 含 root）
            listOf(3) to "*[4]",
            listOf(3, 1, 1) to "*[4]>*[1]>*[1]", // 首段之后 rank == 子下标
        )
        for (testCase in cases) {
            val indexPath: List<Int> = testCase.first
            val expected: String = testCase.second
            val path = valueOf(
                NodeSnapshot(className = "android.widget.Switch", pkg = TARGET_PKG, indexPath = indexPath),
            ).str(RecorderCompiler.PATH_KEY)!!
            assertEquals(expected, path, "indexPath=$indexPath")
            assertTrue(anyNodeChain.matches(path), "路径必须保持纯 AnyNode 链形态：$path")
            assertTrue("Switch" !in path, "className 段形态已作废，任何类名不得嵌入路径串：$path")
            // 裁决的实质承诺：该形态对 NodeTreeLocator 无损可回放
            val located = NodeTreeLocator().locate(root, LocatorRequest(path = path))
            assertSame(root.at(*indexPath.toIntArray()), (located as LocatorHit).node, "path=$path")
        }
    }
}
