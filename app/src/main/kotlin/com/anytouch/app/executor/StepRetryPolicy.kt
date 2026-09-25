package com.anytouch.app.executor

/**
 * 单步失败的形态分类（S5-R12 要求 1 的"哪些失败可以自动重试"的唯一词表）。
 *
 * 平台侧只把现场摊成这一格枚举（"派发被明示拒绝"／"节点没找到"／"字没落进框"…），
 * 分类之后**不再自己判该不该重试**——那是 [StepRetryPolicy] 的活（雷 18 同族：两处实现只有坏的那条会被看见）。
 */
enum class StepFailure {
    /** `performAction` 返回 false：动作根本没执行过，线索未被消耗。 */
    ExplicitRefusal,

    /** 定位轮询到超时仍未命中（节点此刻不在树上）。 */
    LocatorMiss,

    /** 派发被接收但未落字（`type_text` 的虚报形态，复核判失败）。 */
    TextNotLanded,

    /** 高危命中后未获二次确认（含 15s 无人应答默认拒）。 */
    HighRiskConfirmation,

    /** 用户按了急停球。 */
    UserStopped,

    /** 安全阀／三门禁拒（`SAFETY_GATE_BLOCKED`）。 */
    SafetyGateBlocked,

    /** 该步不带任何定位线索（脏任务，含"只填了坐标"这一形态）。 */
    MissingLocatorClue,

    /** 执行器跑不动的动作类型。 */
    UnsupportedType,
}

/**
 * 步骤自动重试的**唯一**额度真源（军令 S5-R12 要求 1 + 裁决钉 2／钉 9）。
 *
 * 为什么需要一张合并的账（钉 9 原文的理由）：本批之前盘上已经存在两处"再试一次"——明示拒重派
 * （[redispatchPlan]）与 `type_text` 未落字的整步兜底。军令又加了"每步最多重试 2 次"，
 * 三处各留一份就是 1+1+2 的隐式放大。**一条队列里只许有一本重试账**：
 * 单步总尝试 ≤ 3（原始 1 + 重试 2），且两处旧兜底消耗的也是这同一份额度。
 *
 * 高危步一律不自动重试（三裁 ②）：所谓"点击失败"可能**其实已经落地**（只是校验超时），
 * 当场重试＝同一个删除/付款类动作被触发第二次。高危确认口径因此一字不动：每次命中照旧弹、
 * 不答默认拒、失败即按现行停下报错，且**不新增任务开头预弹**。
 *
 * 本文件 android-free：不读节点、不写日志、不碰设备。
 */
object StepRetryPolicy {

    /** 军令"最多 2 次"= 非高危步的重试上限；总尝试数 = 1 + 本值。 */
    const val MAX_STEP_RETRIES = 2

    /** 单步总尝试上限（原始一次 + 重试两次），账面与用例都引用这一格。 */
    const val MAX_STEP_ATTEMPTS = MAX_STEP_RETRIES + 1

    /** 该失败形态可重试的三格白名单之外一律不可重试（默认拒绝，不猜意图）。 */
    fun retryable(failure: StepFailure): Boolean = when (failure) {
        StepFailure.ExplicitRefusal, StepFailure.LocatorMiss, StepFailure.TextNotLanded -> true
        StepFailure.HighRiskConfirmation,
        StepFailure.UserStopped,
        StepFailure.SafetyGateBlocked,
        StepFailure.MissingLocatorClue,
        StepFailure.UnsupportedType,
        -> false
    }

    /** 重试上限：高危命中过的步为 0（[MAX_STEP_RETRIES] 只给非高危步）。 */
    fun retryCeiling(riskMatched: Boolean): Int = if (riskMatched) 0 else MAX_STEP_RETRIES

    /**
     * 还能不能再试一次：形态可重试 **且** 已消耗的重试数没撞上限。
     *
     * @param retriesUsed 本步已经消耗掉的重试次数（含两处旧兜底消耗的那几次——同一本账）。
     */
    fun shouldRetry(failure: StepFailure, riskMatched: Boolean, retriesUsed: Int): Boolean =
        retryable(failure) && retriesUsed < retryCeiling(riskMatched)

    /** 还剩几次可试（[redispatchPlan] 那一环的预算由它下发，禁止在别处再写一个"1"）。 */
    fun retriesLeft(riskMatched: Boolean, retriesUsed: Int): Int =
        (retryCeiling(riskMatched) - retriesUsed).coerceAtLeast(0)
}
