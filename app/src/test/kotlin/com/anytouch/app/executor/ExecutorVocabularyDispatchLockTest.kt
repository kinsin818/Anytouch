package com.anytouch.app.executor

import com.anytouch.app.locator.TestUi
import com.anytouch.app.locator.UiNode
import com.anytouch.app.locator.ui
import com.anytouch.app.platform.NodeActions
import com.anytouch.app.safety.KillSwitch
import com.anytouch.byok.executorSupportedActionTypes
import com.anytouch.byok.supportsExecutorDispatch
import com.anytouch.contracts.Action
import com.anytouch.contracts.ActionSafety
import com.anytouch.contracts.ActionType
import com.anytouch.contracts.ContractJson
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject

/**
 * 词表真源 ⇄ 执行器分派的**双向可执行对拍**（S3-F/F2-2，裁决 S31-B3）。
 *
 * 为什么必须有这条：`:byok` 的词表真源（`byok/.../ExecutorVocabulary.kt`）由四条 `ActionType` 常量组成，
 * 而"执行器跑得动哪些 type"的**行为定义**住在 `NodeTaskRunner.run()` 的那个 `when` 里。
 * 两边隔着红线 G（`executor/` 禁止 import 编译模块，见 `scripts/ci-local.sh` 红线 G），
 * 谁都不能引用谁——于是"真源说跑得动、执行器其实跑不动"（或反过来）在编译期根本看不出来。
 * 本用例把两个方向都真跑一遍（不是读文本比对）：
 * - **真源加一条而 `when` 没改** → `when` 落 `else` 报 unsupported_type，对拍红；
 * - **`when` 加一条分支而真源没改** → 该 type 不再报 unsupported_type，对拍同样红。
 * 候选集是反射取 `ActionType` 里**全部**字符串常量（新增契约常量自动进队，本用例不抄第二份清单），
 * 外加两个"契约里根本没有"的字面量，覆盖 `supportsExecutorDispatch` 对未知串的分支。
 *
 * 本文件住 `app/src/test/`（红线 G 只 grep `app/src/main/kotlin/...` 下那六个执行路径目录）才允许
 * import `:byok`；主源码 `executor/` 若被谁"顺手"补上一句 import，红线 G 会红——那是另一道锁，
 * 不是本用例的职责（本用例锁语义，脚本锁结构）。
 */
class ExecutorVocabularyDispatchLockTest {

    /** 假设备：只记流水，不碰任何真实句柄。派发一律回 true（词表对拍只关心"进没进派发分支"）。 */
    private class RecordingDevice(private val root: UiNode?) : NodeActions {
        val performed = mutableListOf<String>()
        override suspend fun root(): UiNode? = root
        override fun click(node: UiNode): Boolean {
            performed += "click"
            return true
        }

        override fun scroll(node: UiNode, forward: Boolean): Boolean {
            performed += "scroll"
            return true
        }

        override fun setText(node: UiNode, text: String): Boolean {
            performed += "setText"
            (node as? TestUi)?.text = text
            return true
        }

        override fun textOf(node: UiNode): String? = node.text
    }

    private fun tree(): UiNode = ui(
        clazz = "android.widget.FrameLayout",
        children = listOf(
            ui(id = "android:id/list", clazz = "android.widget.ListView", scrollable = true),
            ui(marker = "System", clazz = "android.widget.TextView", clickable = true),
            ui(marker = "", clazz = "android.widget.EditText"),
        ),
    )

    private fun runner(device: NodeActions) = NodeTaskRunner(
        device = device,
        locateTimeoutMs = 0,
        locatePollMs = 10,
        settleMs = 0,
        landedTimeoutMs = 0,
        confirmTimeoutMs = 200,
    )

    /** 线索/参数一次给满：任何一步只要能进派发分支，就不会因为"缺线索/定位不到"另案归因。 */
    private fun probeAction(type: String): Action = Action(
        actionId = "probe-$type",
        type = type,
        source = "node",
        value = ContractJson.instance.parseToJsonElement(
            """{"resource_id":"android:id/list","text":"System","content_desc":"desc","path":"root/0",""" +
                """"input":"hi","direction":"forward","ms":1,"key":"back"}""",
        ) as JsonObject,
        safety = ActionSafety(viewportOk = true, clickEnabled = true),
    )

    /** 真跑一遍：返回"执行器把这一 type 判成 unsupported_type"是否为真。 */
    private fun executorCallsItUnsupported(type: String): Boolean = runBlocking {
        val result = runner(RecordingDevice(tree())).run(listOf(probeAction(type))).results.single()
        result.recovery?.message?.contains("unsupported action type") == true
    }

    /** `ActionType` 里声明的全部字符串常量（新增常量自动进队，本用例不抄第二份清单）。 */
    private fun declaredActionTypes(): Set<String> =
        ActionType::class.java.fields
            .filter { it.type == String::class.java && !it.isSynthetic }
            .map { it.get(null) as String }
            .toSet()

    @BeforeTest
    fun resetKill() {
        KillSwitch.reset()
    }

    @AfterTest
    fun clearKill() {
        KillSwitch.reset()
    }

    @Test
    fun `候选集非空且含四个基本类型（对拍不许在空集合上恒真通过）`() {
        val declared = declaredActionTypes()
        assertTrue(declared.isNotEmpty(), "反射取不到 ActionType 常量＝下面几条对拍全在空集合上跑＝假锁")
        assertTrue(
            declared.containsAll(setOf(ActionType.WAIT, ActionType.CLICK, ActionType.SCROLL, ActionType.TYPE_TEXT)),
            "契约常量取少了（$declared）＝候选集不完整＝只对了半边",
        )
    }

    @Test
    fun `两个方向的集合相等：when 判 unsupported 的集合 == 真源之外的集合`() {
        val declared = declaredActionTypes()
        val unsupportedByExecutor = declared.filter { executorCallsItUnsupported(it) }.toSet()
        val outsideSource = declared - executorSupportedActionTypes
        assertEquals(
            outsideSource,
            unsupportedByExecutor,
            "词表真源与执行器 when 脱节：真源说跑不动=$outsideSource，" +
                "执行器实际说跑不动=$unsupportedByExecutor" +
                "（前者多=when 里有真源未声明的分支；后者多=真源声明了而 when 落 else）",
        )
    }

    @Test
    fun `真源声明跑得动的每一条都真进了派发分支`() {
        for (type in executorSupportedActionTypes) {
            assertFalse(
                executorCallsItUnsupported(type),
                "真源说 type=$type 跑得动，执行器却落 else（unsupported_type）＝落账放了跑不动的账",
            )
            assertTrue(supportsExecutorDispatch(type), "真源自相矛盾：集合里有 $type 而 supportsExecutorDispatch 说没有")
        }
    }

    @Test
    fun `真源之外声明的每一条都被执行器挡下`() {
        val outside = declaredActionTypes() - executorSupportedActionTypes
        assertTrue(outside.isNotEmpty(), "真源若覆盖全部契约常量＝这条负向锁永不为红，先确认候选集非空")
        for (type in outside) {
            assertTrue(executorCallsItUnsupported(type), "真源未声明 type=$type，执行器却真去派发了（越权执行＝假绿）")
            assertFalse(supportsExecutorDispatch(type), "词表真源与自身判据不一致：$type")
        }
    }

    @Test
    fun `契约里根本没有的字面量两侧同判拒`() {
        for (type in listOf("teleport", "click ")) {
            assertFalse(
                supportsExecutorDispatch(type),
                "未知串 \"$type\" 不该被认成支持集成员（宽松匹配＝假门禁）",
            )
            assertTrue(executorCallsItUnsupported(type), "未知串 \"$type\" 竟被执行器派发（when 之外还有兜底）")
        }
    }

    @Test
    fun `四条支持类型各自真的动作都发得出去`() = runBlocking {
        val device = RecordingDevice(tree())
        val report = runner(device).run(
            listOf(
                probeAction(ActionType.CLICK),
                probeAction(ActionType.SCROLL),
                probeAction(ActionType.TYPE_TEXT),
                probeAction(ActionType.WAIT),
            ),
        )
        assertFalse(report.stopped, "四条支持类型组成的账应当整队跑通：${report.results.map { it.recovery }}")
        assertEquals(4, report.results.size)
        assertTrue(report.results.all { it.ok }, report.results.map { it.recovery?.message }.toString())
        assertEquals(
            listOf("click", "scroll", "setText"),
            device.performed,
            "wait 不派发设备动作，其余三条必须各派一次",
        )
    }

    @Test
    fun `真源四条与契约常量的对应关系不许漂进空集`() {
        // 反向再兜一道：词表若被谁改成空集或漏一条，落账口与编译侧会一起失去判据（整本放行=假绿）。
        assertEquals(4, executorSupportedActionTypes.size, "授权词表条数变了：$executorSupportedActionTypes")
        assertEquals(
            setOf(ActionType.WAIT, ActionType.CLICK, ActionType.SCROLL, ActionType.TYPE_TEXT),
            executorSupportedActionTypes,
        )
    }
}
