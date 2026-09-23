package com.anytouch.byok

/**
 * Key 脱敏（纯函数，零平台，JVM 可锁）。
 *
 * 军令口径：Key 只进内存，绝不落盘、绝不打印、绝不入 git/证据。这条纪律不能靠"记得别打印"——
 * 全仓第一次引入网络调用，异常消息、HTTP 响应体、模型回显都可能自带 Key。本文件是兜底闸门：
 * 任何字符串在进日志/证据/UI 之前先过 [mask]，识别**所有**已知密钥形态而不是某一家前缀。
 */
object KeyMasker {

    const val MASK = "***"

    /**
     * 通用密钥形态：长度 >=16 的 base64url 样串（字母数字 + `-_.`）。
     * 有意宽泛——宁可把一段长哈希打码，也不能漏掉一家新前缀的 Key。
     * 只匹配"整段无空白"的 token，短词与普通英文不受影响。
     */
    private val GENERIC_TOKEN = Regex("(?<![A-Za-z0-9_\\-.])[A-Za-z0-9_\\-.]{16,}(?![A-Za-z0-9_\\-.])")

    /** 显式"这就是凭据"的键值对：key/token/secret/authorization/api-key/Bearer xxx。 */
    private val CREDENTIAL_PAIR = Regex(
        "(?i)(api[_-]?key|apikey|access[_-]?key|secret|token|authorization|bearer|password|key)[\"']?\\s*[:=]\\s*[\"']?([^\\s,\"'};]+)",
    )

    private val BEARER_HEADER = Regex("(?i)\\bBearer\\s+([A-Za-z0-9._\\-\\+/=]{4,})")

    /** 已知厂商前缀：即便长度不足 16 也必须打码。 */
    private val PREFIXED_KEY = Regex("\\b(nvapi|sk|ghp|gho|github_pat|xoxb|AIza)[_-][A-Za-z0-9_\\-.]{4,}")

    /**
     * @param secrets 本次调用明确知道的秘密字面量（就是那把 Key 本身）；先做精确替换，
     *   因为它是唯一"确定要消失"的串。空白与过短的串忽略（过短会误伤正常文本）。
     */
    fun mask(text: String, vararg secrets: String?): String {
        var out = text
        secrets.filterNotNull().filter { it.length >= 6 }.distinct().forEach { secret ->
            out = out.replace(secret, MASK)
        }
        out = BEARER_HEADER.replace(out) { "${it.value.substringBefore(" ")} $MASK" }
        out = CREDENTIAL_PAIR.replace(out) { m ->
            val head = m.value.substringBefore(m.groupValues[2])
            head + MASK
        }
        out = PREFIXED_KEY.replace(out, MASK)
        return GENERIC_TOKEN.replace(out) { m ->
            // 已含掩码或纯点号的片段（如 "..." / 版本串）不必再动
            if (m.value == MASK || m.value.all { c -> c == '.' }) m.value else MASK
        }
    }

    /**
     * 展示形态：只给尾 4 位，其余一律掩码——用户需要"确认是不是这把 Key"，不需要 Key 本身。
     * 短于 8 位的串整段打码（此时尾 4 位占比过半，等于泄露一半）。
     */
    fun maskForDisplay(key: String?): String? {
        if (key.isNullOrBlank()) return null
        if (key.length < 8) return MASK
        return MASK + key.takeLast(4)
    }
}
