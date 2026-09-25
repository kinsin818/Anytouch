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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.anytouch.app.activation.ActivationCopy
import com.anytouch.app.activation.ActivationState
import com.anytouch.app.activation.ActivationVerdict
import com.anytouch.app.activation.ProFeature
import com.anytouch.app.activation.ProGate
import com.anytouch.app.activation.UPGRADE_MARK
import com.anytouch.app.activation.lockedHint
import com.anytouch.app.activation.maskedTail
import com.anytouch.app.activation.proGateOf
import com.anytouch.app.activation.proRejectionAfterChange
import com.anytouch.app.activation.userCopy
import com.anytouch.app.compile.ByokGateway
import com.anytouch.app.compile.byokContextFlagOf
import com.anytouch.app.platform.AndroidActivationDisk
import com.anytouch.app.platform.AndroidSavedTaskDisk
import com.anytouch.app.platform.RecordGate
import com.anytouch.app.platform.runGateOf
import com.anytouch.app.platform.runUserCopy
import com.anytouch.app.platform.userCopy
import com.anytouch.app.recorder.DeleteOutcome
import com.anytouch.app.recorder.ManualStep
import com.anytouch.app.recorder.ReadOutcome
import com.anytouch.app.recorder.SaveOutcome
import com.anytouch.app.recorder.SavedTaskGate
import com.anytouch.app.recorder.StepEdit
import com.anytouch.app.recorder.encodeActions
import com.anytouch.app.recorder.ledgerRunningCopy
import com.anytouch.app.recorder.savedRejectionAfterChange
import com.anytouch.app.recorder.session.RecorderStore
import com.anytouch.app.recorder.userCopy
import com.anytouch.app.template.PresetTemplateLibrary
import com.anytouch.app.template.TemplateLoad
import com.anytouch.app.template.TemplateLoader
import com.anytouch.app.ui.ByokPanel
import com.anytouch.app.ui.StepListEditor
import com.anytouch.byok.executorSupportedActionTypes
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 任务注入窗 + 录制控制窗（S2-ONDEVICE 主窗窄口）：
 * - 手点（Run/开始录制/停止并编译）与 adb（--es task_json / record_start / record_stop / session_json，
 *   冒烟脚本用）双通道；
 * - adb 通道触发后立刻退后台，让目标 App 成为活动窗口（keep_fg 例外）。
 */
@OptIn(ExperimentalComposeUiApi::class)
class MainActivity : ComponentActivity() {

    /**
     * 「我的任务」磁盘件（进程内单例，见 `AndroidSavedTaskDisk.of`）：UI 按钮与 adb 注入两条通道
     * 必须操作同一个文件件。lazy 的唯一理由：冷启动不该为存档先付一次 IO。
     */
    private val savedStore by lazy { AndroidSavedTaskDisk.of(applicationContext) }

    /**
     * 本机激活态磁盘件（S5-f 军令 §3；进程内单例，界面对话框与 adb 注入两条通道共用同一个文件）。
     *
     * **本件的读写走主线程，与 [savedStore] 那条串行线刻意不同**，理由记在这里而不是藏在注释里：
     * 镜像必须在"第一帧"和 `handleTrigger` 之前就位——否则冷启动注入的那一跑（`--es step_insert_at`
     * 与 `--es activation_code` 同一条 intent）会先读镜像再刷镜像，解锁成功却仍被判未解锁。
     * 量的口径：一枚四字节私有文件（不是整本存档），与 `loadTemplate` 在本线程读预制模板资产同形态。
     */
    private val activationStore by lazy { AndroidActivationDisk.of(applicationContext) }

    /**
     * 存档读写专用的**串行**调度线。两件事各自的理由：
     * - 离开主线程：文件 IO 一律不上 UI 线程（与 Keystore 同一口径）；
     * - 只要一个 worker：UI 通道、注入通道、每次操作后的重读、状态跃迁后的重读都碰同一件文件，
     *   并发时"后读的那份把先写的那份冲回屏上"就是镜像说谎的入口（屏上列表与盘不一致，且没人会再刷）。
     */
    private val savedExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "anytouch-saved")
    }

    private val savedScope = CoroutineScope(SupervisorJob() + savedExecutor.asCoroutineDispatcher())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 冷启动第一帧之前把本机激活态读进镜像（判据 3"上次解锁、重启仍在"的前半格；
        // 为什么在本线程读而非排到串行线上，见 [activationStore] 的注释）。
        publishActivation(activationStore.state())
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
                    // 重复执行的屏上默认值与 RepeatPolicy 的默认值同源（写死两处=两套真值）
                    var repeatCount by remember {
                        mutableStateOf(RepeatPolicy.DEFAULT_REPETITIONS.toString())
                    }
                    var repeatInterval by remember {
                        mutableStateOf(RepeatPolicy.DEFAULT_INTERVAL_SEC.toString())
                    }
                    var repeatNoAsk by remember { mutableStateOf(false) }
                    // 「我的任务」命名框的草稿（S5-e 要求 2）：与 taskJson 同理必须 remember，
                    // 否则每帧重组合把用户正在敲的名字冲掉。
                    var saveName by remember { mutableStateOf("") }
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
                    val savedTasks by AppState.savedTasks.collectAsState()
                    val savedRejection by AppState.savedRejection.collectAsState()
                    // 付费墙三格（S5-f）：镜像与另两格同源（activated 是磁盘件的镜像，见 AppState 注释）
                    val activated by AppState.activated.collectAsState()
                    val activationTail by AppState.activationTail.collectAsState()
                    val proRejection by AppState.proRejection.collectAsState()
                    val activationMessage by AppState.activationMessage.collectAsState()
                    // 激活对话框：只在点「Activate」时开，成功/取消都收（解锁态本身常驻下面那格回显，不靠对话框）
                    var showActivation by remember { mutableStateOf(false) }
                    var activationDraft by remember { mutableStateOf("") }
                    // `running` 从 Column 里提到这一层：存档红字的过期边要按它作废 RUNNING 档（下面那条
                    // LaunchedEffect），而过期判据只认状态、不认文本——两处各 collect 一次才是两套真值。
                    val running by AppState.running.collectAsState()
                    // 编译产物到达即进任务框；用户随后手改，建议流即刻作废（不夺字）
                    LaunchedEffect(suggestion) {
                        suggestion?.let {
                            taskJson = it
                            RecorderStore.suggestedTaskJson.value = null
                        }
                    }
                    // 首进界面把已存配置回填到屏上（只填还空着的字段，用户正在敲的字不夺）
                    LaunchedEffect(Unit) { byok.refreshFromVault() }
                    // 首进界面（本 effect 的键在首次组合也会触发一次）把存档列表摆上屏——判据 3
                    // "冷启（进程重进）仍在"的前半格；此后每次执行/编译归位都以盘为准重读一次，
                    // 顺带让纯函数复核红字（RUNNING／COMPILING／NOT_ACTIVATED 是**纯状态档**，状态走了话术必须跟着走，
                    // 留着就是假红——S5-f 把 activated 并进来：用户刚解锁还看见"Upgrade to Pro"就是那一形态）。
                    LaunchedEffect(running, compileBusy, activated) { refreshSaved() }
                    Column(
                        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        val connected by AppState.serviceConnected.collectAsState()
                        val report by AppState.lastRunReport.collectAsState()
                        Text("Anytouch executor", style = MaterialTheme.typography.headlineSmall)
                        // 激活面（S5-f 军令 §1「首页加 Activate 按钮，点开弹出激活码输入框」）。
                        // 这一钮**永不置灰**：未激活要点得开、已激活也点得开（改码/复核同一条通道），
                        // 解锁态常驻下面那格，不靠对话框活着。
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Button(
                                onClick = {
                                    // 打开输码框=开始新的一次尝试：上一次的结论先撤（它绑的是那一次输码）
                                    activationDraft = ""
                                    AppState.activationMessage.value = null
                                    showActivation = true
                                },
                                modifier = Modifier.testTag("activate"),
                            ) { Text(ActivationCopy.BUTTON) }
                            Text(
                                if (activated) {
                                    ActivationCopy.unlocked(maskedTail(activationTail))
                                } else {
                                    "${UPGRADE_MARK}: not activated on this device. " +
                                        "Recording, compiling and running tasks stay free."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.testTag("activation_state"),
                            )
                        }
                        // 同一句输码结论：框开着在框里说（[ActivationDialog]），框关着在这里说。
                        // 只留一份文字、两处按互斥呈现——冷启动走注入通道时框是关的，没这一格那句拒因就永不上屏。
                        if (!showActivation) {
                            activationMessage?.let {
                                Text(
                                    it,
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.testTag("activation_rejection"),
                                )
                            }
                        }
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
                            // 未激活只灰"补一步"那一枚（删/改名/移序是录制面本来就有的能力，圈进墙里=越界）
                            proUnlocked = activated,
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
                        if (!activated) {
                            Text(
                                lockedHint(ProFeature.MANUAL_STEP_INSERT),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.testTag("pro_hint_step_insert"),
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
                        // 「我的任务」（S5-e 要求 2 / 三裁②"走同一个现有落账口，不另起存储"）。
                        // 这一面只做三件事：命名存、载入、删除——载入与 AI 编译/模板**共用同一个落账口**
                        // （`acceptModelActions(origin="saved")`），"载入即执行"仍走同一个派发口（`submitTask`）。
                        // 屏上不出现第二条通道：RUNNING 档、词表档、编译互斥、V-3 框账比对一条都不因"这是存档"而绕开。
                        Text("My tasks", style = MaterialTheme.typography.titleSmall)
                        if (!activated) {
                            Text(
                                lockedHint(ProFeature.SAVED_TASKS),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.testTag("pro_hint_saved_tasks"),
                            )
                        }
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            OutlinedTextField(
                                value = saveName,
                                onValueChange = { saveName = it },
                                modifier = Modifier.weight(1f, fill = false).testTag("save_task_name"),
                                label = { Text("Name for this task") },
                                singleLine = true,
                            )
                            // 存一条**不置灰编译**：它读此刻屏上那本账、写进自己的文件，不动账本本身，
                            // 因此不属于编译互斥要拦的那四个入口（改账/换账才拦）。编译回来换的是账，不是已存的文件。
                            // 未激活即置灰（S5-f 军令 §3 三入口之一）：灰只是提示，门禁在 saveCurrentTask 入口，
                            // `--es task_save` 绕过按钮同样落 NOT_ACTIVATED 档（同 step_insert 那条先例）。
                            Button(
                                onClick = { saveCurrentTask(saveName, "ui_button") },
                                enabled = activated,
                                modifier = Modifier.testTag("save_task"),
                            ) { Text("Save this task") }
                        }                        // 空表提示只在"确实读到了空表"时说：读不出时这句"No saved tasks yet"就是假陈述，
                        // 那时该说的是下面那格红字（盘读不出）。
                        if (savedTasks.isEmpty() && savedRejection == null) {
                            Text(
                                "No saved tasks yet: record or compile a task, type a name above, " +
                                    "then tap “Save this task”.",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.testTag("saved_empty"),
                            )
                        }
                        savedTasks.forEachIndexed { index, task ->
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    task.name,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    modifier = Modifier.testTag("saved_task_$index"),
                                )
                                // 置灰只是提示：真门禁在 loadIntoLedger 入口、落账口与派发口，
                                // adb 注入绕过按钮同样被拒（下面 saved_rejection 那格就是它的红字）。
                                Button(
                                    onClick = { loadSavedTask(task.name, "ui_button") },
                                    // 未激活即置灰（S5-f 军令 §3 三入口之一）：门禁在 loadIntoLedger 入口
                                    enabled = activated && !compileBusy,
                                    modifier = Modifier.testTag("saved_load_$index"),
                                ) { Text("Load") }
                                Button(
                                    onClick = {
                                        runSavedTask(
                                            task.name,
                                            "ui_button",
                                            repeatCount,
                                            repeatInterval,
                                            repeatNoAsk,
                                        )
                                    },
                                    // 与「Run task」同一档前置：服务不在场放了也不动（派发口那头的判据不变）。
                                    // 未激活即置灰：这一枚在墙内（存档直跑=我的任务那一枚功能），
                                    // 而下面那枚「Run task」在墙外——两者不是一个入口，别顺手一起灰。
                                    enabled = connected && !compileBusy && activated,
                                    modifier = Modifier.testTag("saved_run_$index"),
                                ) { Text("Run") }
                                Button(
                                    onClick = { deleteSavedTask(task.name, "ui_button") },
                                    // 未激活即置灰：门禁在 deleteSavedTask 入口（删的是自己的存档，属墙内）
                                    enabled = activated,
                                    modifier = Modifier.testTag("saved_delete_$index"),
                                ) { Text("Delete") }
                            }
                        }
                        savedRejection?.let {
                            Text(
                                it,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.testTag("saved_rejection"),
                            )
                        }
                        OutlinedTextField(
                            value = taskJson,
                            onValueChange = { taskJson = it },
                            modifier = Modifier.fillMaxWidth().weight(1f, fill = false).testTag("task_input"),
                            minLines = 8,
                        )
                        // 重复执行（S5-d 军令第 1 条）：两框一勾。这里的值**只进 RepeatPolicy.parse**，
                        // UI 不自带第二份判据；解析坏了走 task_rejection 那格红字，不静默按默认跑。
                        // 未激活即置灰（S5-f 军令 §3）：两框灰、**那一勾不灰**——勾改的是高危确认语义，
                        // 属"绝不进墙"清单（附页 §2）：付费墙不许改变任何一条安全语义。
                        // 灰只是提示：默认值就是 1（单发），未激活用户照样能跑单发；真门禁在 submitTask 入口，
                        // `--es repeat_count 2` 绕过界面同样被拒。
                        if (!activated) {
                            Text(
                                lockedHint(ProFeature.REPEAT_LOOP),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.testTag("pro_hint_repeat_loop"),
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = repeatCount,
                                onValueChange = { repeatCount = it },
                                enabled = activated,
                                modifier = Modifier.weight(1f, fill = false).testTag("repeat_count"),
                                label = { Text("Repetitions (1-100)") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                            )
                            OutlinedTextField(
                                value = repeatInterval,
                                onValueChange = { repeatInterval = it },
                                enabled = activated,
                                modifier = Modifier.weight(1f, fill = false).testTag("repeat_interval"),
                                label = { Text("Interval seconds (1-60)") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                            )
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Checkbox(
                                checked = repeatNoAsk,
                                onCheckedChange = { repeatNoAsk = it },
                                modifier = Modifier.testTag("repeat_no_ask"),
                            )
                            Text("Repeat without asking", style = MaterialTheme.typography.bodySmall)
                        }
                        // 这一勾改的是高危确认的语义，必须把"勾了什么会少问一次"写在屏上（裁决 S5-R11 补裁 C）
                        Text(
                            "Every round asks for confirmation by default. \"Repeat without asking\" only skips " +
                                "the panel for a risk you already approved during this run; a new kind of risk " +
                                "still asks.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Button(
                            onClick = {
                                submitTask(
                                    taskJson,
                                    "ui_button",
                                    repeatCount,
                                    repeatInterval,
                                    repeatNoAsk,
                                )
                            },
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
                        // 付费墙拦下"带轮数的派发"另开一格（同 record/record_stop 分格那条律：
                        // 两件事同时红时不许互相盖——V-3 的框账不符与这一句完全可以同框）
                        proRejection?.let {
                            Text(
                                it,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.testTag("pro_rejection"),
                            )
                        }
                        report?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 18,
                                // 挂 tag 的唯一理由：设备面要能**证伪**"报告上屏了没有"——没有 tag，
                                // 任何"屏上没有 repeats 字段"的断言都能在看不见报告的情况下恒真通过（假绿）。
                                modifier = Modifier.testTag("run_report"),
                            )
                        }
                        // 激活码输入框（军令 §1"点开弹出激活码输入框"）
                        if (showActivation) {
                            ActivationDialog(
                                draft = activationDraft,
                                onDraft = { activationDraft = it },
                                message = activationMessage,
                                onConfirm = { submitActivation(activationDraft, "ui_button") },
                                onDismiss = { showActivation = false },
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

    override fun onDestroy() {
        // 收掉存档那条串行线：窗都不在了，排队中的刷新没有该去的屏（写口本身是同目录改名，不会留半本）。
        savedScope.cancel()
        savedExecutor.shutdown()
        super.onDestroy()
    }

    private fun handleTrigger(intent: Intent?) {
        intent ?: return
        var handled = false
        // 激活注入通道排在**所有**被门禁圈的通道之前：同一条 intent 里既给码又派发时，
        // 判据必须看见"已经激活"——反过来就成了"注入永远进不了墙"的假绿（测试通道与真人同一条校验路径）。
        // 先复位再输码：两个 extra 同时给出时语义是"从零重解一次"。
        if (intent.getBooleanExtra(EXTRA_ACTIVATION_RESET, false)) {
            resetActivation("adb_inject")
            handled = true
        }
        intent.getStringExtra(EXTRA_ACTIVATION_CODE)?.let { code ->
            submitActivation(code, "adb_inject")
            handled = true
        }
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
        // 手动补步骤注入通道（S5-e 判据 5/7 的设备面手）：字段与屏上面板一一同号，递的还是同一个
        // StepEdit.Insert、走的还是同一个唯一写口。注意这半段**没有坐标可递**——extra 清单里压根
        // 没有 x/y 两格（三裁③"不开放坐标点击入口"落在通道形状上，不是落在注释上）。
        // 插入位缺失/非数字同样送 -1 哨兵，由越界档拒（与移序那条例外同一条口径）；名字允许空白，
        // 因为 BLANK_NAME 这一档要在设备面可证伪（与 task_save 同一理由）。
        intent.getStringExtra(EXTRA_STEP_INSERT_AT)?.let { rawAt ->
            RecorderStore.applyEdit(
                StepEdit.Insert(
                    index = rawAt.toIntOrNull() ?: -1,
                    step = ManualStep(
                        type = intent.getStringExtra(EXTRA_STEP_INSERT_TYPE).orEmpty(),
                        actionId = intent.getStringExtra(EXTRA_STEP_INSERT_NAME).orEmpty(),
                        resourceId = intent.getStringExtra(EXTRA_STEP_INSERT_RESOURCE_ID).orEmpty(),
                        text = intent.getStringExtra(EXTRA_STEP_INSERT_TEXT).orEmpty(),
                        contentDesc = intent.getStringExtra(EXTRA_STEP_INSERT_CONTENT_DESC).orEmpty(),
                        path = intent.getStringExtra(EXTRA_STEP_INSERT_PATH).orEmpty(),
                        instance = intent.getStringExtra(EXTRA_STEP_INSERT_INSTANCE).orEmpty(),
                        input = intent.getStringExtra(EXTRA_STEP_INSERT_INPUT).orEmpty(),
                        waitMs = intent.getStringExtra(EXTRA_STEP_INSERT_MS).orEmpty(),
                    ),
                ),
            )
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
        // 「我的任务」注入通道（S5-e 判据 3/4 的设备面手）：以**名字**为句柄，与列表行三枚按钮
        // 逐一转调同一函数——门禁、回执、红字全走同一条，通道本身一个字都不判。
        // 为什么按名字不按序号：序号是屏面镜像的位置，两条通道并发删改时序号会指错那条存档
        // （指错=删错/载错，比红字严重得多）；名字本来就是唯一的（重名在存入口就被拒）。
        intent.getStringExtra(EXTRA_TASK_SAVE)?.let { name ->
            saveCurrentTask(name, "adb_inject")
            handled = true
        }
        intent.getStringExtra(EXTRA_SAVED_LOAD)?.takeIf { it.isNotBlank() }?.let { name ->
            loadSavedTask(name, "adb_inject")
            handled = true
        }
        intent.getStringExtra(EXTRA_SAVED_DELETE)?.takeIf { it.isNotBlank() }?.let { name ->
            deleteSavedTask(name, "adb_inject")
            handled = true
        }
        intent.getStringExtra(EXTRA_SAVED_RUN)?.takeIf { it.isNotBlank() }?.let { name ->
            // 与「Run task」那一条共用同一组重复参数（同一解析口、同一判据，不在通道里另定默认）
            runSavedTask(
                name,
                "adb_inject",
                intent.getStringExtra(EXTRA_REPEAT_COUNT),
                intent.getStringExtra(EXTRA_REPEAT_INTERVAL),
                intent.getBooleanExtra(EXTRA_REPEAT_NO_ASK, false),
            )
            handled = true
        }
        intent.getStringExtra(EXTRA_TASK_JSON)?.let { json ->
            // 重复执行的三枚注入参数与界面两框一勾**同一解析口**（RepeatPolicy.parse）：
            // 缺省即单发、即每轮都问（fail-closed）；脏值由 submitTask 拒派发并上屏，不在通道里猜意图。
            submitTask(
                json,
                "adb_inject",
                intent.getStringExtra(EXTRA_REPEAT_COUNT),
                intent.getStringExtra(EXTRA_REPEAT_INTERVAL),
                intent.getBooleanExtra(EXTRA_REPEAT_NO_ASK, false),
            )
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
                        // 话术单源：模板与「我的任务」两条装载来路共用同一句（见 `ledgerRunningCopy`）
                        val copy = ledgerRunningCopy("template")
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

    /**
     * 「我的任务」三件操作（S5-e 要求 2 / 老板三裁②"走同一个现有落账口，不另起存储"）。
     *
     * 本面**一行判据都不写**：存档门禁住 `savedTaskGateOf`、红字过期边住 `savedRejectionAfterChange`、
     * 载入门禁住落账口 `acceptModelActions`、派发门禁住 `submitTask`。接线只做三件事：取数、转调、上屏留痕。
     *
     * **"第二条通道在哪里"的答案：没有第二条。** 载入=预制模板走的那同一条 `acceptModelActions`
     * （origin 换成 "saved"，它只进日志、不改判据），执行=「Run task」那同一条 `submitTask`。
     * 所以 RUNNING 档、词表档、编译互斥、V-3 框账比对、60s TTL 对存档来路逐字同样成立——
     * "这是我存过的"不构成任何豁免。
     *
     * 文件 IO 一律离开主线程（与 Keystore 同一口径），并且**全部排在 `savedScope` 这一条串行线上**：
     * 两条通道并发读写同一件文件时，"后读的那份把先写的那份冲回屏上"就是镜像说谎的入口。
     * **由此带来的设备面纪律**：一次操作的结论是异步回到屏上的，设备断言必须先等
     * `S5ESMOKE saved task` 那一行回执、再读屏——反过来就是把上一格的旧镜像当成这一格的结果（假绿形态）。
     */
    private fun saveCurrentTask(name: String, via: String) {
        if (proBlockedSaved("save", name, via)) return
        savedScope.launch {
            // 存的是**屏上那本步序账**，不是任务框文本：框里可能已被用户手改（那正是 V-3 要拦的两套真值），
            // 账本才是"录出来／编出来／装载进来"的那一份。
            val actions = RecorderStore.compiledActions.value
            when (val outcome = savedStore.save(name, actions)) {
                is SaveOutcome.Saved -> {
                    AppState.setSavedRejection(null, null)
                    Log.i(
                        TAG,
                        "S5ESMOKE saved task op=save ok name=\"${outcome.task.name}\" " +
                            "steps=${outcome.task.actions.size} total=${outcome.total} via=$via",
                    )
                }
                is SaveOutcome.Rejected ->
                    rejectSaved("save", outcome.gate, outcome.name, via, "ledger=${actions.size}")
            }
            refreshSavedFromDisk()
        }
    }

    /**
     * 删一条存档：同样先读盘再写盘（在 `SavedTaskStore` 内），本函数只分流。
     * 删的是存档件里那一条，**不动屏上步序账**——所以它不在编译互斥要拦的那几件事里
     * （与「载入」相对：那一件是整本换账，必须暂停）。
     */
    private fun deleteSavedTask(name: String, via: String) {
        if (proBlockedSaved("delete", name, via)) return
        savedScope.launch {
            when (val outcome = savedStore.delete(name)) {
                is DeleteOutcome.Deleted -> {
                    AppState.setSavedRejection(null, null)
                    Log.i(
                        TAG,
                        "S5ESMOKE saved task op=delete ok name=\"${outcome.name}\" " +
                            "remaining=${outcome.remaining} via=$via",
                    )
                }
                is DeleteOutcome.Rejected ->
                    rejectSaved("delete", outcome.gate, outcome.name, via, "list=${savedStore.list().size}")
            }
            refreshSavedFromDisk()
        }
    }

    /**
     * 载入一条存档（「Load」钮与「Run」钮共用）：读档 → **同一个落账口**整本进账。
     * 返回进账后的任务框文本（派发口要用它与账逐字可比），null=没进账（红字与回执都已到位）。
     *
     * 编译互斥在**入口**把关——落账口刻意不看 `compileBusy`（它要接编译那一跑的产物，看了就自锁死），
     * 所以"换账本的来路"必须在入口自己拒，与 `loadTemplate` 同一分工；RUNNING 档与词表档不在此处复制，
     * 由落账口那两档返回（本函数只转述话术）。
     *
     * 付费墙排在最前（[proBlockedSaved]）：载入与"存档直跑"两条通道共用本函数，一句判据两处生效；
     * 而"这台机器没解锁"比"编译在跑"更前置——未激活用户根本不该走到读盘那一步。
     */
    private fun loadIntoLedger(name: String, via: String): String? {
        if (proBlockedSaved("load", name, via)) return null
        if (AppState.compileBusy.value) {
            rejectSaved("load", SavedTaskGate.COMPILING, name, via, "running=${AppState.running.value}")
            return null
        }
        val task = savedStore.find(name)
        if (task == null) {
            rejectSaved("load", SavedTaskGate.NOT_FOUND, name, via, "list=${savedStore.list().size}")
            return null
        }
        return when (
            val verdict = RecorderStore.acceptModelActions(task.actions, executorSupportedActionTypes, origin = "saved")
        ) {
            is RecorderStore.ModelLedger.Written -> {
                AppState.setSavedRejection(null, null)
                Log.i(
                    TAG,
                    "S5ESMOKE saved task op=load ok name=\"${task.name}\" steps=${verdict.steps} " +
                        "replaced=${verdict.replaced} via=$via",
                )
                // 落账口发布的建议就是这一串（同一个 `encodeActions` 真值），派发口拿它喂 V-3 才逐字可比
                encodeActions(task.actions)
            }
            is RecorderStore.ModelLedger.RefusedRunning -> {
                // 档位存 RUNNING（不是 null）：这句说的是"此刻有任务在跑"，纯状态档——跑完还挂着就是假红。
                // 作废判据住 `savedRejectionAfterChange`，话术与模板来路共用 `ledgerRunningCopy` 那一份。
                rejectSaved("load", SavedTaskGate.RUNNING, task.name, via, "incoming=${task.actions.size}")
                null
            }
            is RecorderStore.ModelLedger.RefusedUnsupportedType -> {
                // 词表档判据在落账口；这一句只补存档来路特有的那一半——编译来路该重编、模板来路是资产
                // 带病、存档来路是"这条存的本数与本 build 的词表脱钩了"。三条建议不同是应该的。
                // 档位存 null：这条拒因是**请求绑定**的（说的是这一条存档的内容），状态跃迁不会让它失去依据，
                // 与 V-3 那一格同律（见 `AppState.taskRejectionGate` 的 null 分支）。
                val copy = "Saved task \"${task.name}\" step ${verdict.index + 1} is type=${verdict.type}, " +
                    "which this build's executor cannot run — not a single step of the whole ledger was " +
                    "written, and the steps on screen stayed exactly as they were. The saved entry is left " +
                    "untouched in your list: re-record that step on this build (or delete the entry) " +
                    "instead of running a half ledger."
                AppState.setSavedRejection(null, copy)
                Log.w(
                    TAG,
                    "S5ESMOKE saved task op=load refused gate=UNSUPPORTED_TYPE name=\"${task.name}\" " +
                        "via=$via index=${verdict.index} type=${verdict.type}",
                )
                null
            }
        }
    }

    /** 「Load」钮／注入：只进账，不派发。 */
    private fun loadSavedTask(name: String, via: String) {
        savedScope.launch { loadIntoLedger(name, via) }
    }

    /** 「Run」钮／注入 = 上面那一条 + 派发口那一条，中间没有任何第三通道（军令"点一下就能载入并直接执行"）。 */
    private fun runSavedTask(
        name: String,
        via: String,
        repetitionsRaw: String?,
        intervalRaw: String?,
        askWithoutPrompt: Boolean,
    ) {
        savedScope.launch {
            val json = loadIntoLedger(name, via) ?: return@launch
            submitTask(json, "saved_$via", repetitionsRaw, intervalRaw, askWithoutPrompt)
        }
    }

    /** 一次存档被拒：档位与话术成对写（过期边认身份不认文本），回执与红字同一条（禁静默"点了没反应"）。 */
    private fun rejectSaved(op: String, gate: SavedTaskGate, name: String, via: String, detail: String) {
        AppState.setSavedRejection(gate, gate.userCopy())
        Log.w(TAG, "S5ESMOKE saved task op=$op refused gate=$gate name=\"$name\" via=$via $detail")
    }

    /**
     * 从盘重读列表并按纯函数复核红字（每写一次之后、执行/编译状态归位之后、首进界面各走一次）。
     * 屏上那份镜像**每次都以盘为准**：内存里不养第二本列表，否则冷启动或另一条通道刚写过文件时
     * 镜像就会说谎（条目数量级也犯不上缓存）。
     *
     * 这里刻意每次操作后都重读、而不是"写完把新列表塞进 flow"：写完再读才是对盘取证
     * （写口已经以字节回读为准，屏面这一层再信一次内存里的乐观值就是两层各自乐观）。
     */
    private fun refreshSavedFromDisk() {
        val outcome = savedStore.read()
        AppState.savedTasks.value = (outcome as? ReadOutcome.Ok)?.tasks ?: emptyList()
        val next = savedRejectionAfterChange(
            AppState.savedRejectionGate,
            outcome,
            AppState.running.value,
            AppState.compileBusy.value,
            AppState.activated.value,
        )
        if (next != AppState.savedRejectionGate) AppState.setSavedRejection(next, next?.userCopy())
    }

    /** [refreshSavedFromDisk] 的异步壳：主线程（LaunchedEffect）只走这一条，不直接碰盘。 */
    private fun refreshSaved() {
        savedScope.launch { refreshSavedFromDisk() }
    }

    private fun submitTask(
        json: String,
        via: String,
        repetitionsRaw: String?,
        intervalRaw: String?,
        askWithoutPrompt: Boolean,
    ) {
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
        // 重复执行的数字在这一格把关（军令第 1 条的 1-100 / 1-60 两条范围）：判据住 RepeatPolicy，
        // 界外一律**不派发**并上屏——静默夹取到 100 或退回默认 1 都是把用户填的数字换掉。
        val repeatVerdict = RepeatPolicy.parse(repetitionsRaw, intervalRaw, askWithoutPrompt)
        if (repeatVerdict is RepeatVerdict.Rejected) {
            AppState.setTaskRejection(null, repeatVerdict.copy)
            Log.w(
                TAG,
                "S5DSMOKE submit refused gate=REPEAT_FIELD field=${repeatVerdict.field} via=$via " +
                    "repetitions=$repetitionsRaw interval=$intervalRaw noAsk=$askWithoutPrompt",
            )
            return
        }
        val plan = (repeatVerdict as RepeatVerdict.Accepted).plan
        // 付费墙只圈"真动用了轮数"的那一次派发（判据住 `proGateOf`，排在这里是因为要先读懂数字）：
        // 未激活用户跑单发必须逐字放行——那是判据 5 的对照格，也是"付费墙不许改变免费面语义"那条自钉。
        if (proGateForRepeat(plan, via)) return
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
        Log.i(
            TAG,
            "S5DSMOKE submit accepted via=$via reps=${plan.repetitions} interval=${plan.intervalSec}s " +
                "askEveryRound=${plan.askEveryRound}",
        )
        AppState.submit(json, plan)
    }

    /**
     * 一次输码（对话框「Unlock」与 adb 注入 `--es activation_code` **同一入口、同一校验器**）：
     * 判据住 `ActivationStore`/`ActivationCode`，此处只做三件事——分流、上屏、留痕。
     * 测试通道**没有旁路**：这一枚 extra 不接受"直接置位"，它送的还是那 18 个字符，
     * 脏码在这里同样落拒因档（与真人逐字同一条路径，附页 §3 判据 10）。
     *
     * @return true=已解锁且已落盘（对话框据此收起；被拒时框留着，让人改那一位错的字）。
     */
    private fun submitActivation(rawCode: String, via: String): Boolean {
        val verdict = activationStore.submit(rawCode)
        val unlocked = verdict == ActivationVerdict.UNLOCKED
        publishActivation(activationStore.state())
        if (unlocked) {
            AppState.activationMessage.value = null
            // 只回显尾四位：整枚能解锁的串不进日志（与 API Key 同律，红线 H 的精神面）
            Log.i(TAG, "S5FSMOKE activation ok tail=${activationStore.state().tail} via=$via")
        } else {
            val copy = ActivationCopy.refusal(verdict)
            AppState.activationMessage.value = copy
            Log.w(TAG, "S5FSMOKE activation refused gate=$verdict via=$via detail=$copy")
        }
        return unlocked
    }

    /**
     * 复位本机激活态（**只给测试通道**：判据 5 的"未激活那一态"要能在同一枚 apk 上被证伪，
     * 而用户侧的等价动作是"清除应用数据"，本批不做取消激活的界面，附页 §4）。
     * 撤flag 之后仍走 [publishActivation]：三处入口的解锁态与那三句提示必须一起翻回"未激活"。
     */
    private fun resetActivation(via: String) {
        val done = activationStore.reset()
        publishActivation(activationStore.state())
        AppState.activationMessage.value = null
        Log.i(TAG, "S5FSMOKE activation reset ok=$done via=$via")
    }

    /**
     * 磁盘态 → 屏面镜像的**唯一写处**（四条来路：冷启动 / 对话框解锁 / 注入解锁 / 注入复位）。
     * 顺序在此钉死：**先翻镜像，再复核付费墙红字**——过期边读的就是 [AppState.activated] 这一格，
     * 反过来那条 "Upgrade to Pro" 就成了撤不掉的假红（假红与假绿同罪）。
     * 存档格那一句不在这里撤：它由 `LaunchedEffect(running, compileBusy, activated)` 那一条
     * 顺带重读盘时复核（撤红字与刷新列表是同一次采样，不许两份结论）。
     */
    private fun publishActivation(state: ActivationState) {
        AppState.activated.value = state.activated
        AppState.activationTail.value = state.tail
        RecorderStore.revalidateEditRejection()
        val next = proRejectionAfterChange(AppState.proRejectionGate, state.activated)
        if (next != AppState.proRejectionGate) {
            AppState.setProRejection(next, next?.userCopy(ProFeature.REPEAT_LOOP))
            Log.i(TAG, "S5FSMOKE pro rejection expired activated=${state.activated}")
        }
    }

    /**
     * 付费墙拦下派发那一格（三枚进墙功能里唯一住在派发口的：重复循环）。
     * **排在 `RepeatPolicy.parse` 之后**：只有把用户填的数字读懂了，才知道这一次到底"动用"没动用轮数
     * （[RepeatPlan.isSingleShot]）——未激活用户跑单发必须逐字放行，那是判据 5 的对照格。
     * 也排在 V-3 框账比对之前：那一句说的是"这一次根本不该派发"，比"框与账对不对得上"更前置。
     */
    private fun proGateForRepeat(plan: RepeatPlan, via: String): Boolean {
        val gate = proGateOf(
            ProFeature.REPEAT_LOOP,
            exercised = !plan.isSingleShot,
            activated = AppState.activated.value,
        ) ?: return false
        val copy = gate.userCopy(ProFeature.REPEAT_LOOP)
        AppState.setProRejection(gate, copy)
        Log.w(
            TAG,
            "S5FSMOKE pro refused feature=REPEAT_LOOP gate=$gate via=$via " +
                "reps=${plan.repetitions} interval=${plan.intervalSec}s detail=$copy",
        )
        return true
    }

    /**
     * 付费墙拦下存档那一格（军令 §3 三枚进墙功能之一）：**四条通道（存/载/删/存档直跑）在各自入口判**，
     * 判据住 [proGateOf]，档位与话术成对写进 `saved_rejection` 那一格（[rejectSaved] 那条纪律一字不改）。
     *
     * 为什么判在 MainActivity 而不在 `SavedTaskStore`：磁盘件那一侧管的是重名/空账/脏文件，
     * 它不该知道"这台机器买没买"；而四条通道在这里已经收口成三处（载入与直跑共用 [loadIntoLedger]），
     * 一句判据三处转调，按钮与 `--es task_save` 都绕不过去。
     */
    private fun proBlockedSaved(op: String, name: String, via: String): Boolean {
        val gate = proGateOf(ProFeature.SAVED_TASKS, exercised = true, activated = AppState.activated.value)
            ?: return false
        rejectSaved(op, SavedTaskGate.NOT_ACTIVATED, name, via, "activated=false feature=$gate")
        return true
    }

    /**
     * 激活码输入框（军令 §1"点开弹出输入框"）。
     *
     * **整棵子树自己带一次 `testTagsAsResourceId`**：对话框是另一个窗口，首页 `Surface` 上那层语义
     * 配置不会跨窗传播——不在这里再带一次，设备面 uiautomator 就看不见这三枚 tag，
     * "框上了屏"那条断言会在**根本看不见框**的情况下恒真通过（假绿形态，判据 1 就废了）。
     */
    @Composable
    private fun ActivationDialog(
        draft: String,
        onDraft: (String) -> Unit,
        message: String?,
        onConfirm: () -> Unit,
        onDismiss: () -> Unit,
    ) {
        Dialog(onDismissRequest = onDismiss) {
            Column(
                Modifier.fillMaxWidth().padding(20.dp).semantics { testTagsAsResourceId = true },
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(ActivationCopy.TITLE, style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = draft,
                    onValueChange = onDraft,
                    label = { Text(ActivationCopy.FIELD_LABEL) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                    modifier = Modifier.fillMaxWidth().testTag("activation_input"),
                )
                Text(ActivationCopy.SCOPE, style = MaterialTheme.typography.bodySmall)
                // 同一句结论在框内说时，首页那一格让位（`if (!showActivation)`）：一句话两处呈现，只留一份文字
                message?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag("activation_rejection"),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onConfirm,
                        modifier = Modifier.testTag("activation_confirm"),
                    ) { Text(ActivationCopy.CONFIRM) }
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.testTag("activation_cancel"),
                    ) { Text(ActivationCopy.CANCEL) }
                }
            }
        }
    }

    companion object {
        /** 与执行器/录制面同一 tag：冒烟脚本按 `-s AnytouchRun:*` 过滤，换 tag 即断言失明。 */
        private const val TAG = "AnytouchRun"

        const val EXTRA_TASK_JSON = "task_json"
        /**
         * 重复执行注入通道（S5-d）：与界面两框一勾同一解析口、同一判据（`RepeatPolicy`）。
         * 三枚都可缺省——缺省=单发+每轮都问，与旧口径逐字同义（不注入就等于本批之前的行为）。
         */
        const val EXTRA_REPEAT_COUNT = "repeat_count"
        const val EXTRA_REPEAT_INTERVAL = "repeat_interval"
        const val EXTRA_REPEAT_NO_ASK = "repeat_no_ask"
        const val EXTRA_KEEP_FG = "keep_fg"
        const val EXTRA_RECORD_START = "record_start"
        const val EXTRA_RECORD_STOP = "record_stop"
        const val EXTRA_SESSION_JSON = "session_json"
        /** 预制模板装载（S5-a）：只认注册表 id，脏 id 由装载器拒并上屏，不在注入通道猜意图。 */
        const val EXTRA_TEMPLATE_LOAD = "template_load"
        /**
         * 「我的任务」四枚注入通道（S5-e）：值都是**存档名字**。
         * `task_save` 刻意不 `takeIf { isNotBlank() }`——空名正是 [com.anytouch.app.recorder.SavedTaskGate.BLANK_NAME]
         * 那一条判据要能在设备面被证伪的输入；其余三枚空串没有指代对象，直接当"没下发"。
         */
        const val EXTRA_TASK_SAVE = "task_save"
        const val EXTRA_SAVED_LOAD = "saved_load"
        const val EXTRA_SAVED_RUN = "saved_run"
        const val EXTRA_SAVED_DELETE = "saved_delete"
        const val EXTRA_AI_INTENT = "ai_intent"
        const val EXTRA_AI_COMPILE = "ai_compile"

        /** 只认 on/off 两个字面（判据在 [byokContextFlagOf]）：别的写法一律不改动当前开关。 */
        const val EXTRA_CTX_ENABLED = "ctx_enabled"
        const val EXTRA_STEP_REMOVE = "step_remove"
        const val EXTRA_STEP_RENAME = "step_rename"
        const val EXTRA_STEP_RENAME_TO = "step_rename_to"
        const val EXTRA_STEP_MOVE = "step_move"
        const val EXTRA_STEP_MOVE_TO = "step_move_to"

        // 手动补步骤（S5-e 要求 3）：插入位 + 面板那九格，一格一个 extra，通道内零判据
        const val EXTRA_STEP_INSERT_AT = "step_insert_at"
        const val EXTRA_STEP_INSERT_TYPE = "step_insert_type"
        const val EXTRA_STEP_INSERT_NAME = "step_insert_name"
        const val EXTRA_STEP_INSERT_RESOURCE_ID = "step_insert_resource_id"
        const val EXTRA_STEP_INSERT_TEXT = "step_insert_text"
        const val EXTRA_STEP_INSERT_CONTENT_DESC = "step_insert_content_desc"
        const val EXTRA_STEP_INSERT_PATH = "step_insert_path"
        const val EXTRA_STEP_INSERT_INSTANCE = "step_insert_instance"
        const val EXTRA_STEP_INSERT_INPUT = "step_insert_input"
        const val EXTRA_STEP_INSERT_MS = "step_insert_ms"

        /**
         * 激活码注入通道（S5-f 判据 10）：`activation_code` 送的还是那 18 个字符，走的是**同一个**
         * 校验器（`ActivationCode.classify`）——测试通道没有"直接置位"这扇后门，脏码同样落拒因档。
         * `activation_reset` 只把本机激活态清掉（用于证伪"未激活那一态"），不参与校验路径。
         */
        const val EXTRA_ACTIVATION_CODE = "activation_code"
        const val EXTRA_ACTIVATION_RESET = "activation_reset"

        /** 冒烟任务：Connected devices → Connection preferences → Bluetooth（模拟器实测可三级钻取；真机口径属 T3）。门禁显式放行。 */
        const val SAMPLE_TASK =
            """[{"action_id":"cd","type":"click","source":"node","value":{"text":"Connected devices"},"safety":{"viewport_ok":true,"click_enabled":true}},{"action_id":"cp","type":"click","source":"node","value":{"text":"Connection preferences"},"safety":{"viewport_ok":true,"click_enabled":true}},{"action_id":"bt","type":"click","source":"node","value":{"text":"Bluetooth"},"safety":{"viewport_ok":true,"click_enabled":true}}]"""
    }
}
