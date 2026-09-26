package com.anytouch.app.activation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 本机激活态落盘的 JVM 锁（S5-f 军令 §3"激活状态存本机"那一半，老板裁 4 走路 B）。
 *
 * 这里钉的是三件只有纯函数能钉的事：
 * 1. **写成功 ≠ 解锁了**——落盘失败/读回不是那几个字节必须报 `WRITE_FAILED`，绝不报"解锁了"
 *    （与凭据件、存档件同一条纪律：不信 IO 层的沉默）；
 * 2. **脏文件按未激活算**——"文件里有字就算解锁"等于把"随便 touch 一个文件"变成付费绕过；
 * 3. **盘上只有末四位**——能解锁的整串留在设备上没有任何用途，只多一个可抄的东西。
 *
 * 真跑那一半（`install -r` 换进程后仍在）归设备面：`scripts/s5f-activation-smoke.sh` 的冷启动格。
 */
class ActivationStoreTest {

    private val code = "ANY-PRO9-FAN8-B1CE"

    /**
     * 上面那些老用例一律喂"服务器已经点头"这一档：它们钉的是**本地判据与落盘**，
     * 把服务器那一格固定成 [ActivationRemote.Allowed] 才只变一件事（v1.0.5 那六档判据逐字不回退，判据 3）。
     *
     * 顺带钉住本批的顺序：脏码即使配上 Allowed 也照样一个字节都不落盘——
     * **本地判据排在服务器之前**，这就是"形状都没对就别出门"那条省额度规则的机器锁形态。
     */
    private val allowed = ActivationRemote.Allowed(seatsUsed = 1, seatsTotal = 2)

    private class FakeDisk(initial: String? = null) : ActivationDisk {
        var text: String? = initial
        var writable: Boolean = true
        var readable: Boolean = true
        var clears: Boolean = true
        var writeCalls: Int = 0

        override fun read(): String? = if (readable) text else null

        override fun write(text: String): Boolean {
            writeCalls++
            if (!writable) return false
            this.text = text
            return true
        }

        override fun clear(): Boolean {
            if (!clears) return false
            text = null
            return true
        }
    }

    /** 写() 回 true 但 read() 永远 null：只信 IO 层返回值时会漏掉的那一格。 */
    private class LyingDisk : ActivationDisk {
        override fun read(): String? = null
        override fun write(text: String): Boolean = true
        override fun clear(): Boolean = true
    }

    // ---- 1. 输码成功的那一格 ----

    @Test
    fun `golden 码放行后盘上只留末四位 且回读同一份字节`() {
        val disk = FakeDisk()
        val store = ActivationStore(disk)
        assertEquals(ActivationVerdict.UNLOCKED, store.submit(code, allowed))
        assertEquals("B1CE", disk.text, "盘上留的必须是末四位：整枚能解锁的串不许落在设备上")
        assertEquals(1, disk.writeCalls, "算对之前一个字节都不许写：脏码会留下半个解锁态")
        val state = store.state()
        assertTrue(state.activated)
        assertEquals("B1CE", state.tail)
        assertTrue(store.isActivated())
    }

    @Test
    fun `小写与粘贴空白同样解锁 与校验器共用同一条归一口`() {
        val disk = FakeDisk()
        val store = ActivationStore(disk)
        assertEquals(ActivationVerdict.UNLOCKED, store.submit("  ${code.lowercase()} \n", allowed))
        assertEquals("B1CE", disk.text, "盘上留的仍是规范式尾四位（不是小写、不带空白）")
    }

    // ---- 2. 算错一个字节都不写盘 ----

    @Test
    fun `六种脏码一律不落盘 每档原样返回自己的拒因`() {
        listOf(
            "" to ActivationVerdict.EMPTY,
            "XNY-A1B2-C3D4-E5NX" to ActivationVerdict.BAD_PREFIX,
            "ANY-A1B2-C3D4-E5N" to ActivationVerdict.BAD_LENGTH,
            "ANY_A1B2_C3D4_E5NX" to ActivationVerdict.BAD_SEPARATOR,
            "ANY-A1B#-C3D4-E5NX" to ActivationVerdict.BAD_CHARSET,
            "ANY-A1B2-C3D4-E5NY" to ActivationVerdict.CHECKSUM,
        ).forEach { (raw, expect) ->
            val disk = FakeDisk()
            val store = ActivationStore(disk)
            assertEquals(expect, store.submit(raw, allowed), "$raw 的结论必须逐字来自校验器")
            assertEquals(0, disk.writeCalls, "$raw 被拒却动了盘")
            assertFalse(store.isActivated())
        }
    }

    // ---- 3. 写不成绝不报"解锁了" ----

    @Test
    fun `盘写不进时报 WRITE_FAILED 而不是 UNLOCKED`() {
        val disk = FakeDisk().apply { writable = false }
        val store = ActivationStore(disk)
        assertEquals(ActivationVerdict.WRITE_FAILED, store.submit(code, allowed), "写不成就是没解锁：IO 层的沉默不能当真")
        assertFalse(store.isActivated(), "写失败还算解锁=假绿")
        assertEquals(1, disk.writeCalls, "判据算对之后才允许动盘")
    }

    @Test
    fun `写口谎报成功但读回不是那几个字节 同样算失败`() {
        val store = ActivationStore(LyingDisk())
        assertEquals(ActivationVerdict.WRITE_FAILED, store.submit(code, allowed), "只信 write() 的返回值=信了一层谎")
        assertFalse(store.isActivated())
    }

    // ---- 4. 脏文件按未激活算（宁可让人再输一次） ----

    @Test
    fun `盘上内容不合规一律按未激活 不认文件里有字就算解锁`() {
        // 值为 null = 该判未激活；非 null = 该放行且尾回显就是这一串
        mapOf<String?, String?>(
            null to null,
            "" to null,
            "   " to null,
            "B1C" to null,
            "B1CEZ" to null,
            "ANY-PRO9-FAN8-B1CE" to null,
            "b1c_" to null,
            "…B1CE" to null,
            "0000-0000" to null,
            "1234" to "1234",
            "b1ce" to "B1CE",
            "  B1CE " to "B1CE",
        ).forEach { (content, expectTail) ->
            val state = ActivationStore(FakeDisk(content)).state()
            if (expectTail == null) {
                assertEquals(ActivationState.LOCKED, state, "$content 不算解锁：脏内容放行=touch 一个文件即绕过付费")
            } else {
                assertTrue(state.activated, "$content 是合规尾四位，判未激活=把已付款的人关在门外")
                assertEquals(expectTail, state.tail)
            }
        }
    }

    @Test
    fun `读不出来的文件与没这个文件同义 不是解锁`() {
        val disk = FakeDisk("B1CE").apply { readable = false }
        assertFalse(ActivationStore(disk).isActivated(), "读不到就当保留旧态：那是把 IO 故障算成已付费")
    }

    // ---- 5. 复位（设备面判据 5 的"未激活那一态"要用它，用户侧等价动作是清除应用数据） ----

    @Test
    fun `reset 之后必须真的没有那四个字符`() {
        val disk = FakeDisk()
        val store = ActivationStore(disk)
        store.submit(code, allowed)
        assertTrue(store.isActivated())
        assertTrue(store.reset())
        assertNull(disk.text)
        assertFalse(store.isActivated())
        assertEquals(ActivationState.LOCKED, store.state())
    }

    @Test
    fun `删不干净时 reset 报失败 不报成功`() {
        // FakeDisk.clear() 在 clears=false 时不动 text：reset 的判据是"再读一次还在不在"，
        // 因此必须回 false——撤 flag 没做成还报成功，设备面那格"未激活"就成了假绿
        val store = ActivationStore(FakeDisk("B1CE").apply { clears = false })
        assertFalse(store.reset(), "复位没做成不许谎报")
        assertTrue(store.isActivated(), "盘上仍是合规尾四位：这一格必须仍算已激活，reset 的 false 才有意义")
    }

    // ---- 6. S5-g：本地判据过 ≠ 解锁，服务器说了算（军令 §2 + 裁 1 覆盖 S5-R13 裁 1） ----

    @Test
    fun `形状全对但服务器说不在册 落 SERVER_INVALID 且一个字节都不写`() {
        val disk = FakeDisk()
        val store = ActivationStore(disk)
        assertEquals(
            ActivationVerdict.SERVER_INVALID,
            store.submit(code, ActivationRemote.Invalid),
            "本地算对了就解锁=v1.0.5 的口径；本批改判据后这一格必须是拒",
        )
        assertEquals(0, disk.writeCalls, "服务器没点头却动了盘=把'格式像'当成'买过了'")
        assertFalse(store.isActivated())
    }

    @Test
    fun `额度满落 SERVER_SEATS_FULL 且不落盘`() {
        val disk = FakeDisk()
        val store = ActivationStore(disk)
        assertEquals(ActivationVerdict.SERVER_SEATS_FULL, store.submit(code, ActivationRemote.SeatsFull))
        assertEquals(0, disk.writeCalls)
        assertFalse(store.isActivated())
    }

    @Test
    fun `连不上服务器就是拒绝激活 这一档绝不写成通过`() {
        val disk = FakeDisk()
        val store = ActivationStore(disk)
        assertEquals(
            ActivationVerdict.SERVER_UNREACHABLE,
            store.submit(code, ActivationRemote.Unreachable),
            "fail-closed（自钉 2）：'没答上'若算通过，整套额度锁等于没有",
        )
        assertEquals(0, disk.writeCalls)
        assertFalse(store.isActivated())
    }

    @Test
    fun `本机给不出设备标识时既不落盘也不冒充网络故障`() {
        val disk = FakeDisk()
        val store = ActivationStore(disk)
        assertEquals(
            ActivationVerdict.DEVICE_ID_MISSING,
            store.submit(code, ActivationRemote.IdentityMissing),
            "这一格与'连不上'分开：让买家去查网络是误导，真实情况是根本没出门",
        )
        assertEquals(0, disk.writeCalls)
    }

    @Test
    fun `已经解锁的机器遇到服务器不应 既不改盘也不撤既有解锁态`() {
        // 判据 5 的 JVM 半边：联网只发生在"输码这一次"，任何后续失败都不许把已付过费的人关回门外
        val disk = FakeDisk("B1CE")
        val store = ActivationStore(disk)
        assertEquals(ActivationVerdict.SERVER_UNREACHABLE, store.submit(code, ActivationRemote.Unreachable))
        assertEquals("B1CE", disk.text, "一次没成的输码不许顺手清掉既有态")
        assertTrue(store.isActivated())
    }

    @Test
    fun `服务器点头但盘写不成仍报 WRITE_FAILED 不报解锁`() {
        val disk = FakeDisk().apply { writable = false }
        assertEquals(
            ActivationVerdict.WRITE_FAILED,
            ActivationStore(disk).submit(code, allowed),
            "远程通过了不等于本机通过了：落盘复核这一格一步都不能省",
        )
    }
}
