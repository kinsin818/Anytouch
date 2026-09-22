package com.anytouch.app.recorder

import com.anytouch.app.locator.LocatorHit
import com.anytouch.app.locator.LocatorRequest
import com.anytouch.app.locator.NodeTreeLocator
import com.anytouch.app.locator.TestUi
import com.anytouch.app.locator.ui
import com.anytouch.contracts.Action
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

/**
 * 军令 L2-9：编辑操作为纯列表函数——步骤间零耦合的机械化证明：
 * 删第 2 步后，原第 5 步逐字段原样、且对活树仍可定位回放。
 */
class RecorderEditingTest {

    private val compiler = RecorderCompiler()

    /** 五叶树：每叶唯一 resource-id，快照带 id → 步骤间只可能共享树结构，不共享状态。 */
    private fun fiveRowTree(): TestUi = ui(
        clazz = "android.view.Window",
        children = listOf(
            ui(marker = "r1", id = "com.x:id/row1", clazz = "android.widget.Button"),
            ui(marker = "r2", id = "com.x:id/row2", clazz = "android.widget.Button"),
            ui(marker = "r3", id = "com.x:id/row3", clazz = "android.widget.Button"),
            ui(marker = "r4", id = "com.x:id/row4", clazz = "android.widget.Button"),
            ui(marker = "r5", id = "com.x:id/row5", clazz = "android.widget.Button"),
        ),
    )

    /** 固定树实例：NodeTreeLocator 以对象恒等求路径，逐次重建会断掉恒等。 */
    private val root: TestUi = fiveRowTree()

    private fun fiveSteps(): List<Action> {
        val events = buildList<RecEvent> {
            add(WindowChanged(TARGET_PKG, timestampMs = 0))
            for (k in 0 until 5) add(clickAt(root, 100L + k * 1000, listOf(k)))
        }
        return compiler.compile(events, TARGET_PKG).actions.also { assertEquals(5, it.size) }
    }

    @Test
    fun `L2-9 删第2步后原第5步逐字段原样且活树仍可定位命中`() {
        val steps = fiveSteps()
        val originalStep5 = steps[4]

        val edited = removeStep(steps, index = 1)

        assertEquals(4, edited.size)
        assertEquals(originalStep5, edited[3], "删步不重编号、不改写其他步骤任何字段")
        assertSame(steps[4], edited[3], "纯函数：未触碰的元素按引用透传")
        // "可回放"的实证：解码定位线索，对活树跑冻结的 NodeTreeLocator，仍命中 row5 本尊
        val clue = edited[3].value!!.str(RecorderCompiler.RESOURCE_ID_KEY)!!
        val hit = NodeTreeLocator().locate(root, LocatorRequest(resourceId = clue)) as LocatorHit
        assertEquals("r5", hit.node.text)
        assertEquals("root/4", hit.nodeRef.indexPath)
        // 输入列表未被就地修改（纯函数验证）
        assertEquals(5, steps.size)
    }

    @Test
    fun `L2-9 改名与移序为纯列表函数，其余步骤逐条不动`() {
        val steps = fiveSteps()

        val renamed = renameStep(steps, index = 2, newActionId = "manual-edit-9")
        assertEquals("manual-edit-9", renamed[2].actionId)
        assertEquals(steps.filterIndexed { i, _ -> i != 2 }, renamed.filterIndexed { i, _ -> i != 2 })
        assertEquals(5, steps.size, "rename 不删不移")

        val moved = moveStep(steps, from = 0, to = 3)
        assertEquals(listOf(steps[1], steps[2], steps[3], steps[0], steps[4]), moved)
        assertEquals(steps.toSet(), moved.toSet(), "移序只动位置不动内容（actionId 钉死源事件下标）")
        assertEquals(5, steps.size)

        val identity = moveStep(steps, from = 2, to = 2)
        assertEquals(steps, identity)
    }

    @Test
    fun `L2-9 编辑原语越界与空名一律拒绝，不静默吞`() {
        val steps = fiveSteps()
        assertFailsWith<IllegalArgumentException> { removeStep(steps, 5) }
        assertFailsWith<IllegalArgumentException> { removeStep(steps, -1) }
        assertFailsWith<IllegalArgumentException> { renameStep(steps, 0, "  ") }
        assertFailsWith<IllegalArgumentException> { moveStep(steps, 0, 9) }
        assertFailsWith<IllegalArgumentException> { moveStep(emptyList(), 0, 0) }
    }

    @Test
    fun `L2-9 编辑后的列表重新序列化往返仍等值（删步不产脏 JSON）`() {
        val edited = removeStep(fiveSteps(), 0)
        assertEquals(edited, decodeActions(encodeActions(edited)))
    }
}
