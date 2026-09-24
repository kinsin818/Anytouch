package com.anytouch.app.platform

import com.anytouch.app.compile.ByokPreflight
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 开录门禁（军令 L1/L2 + 红线 E）——"缺项即拒"的纯函数锁。
 * 为什么必须有 JVM 例：门禁曾在 Compose 的 `enabled=` 表达式里，单测覆盖不到，
 * 而"服务没连上却开了一段空录制并报告成功"是第 8 雷（静默空录）形态，只靠设备冒烟抓不住回归。
 */
class AccessibilityGateTest {

    @Test
    fun `四项齐备才 READY`() {
        assertEquals(
            RecordGate.READY,
            recordGateOf(serviceConnected = true, running = false, ballAttached = true, compileBusy = false),
        )
    }

    @Test
    fun `服务未连即拒，且优先级最高`() {
        // 执行中/球未挂/编译在跑时同样不能放行：缺服务=采集钩子不触发，开录必是空会话
        assertEquals(
            RecordGate.SERVICE_OFF,
            recordGateOf(serviceConnected = false, running = false, ballAttached = false, compileBusy = false),
        )
        assertEquals(
            RecordGate.SERVICE_OFF,
            recordGateOf(serviceConnected = false, running = true, ballAttached = true, compileBusy = true),
        )
    }

    @Test
    fun `执行中即拒（录进去的是执行器自己的手）`() {
        assertEquals(
            RecordGate.RUNNING,
            recordGateOf(serviceConnected = true, running = true, ballAttached = true, compileBusy = false),
        )
    }

    @Test
    fun `挂不上球即拒（L1 原判据，不做看不见球的降级录制）`() {
        assertEquals(
            RecordGate.BALL_UNAVAILABLE,
            recordGateOf(serviceConnected = true, running = false, ballAttached = false, compileBusy = false),
        )
    }

    // ---- 裁决 S3-R4-1：编译在跑 = 录制面互斥（跟"执行中禁编辑"一个逻辑）----

    @Test
    fun `编译在跑即拒开录（其余三项齐备也不放行）`() {
        assertEquals(
            RecordGate.COMPILING,
            recordGateOf(serviceConnected = true, running = false, ballAttached = true, compileBusy = true),
        )
    }

    @Test
    fun `执行中优先于编译中（账本先归执行器，别让两把锁互相伪装）`() {
        assertEquals(
            RecordGate.RUNNING,
            recordGateOf(serviceConnected = true, running = true, ballAttached = true, compileBusy = true),
        )
    }

    @Test
    fun `球挂不上优先于编译中（长期缺项先说：编译归了那句话还成立，过期边才不会留下失去依据的红字）`() {
        assertEquals(
            RecordGate.BALL_UNAVAILABLE,
            recordGateOf(serviceConnected = true, running = false, ballAttached = false, compileBusy = true),
        )
    }

    @Test
    fun `停止并编译两档：编译中拒、否则 READY（会话在不在不归门禁判）`() {
        assertEquals(RecordGate.COMPILING, stopCompileGateOf(compileBusy = true))
        assertEquals(RecordGate.READY, stopCompileGateOf(compileBusy = false))
    }

    @Test
    fun `两个入口共用一份判据与一句话术（一份判据两处用，改一处漏一处=串状态）`() {
        val viaStart = recordGateOf(
            serviceConnected = true,
            running = false,
            ballAttached = true,
            compileBusy = true,
        )
        val viaStop = stopCompileGateOf(compileBusy = true)
        assertEquals(viaStart, viaStop)
        assertEquals(viaStart.userCopy(), viaStop.userCopy())
    }

    @Test
    fun `录制面被挡与编译侧不开第二跑，是两句不同的话（同一句会把用户引到错误的环节）`() {
        assertNotEquals(ByokPreflight.copyOf(ByokPreflight.Gate.BUSY_COMPILE), RecordGate.COMPILING.userCopy())
    }

    @Test
    fun `READY 无话术，其余四档必须有人读归因`() {
        assertNull(RecordGate.READY.userCopy())
        assertNotNull(RecordGate.SERVICE_OFF.userCopy())
        assertNotNull(RecordGate.RUNNING.userCopy())
        assertNotNull(RecordGate.BALL_UNAVAILABLE.userCopy())
        assertNotNull(RecordGate.COMPILING.userCopy())
    }

    @Test
    fun `未连接话术含立即可见动作与禁用结论（L2-① 字面判据）`() {
        val copy = RecordGate.SERVICE_OFF.userCopy().orEmpty()
        assertEquals(true, copy.contains("right away"))
        assertEquals(true, copy.contains("cannot start"))
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
    fun `设备实证过的假红形态：执行中转假且四项齐备，RUNNING 话术必须已作废`() {
        // 复现 evidence/S2/raw/recui-probe-stale-red-20260923.log：红字挂着→注入开录→门禁判 READY
        val gate = recordGateOf(
            serviceConnected = true,
            running = false,
            ballAttached = true,
            compileBusy = false,
        )
        assertEquals(RecordGate.READY, gate)
        assertNull(startRejectionAfterStateChange(RecordGate.RUNNING.userCopy(), gate))
    }

    // ---- 停止并编译拒因的过期边（裁决 S3-R4-1，与 V-2 同一条纪律）----

    @Test
    fun `编译归位即作废停止话术（跑完还挂着「编译还在路上」=假红）`() {
        assertNull(stopRejectionAfterStateChange(RecordGate.COMPILING, compileBusy = false))
    }

    @Test
    fun `编译还在跑时停止话术原样保留（不许把有效拒因悄悄抹掉）`() {
        assertEquals(RecordGate.COMPILING, stopRejectionAfterStateChange(RecordGate.COMPILING, compileBusy = true))
    }

    @Test
    fun `停止面无拒因时不凭空造档位`() {
        assertNull(stopRejectionAfterStateChange(null, compileBusy = true))
        assertNull(stopRejectionAfterStateChange(null, compileBusy = false))
    }

    @Test
    fun `非持有档不许被编译过期边顺手抹掉（过期边只认自己那一档）`() {
        listOf(RecordGate.SERVICE_OFF, RecordGate.RUNNING, RecordGate.BALL_UNAVAILABLE).forEach {
            assertEquals(it, stopRejectionAfterStateChange(it, compileBusy = false), "$it 不归这条边管")
        }
    }

    // ---- 裁决 S31-B2 / S3-F-F1：编译互斥从录制两面扩到「执行任务」入口（同一格判据，不另写一份）----

    @Test
    fun `执行面真值表四格：只有都不忙才 READY`() {
        assertEquals(RecordGate.READY, runGateOf(running = false, compileBusy = false))
        assertEquals(RecordGate.COMPILING, runGateOf(running = false, compileBusy = true))
        assertEquals(RecordGate.RUNNING, runGateOf(running = true, compileBusy = false))
        assertEquals(RecordGate.RUNNING, runGateOf(running = true, compileBusy = true))
    }

    @Test
    fun `执行面与停止面共用同一个编译判据（两处各写一份 if 就是串状态）`() {
        listOf(true, false).forEach { busy ->
            assertEquals(
                stopCompileGateOf(busy),
                runGateOf(running = false, compileBusy = busy),
                "running=false 时执行面必须与停止面同判：编译档只有一个真值来源",
            )
        }
    }

    @Test
    fun `执行中优先于编译中（派发面同录制面口径：账本先归执行器）`() {
        assertEquals(RecordGate.RUNNING, runGateOf(running = true, compileBusy = true))
    }

    @Test
    fun `执行面话术齐备且 COMPILING 引用共用头一句（裁 S31-B2 要求那句在四个入口都成立）`() {
        val compiling = requireNotNull(RecordGate.COMPILING.runUserCopy())
        assertTrue(compiling.startsWith(COMPILE_HOLD_HEADLINE), "派发口的编译话术没引用共用头一句：$compiling")
        val viaRecord = requireNotNull(
            recordGateOf(serviceConnected = true, running = false, ballAttached = true, compileBusy = true)
                .runUserCopy(),
        )
        val viaStop = requireNotNull(stopCompileGateOf(compileBusy = true).runUserCopy())
        listOf(viaRecord, viaStop).forEach { copy ->
            assertTrue(copy.startsWith(COMPILE_HOLD_HEADLINE), "录制两面的头一句分叉了：$copy")
        }
        // 编辑面（StepEditGate.COMPILING）的同一条断言住在 StepEditingTest：本文件在 platform 包里
        // 引 recorder 的同名扩展 userCopy 会和自家那份撞，不在这里绕。
    }

    @Test
    fun `RUNNING 话术按入口分表：派发口不得复读开录口的话`() {
        val viaRun = requireNotNull(RecordGate.RUNNING.runUserCopy())
        val viaRecord = requireNotNull(RecordGate.RUNNING.userCopy())
        assertNotEquals(viaRecord, viaRun, "派发口挂上「不能开录」=把另一件事说成这件事（话术面串状态）")
        assertTrue(viaRun.contains("dispatch"), viaRun)
        assertTrue(viaRecord.contains("start recording"), viaRecord)
        // 其余档回落到同一份表：派发口不另写一句"编译中"
        assertEquals(RecordGate.COMPILING.userCopy(), RecordGate.COMPILING.runUserCopy())
        assertEquals(RecordGate.SERVICE_OFF.userCopy(), RecordGate.SERVICE_OFF.runUserCopy())
        assertNull(RecordGate.READY.runUserCopy())
    }

    @Test
    fun `派发拒因过期边：门禁转 READY 即作废，仍判拒时原样保留`() {
        assertNull(taskRejectionAfterStateChange(RecordGate.COMPILING, running = false, compileBusy = false))
        assertNull(taskRejectionAfterStateChange(RecordGate.RUNNING, running = false, compileBusy = false))
        assertEquals(RecordGate.COMPILING, taskRejectionAfterStateChange(RecordGate.COMPILING, running = false, compileBusy = true))
        assertEquals(RecordGate.RUNNING, taskRejectionAfterStateChange(RecordGate.RUNNING, running = true, compileBusy = false))
    }

    @Test
    fun `编译归位但任务还在跑时派发红字不得被抹掉（撤字条件是整个门禁 READY）`() {
        assertEquals(
            RecordGate.COMPILING,
            taskRejectionAfterStateChange(RecordGate.COMPILING, running = true, compileBusy = false),
            "此刻仍该拒（执行中），把上一句撤了屏上就没有任何一句归因",
        )
    }

    @Test
    fun `请求绑定的派发拒因（档位存 null）永不被状态跃迁抹掉`() {
        // V-3 的"框账不符"绑用户那一次点击：调用方存 null，状态怎么跳都不许替它作决定。
        assertNull(taskRejectionAfterStateChange(null, running = true, compileBusy = true))
        assertNull(taskRejectionAfterStateChange(null, running = false, compileBusy = false))
    }
}
