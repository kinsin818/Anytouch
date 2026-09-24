package com.anytouch.app.compile

import javax.crypto.SecretKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Key 生命周期（军令 S3 §3-3）在 JVM 侧的全部判据。
 *
 * 设备适配器只贡献"密钥从 Keystore 取"和"字节落私有文件"两件事，剩下这些最容易自欺的环节
 * 都在这里锁：写返回成功 ≠ 存上了；存上了 ≠ 读得回同一份；删了文件 ≠ 清干净；
 * 从没配过 ≠ 配过但坏了。四条分开，用户与排障才不会被一句"保存失败"糊住。
 */
class CredentialRepositoryTest {

    private val keyA: SecretKey = GcmBlobCipher.keyFrom(ByteArray(16) { (it + 1).toByte() })

    private class FakeStore(initial: ByteArray? = null) : CredentialRepository.BlobStore {
        var data: ByteArray? = initial
        var writeOk = true
        var nullOnRead = false
        var staleBytes: ByteArray? = null
        var writes = 0
        var deletes = 0

        override fun exists(): Boolean = data != null

        // 两个独立开关：假件当初写成 `readOverride?.invoke() ?: data`，null 会被 elvis 吞掉退回 data，
        // 于是"读不回"这一档被假件自己判成 Saved——假件的兜底比生产代码更能骗人，记在这条注释里。
        override fun read(): ByteArray? = when {
            nullOnRead -> null
            staleBytes != null -> staleBytes
            else -> data
        }

        override fun write(bytes: ByteArray): Boolean {
            if (!writeOk) return false
            writes++
            data = bytes
            return true
        }

        override fun delete(): Boolean {
            deletes++
            data = null
            return true
        }
    }

    private class FakeSlot(
        override val name: String,
        var present: Boolean,
        var refuseErase: Boolean = false,
    ) : WipeSlot {
        override fun exists(): Boolean = present
        override fun erase() {
            if (!refuseErase) present = false
        }
    }

    private fun repo(
        store: FakeStore,
        slot: FakeSlot = FakeSlot("系统密钥库别名", present = true),
        keyUnavailable: Boolean = false,
        cipherKey: SecretKey = keyA,
    ): CredentialRepository = CredentialRepository(
        store,
        GcmBlobCipher {
            if (keyUnavailable) throw IllegalStateException("keystore locked") else cipherKey
        },
        slot,
    )

    @Test
    fun `保存后逐字段读回 含竖线 中文与空串`() {
        val store = FakeStore()
        val r = repo(store)
        assertIs<CredentialRepository.SaveResult.Saved>(r.save("a|b", "https://x/v1", ""))
        val found = assertIs<CredentialRepository.LoadResult.Found>(r.load())
        assertEquals(listOf("a|b", "https://x/v1", ""), listOf(found.secret.apiKey, found.secret.baseUrl, found.secret.model))
    }

    @Test
    fun `落盘字节里找不到明文 Key 也找不到完整地址`() {
        val store = FakeStore()
        repo(store).save("nvapi-supersecretsupersecret", "https://gw.example-corp.co/v1", "glm-5.3")
        val onDisk = String(store.data!!, Charsets.ISO_8859_1)
        assertFalse("supersecret" in onDisk, "明文 Key 躺在密文里")
        assertFalse("example-corp" in onDisk, "连地址都不该明文落盘")
    }

    @Test
    fun `写入被拒时报写失败 且没有半条凭据留下`() {
        val store = FakeStore()
        store.writeOk = false
        val r = repo(store)
        val failed = assertIs<CredentialRepository.SaveResult.Failed>(r.save("k", "https://x/v1", "m"))
        assertEquals(CredentialRepository.SaveResult.Reason.WRITE_FAILED, failed.reason)
        assertIs<CredentialRepository.LoadResult.NotFound>(r.load())
    }

    @Test
    fun `写返回成功但读不回 一律不算已保存`() {
        val store = FakeStore()
        store.nullOnRead = true // 存储层"写完了又说没有"——最典型的假绿来源
        val failed = assertIs<CredentialRepository.SaveResult.Failed>(repo(store).save("k", "https://x/v1", "m"))
        assertEquals(CredentialRepository.SaveResult.Reason.READ_BACK_NOTHING, failed.reason)
        assertTrue(failed.userCopy().contains("cannot be counted as saved"), failed.userCopy())
    }

    @Test
    fun `读回的是另一份凭据时报不一致 不报已保存`() {
        val store = FakeStore()
        val old = repo(store).save("first-key", "https://old/v1", "m1")
        assertIs<CredentialRepository.SaveResult.Saved>(old)
        val snapshot = store.data
        store.staleBytes = snapshot // 写进去了，但读出来永远是旧那份
        val failed = assertIs<CredentialRepository.SaveResult.Failed>(repo(store).save("second-key", "https://new/v1", "m2"))
        assertEquals(CredentialRepository.SaveResult.Reason.READ_BACK_MISMATCH, failed.reason)
    }

    @Test
    fun `清除成功后读不回 单边没清掉就点名`() {
        val store = FakeStore()
        val aliasOk = FakeSlot("系统密钥库别名", present = true)
        val r = repo(store, aliasOk)
        r.save("k", "https://x/v1", "m")
        val wiped = r.clear()
        assertTrue(wiped.cleared, wiped.userCopy())
        assertIs<CredentialRepository.LoadResult.NotFound>(r.load())
        assertEquals(1, store.deletes)

        val store2 = FakeStore()
        val aliasStuck = FakeSlot("系统密钥库别名", present = true, refuseErase = true)
        val r2 = repo(store2, aliasStuck)
        r2.save("k", "https://x/v1", "m")
        val half = r2.clear()
        assertFalse(half.cleared, "别名擦不掉却报清除＝假绿")
        assertEquals(listOf("系统密钥库别名"), half.stillThere)
        assertTrue(half.userCopy().contains("系统密钥库别名"), half.userCopy())
    }

    @Test
    fun `密钥库取不出时保存明确失败 不静默`() {
        val store = FakeStore()
        val failed = assertIs<CredentialRepository.SaveResult.Failed>(repo(store, keyUnavailable = true).save("k", "https://x/v1", "m"))
        assertEquals(CredentialRepository.SaveResult.Reason.KEY_UNAVAILABLE, failed.reason)
        assertFalse(store.exists(), "拿不到密钥就不该有任何落盘")
    }

    @Test
    fun `配过但坏了 与从没配过 两档不同形`() {
        val store = FakeStore()
        val r = repo(store)
        r.save("k", "https://x/v1", "m")
        store.data = store.data!!.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        assertTrue(r.hasStoredCredential(), "文件还在，存在性判断不依赖能否解密")
        val failed = assertIs<CredentialRepository.LoadResult.Failed>(r.load())
        assertEquals(GcmBlobCipher.Failure.TAMPERED, failed.failure)
        assertTrue(failed.userCopy().contains("enter the key once more"), failed.userCopy())

        assertIs<CredentialRepository.LoadResult.NotFound>(repo(FakeStore()).load())
    }

    @Test
    fun `换一把密钥后旧密文一律解不开`() {
        val store = FakeStore()
        repo(store).save("k", "https://x/v1", "m")
        val other = GcmBlobCipher.keyFrom(ByteArray(16) { (it + 99).toByte() })
        val failed = assertIs<CredentialRepository.LoadResult.Failed>(repo(store, cipherKey = other).load())
        assertEquals(GcmBlobCipher.Failure.TAMPERED, failed.failure)
    }

    @Test
    fun `重复保存以最后一次为准`() {
        val store = FakeStore()
        val r = repo(store)
        r.save("k1", "https://a/v1", "m1")
        r.save("k2", "https://b/v1", "m2")
        val found = assertIs<CredentialRepository.LoadResult.Found>(r.load())
        assertEquals("k2", found.secret.apiKey)
        assertEquals("https://b/v1", found.secret.baseUrl)
    }
}
