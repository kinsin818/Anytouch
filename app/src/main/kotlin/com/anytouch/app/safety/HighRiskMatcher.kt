package com.anytouch.app.safety

/** 单条命中记录：哪条规则、哪个字段上中的。 */
data class RuleMatch(
    val rule: HighRiskRule,
    val matchedField: ProbeField,
)

enum class ProbeField { RESOURCE_ID, TEXT, CONTENT_DESC }

/**
 * 安全阀判定结果，三态：
 * - [Clear]：三字段均未命中，放行。
 * - [RequiresSecondConfirm]：命中高危词 → 需主窗弹二次确认（军令 L2-2）。
 *   [matchedRule] 按类别声明顺序取最高优先级一条；[allMatches] 保留全部命中供归因。
 * - [Denied]：词表装载失败/未初始化 → 默认拒绝，任何动作不放行（军令 L2-2 + 红线 3）。
 */
sealed class SafetyVerdict {

    data object Clear : SafetyVerdict()

    data class RequiresSecondConfirm(
        val matchedRule: HighRiskRule,
        val allMatches: List<RuleMatch>,
    ) : SafetyVerdict()

    data class Denied(
        val reason: DenyReason,
        val cause: Throwable?,
    ) : SafetyVerdict()

    enum class DenyReason { RULES_LOAD_FAILED, RULES_EMPTY, NOT_INITIALIZED }
}

/**
 * 高危匹配器。装载策略：构造即固化状态（Ready / 默认拒绝三因之一），
 * 判定路径不再抛异常 —— 装载坏了只能拒，不能放行，与 ClosedLoop 遇 STOP 即断的风格一致。
 *
 * 判定设计（军令要求留痕）：
 * - 三字段（resource-id/text/content-desc）逐一小写子串匹配，任一字段中即命中；
 * - 展示文本同样参与判定："已支付金额"这类只读节点也标高危 —— 宁可多一次二次确认，
 *   不做"看起来是展示就放行"的猜测（执行器无法从文本可靠区分展示与操作）；
 * - 多类别同中不裁决互斥，全量记入 [SafetyVerdict.RequiresSecondConfirm.allMatches]。
 */
class HighRiskMatcher private constructor(
    private val state: LoadState,
) {

    private sealed class LoadState {
        class Ready(val rules: List<HighRiskRule>) : LoadState()
        class LoadFailed(val cause: Throwable) : LoadState()
        data object EmptyRules : LoadState()
        data object Uninitialized : LoadState()
    }

    companion object {
        /** 生产入口：挂硬编码词表。 */
        fun default(): HighRiskMatcher = from(HardcodedHighRiskRules.SOURCE)

        /** 注入规则源入口：装载异常/空表 → 默认拒绝态。 */
        fun from(source: HighRiskRuleSource): HighRiskMatcher {
            val rules = try {
                source.load()
            } catch (t: Throwable) {
                return HighRiskMatcher(LoadState.LoadFailed(t))
            }
            return HighRiskMatcher(
                if (rules.isEmpty()) LoadState.EmptyRules else LoadState.Ready(rules),
            )
        }

        /** 未初始化态：供防御性调用方持有占位实例。 */
        fun uninitialized(): HighRiskMatcher = HighRiskMatcher(LoadState.Uninitialized)
    }

    val isRuleSetReady: Boolean get() = state is LoadState.Ready

    fun inspect(probe: NodeTextProbe): SafetyVerdict {
        val rules = when (val s = state) {
            is LoadState.Ready -> s.rules
            is LoadState.LoadFailed ->
                return SafetyVerdict.Denied(SafetyVerdict.DenyReason.RULES_LOAD_FAILED, s.cause)
            LoadState.EmptyRules ->
                return SafetyVerdict.Denied(SafetyVerdict.DenyReason.RULES_EMPTY, null)
            LoadState.Uninitialized ->
                return SafetyVerdict.Denied(SafetyVerdict.DenyReason.NOT_INITIALIZED, null)
        }

        val matches = ArrayList<RuleMatch>(4)
        for ((field, value) in probeFields(probe)) {
            if (value.isNullOrEmpty()) continue
            val haystack = value.lowercase()
            for (rule in rules) {
                if (haystack.contains(rule.keyword.lowercase())) {
                    matches += RuleMatch(rule, field)
                }
            }
        }
        if (matches.isEmpty()) return SafetyVerdict.Clear

        val primary = matches.minByOrNull { it.rule.category.ordinal }
            ?: return SafetyVerdict.Clear
        return SafetyVerdict.RequiresSecondConfirm(primary.rule, matches)
    }

    private fun probeFields(probe: NodeTextProbe): List<Pair<ProbeField, String?>> = listOf(
        ProbeField.RESOURCE_ID to probe.resourceId,
        ProbeField.TEXT to probe.text,
        ProbeField.CONTENT_DESC to probe.contentDesc,
    )
}
