package com.anytouch.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 任务总线策略（TTL + consume）——"陈旧注入绝不因服务重绑而偷跑"的 JVM 侧防线。
 * 背景：设备实测复现"注入后服务未绑，数分钟后重绑，旧任务自动开跑"（StateFlow 头重放）。
 */
class AppStateTest {

    @Test
    fun `TTL 内请求不过期`() {
        val r = TaskRequest(id = 1L, json = "[]", submittedAtMs = 1_000_000L)
        assertFalse(AppState.isExpired(r, nowMs = 1_000_000L + TaskPolicy.TTL_MS))
    }

    @Test
    fun `超过 TTL 一毫秒即过期`() {
        val r = TaskRequest(id = 1L, json = "[]", submittedAtMs = 1_000_000L)
        assertTrue(AppState.isExpired(r, nowMs = 1_000_000L + TaskPolicy.TTL_MS + 1))
    }

    @Test
    fun `submit 落新鲜请求且可被消费作废`() {
        AppState.submit("[]")
        val head = AppState.taskRequests.value!!
        assertFalse(AppState.isExpired(head))
        AppState.consume(TaskRequest(id = 999L, json = "other"))
        assertEquals(head.id, AppState.taskRequests.value?.id)
        AppState.consume(head)
        assertNull(AppState.taskRequests.value)
    }
}
