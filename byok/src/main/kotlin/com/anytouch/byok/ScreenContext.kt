package com.anytouch.byok

/**
 * 屏上下文最小化（S3 裁 2 + 军令 §3-5）。
 *
 * 输入是节点四元组的**事实**，不是 Android 类型——走树取数留在 app 侧，判据全部住这里，
 * 这样"关开关=零条上行""可编辑节点只报存在""密码框整条剔除"三条红线才有 JVM 锁。
 *
 * 上行词表白名单只有两样：可见 text、resource-id 的 entry 名。
 * contentDescription 不在名单内（它常被人写成整段说明文字，且回放定位用不上它上行）。
 */
data class ScreenNodeFact(
    val text: String? = null,
    val resourceId: String? = null,
    val className: String? = null,
    /** 可编辑=输入框。它的 text 属于用户数据，一律不上行，只上行"这里有个输入框"。 */
    val editable: Boolean = false,
    /** 密码/数字签名类：整条剔除，连 id 都不上行。 */
    val passwordLike: Boolean = false,
    val visibleToUser: Boolean = true,
) {
    override fun toString(): String =
        "ScreenNodeFact(resourceId=$resourceId, className=$className, editable=$editable, " +
            "passwordLike=$passwordLike, visibleToUser=$visibleToUser, text=***)"
}

/** 上行词表与"为什么只剩这几条"的账。条数账必须上屏，否则用户不知道被带走了什么。 */
data class ScreenContext(
    val enabled: Boolean,
    val lines: List<String>,
    val nodesSeen: Int,
    val droppedPassword: Int,
    val droppedInvisible: Int,
    val droppedHiddenContent: Int,
    val droppedDuplicate: Int,
    val droppedOverCap: Int,
) {
    val uploadedCount: Int get() = lines.size

    /** 给模型的上下文块；无内容时返回空串——空串意味着"一个字都没上行"。 */
    fun render(): String = if (lines.isEmpty()) "" else buildString {
        appendLine("【当前屏幕可见词表】（仅供定位，坐标一律禁止）")
        lines.forEach { appendLine(it) }
    }

    fun notice(): String = if (!enabled) {
        "屏幕上下文：已关闭——本次编译不上行任何屏幕文本"
    } else {
        buildString {
            append("屏幕上下文：已开启——屏上 $nodesSeen 个节点，本次上行 $uploadedCount 条可见文本")
            val drops = buildList {
                if (droppedPassword > 0) add("密码类整条剔除 $droppedPassword")
                if (droppedHiddenContent > 0) add("输入框只报存在 $droppedHiddenContent")
                if (droppedInvisible > 0) add("屏外 $droppedInvisible")
                if (droppedDuplicate > 0) add("重复 $droppedDuplicate")
                if (droppedOverCap > 0) add("超上限 $droppedOverCap")
            }
            if (drops.isNotEmpty()) append("；").append(drops.joinToString("、"))
        }
    }

    companion object {
        /** 关掉开关的样子：零条、且渲染出来是空串。 */
        fun disabled(nodesSeen: Int = 0): ScreenContext =
            ScreenContext(false, emptyList(), nodesSeen, 0, 0, 0, 0, 0)
    }
}

class ScreenContextBuilder(private val cap: Int = DEFAULT_CAP) {

    init {
        require(cap > 0) { "cap must be > 0 but was $cap" }
    }

    fun build(facts: List<ScreenNodeFact>, enabled: Boolean = true): ScreenContext {
        // 关开关必须短路到零条：不能"关了小上行但顺手把条数账也报出去"。
        if (!enabled) return ScreenContext(false, emptyList(), facts.size, 0, 0, 0, 0, 0)
        // 可见的先上行：不可见节点的词表会把模型引到还没出现的页面上去。
        val ordered = facts.withIndex().sortedWith(
            compareBy({ !it.value.visibleToUser }, { it.index }),
        )
        val lines = ArrayList<String>(minOf(cap, facts.size))
        val seen = HashSet<String>()
        var password = 0
        var invisible = 0
        var hiddenContent = 0
        var duplicate = 0
        var overCap = 0
        for ((_, f) in ordered) {
            if (f.passwordLike) {
                password++
                continue
            }
            if (!f.visibleToUser) {
                invisible++
                continue
            }
            val idEntry = f.resourceId?.substringAfter('/')?.takeIf { it.isNotBlank() }
            // 类名自己像输入框也算可编辑：宁可少上行一条词表，也不冒"把用户输入内容带出去"的风险。
            val editable = f.editable || f.className.orEmpty().contains("EditText") ||
                f.className.orEmpty().contains("TextInput") ||
                f.className.orEmpty().contains("AutoCompleteTextView")
            val line = if (editable) {
                if (!f.text.isNullOrBlank()) hiddenContent++
                "input_field(" + (idEntry?.let { "id=$it" } ?: f.className.shortOr("unknown")) + ")"
            } else {
                val text = f.text?.trim()?.takeIf { it.isNotEmpty() }
                when {
                    text != null -> "text=\"$text\""
                    idEntry != null -> "id=$idEntry"
                    else -> null
                }
            } ?: continue
            if (!seen.add(line)) {
                duplicate++
                continue
            }
            if (lines.size >= cap) {
                overCap++
                continue
            }
            lines += line
        }
        return ScreenContext(
            enabled = true,
            lines = lines,
            nodesSeen = facts.size,
            droppedPassword = password,
            droppedInvisible = invisible,
            droppedHiddenContent = hiddenContent,
            droppedDuplicate = duplicate,
            droppedOverCap = overCap,
        )
    }

    companion object {
        const val DEFAULT_CAP = 40
    }
}

private fun String?.shortOr(fallback: String): String = this?.substringAfterLast('.')?.takeIf { it.isNotBlank() } ?: fallback
