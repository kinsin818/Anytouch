package com.anytouch.app.safety

/**
 * 高危二次确认面板的上屏文案（老板 S5-R10 裁 1：面板给人看，不露内部规则 id）。
 *
 * 纪律：这里只是 category → 人话的纯显示映射，**判定链一律不走这里**——
 * 命中与否仍由 [HardcodedHighRiskRules] + [HighRiskMatcher] 决定，改字不改闸。
 * 归因（哪条规则、哪个字段中的）留在日志与回执里，工程侧照样可查。
 */
object HighRiskPanelCopy {

    /** 面板标题。七类共用一句。 */
    const val TITLE: String = "This step needs your OK"

    /** 类别专属的第一句：说清"会发生什么"，用用户词，不用词表术语。 */
    fun headline(category: HighRiskCategory): String = when (category) {
        HighRiskCategory.PAYMENT ->
            "It may pay money out of a wallet or account on this device."
        HighRiskCategory.TRANSFER ->
            "It may move money or files from this device to someone else."
        HighRiskCategory.SEND ->
            "It may send something to other people."
        HighRiskCategory.DELETE ->
            "It may delete items here (photos, messages and the like). Deleted content is often gone for good."
        HighRiskCategory.PASSWORD ->
            "It may reveal or change a password or security code."
        HighRiskCategory.PURCHASE ->
            "It may place an order and start a charge."
        HighRiskCategory.CONFIRM_ORDER ->
            "It may confirm an order that was already placed."
    }

    /** 面板正文：标题 + 类别话 + 一句"只在你自己要的时候才确认 / 不答什么都不跑"。 */
    fun body(verdict: SafetyVerdict.RequiresSecondConfirm): String =
        "$TITLE\n\n" +
            headline(verdict.matchedRule.category) + "\n\n" +
            "Only confirm if you asked for this. If you don't answer, nothing runs."

    /** 按钮字样沿用 v1.0.1 两枚，未在本批改动（正文变长会推面板宽高，按钮位置另按实测校）。 */
    const val CANCEL: String = "Cancel"
    const val CONFIRM: String = "Confirm and run"
}
