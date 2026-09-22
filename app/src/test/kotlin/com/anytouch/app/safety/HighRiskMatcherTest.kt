package com.anytouch.app.safety

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class HighRiskMatcherTest {

    private val matcher = HighRiskMatcher.default()

    // ---- 每类命中例（军令七类中英双语，逐类正反） ----

    @Test
    fun `七类中文关键词逐一命中`() {
        val cases = listOf(
            HighRiskCategory.PAYMENT to "请输入支付密码再付款",
            HighRiskCategory.TRANSFER to "向好友转账",
            HighRiskCategory.SEND to "发送给联系人",
            HighRiskCategory.DELETE to "删除该条目",
            HighRiskCategory.PASSWORD to "请输入口令",
            HighRiskCategory.PURCHASE to "立即购买",
            HighRiskCategory.CONFIRM_ORDER to "确认订单",
        )
        for ((category, text) in cases) {
            val verdict = matcher.inspect(SimpleNodeProbe(text = text))
            assertIs<SafetyVerdict.RequiresSecondConfirm>(verdict, "漏判: $text")
            assertEquals(category, verdict.matchedRule.category, "主判类别错: $text")
        }
    }

    @Test
    fun `英文关键词大小写不敏感命中`() {
        val cases = listOf(
            HighRiskCategory.PAYMENT to "PAY NOW",
            HighRiskCategory.TRANSFER to "Transfer Money",
            HighRiskCategory.SEND to "Send a file",
            HighRiskCategory.DELETE to "Delete Account",
            HighRiskCategory.PASSWORD to "Enter your Password",
            HighRiskCategory.PURCHASE to "Buy again",
            HighRiskCategory.CONFIRM_ORDER to "Proceed to Checkout",
        )
        for ((category, text) in cases) {
            val verdict = matcher.inspect(SimpleNodeProbe(text = text))
            assertIs<SafetyVerdict.RequiresSecondConfirm>(verdict, "漏判: $text")
            assertEquals(category, verdict.matchedRule.category, "主判类别错: $text")
        }
    }

    // ---- 非命中例 ----

    @Test
    fun `普通设置节点三字段均未命中则放行`() {
        val verdict = matcher.inspect(
            SimpleNodeProbe(
                resourceId = "com.android.settings:id/wifi_entry",
                text = "WLAN",
                contentDesc = "无线网络开关",
            ),
        )
        assertIs<SafetyVerdict.Clear>(verdict)
    }

    @Test
    fun `三字段全空或null不崩溃且放行`() {
        assertIs<SafetyVerdict.Clear>(matcher.inspect(SimpleNodeProbe()))
        assertIs<SafetyVerdict.Clear>(
            matcher.inspect(SimpleNodeProbe(resourceId = "", text = null, contentDesc = null)),
        )
    }

    // ---- 边界（军令点名两例） ----

    @Test
    fun `发送键盘命中发送类而非支付类`() {
        val verdict = matcher.inspect(
            SimpleNodeProbe(text = "发送键盘", resourceId = "com.x:id/send_keyboard"),
        )
        assertIs<SafetyVerdict.RequiresSecondConfirm>(verdict)
        assertEquals(HighRiskCategory.SEND, verdict.matchedRule.category)
        assertTrue(
            verdict.allMatches.none { it.rule.category == HighRiskCategory.PAYMENT },
            "发送键盘 不应牵连支付类: ${verdict.allMatches}",
        )
    }

    @Test
    fun `已支付金额展示文本同样命中支付类（宁可多一次二次确认）`() {
        val verdict = matcher.inspect(SimpleNodeProbe(text = "已支付金额 ¥128.00"))
        assertIs<SafetyVerdict.RequiresSecondConfirm>(verdict)
        assertEquals(HighRiskCategory.PAYMENT, verdict.matchedRule.category)
        assertEquals(ProbeField.TEXT, verdict.allMatches.first().matchedField)
    }

    @Test
    fun `snake_case的resourceId命中子串匹配路径`() {
        val verdict = matcher.inspect(SimpleNodeProbe(resourceId = "com.x:id/pay_confirm_button"))
        assertIs<SafetyVerdict.RequiresSecondConfirm>(verdict)
        assertEquals(ProbeField.RESOURCE_ID, verdict.allMatches.first().matchedField)
    }

    @Test
    fun `contentDesc字段同样受检`() {
        val verdict = matcher.inspect(SimpleNodeProbe(contentDesc = "确认转账给陌生人"))
        assertIs<SafetyVerdict.RequiresSecondConfirm>(verdict)
        assertEquals(HighRiskCategory.TRANSFER, verdict.matchedRule.category)
        assertEquals(ProbeField.CONTENT_DESC, verdict.allMatches.first().matchedField)
    }

    @Test
    fun `多类同中按声明顺序取最高优先级为主判并全量留痕`() {
        val verdict = matcher.inspect(
            SimpleNodeProbe(text = "发送", resourceId = "com.x:id/delete_pay"),
        )
        assertIs<SafetyVerdict.RequiresSecondConfirm>(verdict)
        assertEquals(
            HighRiskCategory.PAYMENT,
            verdict.matchedRule.category,
            "PAYMENT 声明序最前，应为主判",
        )
        val categories = verdict.allMatches.map { it.rule.category }.toSet()
        assertTrue(HighRiskCategory.SEND in categories && HighRiskCategory.DELETE in categories, "$categories")
    }

    // ---- 默认拒绝（军令 L2-2 + 红线 3） ----

    @Test
    fun `词表装载抛异常则任何动作默认拒绝`() {
        val broken = HighRiskMatcher.from(HighRiskRuleSource { throw IllegalStateException("rules gone") })
        assertTrue(!broken.isRuleSetReady)
        for (probe in listOf(SimpleNodeProbe(), SimpleNodeProbe(text = "WLAN"), SimpleNodeProbe(text = "去支付"))) {
            assertIs<SafetyVerdict.Denied>(broken.inspect(probe), "装载失败后不得放行任何动作: $probe")
        }
        assertEquals(
            SafetyVerdict.DenyReason.RULES_LOAD_FAILED,
            (broken.inspect(SimpleNodeProbe(text = "WLAN")) as SafetyVerdict.Denied).reason,
        )
    }

    @Test
    fun `词表装载为空表视为坏装载默认拒绝`() {
        val empty = HighRiskMatcher.from(HighRiskRuleSource { emptyList() })
        assertIs<SafetyVerdict.Denied>(empty.inspect(SimpleNodeProbe()))
        assertEquals(
            SafetyVerdict.DenyReason.RULES_EMPTY,
            (empty.inspect(SimpleNodeProbe()) as SafetyVerdict.Denied).reason,
        )
    }

    @Test
    fun `未初始化matcher默认拒绝`() {
        val verdict = HighRiskMatcher.uninitialized().inspect(SimpleNodeProbe(text = "WLAN"))
        assertIs<SafetyVerdict.Denied>(verdict)
        assertEquals(SafetyVerdict.DenyReason.NOT_INITIALIZED, verdict.reason)
    }
}
