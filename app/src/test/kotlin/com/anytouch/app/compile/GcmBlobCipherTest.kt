package com.anytouch.app.compile

import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Keystore 加密持久化的可测内核（S3-B 判据 5）。
 *
 * 设备侧只剩"取别名密钥 + 读写文件"两件事，判定与密码学全在这份零 Android 依赖的代码里，
 * 所以能在 JVM 逐档锁死：密文里不许有明文、改一个字节必须解不开、换密钥必须解不开、
 * 读不回必须分档（"从没配过"和"配过但坏了"不能长一样，否则用户与排障都被误导）。
 */
class GcmBlobCipherTest {

    private val keyA: SecretKey = GcmBlobCipher.keyFrom(ByteArray(16) { it.toByte() })
    private val keyB: SecretKey = SecretKeySpec(ByteArray(16) { (it + 7).toByte() }, "AES")

    private fun cipher(key: SecretKey = keyA) = GcmBlobCipher { key }

    private fun roundTrip(plain: String, key: SecretKey = keyA): String {
        val blob = cipher(key).encrypt(plain.toByteArray())
        val out = cipher(key).decrypt(blob)
        return (assertIs<GcmBlobCipher.Outcome.Ok>(out)).plain.decodeToString()
    }

    @Test
    fun `编解码保住三个字段 含竖线 换行 中文与空串`() {
        val fields = listOf("a|b\nc", "中文 Key 值", "")
        val raw = VaultCodec.encode(fields)
        assertEquals(fields, VaultCodec.decode(raw))
    }

    @Test
    fun `解码对截断与畸形长度头一律拒`() {
        val full = VaultCodec.encode(listOf("0123456789", "abc"))
        assertNull(VaultCodec.decode(full.copyOfRange(0, full.size - 3))) // 末段被切
        assertNull(VaultCodec.decode("garbage-without-length".toByteArray())) // 无长度头
        assertNull(VaultCodec.decode("99|short".toByteArray())) // 长度越界
        assertNull(VaultCodec.decode("-1|x".toByteArray())) // 负长度
        assertEquals(1, VaultCodec.decode(VaultCodec.encode(listOf("")))?.size) // 合法空值不误拒
    }

    @Test
    fun `密文里找不到明文字节 且同一明文两次密文不同`() {
        val plain = "nvapi-0123456789abcdef0123456789abcdef".toByteArray()
        val c = cipher()
        val first = c.encrypt(plain)
        val second = c.encrypt(plain)
        assertNotEquals(first.toList(), second.toList(), "IV 必须每次随机，否则同 Key 同密文可被比对")
        listOf(first, second).forEach { blob ->
            assertTrue(IndexOfPlain.find(blob, plain) < 0, "明文原样躺在密文里")
            val back = assertIs<GcmBlobCipher.Outcome.Ok>(c.decrypt(blob))
            assertContentEquals(plain, back.plain)
            assertEquals("", back.userCopy(), "成功档不该有话术")
        }
    }

    @Test
    fun `改一个字节就是 TAMPERED 而不是解出乱码`() {
        val blob = cipher().encrypt("real-key-value".toByteArray())
        val flipped = blob.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        val out = cipher().decrypt(flipped)
        val failed = assertIs<GcmBlobCipher.Outcome.Failed>(out)
        assertEquals(GcmBlobCipher.Failure.TAMPERED, failed.failure)
        assertTrue(failed.userCopy().isNotBlank(), "失败必带话术（错误必显示）")
    }

    @Test
    fun `换一把密钥同样解不开`() {
        val blob = cipher(keyA).encrypt("k".toByteArray())
        val out = cipher(keyB).decrypt(blob)
        assertEquals(GcmBlobCipher.Failure.TAMPERED, assertIs<GcmBlobCipher.Outcome.Failed>(out).failure)
    }

    @Test
    fun `空文件 短blob 与取不到密钥 三档各自分明`() {
        assertEquals(GcmBlobCipher.Failure.NO_DATA, assertIs<GcmBlobCipher.Outcome.Failed>(cipher().decrypt(ByteArray(0))).failure)
        assertEquals(GcmBlobCipher.Failure.BAD_FORMAT, assertIs<GcmBlobCipher.Outcome.Failed>(cipher().decrypt(ByteArray(20))).failure)
        val noKey = GcmBlobCipher { throw IllegalStateException("keystore entry gone") }
        val failed = assertIs<GcmBlobCipher.Outcome.Failed>(noKey.decrypt(ByteArray(40)))
        assertEquals(GcmBlobCipher.Failure.UNWRAP_FAILED, failed.failure)
        assertTrue(failed.userCopy().contains("system keystore"), "取不到密钥要说清是密钥库问题：${failed.userCopy()}")
    }

    @Test
    fun `四档失败话术互不雷同 且没有一档是空的`() {
        val copies = GcmBlobCipher.Failure.entries.map {
            GcmBlobCipher.Outcome.Failed(it).userCopy()
        }
        assertEquals(4, copies.size)
        assertTrue(copies.all { it.isNotBlank() })
        assertEquals(copies.size, copies.toSet().size)
    }

    @Test
    fun `toString 打不出来 Key 两条泄露路径都堵`() {
        val secret = StoredSecret("nvapi-supersecretsupersecret", "https://api.example.com/v1", "glm")
        assertTrue("supersecret" !in secret.toString(), secret.toString())
        val ok = GcmBlobCipher.Outcome.Ok("nvapi-supersecretsupersecret".toByteArray())
        assertTrue("supersecret" !in ok.toString(), ok.toString())
        assertTrue("supersecret" !in GcmBlobCipher.Outcome.Failed(GcmBlobCipher.Failure.TAMPERED).toString())
    }

    private object IndexOfPlain {
        fun find(haystack: ByteArray, needle: ByteArray): Int {
            outer@ for (i in 0..haystack.size - needle.size) {
                for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
                return i
            }
            return -1
        }
    }
}
