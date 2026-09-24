package com.anytouch.app.recorder

import com.anytouch.contracts.Action
import com.anytouch.contracts.ActionSafety
import com.anytouch.contracts.ActionType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * AI 编译产物落账口**词表档**的 JVM 锁（S3-F/F2-3，裁 S31-B3"整本拒 + 显式"）。
 *
 * 这里锁的是判据本体（三个纯函数）。带 `Log` 的接线（`RecorderStore.acceptModelActions`）在 JVM 里
 * 起不来（本仓不用 Robolectric，`android.util.Log` 会抛未 MOCK 的运行时异常），所以判据必须住在纯函数、
 * 接线只按结论分流——这与 `stepEditGateOf`/`suggestionAfterEdit` 同一套纪律（母单 §2 军令）。
 */
class ModelLedgerGateTest {

    private val supported = setOf(ActionType.WAIT, ActionType.CLICK, ActionType.SCROLL, ActionType.TYPE_TEXT)

    private fun act(type: String, id: String = "a"): Action =
        Action(
            actionId = id,
            type = type,
            source = "node",
            value = null,
            safety = ActionSafety(viewportOk = true, clickEnabled = true),
        )

    // ---- 整本拒的"整本"：一条不合规就点名那一条，且不返回部分账 ----

    @Test
    fun `全支持的账返回 null（整本放行）`() {
        val ledger = listOf(act(ActionType.CLICK), act(ActionType.WAIT, "b"), act(ActionType.SCROLL, "c"))
        assertNull(firstUnsupportedModelAction(ledger, supported))
    }

    @Test
    fun `空账不构成词表违规（空账归另一档判，本档不越权）`() {
        assertNull(firstUnsupportedModelAction(emptyList(), supported))
    }

    @Test
    fun `第一条违规取位序最小的那一条`() {
        val ledger = listOf(
            act(ActionType.CLICK, "a"),
            act(ActionType.KEY, "b"),
            act(ActionType.SUBMIT, "c"),
        )
        val found = firstUnsupportedModelAction(ledger, supported)
        assertNotNull(found)
        assertEquals(1, found.index, "报第一条就得是第一条：指到第 3 条会把用户引去改错的那一步")
        assertEquals(ActionType.KEY, found.type)
    }

    @Test
    fun `首条即违规时 index 为 0`() {
        val found = firstUnsupportedModelAction(listOf(act(ActionType.MOVE), act(ActionType.CLICK)), supported)
        assertEquals(UnsupportedModelAction(0, ActionType.MOVE), found)
    }

    @Test
    fun `支持集为空时任何非空账都被判违规（判据不许把空集合当放行）`() {
        val found = firstUnsupportedModelAction(listOf(act(ActionType.CLICK)), emptySet())
        assertNotNull(found, "支持集空=什么都跑不动，判据必须拒；这里放行就是假绿")
        assertEquals(0, found.index)
    }

    // ---- 话术面（L2-③ 错误必显示）----

    @Test
    fun `支持集上屏按字典序拼接（同一份集合两处显示不能长得不一样）`() {
        assertEquals("click/scroll/type_text/wait", modelLedgerSupportedTypesCopy(supported))
        assertEquals("click/scroll/type_text/wait", modelLedgerSupportedTypesCopy(supported.reversed().toSet()))
    }

    @Test
    fun `拒因点名第几步、什么 type、以及现在跑得动的有哪几个`() {
        val copy = unsupportedModelLedgerCopy(UnsupportedModelAction(2, ActionType.KEY), supported)
        assertTrue(copy.contains("Step 3"), "位序要按人读的第 N 步说（账上 index=2）：$copy")
        assertTrue(copy.contains(ActionType.KEY), "没点名跑不动的那一条 type：$copy")
        for (type in supported) {
            assertTrue(copy.contains(type), "没告诉用户现在跑得动的是哪几个（type=$type）：$copy")
        }
    }

    @Test
    fun `拒因必须说清整本不落账并交代重编代价`() {
        val copy = unsupportedModelLedgerCopy(UnsupportedModelAction(0, ActionType.SELECT_DROPDOWN), supported)
        assertTrue(copy.contains("whole ledger"), "没说整本拒=用户以为那一步被跳过后账还在：$copy")
        assertTrue(copy.contains("not a single step"), copy)
        assertTrue(copy.contains("recompile"), "拒一次=再烧一跑的钱，副作用不许瞒：$copy")
        assertTrue(copy.contains("S31-B3"), "话术要带裁决号，屏上归因才对得上工单：$copy")
    }

    @Test
    fun `支持集只有一个成员时话术仍列全（不许写死四个）`() {
        val copy = unsupportedModelLedgerCopy(UnsupportedModelAction(0, "teleport"), setOf(ActionType.CLICK))
        assertTrue(copy.contains(ActionType.CLICK))
        assertTrue(!copy.contains(ActionType.SCROLL), "支持集里没有 scroll 却印了 scroll＝话术自己抄了一份硬编码词表")
    }
}
