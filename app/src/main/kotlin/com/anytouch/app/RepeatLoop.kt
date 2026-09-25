package com.anytouch.app

import kotlin.random.Random

/**
 * 重复执行计划（S5-d，军令件 `orders/ANYTOUCH-S5d-repeat-loop-ORDER.md` + 裁决 S5-R11）。
 *
 * 这一层是**纯判据**：界内/界外、轮间是否继续、轮间等多久、高危确认在后续轮次是否复用，
 * 全在这里定死，接线（MainActivity 的框、无障碍服务的循环）一律不得自带第二份判据
 * （同 [TaskAdmission] 那条纪律：判据要能在 JVM 里锁住，设备面只验接线）。
 */
data class RepeatPlan(
    val repetitions: Int = RepeatPolicy.DEFAULT_REPETITIONS,
    val intervalSec: Int = RepeatPolicy.DEFAULT_INTERVAL_SEC,
    /** true（默认）=每一轮该弹还弹；只有用户在界面上亲手勾掉它，才允许复用第一轮的放行。 */
    val askEveryRound: Boolean = true,
) {
    val isSingleShot: Boolean get() = repetitions <= 1
}

/** 派发口的解析结果：要么给出计划，要么给出**必须上屏**的拒因（不静默夹取、不猜意图）。 */
sealed class RepeatVerdict {
    data class Accepted(val plan: RepeatPlan) : RepeatVerdict()

    /** @param copy 人读拒因（上屏 + 日志同一句，与其余拒因同律） */
    data class Rejected(val field: String, val copy: String) : RepeatVerdict()
}

object RepeatPolicy {
    const val DEFAULT_REPETITIONS = 1
    const val MIN_REPETITIONS = 1
    const val MAX_REPETITIONS = 100
    const val DEFAULT_INTERVAL_SEC = 5
    const val MIN_INTERVAL_SEC = 1
    const val MAX_INTERVAL_SEC = 60

    /** 军令第 2 条：实际等待 = 设定间隔 ± 该值秒（均匀随机）。 */
    const val JITTER_SEC = 1

    /**
     * 只认 ASCII 十进制字面量：JDK 的 `toIntOrNull` 会接受 `٢`（阿拉伯-印度数字）这类
     * Unicode 数字并静默解释成 2，那等于在派发口猜意图——脏值一律走拒因，不夹取也不换算。
     */
    private val asciiDigits = Regex("[0-9]{1,4}")

    private fun parseAsciiInt(raw: String): Int? = raw.takeIf { asciiDigits.matches(it) }?.toIntOrNull()

    /** 上屏回显用户所填的最大长度：长串会把面板按钮挤位移（v1.0.1→v1.0.2 实测过这一位移）。 */
    private const val ECHO_MAX = 24

    /** 注入通道/手点共用的唯一解析口。空串与缺失=按默认值，脏值与越界=拒（不夹取）。 */
    fun parse(repetitions: String?, intervalSec: String?, askWithoutPrompt: Boolean): RepeatVerdict {
        val reps = repetitions?.trim().orEmpty()
        val interval = intervalSec?.trim().orEmpty()
        if (reps.isNotEmpty()) {
            val n = parseAsciiInt(reps)
            if (n == null || n < MIN_REPETITIONS || n > MAX_REPETITIONS) {
                return RepeatVerdict.Rejected(
                    "repetitions",
                    "Repetitions must be a whole number between $MIN_REPETITIONS and $MAX_REPETITIONS " +
                        "(you typed \"${reps.take(ECHO_MAX)}\"). Nothing was dispatched — " +
                        "fix the number and run again.",
                )
            }
        }
        if (interval.isNotEmpty()) {
            val n = parseAsciiInt(interval)
            if (n == null || n < MIN_INTERVAL_SEC || n > MAX_INTERVAL_SEC) {
                return RepeatVerdict.Rejected(
                    "interval",
                    "Interval must be a whole number of seconds between $MIN_INTERVAL_SEC and $MAX_INTERVAL_SEC " +
                        "(you typed \"${interval.take(ECHO_MAX)}\"). Nothing was dispatched — " +
                        "fix the number and run again.",
                )
            }
        }
        return RepeatVerdict.Accepted(
            RepeatPlan(
                repetitions = parseAsciiInt(reps) ?: DEFAULT_REPETITIONS,
                intervalSec = parseAsciiInt(interval) ?: DEFAULT_INTERVAL_SEC,
                askEveryRound = !askWithoutPrompt,
            ),
        )
    }

    /**
     * 轮间等待毫秒数：设定间隔 + 抖动偏移（由调用侧交回，保持本函数纯），**下夹 0**
     * ——1 秒档摇到 -1 就是 0 秒等待，绝不出现负数延时。
     */
    fun waitMs(intervalSec: Int, jitterOffsetSec: Int): Long =
        maxOf(0, intervalSec + jitterOffsetSec) * 1000L

    /** 抖动偏移取值域（军令第 2 条：±1 秒，含两端）。 */
    fun nextJitterOffset(random: Random = Random.Default): Int =
        random.nextInt(-JITTER_SEC, JITTER_SEC + 1)
}

/** 一轮跑完后循环该往哪儿走。判据与接线分开：这里能决定，服务侧只照办。 */
sealed class LoopStep {
    /** 还要跑下一轮，先等 [waitMs]（急停球在等待期间按下即弃剩余轮次）。 */
    data class WaitThen(val waitMs: Long, val nextRound: Int) : LoopStep()

    /** 设定轮数已跑完。 */
    object Finished : LoopStep()

    /** 急停或本轮被中止：剩余轮次一律不开。 */
    data class Aborted(val reason: String) : LoopStep()
}

/**
 * 开每一轮之前的最后一道闸：轮间等待（分片轮询）期内按下急停，剩余轮次一轮都不开——
 * 军令第 4 条"点了立刻停所有后续轮次"包括停在等待里的那一种。返回急停归因串；null=可以开这一轮。
 */
fun haltReasonBeforeRound(killStopped: Boolean): String? = if (killStopped) "stop_ball" else null

/** 本轮该走哪一步（纯函数，服务侧不得复写这份判据）。 */
fun loopStepAfter(
    completedRound: Int,
    plan: RepeatPlan,
    roundStopped: Boolean,
    killStopped: Boolean,
    jitterOffsetSec: Int,
): LoopStep = when {
    killStopped -> LoopStep.Aborted("stop_ball")
    roundStopped -> LoopStep.Aborted("round_stopped")
    completedRound >= plan.repetitions -> LoopStep.Finished
    else -> LoopStep.WaitThen(RepeatPolicy.waitMs(plan.intervalSec, jitterOffsetSec), completedRound + 1)
}

/**
 * 高危确认在重复轮次里的复用口径（裁决 S5-R11 补裁 C）：
 * 只有用户亲手勾掉"每轮都问"，**且**这一类别在本跑中之前的轮次里真被人在面板上放行过，才自动放行；
 * 冒出新类别照旧弹窗。缓存只窄不宽，每一次自动放行都要由调用侧写日志留痕。
 */
class RepeatConfirmCache(private val askEveryRound: Boolean) {
    enum class Decision {
        /** 弹面板，等用户亲手答（超时=默认拒，语义不动）。 */
        ASK_PANEL,

        /** 本跑早前某轮已被放行过的同一类别，且用户勾了"不再问"。 */
        AUTO_CONFIRMED_EARLIER_GRANT,
    }

    private val granted = LinkedHashSet<String>()

    fun decide(category: String, round: Int): Decision = when {
        askEveryRound -> Decision.ASK_PANEL
        round <= 1 -> Decision.ASK_PANEL
        category in granted -> Decision.AUTO_CONFIRMED_EARLIER_GRANT
        else -> Decision.ASK_PANEL
    }

    /** 只在"面板真被答成放行"时记账（自动放行不重复记账，避免把一次授权放大成无限授权）。 */
    fun onPanelGranted(category: String) {
        granted.add(category)
    }

    /** 供归因/断言用：当前已放行的类别（顺序稳定）。 */
    fun grantedCategories(): Set<String> = granted.toSet()
}

/** 一轮的收口摘要（回执与日志的共用形态）。 */
data class RoundSummary(
    val round: Int,
    val of: Int,
    val ok: Int,
    val total: Int,
    val stopped: Boolean,
    val stopReason: String?,
)

/** 人读收口行：单发任务保持与旧口径逐字一致（不破坏既有断言），多轮才加 round 段。 */
fun RoundSummary.logLine(plan: RepeatPlan): String {
    val base = "ok=$ok total=$total stopped=$stopped stop=${stopReason ?: "-"}"
    return if (plan.isSingleShot) base else "round=$round/$of $base"
}

/** 整跑收口（写进 [AppState.lastRunReport] 的摘要行）：跑了多少轮、为什么停。 */
fun loopReceipt(runs: List<RoundSummary>, plan: RepeatPlan, abortedReason: String?): String {
    val done = runs.size
    return "repeats: $done of ${plan.repetitions} round(s) ran" +
        (if (abortedReason == null) "" else ", stopped early ($abortedReason)") +
        (if (plan.askEveryRound) ", asked for confirmation every round" else ", confirmation asked once (you ticked \"Repeat without asking\")")
}
