package com.anytouch.app

import android.content.Intent
import android.os.Bundle
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
import com.anytouch.app.recorder.session.RecorderStore

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
                        Text(if (connected) "无障碍服务：已连接" else "无障碍服务：未连接（先启用）")
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
                        OutlinedTextField(
                            value = taskJson,
                            onValueChange = { taskJson = it },
                            modifier = Modifier.fillMaxWidth().weight(1f, fill = false).testTag("task_input"),
                            minLines = 8,
                        )
                        Button(
                            onClick = { AppState.submit(taskJson) },
                            enabled = connected,
                            modifier = Modifier.testTag("run_task"),
                        ) { Text("执行任务") }
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
        intent.getStringExtra(EXTRA_SESSION_JSON)?.takeIf { it.isNotBlank() }?.let { json ->
            // 预置会话注入（冒烟通道）：合法与否由 RecorderStore 留痕归因，此处不重复判
            RecorderStore.injectSerialized(json)
            handled = true
        }
        intent.getStringExtra(EXTRA_TASK_JSON)?.let { json ->
            AppState.submit(json)
            handled = true
        }
        if (handled && !intent.getBooleanExtra(EXTRA_KEEP_FG, false)) moveTaskToBack(true)
    }

    companion object {
        const val EXTRA_TASK_JSON = "task_json"
        const val EXTRA_KEEP_FG = "keep_fg"
        const val EXTRA_RECORD_START = "record_start"
        const val EXTRA_RECORD_STOP = "record_stop"
        const val EXTRA_SESSION_JSON = "session_json"

        /** 冒烟任务：Connected devices → Connection preferences → Bluetooth（模拟器实测可三级钻取；真机口径属 T3）。门禁显式放行。 */
        const val SAMPLE_TASK =
            """[{"action_id":"cd","type":"click","source":"node","value":{"text":"Connected devices"},"safety":{"viewport_ok":true,"click_enabled":true}},{"action_id":"cp","type":"click","source":"node","value":{"text":"Connection preferences"},"safety":{"viewport_ok":true,"click_enabled":true}},{"action_id":"bt","type":"click","source":"node","value":{"text":"Bluetooth"},"safety":{"viewport_ok":true,"click_enabled":true}}]"""
    }
}
