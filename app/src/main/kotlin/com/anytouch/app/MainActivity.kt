package com.anytouch.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 任务注入窗：手点（Run）与 adb（--es task_json，冒烟脚本用）双通道写 AppState.taskRequests。
 * adb 通道触发后立刻退到后台，让目标 App（如系统设置）成为活动窗口。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        var initial by mutableStateOf(SAMPLE_TASK)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    var taskJson by mutableStateOf(initial)
                    Column(
                        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        val connected by AppState.serviceConnected.collectAsState()
                        val running by AppState.running.collectAsState()
                        val report by AppState.lastRunReport.collectAsState()
                        Text("Anytouch S1 执行器", style = MaterialTheme.typography.headlineSmall)
                        Text(if (connected) "无障碍服务：已连接" else "无障碍服务：未连接（先启用）")
                        Text(if (running) "任务执行中…（可点悬浮球停止）" else "空闲")
                        OutlinedTextField(
                            value = taskJson,
                            onValueChange = { taskJson = it },
                            modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                            minLines = 8,
                        )
                        Button(onClick = { AppState.submit(taskJson) }) { Text("执行任务") }
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
        val json = intent?.getStringExtra(EXTRA_TASK_JSON) ?: return
        AppState.submit(json)
        moveTaskToBack(true)
    }

    companion object {
        const val EXTRA_TASK_JSON = "task_json"

        /** 冒烟任务：Connected devices → Connection preferences → Bluetooth（模拟器实测可三级钻取；真机口径属 T3）。门禁显式放行。 */
        const val SAMPLE_TASK =
            """[{"action_id":"cd","type":"click","source":"node","value":{"text":"Connected devices"},"safety":{"viewport_ok":true,"click_enabled":true}},{"action_id":"cp","type":"click","source":"node","value":{"text":"Connection preferences"},"safety":{"viewport_ok":true,"click_enabled":true}},{"action_id":"bt","type":"click","source":"node","value":{"text":"Bluetooth"},"safety":{"viewport_ok":true,"click_enabled":true}}]"""
    }
}
