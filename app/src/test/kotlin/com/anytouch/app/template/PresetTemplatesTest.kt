package com.anytouch.app.template

import com.anytouch.app.recorder.decodeActions
import com.anytouch.app.recorder.encodeActions
import com.anytouch.app.recorder.firstUnsupportedModelAction
import com.anytouch.byok.executorSupportedActionTypes
import com.anytouch.contracts.Action
import com.anytouch.contracts.ActionSafety
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 预制模板的结构锁（S5-a，老板 S5-R7 分档验收里"Gmail/Discord 降为装载面+结构判据"的那一半）。
 *
 * 为什么全在 JVM 锁：模板是**盘上资产 + 注册表**两份东西，脱钩是最容易发生的腐化形态
 * （改了 JSON 忘了改表 / 加了文件没登记 / 删了文件表还在）。设备轮只能证明"当下这一枚 apk 里
 * 恰好能跑"，锁不住"库与资产永远一致"——那是编译期断言的活。
 *
 * 工作目录口径：Gradle 单元测试的 cwd 是 app 模块目录（与 `PresetTemplateLibrary.assetPath`
 * 的 assets 相对路径同源），仓库级文件用 `../../` 上溯——与 `ModelLedgerGate` 的既有用例同法。
 */
class PresetTemplatesTest {

    private val assetDir = File("src/main/assets/templates")

    private fun assetFile(template: PresetTemplate): File = File(assetDir, template.id + ".json")

    @Test
    fun `注册表与资产目录双向对拍`() {
        val files = assetDir.listFiles { f -> f.name.endsWith(".json") }?.map { it.name }
            ?: error("资产目录不可读: ${assetDir.absolutePath}")
        assertEquals(
            "注册表与 templates/*.json 必须逐一对应（多一个少一个都算脱钩）",
            files.sorted(),
            PresetTemplateLibrary.all.map { "${it.id}.json" }.sorted(),
        )
        val ids = PresetTemplateLibrary.all.map { it.id }
        assertEquals("id 不许重复", ids.size, ids.distinct().size)
        val labels = PresetTemplateLibrary.all.map { it.label }
        assertEquals("展示名不许重复", labels.size, labels.distinct().size)
        // assetPath 必须指回"templates/<id>.json"：路径也是被锁的事实，不许有人悄悄加新目录
        PresetTemplateLibrary.all.forEach {
            assertEquals("templates/${it.id}.json", it.assetPath)
        }
    }

    @Test
    fun `每个模板解码成功且词表全在支持集内`() {
        PresetTemplateLibrary.all.forEach { template ->
            val actions = decodeActions(assetFile(template).readText())
            assertTrue("${template.id} 空账", actions.isNotEmpty())
            // 与落账口同一判据同一支持集：这里绿而设备轮收 RefusedUnsupportedType = 锁失效
            val unsupported = firstUnsupportedModelAction(actions, executorSupportedActionTypes)
            assertNull("${template.id} 含执行器跑不动的 type: $unsupported", unsupported)
            val ids = actions.map { it.actionId }
            assertEquals("${template.id} action_id 重复", ids.size, ids.distinct().size)
        }
    }

    @Test
    fun `全账节点引用零坐标`() {
        PresetTemplateLibrary.all.forEach { template ->
            val raw = assetFile(template).readText()
            // 原始文本层也扫一遍：坐标禁入是红线级判据，序列化器宽容也不许它混进资产
            assertTrue("${template.id} 原始 JSON 出现 target 键", !raw.contains("\"target\""))
            assertTrue("${template.id} 原始 JSON 出现坐标键", !raw.contains("\"x\":") && !raw.contains("\"y\":"))
            decodeActions(raw).forEach { action ->
                assertEquals("${template.id}/${action.actionId} 只许 node 来源", "node", action.source)
                assertNull("${template.id}/${action.actionId} 禁坐标", action.target)
            }
        }
    }

    @Test
    fun `click 步必须双门放行且带定位子`() {
        PresetTemplateLibrary.all.forEach { template ->
            decodeActions(assetFile(template).readText()).forEach { action ->
                when (action.type) {
                    "click" -> {
                        assertTrue(
                            "${template.id}/${action.actionId} click 必须显式 viewport_ok+click_enabled（缺省 false=不可执行）",
                            action.safety.viewportOk && action.safety.clickEnabled,
                        )
                        assertLocator(template.id, action)
                    }
                    "type_text" -> {
                        assertLocator(template.id, action)
                        val input = action.value?.let { (it["input"] as? kotlinx.serialization.json.JsonPrimitive)?.content }
                        assertTrue("${template.id}/${action.actionId} type_text 缺 input", !input.isNullOrBlank())
                    }
                    "wait" -> {
                        val ms = action.value?.let { (it["ms"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull() }
                        assertTrue("${template.id}/${action.actionId} wait 缺 ms 或越界($ms)", ms != null && ms in 200..5_000)
                    }
                    "scroll" -> {
                        val direction = action.value?.let { (it["direction"] as? kotlinx.serialization.json.JsonPrimitive)?.content }
                        assertTrue("${template.id}/${action.actionId} scroll 缺 direction", !direction.isNullOrBlank())
                    }
                    else -> error("${template.id} 出现词表外 type=${action.type}")
                }
            }
        }
    }

    private fun assertLocator(templateId: String, action: Action) {
        val value = action.value
        assertNotNull("$templateId/${action.actionId} 无 value 块", value)
        val locatorKeys = listOf("resource_id", "text", "content_desc", "path")
        val hasLocator = locatorKeys.any { key ->
            (value!![key] as? kotlinx.serialization.json.JsonPrimitive)?.content?.isNotBlank() == true
        }
        assertTrue("$templateId/${action.actionId} 缺定位子（resource_id/text/content_desc/path 至少一个非空）", hasLocator)
    }

    @Test
    fun `encode 往返逐字幂等 V3准入对模板放行`() {
        // 装载口把 encodeActions(decoded) 交给建议流；下一次"执行任务"的 V-3 比对要求
        // 框内文本 == 现算 encode(账)。若资产解码后再编码不稳定，模板自己放不出来（与编译产物同一 JVM 锁）。
        PresetTemplateLibrary.all.forEach { template ->
            val actions = decodeActions(assetFile(template).readText())
            val once = encodeActions(actions)
            assertEquals("${template.id} encode→decode 不不动点", actions, decodeActions(once))
            assertEquals("${template.id} encode 不幂等", once, encodeActions(decodeActions(once)))
        }
    }

    @Test
    fun `登录前置账与S5-R7口径逐字一致`() {
        assertEquals(
            mapOf(
                "photos_cleanup" to false, // 相册不需账号（首启备份弹框可跳过——摸底实证见 evidence/S5/s5a-target-recon.md）
                "gmail_cleanup" to true,
                "discord_checkin" to true,
            ),
            PresetTemplateLibrary.all.associate { it.id to it.requiresUserSignIn },
        )
        val hint = PresetTemplateLibrary.signInHint()
        assertTrue(hint.contains("Gmail 清理"))
        assertTrue(hint.contains("Discord 签到"))
        assertTrue("相册不该被点名要登录", !hint.contains("相册"))
        assertTrue(hint.contains("不做登录操作"))
    }

    @Test
    fun `DEVICE_PROVEN_ON_AVD 标记必须有磁盘实证背书`() {
        // 反虚标锁：谁把档位翻成"设备已实证"，就必须先在 evidence/S5/ 落下同名实证文件，
        // 否则本用例即红——账面口径由编译期兜住，不靠人自觉（假绿同罪）。
        PresetTemplateLibrary.all
            .filter { it.verification == TemplateVerification.DEVICE_PROVEN_ON_AVD }
            .forEach { template ->
                val proven = File("../evidence/S5")
                    .listFiles { f -> f.isFile && f.name.startsWith("${template.id}-device-proven") }
                    ?: emptyArray()
                assertTrue(
                    "${template.id} 标了 DEVICE_PROVEN_ON_AVD 但 evidence/S5/ 无 ${template.id}-device-proven* 实证文件",
                    proven.isNotEmpty(),
                )
            }
    }

    @Test
    fun `负例 越权type会被落账口判出`() {
        // 锁判据本体仍咬得住（红线的"能 FAIL"性质）：把 key 混进一本模板账，词表档必须点名第一条。
        val poison = Action(
            actionId = "press_enter",
            type = "key",
            value = null,
            source = "node",
            safety = ActionSafety(viewportOk = true, clickEnabled = true),
        )
        val good = decodeActions(assetFile(PresetTemplateLibrary.byId("photos_cleanup")!!).readText())
        val verdict = firstUnsupportedModelAction(good + poison, executorSupportedActionTypes)
        assertNotNull(verdict)
        assertEquals(good.size, verdict!!.index)
        assertEquals("key", verdict.type)
    }
}
