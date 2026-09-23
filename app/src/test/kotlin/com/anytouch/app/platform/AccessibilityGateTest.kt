package com.anytouch.app.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * 开录门禁（军令 L1/L2 + 红线 E）——"缺项即拒"的纯函数锁。
 * 为什么必须有 JVM 例：门禁曾在 Compose 的 `enabled=` 表达式里，单测覆盖不到，
 * 而"服务没连上却开了一段空录制并报告成功"是第 8 雷（静默空录）形态，只靠设备冒烟抓不住回归。
 */
class AccessibilityGateTest {

    @Test
    fun `三项齐备才 READY`() {
        assertEquals(RecordGate.READY, recordGateOf(serviceConnected = true, running = false, ballAttached = true))
    }

    @Test
    fun `服务未连即拒，且优先级最高`() {
        // 执行中/球未挂时同样不能放行：缺服务=采集钩子不触发，开录必是空会话
        assertEquals(RecordGate.SERVICE_OFF, recordGateOf(serviceConnected = false, running = false, ballAttached = false))
        assertEquals(RecordGate.SERVICE_OFF, recordGateOf(serviceConnected = false, running = true, ballAttached = true))
    }

    @Test
    fun `执行中即拒（录进去的是执行器自己的手）`() {
        assertEquals(RecordGate.RUNNING, recordGateOf(serviceConnected = true, running = true, ballAttached = true))
    }

    @Test
    fun `挂不上球即拒（L1 原判据，不做看不见球的降级录制）`() {
        assertEquals(
            RecordGate.BALL_UNAVAILABLE,
            recordGateOf(serviceConnected = true, running = false, ballAttached = false),
        )
    }

    @Test
    fun `READY 无话术，其余三档必须有人读归因`() {
        assertNull(RecordGate.READY.userCopy())
        assertNotNull(RecordGate.SERVICE_OFF.userCopy())
        assertNotNull(RecordGate.RUNNING.userCopy())
        assertNotNull(RecordGate.BALL_UNAVAILABLE.userCopy())
    }

    @Test
    fun `未连接话术含立即可见动作与禁用结论（L2-① 字面判据）`() {
        val copy = RecordGate.SERVICE_OFF.userCopy().orEmpty()
        assertEquals(true, copy.contains("请立即"))
        assertEquals(true, copy.contains("不能开始录制"))
    }

    // ---- V-2（老板 09-23 裁决）：拒因的过期边 ----

    @Test
    fun `门禁转 READY 即作废陈旧拒因（空闲态不许再挂着执行中话术）`() {
        val stale = RecordGate.RUNNING.userCopy()
        assertNotNull(stale)
        assertNull(startRejectionAfterStateChange(stale, RecordGate.READY))
    }

    @Test
    fun `仍判拒时话术原样保留（不许把有效拒因悄悄抹掉）`() {
        val copy = RecordGate.SERVICE_OFF.userCopy()
        assertEquals(copy, startRejectionAfterStateChange(copy, RecordGate.RUNNING))
        assertEquals(copy, startRejectionAfterStateChange(copy, RecordGate.SERVICE_OFF))
        assertEquals(copy, startRejectionAfterStateChange(copy, RecordGate.BALL_UNAVAILABLE))
    }

    @Test
    fun `无拒因时不凭空造话术`() {
        assertNull(startRejectionAfterStateChange(null, RecordGate.READY))
        assertNull(startRejectionAfterStateChange(null, RecordGate.RUNNING))
    }

    @Test
    fun `设备实证过的假红形态：执行中转假且三项齐备，RUNNING 话术必须已作废`() {
        // 复现 evidence/S2/raw/recui-probe-stale-red-20260923.log：红字挂着→注入开录→门禁判 READY
        val gate = recordGateOf(serviceConnected = true, running = false, ballAttached = true)
        assertEquals(RecordGate.READY, gate)
        assertNull(startRejectionAfterStateChange(RecordGate.RUNNING.userCopy(), gate))
    }
}
