package com.anytouch.contracts

/**
 * 测试用 Action 构造助手（仅 test 源码集），供 PipelineTest 直测 MockBackend。
 */
object PipelineFixtures {
    fun action(
        actionId: String,
        viewportOk: Boolean,
        clickEnabled: Boolean,
        type: String = ActionType.CLICK,
        target: Point? = Point(1, 1),
    ): Action = Action(
        actionId = actionId,
        type = type,
        target = target,
        source = ActionSource.NODE,
        safety = ActionSafety(
            viewportOk = viewportOk,
            clickEnabled = clickEnabled,
            requiresTransition = false,
        ),
    )
}
