package com.anytouch.app.compile

import com.anytouch.app.compile.ByokPreflight.Gate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 面板状态的 JVM 锁（S3-D）：只锁两件"放 Compose 里就只能靠手点验"的事——
 * 不开第二跑、以及新一次开始时陈旧话术必须撤下（V-2 同律：还有效的话术不许抹，已失效的不许留）。
 */
class ByokPanelStateTest {

    private fun report(published: Boolean = false, gate: Gate = Gate.RUNNING) = ByokReport(
        published = published,
        userCopy = "旧结论",
        gate = gate,
    )

    @Test
    fun `第一跑未归时不开第二跑`() {
        val s = ByokPanelState()
        assertTrue(s.beginCompile())
        assertTrue(s.busy.value)
        assertFalse(s.beginCompile(), "同一句话问两次=烧两次钱，而且后回来的产物会静默盖掉先回来的")
        assertEquals(Gate.BUSY_COMPILE, s.report.value!!.gate)
        assertTrue(s.busy.value, "被拒的第二跑不许把第一跑的 busy 一起清掉")
    }

    @Test
    fun `新一次编译开始时撤下上一条陈旧话术`() {
        val s = ByokPanelState()
        s.endCompile(report())
        assertEquals("旧结论", s.report.value!!.userCopy)
        assertTrue(s.beginCompile())
        assertNull(s.report.value, "新一次已经在路上了，还挂着上一条红字就是假红")
        assertTrue(s.busy.value)
    }

    @Test
    fun `结论落地时 busy 必须落位 否则按钮永远灰着`() {
        val s = ByokPanelState()
        s.beginCompile()
        s.endCompile(report(published = true, gate = Gate.READY))
        assertFalse(s.busy.value)
        assertTrue(s.report.value!!.published)
    }

    @Test
    fun `开关默认开 且只有 on 与 off 两个字面能改它`() {
        val s = ByokPanelState()
        assertTrue(s.contextEnabled.value, "S3 裁 2 原文：带屏上下文、可开关——默认是开")
        assertEquals(false, byokContextFlagOf("off"))
        assertEquals(true, byokContextFlagOf("on"))
        assertNull(byokContextFlagOf("true"))
        assertNull(byokContextFlagOf(null))
        byokContextFlagOf("1")?.let { s.contextEnabled.value = it }
        assertTrue(s.contextEnabled.value, "没被认出的写法不得顺手把开关改成别的样子")
    }

    @Test
    fun `清除凭据后屏上不留任何看起来还配着的痕迹`() {
        val s = ByokPanelState()
        s.keyTail.value = "***6789"
        s.hostNotice.value = "你的 Key 只发往这一个地址：a.example"
        s.baseUrl.value = "https://a.example/v1"
        s.model.value = "m"
        s.onCredentialsWiped()
        assertNull(s.keyTail.value)
        assertNull(s.hostNotice.value)
        assertEquals("" , s.baseUrl.value)
        assertEquals("", s.model.value)
    }

    @Test
    fun `待编译那句话只有一格 UI 与注入通道写同一处`() {
        val s = ByokPanelState()
        s.intent.value = "进蓝牙页"
        assertEquals("进蓝牙页", s.intent.value)
        assertFalse(s.intent.value.isBlank())
    }
}
