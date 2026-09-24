package com.anytouch.app.compile

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * BYOK Key 的 Keystore 加密持久化——**设备侧适配器**（老板 09-23 S3 裁决 3）。
 *
 * 本文件故意薄：只做三件非 Android 环境做不了的事——
 * 1) 取/建 AndroidKeyStore 别名密钥（不可导出、GCM、绑本机）；
 * 2) 读写应用私有目录里的密文文件（红线 I 禁 SharedPreferences 与外部目录，明文无处落脚）；
 * 3) 把"密钥库别名"作为一个可擦槽位交给 [wipeSlots]。
 *
 * 保存/读回/清除的判定全在 [CredentialRepository]（JVM 锁死），所以"清除后读不回""写成功≠存上了"
 * 这类结论不依赖设备就能验。切片 E 在真机上只补两件事：Keystore 真能生成/取回，以及断网后仍零请求。
 *
 * 已知边界（诚实记录）：别名密钥不可导出 ⇒ 换机或恢复出厂后密文自动解不开，按 TAMPERED/UNWRAP 话术
 * 引导重填——这是防"拷走密文就能读 Key"的特性，不是缺陷。明文 Key 在内存里是 String，Android 上
 * 无法安全 zeroize（String 不可销毁），本批不假装解决。
 */
object AndroidKeyVault {

    const val ANDROID_KEYSTORE = "AndroidKeyStore"
    const val KEY_ALIAS = "anytouch-byok-aes"

    private const val DIR_NAME = "byok"
    private const val FILE_NAME = "credential.bin"

    fun create(context: Context): CredentialRepository {
        val blobFile = File(File(context.applicationContext.filesDir, DIR_NAME), FILE_NAME)
        val store = object : CredentialRepository.BlobStore {
            override fun exists(): Boolean = blobFile.exists()

            override fun read(): ByteArray? = if (blobFile.exists()) blobFile.readBytes() else null

            // "写成功"以文件里真是这些字节为准，不信 IO 层的沉默
            override fun write(bytes: ByteArray): Boolean = try {
                blobFile.parentFile?.mkdirs()
                blobFile.writeBytes(bytes)
                blobFile.exists() && blobFile.readBytes().contentEquals(bytes)
            } catch (e: Exception) {
                false
            }

            override fun delete(): Boolean {
                blobFile.delete()
                return !blobFile.exists()
            }
        }
        val aliasSlot = object : WipeSlot {
            override val name: String get() = "system keystore alias"

            // 连密钥库都打不开时按"别名还在"处理：宁可报没清干净，也不能报清干净（fail-closed）
            override fun exists(): Boolean = runCatching { keyStore().isKeyEntry(KEY_ALIAS) }.getOrDefault(true)

            override fun erase() {
                val ks = keyStore()
                if (ks.isKeyEntry(KEY_ALIAS)) ks.deleteEntry(KEY_ALIAS)
            }
        }
        return CredentialRepository(store, GcmBlobCipher(::keystoreKey), aliasSlot)
    }

    /**
     * 别名不存在才生成。别名存在却取不出密钥时**绝不就地重建**：重建等于静默换钥，
     * 会让旧密文凭空变成 TAMPERED 假故障；抛错归入 UNWRAP_FAILED，由用户显式清除后重填。
     */
    private fun keystoreKey(): SecretKey {
        val ks = keyStore()
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        if (ks.isKeyEntry(KEY_ALIAS)) throw GeneralSecurityException("$KEY_ALIAS 存在但取不出 AES 密钥")
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(128)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
}
