package com.anytouch.app.platform

import android.content.Context
import com.anytouch.app.recorder.SavedTaskDisk
import com.anytouch.app.recorder.SavedTaskStore
import java.io.File

/**
 * 「我的任务」存储件的**设备侧适配器**（S5-e 要求 2 / 三裁②"不另起存储"的落盘那一半）。
 *
 * 本文件故意薄，和 `AndroidKeyVault` 同一形态：只做非 Android 环境做不了的一件事——把窄口
 * [SavedTaskDisk] 接到应用私有目录的一个文件上。判定（重名/空账/空名/越界/脏文件）全在
 * `recorder/SavedTasks.kt`，所以"读不回""写成功≠存上了"这类结论能在 JVM 里锁住，不靠设备。
 *
 * 三条硬约束在此的落点：
 * - **只落 `filesDir`**（红线 I 禁 SharedPreferences / 外部目录 / 世界可读文件）。这里落的是**步序账**
 *   不是凭据，但同一条"明文不出私有目录"的纪律一并吃——所以与凭据件（`byok/credential.bin`）
 *   **分目录分文件**：两边的读写不许互相波及。
 * - **"写成功"以文件里真是这些字节为准**（不信 IO 层的沉默），且**先写临时件再改名**：
 *   半本写成功就是 [com.anytouch.app.recorder.SavedTaskGate.READ_FAILED] 要挡的破碎态，
 *   改名在同目录内是原子替换，读者只会看见旧的那本或新的那本，不会看见中间态。
 * - **零网络**：本文件没有任何联网通道（执行期零网络是红线 A/C，创建期才允许碰模型）。
 *
 * 已知边界（诚实记录）：文件在应用私有目录里，卸载或"清除数据"即随之消失——这是"零凭据、
 * 零外传"的代价，不是缺陷；不做云同步也不做导出，本批没有那条授权。
 */
object AndroidSavedTaskDisk {

    private const val DIR_NAME = "saved_tasks"
    private const val FILE_NAME = "tasks.json"
    private const val TMP_SUFFIX = ".tmp"

    @Volatile
    private var instance: SavedTaskStore? = null

    /**
     * 进程内单例（与 `ByokGateway.of` 同一形态）：界面按钮与 adb 注入两条通道必须操作同一个磁盘件。
     * 单例不是为了缓存列表——列表每次现读盘——只是为了让"哪一个文件"这一事实只有一份。
     */
    fun of(context: Context): SavedTaskStore = instance ?: synchronized(this) {
        instance ?: SavedTaskStore(ofDisk(context.applicationContext)).also { instance = it }
    }

    /** 磁盘件：应用私有目录里一个文件（路径口径见文件头）。 */
    private fun ofDisk(context: Context): SavedTaskDisk = object : SavedTaskDisk {
        private val file = File(File(context.filesDir, DIR_NAME), FILE_NAME)

        override fun read(): String? = if (file.exists()) file.readText() else null

        override fun write(text: String): Boolean {
            val tmp = File(file.parentFile, FILE_NAME + TMP_SUFFIX)
            return try {
                file.parentFile?.mkdirs()
                tmp.writeText(text)
                // 改名失败（同目录内理论上只剩权限一种解释）绝不退化成"直接覆写目标件"：
                // 那正是"半本写成功"的入口——覆写到一半断电，整本存档就解不开了。
                if (!tmp.renameTo(file)) {
                    tmp.delete()
                    false
                } else {
                    // 不信 IO 层的沉默：以"文件里真是这些字节"为准（同凭据件那条纪律）
                    file.readText() == text
                }
            } catch (e: Exception) {
                tmp.delete()
                false
            }
        }
    }
}
