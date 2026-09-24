package com.anytouch.app

/**
 * 执行准入（老板 09-23 裁决 V-3）：任务框文本若是"机器上一次发布的建议"且与当前步序账不符，
 * 即拒绝派发并显示话术——不夺用户输入，也不在框旁常驻标注挡 UI。
 *
 * 要堵的那一面（设备实证 `evidence/S2/img/recui-probe-C-orphan-task-json.png`）：
 * 步序账删到 0 步后，任务框里还留着上一次编辑发布的单步 JSON，"执行任务"照样可点——
 * 此刻屏上有两套可回放真值（账 0 步 / 框 1 步），放出来的正是账上已经删掉的步骤。
 *
 * 判据为什么钉在"框内文本 == 机器发布的建议"而不是"框内文本 == 当前账"（字面口径）：
 * 后者会把"用户自己粘一段 JSON 直接执行"这条 S1 主路径一并打死——那时账本就是空的，
 * device-smoke 的 C 系列 13 项断言全部走 task_json 注入（账=0 步），字面口径会整轮假红。
 * 所以只拦"机器写的、且已被账作废"的那一类：用户手敲/手改的文本自证其权（不夺字）。
 *
 * 纯函数、零平台依赖：判据必须能在 JVM 里锁住，接线（MainActivity 唯一提交口）不得自带第二份判据。
 */
enum class TaskAdmission {
    /** 放行：框内文本不是作废的机器建议。 */
    ACCEPT,

    /** 拒放：框内文本恰是上一次发布的建议，而步序账已经变了（含清零）。 */
    STALE_SUGGESTION,
}

/**
 * @param boxJson 任务框当前文本
 * @param ledgerJson 当前步序账的序列化（与发布建议用的是同一个 `encodeActions`，逐字可比）
 * @param lastSuggestion 机器最近一次发布进任务框的建议（null=本轮从未发布过）
 */
fun taskAdmission(boxJson: String, ledgerJson: String, lastSuggestion: String?): TaskAdmission =
    if (lastSuggestion != null && boxJson == lastSuggestion && boxJson != ledgerJson) {
        TaskAdmission.STALE_SUGGESTION
    } else {
        TaskAdmission.ACCEPT
    }

/** 拒放话术（L2-③"错误必显示"）：说清"没放出去"的原因与下一步动作，且不承诺替用户改文本。 */
fun TaskAdmission.userCopy(): String? = when (this) {
    TaskAdmission.ACCEPT -> null
    TaskAdmission.STALE_SUGGESTION ->
        "The task box still holds the stale suggestion published by the last edit, and the step ledger has " +
            "moved on: running it as-is would dispatch steps that are long gone from the ledger. Record " +
            "again and tap “Stop & compile”, or edit the steps in the list before running " +
            "(anything you typed by hand is never touched)."
}
