package com.anytouch.app.activation

/**
 * 本机激活态的落盘窄口（S5-f 军令 §3 的"存在本机"那一半）。
 *
 * 与「我的任务」的 [com.anytouch.app.recorder.SavedTaskDisk] 同一形态：接口只管"读出一条文本/写进文本"，
 * **判定全在 JVM 这一侧**（`ActivationStore`），设备适配器只做 Android 才做得了的那件事。
 * 所以"写成功≠解锁了""文件读成脏东西时该算什么"这些结论能锁在用例里，不靠设备。
 *
 * 老板裁 4（选项原文"B 走既有私有件通道"）：军令那句"存 SharedPreferences"因与**冻结红线 I**
 * （`scripts/ci-local.sh:103` 扫 `app/src/main/` 的源码字面，命中那个 `get…Preferences` API 名即判红——
 * 该扫描器不剥注释，所以这类 token 在本文件里**故意不复述**——本批 KDoc 就曾被红线 C 那张关键字表判红过一回，
 * 撞的正是表里某一个词。）与此字面相撞，改落应用私有目录的同类文件，**红线一字未动**。两者对用户完全同构：
 * 都在应用私有目录、都不出设备、都零网络；差别只在军令的字面，而字面已经按意图落账（`orders/RULINGS-20260922.md` S5-R13）。
 */
interface ActivationDisk {
    /** null = 本机从没激活过（文件不存在）。 */
    fun read(): String?

    /** 返回 false = 没写成或写回读不是这几个字节（不信 IO 层的沉默，同凭据件/存档件那条纪律）。 */
    fun write(text: String): Boolean

    /** 返回 false = 删完还在（复位没做成）。仅测试通道与"清除数据"语义用。 */
    fun clear(): Boolean
}

/** 本机激活态（屏上回显与门禁读取的唯一镜像内容）。 */
data class ActivationState(val activated: Boolean, val tail: String) {
    companion object {
        val LOCKED = ActivationState(activated = false, tail = "")
    }
}

/**
 * 存的那**不是码**，是码的末四位（与屏上回显同一份字节）：
 * - 解锁判据是"有没有这个文件"，不需要留全码，全码留在盘上没有任何用途，只多一个可抄的东西；
 * - 内容必须恰好是 4 个 `[A-Z0-9]`：脏内容按**未激活**算（宁可让人再输一次码，也不许"文件里有字就算解锁"
 *   这种弱判据——那是把"随便 touch 一个文件"变成付费绕过）。
 */
class ActivationStore(private val disk: ActivationDisk) {

    fun state(): ActivationState {
        val text = disk.read()?.trim()?.uppercase().orEmpty()
        return if (text.length == TAIL_LENGTH && text.all { it in 'A'..'Z' || it in '0'..'9' }) {
            ActivationState(true, text)
        } else {
            ActivationState.LOCKED
        }
    }

    fun isActivated(): Boolean = state().activated

    /**
     * 输一枚码：先按纯判据算，**算错一个字节都不写盘**（脏码留下半个解锁态是最坏形态）；
     * 算对了才写，且以"盘上真是这四个字符"为准——写不成回 `WRITE_FAILED`，绝不报"解锁了"。
     */
    fun submit(rawCode: String): ActivationVerdict {
        val verdict = ActivationCode.classify(rawCode)
        if (verdict != ActivationVerdict.UNLOCKED) return verdict
        val tail = ActivationCode.tailForDisplay(rawCode)
        return if (disk.write(tail) && disk.read()?.trim()?.uppercase() == tail) {
            ActivationVerdict.UNLOCKED
        } else {
            ActivationVerdict.WRITE_FAILED
        }
    }

    /** 复位（测试通道判据 5 要用"未激活"那一态；对用户而言等价于清除应用数据）。 */
    fun reset(): Boolean {
        disk.clear()
        return disk.read() == null
    }

    companion object {
        const val TAIL_LENGTH = 4
    }
}
