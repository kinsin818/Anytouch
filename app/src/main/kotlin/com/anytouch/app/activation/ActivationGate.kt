package com.anytouch.app.activation

/**
 * 付费墙面貌：哪三枚功能进墙、墙外一律是什么，以及**全部**上屏英文话术的唯一来源
 * （S5-f 军令 §3；口径见 `orders/ANYTOUCH-S5f-activation-code-APPENDIX.md` §2）。
 *
 * 与其余门禁同一分工：判据与话术住在这里（纯函数，JVM 锁得住），UI 与 adb 注入两条通道只转调，
 * 任何一处自己抄一句就是第二份真值（`HIGH_RISK` 面板、录制面拒因同一条先例）。
 *
 * **主窗自钉、老板可覆的一条**：付费墙只圈"进阶功能"，绝不圈安全面——
 * 高危二次确认与超时默认拒、停止球急停、无障碍服务门禁、执行期零网络、失败重试判据一律在墙外。
 * 把"要不要问用户"绑进"买没买"是拿用户安全换营收，军令没有授权这个取舍。
 * 这条不是口头承诺，由 `ActivationGateTest` 里那份"绝不进墙清单"逐条锁住。
 */
enum class ProFeature {
    /** 重复循环（S5-d 那两框一勾）：只有真动用（轮数 ≥2）才拦。 */
    REPEAT_LOOP,

    /** 我的任务：存/载/删/存档直跑四条通道。 */
    SAVED_TASKS,

    /** 手动补步骤：唯一写口 `applyEdit(StepEdit.Insert)`。 */
    MANUAL_STEP_INSERT,
}

/** 被付费墙拦下的档（只有一档：本功能没有试用期、没有分级、没有倒计时）。 */
enum class ProGate {
    NOT_ACTIVATED,
}

/**
 * 付费墙判据（纯函数）：`exercised` 表达"这一次请求真的用到了那枚进阶功能吗"。
 * 不用到的请求一律放行——未激活用户点"Run task"跑单发任务、录一段、装个模板，
 * 行为与 v1.0.4 逐字相同（判据 5 的对照格就靠这一条成立）。
 */
fun proGateOf(feature: ProFeature, exercised: Boolean, activated: Boolean): ProGate? =
    if (exercised && !activated) ProGate.NOT_ACTIVATED else null

/**
 * 付费墙拒因的过期边（与 `*AfterStateChange` 那一族同一条律）：[ProGate.NOT_ACTIVATED] 是**纯状态档**——
 * 那一句话说的是"输码的那一刻还没解锁"，用户随后解锁成功还挂着红字就是假红（假红与假绿同罪）。
 * 判据不许抄进 UI：这里一份、那里一份就是第二套真值。
 * @return 本次状态跃迁后仍成立的那一档；null = 该撤。
 */
fun proRejectionAfterChange(current: ProGate?, activated: Boolean): ProGate? =
    if (current == ProGate.NOT_ACTIVATED && activated) null else current

/** 军令 §3 指定的那句提示的字面（三处入口共用同一句头，末尾才补各入口自己的下一步）。 */
const val UPGRADE_MARK = "Upgrade to Pro"

fun ProGate.userCopy(feature: ProFeature): String = when (this) {
    ProGate.NOT_ACTIVATED -> when (feature) {
        ProFeature.REPEAT_LOOP ->
            "$UPGRADE_MARK to repeat a task over several rounds. Nothing was dispatched and nothing on this " +
                "device changed — set Repetitions back to 1 to run it once, or tap Activate and enter your code."
        ProFeature.SAVED_TASKS ->
            "$UPGRADE_MARK to keep your own task list on this device. Nothing was saved, loaded or deleted — " +
                "you can still run the steps on screen right now. Tap Activate and enter your code."
        ProFeature.MANUAL_STEP_INSERT ->
            "$UPGRADE_MARK to add a step by hand. No step was inserted — recording steps and running them " +
                "stays free. Tap Activate and enter your code."
    }
}

/**
 * 激活面全部文案（首页钮 + 对话框 + 解锁态 + 七档拒因）。
 * 三条纪律：① 全英文；② 每档拒因**互不相同**且都说清"改哪儿"与"什么都没发生"；
 * ③ 不出现内部档名/枚举名（与 v1.0.2 面板去黑话同一条律，`ActivationGateTest` 逐条断言）。
 */
object ActivationCopy {
    const val BUTTON = "Activate"
    const val TITLE = "Enter your activation code"
    const val FIELD_LABEL = "Activation code (ANY-XXXX-XXXX-XXXX)"
    const val CONFIRM = "Unlock"
    const val CANCEL = "Cancel"

    /** 等服务器那一格时上屏的一句话（v1.0.6 起输码是异步的；没有这句就是八秒的"点了没反应"）。 */
    const val CHECKING =
        "Checking this code with the activation server - it takes a second, please keep this dialog open."

    /**
     * 对话框里那句"这能解锁什么 + 它怎么验"。
     *
     * **v1.0.6 起这句必须改口**（S5-R14 裁 4：仓是公开的，产品在售，不改口就是对外陈述假话）：
     * v1.0.5 那句 "checked on this device only - it never goes online" 已经不成立。
     * 现在这句把三件事一次说清：① 码与一个"本机标识"会出门**一次**；② 只有输码这一步联网；
     * ③ 执行期照旧零网络（红线 C/G 一字未动，"never goes online" 那半句留给执行面，
     * 全仓仍只有这一处出现该字样——`ActivationGateTest` 那条 singleOrNull 锁的就是这个唯一性）。
     * 标识的口径按裁 1 的覆盖注：禁写"绑定硬件"，只准说"每台设备一个标识、重装或刷机可能要重新激活"。
     */
    const val SCOPE =
        "Unlocking adds three things: repeating a task over several rounds, your own saved task list, and " +
            "hand-added steps. When you enter a code, it and an identifier for this device go once to our " +
            "activation server; after that the app needs no network to run a task, and running a task never " +
            "goes online. The identifier is per device, so a reset or a fresh install may need you to unlock " +
            "again."

    /** 军令 §3 逐字指定的成功句头（v1.0.6 起成功只有一个来源：服务器说 ok）。 */
    fun unlocked(tail: String): String =
        "Activation successful. Pro features are unlocked on this device (code ending $tail)."

    fun refusal(verdict: ActivationVerdict): String = when (verdict) {
        ActivationVerdict.UNLOCKED ->
            "Activation successful. Pro features are unlocked on this device."
        ActivationVerdict.EMPTY ->
            "Type the activation code you received with your purchase (it looks like ANY-XXXX-XXXX-XXXX). " +
                "Nothing was unlocked."
        ActivationVerdict.BAD_PREFIX ->
            "Activation codes start with ANY followed by a dash. Check the first letters against your receipt. " +
                "Nothing was unlocked."
        ActivationVerdict.BAD_LENGTH ->
            "An activation code is exactly 18 characters: ANY, then three groups of four, with a dash between " +
                "each group. A missing or extra character won't pass. Nothing was unlocked."
        ActivationVerdict.BAD_SEPARATOR ->
            "Put a dash after ANY and after each group of four characters, like ANY-XXXX-XXXX-XXXX. " +
                "Nothing was unlocked."
        ActivationVerdict.BAD_CHARSET ->
            "Codes use capital letters A-Z and digits 0-9 only (lowercase you type is fine, we switch it). " +
                "A symbol or a space inside a group won't do. Nothing was unlocked."
        ActivationVerdict.CHECKSUM ->
            "That code has the right shape but its last two letters don't match the first two, so it looks " +
                "mistyped. Copy it again from your receipt. Nothing was unlocked."
        ActivationVerdict.WRITE_FAILED ->
            "The code is right, but this device refused to write the unlocked flag to its own storage, so " +
                "nothing was unlocked. Retry, and if it keeps failing report this with the build number."
        // ---- S5-g 新增四档：本地判据过之后才有资格谈这四句 ----
        ActivationVerdict.SERVER_INVALID ->
            "Activation code invalid. It isn't on the list of codes issued with your purchase, so check it " +
                "against your receipt - or contact the seller if you bought it recently. Nothing was unlocked."
        ActivationVerdict.SERVER_SEATS_FULL ->
            "This code has already been activated on 2 devices, maximum reached. Contact the seller to free one " +
                "of them, then try again here. Nothing was unlocked."
        ActivationVerdict.SERVER_UNREACHABLE ->
            // 那句归因话术必须与"码无效"分开：fail-closed 不解释会被买家当成码有问题去退单（自钉 2 补的第二句）
            "Activation needs a network connection. The server could not be reached, so this code was not " +
                "checked at all. Nothing was unlocked."
        ActivationVerdict.DEVICE_ID_MISSING ->
            "This device wouldn't hand over an identifier for the check, so nothing was sent anywhere and " +
                "nothing was unlocked. Try once more; if it keeps failing, report this with the build number."
    }
}

/** 未激活时挂在三处入口旁边的那句短提示（军令 §3"提示 Upgrade to Pro"）。 */
fun lockedHint(feature: ProFeature): String = when (feature) {
    ProFeature.REPEAT_LOOP -> "$UPGRADE_MARK to repeat rounds."
    ProFeature.SAVED_TASKS -> "$UPGRADE_MARK to save and reuse your own tasks."
    ProFeature.MANUAL_STEP_INSERT -> "$UPGRADE_MARK to add steps by hand."
}

/**
 * 屏上/日志能出现的唯一形态：尾四位（`…-NX`）。
 * 与凭据面同律——能解锁的串不整条上屏、不进日志（红线 H 的精神面）。
 */
fun maskedTail(tail: String): String = if (tail.isEmpty()) "-" else "…-$tail"
