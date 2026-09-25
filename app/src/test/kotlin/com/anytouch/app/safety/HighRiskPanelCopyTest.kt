package com.anytouch.app.safety

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 高危二次确认面板的**文案**锁（老板 S5-R10 裁 1："别把 DELETE:delete 这种东西给买家看"）。
 *
 * 只锁显示面，一格都不判判定链——哪一步触发闸由 [HighRiskMatcherTest] 那批用例管，
 * 本类的存在意义是：以后谁再往面板上拼 ruleId / 枚举名 / 词表原文，当场红。
 */
class HighRiskPanelCopyTest {

    private fun verdictFor(category: HighRiskCategory, field: ProbeField = ProbeField.TEXT) =
        SafetyVerdict.RequiresSecondConfirm(
            matchedRule = HighRiskRule(ruleId = "${category.name}:删除", category = category, keyword = "删除"),
            allMatches = listOf(
                RuleMatch(
                    HighRiskRule(ruleId = "${category.name}:删除", category = category, keyword = "删除"),
                    field,
                ),
            ),
        )

    @Test
    fun `七类各有一句人话且互不重复`() {
        val headlines = HighRiskCategory.entries.map { HighRiskPanelCopy.headline(it) }
        headlines.forEach { assertTrue(it.isNotBlank(), "有空类别的人话缺失") }
        assertEquals(headlines.size, headlines.distinct().size, "两类共用一句：面板会把转账说成删除")
    }

    @Test
    fun `面板正文不露规则id枚举名与命中字段黑话`() {
        val banned = buildList {
            add("matched field")
            add("High-risk action")
            add("ruleId")
            HighRiskCategory.entries.forEach { add(it.name) }
            ProbeField.entries.forEach { add(it.name) }
            HardcodedHighRiskRules.RULES.forEach { add(it.ruleId) }
        }
        for (category in HighRiskCategory.entries) {
            val body = HighRiskPanelCopy.body(verdictFor(category))
            for (token in banned) {
                assertFalse(body.contains(token), "$category 的面板正文露出黑话片段：$token → $body")
            }
        }
    }

    @Test
    fun `面板正文纯英文无中文残留`() {
        for (category in HighRiskCategory.entries) {
            val body = HighRiskPanelCopy.body(verdictFor(category))
            assertTrue(
                body.none { it.code in 0x4E00..0x9FFF || it.code in 0x3000..0x303F },
                "$category 面板正文含中日标点字符：$body",
            )
        }
    }

    @Test
    fun `面板说清不答就不跑与取消确认两枚钮`() {
        val body = HighRiskPanelCopy.body(verdictFor(HighRiskCategory.DELETE))
        assertTrue(
            body.contains("nothing runs") || body.contains("won't run"),
            "面板没交代超时默认拒（安全模型的关键一句）：$body",
        )
        assertEquals("Cancel", HighRiskPanelCopy.CANCEL)
        assertEquals("Confirm and run", HighRiskPanelCopy.CONFIRM)
    }
}
