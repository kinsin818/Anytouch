package com.anytouch.byok

/**
 * BYOK 失败分档（老板 09-23 S3 开工令裁决 4 的"失败话术"落点）。
 *
 * 为什么必须分档：全仓第一条 UI 联网路径，失败形态一次就有八种。若统一成"网络错误，请重试"，
 * 用户分不清是 Key 错了、地址错了、还是手机压根没网——本产品是海外专供 BYOK，第一次配置失败
 * 就说假话，用户没有第二次机会。每档话术互不雷同（JVM 用例锁死），且**永不带 Key、永不带请求体**。
 *
 * 本枚举是零平台依赖的纯判定，:app 与 host 探针共用同一份口径（一份判据一处实现）。
 */
enum class ByokErrorKind {
    /** 域名解析失败 / 连接被拒 / TLS 握不上：手机到服务这一段根本没通。 */
    UNREACHABLE,

    /** 401 / 403：连上了，但服务认不下这把 Key。 */
    UNAUTHORIZED,

    /** 429：Key 没问题，请求太密或额度见底。 */
    RATE_LIMITED,

    /** 5xx：服务端自己的问题，用户无需改配置。 */
    SERVER_ERROR,

    /** 连上了但没在时限内拿到完整应答（读超时/写超时）。 */
    TIMEOUT,

    /** 应答不是预期 JSON 结构（含鉴权门户的 HTML 拦截页）。 */
    BAD_RESPONSE,

    /** 编译器 fail-closed 校验拒绝：模型产出了坐标 / 白名单外 type / 缺 safety 等。 */
    COMPILE_REJECT,

    /** 模型交回空动作数组（意图不可编译或模型拒编）。 */
    EMPTY_ACTIONS,
    ;

    /**
     * 面向用户的话术（真文案在切片 D 接 UI；此处先把口径钉死）。S5-c 起上屏一律纯英文。
     * 只说"发生了什么 + 下一步做什么"，绝不复述配置内容——URL 里可能带 token，Key 一个字都不行。
     */
    fun userCopy(): String = when (this) {
        UNREACHABLE -> "This service URL cannot be reached: the phone may be offline, the address may be " +
            "mis-typed, or its certificate was not accepted. Open the same address in the phone's browser to " +
            "confirm it is reachable, then come back and re-check the service URL."
        UNAUTHORIZED -> "The service rejected this key (401/403). Check in the provider's console that the key " +
            "is still valid, was pasted in full, and has chat/completions access, then enter it again."
        RATE_LIMITED -> "Too many requests or the quota is used up (429). Wait a moment and compile again; if " +
            "this keeps happening, check your balance and rate limits in the provider's console."
        SERVER_ERROR -> "The service itself failed (5xx) — this is not your configuration. Compile again in a " +
            "moment; if it fails repeatedly, check the provider's status page."
        TIMEOUT -> "Waited a long time and never got a complete answer. The model may be queued or the network " +
            "too slow. Try again shortly, or pick a faster model and compile again."
        BAD_RESPONSE -> "The service replied in a format we did not agree on (not the expected JSON). Usually a " +
            "login/auth wall intercepted the request, or this address is not a chat endpoint. Verify the service " +
            "URL ends in an OpenAI-compatible endpoint such as /v1."
        COMPILE_REJECT -> "The steps the model produced did not pass the safety check (raw coordinates, an " +
            "action type outside the vocabulary, or a missing safety flag). Such output is never handed to the " +
            "executor. State the task more concretely and try again."
        EMPTY_ACTIONS -> "The model could not turn that sentence into executable steps (it returned an empty " +
            "list). Break the goal into visible actions, e.g. which on-screen text to tap first."
    }
}

/**
 * 传输层带类型的失败：调用方拿 [kind] 分档，不拿字符串比对（雷 18 同族纪律：文本不是身份）。
 * [safeDetail] 只能是状态码/异常类名一类机器事实，进日志前必须再过 [KeyMasker]。
 */
class TransportFailure(
    val kind: ByokErrorKind,
    val safeDetail: String,
) : Exception(safeDetail)

/**
 * HTTP 状态码 -> 失败档（纯函数，JVM 锁）。
 *
 * 不跟随重定向：3xx 与其余未点名状态一律归 [ByokErrorKind.BAD_RESPONSE]——跟随跳转等于允许服务端
 * 把一个 https 配置点导向任意地址，政策上不该由客户端承诺。
 */
fun httpKindOf(status: Int): ByokErrorKind? = when (status) {
    in 200..299 -> null
    401, 403 -> ByokErrorKind.UNAUTHORIZED
    429 -> ByokErrorKind.RATE_LIMITED
    in 500..599 -> ByokErrorKind.SERVER_ERROR
    else -> ByokErrorKind.BAD_RESPONSE
}
