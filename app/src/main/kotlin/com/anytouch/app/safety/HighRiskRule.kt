package com.anytouch.app.safety

/**
 * 高危动作类别。声明顺序即多命中时的主判定优先级（PAYMENT 最高）。
 * 军令冻结七类：支付/转账/发送/删除/密码/购买/确认订单，不扩类。
 */
enum class HighRiskCategory {
    PAYMENT,
    TRANSFER,
    SEND,
    DELETE,
    PASSWORD,
    PURCHASE,
    CONFIRM_ORDER,
}

/**
 * 单条高危规则 = 类别 + 关键词（中英双语集在 [HardcodedHighRiskRules] 组织）。
 * 匹配方式：小写化后的子串匹配（见 [HighRiskMatcher] 判定设计说明）。
 */
data class HighRiskRule(
    val ruleId: String,
    val category: HighRiskCategory,
    val keyword: String,
)

/** 规则装载来源：抛异常/返回空表都会被 matcher 归入默认拒绝态。 */
fun interface HighRiskRuleSource {
    @Throws(Exception::class)
    fun load(): List<HighRiskRule>
}

/**
 * 硬编码词表（军令 L2-1：不读外部文件，装载失败只可能来自初始化路径）。
 *
 * 英文关键词选词纪律：因 resource-id 多为 snake_case（'_' 在正则 \b 内算单词字符，
 * 词边界法会漏掉 "id_pay_confirm"），统一采用小写子串匹配；
 * 为此刻意排除会误伤的短词（drop/pin/buy→保留因语义仍需"下单"类，见下），
 * 只保留子串误伤概率低的词（pay/transfer/delete/password…）。
 */
object HardcodedHighRiskRules {

    val RULES: List<HighRiskRule> = buildList {
        addAll(category(HighRiskCategory.PAYMENT, "支付", "付款", "收银", "扣款", "pay", "payment", "cashier", "billing", "charge"))
        addAll(category(HighRiskCategory.TRANSFER, "转账", "转帐", "汇款", "transfer", "remit"))
        addAll(category(HighRiskCategory.SEND, "发送", "发出", "send", "forward", "share"))
        addAll(category(HighRiskCategory.DELETE, "删除", "清空", "卸载", "delete", "remove", "uninstall", "erase"))
        addAll(category(HighRiskCategory.PASSWORD, "密码", "口令", "验证码", "password", "passwd", "passcode", "pwd"))
        addAll(category(HighRiskCategory.PURCHASE, "购买", "下单", "购物车", "buy", "purchase"))
        addAll(category(HighRiskCategory.CONFIRM_ORDER, "确认订单", "提交订单", "确认收货", "confirm order", "place order", "checkout", "order now"))
    }

    val SOURCE: HighRiskRuleSource = HighRiskRuleSource { RULES }

    private fun category(cat: HighRiskCategory, vararg keywords: String): List<HighRiskRule> =
        keywords.map { HighRiskRule(ruleId = "${cat.name}:$it", category = cat, keyword = it) }
}
