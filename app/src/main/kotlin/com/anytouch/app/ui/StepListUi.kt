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
import com.anytouch.app.recorder.ManualStep
import com.anytouch.app.recorder.StepEdit
import com.anytouch.app.recorder.StepEditGate
import com.anytouch.app.recorder.manualInsertableTypes
import com.anytouch.app.recorder.stepLabel
import com.anytouch.app.recorder.userCopy
import com.anytouch.contracts.Action
import com.anytouch.contracts.ActionType

/**
 * 步序账编辑区（军令 L2-9 的 UI 面 + S5-e 要求 3）：删步 / 改名 / 移序 / 手动补一步，
 * 全部经 [onEdit] 送进 RecorderStore 的唯一写口——本组件不含任何"能不能编辑"的判断（置灰不是门禁），
 * 只做呈现与投递；被拒话术由调用方显示（与开录拒绝同一条显示路径）。
 *
 * [editable]=false 时行内操作置灰并显式说明原因（执行中禁编辑，老板 09-23 裁决）。
 * 置灰只是**给人看的提示**：真正的门禁在唯一写口 `applyEdit` 里，adb 注入通道绕过这些按钮
 * 也一样被拒（同一判据、同一条留痕），所以这里灰不灰都不影响安全性，只影响可见性。
 *
 * [onEdit] 返回写口的结论：插步面板要按它决定"收起来还是留在屏上让人改"——
 * 拒了却自动收起=用户填的东西蒸发（"点了没反应"那条黑洞律）。
 */
@Composable
fun StepListEditor(
    actions: List<Action>,
    onEdit: (StepEdit) -> Boolean,
    modifier: Modifier = Modifier,
    editable: Boolean = true,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Steps ${actions.size} (add / delete / rename / reorder here; only this step ledger is touched)",
            style = MaterialTheme.typography.titleSmall,
        )
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
    onEdit: (StepEdit) -> Boolean,
    editable: Boolean,
) {
    var insertOpen by remember(action.actionId) { mutableStateOf(false) }
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
            // 军令原句"每一步后面加个+按钮"：这一行的 "+" 插在本步**之后**（插入位 = index+1），
            // 于是最后一步的 "+" 就是追加。想插在最前面：先插再 Up（移序本来就管这个）。
            OutlinedButton(
                onClick = { insertOpen = !insertOpen },
                modifier = Modifier.testTag("step_insert_toggle_$index"),
            ) { Text(if (insertOpen) "Cancel +" else "+ Step") }
        }
        if (insertOpen) {
            ManualStepPanel(
                position = index + 1,
                rowIndex = index,
                editable = editable,
                onAdd = { step -> onEdit(StepEdit.Insert(index + 1, step)) },
                onClose = { insertOpen = false },
            )
        }
    }
}

/**
 * 手动补一步的面板（S5-e 要求 3 / 三裁③）。
 *
 * **这里没有坐标可填，一格都没有**（执行器不读 `target` 定位，填了也不生效，见 [ManualStep] 的说明）。
 * 面板只交出一格 [ManualStep] 形状的数据，判据一律在纯函数那一侧（`stepEditGateOf`）——
 * 面板不预筛、不自带门禁：能点出去的就送进去，被拒的话术由调用方上屏（本面只负责不收起来）。
 * 类型按钮直接列 [manualInsertableTypes]（真源那一格），加一种类型这里跟着多一个钮，不另立清单。
 */
@Composable
private fun ManualStepPanel(
    position: Int,
    rowIndex: Int,
    editable: Boolean,
    onAdd: (ManualStep) -> Boolean,
    onClose: () -> Unit,
) {
    var type by remember(position) { mutableStateOf(ActionType.CLICK) }
    var name by remember(position) { mutableStateOf("") }
    var resourceId by remember(position) { mutableStateOf("") }
    var screenText by remember(position) { mutableStateOf("") }
    var contentDesc by remember(position) { mutableStateOf("") }
    var path by remember(position) { mutableStateOf("") }
    var instance by remember(position) { mutableStateOf("") }
    var input by remember(position) { mutableStateOf("") }
    var waitMs by remember(position) { mutableStateOf("") }
    val needsClue = type != ActionType.WAIT

    Column(
        modifier = Modifier.fillMaxWidth().testTag("step_insert_panel_$rowIndex"),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("Adds one step at position #$position", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            manualInsertableTypes.forEach { option ->
                // 选中的那条实心、其余描边：屏上要看得出当前填的是哪一类的面板
                if (type == option) {
                    Button(
                        onClick = { type = option },
                        modifier = Modifier.testTag("step_insert_type_$option"),
                    ) { Text(manualTypeLabel(option)) }
                } else {
                    OutlinedButton(
                        onClick = { type = option },
                        modifier = Modifier.testTag("step_insert_type_$option"),
                    ) { Text(manualTypeLabel(option)) }
                }
            }
        }
        StepField("Step name", "step_insert_name_$rowIndex", name) { name = it }
        if (needsClue) {
            StepField("Resource id", "step_insert_resource_id_$rowIndex", resourceId) { resourceId = it }
            StepField("Text on screen", "step_insert_text_$rowIndex", screenText) { screenText = it }
            StepField("Content description", "step_insert_content_desc_$rowIndex", contentDesc) { contentDesc = it }
            StepField("Hierarchy path", "step_insert_path_$rowIndex", path) { path = it }
            StepField("Which match (0 = first)", "step_insert_instance_$rowIndex", instance) { instance = it }
        }
        if (type == ActionType.TYPE_TEXT) {
            StepField("Text to type", "step_insert_input_$rowIndex", input) { input = it }
        }
        if (type == ActionType.WAIT) {
            StepField("Wait milliseconds", "step_insert_ms_$rowIndex", waitMs) { waitMs = it }
        }
        // 三裁③这条边界要写在屏上而不是只写在工单里：用户看到"没有坐标格"才知道不是漏了，
        // 而是这个应用按节点树找目标（与执行器的定位口径同一条）。
        Text(
            "Steps find their target on the accessibility node tree, so there is no x/y to fill in here.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.testTag("step_insert_no_coordinate_hint"),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    val accepted = onAdd(
                        ManualStep(
                            type = type,
                            actionId = name,
                            resourceId = resourceId,
                            text = screenText,
                            contentDesc = contentDesc,
                            path = path,
                            instance = instance,
                            input = input,
                            waitMs = waitMs,
                        ),
                    )
                    // 成功才收起；被拒留在屏上等用户改那一格（话术在列表下方那一格红字里）
                    if (accepted) onClose()
                },
                enabled = editable,
                modifier = Modifier.testTag("step_add_$rowIndex"),
            ) { Text("Add step") }
            OutlinedButton(onClick = onClose, modifier = Modifier.testTag("step_insert_cancel_$rowIndex")) {
                Text("Cancel")
            }
        }
    }
}

@Composable
private fun StepField(
    label: String,
    tag: String,
    value: String,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth().testTag(tag),
        singleLine = true,
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        textStyle = MaterialTheme.typography.bodySmall,
    )
}

private fun manualTypeLabel(type: String): String = when (type) {
    ActionType.CLICK -> "Click"
    ActionType.TYPE_TEXT -> "Type text"
    ActionType.WAIT -> "Wait"
    else -> type
}
