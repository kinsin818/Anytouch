package com.anytouch.app.recorder

import com.anytouch.contracts.Action

/**
 * 步骤编辑原语（军令 L2-9 + S5-e 要求 3）：纯列表函数，输入输出均 List<Action>，
 * 步骤间零耦合——actionId 钉死源事件下标、定位线索各自独立，删/改/移/插不波及其他步骤。
 */
fun removeStep(actions: List<Action>, index: Int): List<Action> {
    require(index in actions.indices) { "step index $index out of bounds for ${actions.size} steps" }
    return actions.filterIndexed { i, _ -> i != index }
}

fun renameStep(actions: List<Action>, index: Int, newActionId: String): List<Action> {
    require(index in actions.indices) { "step index $index out of bounds for ${actions.size} steps" }
    require(newActionId.isNotBlank()) { "newActionId must not be blank" }
    return actions.mapIndexed { i, action -> if (i == index) action.copy(actionId = newActionId) else action }
}

fun moveStep(actions: List<Action>, from: Int, to: Int): List<Action> {
    require(from in actions.indices && to in actions.indices) {
        "move $from -> $to out of bounds for ${actions.size} steps"
    }
    if (from == to) return actions
    val reordered = actions.toMutableList()
    reordered.add(to, reordered.removeAt(from))
    return reordered
}

/**
 * 插一步（S5-e 要求 3）。区间与其余三条**不同**：`0..size`，末尾+1=追加，
 * 因为插入位说的是"落在哪两条之间"，不是"动哪一条"（门禁同一条区间，见 `stepEditGateOf`）。
 * 只收现成的 [Action]：形状判据（类型白名单、必填线索、禁坐标）住 `buildManualStep` 那一处，
 * 这里不复制一份——两条通道各自 `require` 一遍就是雷 18 的写法。
 */
fun insertStep(actions: List<Action>, index: Int, action: Action): List<Action> {
    require(index in 0..actions.size) { "insert index $index out of bounds for ${actions.size} steps" }
    require(action.actionId.isNotBlank()) { "inserted step must carry a non-blank actionId" }
    return actions.subList(0, index) + action + actions.subList(index, actions.size)
}
