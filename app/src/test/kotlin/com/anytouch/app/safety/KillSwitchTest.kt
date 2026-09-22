package com.anytouch.app.safety

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KillSwitchTest {

    @BeforeTest
    fun setUp() = KillSwitch.reset()

    @AfterTest
    fun tearDown() = KillSwitch.reset()

    @Test
    fun `初始为运行态且flow可见当前信号`() = runBlocking {
        assertFalse(KillSwitch.isStopped())
        assertNull(KillSwitch.state.first())
    }

    @Test
    fun `stop后isStopped与flow同时翻转并携带现场`() = runBlocking {
        assertTrue(KillSwitch.stop(reason = "user_tapped_stop_ball", source = "stop_ball"))
        assertTrue(KillSwitch.isStopped())
        val signal = KillSwitch.state.first()
        assertNotNull(signal)
        assertEquals("user_tapped_stop_ball", signal.reason)
        assertEquals("stop_ball", signal.source)
    }

    @Test
    fun `stop幂等且首信号不被覆盖`() {
        assertTrue(KillSwitch.stop(reason = "first"))
        assertFalse(KillSwitch.stop(reason = "second"))
        assertEquals("first", KillSwitch.snapshot()?.reason)
    }

    @Test
    fun `reset恢复运行态可再次停止`() {
        KillSwitch.stop()
        KillSwitch.reset()
        assertFalse(KillSwitch.isStopped())
        assertTrue(KillSwitch.stop(reason = "again"))
        assertEquals("again", KillSwitch.snapshot()?.reason)
    }
}
