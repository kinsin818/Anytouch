package com.anytouch.app.safety

import com.anytouch.contracts.StopCode
import com.anytouch.pipeline.PipelineStopCode
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SafetyQueueRunnerTest {

    private val steps = listOf(
        GuardedStep("s0", SimpleNodeProbe(text = "WLAN")),
        GuardedStep("s1", SimpleNodeProbe(text = "蓝牙")),
        GuardedStep("s2", SimpleNodeProbe(text = "更多连接")),
    )

    @BeforeTest
    fun setUp() = KillSwitch.reset()

    @AfterTest
    fun tearDown() = KillSwitch.reset()

    @Test
    fun `全绿队列逐步执行且无停止回执`() {
        val executed = ArrayList<String>()
        val outcome = SafetyQueueRunner().run(steps, executor = { executed += it.stepId })
        assertEquals(listOf("s0", "s1", "s2"), executed)
        assertFalse(outcome.stopped)
        assertNull(outcome.stopReceipt)
        assertTrue(outcome.steps.all { it.status == SafetyQueueRunner.STATUS_EXECUTED })
    }

    @Test
    fun `开跑前已被kill则一步不执行并产Command风格回执`() {
        KillSwitch.stop(reason = "user_stop", source = "pre_flight")
        var executedCount = 0
        val outcome = SafetyQueueRunner().run(steps, executor = { executedCount++ })
        assertEquals(0, executedCount)
        assertTrue(outcome.stopped)
        val receipt = requireNotNull(outcome.stopReceipt)
        assertEquals("stop", receipt.type)
        assertEquals("stopped", receipt.confirm)
        assertEquals("kill_switch", receipt.source)
        assertEquals("kill-switch-stop-s0-0", receipt.commandId)
        assertEquals("0", receipt.payload["step_index"])
        assertEquals(StopCode.USER_STOP, receipt.payload["stop_code"])
        assertEquals("user_stop", receipt.payload["stop_reason"])
    }

    @Test
    fun `队列中途kill则剩余步中止`() {
        val executed = ArrayList<String>()
        val outcome = SafetyQueueRunner().run(
            steps,
            executor = {
                executed += it.stepId
                if (it.stepId == "s1") KillSwitch.stop(reason = "ball_pressed")
            },
        )
        assertEquals(listOf("s0", "s1"), executed)
        assertTrue(outcome.stopped)
        val receipt = requireNotNull(outcome.stopReceipt)
        assertEquals("2", receipt.payload["step_index"])
        assertEquals("s2", receipt.payload["step_id"])
        assertEquals("ball_pressed", receipt.payload["stop_reason"])
    }

    @Test
    fun `高危步经二次确认后放行执行`() {
        val risky = listOf(GuardedStep("r0", SimpleNodeProbe(text = "确认转账")))
        var confirmed: SafetyVerdict.RequiresSecondConfirm? = null
        val outcome = SafetyQueueRunner().run(
            risky,
            executor = { },
            confirmer = { verdict -> confirmed = verdict; true },
        )
        assertFalse(outcome.stopped)
        assertEquals(SafetyQueueRunner.STATUS_EXECUTED, outcome.steps.single().status)
        assertEquals("second_confirmed", outcome.steps.single().detail)
        assertEquals(HighRiskCategory.TRANSFER, requireNotNull(confirmed).matchedRule.category)
    }

    @Test
    fun `高危步无人确认则整条队列以SAFETY_GATE_BLOCKED中止`() {
        val mixed = listOf(
            GuardedStep("ok0", SimpleNodeProbe(text = "WLAN")),
            GuardedStep("risk1", SimpleNodeProbe(text = "去支付")),
            GuardedStep("ok2", SimpleNodeProbe(text = "蓝牙")),
        )
        val executed = ArrayList<String>()
        val outcome = SafetyQueueRunner(matcher = HighRiskMatcher.default())
            .run(mixed, executor = { executed += it.stepId })
        assertEquals(listOf("ok0"), executed)
        assertTrue(outcome.stopped)
        assertEquals(SafetyQueueRunner.STATUS_BLOCKED, outcome.steps.last().status)
        val receipt = requireNotNull(outcome.stopReceipt)
        assertEquals("safety_gate", receipt.source)
        assertEquals(PipelineStopCode.SAFETY_GATE_BLOCKED, receipt.payload["stop_code"])
        assertTrue(receipt.payload.getValue("stop_reason").contains("PAYMENT"))
        assertFalse(receipt.commandId.startsWith("kill-switch"), "kill 回执不得冒充门禁回执")
    }

    @Test
    fun `词表坏时首步即拒且执行器零调用`() {
        val broken = HighRiskMatcher.from(HighRiskRuleSource { throw IllegalStateException("no rules") })
        var executedCount = 0
        val outcome = SafetyQueueRunner(matcher = broken)
            .run(steps, executor = { executedCount++ })
        assertEquals(0, executedCount)
        assertTrue(outcome.stopped)
        val receipt = requireNotNull(outcome.stopReceipt)
        assertEquals(PipelineStopCode.SAFETY_GATE_BLOCKED, receipt.payload["stop_code"])
        assertTrue(receipt.payload.getValue("stop_reason").startsWith("denied:RULES_LOAD_FAILED"))
    }
}
