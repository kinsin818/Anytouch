package com.anytouch.app.compile

import android.content.Context
import android.provider.Settings
import com.anytouch.app.activation.ActivationRemote
import com.anytouch.byok.ActivationCheck
import com.anytouch.byok.ActivationOutcome
import com.anytouch.byok.ActivationTransport

/**
 * 激活联网那一步的**唯一门面**（S5-g 军令 §2 + 老板裁 1）。
 *
 * 为什么住在 `compile/` 而不是 `activation/` 或 `platform/`：这不是顺手安置——红线 G 规定
 * 全仓只有 UI 面（`MainActivity` / `ui/` / `compile/`）允许 import `com.anytouch.byok`，
 * 而 `activation/` 那侧刻意保持零联网依赖（判据要能在 JVM 里锁，且执行路径不许有" import byok"的
 * 第一个例外可抄）。`compile/` 本来就装着 BYOK 编译那条唯一的联网链路，激活是第二条，同源不同事，
 * 所以它落在这里；`platform/` 继续只碰本地文件（附页 §2 的硬约束）。
 *
 * **本文件一行判据都不写**：档位映射是穷尽式 `when`（byok 新增一档而这里没跟上＝编译错误），
 * 解锁/落盘/拒因全在 `activation/` 那一侧。与 [ByokGateway] 同律：JVM 覆盖为 0 的设备缝。
 *
 * [verify] 是**阻塞**函数：调用方负责挪出主线程（MainActivity 的 `anytouch-activate` 那条线），
 * 且一次点击只发一次——本类不重试、不排期、不后台复核（超时与重试口径见 `ActivationTransport`）。
 */
object ActivationChannel {

    /**
     * 拿本机标识打一次服务器。返回的 [ActivationRemote] 里**没有任何**可用于转发的凭据：
     * 码不回传、设备标识不回传，服务器那两格额度数字是唯一带回来的信息。
     */
    fun verify(context: Context, code: String): ActivationRemote {
        val androidId = readAndroidId(context)
        val hash = ActivationTransport.deviceHashOf(androidId) ?: return ActivationRemote.IdentityMissing
        return remoteOf(ActivationTransport.verify(code, hash))
    }

    /**
     * `ANDROID_ID` 是设备标识的唯一来源（裁 1：军令那句"硬件 MD5"在 Android 10+ 的第三方应用里取不到）。
     *
     * 这里包一层 try：Settings 的内容提供者在系统进程重启时会抛 `DeadSystemException`，
     * 那种时刻**读不到标识就是没标识**——绝不允许退化成"拿空串哈希一个大家都一样的值"，
     * 那等于让所有读不到标识的机器共用同一格额度（`deviceHashOf` 那边也钉了同一条：空白一律返回 null）。
     */
    private fun readAndroidId(context: Context): String? = try {
        Settings.Secure.getString(context.applicationContext.contentResolver, Settings.Secure.ANDROID_ID)
    } catch (e: RuntimeException) {
        null
    }

    /** 穷尽式映射：四个服务器档位 → 判据层的四个档（判据层因此不必 import 联网模块）。 */
    internal fun remoteOf(check: ActivationCheck): ActivationRemote = when (check.outcome) {
        ActivationOutcome.ALLOWED -> ActivationRemote.Allowed(check.seatsUsed, check.seatsTotal)
        ActivationOutcome.INVALID -> ActivationRemote.Invalid
        ActivationOutcome.SEATS_FULL -> ActivationRemote.SeatsFull
        ActivationOutcome.UNREACHABLE -> ActivationRemote.Unreachable
    }
}
