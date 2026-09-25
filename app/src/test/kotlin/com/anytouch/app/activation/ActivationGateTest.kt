package com.anytouch.app.activation

import com.anytouch.app.RepeatPlan
import com.anytouch.app.RepeatPolicy
import com.anytouch.app.RepeatVerdict
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 付费墙面貌的 JVM 锁（S5-f 军令 §3 + 附页 §2 的"三进墙 / 绝不进墙"清单）。
 *
 * 这份锁要同时钉住两件事，缺一件都不算锁：
 * 1. **进墙的三枚**：真动用才拦（单发逐字放行）、被拦时话术上屏且声明"什么都没发生"、解锁那一瞬陈旧话术作废；
 * 2. **绝不进墙的那些**：安全面（高危确认与超时默认拒、停止球、执行期零网络）、无障碍门禁、AI 编译与 BYOK、
 *    录制、模板装载、派发与执行本体、失败重试、步序账的删/改名/移序、磁盘洁净化——
 *    一条都不许被付费状态波及。第 2 条是**主窗自钉**（老板可覆），钉法是读源码的结构锁：
 *    墙内符号只准出现在白名单文件里，任何一处把 `ProGate` 接进执行器或安全面，本用例当场红。
 *
 * 理由写在附页 §2 那一节里，不复述：把"要不要问用户"绑进"买没买"是拿用户安全换营收。
 */
class ActivationGateTest {

    private val features = ProFeature.values().toList()

    // ---- 1. 判据矩阵：三枚功能 × 动用与否 × 激活与否 ----

    @Test
    fun `三枚功能在真动用且未激活时全部拦下 其余四格全部放行`() {
        features.forEach { feature ->
            assertEquals(
                ProGate.NOT_ACTIVATED,
                proGateOf(feature, exercised = true, activated = false),
                "$feature 真动用了却没拦：墙成了摆设",
            )
            assertNull(proGateOf(feature, exercised = true, activated = true), "$feature 解锁后仍被拦=付费无效")
            assertNull(
                proGateOf(feature, exercised = false, activated = false),
                "$feature 没用到的请求必须放行：判据 5 的对照格",
            )
            assertNull(proGateOf(feature, exercised = false, activated = true))
        }
    }

    @Test
    fun `重复循环只有真动用轮数才拦 单发与默认值逐字不受影响`() {
        // 这一格把"exercised 从哪来"钉在判据侧：轮数=1（含框里留着默认值）等于没动用
        fun exercisedFor(repetitions: String?): Boolean {
            val verdict = RepeatPolicy.parse(repetitions, null, askWithoutPrompt = false)
            assertTrue(verdict is RepeatVerdict.Accepted, "$repetitions 应当解析成功")
            return !(verdict as RepeatVerdict.Accepted).plan.isSingleShot
        }
        listOf(null, "", "1").forEach { assertFalse(exercisedFor(it), "单发不得算动用轮数") }
        listOf("2", "100").forEach { assertTrue(exercisedFor(it), "$it 是动用轮数") }

        val single = RepeatPlan(repetitions = 1)
        val loop = RepeatPlan(repetitions = 3)
        assertNull(proGateOf(ProFeature.REPEAT_LOOP, !single.isSingleShot, activated = false), "未激活跑单发必须照跑")
        assertEquals(
            ProGate.NOT_ACTIVATED,
            proGateOf(ProFeature.REPEAT_LOOP, !loop.isSingleShot, activated = false),
            "未激活跑三轮必须拦",
        )
    }

    // ---- 2. 陈旧话术的过期边（假红与假绿同罪） ----

    @Test
    fun `解锁成功那一瞬付费墙红字作废 没解锁则原样留着`() {
        assertNull(
            proRejectionAfterChange(ProGate.NOT_ACTIVATED, activated = true),
            "已经解锁还挂着 Upgrade to Pro=假红：骗一个付了钱的人",
        )
        assertEquals(ProGate.NOT_ACTIVATED, proRejectionAfterChange(ProGate.NOT_ACTIVATED, activated = false))
        assertNull(proRejectionAfterChange(null, activated = true), "没有红字时不许凭空造一条")
        assertNull(proRejectionAfterChange(null, activated = false))
    }

    // ---- 3. 话术：单一真源、全英文、不含内部档名、必说"什么都没发生" ----

    private val upgradeSentences: Map<ProFeature, String> get() = features.associateWith {
        ProGate.NOT_ACTIVATED.userCopy(it)
    }

    @Test
    fun `三枚入口各得一句自己的话术 同一句头一个来源`() {
        val copies = upgradeSentences.values.toList()
        assertEquals(3, copies.toSet().size, "三处共用一句话术：用户在步序账前看到的和在存档面看到的是同一句空话")
        copies.forEach {
            assertTrue(it.startsWith(UPGRADE_MARK), "军令 §3 指定字样必须在句首：$it")
            assertTrue(it.contains("Nothing was") || it.contains("No step was"), "被拦必须声明什么都没发生（静默=黑洞）：$it")
            assertTrue(it.contains("Activate"), "得给出下一步动作，不只是拒绝：$it")
        }
        assertEquals("Upgrade to Pro", UPGRADE_MARK, "军令指定的字面")
    }

    @Test
    fun `上屏的每一句都不含中文 也不含内部档名与黑话`() {
        val all = buildList {
            add(ActivationCopy.BUTTON)
            add(ActivationCopy.TITLE)
            add(ActivationCopy.FIELD_LABEL)
            add(ActivationCopy.CONFIRM)
            add(ActivationCopy.CANCEL)
            add(ActivationCopy.SCOPE)
            add(ActivationCopy.unlocked("B1CE"))
            addAll(ActivationVerdict.values().map { ActivationCopy.refusal(it) })
            addAll(ProFeature.values().map { ProGate.NOT_ACTIVATED.userCopy(it) })
            addAll(ProFeature.values().map { lockedHint(it) })
        }
        assertTrue(all.isNotEmpty())
        all.forEach { copy ->
            assertFalse(copy.any { it in '一'..'龥' }, "上屏文案出现中文（军令 §5）：$copy")
            listOf(
                "NOT_ACTIVATED", "UNLOCKED", "WRITE_FAILED", "BAD_CHARSET", "BAD_LENGTH", "BAD_PREFIX",
                "BAD_SEPARATOR", "CHECKSUM", "REPEAT_LOOP", "SAVED_TASKS", "MANUAL_STEP_INSERT",
                "ProGate", "ProFeature", "ActivationVerdict", "exercised", "activated", "filesDir", "flag.txt",
            ).forEach {
                assertFalse(copy.contains(it), "上屏文案里出现内部档名 $it（v1.0.2 去黑话同一条律）：$copy")
            }
        }
        assertNotNull(all.singleOrNull { it.contains("never goes online") }, "对话框必须讲清不联网（军令 §4 的对外那一半）")
    }

    @Test
    fun `三处短提示各不相同且都以指定字样开头`() {
        val hints = features.map { lockedHint(it) }
        assertEquals(3, hints.toSet().size)
        hints.forEach { assertTrue(it.startsWith(UPGRADE_MARK), it) }
        // 短提示不能把"没解锁"说成"功能坏了"：每句都得让人知道这是付费面
        hints.forEach { assertTrue(it.contains(" to "), "提示里得有『Upgrade to Pro to …』那半句：$it") }
    }

    @Test
    fun `解锁回显只到末四位 全码的任何一片都不进文案`() {
        val copy = ActivationCopy.unlocked("B1CE")
        assertTrue(copy.contains("B1CE"), "回显得让人认得出是哪台机器解的锁：$copy")
        assertFalse(copy.contains("ANY-"), "解锁态文案里出现了可拼回的前缀形状")
        // 只有对话框那句格式占位符能带 ANY-XXXX：它是"该长成什么样"，不是一枚码
        assertTrue(ActivationCopy.FIELD_LABEL.contains("ANY-XXXX-XXXX-XXXX"), "输入框没把格式打在脸上")
    }

    // ---- 4. 绝不进墙：结构锁（读主源码，白名单之外不得出现墙内符号） ----

    private val mainRoot = File("src/main/kotlin/com/anytouch/app")

    /** 墙内符号：出现这些名字的那一行就是在"问买没买"。 */
    private val wallMarkers = listOf(
        "ProGate", "ProFeature", "proGateOf", "UPGRADE_MARK", "lockedHint", "maskedTail",
        "ActivationStore", "ActivationCode", "ActivationCopy", "ActivationState", "ActivationDisk",
        "ActivationVerdict", "proRejection", "proBlockedSaved", "proUnlocked", "AppState.activated",
        "NOT_ACTIVATED", "activation_code", "activation_reset",
    )

    /** 白名单：本批唯一被付费状态波及的文件，一格不多。 */
    private val allowedWallFiles = setOf(
        "AppState.kt",
        "MainActivity.kt",
        "activation/ActivationCode.kt",
        "activation/ActivationGate.kt",
        "activation/ActivationStore.kt",
        "platform/AndroidActivationDisk.kt",
        "recorder/SavedTasks.kt",
        "recorder/StepEditing.kt",
        "recorder/session/RecorderStore.kt",
        "ui/StepListUi.kt",
    )

    @Test
    fun `付费墙符号只住在白名单文件里 安全面执行面编译面一律未被波及`() {
        val scanned = mainRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue(scanned.size >= 40, "扫描目录没走全（读到 ${scanned.size} 份）：本锁会因此假绿")
        val offenders = scanned.filter { file ->
            val rel = file.relativeTo(mainRoot).path.replace('\\', '/')
            wallMarkers.any { file.readText().contains(it) } && rel !in allowedWallFiles
        }
        assertTrue(
            offenders.isEmpty(),
            "这些文件问起了『买没买』（绝不进墙清单被破）：" + offenders.joinToString { it.name },
        )
        // 白名单不许虚设：一格空挂就是"清单已过期"的形态
        val present = scanned.filter { file -> wallMarkers.any { file.readText().contains(it) } }
            .map { it.relativeTo(mainRoot).path.replace('\\', '/') }.toSet()
        assertEquals(allowedWallFiles, present, "墙内文件清单与实际不符：多出来的该删（白名单虚设=锁失效）")
    }

    /**
     * 逐名点验那几条最要紧的"绝不进墙"：上面那条白名单已经盖住，这里点名是因为
     * 老板判据 §2 写的就是这些具体名字——将来有人把墙接进去，红的得是他那一格的名字。
     */
    @Test
    fun `点名验证：高危确认 停止球 重试 派发 录制 模板 编译 BYOK 都不认付费状态`() {
        val files = listOf(
            "safety/HighRiskMatcher.kt",
            "safety/HighRiskPanelCopy.kt",
            "safety/KillSwitch.kt",
            "safety/SafetyQueueRunner.kt",
            "executor/StepRetryPolicy.kt",
            "executor/NodeTaskRunner.kt",
            "platform/AccessibilityGate.kt",
            "recorder/session/RecorderSession.kt",
            "recorder/capture/CaptureBridge.kt",
            "template/TemplateLoader.kt",
            "compile/ByokGateway.kt",
            "service/AnytouchAccessibilityService.kt",
        )
        files.forEach { rel ->
            val text = File(mainRoot, rel).readText()
            val hit = wallMarkers.filter { text.contains(it) }
            assertTrue(hit.isEmpty(), "$rel 里出现了 $hit：这一格在墙外，付费状态一进它的话术就变了")
        }
    }

    @Test
    fun `进墙的三枚各由唯一写口把关 置灰只是提示`() {
        // 结构锁的"正面"那一半：三处入口都必须在**通道**上判，而不是只把钮变灰
        val main = File(mainRoot, "MainActivity.kt").readText()
        assertTrue(main.contains("if (proGateForRepeat(plan, via)) return"), "派发口没判墙：置灰成了唯一防线")
        assertTrue(main.contains("if (proBlockedSaved("), "存档四通道没判墙")
        val store = File(mainRoot, "recorder/session/RecorderStore.kt").readText()
        assertTrue(store.contains("AppState.activated.value"), "唯一写口 applyEdit 没把激活态送进判据")
        // 三枚钮的置灰只是提示，且共用同一句 hint 的来源（不抄三份）
        val ui = File(mainRoot, "ui/StepListUi.kt").readText()
        assertTrue(ui.contains("enabled = proUnlocked"), "『+ Step』钮没随激活态置灰")
    }
}
