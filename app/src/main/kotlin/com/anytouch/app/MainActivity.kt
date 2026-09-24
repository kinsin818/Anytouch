package com.anytouch.app

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import com.anytouch.app.compile.ByokGateway
import com.anytouch.app.compile.byokContextFlagOf
import com.anytouch.app.platform.RecordGate
import com.anytouch.app.platform.runGateOf
import com.anytouch.app.platform.runUserCopy
import com.anytouch.app.platform.userCopy
import com.anytouch.app.recorder.StepEdit
import com.anytouch.app.recorder.encodeActions
import com.anytouch.app.recorder.session.RecorderStore
import com.anytouch.app.template.PresetTemplateLibrary
import com.anytouch.app.template.TemplateLoad
import com.anytouch.app.template.TemplateLoader
import com.anytouch.app.ui.ByokPanel
import com.anytouch.app.ui.StepListEditor
import com.anytouch.byok.executorSupportedActionTypes

/**
 * 任务注入窗 + 录制控制窗（S2-ONDEVICE 主窗窄口）：
 * - 手点（Run/开始录制/停止并编译）与 adb（--es task_json / record_start / record_stop / session_json，
 *   冒烟脚本用）双通道；
 * - adb 通道触发后立刻退后台，让目标 App 成为活动窗口（keep_fg 例外）。
 */
@OptIn(ExperimentalComposeUiApi::class)
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        var initial by mutableStateOf(SAMPLE_TASK)
        setContent {
            MaterialTheme {
                Surface(
                    Modifier.fillMaxSize().semantics { testTagsAsResourceId = true },
                ) {
                    // 必须 remember：裸 mutableStateOf 在每次重组合都新建实例，输入写进去又被初值冲掉
                    // （模拟器实测 SET_TEXT/PASTE 的 onValueChange 都触发了，值却在下一帧回到样例——排查两小时的"虚报"实为自家状态丢失）。
                    var taskJson by remember { mutableStateOf(initial) }
                    var targetPkg by remember { mutableStateOf(RecorderStore.targetPkg) }
                    // 进程内单例：面板与 adb 注入通道必须看见同一格意图、同一个"编译中"（两套=两套真值）
                    val byok = remember { ByokGateway.of(applicationContext) }
                    val recording by RecorderStore.activeSession.collectAsState()
                    val suggestion by RecorderStore.suggestedTaskJson.collectAsState()
                    val startRejection by RecorderStore.startRejection.collectAsState()
                    val steps by RecorderStore.compiledActions.collectAsState()
                    val editRejection by RecorderStore.editRejection.collectAsState()
                    val stopRejection by RecorderStore.stopRejection.collectAsState()
                    val compileBusy by AppState.compileBusy.collectAsState()
                    val taskRejection by AppState.taskRejection.collectAsState()
                    val templateRejection by AppState.templateRejection.collectAsState()
                    // 编译产物到达即进任务框；用户随后手改，建议流即刻作废（不夺字）
                    LaunchedEffect(suggestion) {
                        suggestion?.let {
                            taskJson = it
                            RecorderStore.suggestedTaskJson.value = null
                        }
                    }
                    // 首进界面把已存配置回填到屏上（只填还空着的字段，用户正在敲的字不夺）
                    LaunchedEffect(Unit) { byok.refreshFromVault() }
                    Column(
                        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        val connected by AppState.serviceConnected.collectAsState()
                        val running by AppState.running.collectAsState()
                        val report by AppState.lastRunReport.collectAsState()
                        Text("Anytouch executor", style = MaterialTheme.typography.headlineSmall)
                        // L2-①：未连接即首启引导必现（同一话术单源于 AccessibilityGate，UI 不各写一份）
                        Text(
                            if (connected) "Accessibility service: connected"
                            else RecordGate.SERVICE_OFF.userCopy().orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            when {
                                running -> "Task running… (tap the floating ball to stop)"
                                // 编译持有的那一段时间必须看得见（裁决 S3-R4-1：屏上不说，用户只会觉得按钮自己坏了）
                                // 编译持有的那一段时间必须看得见（裁决 S3-R4-1：屏上不说，用户只会觉得按钮自己坏了；
                                // S31-B2 扩面后这一句要把四个入口都点到：录制、改账、执行）
                                compileBusy -> "AI compiling… (one request is in flight; recording, step edits and runs stay paused)"
                                else -> "Idle"
                            },
                        )
                        OutlinedTextField(
                            value = targetPkg,
                            onValueChange = { targetPkg = it },
                            modifier = Modifier.fillMaxWidth().testTag("target_pkg"),
                            label = { Text("Package to record") },
                            singleLine = true,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { RecorderStore.start(targetPkg.trim()) },
                                // 编译在跑即置灰（灰只是提示，门禁在 RecorderStore 入口，adb 通道同样被拒）
                                enabled = connected && recording == null && !compileBusy,
                                modifier = Modifier.testTag("record_start"),
                            ) { Text("Start recording") }
                            Button(
                                onClick = { RecorderStore.stopAndCompile() },
                                enabled = recording != null && !compileBusy,
                                modifier = Modifier.testTag("record_stop"),
                            ) { Text("Stop & compile") }
                        }
                        Text(
                            if (recording != null && RecorderStore.isRecording)
                                "Recording: only actions inside the target package's windows"
                            else "Not recording",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        // L2-③：被拒的开录必须把话术显示出来（静默禁用=黑洞）；adb 注入通道同一条流
                        startRejection?.let {
                            Text(
                                it,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.testTag("record_rejection"),
                            )
                        }
                        // 「停止并编译」被拒另开一格（裁决 S3-R4-1）：与开录拒因分格，两件事同时红时不许互相盖
                        stopRejection?.let {
                            Text(
                                it,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.testTag("record_stop_rejection"),
                            )
                        }
                        // 屏上下文的账目与 AI 编译结论都在这块面板里（判据一行不在 UI，见 ui/ByokPanel 的说明）
                        ByokPanel(gateway = byok, modifier = Modifier.fillMaxWidth())
                        StepListEditor(
                            actions = steps,
                            onEdit = RecorderStore::applyEdit,
                            // 执行中置灰（老板 09-23 裁决：禁编辑门禁，停止球位置因此不动）。
                            // 编译在跑同样置灰（裁 S31-B2）——灰只是提示，门禁在 applyEdit，注入绕过按钮照样被拒。
                            editable = !running && !compileBusy,
                            modifier = Modifier.fillMaxWidth().testTag("step_list"),
                        )
                        // 编辑被拒同样必现（与开录拒绝同律：置灰/无回执=黑洞，用户要知道"没删掉"为什么）
                        editRejection?.let {
                            Text(
                                it,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.testTag("step_edit_rejection"),
                            )
                        }
                        // 预制模板装载（S5-a，军令 R3-1 三模板）：与 AI 编译同一落账口、同一拒因上屏律。
                        // 灰只是提示——真门禁在 loadTemplate 入口与 acceptModelActions，adb 注入绕按钮同样被拒。
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { loadTemplate("photos_cleanup", "ui_button") },
                                enabled = !compileBusy,
                                modifier = Modifier.testTag("template_photos"),
                            ) { Text(PresetTemplateLibrary.byId("photos_cleanup")!!.label) }
                            Button(
                                onClick = { loadTemplate("gmail_cleanup", "ui_button") },
                                enabled = !compileBusy,
                                modifier = Modifier.testTag("template_gmail"),
                            ) { Text(PresetTemplateLibrary.byId("gmail_cleanup")!!.label) }
                            Button(
                                onClick = { loadTemplate("discord_checkin", "ui_button") },
                                enabled = !compileBusy,
                                modifier = Modifier.testTag("template_discord"),
                            ) { Text(PresetTemplateLibrary.byId("discord_checkin")!!.label) }
                        }
                        // S5-R7 上架口径同源：这两个模板需用户先在对应 App 内自行登录；本工具不做登录、不碰凭证
                        Text(
                            PresetTemplateLibrary.signInHint(),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        // 装载被拒必须显形（与开录/编辑/派发同律：静默"点了没反应"=黑洞）
                        templateRejection?.let {
                            Text(
                                it,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.testTag("template_rejection"),
                            )
                        }
                        OutlinedTextField(
                            value = taskJson,
                            onValueChange = { taskJson = it },
                            modifier = Modifier.fillMaxWidth().weight(1f, fill = false).testTag("task_input"),
                            minLines = 8,
                        )
                        Button(
                            onClick = { submitTask(taskJson, "ui_button") },
                            // 编译在跑即置灰（裁 S31-B2，与上面两个录制按钮同一形态）。灰只是提示：
                            // 真门禁在 submitTask 入口，adb 注入绕过按钮同样被拒（下面 task_rejection 那格就是它的红字）。
                            enabled = connected && !compileBusy,
                            modifier = Modifier.testTag("run_task"),
                        ) { Text("Run task") }
                        // 被拦下的派发必须显形（与开录/编辑拒因同律：静默"点了没反应"=黑洞）。
                        // 这一格现在装两种拒因，同源不同档：V-3 的"框账不符"（请求绑定，下一次派发覆盖）
                        // 与 S3-F 的"编译在跑"（纯状态档，编译归位即由 revalidateRunRejection 作废）。
                        taskRejection?.let {
                            Text(
                                it,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.testTag("task_rejection"),
                            )
                        }
                        report?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 18,
                            )
                        }
                    }
                }
            }
        }
        handleTrigger(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleTrigger(intent)
    }

    private fun handleTrigger(intent: Intent?) {
        intent ?: return
        var handled = false
        intent.getStringExtra(EXTRA_RECORD_START)?.takeIf { it.isNotBlank() }?.let { pkg ->
            RecorderStore.start(pkg)
            handled = true
        }
        if (intent.getBooleanExtra(EXTRA_RECORD_STOP, false)) {
            RecorderStore.stopAndCompile()
            handled = true
        }
        // 步骤编辑注入通道（测试用，与 UI 按钮同走 RecorderStore.applyEdit 唯一写口）：
        // 门禁判据不因通道而变——被拒时同样出 step edit refused 日志，脚本据此断言"注入不等于放行"。
        intent.getStringExtra(EXTRA_STEP_REMOVE)?.toIntOrNull()?.let { index ->
            RecorderStore.applyEdit(StepEdit.Remove(index))
            handled = true
        }
        intent.getStringExtra(EXTRA_STEP_RENAME)?.toIntOrNull()?.let { index ->
            RecorderStore.applyEdit(
                StepEdit.Rename(index, intent.getStringExtra(EXTRA_STEP_RENAME_TO).orEmpty()),
            )
            handled = true
        }
        intent.getStringExtra(EXTRA_STEP_MOVE)?.toIntOrNull()?.let { from ->
            val to = intent.getStringExtra(EXTRA_STEP_MOVE_TO)?.toIntOrNull()
            // 目标位缺失/非数字不静默当 0：交给门禁按越界拒（默认拒绝，不猜意图）
            RecorderStore.applyEdit(StepEdit.Move(from, to ?: -1))
            handled = true
        }
        // AI 编译注入通道（切片 D 的测试通道，与面板同一格意图、同一入口判据）：
        // 先落意图，再按 ctx_enabled 调开关，最后按 ai_compile 触发——三步都可单独下发，
        // 脚本因此能"先看词表账，再决定编不编"。Key 不走这条通道（字面量进 adb 就是进 shell 历史）。
        intent.getStringExtra(EXTRA_AI_INTENT)?.let { ByokGateway.of(applicationContext).state.intent.value = it }
        intent.getStringExtra(EXTRA_CTX_ENABLED)?.let { raw ->
            byokContextFlagOf(raw)?.let { on ->
                ByokGateway.of(applicationContext).state.contextEnabled.value = on
            }
        }
        if (intent.getBooleanExtra(EXTRA_AI_COMPILE, false)) {
            val byok = ByokGateway.of(applicationContext)
            byok.compile(byok.state.intent.value)
            handled = true
        }
        intent.getStringExtra(EXTRA_SESSION_JSON)?.takeIf { it.isNotBlank() }?.let { json ->
            // 预置会话注入（冒烟通道）：合法与否由 RecorderStore 留痕归因，此处不重复判
            RecorderStore.injectSerialized(json)
            handled = true
        }
        intent.getStringExtra(EXTRA_TEMPLATE_LOAD)?.takeIf { it.isNotBlank() }?.let { id ->
            // 模板装载注入通道（S5-a 冒烟用）：与 UI 按钮同一入口同一门禁，被拒同样出 refused 日志
            loadTemplate(id, "adb_inject")
            handled = true
        }
        intent.getStringExtra(EXTRA_TASK_JSON)?.let { json ->
            submitTask(json, "adb_inject")
            handled = true
        }
        if (handled && !intent.getBooleanExtra(EXTRA_KEEP_FG, false)) moveTaskToBack(true)
    }

    /**
     * 派发唯一入口（"执行任务"按钮与 adb 注入共用，与录制/编辑面同一条纪律：**门禁落入口不落按钮**）。
     * 判据住在纯函数 [taskAdmission]（JVM 锁得住），此处只按结果分流；拒放既上屏（`task_rejection`）
     * 又留痕（`S1SMOKE submit refused`）——静默 return 等于把一次"点了没反应"藏进黑洞。
     *
     * S3-F/F1 在这一格前面再加一条**编译互斥**（裁决 S31-B2）：判据不在此处写 `if (compileBusy)`，
     * 而是转调 `runGateOf`——与「停止并编译」/「开录」/「步骤编辑」共用同一格编译真值
     * （`platform/AccessibilityGate.kt`）。编译那一跑回来会整本换账，此刻放行执行就是把"正在跑的那一跑"
     * 和"屏上的账"变成两套（旧形态：run_task 在编译中途溜进去，随后 `acceptModelActions` 收 RefusedRunning）。
     */
    /**
     * 预制模板装载入口（S5-a）：编译互斥在**入口**把关（`acceptModelActions` 那唯一落账口刻意不看
     * compileBusy——它要接编译产物，看了就自锁死；RUNNING 档与词表档仍在落账口，本函数不复制判据）。
     * 成功路径不改任务框一个字：产物经 `publishSuggestion` 走既有建议流进框（与 AI 编译同一通道，
     * V-3 准入因此对"装载后直接执行"逐字放行）。三条来路（UI 按钮/adb 注入）任一被拒都红字上屏+留痕。
     */
    private fun loadTemplate(id: String, via: String) {
        if (AppState.compileBusy.value) {
            val copy = runGateOf(
                running = AppState.running.value,
                compileBusy = true,
            ).runUserCopy().orEmpty()
            AppState.templateRejection.value = copy
            Log.w(TAG, "S5SMOKE template load refused gate=COMPILING id=$id via=$via detail=$copy")
            return
        }
        when (val load = TemplateLoader.load(applicationContext, id)) {
            is TemplateLoad.Failed -> {
                AppState.templateRejection.value = load.reason
                Log.w(TAG, "S5SMOKE template load refused gate=LOADER id=${load.id} via=$via detail=${load.reason}")
            }
            is TemplateLoad.Ok -> {
                val verdict = RecorderStore.acceptModelActions(
                    load.actions,
                    executorSupportedActionTypes,
                    origin = "template",
                )
                when (verdict) {
                    is RecorderStore.ModelLedger.Written -> {
                        AppState.templateRejection.value = null
                        Log.i(
                            TAG,
                            "S5SMOKE template load ok id=${load.template.id} via=$via " +
                                "steps=${verdict.steps} replaced=${verdict.replaced} " +
                                "verification=${load.template.verification}",
                        )
                    }
                    is RecorderStore.ModelLedger.RefusedRunning -> {
                        val copy = "A task is running, so the whole template was refused entry to the ledger " +
                            "(the running task and the on-screen ledger must never become two books). Wait " +
                            "for this run to finish, or tap the floating ball to stop, then load it again."
                        AppState.templateRejection.value = copy
                        Log.w(TAG, "S5SMOKE template load refused gate=RUNNING id=${load.template.id} via=$via")
                    }
                    is RecorderStore.ModelLedger.RefusedUnsupportedType -> {
                        // 词表档判据在落账口；此处只把"预制资产带病"说清楚（话术与编译来路分格：
                        // 编译来路该重编，模板来路是库与资产脱钩——两件事不许共用一句"再点一次 AI 编译"）
                        val copy = "Template “${load.template.label}” step ${verdict.index + 1} is " +
                            "type=${verdict.type}, which the executor cannot run — not a single step of the " +
                            "whole ledger was written. This shipped template asset is defective (the JVM lock " +
                            "PresetTemplatesTest should be red as well); do not use it until a fixed build."
                        AppState.templateRejection.value = copy
                        Log.w(
                            TAG,
                            "S5SMOKE template load refused gate=UNSUPPORTED_TYPE id=${load.template.id} " +
                                "via=$via index=${verdict.index} type=${verdict.type}",
                        )
                    }
                }
            }
        }
    }

    private fun submitTask(json: String, via: String) {
        val ledger = RecorderStore.compiledActions.value
        // 编译档排在 V-3 之前：编译在跑时"框里的文本与账是否一致"根本没有意义——那一跑回来整本都要换。
        val runGate = runGateOf(running = AppState.running.value, compileBusy = AppState.compileBusy.value)
        if (runGate == RecordGate.COMPILING) {
            val copy = runGate.runUserCopy().orEmpty()
            AppState.setTaskRejection(RecordGate.COMPILING, copy)
            Log.w(
                TAG,
                "S1SMOKE submit refused gate=$runGate via=$via ledger=${ledger.size} " +
                    "machineSuggestion=${RecorderStore.lastSuggestedJson?.length} detail=$copy",
            )
            return
        }
        // RUNNING 不在派发口拒：既有语义是"执行中新注入排在当前这一跑之后串行执行"
        // （见 `AnytouchAccessibilityService` 总线那头的 busy 防线注释）。本批只扩编译面，不动这条；
        // 真要在派发口拒 RUNNING 得另裁一刀——那时改的是上面那一个 when，不是再加一份判据。
        val verdict = taskAdmission(
            boxJson = json,
            ledgerJson = encodeActions(ledger),
            lastSuggestion = RecorderStore.lastSuggestedJson,
        )
        if (verdict != TaskAdmission.ACCEPT) {
            val copy = verdict.userCopy()
            // 档位存 null：V-3 这一类是**请求绑定**的拒因，绑用户那一次点击，不许被状态跃迁悄悄抹掉
            AppState.setTaskRejection(null, copy)
            Log.w(
                TAG,
                "S1SMOKE submit refused gate=$verdict via=$via ledger=${ledger.size} " +
                    "machineSuggestion=${RecorderStore.lastSuggestedJson?.length} detail=$copy",
            )
            return
        }
        AppState.setTaskRejection(null, null)
        AppState.submit(json)
    }

    companion object {
        /** 与执行器/录制面同一 tag：冒烟脚本按 `-s AnytouchRun:*` 过滤，换 tag 即断言失明。 */
        private const val TAG = "AnytouchRun"

        const val EXTRA_TASK_JSON = "task_json"
        const val EXTRA_KEEP_FG = "keep_fg"
        const val EXTRA_RECORD_START = "record_start"
        const val EXTRA_RECORD_STOP = "record_stop"
        const val EXTRA_SESSION_JSON = "session_json"
        /** 预制模板装载（S5-a）：只认注册表 id，脏 id 由装载器拒并上屏，不在注入通道猜意图。 */
        const val EXTRA_TEMPLATE_LOAD = "template_load"
        const val EXTRA_AI_INTENT = "ai_intent"
        const val EXTRA_AI_COMPILE = "ai_compile"

        /** 只认 on/off 两个字面（判据在 [byokContextFlagOf]）：别的写法一律不改动当前开关。 */
        const val EXTRA_CTX_ENABLED = "ctx_enabled"
        const val EXTRA_STEP_REMOVE = "step_remove"
        const val EXTRA_STEP_RENAME = "step_rename"
        const val EXTRA_STEP_RENAME_TO = "step_rename_to"
        const val EXTRA_STEP_MOVE = "step_move"
        const val EXTRA_STEP_MOVE_TO = "step_move_to"

        /** 冒烟任务：Connected devices → Connection preferences → Bluetooth（模拟器实测可三级钻取；真机口径属 T3）。门禁显式放行。 */
        const val SAMPLE_TASK =
            """[{"action_id":"cd","type":"click","source":"node","value":{"text":"Connected devices"},"safety":{"viewport_ok":true,"click_enabled":true}},{"action_id":"cp","type":"click","source":"node","value":{"text":"Connection preferences"},"safety":{"viewport_ok":true,"click_enabled":true}},{"action_id":"bt","type":"click","source":"node","value":{"text":"Bluetooth"},"safety":{"viewport_ok":true,"click_enabled":true}}]"""
    }
}
