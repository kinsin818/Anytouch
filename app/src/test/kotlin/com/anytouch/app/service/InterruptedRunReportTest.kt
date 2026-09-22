package com.anytouch.app.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 服务生命周期中断回执（第 8 项）：取消路径必须给出"执行中断、结果不可信"的显式报告，
 * 而不是让 UI 停留在上一条陈旧回执上。背景：设备实证服务被重启后在跑任务无声消失。
 */
class InterruptedRunReportTest {

    @Test
    fun `中断回执标记停止且结果集为空并注明不可信`() {
        val root = Json.parseToJsonElement(interruptedRunReport("Job was cancelled")).jsonObject
        assertEquals(true, root.getValue("stopped").jsonPrimitive.content.toBoolean())
        assertTrue(root.getValue("results").jsonArray.isEmpty())
        val payload = root.getValue("stop_command").jsonObject.getValue("payload").jsonObject
        assertEquals("SERVICE_INTERRUPTED", payload.getValue("stop_code").jsonPrimitive.content)
        assertEquals("Job was cancelled", payload.getValue("stop_reason").jsonPrimitive.content)
        assertTrue(payload.getValue("note").jsonPrimitive.content.contains("不代表"))
    }

    @Test
    fun `中断回执是合法 stop 命令且每次调用 commandId 唯一`() {
        val a = Json.parseToJsonElement(interruptedRunReport("x")).jsonObject["stop_command"]!!.jsonObject
        val b = Json.parseToJsonElement(interruptedRunReport("x")).jsonObject["stop_command"]!!.jsonObject
        assertEquals("stop", a.getValue("type").jsonPrimitive.content)
        assertEquals("stopped", a.getValue("confirm").jsonPrimitive.content)
        val idA = a.getValue("command_id").jsonPrimitive.content
        val idB = b.getValue("command_id").jsonPrimitive.content
        assertTrue(idA != idB, "两次调用 commandId 不得相同: $idA")
    }
}
