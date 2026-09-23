package com.anytouch.app

import com.anytouch.app.platform.RecordGate
import com.anytouch.app.platform.userCopy
import com.anytouch.app.recorder.RecorderCompiler
import com.anytouch.app.recorder.StepEditGate
import com.anytouch.app.recorder.encodeActions
import com.anytouch.app.recorder.userCopy as stepGateCopy
import com.anytouch.contracts.Action
import com.anytouch.contracts.ActionSafety
import com.anytouch.contracts.ActionSource
import com.anytouch.contracts.ActionType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 执行准入（V-3，老板 09-23 裁决）：只拦"机器写的、且已被步序账作废的"那条建议。
 * 锁的两端必须同时钉住——放过孤儿建议=放出账上已删的步骤（假绿近亲）；
 * 拦到手敲 JSON=打死 S1 主路径（device-smoke 的 C 系列全是账外注入，账恒为 0 步）。
 */
class TaskAdmissionTest {

    private fun act(id: String) = Action(
        actionId = id,
        type = ActionType.CLICK,
        source = ActionSource.NODE,
        value = buildJsonObject { put(RecorderCompiler.TEXT_KEY, "行$id") },
        safety = ActionSafety(viewportOk = true, clickEnabled = true),
    )

    private fun ledger(steps: Int) = (1..steps).map { act("s$it") }

    private fun suggestionOf(steps: Int) = encodeActions(ledger(steps))

    // ---------- 放行边 ----------

    @Test
    fun `机器建议与当前账逐字一致：放行`() {
        val json = suggestionOf(3)
        assertEquals(TaskAdmission.ACCEPT, taskAdmission(json, json, json))
    }

    @Test
    fun `本轮从未发布建议：放行（首启样例与 adb 注入都走这条）`() {
        val typed = """[{"action_id":"cd","type":"click"}]"""
        assertEquals(TaskAdmission.ACCEPT, taskAdmission(typed, encodeActions(emptyList()), null))
    }

    @Test
    fun `用户手改过的文本自证其权：即便账非空也不拦（不夺字）`() {
        val typed = suggestionOf(2).replace("行s1", "我自己改的")
        assertEquals(TaskAdmission.ACCEPT, taskAdmission(typed, encodeActions(ledger(3)), suggestionOf(3)))
    }

    @Test
    fun `空任务框不是孤儿建议形态：交下游解码归因，不在此处造第二套判据`() {
        assertEquals(TaskAdmission.ACCEPT, taskAdmission("", encodeActions(ledger(1)), suggestionOf(1)))
    }

    // ---------- 拒放边 ----------

    @Test
    fun `账删到清零而框内仍挂机器旧建议：拒放（V-3 设备实证形态）`() {
        val stale = suggestionOf(1)
        assertEquals(TaskAdmission.STALE_SUGGESTION, taskAdmission(stale, encodeActions(emptyList()), stale))
    }

    @Test
    fun `账被编辑过而框内是编辑前那条建议：拒放`() {
        val before = suggestionOf(3)
        assertEquals(TaskAdmission.STALE_SUGGESTION, taskAdmission(before, suggestionOf(2), before))
    }

    @Test
    fun `重新开录作废旧账后仍挂着上次建议：拒放（同一孤儿形态的另一条来路）`() {
        val stale = suggestionOf(2)
        assertEquals(TaskAdmission.STALE_SUGGESTION, taskAdmission(stale, encodeActions(emptyList()), stale))
    }

    @Test
    fun `空账序列化为空数组字面量，不可能与任何非空建议相等（拒放边的前提）`() {
        assertEquals("[]", encodeActions(emptyList()))
    }

    // ---------- 话术 ----------

    @Test
    fun `拒放必有人读话术，放行无话术`() {
        assertNotNull(TaskAdmission.STALE_SUGGESTION.userCopy())
        assertNull(TaskAdmission.ACCEPT.userCopy())
    }

    @Test
    fun `话术说清三件事：为什么没放、账已不含这些步、不夺用户输入`() {
        val copy = TaskAdmission.STALE_SUGGESTION.userCopy().orEmpty()
        assertTrue(copy.contains("步序账"), copy)
        assertTrue(copy.contains("停止并编译"), copy)
        assertTrue(copy.contains("不会被改动"), copy)
    }

    @Test
    fun `四条拒因话术互不雷同（同屏出现时用户要分得清是哪一环拦的）`() {
        val copies = listOfNotNull(
            TaskAdmission.STALE_SUGGESTION.userCopy(),
            RecordGate.RUNNING.userCopy(),
            RecordGate.SERVICE_OFF.userCopy(),
            RecordGate.BALL_UNAVAILABLE.userCopy(),
            StepEditGate.BLANK_NAME.stepGateCopy(),
            StepEditGate.EMPTY_LEDGER.stepGateCopy(),
            StepEditGate.OUT_OF_RANGE.stepGateCopy(),
        )
        assertEquals(7, copies.distinct().size, copies.toString())
    }
}
