package com.anytouch.app.executor

import com.anytouch.contracts.ActionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * STAGE-31-B：执行器三条真实判据（A1 [redispatchPlan] / A2 [textDispatchRoute] / A3 [landedViaTreeScan]）
 * 的 JVM 锁。靶表=母单 `orders/ANYTOUCH-S31-ORDER.md` §1 的 A1/A2/A3 三行（含点名的反例四条）。
 *
 * 语料一律 ASCII（本仓第 11 颗雷：中文参数走 adb/gradle 会转码）。
 * 每条用例都必须"判据被改坏就会红"——反向改动探针的原始输出见
 * `evidence/S31/stage31-b-worker-report.md` §4。
 *
 * 这里测的是**判据本身**；派发/沉降/设备调用留在平台侧（军令 A1 点名的假下沉形态），
 * 那些边的行为由 `NodeTaskRunnerTest` 的队列级用例锁。
 */
class ExecutorDecisionsTest {

    private fun edit(text: String?) = LandedNodeFact("android.widget.EditText", text)

    private fun text(text: String?) = LandedNodeFact("android.widget.TextView", text)

    // ==========================================================================================
    // A1：redispatchPlan —— 明示拒绝才重派、封顶认额度、true/WAIT 一律不派
    //
    // S5-R12 钉 9 之后这里**不再写死次数**：第三个入参是 [StepRetryPolicy] 下发的剩余重试额度
    // （非高危 2、高危 0）。判据只回答"额度还有没有"，额度本身由 `StepRetryPolicyTest` 锁。
    // ==========================================================================================

    @Test
    fun `明示拒绝且额度未尽 非WAIT 该按原线索重派`() {
        for (type in listOf(ActionType.CLICK, ActionType.SCROLL, ActionType.TYPE_TEXT)) {
            assertEquals(
                "performAction 明示拒（false=动作根本没执行过）时 $type 该按原线索重派",
                Redispatch.RetryOnce,
                redispatchPlan(performed = false, type = type, retriesLeft = 2),
            )
            assertEquals(Redispatch.RetryOnce, redispatchPlan(performed = false, type = type, retriesLeft = 1))
        }
    }

    @Test
    fun `额度归零即GiveUp 负数也不给`() {
        for (type in listOf(ActionType.CLICK, ActionType.SCROLL, ActionType.TYPE_TEXT)) {
            assertEquals(
                "$type 额度用尽后必须收 perform_failed，不许自己再翻额度",
                Redispatch.GiveUp,
                redispatchPlan(performed = false, type = type, retriesLeft = 0),
            )
            assertEquals(Redispatch.GiveUp, redispatchPlan(false, type, -1))
            assertEquals(Redispatch.GiveUp, redispatchPlan(false, type, -9))
        }
    }

    @Test
    fun `最坏输入下重派次数恰好等于额度 不越界`() {
        // 把平台侧那个 while(true) 的推进规则原样演一遍：设备对每次派发都明示拒绝（最坏输入），
        // 统计实际发生的重派次数，以及出环时是"以 perform_failed 收官"（返回负数）还是"被接收"（正数）。
        // 判据被放宽（去掉额度判断）时这里会变多或直接不收敛——用例即红。
        assertEquals(
            "非高危：额度 2 就用满 2 次，第三派不给",
            -StepRetryPolicy.MAX_STEP_RETRIES,
            observedRedispatchRounds(ActionType.TYPE_TEXT, riskMatched = false),
        )
        assertEquals("click 同理", -2, observedRedispatchRounds(ActionType.CLICK, riskMatched = false))
        assertEquals("scroll 同理", -2, observedRedispatchRounds(ActionType.SCROLL, riskMatched = false))
        assertEquals(
            "高危（三裁 ①）：一档重派都不给，直接收红——弹窗已经确认过一次，绝不自动重来",
            0,
            observedRedispatchRounds(ActionType.CLICK, riskMatched = true),
        )
    }

    /** 平台侧推进规则的镜像**演练**（不是第二份判据）：判据只调 [redispatchPlan]，额度只问 [StepRetryPolicy]，这里只数它给了几档 RetryOnce。 */
    private fun observedRedispatchRounds(type: String, riskMatched: Boolean): Int {
        var dispatched = false // 设备最坏输入：每一派都明示拒绝
        var retriesUsed = 0
        var retries = 0
        while (true) {
            when (redispatchPlan(dispatched, type, StepRetryPolicy.retriesLeft(riskMatched, retriesUsed))) {
                Redispatch.Skip -> return retries
                Redispatch.GiveUp -> return -retries
                Redispatch.RetryOnce -> {
                    retries += 1
                    retriesUsed += 1
                }
            }
        }
    }

    @Test
    fun `派发已被接收则Skip 与轮数和类型无关`() {
        for (type in listOf(ActionType.CLICK, ActionType.SCROLL, ActionType.TYPE_TEXT, ActionType.WAIT, ActionType.KEY)) {
            for (retriesLeft in 0..2) {
                assertEquals(
                    "performed=true 之后任何再派都是重复点击/重复输入，一档都不给（$type retriesLeft=$retriesLeft）",
                    Redispatch.Skip,
                    redispatchPlan(performed = true, type = type, retriesLeft = retriesLeft),
                )
            }
        }
    }

    @Test
    fun `WAIT永不进入重派 额度再足也不派`() {
        assertEquals(Redispatch.Skip, redispatchPlan(performed = false, type = ActionType.WAIT, retriesLeft = 2))
        assertEquals(Redispatch.Skip, redispatchPlan(performed = false, type = ActionType.WAIT, retriesLeft = 0))
    }

    @Test
    fun `三档落点互不相同 调用方不必猜null`() {
        val outcomes = listOf(
            redispatchPlan(true, ActionType.CLICK, 2),
            redispatchPlan(false, ActionType.CLICK, 2),
            redispatchPlan(false, ActionType.CLICK, 0),
        )
        assertEquals(
            "Skip/RetryOnce/GiveUp 三档各占一个输入格：互换落点=把已接收当失败停机、或把封顶已过再派一次",
            listOf(Redispatch.Skip, Redispatch.RetryOnce, Redispatch.GiveUp),
            outcomes,
        )
    }

    @Test
    fun `除WAIT外的类型一律按原判据参与重派 含未支持类型`() {
        // 原判据写的是 `action.type != ActionType.WAIT`（黑名单一条），不是白名单：
        // 改成 `when` 白名单会把 key/submit 这类"走 else->false"的派发从"重派到额度用尽再收红"变成"直接收红"。
        // （`run()` 的 when 目前只把 CLICK/SCROLL/TYPE_TEXT 送进 runNodeStep，这条锁的是判据字面等价性。）
        for (type in listOf(ActionType.KEY, ActionType.SUBMIT, ActionType.SELECT_DROPDOWN, "teleport")) {
            assertEquals(Redispatch.RetryOnce, redispatchPlan(performed = false, type = type, retriesLeft = 2))
            assertEquals(Redispatch.GiveUp, redispatchPlan(performed = false, type = type, retriesLeft = 0))
        }
    }

    // ==========================================================================================
    // A2：textDispatchRoute —— 两通道合计；成败终裁不在这里
    // ==========================================================================================

    @Test
    fun `两通道真值表四格锁死`() {
        assertFalse("两通道双双明示拒才是这一路失败", textDispatchRoute(setTextOk = false, pasteOk = false))
        assertTrue(textDispatchRoute(setTextOk = true, pasteOk = false))
        assertTrue(textDispatchRoute(setTextOk = false, pasteOk = true))
        assertTrue(textDispatchRoute(setTextOk = true, pasteOk = true))
    }

    @Test
    fun `SET_TEXT明示拒false时PASTE通道单独决定合计`() {
        // 雷 12 的对称面：K40/MIUI 搜索框 SET_TEXT 直接回 false，兜底通道必须在这条边上场。
        assertFalse(textDispatchRoute(false, false))
        assertTrue(textDispatchRoute(false, true))
        assertNotEquals(
            "setTextOk=false 时合计必须跟着 paste 走；若两格同为 false 说明判据被写成了只看 SET_TEXT",
            textDispatchRoute(false, false),
            textDispatchRoute(false, true),
        )
    }

    @Test
    fun `虚报true与明示拒false两种失败形态都在同一合计里`() {
        // "虚报 true"（SET_TEXT 回 true 但不落字）与"明示拒 false"是两种不同失败形态，
        // 但 A2 这一层只看"派发被接收没有"：两者都返回 true，区别只能在 A3 那侧裁出来。
        assertEquals(true, textDispatchRoute(true, false))
        assertEquals(true, textDispatchRoute(false, true))
    }

    @Test
    fun `dispatched为true只代表派发被接收不得当成已落字`() {
        // 母单 A2 的反向锁：dispatched==true 与"落字"是两回事，绝不许在平台侧把 A2 的返回值当成功凭据。
        // 同一格 dispatched=true，A3 一侧可以是"根本没落字"——两问的答案必须由两个函数分别给出。
        val dispatched = textDispatchRoute(setTextOk = true, pasteOk = false)
        assertTrue("SET_TEXT 被接收即 dispatched=true（这一层不许提前判成功/失败）", dispatched)
        // 设备虚报形态：SET_TEXT 回 true，但全树里那个 EditText 仍是空的 → A3 判未落字。
        assertNull(landedViaTreeScan(listOf(edit("")), "hello"))
        assertNull(landedViaTreeScan(emptyList(), "hello"))
        // 反过来，A3 判落也不要求 A2 说 true（句柄活读那半边在平台侧）——两侧互不代答。
        assertEquals(edit("hello world"), landedViaTreeScan(listOf(edit("hello world")), "hello"))
    }

    // ==========================================================================================
    // A3：landedViaTreeScan —— 兜底扫树的两条件与四条反例
    // ==========================================================================================

    @Test
    fun `EditText含输入即判落`() {
        val hit = landedViaTreeScan(listOf(edit("hello world")), "hello")
        assertEquals(LandedNodeFact("android.widget.EditText", "hello world"), hit)
        // 类名是"含 EditText"而非全等：真实框架类名带包名与子类（AppCompatEditText / TextInputEditText）。
        val subclass = LandedNodeFact("androidx.appcompat.widget.AppCompatEditText", "hello world")
        assertEquals(subclass, landedViaTreeScan(listOf(subclass), "hello"))
    }

    @Test
    fun `反例一 TextView含同词不得判落`() {
        // 搜索结果列表形态：整页 TextView 都含这个词，判落就是把"列表项"当"输入框"。
        val nodes = listOf(
            text("hello"),
            LandedNodeFact("android.widget.Button", "hello"),
            LandedNodeFact(null, "hello"),
            text("hello again"),
        )
        assertNull("两条件缺一不可：className 不含 EditText 就不算落字", landedViaTreeScan(nodes, "hello"))
        // 同一批节点里补一个 EditText，才允许判落（证明上一条红在类名条件、不在文本条件）。
        val editable = edit("typed hello")
        assertEquals(editable, landedViaTreeScan(nodes + editable, "hello"))
    }

    @Test
    fun `反例二 want为空白不得短路成命中`() {
        // Kotlin 陷阱本体：`"任意文本".contains("") == true`，want 一旦被当空串传进来，
        // 没有这道早退就会把树里第一个 EditText 报成"已落字"（假绿，比假红更难查）。
        val nodes = listOf(edit("something else"), text("anything"))
        assertTrue("这正是恒真陷阱：contains(\"\") 在 Kotlin 里恒真", "anything".contains(""))
        assertNull("空串 want 不构成落字凭据", landedViaTreeScan(nodes, ""))
        assertNull("纯空格 want 不构成落字凭据", landedViaTreeScan(nodes, "   "))
        assertNull("制表/换行同样是空白", landedViaTreeScan(nodes, "\t\n "))
        // 节点文本本身为空 + want 空白也不许命中（原判据外层那句 if (want.isNotEmpty()) 一并搬在这里）。
        assertNull(landedViaTreeScan(listOf(edit("")), ""))
    }

    @Test
    fun `反例三 className为null不得命中`() {
        // 框架偶尔回 null 类名（自定义 View / 过渡期节点）：null 不许被当成"大概是输入框"。
        val nodes = listOf(LandedNodeFact(null, "hello"), LandedNodeFact("", "hello"))
        assertNull("className 为 null 或空串都不满足可编辑类条件", landedViaTreeScan(nodes, "hello"))
        // 同文本的 EditText 在位即命中（证明上一条红在类名条件）。
        val editable = edit("hello")
        assertEquals(editable, landedViaTreeScan(nodes + editable, "hello"))
    }

    @Test
    fun `反例四 含词但要trim后才等必须仍命中`() {
        // 匹配键=输入本身，且与句柄活读一侧同口径（那边读回即 trim）：want 首尾带空格不许漏判。
        val editable = edit("hello")
        assertEquals("want 首尾空格 trim 后与节点文本相等，必须仍判落", editable, landedViaTreeScan(listOf(editable), "  hello  "))
        assertEquals(editable, landedViaTreeScan(listOf(editable), "hello "))
        // 节点侧带首尾空格、want 干净：子串包含仍然命中（不许改成 trim 后全等）。
        val padded = edit("  hello  ")
        assertEquals(padded, landedViaTreeScan(listOf(padded), "hello"))
        // 反向锁：这里若把子串包含写成全等，含词但要前后有字的正例会一起塌——用另一条用例守住。
        assertEquals(editable, landedViaTreeScan(listOf(editable), "hel"))
    }

    @Test
    fun `两个节点都合格时文档序第一个胜出`() {
        val first = edit("hello one")
        val second = edit("hello two")
        assertEquals("兜底扫树取文档序第一个命中，与原判据 firstOrNull 一致（改成 lastOrNull 就换了一个节点）",
            first, landedViaTreeScan(listOf(first, second), "hello"))
        // 文档序里第一个合格的节点排在合格节点之前也照取（不合格项不得抢占）。
        assertEquals(first, landedViaTreeScan(listOf(text("hello"), first, second), "hello"))
    }

    @Test
    fun `空树返回null而不是命中`() {
        // 平台侧 device.root() 为 null 时摊平成 emptyList()，这条覆盖那条输入。
        assertNull(landedViaTreeScan(emptyList(), "hello"))
        assertNull(landedViaTreeScan(listOf(), "hello"))
    }

    @Test
    fun `可编辑类但无文本不得命中`() {
        // text 为 null 时 `?.contains(...) == true` 那半边必须把它判成不命中（写成 !! 会抛，判 null 才对）。
        assertNull(landedViaTreeScan(listOf(edit(null)), "hello"))
        assertEquals(edit("hello"), landedViaTreeScan(listOf(edit(null), edit("hello")), "hello"))
    }

    @Test
    fun `判落要求两条件同时成立 任一单独成立都不算`() {
        // 四格矩阵：EditText+含词=判落，其余三格=不落。少任一条件都会把假阳性放行。
        assertTrue(landedViaTreeScan(listOf(edit("say hello")), "hello") != null)
        assertNull(landedViaTreeScan(listOf(text("say hello")), "hello"))
        assertNull(landedViaTreeScan(listOf(edit("say goodbye")), "hello"))
        assertNull(landedViaTreeScan(listOf(text("say goodbye")), "hello"))
    }
}
