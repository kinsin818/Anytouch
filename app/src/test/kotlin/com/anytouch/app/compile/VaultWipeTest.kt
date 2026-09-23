package com.anytouch.app.compile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * "清除"判定（军令 S3 §3-3：别名与密文文件双双消失）。
 *
 * 设备侧只提供两个槽位适配器，判定全在这份零 Android 代码里，所以能在 JVM 逐条锁：
 * 单边成功不算清干净、没配过不算失败、擦除抛错按"仍在"处理。
 */
class VaultWipeTest {

    private class Slot(
        override val name: String,
        private var present: Boolean,
        private val eraseThrows: Boolean = false,
        private val eraseFails: Boolean = false,
    ) : WipeSlot {
        var erases = 0
        override fun exists(): Boolean = present
        override fun erase() {
            erases++
            if (eraseThrows) throw IllegalStateException("rom refused")
            if (!eraseFails) present = false
        }
    }

    @Test
    fun `两处都在且都擦掉 才算清除成功`() {
        val file = Slot("本机凭据文件", present = true)
        val alias = Slot("系统密钥库别名", present = true)
        val r = wipeSlots(listOf(file, alias))
        assertTrue(r.cleared, r.userCopy())
        assertEquals(1, file.erases)
        assertEquals(1, alias.erases)
        assertEquals("已清除：本机不再保存任何 Key。", r.userCopy())
    }

    @Test
    fun `单边成功不算清干净 且话术点名为留下的是哪个槽`() {
        val file = Slot("本机凭据文件", present = true)
        val alias = Slot("系统密钥库别名", present = true, eraseFails = true)
        val r = wipeSlots(listOf(file, alias))
        assertFalse(r.cleared, "别名擦不掉却报清除＝假绿")
        assertEquals(listOf("系统密钥库别名"), r.stillThere)
        assertTrue(r.userCopy().contains("系统密钥库别名"), r.userCopy())
    }

    @Test
    fun `擦除抛异常按仍在处理 不算成功`() {
        val r = wipeSlots(listOf(Slot("本机凭据文件", present = true, eraseThrows = true)))
        assertFalse(r.cleared)
        assertEquals(listOf("本机凭据文件"), r.stillThere)
    }

    @Test
    fun `从没配过 两处都不存在 直接算清干净且不动手擦`() {
        val file = Slot("本机凭据文件", present = false)
        val alias = Slot("系统密钥库别名", present = false)
        val r = wipeSlots(listOf(file, alias))
        assertTrue(r.cleared, "首启点清除不该报失败")
        assertEquals(0, file.erases + alias.erases, "不存在就没必要调删除")
    }
}
