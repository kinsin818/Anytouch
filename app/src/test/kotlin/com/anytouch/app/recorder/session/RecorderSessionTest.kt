package com.anytouch.app.recorder.session

import com.anytouch.app.locator.TestUi
import com.anytouch.app.recorder.NodeAction
import com.anytouch.app.recorder.NodeActionKind
import com.anytouch.app.recorder.NodeSnapshot
import com.anytouch.app.recorder.RecEvent
import com.anytouch.app.recorder.RecorderCompiler
import com.anytouch.app.recorder.WindowChanged
import com.anytouch.app.recorder.TARGET_PKG
import com.anytouch.app.recorder.OTHER_PKG
import com.anytouch.app.recorder.snapshotOf
import com.anytouch.app.recorder.tree
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * STAGE-22 验收用例集（对 evidence/S2/stage22-acceptance-checklist.md 清单 #1-#10）。
 * 纯 JVM：时间戳全部来自注入假钟，零真实等待；每个断言可证伪（无恒真填充）。
 */
class RecorderSessionTest {

    private var tick = 0L
    private val fakeClock: () -> Long = { tick += 7; tick }
    private fun fixed(ts: Long): () -> Long = { ts }

    private fun session(maxEvents: Int = 100): RecorderSession =
        RecorderSession(TARGET_PKG, clock = fakeClock, maxEvents = maxEvents)

    private fun recording(maxEvents: Int = 100): RecorderSession =
        session(maxEvents).also { assertEquals(SessionOutcome.Accepted, it.start()) }

    private fun click(root: TestUi, ts: Long, indexPath: List<Int>, confirmed: Boolean = true): NodeAction =
        NodeAction(
            kind = NodeActionKind.CLICK,
            snapshot = snapshotOf(root, indexPath),
            timestampMs = ts,
            confirmed = confirmed,
        )

    private fun setText(
        ts: Long,
        text: String? = "默认 文本",
        resourceId: String = "com.x:id/remark",
    ): NodeAction = NodeAction(
        kind = NodeActionKind.SET_TEXT,
        snapshot = NodeSnapshot(resourceId = resourceId, pkg = TARGET_PKG, indexPath = listOf(1)),
        text = text,
        confirmed = true,
        timestampMs = ts,
    )

    private fun scroll(ts: Long): NodeAction = NodeAction(
        kind = NodeActionKind.SCROLL_FORWARD,
        snapshot = NodeSnapshot(resourceId = "com.x:id/list", pkg = TARGET_PKG, indexPath = listOf(2)),
        confirmed = true,
        timestampMs = ts,
    )

    /* ============ 清单#1：合法转移正例 ============ */

    @Test
    fun `S22-1a 合法转移逐条正例 IDLE→RECORDING⇄PAUSED→STOPPED`() {
        val s = session()
        assertEquals(SessionState.IDLE, s.state)
        assertEquals(SessionOutcome.Accepted, s.start())
        assertEquals(SessionState.RECORDING, s.state)
        assertEquals(SessionOutcome.Accepted, s.pause())
        assertEquals(SessionState.PAUSED, s.state)
        assertEquals(SessionOutcome.Accepted, s.resume())
        assertEquals(SessionState.RECORDING, s.state)
        assertEquals(SessionOutcome.Accepted, s.stop())
        assertEquals(SessionState.STOPPED, s.state)

        // PAUSED 亦可直达 STOPPED（⇄ 段两态皆可停）
        val p = session()
        assertEquals(SessionOutcome.Accepted, p.start())
        assertEquals(SessionOutcome.Accepted, p.pause())
        assertEquals(SessionOutcome.Accepted, p.stop())
        assertEquals(SessionState.STOPPED, p.state)

        // STOPPED→RECORDING 续录边（读档态专用）
        val r = session()
        assertEquals(SessionOutcome.Accepted, r.start())
        assertEquals(SessionOutcome.Accepted, r.stop())
        assertEquals(SessionOutcome.Accepted, r.resumeAsRecording())
        assertEquals(SessionState.RECORDING, r.state)
    }

    /* ============ 清单#1：非法转移全表（每态×每操作） ============ */

    @Test
    fun `S22-1b 非法转移全表 14 组 每态每非法操作均拒绝带归因且状态不变不抛`() {
        // 全表 = 4 态 × 5 个转移操作 = 20 组合 − 6 条合法边
        // （start@IDLE, pause@RECORDING, resume@PAUSED, stop@RECORDING, stop@PAUSED,
        //   resumeAsRecording@STOPPED）= 14 组非法组合，逐一枚举不得缺项。
        val cases: List<Triple<String, SessionState, RecorderSession.() -> Unit>> = listOf(
            Triple("start", SessionState.RECORDING) { start() },
            Triple("start", SessionState.PAUSED) { start(); pause() },
            Triple("start", SessionState.STOPPED) { start(); stop() },
            Triple("pause", SessionState.IDLE) {},
            Triple("pause", SessionState.PAUSED) { start(); pause() },
            Triple("pause", SessionState.STOPPED) { start(); stop() },
            Triple("resume", SessionState.IDLE) {},
            Triple("resume", SessionState.RECORDING) { start() },
            Triple("resume", SessionState.STOPPED) { start(); stop() },
            Triple("stop", SessionState.IDLE) {},
            Triple("stop", SessionState.STOPPED) { start(); stop() },
            Triple("resumeAsRecording", SessionState.IDLE) {},
            Triple("resumeAsRecording", SessionState.RECORDING) { start() },
            Triple("resumeAsRecording", SessionState.PAUSED) { start(); pause() },
        )
        assertEquals(14, cases.size, "全表须恰为 14 组非法组合（20−6，缺项即覆盖面不足）")
        val seenCombos = HashSet<String>()
        for ((opName, expectedState, prelude) in cases) {
            val combo = "$opName@${expectedState.name}"
            assertTrue(seenCombos.add(combo), "$combo 重复")
            val s = session()
            s.prelude()
            assertEquals(expectedState, s.state, "$combo 前奏未落到目标态")
            val outcome = when (opName) {
                "start" -> s.start()
                "pause" -> s.pause()
                "resume" -> s.resume()
                "stop" -> s.stop()
                else -> s.resumeAsRecording()
            }
            val rejected = assertIs<SessionOutcome.Rejected>(outcome, combo)
            assertEquals(RejectionReason.INVALID_STATE, rejected.reason, combo)
            assertTrue(rejected.detail.isNotEmpty(), "$combo 拒绝必须带归因 detail")
            assertTrue(rejected.detail.contains(combo.substringBefore('@')), "$combo 归因须点名被拒操作")
            assertEquals(expectedState, s.state, "$combo 被拒后状态不变")
        }
    }

    /* ============ 清单#2：append 仅 RECORDING 受理 ============ */

    @Test
    fun `S22-2 append 在 IDLE PAUSED STOPPED 三态均拒 事件不入缓冲且带归因`() {
        val idle = session()
        val paused = session()
        assertEquals(SessionOutcome.Accepted, paused.start())
        assertEquals(SessionOutcome.Accepted, paused.pause())
        val stopped = session()
        assertEquals(SessionOutcome.Accepted, stopped.start())
        assertEquals(SessionOutcome.Accepted, stopped.stop())

        for ((name, s) in listOf("IDLE" to idle, "PAUSED" to paused, "STOPPED" to stopped)) {
            val e = scroll(ts = 999)
            val rejected = assertIs<SessionOutcome.Rejected>(s.append(e), name)
            assertEquals(RejectionReason.INVALID_STATE, rejected.reason, name)
            assertTrue(rejected.detail.contains(name), "$name 归因须点名当前态")
            assertEquals(0, s.eventList().size, "$name 态被拒事件不得入缓冲")
            assertEquals(listOf(e), s.rejectedEvents, "$name 被拒事件须留审计副本（禁静默吞）")
        }

        // 对照组：RECORDING 态受理
        val rec = recording()
        assertEquals(SessionOutcome.Accepted, rec.append(scroll(ts = 1)))
        assertEquals(1, rec.eventList().size)
        assertEquals(0, rec.rejectedEvents.size)
    }

    /* ============ 清单#3：maxEvents 溢出拒绝并计数 ============ */

    @Test
    fun `S22-3 溢出 第N加1条被拒且归因OVERFLOW 计数可读 前N条完好`() {
        val s = recording(maxEvents = 2)
        val a = scroll(ts = 1)
        val b = scroll(ts = 2)
        val over1 = scroll(ts = 3)
        val over2 = scroll(ts = 4)
        assertEquals(SessionOutcome.Accepted, s.append(a))
        assertEquals(SessionOutcome.Accepted, s.append(b))
        val r1 = assertIs<SessionOutcome.Rejected>(s.append(over1))
        assertEquals(RejectionReason.OVERFLOW, r1.reason)
        val r2 = assertIs<SessionOutcome.Rejected>(s.append(over2))
        assertEquals(RejectionReason.OVERFLOW, r2.reason)
        assertEquals(2, s.overflowCount, "溢出计数逐条累计可读")
        assertEquals(listOf(a, b), s.eventList(), "前 N 条完好且顺序不变")
        assertEquals(listOf(over1, over2), s.rejectedEvents, "禁静默丢：被拒事件留审计副本")
        assertEquals(2, s.maxEvents)

        // 满仓 + 非 RECORDING：态优先级高于溢出，且不污染溢出计数
        assertEquals(SessionOutcome.Accepted, s.stop())
        val r3 = assertIs<SessionOutcome.Rejected>(s.append(scroll(ts = 5)))
        assertEquals(RejectionReason.INVALID_STATE, r3.reason)
        assertEquals(2, s.overflowCount, "非法态拒绝不得计入溢出")
    }

    @Test
    fun `S22-3b maxEvents 为 0 时任何 append 均溢出拒绝 构造参可低至 0`() {
        val s = recording(maxEvents = 0)
        val r = assertIs<SessionOutcome.Rejected>(s.append(scroll(ts = 1)))
        assertEquals(RejectionReason.OVERFLOW, r.reason)
        assertEquals(1, s.overflowCount)
        assertTrue(s.eventList().isEmpty())
    }

    /* ============ 清单#4：serialize 任意态可调 + 往返等值 ============ */

    @Test
    fun `S22-4 serialize 四态各调一次不抛 含targetPkg与state与事件序列 往返结构等值`() {
        val idle = session()
        val rec = recording().also { assertEquals(SessionOutcome.Accepted, it.append(scroll(ts = 5))) }
        val paused = recording().also {
            it.appendWindowChanged(TARGET_PKG, "Act")
            assertEquals(SessionOutcome.Accepted, it.pause())
        }
        val stopped = recording().also {
            it.append(setText(ts = 6))
            assertEquals(SessionOutcome.Accepted, it.stop())
        }
        val stateNames = mutableListOf<String>()
        for (s in listOf(idle, rec, paused, stopped)) {
            val json = s.serialize()
            stateNames += s.state.name
            assertTrue(json.contains("\"targetPkg\":\"$TARGET_PKG\""), "存档须含 targetPkg")
            assertTrue(json.contains("\"state\":\"${s.state.name}\""), "存档须含当前状态")
            assertTrue(json.contains("\"events\":["), "存档须含事件序列字段")
            val loaded = assertIs<SessionRestore.Loaded>(RecorderSession.deserialize(json, fixed(0)), s.state.name)
            assertEquals(s.targetPkg, loaded.session.targetPkg)
            assertEquals(s.eventList(), loaded.session.eventList(), "事件序列往返全等")
            assertEquals(s.overflowCount, loaded.session.overflowCount)
            assertEquals(s.rejectedEvents, loaded.session.rejectedEvents)
        }
        assertEquals(listOf("IDLE", "RECORDING", "PAUSED", "STOPPED"), stateNames, "四态必须各调一次")
        // 防空对空假绿：三态必须确实带着事件过 serialize（IDLE 才是空档）
        assertEquals(listOf(0, 1, 1, 1), listOf(idle, rec, paused, stopped).map { it.eventList().size })
    }

    @Test
    fun `S22-4b 带事件往返 特殊字符与null字段保真 STOPPED档字节全等`() {
        val tree = tree()
        val s = recording()
        val raw = "  两空格 换行\n制表\t引号\"反斜杠\\尖括号<>冒号: 😀  "
        s.appendWindowChanged(TARGET_PKG, "MainActivity")
        s.append(setText(ts = 42, text = raw))
        s.append(click(tree, 50, listOf(2, 1)))
        s.append(scroll(ts = 60))
        val json = s.serialize()
        val loaded = assertIs<SessionRestore.Loaded>(RecorderSession.deserialize(json, fixed(0)))
        assertEquals(s.eventList(), loaded.session.eventList(), "四类事件（含 SET_TEXT 原文）往返全等")
        val restoredText = (loaded.session.eventList()[1] as NodeAction).text
        assertEquals(raw, restoredText, "SET_TEXT 原文经存档往返不 trim 不丢转义")
        // STOPPED 档再序列化字节全等（幂等）
        assertEquals(SessionOutcome.Accepted, s.stop())
        val stoppedJson = s.serialize()
        val again = assertIs<SessionRestore.Loaded>(RecorderSession.deserialize(stoppedJson, fixed(0)))
        assertEquals(stoppedJson, again.session.serialize(), "STOPPED 档 JSON 往返字节全等")
    }

    @Test
    fun `S22-4c 溢出计数与被拒副本随存档往返`() {
        val s = recording(maxEvents = 1)
        s.append(scroll(ts = 1))
        val rejected = scroll(ts = 2)
        s.append(rejected)
        assertEquals(1, s.overflowCount)
        val loaded = assertIs<SessionRestore.Loaded>(RecorderSession.deserialize(s.serialize(), fixed(0)))
        assertEquals(1, loaded.session.overflowCount)
        assertEquals(listOf(rejected), loaded.session.rejectedEvents)
    }

    /* ============ 清单#5：deserialize 回 STOPPED 只读 + resumeAsRecording 续录 ============ */

    @Test
    fun `S22-5 读档恒 STOPPED只读 RECORDING或PAUSED档不复活 append被拒 resumeAsRecording后受理`() {
        val live = recording()
        live.appendWindowChanged(TARGET_PKG)
        live.append(scroll(ts = 7))
        val jsonFromRecording = live.serialize()
        // PAUSED 档须"先录后停"：在 PAUSED 态 append 本就被拒（见 S22-2），事后补录只会得到空档
        val pausedSession = recording()
        pausedSession.appendWindowChanged(TARGET_PKG)
        pausedSession.append(scroll(ts = 7))
        assertEquals(SessionOutcome.Accepted, pausedSession.pause())
        assertEquals(2, pausedSession.eventList().size, "PAUSED 档前奏：RECORDING 期两条已入缓冲")
        val pausedArchive = pausedSession.serialize()
        for ((name, json) in listOf("RECORDING 档" to jsonFromRecording, "PAUSED 档" to pausedArchive)) {
            val loaded = assertIs<SessionRestore.Loaded>(RecorderSession.deserialize(json, fixed(0)), name)
            assertEquals(SessionState.STOPPED, loaded.session.state, "$name 读档后必须 STOPPED，不得复活成文件里的态")
            assertEquals(2, loaded.session.eventList().size, "$name 事件序列完整读回")
            val rejected = assertIs<SessionOutcome.Rejected>(loaded.session.append(scroll(ts = 8)), name)
            assertEquals(RejectionReason.INVALID_STATE, rejected.reason, "$name 只读取档：append 必须被拒")
            assertEquals(2, loaded.session.eventList().size, "被拒事件不得入缓冲")
            // 普通 resume 不算续录
            assertIs<SessionOutcome.Rejected>(loaded.session.resume(), name)
            assertEquals(SessionOutcome.Accepted, loaded.session.resumeAsRecording(), name)
            assertEquals(SessionState.RECORDING, loaded.session.state)
            assertEquals(SessionOutcome.Accepted, loaded.session.append(scroll(ts = 9)))
            assertEquals(3, loaded.session.eventList().size)
        }
    }

    @Test
    fun `S22-5b 续录后的时间戳来自读档时注入的 clock 而非墙钟`() {
        val live = RecorderSession(TARGET_PKG, clock = { 100L }, maxEvents = 10)
        live.start()
        live.appendWindowChanged(TARGET_PKG)
        val archive = live.serialize()
        var r = 0L
        val loaded = assertIs<SessionRestore.Loaded>(
            RecorderSession.deserialize(archive, clock = { r += 5; r }),
        )
        assertEquals(SessionOutcome.Accepted, loaded.session.resumeAsRecording())
        assertEquals(SessionOutcome.Accepted, loaded.session.appendWindowChanged(OTHER_PKG))
        val events = loaded.session.eventList()
        assertEquals(2, events.size)
        assertEquals(100L, events[0].timestampMs, "原档时间戳不被读档改写")
        assertEquals(5L, events[1].timestampMs, "续录时间戳来自注入 clock")
        assertEquals(10L, loaded.session.nextTimestampMs())
    }

    /* ============ 清单#6：只 import 引用冻结件（本用例反射校验事件类型同一性） ============ */

    @Test
    fun `S22-6 事件结构复用冻结 RecEvent 子类 读档产物仍是同一批类型实例`() {
        val s = recording()
        s.appendWindowChanged(TARGET_PKG, "T")
        s.append(setText(ts = 3))
        s.append(scroll(ts = 4))
        val loaded = assertIs<SessionRestore.Loaded>(RecorderSession.deserialize(s.serialize(), fixed(0)))
        val src = s.eventList()
        val back = loaded.session.eventList()
        assertEquals(src.size, back.size)
        for (i in back.indices) {
            assertEquals(src[i]::class, back[i]::class, "events[$i] 类型须为冻结 RecEvent 子类，非 session 包自定义镜像")
            assertIs<RecEvent>(back[i])
        }
        assertIs<com.anytouch.app.recorder.WindowChanged>(back[0])
        assertIs<NodeAction>(back[1])
        // 冻结类由 recorder 根包加载（本包未重新声明同名类型）
        assertEquals(
            "com.anytouch.app.recorder.WindowChanged",
            back[0]::class.qualifiedName,
        )
    }

    /* ============ 清单#7：deserialize 损坏输入 → 失败类型不崩 ============ */

    @Test
    fun `S22-7 损坏或非法存档 8 种形态均返回 CORRUPT_ARCHIVE 不抛异常`() {
        val valid = recording().let { it.append(scroll(ts = 3)); it }.serialize()
        val truncated = valid.substring(0, valid.length / 2)
        val cases = listOf(
            "空串" to "",
            "截断" to truncated,
            "顶层非对象" to "[]",
            "非 JSON 字面量" to "not json at all",
            "targetPkg 类型错" to valid.replace("\"targetPkg\":\"$TARGET_PKG\"", "\"targetPkg\":7"),
            "未知事件子类型" to valid.replace("\"kind\":\"SCROLL_FORWARD\"", "\"kind\":\"TELEPORT\""),
            "indexPath 非数字" to valid.replace("\"indexPath\":[2]", "\"indexPath\":[\"x\"]"),
            "版本超前" to valid.replace("\"version\":${SessionCodec.VERSION}", "\"version\":999"),
        )
        assertEquals(8, cases.size)
        for ((label, json) in cases) {
            val failed = assertIs<SessionRestore.Failed>(RecorderSession.deserialize(json, fixed(0)), label)
            assertEquals(RejectionReason.CORRUPT_ARCHIVE, failed.reason, label)
            assertTrue(failed.detail.isNotEmpty(), "$label 失败必须带归因 detail")
        }
        // 向前兼容：顶层未知键按 contracts 冻结口径（ignoreUnknownKeys=true）忽略
        val withExtra = valid.replaceFirst("{", "{\"futureField\":{\"a\":1},")
        assertIs<SessionRestore.Loaded>(RecorderSession.deserialize(withExtra, fixed(0)), "未知顶层键应被容忍")
    }

    @Test
    fun `S22-7b 损坏档读失败后原会话不受影响 可继续正常录制`() {
        val s = recording()
        s.append(scroll(ts = 1))
        assertIs<SessionRestore.Failed>(RecorderSession.deserialize("{", fixed(0)))
        assertEquals(SessionState.RECORDING, s.state, "反序列化失败不得影响在录会话")
        assertEquals(SessionOutcome.Accepted, s.append(scroll(ts = 2)))
    }

    /* ============ 清单#8：注入 clock 决定时间戳，零真实等待 ============ */

    @Test
    fun `S22-8 clock 为会话唯一时间源 连续取号单调递增且测试零真实等待`() {
        val s = RecorderSession(TARGET_PKG, clock = fakeClock, maxEvents = 10)
        s.start()
        assertEquals(7L, s.nextTimestampMs(), "第 1 次取号")
        assertEquals(SessionOutcome.Accepted, s.appendWindowChanged(TARGET_PKG, "A"))
        assertEquals(14L, (s.eventList().single() as WindowChanged).timestampMs, "appendWindowChanged 内部自取第 2 号")
        assertEquals(21L, s.nextTimestampMs(), "时钟只由本会话推进，取号连续")
        // 调用方自备时间戳：会话原样保存，不改写
        val given = scroll(ts = 31)
        assertEquals(SessionOutcome.Accepted, s.append(given))
        assertEquals(31L, s.eventList().last().timestampMs)
        assertTrue(RecorderSession.DEFAULT_MAX_EVENTS > 0, "缺省缓冲上限须为正数")
        // 缺省 clock 走墙钟（纯 JVM 可用，非测试断言主径）
        val wall = RecorderSession(TARGET_PKG)
        wall.start()
        assertTrue(wall.appendWindowChanged(TARGET_PKG) is SessionOutcome.Accepted)
        assertTrue((wall.eventList().single() as WindowChanged).timestampMs > 0)
    }

    /* ============ 清单#9：金链 —— 录制→存档→读档→续录→compile 与一次性等值 ============ */

    @Test
    fun `S22-9 金链 断点续录后compile产物与一次性compile逐字段等值`() {
        val tree = tree()
        val head: List<RecEvent> = listOf(
            WindowChanged(TARGET_PKG, "MainActivity", timestampMs = 10),
            click(tree, 100, listOf(0)),
            click(tree, 260, listOf(0)), // 同路径间隔 160ms<300ms → 去抖合并（验 merged 也等值）
            setText(300),
        )
        val tail: List<RecEvent> = listOf(
            scroll(ts = 5500),
            click(tree, 6000, listOf(2, 1)),
            click(tree, 6100, listOf(1), confirmed = false), // 高危未确认 → 安全丢弃归因
            WindowChanged(OTHER_PKG, timestampMs = 6200),
            click(tree, 6300, listOf(1)), // 窗口已漂走 → 页面漂移归因
        )
        val all = head + tail
        val baseline = RecorderCompiler().compile(all, TARGET_PKG)

        // 断点续录链：录 head → pause → serialize → deserialize(STOPPED 只读) → resumeAsRecording → 录 tail
        val live = RecorderSession(TARGET_PKG, clock = fixed(0), maxEvents = 50)
        assertEquals(SessionOutcome.Accepted, live.start())
        head.forEach { assertEquals(SessionOutcome.Accepted, live.append(it)) }
        assertEquals(SessionOutcome.Accepted, live.pause())
        val whilePaused = assertIs<SessionOutcome.Rejected>(live.append(scroll(ts = 4999)))
        assertEquals(RejectionReason.INVALID_STATE, whilePaused.reason, "暂停期事件不得混入")
        val archive = live.serialize()
        val loaded = assertIs<SessionRestore.Loaded>(RecorderSession.deserialize(archive, fixed(0)))
        assertEquals(SessionOutcome.Accepted, loaded.session.resumeAsRecording())
        tail.forEach { assertEquals(SessionOutcome.Accepted, loaded.session.append(it)) }
        val resumed = loaded.session.eventList()

        assertEquals(all, resumed, "续录后事件序列与原序列全等（暂停期被拒事件未混入）")
        val out = RecorderCompiler().compile(resumed, TARGET_PKG)
        assertEquals(baseline.actions, out.actions, "compile 步骤逐字段等值")
        assertEquals(baseline.drops, out.drops, "丢弃归因逐字段等值")
        assertEquals(baseline.merged, out.merged, "去抖计数等值")
        // 防"空对空"假绿：基准本身必须真有步骤、真有合并、真有丢弃
        assertTrue(out.actions.isNotEmpty(), "金链必须真产出步骤")
        assertEquals(1, out.merged)
        assertEquals(2, out.drops.size)
        assertEquals(4, out.actions.size)
        // actionId 携带输入列表下标（0 基），head 首元素是 WindowChanged → 首步骤为 rec-0001
        assertEquals(RecorderCompiler.ACTION_ID_PREFIX + "0001", out.actions.first().actionId)
        assertEquals(RecorderCompiler().compile(all, TARGET_PKG).actions.first().actionId, out.actions.first().actionId)
    }

    /* ============ 清单#10：断言总数（本文件 @Test 数 ≥10，此处再补一条独立用例） ============ */

    @Test
    fun `S22-10 会话只读视图 eventList 返回拷贝 后续录制不回灌旧视图`() {
        val s = recording()
        val e1 = scroll(ts = 1)
        s.append(e1)
        val view = s.eventList()
        assertEquals(listOf(e1), view)
        assertEquals(SessionOutcome.Accepted, s.append(scroll(ts = 2)))
        assertEquals(1, view.size, "旧视图不得随后续录制增长（证明是拷贝而非活引用）")
        assertEquals(2, s.eventList().size)
        assertEquals(SessionOutcome.Accepted, s.stop())
        assertEquals(SessionState.STOPPED, s.state)
    }
}
