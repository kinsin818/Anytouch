package com.anytouch.app.recorder

import com.anytouch.byok.executorSupportedActionTypes
import com.anytouch.contracts.Action
import com.anytouch.contracts.ActionSafety
import com.anytouch.contracts.ActionSource
import com.anytouch.contracts.ActionType
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 手动补步骤（S5-e 要求 3 / 老板 09-24 三裁③）的纯函数锁 + 结构锁。
 *
 * 三裁③原文口径："第一版只允许加节点/输入/等待类步骤，不开放坐标点击入口，坐标容易不准，后面再补"。
 * 所以这一族的判据分两层看：
 * - **该拒的拒得干净**（类型白名单、必填线索、数字字段、越界插入位）——漏一格就是往账里埋一条必红的步骤；
 * - **禁坐标要可证伪**（EXECUTION §3-7"不是嘴上说没有"）——所以既有运行时锁（产物 `target==null`、
 *   编码后账内无坐标键），也有结构锁（手动步的类型、UI 面板、adb 注入段落三处都表达不出坐标）。
 */
class StepInsertionTest {

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

    private fun steps(count: Int = 3) = (1..count).map { i ->
        act(ActionType.CLICK, "s$i", RecorderCompiler.TEXT_KEY to "row$i")
    }

    private fun click(name: String = "manual-1", clue: String = "com.x:id/bt") =
        ManualStep(ActionType.CLICK, name, resourceId = clue)

    private fun insert(index: Int, step: ManualStep = click()) = StepEdit.Insert(index, step)

    // ---------- 本体四档 ----------
    @Test
    fun `三类各自成形 其余类型一律 INSERT_TYPE`() {
        assertNull(manualStepGateOf(click()))
        assertNull(
            manualStepGateOf(ManualStep(ActionType.TYPE_TEXT, "t", text = "Search", input = "hi")),
        )
        assertNull(manualStepGateOf(ManualStep(ActionType.WAIT, "w")))
        assertNull(manualStepGateOf(ManualStep(ActionType.WAIT, "w", waitMs = "0")))
        listOf(ActionType.SCROLL, ActionType.KEY, ActionType.MOVE, "", "click ").forEach {
            assertEquals(StepEditGate.INSERT_TYPE, manualStepGateOf(ManualStep(it, "n", text = "x", input = "y")), "$it 不该进手动面")
        }
    }

    @Test
    fun `点击与输入必须有节点线索 wait 不需要`() {
        assertEquals(StepEditGate.INSERT_CLUE, manualStepGateOf(ManualStep(ActionType.CLICK, "n")))
        assertEquals(StepEditGate.INSERT_CLUE, manualStepGateOf(ManualStep(ActionType.CLICK, "n", resourceId = "   ")))
        assertEquals(
            StepEditGate.INSERT_CLUE,
            manualStepGateOf(ManualStep(ActionType.TYPE_TEXT, "n", input = "hi")),
            "只有 input 也算无线索：input 是要打的字，不是地址（线索档先于载荷档）",
        )
        // 四条线索各自一条即够（与执行器 `toLocatorRequest` 的"四者有一即可"同形）
        listOf(
            ManualStep(ActionType.CLICK, "n", resourceId = "com.x:id/bt"),
            ManualStep(ActionType.CLICK, "n", text = "Bluetooth"),
            ManualStep(ActionType.CLICK, "n", contentDesc = "toggle"),
            ManualStep(ActionType.CLICK, "n", path = "*[0]>*[1]"),
            ManualStep(ActionType.TYPE_TEXT, "n", resourceId = "com.x:id/et", input = "hi"),
        ).forEach { assertNull(manualStepGateOf(it), "$it 该放行") }
        // wait 按定义没有线索
        assertNull(manualStepGateOf(ManualStep(ActionType.WAIT, "w")))
    }

    @Test
    fun `输入步必须有要打的字 空白即拒`() {
        listOf("", "  ", "\t").forEach {
            assertEquals(
                StepEditGate.INSERT_EMPTY_INPUT,
                manualStepGateOf(ManualStep(ActionType.TYPE_TEXT, "n", text = "field", input = it)),
            )
        }
    }

    @Test
    fun `数字字段只认非负整数 空白按缺省走`() {
        listOf("", "0", "3", " 7 ").forEach {
            assertNull(manualStepGateOf(click().copy(instance = it)), "$it 合法")
            assertNull(manualStepGateOf(ManualStep(ActionType.WAIT, "w", waitMs = it)), "$it 合法")
        }
        listOf("-1", "abc", "1.5", "1e3", "0x2").forEach {
            assertEquals(StepEditGate.INSERT_BAD_NUMBER, manualStepGateOf(click().copy(instance = it)), "$it 不得当成 0 放行")
            assertEquals(StepEditGate.INSERT_BAD_NUMBER, manualStepGateOf(ManualStep(ActionType.WAIT, "w", waitMs = it)))
        }
    }

    @Test
    fun `多缺陷同体时按优先级归因 一档一因`() {
        // 类型不对时谈线索没有意义（scroll 的线索另有一套规矩）；线索缺失先于载荷；载荷先于数字
        assertEquals(
            StepEditGate.INSERT_TYPE,
            manualStepGateOf(ManualStep(ActionType.SCROLL, "n")),
        )
        assertEquals(
            StepEditGate.INSERT_CLUE,
            manualStepGateOf(ManualStep(ActionType.TYPE_TEXT, "n", input = "  ")),
        )
        assertEquals(
            StepEditGate.INSERT_EMPTY_INPUT,
            manualStepGateOf(ManualStep(ActionType.TYPE_TEXT, "n", text = "f", instance = "-2")),
        )
    }

    // ---------- 账本侧 ----------
    @Test
    fun `追加位等于账长是合法的 越界位一律拒`() {
        val s = steps()
        assertEquals(StepEditGate.READY, stepEditGateOf(s, insert(s.size)))
        assertEquals(StepEditGate.READY, stepEditGateOf(s, insert(0)))
        assertEquals(StepEditGate.READY, stepEditGateOf(s, insert(2)))
        listOf(-1, s.size + 1, 99).forEach {
            assertEquals(StepEditGate.OUT_OF_RANGE, stepEditGateOf(s, insert(it)), "$it 不是可插入位")
        }
    }

    @Test
    fun `空账本不许从零手搓 三裁③给的是补步骤`() {
        assertEquals(StepEditGate.EMPTY_LEDGER, stepEditGateOf(emptyList(), insert(0)))
        // 同一条 EMPTY_LEDGER 也仍管删改移：这一档不因"新添了插步"而放宽
        assertEquals(StepEditGate.EMPTY_LEDGER, stepEditGateOf(emptyList(), StepEdit.Remove(0)))
    }

    @Test
    fun `空名先于类型档 与改名共用同一格归因`() {
        assertEquals(
            StepEditGate.BLANK_NAME,
            stepEditGateOf(steps(), insert(0, ManualStep(ActionType.SCROLL, "  "))),
            "空名与非法类型同时存在时先说名字（改名面同一档，不许另立一份）",
        )
    }

    @Test
    fun `执行中与编译中排在手动四档之前`() {
        val bad = insert(9, ManualStep(ActionType.SCROLL, ""))
        assertEquals(StepEditGate.RUNNING, stepEditGateOf(steps(), bad, running = true, compileBusy = true))
        assertEquals(StepEditGate.COMPILING, stepEditGateOf(steps(), bad, running = false, compileBusy = true))
        assertEquals(StepEditGate.OUT_OF_RANGE, stepEditGateOf(steps(), bad, running = false, compileBusy = false))
    }

    @Test
    fun `手动四档是请求绑定档 状态跃迁不许顺手抹掉`() {
        listOf(
            StepEditGate.INSERT_TYPE,
            StepEditGate.INSERT_CLUE,
            StepEditGate.INSERT_EMPTY_INPUT,
            StepEditGate.INSERT_BAD_NUMBER,
        ).forEach {
            assertEquals(
                it,
                editRejectionAfterStateChange(it, running = false, compileBusy = false),
                "$it 绑在那一次插入请求上，不该随状态消失",
            )
        }
    }

    @Test
    fun `门禁与原语严格对齐 非 READY 的插步 applyStepEdit 必抛`() {
        val s = steps()
        listOf(
            s to insert(9),
            s to insert(0, ManualStep(ActionType.SCROLL, "n", text = "x")),
            s to insert(0, click().copy(instance = "-1")),
            s to insert(0, ManualStep(ActionType.CLICK, "n")),
            s to insert(0, ManualStep(ActionType.TYPE_TEXT, "n", text = "f")),
            s to insert(0, ManualStep(ActionType.CLICK, " ", text = "x")),
        ).forEach { (actions, edit) ->
            assertTrue(stepEditGateOf(actions, edit) != StepEditGate.READY, "$edit 必须被拦")
            assertFailsWith<IllegalArgumentException> { applyStepEdit(actions, edit) }
        }
    }

    @Test
    fun `插入位置逐档正确 其余步骤一字不动`() {
        val s = steps()
        val added = buildManualStep(click(name = "mid", clue = "com.x:id/mid"))
        val edited = applyStepEdit(s, insert(1, click(name = "mid", clue = "com.x:id/mid")))
        assertEquals(listOf("s1", "mid", "s2", "s3"), edited.map { it.actionId })
        assertEquals(listOf(s[0], s[1], s[2]), listOf(edited[0], edited[2], edited[3]), "插一步不许波及其他步骤")
        assertEquals(added, edited[1])
        assertEquals(listOf("mid", "s1", "s2", "s3"), applyStepEdit(s, insert(0, click(name = "mid", clue = "m"))).map { it.actionId })
        assertEquals(listOf("s1", "s2", "s3", "mid"), applyStepEdit(s, insert(3, click(name = "mid", clue = "m"))).map { it.actionId })
        // 插完再删回来：账本与原来逐字段同形（可逆=插步没有偷偷改写别的步骤）
        assertEquals(s, applyStepEdit(edited, StepEdit.Remove(1)))
    }

    @Test
    fun `插完的账仍与任务框同形 建议照常重发`() {
        val s = steps()
        val edited = applyStepEdit(s, insert(1, click()))
        val json = assertNotNull(suggestionAfterEdit(edited))
        assertEquals(edited, decodeActions(json))
        assertEquals(encodeActions(edited), json, "编码两份账=V-3 会把合法插步判成陈旧")
    }

    // ---------- 产物形状 ----------
    @Test
    fun `产物 target 恒为空 safety 两门与编译器同形 source 为节点`() {
        val all = listOf(
            buildManualStep(click()),
            buildManualStep(ManualStep(ActionType.TYPE_TEXT, "t", text = "f", input = " hi ")),
            buildManualStep(ManualStep(ActionType.WAIT, "w", waitMs = "250")),
            buildManualStep(ManualStep(ActionType.WAIT, "w")),
        )
        all.forEach {
            assertNull(it.target, "手动步带坐标=三裁③破口")
            assertEquals(ActionSource.NODE, it.source)
            assertEquals(ActionSafety(viewportOk = true, clickEnabled = true, requiresTransition = false), it.safety)
        }
        assertEquals(" hi ", all[1].value?.get(RecorderCompiler.INPUT_KEY)?.toString()?.trim('"'), "输入内容不 trim")
    }

    @Test
    fun `value 键集按类型各有其形 一列不加`() {
        assertEquals(
            setOf(RecorderCompiler.RESOURCE_ID_KEY),
            buildManualStep(click()).value?.keys,
        )
        assertEquals(
            setOf(RecorderCompiler.TEXT_KEY, RecorderCompiler.INPUT_KEY),
            buildManualStep(ManualStep(ActionType.TYPE_TEXT, "t", text = "f", input = "i")).value?.keys,
        )
        assertEquals(setOf(WAIT_MS_KEY), buildManualStep(ManualStep(ActionType.WAIT, "w", waitMs = "1")).value?.keys)
        assertNull(buildManualStep(ManualStep(ActionType.WAIT, "w")).value?.get(WAIT_MS_KEY), "省略 ms 不落键（执行器缺省 500）")
        // 与所选类型无关的字段一律忽略：wait 带 text 不进账（账里不留执行器读不到的"依据"）
        assertEquals(setOf(WAIT_MS_KEY), buildManualStep(ManualStep(ActionType.WAIT, "w", waitMs = "1", text = "x")).value?.keys)
        // instance：0 与省略同形故不落，>0 才落，且落的是数字
        assertNull(buildManualStep(click().copy(instance = "0")).value?.get(INSTANCE_KEY))
        assertEquals(3, buildManualStep(click().copy(instance = "3")).value?.get(INSTANCE_KEY).toString().toInt())
        assertTrue(
            buildManualStep(click().copy(resourceId = " v ")).value.toString().contains("\"v\""),
            "线索字段 trim 后入账",
        )
    }

    @Test
    fun `手动步编码后账内零坐标`() {
        val ledger = steps() + buildManualStep(click())
        val json = encodeActions(ledger)
        listOf("x", "y", "point", "coordinate", "coordinates", "bounds", "offset").forEach { key ->
            assertFalse(json.contains("\"$key\":"), "编码产物出现坐标样键 $key：$json")
        }
        assertEquals(4, Regex("\"target\":null").findAll(json).count(), "每一步的 target 都必须显式为 null（与录制步同形）：$json")
        assertFalse(Regex("\"target\":\\{").containsMatchIn(json), "出现非空 target：坐标入口回潮")
    }

    @Test
    fun `手动可插的三条必须都是执行器跑得动的类型`() {
        // 落账口那条词表闸只管机器产物，编辑写口不经它——所以手动面的子集关系只能由这条锁兜住：
        // 插一条执行器跑不动的步骤=账上多一颗必 EXECUTOR_ERROR 的雷，而且要等到放跑才炸。
        assertTrue(
            manualInsertableTypes.all { it in executorSupportedActionTypes },
            "手动类型超出了执行器真源：$manualInsertableTypes vs $executorSupportedActionTypes",
        )
        assertTrue(ActionType.SCROLL in executorSupportedActionTypes && ActionType.SCROLL !in manualInsertableTypes)
    }

    // ---------- 结构锁：禁坐标可证伪（EXECUTION §3-7） ----------
    private val mainRoot = File("src/main/kotlin/com/anytouch/app")

    private fun src(rel: String): String = File(mainRoot, rel).readText()

    /** 只留代码行：KDoc 与注释本来就要写明"禁了什么"，扫注释会自己咬自己。 */
    private fun codeOnly(text: String): String = text.lines()
        .filterNot { it.trim().startsWith("*") || it.trim().startsWith("//") || it.trim().startsWith("/*") }
        .joinToString("\n")

    /**
     * 坐标入口的字面形态。用词边界正则而不是子串：`rowIndex =` 里那个 "x =" 不是坐标，
     * 子串扫法会把正常代码判成红项——判据误伤一次，下次真出现坐标时就没人信这条锁了。
     */
    private val coordinateMarkers = listOf(
        Regex("\\bPoint\\("),
        Regex("\\btarget\\s*="),
        Regex("\\b[xy]\\s*="),
        Regex("\\.[xy]\\b"),
        Regex("\\bcoordinat"),
        Regex("\\bbounds\\b"),
        Regex("\\boffset"),
        Regex("\\btapAt"),
    )

    @Test
    fun `手动步类型 面板与注入通道三处都表达不出坐标`() {
        // 判据层是 `target` 唯一被写下的地方，而它写下的那一格恒为 null——所以这里不禁"出现 target"，
        // 禁的是"target 被赋成别的"：整文件只准有一处赋值，且赋的是 null。
        val judge = codeOnly(src("recorder/StepInsertion.kt"))
        val assignments = Regex("target = ([^,\\n]+)").findAll(judge).map { it.groupValues[1] }.toList()
        assertEquals(listOf("null"), assignments, "判据层给 target 赋了别的值（或开了第二个赋值点）")
        assertFalse(judge.contains("Point("), "判据层直接构造 Point")
        listOf("ui/StepListUi.kt" to "面板").forEach { (rel, what) ->
            val code = codeOnly(src(rel))
            coordinateMarkers.forEach { assertFalse(it.containsMatchIn(code), "$what 的代码行出现 ${it.pattern}：坐标入口回潮") }
        }
        val block = src("MainActivity.kt").substringAfter("手动补步骤注入通道").substringBefore("AI 编译注入通道")
        assertTrue(block.length > 600, "锚点段落没抓到：注入面结构变了，本锁要重新对拍")
        coordinateMarkers.forEach { assertFalse(it.containsMatchIn(block), "注入通道出现 ${it.pattern}：坐标入口回潮") }
        assertEquals(1, Regex("StepEdit\\.Insert\\(").findAll(block).count(), "插步开了第二个写口")
        assertEquals(10, Regex("EXTRA_STEP_INSERT_").findAll(block).count(), "注入字段与面板九格+插入位不再同号")
    }

    @Test
    fun `注入通道只转调不自带门禁`() {
        val block = src("MainActivity.kt").substringAfter("手动补步骤注入通道").substringBefore("AI 编译注入通道")
        listOf("manualStepGateOf", "stepEditGateOf", "buildManualStep", "compileBusy", "AppState.running").forEach {
            assertFalse(block.contains(it), "通道里出现 $it：判据抄第二份=两处会分叉")
        }
        assertTrue(block.contains("RecorderStore.applyEdit"), "通道没走唯一写口")
        assertTrue(block.contains("?: -1"), "插入位缺失必须送哨兵按越界拒，不许静默当 0")
    }

    @Test
    fun `面板上屏字面量全英文 无残留中文`() {
        val cjk = Regex("[\\u3000-\\u303F\\u3040-\\u9FFF\\uF900-\\uFAFF\\uFF00-\\uFFEF]")
        // 取"引号之间"的片段：注释里的中文不是上屏文案，扫注释会自己咬自己（codeOnly 已滤掉注释行）
        val literals = codeOnly(src("ui/StepListUi.kt")).split('"').filterIndexed { i, _ -> i % 2 == 1 }
            .filter { it.isNotBlank() }
        assertTrue(literals.size >= 20, "字面量一条没抓到：抽取口径与文件不合，本锁等于没锁（抓到 ${literals.size} 条）")
        literals.forEach { assertFalse(cjk.containsMatchIn(it), "上屏字面量含中文：$it") }
    }

    @Test
    fun `执行器读得到的键就是手动写得出的键`() {
        // 手动面能填的字段一列不加、也不能少到"填了没人读"：与执行器解码口逐字对拍
        val runner = src("executor/NodeTaskRunner.kt")
        listOf("resource_id", "text", "content_desc", "path", "instance", "input", "ms").forEach {
            assertTrue(runner.contains("\"$it\""), "执行器解码口不再认 $it：手动面板那一格成了死字段")
        }
        assertTrue(runner.contains("longParam(action.value, \"ms\", 500)"), "wait 缺省值口径变了")
        assertEquals(500L, DEFAULT_WAIT_MS, "标题里的缺省毫秒数与执行器不同=编辑页与回放差一秒")
    }

    // ---------- 话术与标题 ----------
    @Test
    fun `九档拒因话术互不雷同 手动四档各说各的因`() {
        val gates = StepEditGate.values().filter { it != StepEditGate.READY }
        assertEquals(9, gates.size, "门禁加档请同时加话术与用例（这里数的是拒因档总数）")
        val copies = gates.map { assertNotNull(it.userCopy(), "$it 必须给用户话术（L2-③ 禁静默禁用）") }
        assertEquals(copies.size, copies.distinct().size, "两档共用一句=用户不知道该改哪一格")
        assertTrue(StepEditGate.INSERT_TYPE.userCopy()!!.contains("click"))
        assertTrue(StepEditGate.INSERT_TYPE.userCopy()!!.contains("wait"))
        assertTrue(StepEditGate.INSERT_CLUE.userCopy()!!.contains("coordinate"))
        assertTrue(StepEditGate.INSERT_EMPTY_INPUT.userCopy()!!.contains("empty"))
        assertTrue(StepEditGate.INSERT_BAD_NUMBER.userCopy()!!.contains("milliseconds"))
        assertTrue(StepEditGate.RUNNING.userCopy()!!.contains("adding"), "执行中禁编辑现在也管插步，话术须点到")
    }

    @Test
    fun `九档拒因话术零中文`() {
        // 与 scripts/ui-english-sweep.sh 同一段码位（CJK + 假名 + 全角标点），两侧同一口径才叫一条判据
        val cjk = Regex("[\\u3000-\\u303F\\u3040-\\u9FFF\\uF900-\\uFAFF\\uFF00-\\uFFEF]")
        StepEditGate.values().mapNotNull { it.userCopy() }.forEach {
            assertFalse(cjk.containsMatchIn(it), "拒因话术含中文（上屏面须全英文）：$it")
        }
    }

    @Test
    fun `标题认得 wait 的毫秒数 不写 no clue`() {
        assertEquals("wait · 500ms", stepLabel(act(ActionType.WAIT)))
        assertEquals("wait · 1200ms", stepLabel(act(ActionType.WAIT, clues = arrayOf(WAIT_MS_KEY to "1200"))))
        assertEquals("click · bt", stepLabel(act(ActionType.CLICK, clues = arrayOf(RecorderCompiler.RESOURCE_ID_KEY to "com.x:id/bt"))))
    }

    @Test
    fun `opName 四操作各自可辨（日志留痕要能归因）`() {
        assertEquals(
            listOf("remove", "rename", "move", "insert"),
            listOf(
                StepEdit.Remove(0),
                StepEdit.Rename(0, "x"),
                StepEdit.Move(0, 1),
                insert(0),
            ).map { it.opName() },
        )
    }
}
