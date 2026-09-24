package com.anytouch.app.executor

import com.anytouch.app.locator.LocatorHit
import com.anytouch.app.locator.LocatorMiss
import com.anytouch.app.locator.LocatorRequest
import com.anytouch.app.locator.LocatorResult
import com.anytouch.app.locator.NodeTreeLocator
import com.anytouch.app.locator.UiNode
import com.anytouch.app.locator.preOrder
import com.anytouch.app.platform.NodeActions
import com.anytouch.app.safety.HighRiskMatcher
import com.anytouch.app.safety.KillSwitch
import com.anytouch.app.safety.SafetyVerdict
import com.anytouch.app.safety.SimpleNodeProbe
import com.anytouch.app.safety.StopReceipt
import com.anytouch.contracts.Action
import com.anytouch.contracts.ActionResult
import com.anytouch.contracts.ActionType
import com.anytouch.contracts.Command
import com.anytouch.contracts.StopCode
import com.anytouch.contracts.StopReason
import com.anytouch.contracts.StopSeverity
import com.anytouch.pipeline.PipelineStopCode
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * 真设备任务驱动器：Action 序列 -> 每步 ①查 KillSwitch ②三门禁（viewport/click 显式放行）
 * ③对活树定位（轮询到超时，页面切换自愈）④安全阀检查（高危挂起等二次确认，超时/无人确认=拒绝）
 * ⑤performAction。任一步 STOP 级失败即中止整条队列——与 STAGE-02 ClosedLoop 语义同形。
 *
 * 零 Android import（设备访问全走 [NodeActions] 注入），JVM 可测。
 * 停止回执在此无损映射回 contracts 的 Command（worker 侧 StopReceipt 用 Map 保形）。
 */
class NodeTaskRunner(
    private val device: NodeActions,
    private val matcher: HighRiskMatcher = HighRiskMatcher.default(),
    private val confirmer: suspend (SafetyVerdict.RequiresSecondConfirm) -> Boolean = { false },
    private val killSwitch: KillSwitch = KillSwitch,
    private val locateTimeoutMs: Long = 15_000,
    private val locatePollMs: Long = 250,
    private val confirmTimeoutMs: Long = 15_000,
    private val settleMs: Long = 350,
    private val landedTimeoutMs: Long = 4_000,
    private val focusSettleMs: Long = 800,
) {

    data class Report(
        val results: List<ActionResult>,
        val stopped: Boolean,
        val stopCommand: Command? = null,
    )

    private val locator = NodeTreeLocator()

    suspend fun run(actions: List<Action>): Report {
        val results = ArrayList<ActionResult>(actions.size)
        var stopCommand: Command? = null

        for ((index, action) in actions.withIndex()) {
            val kill = killSwitch.snapshot()
            if (kill != null) {
                stopCommand = killReceipt(action, index, kill).toCommand()
                break
            }

            val gateViolations = buildList {
                if (!action.safety.viewportOk) add("viewport_ok")
                if (!action.safety.clickEnabled) add("click_enabled")
            }
            if (action.type != ActionType.WAIT && gateViolations.isNotEmpty()) {
                results += failure(
                    action,
                    StopReason(
                        code = PipelineStopCode.SAFETY_GATE_BLOCKED,
                        severity = StopSeverity.STOP,
                        message = "safety gate blocked: ${gateViolations.joinToString(",")}",
                        evidence = buildJsonObject { put("action_id", action.actionId) },
                    ),
                )
                stopCommand =
                    gateReceipt(action, index, "action_gates:${gateViolations.joinToString(",")}").toCommand()
                break
            }

            // 这一段 when 就是"执行器跑得动哪些 type"的**行为定义**（派单书 §4-1 钉的真集基准）。
            // 词表真源住 `:byok`（S3-F/F2、派单书 §5）：`byok/.../ExecutorVocabulary.kt` 的
            // `executorSupportedActionTypes` 由同样的四条 `ActionType` 常量组成，编译侧与落账侧都从它派生。
            // 本文件**不许** import 那个符号——`executor/` 住执行路径，红线 G（scripts/ci-local.sh）禁它
            // 看见编译模块，"编译期联网、执行期零网络"才是结构而不是注释。
            // 两边脱节因此由**可执行对拍**兜住：`app/src/test/.../executor/ExecutorVocabularyDispatchLockTest.kt`
            // 逐条真跑这一队，断言"when 判 unsupported_type 的集合 == 真源之外的集合"，
            // 两个方向都能红（这里加一条分支而真源未改 → 红；真源加一条而这里没改 → 也红）。
            val outcome = when (action.type) {
                ActionType.WAIT -> {
                    delay(longParam(action.value, "ms", 500))
                    StepOutcome(
                        success(action) {
                            put("mock", false)
                            put("waited_ms", longParam(action.value, "ms", 500))
                        },
                    )
                }

                ActionType.CLICK, ActionType.SCROLL, ActionType.TYPE_TEXT -> runNodeStep(action, index)

                else -> StepOutcome(
                    failure(
                        action,
                        StopReason(
                            code = StopCode.EXECUTOR_ERROR,
                            severity = StopSeverity.STOP,
                            message = "unsupported action type: ${action.type}",
                        ),
                    ),
                    gateReceipt(action, index, "unsupported_type:${action.type}", StopCode.EXECUTOR_ERROR).toCommand(),
                )
            }
            results += outcome.result
            stopCommand = outcome.stopCommand
            if (stopCommand == null && killSwitch.isStopped() && index == actions.size - 1) {
                // 末步执行中按下停止：后面没有"步首查询"可命中，此处在步界补发回执，
                // 否则整队会以 stopped=false 收官（中间步的停止由下一步步首检查归因，语义更准）。
                stopCommand = killReceipt(action, index, killSwitch.snapshot()!!).toCommand()
            }
            if (stopCommand != null) break
        }

        return Report(results, stopped = stopCommand != null, stopCommand = stopCommand)
    }

    /* ---- 单步：定位 -> 安全阀 -> 执行 ---- */

    private class StepOutcome(val result: ActionResult, val stopCommand: Command? = null)

    private suspend fun runNodeStep(action: Action, index: Int): StepOutcome {
        val request = action.value.toLocatorRequest()
        if (request == null) {
            return StepOutcome(
                failure(
                    action,
                    StopReason(
                        code = PipelineStopCode.INVALID_INPUT,
                        severity = StopSeverity.STOP,
                        message = "action ${action.actionId} 缺少定位线索（value 需含 resource_id/text/content_desc/path 之一）",
                    ),
                ),
                gateReceipt(action, index, "missing_locator", PipelineStopCode.INVALID_INPUT).toCommand(),
            )
        }

        val located = locateWithRetry(request)
        if (located == null) {
            val kill = killSwitch.snapshot()
                ?: KillSwitch.KillSignal(reason = "user_stop", source = "kill_switch", sequence = 0)
            return StepOutcome(
                failure(
                    action,
                    StopReason(
                        code = StopCode.USER_STOP,
                        severity = StopSeverity.STOP,
                        message = "用户在定位期间按下停止",
                        evidence = buildJsonObject {
                            put("stop_reason", kill.reason)
                            put("stop_source", kill.source)
                        },
                    ),
                ),
                killReceipt(action, index, kill).toCommand(),
            )
        }
        val hit = located as? LocatorHit
        if (hit == null) {
            val miss = located as LocatorMiss
            return StepOutcome(
                failure(
                    action,
                    StopReason(
                        code = miss.code,
                        severity = StopSeverity.STOP,
                        message = miss.summary,
                        evidence = buildJsonObject {
                            put("action_id", action.actionId)
                            putJsonArray("attempts") {
                                miss.attempts.forEach { a ->
                                    add(
                                        buildJsonObject {
                                            put("level", a.level.order)
                                            put("outcome", a.outcome.name)
                                            put("detail", a.detail)
                                        },
                                    )
                                }
                            }
                        },
                    ),
                ),
                gateReceipt(action, index, miss.code).toCommand(),
            )
        }

        val probe = SimpleNodeProbe(
            resourceId = hit.node.resourceId,
            text = hit.node.text,
            contentDesc = hit.node.contentDesc,
        )
        var secondConfirmed = false
        when (val verdict = matcher.inspect(probe)) {
            SafetyVerdict.Clear -> Unit

            is SafetyVerdict.RequiresSecondConfirm -> {
                // 等确认期间必须响应全局停止：轮询 KillSwitch 抢先取消确认等待，
                // 停止信号到回执的延迟 ≤ locatePollMs（悬浮球点了就停，不再拖满 15s 超时）。
                val confirmed: Boolean? = coroutineScope {
                    val waiter = async { withTimeoutOrNull(confirmTimeoutMs) { confirmer(verdict) } ?: false }
                    val killer = launch {
                        while (!killSwitch.isStopped() && waiter.isActive) delay(locatePollMs)
                        if (killSwitch.isStopped()) waiter.cancel()
                    }
                    try {
                        waiter.await()
                    } catch (e: CancellationException) {
                        if (coroutineContext[Job]?.isActive != true) throw e
                        null
                    } finally {
                        killer.cancel()
                    }
                }
                if (confirmed == true) {
                    secondConfirmed = true
                } else {
                    val kill = killSwitch.snapshot()
                    if (kill != null) {
                        return StepOutcome(
                            failure(
                                action,
                                StopReason(
                                    code = StopCode.USER_STOP,
                                    severity = StopSeverity.STOP,
                                    message = "用户在二次确认等待期按下停止",
                                    evidence = buildJsonObject {
                                        put("stop_reason", kill.reason)
                                        put("stop_source", kill.source)
                                    },
                                ),
                            ),
                            killReceipt(action, index, kill).toCommand(),
                        )
                    }
                    return StepOutcome(
                        failure(
                            action,
                            StopReason(
                                code = PipelineStopCode.SAFETY_GATE_BLOCKED,
                                severity = StopSeverity.STOP,
                                message = "高危命中未获二次确认: ${verdict.matchedRule.ruleId}",
                                evidence = buildJsonObject { put("rule", verdict.matchedRule.ruleId) },
                            ),
                        ),
                        gateReceipt(action, index, verdict.matchedRule.ruleId).toCommand(),
                    )
                }
            }

            is SafetyVerdict.Denied -> {
                return StepOutcome(
                    failure(
                        action,
                        StopReason(
                            code = PipelineStopCode.SAFETY_GATE_BLOCKED,
                            severity = StopSeverity.STOP,
                            message = "安全阀拒绝: ${verdict.reason.name}",
                            evidence = buildJsonObject { put("reason", verdict.reason.name) },
                        ),
                    ),
                    gateReceipt(action, index, "denied:${verdict.reason.name}").toCommand(),
                )
            }
        }

        // Compose 输入框在未获焦时 SET_TEXT/PASTE 会"派发成功但不落字"（模拟器实测：ACTION_FOCUS 后
        // IME 约 90ms 才挂上）。先聚焦、沉降、对活树重取节点（IME 弹起会换掉旧句柄）再派发。
        suspend fun textTarget(): UiNode {
            if (action.type != ActionType.TYPE_TEXT) return hit.node
            device.focus(hit.node)
            delay(focusSettleMs)
            return (locator.locate(device.root(), request) as? LocatorHit)?.node ?: hit.node
        }
        var performed = when (action.type) {
            ActionType.CLICK -> device.click(hit.node)
            ActionType.SCROLL -> device.scroll(
                hit.node,
                (action.value?.string("direction") ?: "forward") != "backward",
            )

            ActionType.TYPE_TEXT -> {
                val t = textTarget()
                val input = action.value?.string("input") ?: ""
                // 真机实测（K40/MIUI 搜索框）：SET_TEXT 可"明示拒绝"返回 false——兜底通道必须在这条边
                // 也上场，而非只治"虚报 true"；成败终裁仍是下方落字复核，paste 派发被接收≠落字。
                // A2：两通道的合计判据在 textDispatchRoute；"何时真去调 PASTE"的短路留在这里——
                // 那是有副作用的设备调用，无条件派发 PASTE 本身就是语义变化（setText 已接收时不该再贴一次）。
                val setTextOk = device.setText(t, input)
                val pasteOk = if (setTextOk) false else device.pasteText(t, input)
                textDispatchRoute(setTextOk, pasteOk)
            }
            else -> false
        }
        // A1：原判据「明示拒 → 沉降 settleMs*2 → 按原线索重定位 → 重派一次 → 仍 false 才收
        // perform_failed」的"该不该重派 + 派到第几次"整体住在 redispatchPlan（三档落点互不相同）。
        // 平台侧这里只剩三档的**动作**：沉降与设备调用是取数，attempt 只做自增（无循环由纯函数的封顶给出，
        // 不在这里再数一遍轮数）。动作派发本身绝不进纯函数（那是假下沉）。
        var dispatched = performed
        var attempt = 0
        while (true) {
            when (redispatchPlan(dispatched, action.type, attempt)) {
                // 派发已被接收（或该类型不参与重派）：出环，走下方落字复核
                Redispatch.Skip -> break

                Redispatch.RetryOnce -> {
                    attempt += 1
                    // 句柄陈旧假红（AVD 三档矩阵收口轮 C1/C7 + MarvisPhone 重启后 C7 设备实证）：定位命中后
                    // 异步卡片/索引重排整棵树，performAction 打在死句柄上被明示拒绝（type_text 亦同：SET_TEXT
                    // 与 PASTE 双双 false 可能只是派发瞬间字段在重建）。false=动作根本没执行过、无线索被消耗、
                    // 无输入落盘，按原线索重定位一次再派是零副作用的自证。
                    delay(settleMs * 2)
                    val again = (locator.locate(device.root(), request) as? LocatorHit)?.node
                    if (again != null) {
                        dispatched = when (action.type) {
                            ActionType.CLICK -> device.click(again)
                            ActionType.SCROLL -> device.scroll(
                                again,
                                (action.value?.string("direction") ?: "forward") != "backward",
                            )

                            ActionType.TYPE_TEXT -> {
                                val input = action.value?.string("input") ?: ""
                                device.focus(again)
                                delay(focusSettleMs)
                                // A2：同上，合计判据在 textDispatchRoute，短路留平台侧
                                val setTextOk = device.setText(again, input)
                                val pasteOk = if (setTextOk) false else device.pasteText(again, input)
                                textDispatchRoute(setTextOk, pasteOk)
                            }

                            else -> false
                        }
                    }
                }

                // 封顶已过仍明示拒：这一次重派的机会已经用掉了，收 perform_failed（绝不第三派）
                Redispatch.GiveUp -> return StepOutcome(
                    failure(
                        action,
                        StopReason(
                            code = StopCode.EXECUTOR_ERROR,
                            severity = StopSeverity.STOP,
                            message = "performAction 失败: ${action.type} @ ${hit.nodeRef.indexPath}",
                            evidence = buildJsonObject { put("index_path", hit.nodeRef.indexPath) },
                        ),
                    ),
                    gateReceipt(action, index, "perform_failed").toCommand(),
                )
            }
        }
        var usedPasteRoute = false
        if (action.type == ActionType.TYPE_TEXT) {
            // 模拟器实测两面虚报：performAction 布尔不作数，落字复核以"派发句柄的活读"为准（refresh）。
            // 不可按原线索重定位复核：线索文本会被输入本身改掉（hint 消失），重定位会配到别的节点造成假阴性
            // （Settings 搜索框实测：文本已落成功却被复核判失败）。仅句柄失效时才回退线索重定位。
            val input = action.value?.string("input") ?: ""
            delay(settleMs)
            var target = hit.node
            var landedText = awaitLanded(target, input)
            if (input.isNotBlank() && landedText?.contains(input.trim()) != true) {
                device.focus(target)
                delay(focusSettleMs)
                landedText = device.textOf(target)
                if (landedText == null) {
                    target = (locator.locate(device.root(), request) as? LocatorHit)?.node ?: target
                }
                if (device.pasteText(target, input)) {
                    usedPasteRoute = true
                    delay(settleMs)
                    landedText = awaitLanded(target, input)
                }
                if (input.isNotBlank() && landedText?.contains(input.trim()) != true) {
                    // 整步重试一次（AVD 矩阵实测，API 35 搜索页）：过渡动画中途 SET_TEXT 派发给将被重建的
                    // 输入框→谎 true 不落字、paste 同拒。此时输入未落、线索未被消耗，按原线索重定位是安全的
                    // （与"复核禁重定位"不冲突——那条防的是线索已被输入改掉）。先多沉一拍：过渡未终时
                    // 单发定位会零命中、重试直接空转（avd35 实测 fresh==null 形态）。
                    delay(settleMs * 2)
                    val fresh = (locator.locate(device.root(), request) as? LocatorHit)?.node
                    if (fresh != null) {
                        device.focus(fresh)
                        delay(focusSettleMs)
                        val again = (locator.locate(device.root(), request) as? LocatorHit)?.node ?: fresh
                        if (device.setText(again, input) || device.pasteText(again, input)) {
                            usedPasteRoute = true
                            delay(settleMs)
                            target = again
                            landedText = awaitLanded(again, input)
                        }
                    }
                }
                if (landedText?.contains(input.trim()) != true) {
                    killSwitch.snapshot()?.let { kill ->
                        return StepOutcome(
                            failure(
                                action,
                                StopReason(
                                    code = StopCode.USER_STOP,
                                    severity = StopSeverity.STOP,
                                    message = "用户在落字复核期间按下停止",
                                    evidence = buildJsonObject {
                                        put("stop_reason", kill.reason)
                                        put("stop_source", kill.source)
                                    },
                                ),
                            ),
                            killReceipt(action, index, kill).toCommand(),
                        )
                    }
                    return StepOutcome(
                        failure(
                            action,
                            StopReason(
                                code = StopCode.EXECUTOR_ERROR,
                                severity = StopSeverity.STOP,
                                message = "SET_TEXT${if (usedPasteRoute) "/PASTE" else ""} 未落字（performAction=true 为虚报）: 期望包含 \"$input\"",
                                evidence = buildJsonObject {
                                    put("expected", input)
                                    put("actual", landedText ?: "<句柄失效或无文本>")
                                },
                            ),
                        ),
                        gateReceipt(action, index, "set_text_unverified", StopCode.EXECUTOR_ERROR).toCommand(),
                    )
                }
            }
        }
        if (action.type == ActionType.CLICK || action.type == ActionType.SCROLL) {
            delay(settleMs) // 页面切换沉降；下一次定位自带轮询，不在此等待特定节点
        }
        return StepOutcome(
            success(action) {
                put("mock", false)
                put("level", hit.level.name)
                put("matched_by", hit.matchedBy)
                put("index_path", hit.nodeRef.indexPath)
                if (secondConfirmed) put("second_confirmed", true)
                if (usedPasteRoute) put("route", "paste_fallback")
            },
        )
    }

    /** 落字复核轮询：活读派发句柄（device.textOf→refresh），零真实等待外的误判。返回末次读数。
     *  每轮查 KillSwitch：按下停止即刻放弃复核返回，调用方出 USER_STOP 回执而非误报"未落字"。 */
    private suspend fun awaitLanded(node: UiNode, input: String): String? {
        val deadline = System.currentTimeMillis() + landedTimeoutMs
        val want = input.trim()
        var text: String? = null
        while (true) {
            if (killSwitch.isStopped()) return text
            text = device.textOf(node)?.trim()
            if (text?.contains(want) == true) return text
            // A3：兜底扫树的判据整体在 landedViaTreeScan（含 want 空白不构成凭据那一层早退）。
            // 平台侧只做取数：把活树按文档序摊平成 LandedNodeFact（只喂判据要读的两个字段）。
            // 上方"派发句柄活读优先"那半边是活读设备句柄（device.textOf→refresh），属取数，按军令不搬。
            // 句柄活读兜底扫树（AVD API 35 搜索页实证）：过渡期派发给旧句柄后 Compose 整节点
            // 换新，字已落进新输入框而旧句柄永远读不到——不扫树就把"真落字"报成 set_text_unverified 假红。
            // 这不是"按线索重定位"（线索 hint 会被输入改掉），匹配键是输入本身；限定可编辑类，
            // 防搜索结果列表（TextView 含同词）造成假阳性。
            val landedElsewhere = landedViaTreeScan(
                device.root()?.preOrder()?.map { LandedNodeFact(it.className, it.text) } ?: emptyList(),
                want,
            )
            if (landedElsewhere != null) return landedElsewhere.text?.trim()
            if (System.currentTimeMillis() >= deadline) return text
            delay(locatePollMs)
        }
    }

    /** 页面切换期定位轮询：超时前每 [locatePollMs] 重取一次活树；root 未就绪时定位器自身记 miss。
     *  每轮查 KillSwitch：按下停止后 ≤locatePollMs 放弃定位，返回 null 由调用方出 USER_STOP 回执。 */
    private suspend fun locateWithRetry(request: LocatorRequest): LocatorResult? {
        val deadline = System.currentTimeMillis() + locateTimeoutMs
        var last: LocatorResult
        while (true) {
            if (killSwitch.isStopped()) return null
            last = locator.locate(device.root(), request)
            if (last is LocatorHit) return last
            if (System.currentTimeMillis() >= deadline) return last
            delay(locatePollMs)
        }
    }

    /* ---- 回执构造与映射 ---- */

    private fun killReceipt(action: Action, index: Int, kill: KillSwitch.KillSignal) = StopReceipt(
        commandId = "kill-switch-stop-${action.actionId}-$index",
        source = "kill_switch",
        payload = mapOf(
            "step_index" to index.toString(),
            "step_id" to action.actionId,
            "stop_code" to StopCode.USER_STOP,
            "stop_reason" to kill.reason,
            "stop_source" to kill.source,
        ),
    )

    private fun gateReceipt(action: Action, index: Int, reason: String, code: String = PipelineStopCode.SAFETY_GATE_BLOCKED) = StopReceipt(
        commandId = "safety-gate-stop-${action.actionId}-$index",
        source = "safety_gate",
        payload = mapOf(
            "step_index" to index.toString(),
            "step_id" to action.actionId,
            "stop_code" to code,
            "stop_reason" to reason,
        ),
    )

    /** worker 侧 Map 载荷 -> contracts JsonObject，字段无损（主窗窄口承诺）。 */
    private fun StopReceipt.toCommand(): Command = Command(
        commandId = commandId,
        type = type,
        source = source,
        payload = buildJsonObject {
            payload.forEach { (k, v) -> put(k, v) }
        },
        confirm = confirm,
    )

    private fun success(action: Action, details: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) =
        ActionResult(
            ok = true,
            status = "success",
            actionId = action.actionId,
            backend = BACKEND,
            landed = action.target,
            recovery = null,
            details = buildJsonObject(details),
        )

    private fun failure(action: Action, reason: StopReason) = ActionResult(
        ok = false,
        status = "failed",
        actionId = action.actionId,
        backend = BACKEND,
        recovery = reason,
    )

    companion object {
        const val BACKEND = "accessibility"
    }
}

/* ---- Action.value 线索解码（键名即任务 JSON DSL 的对外口径） ---- */

private fun JsonObject?.toLocatorRequest(): LocatorRequest? {
    this ?: return null
    val request = LocatorRequest(
        resourceId = string("resource_id"),
        text = string("text"),
        contentDesc = string("content_desc"),
        path = string("path"),
        instance = int("instance"),
    )
    return request.takeIf {
        it.resourceId != null || it.text != null || it.contentDesc != null || it.path != null
    }
}

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.int(key: String): Int =
    (this[key] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0

private fun longParam(value: JsonObject?, key: String, default: Long): Long =
    (value?.get(key) as? JsonPrimitive)?.content?.toLongOrNull() ?: default
