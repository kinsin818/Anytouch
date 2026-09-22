package com.anytouch.pipeline

import com.anytouch.contracts.Action
import com.anytouch.contracts.ActionResult

/**
 * 执行器后端最小接口 —— STAGE-02 Mock 闭环专用；S1 真执行器在此接口上扩展。
 * 契约类型全部复用 com.anytouch.contracts，本接口不引入新契约。
 */
interface ExecutorBackend {
    fun act(action: Action): ActionResult
}
