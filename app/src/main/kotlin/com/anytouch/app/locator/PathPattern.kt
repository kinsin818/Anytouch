package com.anytouch.app.locator

/**
 * 三阶层级路径语法（L3 实现留白自决，此处为冻结后的对外口径）：
 *
 *   segment   := pred ('&' pred)* ( '[' digit+ ']' )?
 *   pred      := '*' | key '=' value | bareToken            // bareToken 等价 class=bareToken
 *   key       := class | id | text | desc | clickable | scrollable | package
 *   path      := segment ('>' segment)*
 *
 * 比较规则：class 短名大小写不敏感全等；id 全等或 entry 名全等；text/desc trim 后全等；
 * clickable/scrollable 取值为 true/false；package 全等。路径段在上一段锚点的后代中按层序取候选，
 * `[n]` 为该段候选集中的序号（0 基），缺省取 0。值中不要出现 '&'、'>' 或结尾的 '[n]'。
 */
internal class PathSyntaxException(message: String) : IllegalArgumentException(message)

internal data class PathSegment(
    val raw: String,
    val predicates: List<SegmentPredicate>,
    val index: Int,
) {
    fun matches(node: UiNode): Boolean = predicates.all { it.matches(node) }
}

internal sealed class SegmentPredicate {
    abstract fun matches(node: UiNode): Boolean

    object AnyNode : SegmentPredicate() {
        override fun matches(node: UiNode): Boolean = true
    }

    data class Equals(val key: String, val value: String) : SegmentPredicate() {
        override fun matches(node: UiNode): Boolean = when (key) {
            "class" -> {
                val expected = value.trim()
                expected.equals(node.className?.trim(), ignoreCase = true) ||
                    expected.equals(node.shortClassName?.trim(), ignoreCase = true)
            }
            "id" -> {
                val expected = value.trim()
                expected == node.resourceId?.trim() || expected == node.resourceIdEntryName?.trim()
            }
            "text" -> value.trim() == node.text?.trim()
            "desc" -> value.trim() == node.contentDesc?.trim()
            "package" -> value.trim() == node.packageName?.trim()
            else -> error("unknown predicate key: $key")
        }
    }

    data class BooleanFlag(val key: String, val expected: Boolean) : SegmentPredicate() {
        override fun matches(node: UiNode): Boolean = when (key) {
            "clickable" -> node.clickable == expected
            "scrollable" -> node.scrollable == expected
            else -> error("unknown predicate key: $key")
        }
    }
}

internal object PathPatternParser {

    private val BOOLEAN_KEYS = setOf("clickable", "scrollable")
    private val VALUE_KEYS = setOf("class", "id", "text", "desc", "package")

    /** 解析失败抛 [PathSyntaxException]，携带可读原因；不做静默宽容。 */
    fun parse(path: String): List<PathSegment> {
        val trimmed = path.trim()
        if (trimmed.isEmpty()) throw PathSyntaxException("路径为空")
        return trimmed.split('>').map { before ->
            var text = before.trim()
            if (text.isEmpty()) throw PathSyntaxException("段 '$before' 为空")
            var index = 0
            if (text.endsWith("]")) {
                val open = text.lastIndexOf('[')
                if (open < 0) throw PathSyntaxException("段 '$before' 的 ']' 缺少配对的 '['")
                val digits = text.substring(open + 1, text.length - 1)
                if (digits.isEmpty() || digits.any { !it.isDigit() }) {
                    throw PathSyntaxException("段 '$before' 的序号 '$digits' 不是非负整数")
                }
                index = digits.toInt()
                text = text.substring(0, open).trim()
            }
            if (text.isEmpty()) throw PathSyntaxException("段 '$before' 只有序号、没有谓词")
            val parts = text.split('&').map { it.trim() }
            if (parts.any { it.isEmpty() }) throw PathSyntaxException("段 '$before' 含空谓词")
            PathSegment(raw = before.trim(), predicates = parts.map { parsePredicate(before.trim(), it) }, index = index)
        }
    }

    private fun parsePredicate(segment: String, part: String): SegmentPredicate {
        if (part == "*") return SegmentPredicate.AnyNode
        val eq = part.indexOf('=')
        if (eq < 0) {
            if (part.contains('[') || part.contains(']')) {
                throw PathSyntaxException("段 '$segment' 的谓词 '$part' 语法非法")
            }
            return SegmentPredicate.Equals("class", part)
        }
        val key = part.substring(0, eq).trim().lowercase()
        val value = part.substring(eq + 1).trim()
        if (key.isEmpty()) throw PathSyntaxException("段 '$segment' 的谓词 '$part' 缺少键名")
        if (key in BOOLEAN_KEYS) {
            val expected = when (value.lowercase()) {
                "true" -> true
                "false" -> false
                else -> throw PathSyntaxException("段 '$segment'：$key 的取值 '${value}' 只能是 true/false")
            }
            return SegmentPredicate.BooleanFlag(key, expected)
        }
        if (key !in VALUE_KEYS) {
            val allowed = (VALUE_KEYS + BOOLEAN_KEYS).sorted().joinToString("/")
            throw PathSyntaxException("段 '$segment' 的未知谓词键 '$key'（可用：$allowed）")
        }
        if (value.isEmpty()) throw PathSyntaxException("段 '$segment' 的谓词 '$part' 缺少取值")
        return SegmentPredicate.Equals(key, value)
    }
}
