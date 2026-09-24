package com.anytouch.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.anytouch.app.compile.ByokGateway

/**
 * BYOK 面板（切片 D 的 UI 面）：意图 → AI 编译 → 步序账，外加"填自己的 Key"这一路。
 *
 * 本组件**不含判据**：能不能出门问 [ByokGateway] 里的预检，落不落账问 RecorderStore 的唯一写口，
 * 灰不置灰都只是提示（adb 注入通道绕过按钮也一样被拒）。所有话术都是纯函数算好的原文，
 * 这里只负责"必须显示"（L2-③：静默禁用=黑洞）。
 *
 * Key 输入框只在内存里活到「保存」那一下：点完保存立即清空（屏上不留明文，也不留"看起来还填着"的错觉），
 * 之后屏上只有尾 4 位。地址与模型名不是秘密，原样可读写。
 */
@Composable
fun ByokPanel(
    gateway: ByokGateway,
    modifier: Modifier = Modifier,
) {
    val state = gateway.state
    val busy by state.busy.collectAsState()
    val ctxEnabled by state.contextEnabled.collectAsState()
    val report by state.report.collectAsState()
    val intentDraft by state.intent.collectAsState()
    val keyTail by state.keyTail.collectAsState()
    val baseUrl by state.baseUrl.collectAsState()
    val model by state.model.collectAsState()
    val configMessage by state.configMessage.collectAsState()
    val hostNotice by state.hostNotice.collectAsState()
    var keyField by remember { mutableStateOf("") }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("AI compile (bring your own key — network is used only when creating a task)", style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(
            value = intentDraft,
            onValueChange = { state.intent.value = it },
            modifier = Modifier.fillMaxWidth().testTag("ai_intent"),
            label = { Text("Say what to do in one sentence (e.g. open the Bluetooth page)") },
            minLines = 2,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = ctxEnabled,
                onCheckedChange = { state.contextEnabled.value = it },
                modifier = Modifier.testTag("ai_ctx_switch"),
            )
            Text(
                "Attach the visible text of the current screen (only on-screen labels and control ids are " +
                    "sent; text-field contents, password fields and screenshots never are)",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Button(
            onClick = { gateway.compile(state.intent.value) },
            enabled = !busy,
            modifier = Modifier.testTag("ai_compile"),
        ) { Text(if (busy) "AI is compiling…" else "AI compile") }
        if (busy) {
            Text(
                "AI is compiling: this run sends one request to the address below and takes from a few " +
                    "seconds up to tens of seconds.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("ai_busy"),
            )
        }
        report?.let {
            Text(
                it.userCopy,
                color = if (it.published) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("ai_report"),
            )
            it.context?.let { ctx ->
                // 上行账目必须上屏（裁 2）：几条、屏上多少节点、各档剔了多少
                Text(
                    ctx.notice(),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("ai_ctx_notice"),
                )
            }
        }
        Text("BYOK configuration (kept encrypted on this device in the Keystore, never sent anywhere else)", style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(
            value = baseUrl,
            onValueChange = { state.baseUrl.value = it },
            modifier = Modifier.fillMaxWidth().testTag("byok_base_url"),
            label = { Text("Service URL (OpenAI-compatible endpoint, https only)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        )
        OutlinedTextField(
            value = model,
            onValueChange = { state.model.value = it },
            modifier = Modifier.fillMaxWidth().testTag("byok_model"),
            label = { Text("Model name") },
            singleLine = true,
        )
        OutlinedTextField(
            value = keyField,
            onValueChange = { keyField = it },
            modifier = Modifier.fillMaxWidth().testTag("byok_key"),
            label = { Text("API key (after saving, only its last 4 digits stay on screen)") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    gateway.save(keyField, baseUrl, model)
                    keyField = ""
                },
                modifier = Modifier.testTag("byok_save"),
            ) { Text("Save") }
            OutlinedButton(
                onClick = { gateway.clear() },
                modifier = Modifier.testTag("byok_clear"),
            ) { Text("Erase device credentials") }
        }
        Text(
            keyTail?.let { "Key saved on this device: $it" } ?: "No key saved on this device",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.testTag("byok_key_tail"),
        )
        hostNotice?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("byok_host_notice"),
            )
        }
        configMessage?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("byok_config_message"),
            )
        }
    }
}
