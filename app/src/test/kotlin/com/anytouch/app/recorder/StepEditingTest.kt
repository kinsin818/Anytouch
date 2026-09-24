package com.anytouch.app.recorder

import com.anytouch.app.platform.COMPILE_HOLD_HEADLINE
import com.anytouch.app.platform.RecordGate
import com.anytouch.app.platform.userCopy
import com.anytouch.contracts.Action
import com.anytouch.contracts.ActionSafety
import com.anytouch.contracts.ActionSource
import com.anytouch.contracts.ActionType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 步骤编辑门禁（UI 面 fail-closed）+ 步序账人读标题的纯函数锁。
 * 判据必须严格到"READY 时冻结原语绝不抛、非 READY 时原语必抛"：
 * 只测"能拒"不够，拒得比原语松就是漏网——越界请求会绕过门禁把 `require` 崩到 UI 线程（无痕丢失）。
 */
class StepEditingTest {

    private fun act(
        type: String,
        id: String = "a",
        vararg clues: Pair<String, String>,
    ) = Action(
        actionId = id,
        type = type,
        source = ActionSource.NODE,
        value = buildJsonObject { clues.forEach { (k, v) -> put(k, v) } },
        safety = ActionSafety(viewportOk = true, clickEnabled = true),
    )

    /** 四步点击链：id 唯一、线索各异，够覆盖首/中/尾三种下标。 */
    private fun steps(count: Int = 4) = (1..count).map { i ->
        act(ActionType.CLICK, "s$i", RecorderCompiler.TEXT_KEY to "行$i")
    }

    // ---------- READY ----------
    @Test
    fun `三操作在合法下标与非空名时 READY`() {
        val s = steps()
        assertEquals(StepEditGate.READY, stepEditGateOf(s, StepEdit.Remove(0)))
        assertEquals(StepEditGate.READY, stepEditGateOf(s, StepEdit.Remove(s.size - 1)))
        assertEquals(StepEditGate.READY, stepEditGateOf(s, StepEdit.Rename(2, " 带空格 ")))
        assertEquals(StepEditGate.READY, stepEditGateOf(s, StepEdit.Move(0, s.size - 1)))
    }

    // ---------- 空账 ----------
    @Test
    fun `空步序账三操作一律拒为 EMPTY_LEDGER`() {
        listOf(
            StepEdit.Remove(0),
            StepEdit.Rename(0, "x"),
            StepEdit.Move(0, 0),
        ).forEach {
            assertEquals(StepEditGate.EMPTY_LEDGER, stepEditGateOf(emptyList(), it), "$it 不该在空账上放行")
        }
    }

    // ---------- 越界 ----------
    @Test
    fun `越界下标拒为 OUT_OF_RANGE 且移序目标位单判`() {
        val s = steps(2)
        assertEquals(StepEditGate.OUT_OF_RANGE, stepEditGateOf(s, StepEdit.Remove(2)))
        assertEquals(StepEditGate.OUT_OF_RANGE, stepEditGateOf(s, StepEdit.Remove(-1)))
        assertEquals(StepEditGate.OUT_OF_RANGE, stepEditGateOf(s, StepEdit.Rename(9, "x")))
        // 源位合法、目标位越界同样拒：半条合法请求不等于可执行
        assertEquals(StepEditGate.OUT_OF_RANGE, stepEditGateOf(s, StepEdit.Move(0, 2)))
        assertEquals(StepEditGate.OUT_OF_RANGE, stepEditGateOf(s, StepEdit.Move(0, -1)))
        // 注入通道缺目标位时主窗送 -1 哨兵：必须落在越界，不能被当成"移到 0"
        assertEquals(StepEditGate.OUT_OF_RANGE, stepEditGateOf(s, StepEdit.Move(1, -1)))
    }

    @Test
    fun `空步骤名拒为 BLANK_NAME`() {
        val s = steps()
        listOf("", "   ", "\t\n").forEach {
            assertEquals(StepEditGate.BLANK_NAME, stepEditGateOf(s, StepEdit.Rename(0, it)))
        }
    }

    @Test
    fun `门禁优先级 先空账后越界 先越界后空名`() {
        assertEquals(StepEditGate.EMPTY_LEDGER, stepEditGateOf(emptyList(), StepEdit.Rename(5, "")))
        assertEquals(StepEditGate.OUT_OF_RANGE, stepEditGateOf(steps(1), StepEdit.Rename(3, "")))
    }

    // ---------- 执行中禁编辑（老板 09-23 裁决：不动停止球位置，重叠区因执行期不让编辑自然消解） ----------
    @Test
    fun `执行中一律拒为 RUNNING 且排在其余三档之前`() {
        val s = steps()
        // 四份本来会放行/会各自归因的请求，在执行中统统只得到一个答案
        listOf(
            s to StepEdit.Remove(0),
            emptyList<Action>() to StepEdit.Remove(0),
            s to StepEdit.Remove(99),
            s to StepEdit.Rename(0, ""),
        ).forEach { (actions, edit) ->
            assertEquals(
                StepEditGate.RUNNING,
                stepEditGateOf(actions, edit, running = true, compileBusy = false),
                "$edit 执行中不得放行",
            )
        }
    }

    @Test
    fun `非执行中四参判定与两参逐档同果（禁编辑不得顺手改坏旧判据）`() {
        val s = steps(2)
        listOf(
            s to StepEdit.Remove(0),
            emptyList<Action>() to StepEdit.Remove(0),
            s to StepEdit.Move(0, 9),
            s to StepEdit.Rename(0, " "),
        ).forEach { (actions, edit) ->
            assertEquals(
                stepEditGateOf(actions, edit),
                stepEditGateOf(actions, edit, running = false, compileBusy = false),
                "两条状态都为假时必须与两参判定一字不差（同一判据，不许两份账）",
            )
        }
    }

    @Test
    fun `跑完即可编 同一请求由 RUNNING 转 READY`() {
        val s = steps()
        val edit = StepEdit.Remove(0)
        assertEquals(StepEditGate.RUNNING, stepEditGateOf(s, edit, running = true, compileBusy = false))
        assertEquals(StepEditGate.READY, stepEditGateOf(s, edit, running = false, compileBusy = false))
    }

    // ---------- 编译中禁编辑（S3-F/F1，裁决 S31-B2：编译互斥从录制两面扩到改账面） ----------

    @Test
    fun `编译在跑一律拒为 COMPILING 且排在内容三档之前`() {
        val s = steps()
        // 与 RUNNING 那一档同形：四份本来会放行/会各自归因的请求，编译在跑时统统只得到一个答案。
        // 这条锁的就是"账不动"——放行任何一份都是在一份即将被整本覆写的账上动笔。
        listOf(
            s to StepEdit.Remove(0),
            emptyList<Action>() to StepEdit.Remove(0),
            s to StepEdit.Remove(99),
            s to StepEdit.Rename(0, ""),
        ).forEach { (actions, edit) ->
            assertEquals(
                StepEditGate.COMPILING,
                stepEditGateOf(actions, edit, running = false, compileBusy = true),
                "$edit 编译中不得放行",
            )
        }
    }

    @Test
    fun `两条状态同时为真时先说执行 不说编译`() {
        // 排序即判据：RUNNING 在前是既有语义（执行中禁编辑，老板 09-23 裁决 2）不被动过；
        // 反过来若先说编译，执行结束后那条话术失去依据却仍挂着一半理由（过期边会晚一个状态）。
        val s = steps()
        assertEquals(
            StepEditGate.RUNNING,
            stepEditGateOf(s, StepEdit.Remove(0), running = true, compileBusy = true),
        )
    }

    @Test
    fun `编译归位即可编 同一请求由 COMPILING 转 READY`() {
        val s = steps()
        val edit = StepEdit.Remove(0)
        assertEquals(StepEditGate.COMPILING, stepEditGateOf(s, edit, running = false, compileBusy = true))
        assertEquals(StepEditGate.READY, stepEditGateOf(s, edit, running = false, compileBusy = false))
    }

    @Test
    fun `编辑两档话术各说自己的事 编译面头一句与录制面同一格`() {
        val compiling = StepEditGate.COMPILING.userCopy().orEmpty()
        val running = StepEditGate.RUNNING.userCopy().orEmpty()
        assertTrue(compiling.isNotBlank() && running.isNotBlank())
        assertNotEquals(compiling, running, "两档共用一句=用户分不清是不许改还是改完了")
        // 头一句必须是派单书 §1-F1-2 点名的那句"AI 编译中，改账与执行此刻不动"，且与录制/执行面同一格常量
        assertTrue(
            compiling.startsWith(COMPILE_HOLD_HEADLINE),
            "编辑面的编译话术没引用共用头一句＝话术又分叉一份：$compiling",
        )
        assertTrue(
            RecordGate.COMPILING.userCopy()!!.contains(COMPILE_HOLD_HEADLINE),
            "录制/执行面那一句必须与编辑面共用同一个头一句常量",
        )
    }

    @Test
    fun `过期边只作废纯状态档 请求档不得被状态跃迁顺手抹掉`() {
        assertNull(
            editRejectionAfterStateChange(StepEditGate.RUNNING, running = false, compileBusy = false),
            "执行结束还挂着 RUNNING=假红",
        )
        assertEquals(
            StepEditGate.RUNNING,
            editRejectionAfterStateChange(StepEditGate.RUNNING, running = true, compileBusy = false),
        )
        // S3-F/F1-3：COMPILING 同为纯状态档，编译归位即撤（不撤就是假红），且必须按**各自那一路**状态撤
        assertNull(
            editRejectionAfterStateChange(StepEditGate.COMPILING, running = false, compileBusy = false),
            "编译都回来了还挂着 COMPILING=假红（S31-B2 扩面的另一半）",
        )
        assertEquals(
            StepEditGate.COMPILING,
            editRejectionAfterStateChange(StepEditGate.COMPILING, running = false, compileBusy = true),
        )
        assertEquals(
            StepEditGate.COMPILING,
            editRejectionAfterStateChange(StepEditGate.COMPILING, running = true, compileBusy = true),
            "执行归位不影响编译档：两条状态各管各的撤字边",
        )
        assertEquals(
            StepEditGate.RUNNING,
            editRejectionAfterStateChange(StepEditGate.RUNNING, running = true, compileBusy = true),
            "编译归位不影响执行档：同上",
        )
        listOf(StepEditGate.EMPTY_LEDGER, StepEditGate.OUT_OF_RANGE, StepEditGate.BLANK_NAME).forEach {
            assertEquals(
                it,
                editRejectionAfterStateChange(it, running = false, compileBusy = false),
                "$it 绑在那次请求上，不该随状态消失",
            )
        }
        assertNull(editRejectionAfterStateChange(null, running = false, compileBusy = false), "无拒因时不得凭空造一条")
    }

    // ---------- 门禁与原语严格对齐 ----------
    @Test
    fun `非 READY 判据下冻结原语必抛 证明门禁一步不漏`() {
        val none: List<Action> = emptyList()
        val two = steps(2)
        listOf<Pair<List<Action>, StepEdit>>(
            none to StepEdit.Remove(0),
            two to StepEdit.Remove(2),
            two to StepEdit.Move(0, 5),
            two to StepEdit.Rename(0, " "),
        ).forEach { (actions, edit) ->
            assertTrue(
                stepEditGateOf(actions, edit) != StepEditGate.READY,
                "${edit.opName()} 必须被门禁拦下（否则原语抛到 UI 线程）",
            )
            assertFailsWith<IllegalArgumentException> { applyStepEdit(actions, edit) }
        }
    }

    @Test
    fun `READY 判据下 applyStepEdit 与冻结原语逐字段同果`() {
        val s = steps()
        assertEquals(removeStep(s, 1), applyStepEdit(s, StepEdit.Remove(1)))
        assertEquals(renameStep(s, 2, "改名后"), applyStepEdit(s, StepEdit.Rename(2, "改名后")))
        assertEquals(moveStep(s, 0, 3), applyStepEdit(s, StepEdit.Move(0, 3)))
    }

    @Test
    fun `改名先 trim 且只动 actionId 不碰定位线索`() {
        val s = steps()
        val edited = applyStepEdit(s, StepEdit.Rename(0, "  step_a  "))
        assertEquals("step_a", edited[0].actionId)
        assertEquals(s[0].value, edited[0].value, "改名不许改写回放依据（那是改录出来的事实）")
    }

    // ---------- 话术 ----------
    @Test
    fun `READY 无话术 四档拒因各自有话术且互不雷同`() {
        assertNull(StepEditGate.READY.userCopy())
        val copies = listOf(
            StepEditGate.RUNNING,
            StepEditGate.EMPTY_LEDGER,
            StepEditGate.OUT_OF_RANGE,
            StepEditGate.BLANK_NAME,
        ).map { assertNotNull(it.userCopy(), "$it 必须给用户话术（L2-③ 禁静默禁用）") }
        assertEquals(4, copies.distinct().size, "四档拒因话术雷同=用户无从知道该改哪一条")
        assertTrue(copies[0].contains("while a task is running") && copies[0].contains("stop"), "执行中话术须给出出口（等完/点球停止）：${copies[0]}")
        assertTrue(copies[1].contains("record first"), "空账话术须指向'先录制编译'：${copies[1]}")
        assertTrue(copies[2].contains("refresh"), "越界话术须提示看当前列表：${copies[2]}")
        assertTrue(copies[3].contains("cannot be empty"), "空名话术须说明名不能空：${copies[3]}")
    }

    // ---------- 空账不发建议 ----------
    @Test
    fun `编辑后空账不写建议 非空账序列化解得回同步数`() {
        assertNull(suggestionAfterEdit(emptyList()), "空步序账进建议流=空任务可回放，假绿形态")
        val s = steps()
        val json = assertNotNull(suggestionAfterEdit(s))
        assertEquals(s.size, decodeActions(json).size)
        assertEquals(encodeActions(s), json)
        // 删到只剩一步再删光：清零这条边同样不发建议（编辑面的 EMPTY 与编译面同律）
        val lastOne = applyStepEdit(s, StepEdit.Remove(3))
        assertEquals(3, lastOne.size)
        assertNotNull(suggestionAfterEdit(lastOne))
        val emptied = lastOne.indices.reversed().fold(lastOne) { acc, i -> applyStepEdit(acc, StepEdit.Remove(i)) }
        assertTrue(emptied.isEmpty())
        assertNull(suggestionAfterEdit(emptied))
    }

    // ---------- 人读标题 ----------
    @Test
    fun `标题线索优先级 text 优于 desc 优于 id 优于 path`() {
        assertEquals(
            "click · 蓝牙",
            stepLabel(
                act(
                    ActionType.CLICK,
                    clues = arrayOf(
                        RecorderCompiler.RESOURCE_ID_KEY to "com.x:id/bt",
                        RecorderCompiler.TEXT_KEY to "蓝牙",
                        RecorderCompiler.CONTENT_DESC_KEY to "desc",
                    ),
                ),
            ),
        )
        assertEquals(
            "click · desc",
            stepLabel(
                act(
                    ActionType.CLICK,
                    clues = arrayOf(
                        RecorderCompiler.RESOURCE_ID_KEY to "com.x:id/bt",
                        RecorderCompiler.CONTENT_DESC_KEY to "desc",
                    ),
                ),
            ),
        )
        assertEquals(
            "click · bt",
            stepLabel(
                act(ActionType.CLICK, clues = arrayOf(RecorderCompiler.RESOURCE_ID_KEY to "com.x:id/bt")),
            ),
        )
        assertEquals(
            "click · root/1/2",
            stepLabel(act(ActionType.CLICK, clues = arrayOf(RecorderCompiler.PATH_KEY to "root/1/2"))),
        )
        assertEquals(
            "click · no clue",
            stepLabel(act(ActionType.CLICK)),
            "零线索步骤不得冒充有线索（与编译器归因同口径）",
        )
    }

    @Test
    fun `标题按类型带专属字段 超长截断`() {
        assertEquals(
            "scroll · forward",
            stepLabel(act(ActionType.SCROLL, clues = arrayOf(RecorderCompiler.DIRECTION_KEY to "forward"))),
        )
        assertEquals(
            "type_text · Search settings · types bluetooth",
            stepLabel(
                act(
                    ActionType.TYPE_TEXT,
                    clues = arrayOf(
                        RecorderCompiler.TEXT_KEY to "Search settings",
                        RecorderCompiler.INPUT_KEY to "bluetooth",
                    ),
                ),
            ),
        )
        val long = stepLabel(act(ActionType.CLICK, clues = arrayOf(RecorderCompiler.TEXT_KEY to "x".repeat(40))))
        assertTrue(long.endsWith("…"), "超长线索须截断，不撑爆编辑行：$long")
        assertEquals(
            "scroll · no direction",
            stepLabel(act(ActionType.SCROLL)),
            "缺方向不得显示 unknown（用户看得懂的字才叫提示）",
        )
        assertEquals(
            "type_text · no clue",
            stepLabel(act(ActionType.TYPE_TEXT)),
            "既无线索也无输入时不编造内容",
        )
    }

    @Test
    fun `删移重排后每步标题原样跟随 不产生新线索`() {
        val s = steps()
        val labelsById = s.associate { it.actionId to stepLabel(it) }
        val reordered = applyStepEdit(applyStepEdit(s, StepEdit.Move(0, 2)), StepEdit.Remove(3))
        assertEquals(listOf("s2", "s3", "s1"), reordered.map { it.actionId })
        reordered.forEach { assertEquals(labelsById.getValue(it.actionId), stepLabel(it)) }
    }

    @Test
    fun `opName 三操作各自可辨（日志留痕要能归因）`() {
        assertEquals(
            listOf("remove", "rename", "move"),
            listOf(StepEdit.Remove(0), StepEdit.Rename(0, "x"), StepEdit.Move(0, 1)).map { it.opName() },
        )
    }
}
