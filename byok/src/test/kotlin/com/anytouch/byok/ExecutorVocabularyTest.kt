package com.anytouch.byok

import com.anytouch.contracts.Action
import com.anytouch.contracts.ActionType
import com.anytouch.contracts.ContractJson
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * S3-F/F2 的 JVM 锁：动作词表**一份真源、三处对拍**。
 *
 * 四条判据各自要堵的形态：
 * - 真源自身：成员就是执行器那个 `when` 的四条 `ActionType` 常量，元素是 `String`（S31-B5），
 *   且 `key` 必须已不在里面（S31-B3 收紧词表的落点；给执行面补 KEY 是 §3 明确不做）。
 * - **提示词 vs 校验器**：提示词那一半句里出现的 type 名集合必须逐字等于真源。旧版两边各抄一份，
 *   改一侧就脱节——这才是 E5i 的正身。
 * - **真源 vs 冒烟脚本**：`scripts/byok-smoke.sh` 的 E5i 那行 `grep -vw -e …` 是第三份手抄，
 *   搬不进 Kotlin 就当字符串钉住（派单书 §1-F2-1 的第①条路）。真源一改而脚本未改，这条先红，
 *   脚本不会继续当一道假门禁。
 * - **过期话术**：E5i 的红字里不许再留"还在等裁决"，也不许不指真源——门禁里留着过期边本身就是假红。
 */
class ExecutorVocabularyTest {

    private class Fake(val reply: String) : LlmTransport {
        override fun complete(systemPrompt: String, userPrompt: String): String = reply
    }

    private fun compile(raw: String): CompileResult = DslCompiler(Fake(raw)).compile("任意意图")

    private fun actionJson(type: String, valueJson: String): String =
        """[{"action_id":"a1","type":"$type","source":"node","value":$valueJson,""" +
            """"safety":{"viewport_ok":true,"click_enabled":true}}]"""

    /** 仓库根的 `scripts/byok-smoke.sh`：从测试工作目录向上找；找不到即红（读不到文件不许装绿）。 */
    private fun smokeScript(): File {
        var dir: File? = File(System.getProperty("user.dir")).takeIf { it.exists() }
        while (dir != null) {
            val candidate = File(dir, "scripts/byok-smoke.sh")
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        throw AssertionError(
            "找不到 scripts/byok-smoke.sh（user.dir=${System.getProperty("user.dir")}）" +
                "——钉脚本的判据不许在读不到文件时装绿",
        )
    }

    // ---------- 1. 真源自身 ----------

    @Test
    fun `真源恰为执行器四常量 且 key 已从授权集消失`() {
        // 编译期就钉住"元素类型是 String"（S31-B5）：这一行若变成 Set<ActionType> 直接编不过。
        val asStrings: Set<String> = executorSupportedActionTypes
        assertEquals(
            setOf(ActionType.WAIT, ActionType.CLICK, ActionType.SCROLL, ActionType.TYPE_TEXT),
            asStrings,
            "真源成员必须逐字等于 NodeTaskRunner 那个 when 的四条常量，多一条少一条都算词表又分叉",
        )
        assertFalse(ActionType.KEY in asStrings, "key 回来了＝E5i 那颗雷回来了（执行面对它收 EXECUTOR_ERROR）")
        assertEquals(4, asStrings.size, "集合若含重复或被换成 List，与脚本的对拍会失真")
    }

    @Test
    fun `派发判定逐格锁死`() {
        for (type in listOf(ActionType.WAIT, ActionType.CLICK, ActionType.SCROLL, ActionType.TYPE_TEXT)) {
            assertTrue(supportsExecutorDispatch(type), "$type 必须判派得动")
        }
        for (type in listOf(ActionType.KEY, ActionType.SUBMIT, ActionType.SELECT_DROPDOWN, ActionType.MOVE, "teleport")) {
            assertFalse(supportsExecutorDispatch(type), "$type 判派得动＝把跑不动的动作放行")
        }
        assertFalse(supportsExecutorDispatch("wait "), "不做 trim：契约里没有带空格的 type，宽一次就是第二套口径")
        assertFalse(supportsExecutorDispatch(""))
    }

    // ---------- 2. 提示词与校验器同源 ----------

    @Test
    fun `提示词的 type 半句逐字等于真源拼出的那半句`() {
        val clause = Regex("""type 只允许 ([^；\n]+)""").find(CompilerPrompt.SYSTEM)
            ?: throw AssertionError("提示词里找不到「type 只允许 …」那半句——它若被改写，本条判据要跟着改口径，不许放过")
        assertEquals(executorSupportedTypesPromptClause(), clause.value, "半句不是由真源拼出来的")
        val named = Regex("\"([a-z_]+)\"").findAll(clause.groupValues[1]).map { it.groupValues[1] }.toSet()
        assertEquals(
            executorSupportedActionTypes,
            named,
            "提示词报给模型的动作集与校验器认的动作集不是同一份＝两边各说一套（E5i 正身）",
        )
        // 反向锁：提示词整体不许再教模型怎么写 key/back 动作（旧版第 4 条与第 9 条都在教）
        assertFalse(Regex("\"key\":\"back").containsMatchIn(CompilerPrompt.SYSTEM), "提示词还在示范 back/key＝模型照样交出走不动的步")
        assertFalse(CompilerPrompt.SYSTEM.contains("改走 back/key"), "第 9 条若还指着 key 这条路，收紧就只是半句")
    }

    @Test
    fun `校验器与真源同认一份授权集`() {
        assertEquals(executorSupportedActionTypes, DslCompiler.ALLOWED_TYPES, "ALLOWED_TYPES 若又变回一份手抄集合，真源就成了摆设")
    }

    // ---------- 3. 真源与冒烟脚本（第三份抄本钉死） ----------

    @Test
    fun `冒烟脚本 E5i 的字面集合与真源逐字相等`() {
        val text = smokeScript().readText()
        val line = Regex("""grep -vw((?:\s+-e\s+[A-Za-z_][A-Za-z0-9_]*)+)""").find(text)
            ?: throw AssertionError("脚本里找不到 E5i 那行 grep -vw -e …：判据被改写后必须同步本用例，不许留一条没人核对的门禁")
        val literals = Regex("""-e\s+([A-Za-z_][A-Za-z0-9_]*)""").findAll(line.groupValues[1])
            .map { it.groupValues[1] }.toSet()
        assertEquals(
            executorSupportedActionTypes,
            literals,
            "脚本手抄的词表与真源脱节：真源=[" + executorSupportedTypesSerialized() + "] 脚本=[$literals]。" +
                "脱节的脚本会判绿而产物跑不动（E5i 假门禁形态）",
        )
    }

    @Test
    fun `脚本 E5i 话术已指向裁决与真源 不留过期边`() {
        val e5i = smokeScript().readText().lines().filter { it.contains("E5i") }.joinToString("\n")
        assertTrue(e5i.isNotEmpty(), "脚本里已经没有 E5i 了？那这条钉法要换，不许静默消失")
        assertFalse(
            e5i.contains("本轮不擅自改任一侧") || e5i.contains("需一句裁决收口"),
            "门禁话术里留着还在等裁决＝过期边（裁决 S31-B3 已下），过期话术本身就是假红",
        )
        assertTrue(e5i.contains("S31-B3"), "E5i 必须指到裁决号")
        assertTrue(e5i.contains("ExecutorVocabulary"), "E5i 必须指到真源文件，读脚本的人才不用猜名单住在哪")
    }

    // ---------- 4. 编译侧当场拒（收紧词表的兑付面） ----------

    @Test
    fun `模型交 key 整条编译即拒 不再产出跑不动的步`() {
        val r = compile(actionJson(ActionType.KEY, """{"key":"back"}"""))
        assertIs<CompileResult.Reject>(r, "旧版这一条是 Ok——那正是 E5i 抓到的雷（授权了执行面跑不动的动作）")
        assertEquals("validate", r.stage)
        assertTrue(r.detail.contains(ActionType.KEY), "归因要点名被拒的是哪一条 type：$r")
        assertTrue(r.detail.contains(ActionType.CLICK), "归因要把支持集一起报出去，用户才知道模型该改什么：$r")
    }

    @Test
    fun `wait 由真源派生成为合法动作 形状判据按执行器口径`() {
        assertIs<CompileResult.Ok>(compile(actionJson(ActionType.WAIT, """{"ms":120}""")))
        assertIs<CompileResult.Ok>(compile(actionJson(ActionType.WAIT, """{}""")), "省略 ms 由执行器按默认 500 走，不是编译侧要管的事")
        assertIs<CompileResult.Reject>(compile(actionJson(ActionType.WAIT, """{"ms":"很久"}""")))
        assertIs<CompileResult.Reject>(compile(actionJson(ActionType.WAIT, """{"ms":-1}""")))
    }

    @Test
    fun `四类合法动作逐类仍各守形状判据`() {
        assertIs<CompileResult.Ok>(compile(actionJson(ActionType.CLICK, """{"text":"Bluetooth"}""")))
        assertIs<CompileResult.Ok>(compile(actionJson(ActionType.SCROLL, """{"resource_id":"android:id/list","direction":"forward"}""")))
        assertIs<CompileResult.Ok>(compile(actionJson(ActionType.TYPE_TEXT, """{"text":"搜索","input":"hi"}""")))
        assertIs<CompileResult.Reject>(compile(actionJson(ActionType.SCROLL, """{"resource_id":"x","direction":"sideways"}""")))
        assertIs<CompileResult.Reject>(compile(actionJson(ActionType.CLICK, """{"text":""}""")))
    }

    @Test
    fun `真源每个成员都仍是契约里解得开的 Action 字面`() {
        // 正向配套：真源不是随手字符串，成员必须与契约 ActionType 常量同一份字面（换了写法这条先红）。
        val decoded = executorSupportedActionTypes.map { type ->
            val value = if (type == ActionType.WAIT) """{"ms":1}""" else """{"text":"x","input":"y","resource_id":"r","direction":"forward"}"""
            ContractJson.instance
                .decodeFromString(ListSerializer(Action.serializer()), actionJson(type, value))
                .single().type
        }.toSet()
        assertEquals(executorSupportedActionTypes, decoded)
    }
}
