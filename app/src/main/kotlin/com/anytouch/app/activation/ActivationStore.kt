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
 * 服务器那一格的结论（S5-g 军令 §2 + 老板裁 1）。
 *
 * **为什么这个类型住在 `app/activation/` 而不是直接用 `:byok` 的 `ActivationCheck`**：
 * 判据层一旦 import 联网模块，红线 G 那句"执行路径看不见 byok"就多了第一个例外，
 * 而例外是会被抄的。所以本文件保持零联网依赖，由 `compile/ActivationChannel`
 * （本就只许 UI 面 import byok 的那一层）把 `ActivationCheck` **穷尽式**映射成这里的档——
 * byok 新增一档而那里没跟上就是编译错误，不是"两个口径都算过"（判据 9 的结构锁形态）。
 *
 * [Allowed] 是唯一能解锁的一档。其余四档一律不写盘，其中 [IdentityMissing] 连门都没出：
 * 本机给不出设备标识时不该把责任推给网络，也不该拿别人的额度。
 */
sealed class ActivationRemote {
    /** 服务器认这枚码，且本机占得住一格（或本来就占着）。额度数字只进日志，不参与判据。 */
    data class Allowed(val seatsUsed: Int?, val seatsTotal: Int?) : ActivationRemote()

    /** 码不在白名单里（格式对但没发过，或服务器读不出的脏输入）。 */
    object Invalid : ActivationRemote()

    /** 这枚码已绑满两台，本机不在列表里。 */
    object SeatsFull : ActivationRemote()

    /** 连不上／超时／指纹对不上／答非所问。**fail-closed：这一档等于拒绝**（自钉 2）。 */
    object Unreachable : ActivationRemote()

    /** 本机给不出 `ANDROID_ID`，请求根本没出门。 */
    object IdentityMissing : ActivationRemote()
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
     * 输一枚码：三件事按顺序、缺一不可。
     * 1) **本地纯判据**先过（格式/长度/分隔位/字符集/校验位）——脏码一个字节都不写盘，
     *    也根本不该占用一次请求（v1.0.5 那六档拒因逐字保留，判据 3 的"不回退"格就落在这里）；
     * 2) **服务器说了算**（S5-g 军令 §2，本裁覆盖 v1.0.5 的"本地即权威"）：`本地过 ≠ 解锁`，
     *    只有 [ActivationRemote.Allowed] 才继续；其余四档各回各的新拒因，一律不落盘；
     * 3) 以"盘上真是这四个字符"为准——写不成回 [ActivationVerdict.WRITE_FAILED]，绝不报"解锁了"。
     *
     * 顺序本身就是判据：[remote] 是**参数**而不是回调，所以本文件在结构上无法联网、
     * 也无法在本地判据没过之前给出任何"通过"。
     */
    fun submit(rawCode: String, remote: ActivationRemote): ActivationVerdict {
        val verdict = ActivationCode.classify(rawCode)
        if (verdict != ActivationVerdict.UNLOCKED) return verdict
        val tail = ActivationCode.tailForDisplay(rawCode)
        return when (remote) {
            is ActivationRemote.Allowed ->
                if (disk.write(tail) && disk.read()?.trim()?.uppercase() == tail) {
                    ActivationVerdict.UNLOCKED
                } else {
                    ActivationVerdict.WRITE_FAILED
                }
            ActivationRemote.Invalid -> ActivationVerdict.SERVER_INVALID
            ActivationRemote.SeatsFull -> ActivationVerdict.SERVER_SEATS_FULL
            ActivationRemote.Unreachable -> ActivationVerdict.SERVER_UNREACHABLE
            ActivationRemote.IdentityMissing -> ActivationVerdict.DEVICE_ID_MISSING
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
