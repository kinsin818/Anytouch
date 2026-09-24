package com.anytouch.app.recorder.capture

/**
 * 采集层判据（STAGE-31 31-A：A4 + A5 从 `AndroidCaptureBridge` **整体搬入**，原位置只剩取数与转调）。
 *
 * 本文件 android-free：不接 AccessibilityNodeInfo / AccessibilityEvent / Context，也不写日志。
 * 平台侧只做两件事——把节点树摊成本文件的数据类、把判据结论原样落成日志痕（"错误必显示"住在有句柄的那一侧）。
 * 判据分支一条都不许留在平台侧（雷 18 同族：两处实现只有坏的那条会被看见）。
 */

/**
 * 空白即缺失：trim 后为空串的 text/desc 一律按"这个字段没有内容"记。
 * 全仓唯一一份口径——平台侧私有的 `String?.orNull()` 转调本函数，不留第二份实现。
 */
fun blankToNull(value: String?): String? = value?.trim()?.takeIf { it.isNotEmpty() }

// ============================================================================================
// A4：被点节点自身无线索时，向其**子树**求唯一线索（防滑轨 = 绝不编造线索）
// ============================================================================================

/**
 * 子树的读形（由平台侧摊平后交进来）。只有字段与父子关系，没有任何节点句柄——
 * "走到第几个节点该停"是 [descendantClueOf] 的判据，摊平侧无权替它先决定（它只保证不越预算去多要句柄）。
 */
data class ClueNode(
    val text: String?,
    val desc: String?,
    val isTitleId: Boolean,
    val children: List<ClueNode> = emptyList(),
)

/** A4 的结论：给出线索，或**带着证据计数**弃用（弃用必须可被上层观测到并留痕，不得静默退 path）。 */
sealed interface CluePick {
    data class Text(val text: String) : CluePick

    data class Desc(val desc: String) : CluePick

    /**
     * @param truncated 预算内没走完子树——连"歧不歧义"都还没看清，这条证据整条作废
     * @param titleCount 有文字的 `android:id/title` 节点数（0 与 ≥2 都不构成线索）
     */
    data class Rejected(val titleCount: Int, val textCount: Int, val descCount: Int, val truncated: Boolean) : CluePick
}

/**
 * 判序（每一条都是"宁要脆的 path，不要可能指向别行的 text"）：
 * ① 触顶截断 → 弃（证据没看全，谈不上唯一）；
 * ② 子树内**恰好一个** `android:id/title` 文本 → 取它（偏好设置双行行的 summary 文本天然不构成歧义，
 *    title 就是用户读到的那一行，所以这一档排在文本集唯一性**之前**）；
 * ③ 否则子树文本集**唯一**才取；
 * ④ 一个文本都没有时，`contentDescription` 集唯一才取（文本歧义时 desc **不**救场）。
 * 遍历序=广度优先、文档序兄弟，与回放端 indexPath 的词汇同序；深度上限 [depth]、节点上限 [budget]。
 */
fun descendantClueOf(root: ClueNode, budget: Int, depth: Int): CluePick {
    val texts = LinkedHashSet<String>()
    val descs = LinkedHashSet<String>()
    var titleCount = 0
    var titleText: String? = null
    val queue = ArrayDeque(listOf(root to 0))
    var visited = 0
    var truncated = false
    while (queue.isNotEmpty()) {
        if (visited >= budget) {
            truncated = true
            break
        }
        val (node, level) = queue.removeFirst()
        visited++
        blankToNull(node.text)?.let { t ->
            texts.add(t)
            // title 只给"有字的"计数：这条判据原本就挂在 text 分支里面，空 title 不算证据。
            if (node.isTitleId) {
                titleCount++
                if (titleText == null) titleText = t
            }
        }
        blankToNull(node.desc)?.let { descs.add(it) }
        if (level < depth) {
            for (child in node.children) queue.addLast(child to level + 1)
        }
    }
    val picked: CluePick? = when {
        truncated -> null
        titleCount == 1 -> titleText?.let { CluePick.Text(it) }
        texts.size == 1 -> texts.first()?.let { CluePick.Text(it) }
        texts.isEmpty() && descs.size == 1 -> descs.first()?.let { CluePick.Desc(it) }
        else -> null
    }
    return picked ?: CluePick.Rejected(titleCount, texts.size, descs.size, truncated)
}

// ============================================================================================
// A5：把事件"当场拷贝"的词钉成回放真能用的**定位词汇**（雷 15 / 雷 17 的分界）
// ============================================================================================

/** 活树里一个节点的两个"可钉字段"读形：text 与 desc 各是各的证词，摊平侧不许合并成一个字符串。 */
data class PinFieldFact(val text: String?, val desc: String?)

/**
 * 一词落在节点的哪个字段上——**按活树同口径、不交叉**（雷 15 的判据本体）。
 *
 * 设备实证：工具栏返回键的点击事件 text 里带着 "Navigate up"，而活树中该串只存在于
 * `contentDescription` 字段（`text` 为空）。回放端 [com.anytouch.app.locator.NodeTreeLocator]
 * 二阶只拿 text 比 `node.text`、拿 desc 比 `node.contentDescription`，所以这里必须同样
 * "trim 后全等 + 各比各的字段"：改成包含匹配或允许交叉，就是照抄证词 → 回放 L2 零命中。
 */
fun fieldPinHits(word: String, node: PinFieldFact): Pair<Boolean, Boolean> =
    (blankToNull(node.text) == word) to (blankToNull(node.desc) == word)

/**
 * A5 的四档落点（原判据是 `Pair<String?, String?>?`，三档 null 混在一起＝上层无从区分
 * "压根没词"与"词歧义"，也就写不出逐字的留痕）：
 * - [ByText] / [ByDesc]：钉到了活树字段，`word` 即写入快照的线索；
 * - [Ambiguous]：某侧命中 >1，真歧义，永不进豁免边；
 * - [Dropped]：弃用。`cause` 让上层能分别留痕——[CluePin.Cause.AwaitingTree] 不是结论而是
 *   "这帧树还没长全，平台侧该重扫"（重扫封顶与 sleep 住在平台侧，见 `AndroidCaptureBridge.pinEventClue`）。
 */
sealed interface CluePin {
    /** [Dropped] 的三档原因：上层要按档分别留痕，所以"为什么弃"不能糊成一个 null。 */
    enum class Cause {
        /** 事件压根没带词（非点击类 / text 列表与 desc 皆空）。 */
        NoWord,

        /** 两侧都 0 命中且还没到重扫封顶：0 命中≠歧义，不得与 [Ambiguous] 同落点。 */
        AwaitingTree,

        /** 重扫封顶后仍 0/0，而事件连 desc 都没给：该步必须显形失踪（上层留痕后丢弃）。 */
        NoEvidence,
    }

    data class ByText(val word: String) : CluePin

    /** [viaEventFieldExemption]=true 表示这格 desc 词汇**没经过窗口验证**，是事件字段直拷（雷 17 那一档）。 */
    data class ByDesc(val word: String, val viaEventFieldExemption: Boolean) : CluePin

    data class Ambiguous(val word: String?, val textHits: Int, val descHits: Int) : CluePin

    data class Dropped(val cause: Cause, val word: String?, val textHits: Int, val descHits: Int) : CluePin
}

/**
 * @param eventText 事件当场拷贝的那个词（`event.text` 首项，取不到时是 `event.contentDescription`；
 *                  它只是**词**，不决定该记进哪个字段）
 * @param treeTextHits / treeDescHits 平台侧扫当前窗口活树所得（口径见 [fieldPinHits]）
 * @param allowDescExemption 重扫封顶才为 true：豁免边不许在"树还没长全"的帧上走
 */
fun pinEventClueOf(
    eventText: String?,
    eventDesc: String?,
    treeTextHits: Int,
    treeDescHits: Int,
    allowDescExemption: Boolean,
): CluePin {
    if (eventText == null) return CluePin.Dropped(CluePin.Cause.NoWord, null, treeTextHits, treeDescHits)
    // 判序逐行照搬，一处不能换：text 唯一 → desc 唯一 → 任一侧 >1 才算歧义。
    when {
        treeTextHits == 1 -> return CluePin.ByText(eventText)
        treeDescHits == 1 -> return CluePin.ByDesc(eventText, viaEventFieldExemption = false)
        treeTextHits > 1 || treeDescHits > 1 -> return CluePin.Ambiguous(eventText, treeTextHits, treeDescHits)
    }
    // 走到这里=两侧都 0 命中。点击落下时页面常被自己换掉，此刻扫到的往往是"新页树还没长全"的那一帧
    // （设备实证：18 轮里 3 轮首步的 "Connected devices" 扫到 0/0，而同轮稍后再扫句柄已在新根里 search 到），
    // 所以它是"再扫一次"的证据，不是"词歧义"的证据。
    if (!allowDescExemption) {
        return CluePin.Dropped(CluePin.Cause.AwaitingTree, eventText, treeTextHits, treeDescHits)
    }
    val desc = eventDesc
        ?: return CluePin.Dropped(CluePin.Cause.NoEvidence, eventText, treeTextHits, treeDescHits)
    // 雷 17 的豁免边：**只有这一级**免窗口验证，且**只给 desc 侧**——
    // `event.contentDescription` 由框架从被点节点的该字段直拷（不像 `event.text` 会把整行子树文字聚合进来），
    // 字段归属不必靠活树裁断。设备实证：src=null 的返回键点击走这条路前整步被弃（录 8 步只出 7 步）。
    // 注意即便 eventText 与 eventDesc 字面相同，落点也仍是 desc 词汇：豁免担保的是"这个字段可信"，
    // 不是"这个词该记进哪个字段"——text 侧永远走不到这条边（雷 15 与雷 17 的分界，用例单独锁死）。
    return CluePin.ByDesc(desc, viaEventFieldExemption = true)
}
