package com.anytouch.app.compile

/**
 * 凭据存取的唯一实现（零 Android 依赖，JVM 可锁）。
 *
 * 设备侧只剩两件事：给一个 Keystore 别名密钥（[GcmBlobCipher.keyProvider]）、给一块私有文件
 * （[BlobStore]）。保存/读回/清除的全部判定都在这里，因此军令 §3-3 的三条要求能被真锁住：
 * 密文里无明文、**保存必读回比对（无落字不判成）**、清除后读不回。
 *
 * "写返回成功"从来不等于"存下来了"——所以 [save] 一定要走一遍 [load] 并逐字段比对，
 * 认账了才报已保存。任何一环不符都按失败上屏，禁把"大概存上了"当结论。
 */
class CredentialRepository(
    private val store: BlobStore,
    private val cipher: GcmBlobCipher,
    private val keySlot: WipeSlot,
) {

    /** 落盘字节的最小抽象：JVM 用内存假件，设备用应用私有文件。 */
    interface BlobStore {
        fun exists(): Boolean
        fun read(): ByteArray?
        /** 返回 false 表示这次写没落地（空间、权限、ROM 抽风）。 */
        fun write(bytes: ByteArray): Boolean

        /** 返回 false 表示删除后被系统查回仍在。 */
        fun delete(): Boolean
    }

    sealed interface SaveResult {
        fun userCopy(): String

        object Saved : SaveResult {
            override fun userCopy(): String = "已加密保存到本机。"
        }

        /** [detail] 只放机器事实（异常类名等），永不含 Key。 */
        data class Failed(val reason: Reason, val detail: String? = null) : SaveResult {
            override fun userCopy(): String = reason.userCopy() + (detail?.let { "（$it）" } ?: "")
        }

        enum class Reason {
            KEY_UNAVAILABLE,
            WRITE_FAILED,
            READ_BACK_NOTHING,
            READ_BACK_MISMATCH,
            ;

            fun userCopy(): String = when (this) {
                KEY_UNAVAILABLE -> "系统密钥库现在拿不到加密密钥，Key 没有落盘。请稍后重试或重启手机后再保存。"
                WRITE_FAILED -> "写入本机文件失败，Key 没有保存。请检查存储是否被清理。"
                READ_BACK_NOTHING -> "写入报告成功，但读不回凭据——Key 不能算已保存。"
                READ_BACK_MISMATCH -> "读回的凭据与刚保存的不一致，Key 不能算已保存，请重填一次。"
            }
        }
    }

    sealed interface LoadResult {
        /** 从未保存过：正常首启，不是错误（与"配过但坏了"必须不同形，否则排障两眼一抹黑）。 */
        object NotFound : LoadResult

        data class Failed(val failure: GcmBlobCipher.Failure) : LoadResult {
            fun userCopy(): String = when (failure) {
                GcmBlobCipher.Failure.BAD_FORMAT -> "本机保存的凭据文件不完整，请重新填一次 Key。"
                GcmBlobCipher.Failure.TAMPERED -> "本机凭据与系统密钥不匹配（换机或改过文件），请重新填一次 Key。"
                GcmBlobCipher.Failure.UNWRAP_FAILED -> "系统密钥库取不出加密密钥，请在设置里重新保存一次 Key。"
                GcmBlobCipher.Failure.NO_DATA -> "本机还没有凭据。"
            }
        }

        data class Found(val secret: StoredSecret) : LoadResult
    }

    fun hasStoredCredential(): Boolean = store.exists()

    fun save(apiKey: String, baseUrl: String, model: String): SaveResult {
        val blob = try {
            cipher.encrypt(VaultCodec.encode(listOf(apiKey, baseUrl, model)))
        } catch (e: Exception) {
            return SaveResult.Failed(SaveResult.Reason.KEY_UNAVAILABLE, e.javaClass.simpleName)
        }
        val wrote = try {
            store.write(blob)
        } catch (e: Exception) {
            return SaveResult.Failed(SaveResult.Reason.WRITE_FAILED, e.javaClass.simpleName)
        }
        if (!wrote) return SaveResult.Failed(SaveResult.Reason.WRITE_FAILED)
        return when (val back = load()) {
            is LoadResult.NotFound -> SaveResult.Failed(SaveResult.Reason.READ_BACK_NOTHING)
            is LoadResult.Failed -> SaveResult.Failed(SaveResult.Reason.READ_BACK_NOTHING, back.userCopy())
            is LoadResult.Found ->
                if (back.secret == StoredSecret(apiKey, baseUrl, model)) {
                    SaveResult.Saved
                } else {
                    SaveResult.Failed(SaveResult.Reason.READ_BACK_MISMATCH)
                }
        }
    }

    fun load(): LoadResult {
        val bytes = store.read() ?: return LoadResult.NotFound
        return when (val decrypted = cipher.decrypt(bytes)) {
            is GcmBlobCipher.Outcome.Failed -> LoadResult.Failed(decrypted.failure)
            is GcmBlobCipher.Outcome.Ok -> {
                val fields = VaultCodec.decode(decrypted.plain)
                    ?: return LoadResult.Failed(GcmBlobCipher.Failure.BAD_FORMAT)
                if (fields.size != 3) return LoadResult.Failed(GcmBlobCipher.Failure.BAD_FORMAT)
                LoadResult.Found(StoredSecret(fields[0], fields[1], fields[2]))
            }
        }
    }

    /** 密文文件 + 密钥库别名两处同灭，缺一处即报未清干净（判定见 [wipeSlots]）。 */
    fun clear(): WipeReport {
        val fileSlot = object : WipeSlot {
            override val name: String get() = "本机凭据文件"
            override fun exists(): Boolean = store.exists()
            override fun erase() {
                store.delete()
            }
        }
        return wipeSlots(listOf(fileSlot, keySlot))
    }

}
