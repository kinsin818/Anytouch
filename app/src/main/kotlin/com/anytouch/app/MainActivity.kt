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
import com.anytouch.app.platform.RecordGate
import com.anytouch.app.platform.userCopy
import com.anytouch.app.recorder.StepEdit
import com.anytouch.app.recorder.encodeActions
import com.anytouch.app.recorder.session.RecorderStore
import com.anytouch.app.ui.StepListEditor

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
                    val recording by RecorderStore.activeSession.collectAsState()
                    val suggestion by RecorderStore.suggestedTaskJson.collectAsState()
                    val startRejection by RecorderStore.startRejection.collectAsState()
                    val steps by RecorderStore.compiledActions.collectAsState()
                    val editRejection by RecorderStore.editRejection.collectAsState()
                    val taskRejection by AppState.taskRejection.collectAsState()
                    // 编译产物到达即进任务框；用户随后手改，建议流即刻作废（不夺字）
                    LaunchedEffect(suggestion) {
                        suggestion?.let {
                            taskJson = it
                            RecorderStore.suggestedTaskJson.value = null
                        }
                    }
                    Column(
                        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        val connected by AppState.serviceConnected.collectAsState()
                        val running by AppState.running.collectAsState()
                        val report by AppState.lastRunReport.collectAsState()
                        Text("Anytouch S1 执行器", style = MaterialTheme.typography.headlineSmall)
                        // L2-①：未连接即首启引导必现（同一话术单源于 AccessibilityGate，UI 不各写一份）
                        Text(
                            if (connected) "无障碍服务：已连接"
                            else RecordGate.SERVICE_OFF.userCopy().orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(if (running) "任务执行中…（可点悬浮球停止）" else "空闲")
                        OutlinedTextField(
                            value = targetPkg,
                            onValueChange = { targetPkg = it },
                            modifier = Modifier.fillMaxWidth().testTag("target_pkg"),
                            label = { Text("录制目标包名") },
                            singleLine = true,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { RecorderStore.start(targetPkg.trim()) },
                                enabled = connected && recording == null,
                                modifier = Modifier.testTag("record_start"),
                            ) { Text("开始录制") }
                            Button(
                                onClick = { RecorderStore.stopAndCompile() },
                                enabled = recording != null,
                                modifier = Modifier.testTag("record_stop"),
                            ) { Text("停止并编译") }
                        }
                        Text(
                            if (recording != null && RecorderStore.isRecording) "录制中：只收目标包窗口内动作" else "未在录制",
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
                        StepListEditor(
                            actions = steps,
                            onEdit = RecorderStore::applyEdit,
                            // 执行中置灰（老板 09-23 裁决：禁编辑门禁，停止球位置因此不动）。
                            // 灰只是提示——门禁在 applyEdit，adb 注入绕过按钮同样被拒。
                            editable = !running,
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
                        OutlinedTextField(
                            value = taskJson,
                            onValueChange = { taskJson = it },
                            modifier = Modifier.fillMaxWidth().weight(1f, fill = false).testTag("task_input"),
                            minLines = 8,
                        )
                        Button(
                            onClick = { submitTask(taskJson, "ui_button") },
                            enabled = connected,
                            modifier = Modifier.testTag("run_task"),
                        ) { Text("执行任务") }
                        // V-3：被拦下的派发必须显形（与开录/编辑拒因同律：静默"点了没反应"=黑洞）
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
        intent.getStringExtra(EXTRA_SESSION_JSON)?.takeIf { it.isNotBlank() }?.let { json ->
            // 预置会话注入（冒烟通道）：合法与否由 RecorderStore 留痕归因，此处不重复判
            RecorderStore.injectSerialized(json)
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
     */
    private fun submitTask(json: String, via: String) {
        val ledger = RecorderStore.compiledActions.value
        val verdict = taskAdmission(
            boxJson = json,
            ledgerJson = encodeActions(ledger),
            lastSuggestion = RecorderStore.lastSuggestedJson,
        )
        if (verdict != TaskAdmission.ACCEPT) {
            val copy = verdict.userCopy()
            AppState.taskRejection.value = copy
            Log.w(
                TAG,
                "S1SMOKE submit refused gate=$verdict via=$via ledger=${ledger.size} " +
                    "machineSuggestion=${RecorderStore.lastSuggestedJson?.length} detail=$copy",
            )
            return
        }
        AppState.taskRejection.value = null
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
