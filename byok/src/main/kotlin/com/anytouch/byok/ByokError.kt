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
     * 面向用户的中文话术（真文案在切片 D 接 UI；此处先把口径钉死）。
     * 只说"发生了什么 + 下一步做什么"，绝不复述配置内容——URL 里可能带 token，Key 一个字都不行。
     */
    fun userCopy(): String = when (this) {
        UNREACHABLE -> "连不上这个服务地址：可能是手机没网、地址写错，或证书没通过。请到手机浏览器里打开同一个地址确认能通，再回来检查服务地址。"
        UNAUTHORIZED -> "服务拒绝了这把 Key（401/403）。请到服务商控制台确认 Key 仍然有效、没被截断粘贴、且开通了对话接口，然后重新填写。"
        RATE_LIMITED -> "请求太频繁或额度用完了（429）。等一会儿再编译；如果一直这样，请到服务商控制台看余额和限速。"
        SERVER_ERROR -> "服务自己出错了（5xx），不是你配置的问题。稍后重编一次；连续几次都这样，去看服务商的状态页。"
        TIMEOUT -> "等了很久没等到完整回答。模型可能正排队，或网络太慢。可以稍后重试，或换更快的模型再编一次。"
        BAD_RESPONSE -> "服务回答的不是我们约定的格式（不是预期的 JSON）。常见原因是登录/鉴权页面把请求拦下来了，或这个地址不是对话接口。请核对服务地址是否以 /v1 这类 OpenAI 兼容端点结尾。"
        COMPILE_REJECT -> "模型产出的步骤没有通过安全校验（出现了坐标、越界动作类型或漏掉安全标记）。这类输出不会被送进执行器。请把任务说得更具体一点后重试。"
        EMPTY_ACTIONS -> "模型认为这句话编不出可执行步骤（返回了空列表）。请把目标拆成看得见的具体动作，例如先点哪个可见文字。"
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
