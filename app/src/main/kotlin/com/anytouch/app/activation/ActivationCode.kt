package com.anytouch.app.activation

/**
 * 激活码格式判据（S5-f，军令 `orders/ANYTOUCH-S5f-activation-code-ORDER.md` §2 + 老板裁 2/裁 3）。
 *
 * 本文件是**纯判据**：不碰磁盘、不碰界面、不碰网络，所以"这枚码算不算对"能在 JVM 里逐字锁住
 * （与 [com.anytouch.app.RepeatPolicy] 同一条分工纪律：判据能锁在 JVM，设备面只验接线）。
 *
 * 一句话定性（老板裁 1 原选项"照字面做·装饰性锁"）：**这不是防破解**。仓库公开、源码在 GitHub 上，
 * 算法就写在这里，任何人读一眼源码都能自造码。它的用途只有两个：给买家一道付费提示，
 * 以及给手滑的买家一句当场纠错的话术。任何对外文案禁写"安全保护/防破解"一类字样
 * （口径见 `orders/ANYTOUCH-S5f-activation-code-APPENDIX.md` §0）。
 */
enum class ActivationVerdict {
    /** 格式与校验位都对：解锁三枚进阶功能。 */
    UNLOCKED,

    /** 框里没字：与"写了但写错"分开——那句提示得是"把码填进来"，不是"你码打错了"。 */
    EMPTY,

    /** 头三个字符不是 ANY。 */
    BAD_PREFIX,

    /** 长度不是 18：多半是漏了一位或粘进了多余字符。 */
    BAD_LENGTH,

    /** 第 4、9、14 位不是连字符：分隔符错位（常见于把整串 15 位连打出来）。 */
    BAD_SEPARATOR,

    /** 正文出现 A-Z0-9 之外的字符。 */
    BAD_CHARSET,

    /** 前两位与末两位那对校验字母对不上：形状对但内容像抄错。 */
    CHECKSUM,

    /** 码算对了，但本机落盘没写成（不许把"没落上"报成"解锁了"，与凭据件同一纪律）。 */
    WRITE_FAILED,
}

/**
 * 编码规范（判据钉死在本文件，两份实现对齐用附录 §1 的 golden 表）：
 * - 字面格式 `ANY-XXXX-XXXX-XXXX`，**恰 18 个字符**（军令"总长度 19 位"与格式串自相矛盾，老板裁 2 以格式串为准）；
 * - 正文 12 位字符集 `A-Z0-9`；输入先 `trim`、转大写、去掉内部空白（粘贴容错），其余脏字符一律拒；
 * - 校验位：正文 b1..b12，**b11 = letter(b1)、b12 = letter(b2)**，其中 `letter(c) = chr(ord(c) % 26 + 'A')`
 *   （军令原文"前两位字符ASCII和取模"，老板裁 3 选定的默认细则＝逐位各取模、共两枚字母）。
 */
object ActivationCode {
    const val PREFIX = "ANY"
    const val SEGMENT_LEN = 4
    const val SEGMENT_COUNT = 3
    /**
     * 连字符共**三**枚：`ANY` 之后一枚、三段之间两枚（`ANY-XXXX-XXXX-XXXX`）。
     * 写成 `SEGMENT_COUNT - 1`（=2）会让 `CANONICAL_LENGTH` 变成 17，于是每一枚合法码都被判成
     * `BAD_LENGTH`——这一格由 golden 表在 JVM 里当场抓出，不是靠人眼数出来的。
     */
    private const val DASH_COUNT = SEGMENT_COUNT

    /** 完整码长度：`ANY` + 3 段正文 + 2 枚连字符 = 18。 */
    const val CANONICAL_LENGTH = PREFIX.length + SEGMENT_COUNT * SEGMENT_LEN + DASH_COUNT

    /** 自由正文位数（12 位里末两位是校验字母）：发卡侧与 golden 表共用。 */
    const val FREE_BODY_LENGTH = SEGMENT_COUNT * SEGMENT_LEN - 2

    private val charsetOk = Regex("[A-Z0-9]")
    private val canonicalOk = Regex("ANY-[A-Z0-9]{4}-[A-Z0-9]{4}-[A-Z0-9]{4}")

    /** 一位字母校验位：ASCII 模 26 映射回 A-Z（`A→N`、`0→W`、`9→F`）。 */
    private fun checksumLetter(source: Char): Char = (source.code % 26 + 'A'.code).toChar()

    /**
     * 输入归一化：去首尾空白 → 转大写 → 去掉内部所有空白（复制粘贴常带的空格/换行/制表）。
     * **只**容这些：连字符、点、下划线都不容——猜着改用户敲的东西，等于把另一枚码交给他。
     */
    fun normalize(raw: String): String = raw.trim().uppercase().filterNot { it.isWhitespace() }

    /** 校验一枚码（不写盘、不改状态）：分档给出唯一结论。 */
    fun classify(raw: String): ActivationVerdict {
        val code = normalize(raw)
        if (code.isEmpty()) return ActivationVerdict.EMPTY
        if (code.take(PREFIX.length) != PREFIX) return ActivationVerdict.BAD_PREFIX
        if (code.length != CANONICAL_LENGTH) return ActivationVerdict.BAD_LENGTH
        if (code[3] != '-' || code[8] != '-' || code[13] != '-') return ActivationVerdict.BAD_SEPARATOR
        val body = code.filterIndexed { i, _ -> i != 3 && i != 8 && i != 13 }.drop(PREFIX.length)
        if (!body.all { charsetOk.matches(it.toString()) }) return ActivationVerdict.BAD_CHARSET
        if (body[FREE_BODY_LENGTH] != checksumLetter(body[0]) ||
            body[FREE_BODY_LENGTH + 1] != checksumLetter(body[1])
        ) {
            return ActivationVerdict.CHECKSUM
        }
        // 走到这里规范式必然匹配（上面四条逐字钉过）；再对一次是为了让"判据换写法时静默走偏"
        // 只能以红测暴露，而不是以"两个口径都算过"的形态上线。
        return if (canonicalOk.matches(code)) ActivationVerdict.UNLOCKED else ActivationVerdict.BAD_CHARSET
    }

    fun isUnlocked(raw: String): Boolean = classify(raw) == ActivationVerdict.UNLOCKED

    /**
     * 出一枚合法码：交回 10 位自由正文（b1..b10），补上两位校验字母并排版。
     * 发卡侧（老板出码、冒烟脚本取码）与 JVM 用例共用这一份，避免出现第二种排版口径。
     *
     * 先过 [normalize] 再校验：发卡侧也吃小写与空白（老板手抄一遍就能出码），且**校验的是归一后的那一串**——
     * 反过来先校验再转大写会让下面那句 `uppercase()` 成为永远走不到的死码（小写输入早已在上一行抛掉）。
     */
    fun issue(freeBody: String): String {
        val up = normalize(freeBody)
        require(up.length == FREE_BODY_LENGTH) {
            "free body must be $FREE_BODY_LENGTH chars, got ${up.length}"
        }
        require(up.all { charsetOk.matches(it.toString()) }) { "free body has a dirty char: $up" }
        return "$PREFIX-${up.substring(0, 4)}-${up.substring(4, 8)}-" +
            "${up.substring(8, 10)}${checksumLetter(up[0])}${checksumLetter(up[1])}"
    }

    /**
     * 屏上/日志回显：**只有末四位**（与 API Key 尾 4 位同一条纪律——一个能解锁东西的串，
     * 哪怕强度只是装饰，也不该整条进日志或进截图；`S5FSMOKE` 那行同样只用这个）。
     */
    fun tailForDisplay(raw: String): String = normalize(raw).takeLast(4)
}
