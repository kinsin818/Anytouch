package com.anytouch.app.platform

import android.content.Context
import com.anytouch.app.activation.ActivationDisk
import com.anytouch.app.activation.ActivationStore
import java.io.File

/**
 * 本机激活态的**设备侧适配器**（S5-f 军令 §3 落盘那一半，老板裁 4 走路 B）。
 *
 * 本文件故意薄，和 [AndroidSavedTaskDisk] 同一形态：只做非 Android 环境做不了的一件事——
 * 把窄口 [ActivationDisk] 接到应用私有目录的一个文件上。判定全在 `activation/` 那一侧（JVM 锁得住）。
 *
 * 四条硬约束在此的落点：
 * - **只落 `filesDir`**：应用私有目录，与凭据件、存档件**分目录分文件**，三方读写互不波及。
 *   （红线 I 禁的是 SharedPreferences / 外部目录 / 世界可读文件那条明文落盘通道；本批一字未动，
 *   军令那句"SharedPreferences"如何按意图落账见 `orders/RULINGS-20260922.md` S5-R13。）
 * - **"写成功"以文件里真是这些字节为准**，且先写临时件再同目录改名：半截文件正是
 *   [ActivationStore.state] 要按"未激活"算的那种脏态，改名让读者只会看见旧的那份或新的那份。
 * - **存的是码的末四位，不是码本身**（回显与判据都只需要它；把整枚能解锁的串留在盘上没有用途）。
 * - **零网络**：本文件没有任何联网通道，激活校验全程在本机（军令 §4）。
 *
 * 已知边界（诚实记录，不洗）：卸载或"清除数据"即随之消失，届时需重新输入激活码——
 * 这是"零凭据外传、零外传设备标识"的代价，与本产品的隐私口径同向；本批没有云同步/找回那条授权。
 */
object AndroidActivationDisk {

    private const val DIR_NAME = "activation"
    private const val FILE_NAME = "flag.txt"
    private const val TMP_SUFFIX = ".tmp"

    @Volatile
    private var instance: ActivationStore? = null

    /**
     * 进程内单例（与 `ByokGateway.of`、[AndroidSavedTaskDisk.of] 同一形态）：
     * 界面对话框与 adb 注入两条通道必须操作同一个文件件——两个"哪一个文件"就是两套解锁态。
     */
    fun of(context: Context): ActivationStore = instance ?: synchronized(this) {
        instance ?: ActivationStore(ofDisk(context.applicationContext)).also { instance = it }
    }

    private fun ofDisk(context: Context): ActivationDisk = object : ActivationDisk {
        private val file = File(File(context.filesDir, DIR_NAME), FILE_NAME)

        override fun read(): String? = if (file.exists()) file.readText() else null

        override fun write(text: String): Boolean {
            val tmp = File(file.parentFile, FILE_NAME + TMP_SUFFIX)
            return try {
                file.parentFile?.mkdirs()
                tmp.writeText(text)
                if (!tmp.renameTo(file)) {
                    // 改名失败绝不退化成"直接覆写目标件"：覆写到一半断电就是那半截脏态
                    tmp.delete()
                    false
                } else {
                    file.readText() == text
                }
            } catch (e: Exception) {
                tmp.delete()
                false
            }
        }

        override fun clear(): Boolean {
            if (!file.exists()) return true
            return file.delete() && !file.exists()
        }
    }
}
