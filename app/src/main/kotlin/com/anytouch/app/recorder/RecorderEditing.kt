package com.anytouch.app.recorder

import com.anytouch.contracts.Action

/**
 * 步骤编辑原语（军令 L2-9）：纯列表函数，输入输出均 List<Action>，
 * 步骤间零耦合——actionId 钉死源事件下标、定位线索各自独立，删/改/移不波及其他步骤。
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
