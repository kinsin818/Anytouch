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
import com.anytouch.app.recorder.stepLabel
import com.anytouch.contracts.Action

/**
 * 步序账编辑区（军令 L2-9 的 UI 面）：删步 / 改名 / 移序，全部经 [onEdit] 送进
 * RecorderStore 的唯一写口——本组件不含任何"能不能编辑"的判断（置灰不是门禁），
 * 只做呈现与投递；被拒话术由调用方显示（与开录拒绝同一条显示路径）。
 */
@Composable
fun StepListEditor(
    actions: List<Action>,
    onEdit: (StepEdit) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("步骤 ${actions.size}（删/改名/移序只改这份步序账）", style = MaterialTheme.typography.titleSmall)
        if (actions.isEmpty()) {
            Text(
                "步序账为空：先录制并点「停止并编译」，这里才会出现步骤。",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        actions.forEachIndexed { i, action -> StepRow(i, action, actions.size, onEdit) }
    }
}

@Composable
private fun StepRow(
    index: Int,
    action: Action,
    total: Int,
    onEdit: (StepEdit) -> Unit,
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
                label = { Text("步骤名") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            )
            OutlinedButton(
                onClick = { onEdit(StepEdit.Rename(index, draft)) },
                modifier = Modifier.testTag("step_rename_$index"),
            ) { Text("改名") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { onEdit(StepEdit.Move(index, index - 1)) },
                enabled = index > 0,
                modifier = Modifier.testTag("step_up_$index"),
            ) { Text("上移") }
            OutlinedButton(
                onClick = { onEdit(StepEdit.Move(index, index + 1)) },
                enabled = index < total - 1,
                modifier = Modifier.testTag("step_down_$index"),
            ) { Text("下移") }
            Button(
                onClick = { onEdit(StepEdit.Remove(index)) },
                modifier = Modifier.testTag("step_delete_$index"),
            ) { Text("删除") }
        }
    }
}
