package com.anytouch.app.executor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S5-R12（要求 1 + 三裁 ② + 钉 9）的**额度判据**锁：一张队列只许有一本重试账。
 *
 * 为什么单独立一个纯函数用例（而不是只靠 `NodeTaskRunnerTest` 的队列级用例）：
 * 盘上本批之前已有两处"再试一次"（明示拒重派 / SET_TEXT 整步兜底），军令又加了"每步 ≤2 次"。
 * 三处各写各的常量就是 1+1+2 的隐式放大，而放大这件事在设备面上**看不出来**（每次都像合理兜底）。
 * 所以额度本身必须在纯函数这一层被钉死，接线只许取数。
 *
 * 语料一律 ASCII（本仓第 11 颗雷）。
 */
class StepRetryPolicyTest {

    private val retryable = listOf(
        StepFailure.ExplicitRefusal,
        StepFailure.LocatorMiss,
        StepFailure.TextNotLanded,
    )

    private val neverRetryable = listOf(
        StepFailure.HighRiskConfirmation,
        StepFailure.UserStopped,
        StepFailure.SafetyGateBlocked,
        StepFailure.MissingLocatorClue,
        StepFailure.UnsupportedType,
    )

    @Test
    fun `枚举全集恰等于黑白名单之并 新增形态必须显式归类`() {
        // 白名单 + 黑名单必须覆盖 enum 全集：新增一个 StepFailure 而没在这两条里表态，这里当场红
        // （"忘了归类"在 when 里会被编译器抓到，但把 when 改成 if/else 就漏了——这条锁的是口径本身）。
        assertEquals(
            "全部失败形态都要在可重试/不可重试两张名单里占一格",
            StepFailure.entries.toSet(),
            (retryable + neverRetryable).toSet(),
        )
        assertEquals("同一形态不许既黑又白", 0, (retryable.toSet() intersect neverRetryable.toSet()).size)
    }

    @Test
    fun `三种什么都没发生的失败可重试 其余一律不可`() {
        // 可重试的三条共同点：**这一步什么都没做成**（拒答=没执行、没定位到=没派发、字没落=没落数据），
        // 重跑零副作用。安全阀/急停/脏任务/词表外的类型都属于"人或数据要表态"，不许机器代答。
        for (f in retryable) assertTrue("$f 该可重试", StepRetryPolicy.retryable(f))
        for (f in neverRetryable) assertFalse("$f 绝不自动重试", StepRetryPolicy.retryable(f))
    }

    @Test
    fun `高危步的重试上限为零 非高危为MAX`() {
        assertEquals(2, StepRetryPolicy.MAX_STEP_RETRIES)
        assertEquals(3, StepRetryPolicy.MAX_STEP_ATTEMPTS)
        assertEquals("三裁 ②：命中过高危词表的步一次都不自动重试", 0, StepRetryPolicy.retryCeiling(riskMatched = true))
        assertEquals(StepRetryPolicy.MAX_STEP_RETRIES, StepRetryPolicy.retryCeiling(riskMatched = false))
    }

    @Test
    fun `总尝试恰为1原始加2重试 第3次已用即收口`() {
        for (f in retryable) {
            assertTrue("$f 首次失败该重试", StepRetryPolicy.shouldRetry(f, riskMatched = false, retriesUsed = 0))
            assertTrue("$f 第二次失败仍该重试", StepRetryPolicy.shouldRetry(f, false, 1))
            assertFalse(
                "$f 已用满 2 次后必须停下报错：军令的 2 次是上限不是下限",
                StepRetryPolicy.shouldRetry(f, false, 2),
            )
            assertFalse("$f 已用满后更多额度也不许凭空出现", StepRetryPolicy.shouldRetry(f, false, 7))
        }
    }

    @Test
    fun `高危格无论额度都应false`() {
        for (f in StepFailure.entries) {
            assertFalse(
                "$f 在高危步上不许自动重试（失败可能其实已落地，二触=同一动作跑第二遍）",
                StepRetryPolicy.shouldRetry(f, riskMatched = true, retriesUsed = 0),
            )
        }
    }

    @Test
    fun `retriesLeft随消耗单调递减且永不为负`() {
        assertEquals(2, StepRetryPolicy.retriesLeft(riskMatched = false, retriesUsed = 0))
        assertEquals(1, StepRetryPolicy.retriesLeft(false, 1))
        assertEquals(0, StepRetryPolicy.retriesLeft(false, 2))
        // 负数会把 redispatchPlan 的 `retriesLeft <= 0` 判成 GiveUp —— 结果虽然同形，但"越界即停"
        // 必须由归零来表达：接线里若把 used 算错成 5，这里红而不是悄悄多派一次。
        assertEquals("超额消耗按归零表达，不外溢成负数", 0, StepRetryPolicy.retriesLeft(false, 5))
        assertEquals(0, StepRetryPolicy.retriesLeft(true, 0))
        assertEquals(0, StepRetryPolicy.retriesLeft(true, 9))
    }

    @Test
    fun `额度与剩余两口径互证 不为同一事写两份判据`() {
        // shouldRetry 与 retriesLeft 必须同源：一个说"还能试"另一个就必须 >0。
        // 拆成两份实现迟早出现"一处判 <= 一处判 <"的错位（本仓雷族）。
        for (risk in listOf(false, true)) {
            for (used in 0..4) {
                assertEquals(
                    "risk=$risk used=$used 两口径给出矛盾答案",
                    (StepRetryPolicy.retriesLeft(risk, used) > 0),
                    StepRetryPolicy.shouldRetry(StepFailure.ExplicitRefusal, risk, used),
                )
            }
        }
    }
}
