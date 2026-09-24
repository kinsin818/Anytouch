package com.anytouch.app.recorder.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * STAGE-31 31-A：采集层两判据（A4 [descendantClueOf] / A5 [pinEventClueOf] + [fieldPinHits]）的 JVM 锁。
 *
 * 语料一律 ASCII（本仓第 11 颗雷：中文参数走 adb/gradle 会转码）。
 * 每条用例都必须"判据被改坏就会红"——反向改动自检两轮原始输出见
 * `evidence/S31/stage31-a-worker-report.md`。
 */
class CaptureCluesTest {

    private fun n(
        text: String? = null,
        desc: String? = null,
        title: Boolean = false,
        children: List<ClueNode> = emptyList(),
    ) = ClueNode(text, desc, title, children)

    /** 广度序生成器：root + count 个叶子（用来精确踩 DESCENDANT_BUDGET 的边界）。 */
    private fun wideRoot(count: Int, textAt: Int, word: String): ClueNode =
        n(children = (0 until count).map { i -> n(text = if (i == textAt) word else null) })

    /** 一条链：根在第 0 层，末端节点在第 [chainDepth] 层。 */
    private fun chain(chainDepth: Int, textAtEnd: String): ClueNode {
        var node = n(text = textAtEnd)
        repeat(chainDepth) { node = n(children = listOf(node)) }
        return node
    }

    // ==========================================================================================
    // A4：descendantClueOf —— 判序 ①触顶弃 ②唯一 title ③文本集唯一 ④无文本时 desc 唯一
    // ==========================================================================================

    @Test
    fun `唯一 title 优先于歧义的子树文本`() {
        val root = n(
            children = listOf(
                n(text = "Wi-Fi", title = true),
                n(text = "Shows available networks and Bluetooth pairing"),
            ),
        )
        assertEquals(CluePick.Text("Wi-Fi"), descendantClueOf(root, budget = 32, depth = 8))
    }

    @Test
    fun `两个 title 就是歧义 整条弃用而不是取第一个`() {
        val root = n(children = listOf(n(text = "Wi-Fi", title = true), n(text = "Bluetooth", title = true)))
        val pick = descendantClueOf(root, budget = 32, depth = 8)
        assertTrue("titleCount>1 时不许退回'取第一个 title'", pick is CluePick.Rejected)
        assertEquals(CluePick.Rejected(titleCount = 2, textCount = 2, descCount = 0, truncated = false), pick)
    }

    @Test
    fun `无 title 时子树文本唯一才取`() {
        val root = n(children = listOf(n(desc = "icon"), n(text = "Connected devices")))
        assertEquals(CluePick.Text("Connected devices"), descendantClueOf(root, budget = 32, depth = 8))
    }

    @Test
    fun `文本歧义时 desc 不救场`() {
        val root = n(children = listOf(n(text = "Wi-Fi"), n(text = "Bluetooth"), n(desc = "Settings row")))
        assertEquals(
            CluePick.Rejected(titleCount = 0, textCount = 2, descCount = 1, truncated = false),
            descendantClueOf(root, 32, 8),
        )
    }

    @Test
    fun `子树一个文本都没有时 desc 唯一才取`() {
        val root = n(children = listOf(n(desc = "Play"), n(), n()))
        assertEquals(CluePick.Desc("Play"), descendantClueOf(root, budget = 32, depth = 8))
    }

    @Test
    fun `desc 自身歧义也弃`() {
        val root = n(children = listOf(n(desc = "Play"), n(desc = "Pause")))
        assertEquals(
            CluePick.Rejected(titleCount = 0, textCount = 0, descCount = 2, truncated = false),
            descendantClueOf(root, 32, 8),
        )
    }

    @Test
    fun `预算内没轮到的节点绝不进线索集`() {
        // 广度序：root=visited1，前 31 个子节点走到 visited=32 触顶，第 32 号子节点（唯一带字的）没轮到读。
        val root = wideRoot(count = 32, textAt = 31, word = "Hidden row")
        val pick = descendantClueOf(root, budget = 32, depth = 8)
        assertFalse("触顶时不得把没读到的词当线索编出来", pick is CluePick.Text)
        assertEquals(CluePick.Rejected(titleCount = 0, textCount = 0, descCount = 0, truncated = true), pick)
    }

    @Test
    fun `刚好用满预算不算触顶`() {
        val root = wideRoot(count = 31, textAt = 30, word = "Only row")
        assertEquals(CluePick.Text("Only row"), descendantClueOf(root, budget = 32, depth = 8))
    }

    @Test
    fun `深度上限之外的子树不参与取证`() {
        // 唯一文本挂在第 9 层（判据 level < 8 时才对下一层放行）：读不到 → 弃，宁退 path。
        val deep = chain(chainDepth = 9, textAtEnd = "Deep row")
        assertEquals(
            CluePick.Rejected(titleCount = 0, textCount = 0, descCount = 0, truncated = false),
            descendantClueOf(deep, 32, 8),
        )
        // 同一棵树挪到第 8 层就在深度内命中（证明上一条红在深度判据、不在预算）。
        val ok = chain(chainDepth = 8, textAtEnd = "Deep row")
        assertEquals(CluePick.Text("Deep row"), descendantClueOf(ok, 32, 8))
    }

    @Test
    fun `空白的 title 不算 title 计数`() {
        val root = n(children = listOf(n(text = "   ", title = true), n(text = "Airplane mode")))
        assertEquals(CluePick.Text("Airplane mode"), descendantClueOf(root, budget = 32, depth = 8))
    }

    @Test
    fun `首尾空格同一个词算同一条文本 全空白算没有文本`() {
        val same = n(children = listOf(n(text = "Sound"), n(text = "  Sound  ")))
        assertEquals(CluePick.Text("Sound"), descendantClueOf(same, budget = 32, depth = 8))
        val blank = n(children = listOf(n(text = " "), n(desc = "  ")))
        assertEquals(
            CluePick.Rejected(titleCount = 0, textCount = 0, descCount = 0, truncated = false),
            descendantClueOf(blank, 32, 8),
        )
    }

    @Test
    fun `弃用带着证据计数 上层能原样留痕`() {
        val root = n(
            children = listOf(
                n(text = "A", title = true), n(text = "B", title = true), n(desc = "D"), n(desc = "E"),
            ),
        )
        val pick = descendantClueOf(root, budget = 32, depth = 8) as CluePick.Rejected
        assertEquals(2, pick.titleCount)
        assertEquals(2, pick.textCount)
        assertEquals(2, pick.descCount)
        assertFalse(pick.truncated)
    }

    // ==========================================================================================
    // A5：pinEventClueOf —— 雷 15（不交叉拷贝）与雷 17（desc 侧唯一一级豁免）的分界
    // ==========================================================================================

    @Test
    fun `词只命中活树 desc 时记 desc 词汇 不照抄成 text`() {
        // 设备实证形态：event.text 里带着 "Navigate up"，而活树中该串只存在于 contentDescription 字段。
        val pin = pinEventClueOf("Navigate up", null, treeTextHits = 0, treeDescHits = 1, allowDescExemption = false)
        assertEquals(CluePin.ByDesc("Navigate up", viaEventFieldExemption = false), pin)
        assertFalse("照抄成 text 词汇=回放 L2 零命中", pin is CluePin.ByText)
    }

    @Test
    fun `词命中活树 text 唯一时记 text`() {
        assertEquals(
            CluePin.ByText("Connected devices"),
            pinEventClueOf("Connected devices", null, 1, 0, allowDescExemption = false),
        )
    }

    @Test
    fun `两字段各命中一个时判序走 text`() {
        assertTrue(pinEventClueOf("Sound", "Sound", 1, 1, false) is CluePin.ByText)
    }

    @Test
    fun `text 歧义而 desc 唯一时判序走 desc`() {
        // 原判据的 when 顺序：descHits == 1 排在 >1 判定之前——这条顺序就是"字段归属靠活树裁断"的落点。
        assertEquals(
            CluePin.ByDesc("Back", viaEventFieldExemption = false),
            pinEventClueOf("Back", null, 2, 1, allowDescExemption = false),
        )
    }

    @Test
    fun `desc 歧义而 text 唯一时判序仍走 text`() {
        assertTrue(pinEventClueOf("Back", "Back", 1, 3, allowDescExemption = true) is CluePin.ByText)
    }

    @Test
    fun `零命中在未封顶时不是歧义 而是还要再扫一次树`() {
        val pin = pinEventClueOf("Connected devices", "Connected devices", 0, 0, allowDescExemption = false)
        assertEquals(CluePin.Cause.AwaitingTree, (pin as CluePin.Dropped).cause)
        assertFalse("0/0 与 >1 不得走同一条落点", pin is CluePin.Ambiguous)
    }

    @Test
    fun `多命中是真歧义 封顶也不会退到豁免边`() {
        assertEquals(
            CluePin.Ambiguous("Wi-Fi", textHits = 2, descHits = 0),
            pinEventClueOf("Wi-Fi", "Some desc", 2, 0, allowDescExemption = true),
        )
        assertEquals(
            CluePin.Ambiguous("Wi-Fi", textHits = 0, descHits = 2),
            pinEventClueOf("Wi-Fi", "Some desc", 0, 2, allowDescExemption = true),
        )
    }

    @Test
    fun `text 侧永远走不到豁免边`() {
        // 雷 15 与雷 17 的分界（本批最严一条）：豁免担保的是"event.contentDescription 这个字段可信"，
        // 与"词是从 event.text 抄来的"无关——哪怕两串字面一模一样，落点也只能是 desc 词汇。
        val same = pinEventClueOf("Back", "Back", 0, 0, allowDescExemption = true)
        assertEquals(CluePin.ByDesc("Back", viaEventFieldExemption = true), same)
        assertFalse("text 侧永走不到豁免边：豁免档只能是 ByDesc", same is CluePin.ByText)
        // 词与 desc 不同时更不许把词记成 text。
        val diff = pinEventClueOf("Navigate up", "Up", 0, 0, allowDescExemption = true)
        assertEquals(CluePin.ByDesc("Up", viaEventFieldExemption = true), diff)
    }

    @Test
    fun `豁免边只给 desc 侧 且只在重扫封顶后给`() {
        val capped = pinEventClueOf("Up", "Navigate up", 0, 0, allowDescExemption = true)
        assertEquals(CluePin.ByDesc("Navigate up", viaEventFieldExemption = true), capped)
        val notYet = pinEventClueOf("Up", "Navigate up", 0, 0, allowDescExemption = false)
        assertEquals(CluePin.Cause.AwaitingTree, (notYet as CluePin.Dropped).cause)
    }

    @Test
    fun `封顶后仍零命中且事件没 desc 时弃用 该步显形失踪`() {
        val pin = pinEventClueOf("Up", null, 0, 0, allowDescExemption = true)
        assertEquals(CluePin.Cause.NoEvidence, (pin as CluePin.Dropped).cause)
        assertEquals("Up", pin.word)
        assertEquals(0, pin.textHits)
        assertEquals(0, pin.descHits)
    }

    @Test
    fun `压根没词不与零命中同档 也吃不到豁免`() {
        val pin = pinEventClueOf(null, "Navigate up", 0, 0, allowDescExemption = true)
        assertEquals(CluePin.Cause.NoWord, (pin as CluePin.Dropped).cause)
        assertFalse("没词=没有证词，不许凭 desc 凭空造一格线索", pin is CluePin.ByDesc)
    }

    @Test
    fun `四档落点互不相同 上层无需猜 null`() {
        val outcomes = listOf(
            pinEventClueOf("A", null, 1, 0, false),
            pinEventClueOf("A", "B", 0, 1, false),
            pinEventClueOf("A", null, 2, 0, true),
            pinEventClueOf("A", null, 0, 0, true),
            pinEventClueOf(null, null, 0, 0, false),
        )
        assertEquals(4, outcomes.map { it::class.simpleName }.distinct().size)
        val dropped = outcomes.filterIsInstance<CluePin.Dropped>()
        assertEquals(2, dropped.size)
        assertEquals(listOf(CluePin.Cause.NoEvidence, CluePin.Cause.NoWord), dropped.map { it.cause })
    }

    // ==========================================================================================
    // A5 取数侧的比较判据：fieldPinHits（各比各的字段 + trim 全等）
    // ==========================================================================================

    @Test
    fun `词只与 text 字段全等时算 text 命中`() {
        assertEquals(true to false, fieldPinHits("Sound", PinFieldFact("Sound", "Volume")))
    }

    @Test
    fun `词只与 desc 字段全等时算 desc 命中 不许串到 text`() {
        assertEquals(false to true, fieldPinHits("Navigate up", PinFieldFact(null, "Navigate up")))
    }

    @Test
    fun `两字段同词时两格都报 由判序裁落点`() {
        assertEquals(true to true, fieldPinHits("Sound", PinFieldFact("Sound", "Sound")))
    }

    @Test
    fun `包含不算命中`() {
        // 回放二阶是全等比较：这里若放宽成 contains，"Sound" 会命中整页搜索结果列表 → 歧义被算成唯一。
        assertEquals(false to false, fieldPinHits("Sound", PinFieldFact("Sound and display", "Volume")))
        assertEquals(false to false, fieldPinHits("Sound", PinFieldFact(null, "Play sound now")))
    }

    @Test
    fun `活树字段首尾空格仍算全等 纯空白不算命中`() {
        assertEquals(true to false, fieldPinHits("Sound", PinFieldFact("  Sound ", null)))
        assertEquals(false to true, fieldPinHits("Sound", PinFieldFact("\t ", "Sound")))
    }

    @Test
    fun `字段缺失不参与比较`() {
        assertEquals(false to false, fieldPinHits("Sound", PinFieldFact(null, null)))
    }

    @Test
    fun `空白即缺失的口径只有一份`() {
        assertEquals(null, blankToNull("   "))
        assertEquals("Sound", blankToNull(" Sound "))
        assertEquals(null, blankToNull(null))
    }
}
