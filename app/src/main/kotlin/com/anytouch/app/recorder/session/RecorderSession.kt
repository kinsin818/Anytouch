package com.anytouch.app.recorder.session

import com.anytouch.app.recorder.RecEvent
import com.anytouch.app.recorder.WindowChanged

/** 会话状态（军令 STAGE-22）：合法转移仅 IDLE→RECORDING⇄PAUSED→STOPPED，另加 STOPPED→RECORDING（resumeAsRecording 续录）。 */
enum class SessionState { IDLE, RECORDING, PAUSED, STOPPED }

/** 操作被拒绝的归因（禁静默吞：每次拒绝必须携带 reason）。 */
enum class RejectionReason {
    /** 当前态不允许该操作（非法转移全表统一归因）。 */
    INVALID_STATE,

    /** 事件缓冲已达 maxEvents，本条被拒（禁静默丢）。 */
    OVERFLOW,

    /** deserialize 输入损坏/非法存档。 */
    CORRUPT_ARCHIVE,
}

/** 状态机操作与 append 的统一返回值：Accepted / Rejected，不抛异常。 */
sealed class SessionOutcome {
    data object Accepted : SessionOutcome()

    data class Rejected(val reason: RejectionReason, val detail: String) : SessionOutcome()
}

/** 反序列化返回型：成功携带 STOPPED 只读会话，失败带归因，均不抛。 */
sealed class SessionRestore {
    data class Loaded(val session: RecorderSession) : SessionRestore()

    data class Failed(val reason: RejectionReason, val detail: String) : SessionRestore()
}

/**
 * 录制会话状态机（STAGE-22）：纯 JVM、零新增依赖。
 * 时间戳由注入 clock 决定（生产缺省 System.currentTimeMillis，测试注入假钟，零真实 sleep）；
 * 事件结构只引用 STAGE-21 冻结的 RecEvent，本包不复制也不修改其定义。
 */
class RecorderSession(
    val targetPkg: String,
    val clock: () -> Long = { System.currentTimeMillis() },
    val maxEvents: Int = DEFAULT_MAX_EVENTS,
    initialEvents: List<RecEvent> = emptyList(),
    initialOverflowCount: Int = 0,
    initialRejectedEvents: List<RecEvent> = emptyList(),
    initialState: SessionState = SessionState.IDLE,
) {
    init {
        require(maxEvents >= 0) { "maxEvents must be >= 0 but was $maxEvents" }
        require(initialState == SessionState.STOPPED || (initialEvents.isEmpty() && initialOverflowCount == 0)) {
            "非 STOPPED 会话不得携带初始事件缓冲（读档恢复唯一入口是 deserialize，其态恒 STOPPED）"
        }
    }

    var state: SessionState = initialState
        private set

    private val events: MutableList<RecEvent> = ArrayList(initialEvents)

    /** 溢出被拒条数（可读计数，禁静默丢）。 */
    var overflowCount: Int = initialOverflowCount
        private set

    /** 全部被拒事件（非法态 + 溢出），按发生顺序保留副本，供审计与"不入缓冲"证明。 */
    var rejectedEvents: List<RecEvent> = initialRejectedEvents
        private set

    /** 取下一个时间戳：事件源构造 RecEvent 时用本方法，保证时钟只握在 session 手里。 */
    fun nextTimestampMs(): Long = clock()

    fun start(): SessionOutcome = transition(SessionState.IDLE, SessionState.RECORDING, "start")

    fun pause(): SessionOutcome = transition(SessionState.RECORDING, SessionState.PAUSED, "pause")

    fun resume(): SessionOutcome = transition(SessionState.PAUSED, SessionState.RECORDING, "resume")

    /** stop 从 RECORDING 或 PAUSED 均合法（⇄ 段两态皆可停）。 */
    fun stop(): SessionOutcome {
        val now = state
        if (now != SessionState.RECORDING && now != SessionState.PAUSED) {
            return rejectOp("stop", now, "仅 RECORDING/PAUSED 可 stop")
        }
        state = SessionState.STOPPED
        return SessionOutcome.Accepted
    }

    /** 断点续录：唯一允许离开 STOPPED 的操作（deserialize 回 STOPPED 只读取档后由此续录）。 */
    fun resumeAsRecording(): SessionOutcome =
        transition(SessionState.STOPPED, SessionState.RECORDING, "resumeAsRecording")

    /** 仅 RECORDING 受理（带 stamp 的便捷口；节点动作由调用方自构 RecEvent 后 append）。 */
    fun appendWindowChanged(pkg: String, windowTitle: String? = null): SessionOutcome =
        append(WindowChanged(pkg, windowTitle, timestampMs = clock()))

    fun append(event: RecEvent): SessionOutcome {
        val now = state
        if (now != SessionState.RECORDING) {
            rejectedEvents = rejectedEvents + event
            return SessionOutcome.Rejected(
                RejectionReason.INVALID_STATE,
                "append 在 $now 态被拒：仅 RECORDING 受理，事件未入缓冲",
            )
        }
        if (events.size >= maxEvents) {
            overflowCount++
            rejectedEvents = rejectedEvents + event
            return SessionOutcome.Rejected(
                RejectionReason.OVERFLOW,
                "事件缓冲已满（上限 $maxEvents），第 ${events.size + 1} 条被拒并计数（overflow=$overflowCount），未静默丢",
            )
        }
        events += event
        return SessionOutcome.Accepted
    }

    /** 已受理事件序列（只读拷贝，顺序即录制顺序）。 */
    fun eventList(): List<RecEvent> = events.toList()

    /** 任意态可调。 */
    fun serialize(): String =
        SessionCodec.encode(SessionSnapshot(targetPkg, state, events.toList(), overflowCount, rejectedEvents.toList()))

    private fun transition(from: SessionState, to: SessionState, op: String): SessionOutcome {
        val now = state
        if (now != from) return rejectOp(op, now, "仅 $from 可 $op")
        state = to
        return SessionOutcome.Accepted
    }

    private fun rejectOp(op: String, now: SessionState, expect: String): SessionOutcome =
        SessionOutcome.Rejected(
            RejectionReason.INVALID_STATE,
            "$op 在 $now 态被非法转移拒绝：$expect（状态不变、不抛异常）",
        )

    companion object {
        const val DEFAULT_MAX_EVENTS = 2000

        /** 读档：损坏/非法 → Failed(CORRUPT_ARCHIVE)，永不抛异常；恢复会话态恒 STOPPED（只读取档）。clock 须由调用方注入，续录时间戳才可持续走假钟。 */
        fun deserialize(json: String, clock: () -> Long = { System.currentTimeMillis() }): SessionRestore {
            val root = try {
                SessionCodec.parse(json)
            } catch (e: Exception) {
                return SessionRestore.Failed(
                    RejectionReason.CORRUPT_ARCHIVE,
                    "JSON 解析失败: ${e::class.simpleName}: ${e.message}",
                )
            }
            return try {
                SessionCodec.decode(root, clock)
            } catch (e: Exception) {
                SessionRestore.Failed(
                    RejectionReason.CORRUPT_ARCHIVE,
                    "解码异常兜底: ${e::class.simpleName}: ${e.message}",
                )
            }
        }
    }
}
