package com.anytouch.app

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S5-d 重复执行的判据锁（裁决 S5-R11）。这里锁的是**唯一真值**：接线（UI 框 / 服务循环）
 * 只许调用这些函数，不许自带第二份判据——所以判据的每一条边界都必须能在 JVM 里咬住。
 */
class RepeatLoopTest {

    private fun accepted(reps: String?, interval: String?, noAsk: Boolean = false): RepeatPlan =
        when (val v = RepeatPolicy.parse(reps, interval, noAsk)) {
            is RepeatVerdict.Accepted -> v.plan
            is RepeatVerdict.Rejected -> error("expected accepted, got ${v.field}: ${v.copy}")
        }

    private fun rejected(reps: String?, interval: String?): RepeatVerdict.Rejected =
        RepeatPolicy.parse(reps, interval, false) as RepeatVerdict.Rejected

    @Test
    fun `缺失与空串按军令默认值 1 轮 5 秒 每轮都问`() {
        for ((r, i) in listOf(Pair(null, null), Pair("", ""), Pair("  ", "\t"))) {
            val plan = accepted(r, i)
            assertEquals(RepeatPolicy.DEFAULT_REPETITIONS, plan.repetitions)
            assertEquals(RepeatPolicy.DEFAULT_INTERVAL_SEC, plan.intervalSec)
            assertTrue("默认必须每轮都问（勾掉才是用户主动授权）", plan.askEveryRound)
            assertTrue(plan.isSingleShot)
        }
    }

    @Test
    fun `界内取值逐字进计划`() {
        val plan = accepted("7", "12", noAsk = true)
        assertEquals(7, plan.repetitions)
        assertEquals(12, plan.intervalSec)
        assertFalse(plan.askEveryRound)
        assertEquals(
            accepted("100", "60"),
            RepeatPlan(100, 60, true),
        )
        assertEquals(RepeatPlan(1, 1, true), accepted("1", "1"))
    }

    @Test
    fun `脏值与越界一律拒派发 不静默夹取`() {
        // "٢" 是阿拉伯-印度数字 2：JDK 的 toIntOrNull 会把它静默换算成 2 并放行，
        // 判据层必须只认 ASCII 字面量——在派发口猜用户填的是什么数字就是第二份语义。
        for (bad in listOf("0", "101", "-3", "abc", "1.5", "1e2", " 1 0", "٢", "1_0", "+5", "1234")) {
            val v = rejected(bad, "5")
            assertEquals("repetitions", v.field)
            assertTrue("拒因必须说清没派发：$v", v.copy.contains("Nothing was dispatched"))
            assertTrue(v.copy.contains("between 1 and 100"))
        }
        for (bad in listOf("0", "61", "-1", "x", "5.5", "")) {
            if (bad.isEmpty()) continue
            val v = rejected("3", bad)
            assertEquals("interval", v.field)
            assertTrue(v.copy.contains("between 1 and 60"))
        }
        // 越界方向两边都要拦：100 合法、101 拒；60 合法、61 拒
        assertEquals(100, accepted("100", "60").repetitions)
        assertEquals("repetitions", rejected("101", "60").field)
    }

    @Test
    fun `拒因回显有上限量 长串不挤动面板`() {
        val v = rejected("x".repeat(200), "5")
        val echo = Regex("you typed \"([^\"]*)\"").find(v.copy)!!.groupValues[1]
        assertEquals(24, echo.length)
        assertTrue(echo.all { it == 'x' })
    }

    @Test
    fun `轮间等待等于间隔加减一秒 且绝不出现负延时`() {
        assertEquals(4_000L, RepeatPolicy.waitMs(5, -1))
        assertEquals(5_000L, RepeatPolicy.waitMs(5, 0))
        assertEquals(6_000L, RepeatPolicy.waitMs(5, 1))
        // 1 秒档摇到 -1 只能落到 0，不能是 -1000（负 delay 在协程里等价于 0，但账面必须显式夹住）
        assertEquals(0L, RepeatPolicy.waitMs(1, -1))
        assertEquals(1_000L, RepeatPolicy.waitMs(1, 0))
        assertEquals(2_000L, RepeatPolicy.waitMs(1, 1))
        assertEquals(61_000L, RepeatPolicy.waitMs(60, 1))
    }

    @Test
    fun `抖动取值只允许 -1 0 +1 三个偏移`() {
        val seen = mutableSetOf<Int>()
        val random = Random(20260925)
        repeat(500) { seen += RepeatPolicy.nextJitterOffset(random) }
        assertEquals(setOf(-1, 0, 1), seen)
    }

    @Test
    fun `急停优先于一切 本轮被中止也不开下一轮`() {
        val plan = RepeatPlan(5, 5, true)
        assertEquals(
            LoopStep.Aborted("stop_ball"),
            loopStepAfter(1, plan, roundStopped = false, killStopped = true, jitterOffsetSec = 0),
        )
        // 急停 + 本轮已停：仍按急停归因（原因不许挑轻的写）
        assertEquals(
            LoopStep.Aborted("stop_ball"),
            loopStepAfter(1, plan, roundStopped = true, killStopped = true, jitterOffsetSec = 0),
        )
        assertEquals(
            LoopStep.Aborted("round_stopped"),
            loopStepAfter(2, plan, roundStopped = true, killStopped = false, jitterOffsetSec = 1),
        )
    }

    @Test
    fun `跑满设定轮数即收口 未满则按抖动等待下一轮`() {
        val plan = RepeatPlan(3, 7, true)
        assertEquals(
            LoopStep.WaitThen(6_000L, 2),
            loopStepAfter(1, plan, roundStopped = false, killStopped = false, jitterOffsetSec = -1),
        )
        assertEquals(
            LoopStep.WaitThen(8_000L, 3),
            loopStepAfter(2, plan, roundStopped = false, killStopped = false, jitterOffsetSec = 1),
        )
        assertEquals(
            LoopStep.Finished,
            loopStepAfter(3, plan, roundStopped = false, killStopped = false, jitterOffsetSec = 1),
        )
        // 单发计划：第一轮跑完就 Finished，不进等待分支
        assertEquals(
            LoopStep.Finished,
            loopStepAfter(1, RepeatPlan(1, 5, true), false, false, 0),
        )
    }

    @Test
    fun `等待期内急停 下一轮一格都不开`() {
        assertNull(haltReasonBeforeRound(killStopped = false))
        assertEquals("stop_ball", haltReasonBeforeRound(killStopped = true))
    }

    @Test
    fun `不勾开关时每轮都弹 勾了也只复用真被放行过的类别`() {
        val always = RepeatConfirmCache(askEveryRound = true)
        always.onPanelGranted("DELETE")
        for (round in 1..4) {
            assertEquals(RepeatConfirmCache.Decision.ASK_PANEL, always.decide("DELETE", round))
        }

        val cached = RepeatConfirmCache(askEveryRound = false)
        // 第一轮无论如何都要弹（面板就是那一次授权本身）
        assertEquals(RepeatConfirmCache.Decision.ASK_PANEL, cached.decide("DELETE", 1))
        cached.onPanelGranted("DELETE")
        assertEquals(
            RepeatConfirmCache.Decision.AUTO_CONFIRMED_EARLIER_GRANT,
            cached.decide("DELETE", 2),
        )
        // 没放行过的新类别照旧弹：缓存只窄不宽
        assertEquals(RepeatConfirmCache.Decision.ASK_PANEL, cached.decide("PAYMENT", 2))
        cached.onPanelGranted("PAYMENT")
        assertEquals(
            RepeatConfirmCache.Decision.AUTO_CONFIRMED_EARLIER_GRANT,
            cached.decide("PAYMENT", 3),
        )
        // 自动放行不得再往账里加东西（一次授权不被放大成无限授权）
        cached.decide("DELETE", 4)
        assertEquals(setOf("DELETE", "PAYMENT"), cached.grantedCategories())
    }

    @Test
    fun `单发收口行与旧口径逐字一致 多轮才加 round 段`() {
        val single = RepeatPlan(1, 5, true)
        assertEquals(
            "ok=9 total=9 stopped=false stop=-",
            RoundSummary(1, 1, 9, 9, false, null).logLine(single),
        )
        val multi = RepeatPlan(5, 5, true)
        assertEquals(
            "round=2/5 ok=9 total=9 stopped=false stop=-",
            RoundSummary(2, 5, 9, 9, false, null).logLine(multi),
        )
        assertEquals(
            "round=4/5 ok=8 total=9 stopped=true stop=user_stop",
            RoundSummary(4, 5, 8, 9, true, "user_stop").logLine(multi),
        )
    }

    @Test
    fun `整跑收口说清跑了几轮 为什么停 问了几次`() {
        val plan = RepeatPlan(5, 5, askEveryRound = true)
        val runs = listOf(
            RoundSummary(1, 5, 9, 9, false, null),
            RoundSummary(2, 5, 9, 9, false, null),
        )
        assertEquals(
            "repeats: 2 of 5 round(s) ran, stopped early (stop_ball), asked for confirmation every round",
            loopReceipt(runs, plan, "stop_ball"),
        )
        val noAsk = RepeatPlan(2, 5, askEveryRound = false)
        assertEquals(
            "repeats: 2 of 2 round(s) ran, confirmation asked once (you ticked \"Repeat without asking\")",
            loopReceipt(runs, noAsk, null),
        )
    }

    @Test
    fun `上屏拒因与收口纯英文无中文残留 且不撞黑话`() {
        val texts = buildList {
            add(rejected("101", "5").copy)
            add(rejected("3", "61").copy)
            add(loopReceipt(emptyList(), RepeatPlan(3, 5, true), null))
            add(loopReceipt(emptyList(), RepeatPlan(3, 5, false), "round_stopped"))
        }
        for (t in texts) {
            // 与红线 C 同一口径（CJK 归零，破折号/弯引号等英文标点不算）：见 HighRiskPanelCopyTest
            assertTrue(
                "含中日标点字符：$t",
                t.none { it.code in 0x4E00..0x9FFF || it.code in 0x3000..0x303F },
            )
            assertFalse("撞 Upload 字样：$t", Regex("[Uu]pload").containsMatchIn(t))
        }
    }
}
