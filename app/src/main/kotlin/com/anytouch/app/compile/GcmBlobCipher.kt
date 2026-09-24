package com.anytouch.app.compile

import java.io.ByteArrayOutputStream
import java.security.GeneralSecurityException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 落盘的 BYOK 凭据（内存态）。**故意不是 data class**：data class 的默认 toString 会把 Key 印出来，
 * 而对象一旦被塞进日志/异常消息/调试输出就泄露了。JVM 用例锁住这条（同类反例见红线 H）。
 */
class StoredSecret(
    val apiKey: String,
    val baseUrl: String,
    val model: String,
) {
    override fun toString(): String = "StoredSecret(baseUrl=$baseUrl, model=$model, apiKey=***)"

    override fun equals(other: Any?): Boolean = other is StoredSecret &&
        other.apiKey == apiKey && other.baseUrl == baseUrl && other.model == model

    override fun hashCode(): Int = listOf(apiKey, baseUrl, model).hashCode()
}

/**
 * 密文 blob 的字段编解码（纯 Kotlin，零 Android 依赖，JVM 可锁）。
 *
 * 一个文件装三个字段，避免"Key 存了、地址没存"的半保存态；格式=每字段「UTF-8 字节数|字节」顺序拼接，
 * 不依赖分隔符转义——Key 与 URL 里出现任何字符都不会撑破格式。解码对越界/截断一律拒（返回 null），
 * 不做"尽力解析"：解析出半个 Key 比解析失败更糟。
 */
object VaultCodec {

    fun encode(fields: List<String>): ByteArray {
        val out = ByteArrayOutputStream()
        for (f in fields) {
            val bytes = f.toByteArray(Charsets.UTF_8)
            out.write("${bytes.size}|".toByteArray(Charsets.US_ASCII))
            out.write(bytes)
        }
        return out.toByteArray()
    }

    fun decode(raw: ByteArray): List<String>? {
        val out = ArrayList<String>()
        var i = 0
        while (i < raw.size) {
            var j = i
            while (j < raw.size && raw[j] != '|'.code.toByte()) j++
            if (j >= raw.size) return null // 截断：有长度头却没分隔符
            val len = String(raw, i, j - i, Charsets.US_ASCII).toIntOrNull() ?: return null
            if (len < 0 || j + 1 + len > raw.size) return null
            out.add(String(raw, j + 1, len, Charsets.UTF_8))
            i = j + 1 + len
        }
        return out
    }
}

/**
 * AES-GCM blob 加解密（密钥来源注入：设备给 AndroidKeyStore 别名密钥，单测给内存密钥）。
 *
 * 选 GCM 而不是 CBC：GCM 自带认证标签，密文被改一个字节就解不开——CBC + PKCS5 会"解出一堆乱码"，
 * 那正是本产品最不能出现的形态（把乱码当 Key 用 → 401 → 用户以为是 Key 错）。
 * 解密失败一律分档返回：既不抛给 UI 崩溃，也不静默返回 null 让"读取失败"和"从没配过"长得一样。
 */
class GcmBlobCipher(private val keyProvider: () -> SecretKey) {

    enum class Failure {
        /** 文件不存在：从未保存过，属正常首启。 */
        NO_DATA,

        /** blob 短于「一个 IV + 一个认证标签」：文件被截断或写坏。 */
        BAD_FORMAT,

        /** 认证标签校验失败：内容被改，或换了加密密钥。 */
        TAMPERED,

        /** 密钥本身拿不到：KeyStore 条目丢失、被撤销或 ROM 拒绝。 */
        UNWRAP_FAILED,
    }

    sealed interface Outcome {
        /** 面向用户的说法（错误必显示；Ok 无话术）。 */
        fun userCopy(): String

        /** 解出的明文含 Key，所以同样不是 data class（toString 泄露路径与 [StoredSecret] 同一条）。 */
        class Ok(val plain: ByteArray) : Outcome {
            override fun userCopy(): String = ""
            override fun toString(): String = "Ok(***"
        }

        data class Failed(val failure: Failure) : Outcome {
            override fun userCopy(): String = when (failure) {
                Failure.NO_DATA -> "还没有保存过 Key。"
                Failure.BAD_FORMAT -> "本地保存的 Key 文件不完整（可能被清理或写坏），请重新填一次。"
                Failure.TAMPERED -> "本地保存的 Key 与系统密钥不匹配（改动过或换机恢复过来的），请重新填一次。"
                Failure.UNWRAP_FAILED -> "系统密钥库里这把加密密钥取不出来（可能被清除或被安全策略限制），请重新保存一次 Key。"
            }
        }
    }

    /**
     * 加密半边**不自己造 IV**——这是真机 K40 打出来的第一条设备事实（切片 E，09-23 深夜）：
     * 别名密钥按 `setRandomizedEncryptionRequired(true)` 生成时，`Cipher.init` 传调用方 nonce
     * 会被 keystore2 直接拒：`In authorize_create, NONCE is present, although CALLER_NONCE is not present`
     * （Error -55 → 上层 `InvalidAlgorithmParameterException`）。
     * 假密钥提供器（单测走 SunJCE）没有这道授权闸，所以这条在 JVM 全绿的状态下藏了整整一片。
     *
     * 改法取"更安全的那半边"而不是放开闸门：让系统生成 nonce，再从 `cipher.parameters` 取回来前置。
     * blob 格式（IV‖密文）与解密半边一字未动 ⇒ 老文件不需要迁移；放开 `CALLER_NONCE` 能把"nonce 唯一性"
     * 从硬件保证降级成"我们自己的 SecureRandom 大概不会重"，那是拿安全换省事，不做。
     */
    fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, keyProvider()) }
        val iv = try {
            cipher.parameters.getParameterSpec(GCMParameterSpec::class.java).iv
        } catch (e: Exception) {
            throw GeneralSecurityException("系统没把这次加密用的 IV 交回来，拒绝落盘（宁可保存失败，不写解不开的文件）", e)
        }
        // 解密半边按固定 IV_LEN 切 blob：ROM 给了别的长度就必须当场拒，不能写出"看着成功、其实解不开"的文件
        if (iv.size != IV_LEN) {
            throw GeneralSecurityException("系统给的 IV 长度是 ${iv.size}，与 blob 格式约定的 $IV_LEN 不符，拒绝落盘")
        }
        return iv + cipher.doFinal(plain)
    }

    fun decrypt(blob: ByteArray): Outcome {
        if (blob.isEmpty()) return Outcome.Failed(Failure.NO_DATA)
        if (blob.size <= IV_LEN + TAG_BITS / 8) return Outcome.Failed(Failure.BAD_FORMAT)
        val key = try {
            keyProvider()
        } catch (e: Exception) {
            return Outcome.Failed(Failure.UNWRAP_FAILED)
        }
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(
                    Cipher.DECRYPT_MODE,
                    key,
                    GCMParameterSpec(TAG_BITS, blob.copyOfRange(0, IV_LEN)),
                )
            }
            Outcome.Ok(cipher.doFinal(blob, IV_LEN, blob.size - IV_LEN))
        } catch (e: AEADBadTagException) {
            Outcome.Failed(Failure.TAMPERED)
        } catch (e: GeneralSecurityException) {
            Outcome.Failed(Failure.UNWRAP_FAILED)
        }
    }

    companion object {
        private const val IV_LEN = 12
        private const val TAG_BITS = 128
        private const val TRANSFORMATION = "AES/GCM/NoPadding"

        /** 测试与设备共用同一份"造内存密钥"写法，单测里不各写一遍 AES 样板。 */
        fun keyFrom(bytes: ByteArray): SecretKey = SecretKeySpec(bytes, "AES")
    }
}
