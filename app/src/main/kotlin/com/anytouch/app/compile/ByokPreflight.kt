package com.anytouch.app.compile

import com.anytouch.byok.BaseUrlPolicy

/**
 * 编译前的入口门禁（纯函数，零 Android，JVM 锁死）——S3-D 唯一"这次能不能出门"的判据。
 *
 * 为什么判在拨号之前：这是全仓第一条由 UI 触发的网络路径。地址政策（`:byok` 的 `BaseUrlPolicy`）若只
 * 挂在传输层里"顺手拒"，门禁就成了链路最晚的一环；而"没填 Key / 任务正在跑 / 一句话都没说"这三档
 * 与网络毫无关系，拒在出门前既不烧钱，也不会给用户一条假的"网络错误"。
 *
 * **顺序即判据**（自上而下第一个命中即返回，JVM 用例锁整序）：
 * 意图空 → 执行中 → 本机无凭据 → 凭据读不出 → 模型名空 → 地址政策 → READY。
 * 其中"执行中"这一档与既有禁编辑门禁同源（编译产物是整本替换步序账的，执行中换账=在跑的那一跑与
 * 屏上的账变成两套）；结构性拦截还在唯一写口
 * [com.anytouch.app.recorder.session.RecorderStore.acceptModelActions] 上，本档只负责把话说清楚——
 * 两道都要，因为 adb 通道会绕过按钮（"门禁落入口不落按钮"同律）。
 */
object ByokPreflight {

    /** 每一档都有独立人读话术（L2-③ 禁"编译失败"一句话糊），且互不雷同（JVM 用例锁）。 */
    enum class Gate {
        READY,
        EMPTY_INTENT,
        RUNNING,
        BUSY_COMPILE,
        NO_CREDENTIAL,
        UNREADABLE,
        NO_MODEL,
        BAD_BASE_URL,
    }

    /** 除 READY 之外每一档的话术。UI 与 [block] 共用此表，禁在别处抄一份。 */
    fun copyOf(gate: Gate): String = when (gate) {
        Gate.READY -> "Preflight passed (this one should never reach the screen: READY's on-screen text is " +
            "the address acknowledgement, see ByokPlan.notice)"
        Gate.EMPTY_INTENT -> "Say what to do first: the intent box is empty, so not a single character " +
            "would go to the model this time."
        Gate.RUNNING -> "A task is running, so AI compile will not start: a compile result replaces the " +
            "whole step ledger, and swapping the ledger mid-run would leave the running task and the " +
            "ledger on screen as two different books. Tap the floating ball to stop, or wait for this run " +
            "to finish."
        Gate.BUSY_COMPILE -> "The previous AI compile has not come back yet, so no second run starts " +
            "(the same sentence would be billed twice, and whichever returns later would silently " +
            "overwrite whichever returned first)."
        Gate.NO_CREDENTIAL -> "No saved credential on this device yet (key / service URL / model name are " +
            "stored together). Fill the fields below and tap “Save” — the key's last 4 digits showing up " +
            "on screen is what proves it was really stored."
        Gate.UNREADABLE -> "The stored credential on this device cannot be read right now. Enter the key " +
            "again below and save."
        Gate.NO_MODEL -> "The saved credential has no model name, so the compile does not know which " +
            "model to ask. Add the model name below and save again."
        Gate.BAD_BASE_URL -> "The saved service URL no longer passes the address policy, and not a single " +
            "byte was sent over the network this time. Save the address again."
    }

    /** 预检之外（网关连发两跑）也能用同一个表造结论：档位与文案只有一份。 */
    fun block(gate: Gate, detail: String? = null): Verdict.Blocked {
        require(gate != Gate.READY) { "READY 不能由 block 构造" }
        return Verdict.Blocked(gate, detail ?: copyOf(gate))
    }

    /**
     * 一次编译所需的全部输入。**故意不是 data class**（与 [StoredSecret] 同律）：这里揣着明文 Key，
     * 默认 toString 就是一条泄露路径；JVM 用例锁"toString 不含 Key"。
     */
    class ByokPlan(
        val httpsUrl: String,
        val hostEcho: String,
        val model: String,
        val apiKey: String,
    ) {
        override fun toString(): String = "ByokPlan(host=$hostEcho, model=$model, apiKey=***)"

        /** 军令 S3 §3-4：按下编译前必须让用户看见"Key 要去哪儿"（回显口径住在政策层，不另写一份）。 */
        fun notice(): String = BaseUrlPolicy.Accepted(httpsUrl, hostEcho).keyDestination()
    }

    /** 本机凭据的三种存在状态：从没存过 / 存过但解不开 / 可用。三档话术互不雷同。 */
    sealed interface Credentials {
        object Absent : Credentials

        /** [userCopy] 直接取存储层已经写好的归因（换机/改文件/Keystore 拿不到钥匙各不相同）。 */
        data class Unreadable(val userCopy: String) : Credentials

        data class Ready(val secret: StoredSecret) : Credentials
    }

    sealed interface Verdict {
        data class Blocked(val gate: Gate, val userCopy: String) : Verdict

        /** 非 data class：[plan] 里含明文 Key，默认 toString 就是泄露面。 */
        class Ready(val plan: ByokPlan) : Verdict {
            override fun toString(): String = "Verdict.Ready(plan=$plan)"
        }
    }

    /** 存储层结论 -> 门禁输入：不在这里重复判"能不能解"，只搬运归因。 */
    fun credentialsOf(load: CredentialRepository.LoadResult): Credentials = when (load) {
        is CredentialRepository.LoadResult.Found -> Credentials.Ready(load.secret)
        is CredentialRepository.LoadResult.Failed -> Credentials.Unreadable(load.userCopy())
        CredentialRepository.LoadResult.NotFound -> Credentials.Absent
    }

    fun check(intent: String, running: Boolean, creds: Credentials): Verdict {
        if (intent.isBlank()) return block(Gate.EMPTY_INTENT)
        if (running) return block(Gate.RUNNING)
        val secret = when (creds) {
            Credentials.Absent -> return block(Gate.NO_CREDENTIAL)
            is Credentials.Unreadable ->
                return block(Gate.UNREADABLE, copyOf(Gate.UNREADABLE) + " (" + creds.userCopy + ")")
            is Credentials.Ready -> creds.secret
        }
        if (secret.model.isBlank()) return block(Gate.NO_MODEL)
        return when (val policy = BaseUrlPolicy.check(secret.baseUrl)) {
            is BaseUrlPolicy.Ok -> Verdict.Ready(
                ByokPlan(
                    httpsUrl = policy.accepted.httpsUrl,
                    hostEcho = policy.accepted.hostEcho,
                    model = secret.model,
                    apiKey = secret.apiKey,
                ),
            )
            is BaseUrlPolicy.Fail -> block(
                Gate.BAD_BASE_URL,
                copyOf(Gate.BAD_BASE_URL) + " (" + policy.message + ")",
            )
        }
    }

    /**
     * 「保存」按钮的入口门禁：地址政策必须在**写盘之前**判一次。
     * 否则一个明文协议的地址会安安静静存进 Keystore，等到用户按下编译才炸——
     * 那一次失败话术还会被读成"网络问题"，而真正的原因是三分钟前那次保存。
     * 返回 null=可以写；非 null=话术必上屏，且一个字都不落盘。
     */
    fun checkSave(key: String, baseUrl: String, model: String): String? = when {
        key.isBlank() -> "The key is empty: saving an empty key would only make it look configured on this " +
            "device while the next compile still cannot connect."
        model.isBlank() -> "The model name is empty: all three parts are saved together as one complete " +
            "configuration (one blob holds all three fields — no half entries)."
        else -> when (val policy = BaseUrlPolicy.check(baseUrl)) {
            is BaseUrlPolicy.Ok -> null
            is BaseUrlPolicy.Fail -> "The service URL cannot be saved: " + policy.message +
                " (not a single byte was written on this device.)"
        }
    }
}
