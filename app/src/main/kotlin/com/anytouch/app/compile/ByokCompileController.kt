package com.anytouch.app.compile

import com.anytouch.app.compile.ByokPreflight.ByokPlan
import com.anytouch.app.compile.ByokPreflight.Verdict
import com.anytouch.app.recorder.session.RecorderStore
import com.anytouch.byok.ByokErrorKind
import com.anytouch.byok.CompileResult
import com.anytouch.byok.DslCompiler
import com.anytouch.byok.KeyMasker
import com.anytouch.byok.ScreenContext
import com.anytouch.contracts.Action

/**
 * 一次 AI 编译的机器结论。UI 只按它分流与上屏，**绝不自带第二份判据**（雷 18 同族纪律）。
 *
 * [context] 携带词表账（屏上 N 节点 / 上行几条 / 五档剔除），因为"上行面必须看得见"是裁 2 的
 * 屏上义务，不是日志里的可选细节。
 */
data class ByokReport(
    val published: Boolean,
    val userCopy: String,
    val gate: ByokPreflight.Gate? = null,
    val errorKind: ByokErrorKind? = null,
    val stage: String? = null,
    val steps: Int = 0,
    val replaced: Int = 0,
    val context: ScreenContext? = null,
)

/**
 * 编排一次编译的全部判定顺序（纯类，零 Android）：预检 → 问模型 → 校验 → 写唯一真值源（词表由调用方取好传入）。
 *
 * 三条不许让步的地方都在本类锁住（JVM 用例）：
 * 1) **任何一档没走通都不写步序账**（军令 §3-1：拒了还留半条产物=第二套真值）；
 * 2) 词表开着却一个节点都没采到时必须说破（否则"每次都静默上行 0 条"看起来像正常工作）；
 * 3) 失败话术按机器档选文案，归因片段先过 [KeyMasker] 再上屏——传输层的 detail 本身已脱敏，
 *    但校验档带模型原文，不兜一道就可能在屏上印出请求体。
 *
 * 设备侧的取数/线程/Keystore 全在依赖里（[preflightOf]/[compilerOf]/[publish]），见
 * [ByokGateway]；本类因此能在 JVM 里跑完整条链。[publish] 的返回类型是步序账唯一写口自有的
 * [RecorderStore.ModelLedger]——"执行中不许换账"判在写口，本类只按它给的结论说话。
 */
class ByokCompileController(
    private val preflightOf: (String) -> Verdict,
    private val compilerOf: (ByokPlan) -> DslCompiler,
    private val publish: (List<Action>) -> RecorderStore.ModelLedger,
) {

    /**
     * [context] 由调用方取好再传进来：取无障碍树必须在主线程，而这一次编译整体跑在后台线程
     * （真机 HTTP 不能占主线程）。把"什么时候取树"留给设备缝，判据仍然在这里一条不少。
     */
    fun compile(intent: String, context: ScreenContext): ByokReport =
        when (val pre = preflightOf(intent)) {
            is Verdict.Blocked -> ByokReport(
                published = false,
                userCopy = pre.userCopy,
                gate = pre.gate,
            )
            is Verdict.Ready -> ask(pre.plan, intent, context)
        }

    private fun ask(plan: ByokPlan, intent: String, context: ScreenContext): ByokReport {
        val result = try {
            compilerOf(plan).compile(intent, context)
        } catch (e: Throwable) {
            // 构造 transport 或问模型这一步抛出（含传输层没归类的形态）：不许冒到调用方（后台协程抛出=进程崩），
            // 也不许假装成功。异常消息可能自带 URL/Key，只取类名并再过一道脱敏。
            return ByokReport(
                published = false,
                userCopy = ByokErrorKind.UNREACHABLE.userCopy() +
                    "（归因：compile·" + KeyMasker.mask(e.javaClass.simpleName).take(120) + "）",
                errorKind = ByokErrorKind.UNREACHABLE,
                stage = "compile",
                context = context,
            )
        }
        return when (result) {
            is CompileResult.Ok -> written(result.actions, context)
            is CompileResult.Reject -> {
                val kind = result.kind ?: ByokErrorKind.COMPILE_REJECT
                ByokReport(
                    published = false,
                    userCopy = kind.userCopy() + "（归因：" + result.stage + "·" +
                        KeyMasker.mask(result.detail).take(120) + "）",
                    errorKind = kind,
                    stage = result.stage,
                    context = context,
                )
            }
        }
    }

    private fun written(actions: List<Action>, context: ScreenContext): ByokReport =
        when (val ledger = publish(actions)) {
            is RecorderStore.ModelLedger.Written -> ByokReport(
                published = true,
                userCopy = buildString {
                    append("AI 编排出 ").append(ledger.steps).append(" 步，已写进步序账")
                    if (ledger.replaced > 0) {
                        append("（作废旧账 ").append(ledger.replaced).append(" 步）")
                    }
                    append("。请逐条核对步序列表，再点「执行任务」。")
                    contextZeroRowWarning(context)?.let { append("；").append(it) }
                },
                steps = ledger.steps,
                replaced = ledger.replaced,
                context = context,
            )
            RecorderStore.ModelLedger.RefusedRunning -> ByokReport(
                published = false,
                userCopy = "模型编排出 " + actions.size + " 步，但步序账拒绝在执行中换账——" +
                    "这一步**没有写进去**，屏上的步序列表还是原来的账。请停止或等这一跑结束后重编。",
                gate = ByokPreflight.Gate.RUNNING,
                context = context,
            )
        }

    /**
     * 开着开关却零节点：话术必须说破。真实成因（当前活动窗是本 App 自己、服务刚断开、树没下发）
     * 在屏上不可分辨，就不假装能分辨——只把"本次一个字都没上行"这件事实摆出来。
     */
    private fun contextZeroRowWarning(context: ScreenContext): String? =
        if (context.enabled && context.nodesSeen == 0) {
            "注意：屏幕上下文开着，但一个节点都没采到（当前活动窗可能是本 App 自己，或无障碍服务刚断开）——" +
                "本次没有上行任何屏幕文本，模型只按你那句话编排"
        } else {
            null
        }
}
