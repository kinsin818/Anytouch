package com.anytouch.app.service

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S5-d 军令第 6 条的**结构锁**："安全门禁/执行器/编译链路零改动，只是在外面包一层循环逻辑"。
 *
 * 为什么用读源码而不是只靠设备冒烟：循环本体住在 `AnytouchAccessibilityService`（Android 侧），
 * JVM 里跑不动它，设备面只能验"这一轮行为对不对"。而"判据有没有被抄成第二份""循环有没有漏进
 * 门禁本体"是**结构**问题——下一个人顺手在 UI 里写一句 `if (n > 100)`、或在 matcher 里加一句 round
 * 判断，当期设备面照样全绿，账面却已经是两套真值。这一格把那种改法当场变红
 * （同 `scripts/ci-local.sh` 那一族：脚本锁结构，用例锁语义）。
 *
 * 路径按模块目录解析（同 `PresetTemplatesTest` 读 assets 的口径：测试工作目录 = `:app` 模块根）。
 */
class RepeatWiringLockTest {

    private val mainRoot = File("src/main/kotlin/com/anytouch/app")

    private fun src(rel: String): String = File(mainRoot, rel).readText()

    private fun ktFiles(dir: String): List<File> = File(mainRoot, dir)
        .walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    @Test
    fun `轮数与间隔的四条边界只住在一个文件里`() {
        assertTrue("测试工作目录不对，取不到 $mainRoot（本锁会在空文件集上假绿）", mainRoot.isDirectory)
        val owners = mainRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { file ->
                val t = file.readText()
                listOf("MIN_REPETITIONS", "MAX_REPETITIONS", "MIN_INTERVAL_SEC", "MAX_INTERVAL_SEC")
                    .any { t.contains("$it =") }
            }.map { it.name }.toList()
        assertEquals("边界值被抄成第二份真值（判据必须只在 RepeatLoop.kt）：$owners", listOf("RepeatLoop.kt"), owners)
    }

    @Test
    fun `门禁执行器定位编译四个目录里没有任何重复执行字样`() {
        val markers = listOf("RepeatPlan", "RepeatPolicy", "RepeatConfirmCache", "repetitions", "repeat_", "S5DSMOKE")
        for (dir in listOf("safety", "executor", "locator", "compile")) {
            val files = ktFiles(dir)
            assertTrue("$dir/ 一个 .kt 都没扫到＝候选集为空＝假锁", files.isNotEmpty())
            for (file in files) {
                val hits = markers.filter { file.readText().contains(it) }
                assertTrue("${file.path} 里出现了重复执行字样 $hits（军令第 6 条：循环只许包在外面）", hits.isEmpty())
            }
        }
    }

    @Test
    fun `循环仍经同一张面板问高危 且急停只在整跑开头复位一次`() {
        val service = src("service/AnytouchAccessibilityService.kt")
        // 面板这条唯一来路不许被循环绕过（跳过面板的判据在 RepeatConfirmCache，且跳过必写日志）
        // 只数**调用点**：注释里提到函数名是给人读的，不算第二份来路（`(` 就是"真被调用"的形态）
        assertEquals(
            "awaitSecondConfirm 的调用点必须仍只有一个（多了就是循环自带了一条确认来路）",
            1,
            Regex("ui\\.awaitSecondConfirm\\(").findAll(service).count(),
        )
        assertEquals(
            "KillSwitch.reset 只许在整跑入口一次：轮间复位等于把急停球清空后继续跑剩下的轮",
            1,
            Regex("KillSwitch\\.reset\\(\\)").findAll(service).count(),
        )
        // 循环的走向判据一律转调纯函数，服务侧不得自带第二份
        for (judge in listOf("loopStepAfter(", "haltReasonBeforeRound(", "RepeatConfirmCache(", "nextJitterOffset(")) {
            assertTrue("服务侧没转调 $judge（判据可能被判进了接线里）", service.contains(judge))
        }
        assertFalse(
            "服务侧不许自己判轮次/间隔边界（越界判据住 RepeatPolicy）",
            Regex("""\b(repetitions|intervalSec)\s*(<|>|<=|>=)\s*\d""").containsMatchIn(service),
        )
    }

    @Test
    fun `计划随请求进总线 派发口只问一次判据`() {
        val appState = src("AppState.kt")
        assertTrue("TaskRequest 必须携带计划（派发时的数字钉死在请求上，服务不许回读界面）",
            appState.contains("plan: RepeatPlan"))
        val service = src("service/AnytouchAccessibilityService.kt")
        assertFalse("服务侧读注入 extras 就是第二份真值", service.contains("repeat_count"))
        val ui = src("MainActivity.kt")
        assertFalse("界面自带边界判据=第二份真值",
            Regex("""\b(repetitions|intervalSec)\s*(<|>|<=|>=)\s*\d""").containsMatchIn(ui))
        assertEquals(
            "RepeatPolicy.parse 在界面上只许一个调用点（注入通道与手点共用同一解析口）",
            1,
            Regex("RepeatPolicy\\.parse\\(").findAll(ui).count(),
        )
    }
}
