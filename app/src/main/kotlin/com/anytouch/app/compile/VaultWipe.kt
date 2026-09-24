package com.anytouch.app.compile

/**
 * "清除"的判定内核（零 Android 依赖，JVM 可锁）。
 *
 * 军令 §3-3：用户点清除后，Keystore 别名与密文文件必须**双双**消失。
 * 只做单侧清理是最难发现的形态——文件删了但别名还在，下次保存直接复用旧密钥，
 * 用户以为换了 Key，其实换的只是密文；反过来别名删了文件还在，则密文永远解不开（TAMPERED 假故障）。
 * 所以清除的成功判据不是"调用了删除"，而是"再查一次两处都不在"。
 */
interface WipeSlot {
    /** 槽名：用于"没清干净"时点名（错误必显示）。 */
    val name: String

    fun exists(): Boolean

    /** 尽力删除；返回值为"这次操作是否报错"由实现自定，判定只看擦完之后的 [exists]。 */
    fun erase()
}

class WipeReport(
    val cleared: Boolean,
    val stillThere: List<String>,
) {
    fun userCopy(): String = when {
        cleared -> "Erased: this device no longer stores any key."
        else -> "The wipe was not complete — these are still on the device: ${stillThere.joinToString(", ")}. " +
            "Clear them as prompted, or wipe this app's data in system settings."
    }
}

/** 判定与设备侧适配器共用的一份实现：先就地尝试擦，再以"再查一次还在不在"定论。 */
fun wipeSlots(slots: List<WipeSlot>): WipeReport {
    val leftover = slots.filter { slot ->
        if (!slot.exists()) return@filter false // 从没存在过，不算残留（首启清除不能误报失败）
        runCatching { slot.erase() }.onFailure { /* 抛错按仍在处理，交给下面的复查 */ }
        runCatching { slot.exists() }.getOrDefault(true)
    }.map { it.name }
    return WipeReport(cleared = leftover.isEmpty(), stillThere = leftover)
}
