package com.anytouch.byok

import java.net.URI
import java.net.URISyntaxException

/**
 * 服务地址政策（用户手输的第一个入口门禁，纯函数、零平台、JVM 可锁）。
 *
 * BYOK 意味着地址由用户输入，而这台手机上的应用持有无障碍高权限。若放任填 `http://127.0.0.1`
 * 或带 `user:pass@` 的地址，就等于把"走明文链路 / 打本机与云元数据段"的按钮交给配置界面。
 * 因此政策先于请求：过不了 [check] 的地址根本不会被拨号。判定全程只做字面量比对，不做 DNS 解析
 * ——离线也要能拒，且拒得不依赖网络。
 *
 * 注意：本政策管的是**用户配置的入口**。单测里对传输层直接喂 loopback 属另一条路（见 KeyMasker
 * 与传输层用例），不构成对本政策的豁免或例外。
 */
object BaseUrlPolicy {

    enum class Reason {
        BLANK,
        NOT_ABSOLUTE,
        BAD_SCHEME,
        NO_HOST,
        HAS_USERINFO,
        PRIVATE_ADDRESS,
        HAS_QUERY,
        HAS_FRAGMENT,
        MALFORMED,
    }

    /** [httpsUrl] 去掉尾部斜杠，可直接拼 [CHAT_COMPLETIONS_PATH]；[hostEcho] 是唯一允许回显/落日志的片段。 */
    data class Accepted(val httpsUrl: String, val hostEcho: String) {
        /** 军令 S3 §3-4：自定义 host 必须先让用户看见"Key 要去哪儿"，按下编译前知情。 */
        fun keyDestination(): String = "你的 Key 只发往这一个地址：$hostEcho"
    }

    data class Rejected(val reason: Reason, val userCopy: String)

    /** 政策结论：通过（[Accepted]）或拒绝（[Rejected]）。 */
    sealed interface Outcome {
        val message: String
    }

    /** 放行：只有这一支的地址允许被拨号。 */
    data class Ok(val accepted: Accepted) : Outcome {
        override val message: String get() = accepted.hostEcho
    }

    /** 拒绝：入口直接报错，不带请求出门。 */
    data class Fail(val rejected: Rejected) : Outcome {
        override val message: String get() = rejected.userCopy
    }

    private val PRIVATE_V4 = listOf(
        Regex("^127\\."),
        Regex("^10\\."),
        Regex("^192\\.168\\."),
        Regex("^172\\.(1[6-9]|2[0-9]|3[01])\\."),
        Regex("^169\\.254\\."),
        // 运营商级 NAT 100.64.0.0/10：Cloudflare Tailscale 一类内网隧道常落这段
        Regex("^100\\.(6[4-9]|[7-9][0-9]|1[01][0-9]|12[0-7])\\."),
    )

    private val LOOPBACK_HOSTS = setOf("localhost", "::1", "[::1]", "[::]", "::", "0.0.0.0", "ip6-localhost")

    fun check(raw: String): Outcome {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return fail(Reason.BLANK)
        val uri = try {
            URI(trimmed)
        } catch (e: URISyntaxException) {
            return fail(Reason.MALFORMED)
        }
        if (!uri.isAbsolute) return fail(Reason.NOT_ABSOLUTE)
        val scheme = uri.scheme?.lowercase() ?: return fail(Reason.NOT_ABSOLUTE)
        if (scheme != "https") return fail(Reason.BAD_SCHEME, scheme)
        val host = uri.host?.takeIf { it.isNotBlank() } ?: return fail(Reason.NO_HOST)
        if (uri.userInfo != null) return fail(Reason.HAS_USERINFO)
        if (uri.rawQuery != null) return fail(Reason.HAS_QUERY)
        if (uri.fragment != null) return fail(Reason.HAS_FRAGMENT)
        if (isPrivate(host)) return fail(Reason.PRIVATE_ADDRESS, host)
        val normalized = trimmed.trimEnd('/')
        return Ok(Accepted(httpsUrl = normalized, hostEcho = host.lowercase()))
    }

    private fun fail(reason: Reason, detail: String? = null): Fail =
        Fail(Rejected(reason, userCopyFor(reason, detail)))

    private fun isPrivate(host: String): Boolean {
        val bare = host.removeSuffix(".").lowercase()
        if (bare in LOOPBACK_HOSTS) return true
        // IPv4 可以在 IPv6 字面量里以映射写法出现：[::ffff:127.0.0.1]
        val candidates = listOf(bare, bare.removePrefix("[").removeSuffix("]")) +
            listOfNotNull(mappedIpv4Of(bare))
        return candidates.any { c -> PRIVATE_V4.any { it.containsMatchIn(c) } }
    }

    private fun mappedIpv4Of(bare: String): String? {
        val idx = bare.lowercase().lastIndexOf(":ffff:")
        if (idx < 0) return null
        val tail = bare.substring(idx + 6)
        return if (tail.count { it == '.' } == 3) tail else null
    }

    fun userCopyFor(reason: Reason, detail: String? = null): String = when (reason) {
        Reason.BLANK -> "服务地址不能为空。"
        Reason.NOT_ABSOLUTE -> "服务地址要写完整、带 https:// 开头（例如 https://integrate.api.nvidia.com/v1）。" +
            if (detail == null) "" else "（当前地址连协议都没写）"
        Reason.BAD_SCHEME -> "只允许 https（当前协议：${detail ?: "无"}）。明文 http 会让 Key 裸奔在网络里，这一档不放行。"
        Reason.NO_HOST -> "这个地址里看不出主机名，请检查是否漏写了域名。"
        Reason.HAS_USERINFO -> "地址里不许带 user:password@ 这种内嵌凭据——Key 只走请求头。"
        Reason.PRIVATE_ADDRESS -> "这个地址指向本机、内网或云元数据段（${detail ?: "本地"}），只允许公网服务地址。"
        Reason.HAS_QUERY -> "服务地址是接口前缀，不许带 ?查询参数——Key 也不会从参数里读。"
        Reason.HAS_FRAGMENT -> "服务地址不许带 #片段。"
        Reason.MALFORMED -> "这个字符串无法按 URL 解析，请检查括号、引号与多余的空格。"
    }

    /** OpenAI 兼容对话端点：唯一允许被拼接的路径后缀。 */
    const val CHAT_COMPLETIONS_PATH = "/chat/completions"
}
