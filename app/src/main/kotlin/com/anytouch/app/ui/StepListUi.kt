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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.anytouch.app.recorder.StepEdit
import com.anytouch.app.recorder.StepEditGate
import com.anytouch.app.recorder.stepLabel
import com.anytouch.app.recorder.userCopy
import com.anytouch.contracts.Action

/**
 * 步序账编辑区（军令 L2-9 的 UI 面）：删步 / 改名 / 移序，全部经 [onEdit] 送进
 * RecorderStore 的唯一写口——本组件不含任何"能不能编辑"的判断（置灰不是门禁），
 * 只做呈现与投递；被拒话术由调用方显示（与开录拒绝同一条显示路径）。
 *
 * [editable]=false 时行内四个操作置灰并显式说明原因（执行中禁编辑，老板 09-23 裁决）。
 * 置灰只是**给人看的提示**：真正的门禁在唯一写口 `applyEdit` 里，adb 注入通道绕过这些按钮
 * 也一样被拒（同一判据、同一条留痕），所以这里灰不灰都不影响安全性，只影响可见性。
 */
@Composable
fun StepListEditor(
    actions: List<Action>,
    onEdit: (StepEdit) -> Unit,
    modifier: Modifier = Modifier,
    editable: Boolean = true,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Steps ${actions.size} (delete / rename / reorder only touch this step ledger)", style = MaterialTheme.typography.titleSmall)
        if (actions.isEmpty()) {
            Text(
                "The step ledger is empty: record first and tap “Stop & compile”, only then do steps " +
                    "appear here.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (!editable && actions.isNotEmpty()) {
            Text(
                StepEditGate.RUNNING.userCopy().orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("step_edit_locked_hint"),
            )
        }
        actions.forEachIndexed { i, action -> StepRow(i, action, actions.size, onEdit, editable) }
    }
}

@Composable
private fun StepRow(
    index: Int,
    action: Action,
    total: Int,
    onEdit: (StepEdit) -> Unit,
    editable: Boolean,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("#$index ${stepLabel(action)}", style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // remember(action.actionId)：底层步骤一变（删/移导致下标漂移）草稿即回落到真值，
            // 不留"第二份名字"在本地状态里赖着——那是步序账之外的第二个真值源。
            var draft by remember(action.actionId) { mutableStateOf(action.actionId) }
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.weight(1f).testTag("step_rename_field_$index"),
                singleLine = true,
                label = { Text("Step name") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            )
            OutlinedButton(
                onClick = { onEdit(StepEdit.Rename(index, draft)) },
                enabled = editable,
                modifier = Modifier.testTag("step_rename_$index"),
            ) { Text("Rename") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { onEdit(StepEdit.Move(index, index - 1)) },
                enabled = editable && index > 0,
                modifier = Modifier.testTag("step_up_$index"),
            ) { Text("Up") }
            OutlinedButton(
                onClick = { onEdit(StepEdit.Move(index, index + 1)) },
                enabled = editable && index < total - 1,
                modifier = Modifier.testTag("step_down_$index"),
            ) { Text("Down") }
            Button(
                onClick = { onEdit(StepEdit.Remove(index)) },
                enabled = editable,
                modifier = Modifier.testTag("step_delete_$index"),
            ) { Text("Delete") }
        }
    }
}
