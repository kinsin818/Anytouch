package com.anytouch.app.recorder

import com.anytouch.app.platform.COMPILE_HOLD_HEADLINE
import com.anytouch.contracts.Action
import com.anytouch.contracts.ActionSafety
import com.anytouch.contracts.ActionType
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 「我的任务」判据层的 JVM 锁（S5-e 要求 2 / 三裁②"走同一个现有落账口，不另起存储"）。
 *
 * 锁的三层（与工单 §3 判据 3、4 的 JVM 可证伪那两半一一对应）：
 * 1. **门禁本体**（[savedTaskGateOf]）：空名/超长/空账/重名各自成档，且优先级钉住；
 * 2. **编解码与存储件**（[SavedTaskCodec] / [SavedTaskStore]）：整本往返逐字同构（这是"载入后 V-3
 *    框账比吃得下"的前提），脏形状一律 [ReadOutcome.Corrupt] 且**就地覆写不许发生**；
 * 3. **结构锁**（读主源码文本）：存档这条来路没有第二条落账通道、没有凭据、没有坐标、没有网络。
 *
 * 为什么结构锁读文本而不是真跑：带 `Log` 的接线（`RecorderStore.acceptModelActions`、`MainActivity`）
 * 在 JVM 里起不来（本仓不用 Robolectric），这与 `ModelLedgerGateTest` 文件头是同一条理由。
 * 真跑那一半归设备面（`scripts/s5e-smoke.sh` 的存→冷启→载入→执行那一格）。
 */
class SavedTasksTest {

    private class FakeDisk(initial: String? = null) : SavedTaskDisk {
        var text: String? = initial
        var writable: Boolean = true
        var writeCalls: Int = 0

        override fun read(): String? = text

        override fun write(text: String): Boolean {
            writeCalls++
            if (!writable) return false
            this.text = text
            return true
        }
    }

    private fun act(type: String, id: String = "a"): Action =
        Action(
            actionId = id,
            type = type,
            source = "node",
            value = null,
            safety = ActionSafety(viewportOk = true, clickEnabled = true),
        )

    private val ledger = listOf(act(ActionType.CLICK), act(ActionType.WAIT, "b"))

    private fun task(name: String, size: Int = 2) =
        SavedTask(name, (0 until size).map { act(ActionType.CLICK, "a$it") })

    // ---- 1. 门禁本体 ----

    @Test
    fun `空白名字拒 - 无名条目删不准也认不出`() {
        assertEquals(SavedTaskGate.BLANK_NAME, savedTaskGateOf(emptyList(), "   ", ledger))
        assertEquals(SavedTaskGate.BLANK_NAME, savedTaskGateOf(emptyList(), "", ledger))
    }

    @Test
    fun `名字超长拒 - 上限就是列表行装得下的那一格`() {
        val edge = "n".repeat(MAX_SAVED_TASK_NAME)
        assertNull(savedTaskGateOf(emptyList(), edge, ledger), "恰好上限必须放行：拒在界外一格才是本意")
        assertEquals(SavedTaskGate.NAME_TOO_LONG, savedTaskGateOf(emptyList(), edge + "x", ledger))
    }

    @Test
    fun `空账拒 - 能存就能放，空任务是一枚假绿`() {
        assertEquals(SavedTaskGate.EMPTY_LEDGER, savedTaskGateOf(emptyList(), "clean", emptyList()))
    }

    @Test
    fun `重名拒 - 覆盖等于静默丢单`() {
        val existing = listOf(task("clean"))
        assertEquals(SavedTaskGate.DUPLICATE_NAME, savedTaskGateOf(existing, "clean", ledger))
        assertNull(savedTaskGateOf(existing, "Clean", ledger), "名字区分大小写：Clean 与 clean 是两条，不判重名")
    }

    @Test
    fun `门禁优先级 - 空名先于空账（先说最挡手的那一件）`() {
        assertEquals(SavedTaskGate.BLANK_NAME, savedTaskGateOf(emptyList(), "", emptyList()))
        assertEquals(SavedTaskGate.NAME_TOO_LONG, savedTaskGateOf(emptyList(), "x".repeat(65), emptyList()))
    }

    // ---- 2. 编解码 ----

    @Test
    fun `整本往返 - 顺序名字与步数逐字回来`() {
        val tasks = listOf(task("first", 3), task("second", 1))
        val decoded = SavedTaskCodec.decode(SavedTaskCodec.encode(tasks))
        assertIs<ReadOutcome.Ok>(decoded)
        assertEquals(listOf("first", "second"), decoded.tasks.map { it.name }, "数组顺序即列表顺序")
        assertEquals(listOf(3, 1), decoded.tasks.map { it.actions.size })
    }

    @Test
    fun `存储件里的 actions 数组与任务框那串逐字节同构 - V-3 框账比对的前提`() {
        val ledgerText = encodeActions(ledger)
        assertEquals(
            """[{"name":"t","actions":$ledgerText}]""",
            SavedTaskCodec.encode(listOf(SavedTask("t", ledger))),
            "两处各拼一次 JSON，载入后框与账就会看着一样而字节不同——V-3 当场把合法装载判成陈旧",
        )
    }

    @Test
    fun `解码只认这个形状 - 八种脏形状一律 Corrupt 不猜半本`() {
        val dirty = listOf(
            """{"name":"a","actions":[]}""", // 顶层不是数组
            """["just-a-string"]""", // 条目不是对象
            """[{"actions":[]}]""", // 缺 name
            """[{"name":"   ","actions":[]}]""", // 名字空白
            """[{"name":"a"}]""", // 缺 actions
            """[{"name":"a","actions":[]},{"name":"a","actions":[]}]""", // 同名两条
            """[{"name":"a","actions":[{"type":"click"}]}]""", // 动作少键
            "not json at all", // 根本不是 JSON
        )
        dirty.forEachIndexed { index, text ->
            assertIs<ReadOutcome.Corrupt>(SavedTaskCodec.decode(text), "第 $index 种脏形状被放行了：$text")
        }
    }

    @Test
    fun `空数组是空表不是脏文件 - 首次使用不许报错`() {
        assertIs<ReadOutcome.Ok>(SavedTaskCodec.decode("[]"))
    }

    @Test
    fun `多余键按冻结配置忽略 - 向前兼容但少键绝不兼容`() {
        val decoded = SavedTaskCodec.decode("""[{"name":"a","actions":[],"future_field":{"x":1}}]""")
        assertIs<ReadOutcome.Ok>(decoded)
        assertTrue(decoded.tasks.single().actions.isEmpty())
    }

    @Test
    fun `存储件零坐标 - 编码文本里每一个 target 都是 null`() {
        // 三裁②"零坐标"的可证伪形态：账里若混进过一次坐标，这条锁当场红（不是嘴上说没有）
        val text = SavedTaskCodec.encode(listOf(task("with-target", 2)))
        val occurrences = Regex("\"target\"\\s*:").findAll(text).toList()
        assertTrue(occurrences.isNotEmpty(), "编码里根本没有 target 字段=序列化真值变了，本锁要重新对拍")
        occurrences.forEach { match ->
            val after = text.substring(match.range.last + 1).trimStart()
            assertTrue(after.startsWith("null"), "存档里出现了非空 target：${after.take(40)}")
        }
    }

    // ---- 3. 存储件增删读 ----

    @Test
    fun `save 新条目在最前并落盘`() {
        val disk = FakeDisk(SavedTaskCodec.encode(listOf(task("old"))))
        val store = SavedTaskStore(disk)
        val outcome = store.save("new", ledger)
        assertIs<SaveOutcome.Saved>(outcome)
        assertEquals(2, outcome.total)
        assertEquals(listOf("new", "old"), store.list().map { it.name })
    }

    @Test
    fun `盘脏时 save 停手不覆写 - 覆写等于把用户存过的静默丢掉`() {
        val dirty = """[{"name":"a"}]"""
        val disk = FakeDisk(dirty)
        val store = SavedTaskStore(disk)
        assertIs<SaveOutcome.Rejected>(store.save("new", ledger)).also {
            assertEquals(SavedTaskGate.READ_FAILED, it.gate)
        }
        assertEquals(0, disk.writeCalls, "读不出还照写=覆写脏盘")
        assertEquals(dirty, disk.text, "脏盘必须原样留着等上报")
        assertIs<DeleteOutcome.Rejected>(store.delete("a")).also {
            assertEquals(SavedTaskGate.READ_FAILED, it.gate)
        }
        assertEquals(0, disk.writeCalls)
    }

    @Test
    fun `写不进就报 WRITE_FAILED - 不许把没存上报成存好了`() {
        val disk = FakeDisk()
        disk.writable = false
        val store = SavedTaskStore(disk)
        assertIs<SaveOutcome.Rejected>(store.save("new", ledger)).also {
            assertEquals(SavedTaskGate.WRITE_FAILED, it.gate)
        }
        assertNull(disk.text)
    }

    @Test
    fun `delete 找不到即 NOT_FOUND 且不动盘`() {
        val disk = FakeDisk(SavedTaskCodec.encode(listOf(task("keep"))))
        val store = SavedTaskStore(disk)
        assertIs<DeleteOutcome.Rejected>(store.delete("gone")).also {
            assertEquals(SavedTaskGate.NOT_FOUND, it.gate)
        }
        assertEquals(0, disk.writeCalls)
        assertEquals(listOf("keep"), store.list().map { it.name })
    }

    @Test
    fun `delete 成功后列表与盘双无`() {
        val disk = FakeDisk(SavedTaskCodec.encode(listOf(task("gone"))))
        val store = SavedTaskStore(disk)
        val outcome = store.delete("gone")
        assertIs<DeleteOutcome.Deleted>(outcome)
        assertEquals(0, outcome.remaining)
        assertTrue(store.list().isEmpty())
        assertEquals("[]", disk.text, "盘里必须真的没这一条（只改内存镜像=假删）")
    }

    @Test
    fun `find 只取条目不改盘 - 放行判断一律不在本文件`() {
        val disk = FakeDisk(SavedTaskCodec.encode(listOf(task("pick", 2))))
        val store = SavedTaskStore(disk)
        assertEquals(2, store.find("  pick  ")?.actions?.size, "名字两端空白不是第二条名字")
        assertNull(store.find("other"))
        assertEquals(0, disk.writeCalls)
    }

    // ---- 4. 红字过期边（三档纯状态档 + 请求绑定档） ----

    private fun expiry(
        current: SavedTaskGate?,
        outcome: ReadOutcome,
        running: Boolean = false,
        compileBusy: Boolean = false,
    ): SavedTaskGate? = savedRejectionAfterChange(current, outcome, running, compileBusy)

    @Test
    fun `盘读不出盖过一切陈旧话术`() {
        assertEquals(
            SavedTaskGate.READ_FAILED,
            expiry(SavedTaskGate.NOT_FOUND, ReadOutcome.Corrupt("x")),
        )
    }

    @Test
    fun `READ_FAILED 在盘恢复可读的下一格作废 - 留着就是假红`() {
        assertNull(expiry(SavedTaskGate.READ_FAILED, ReadOutcome.Empty))
        assertNull(expiry(SavedTaskGate.READ_FAILED, ReadOutcome.Ok(listOf(task("a")))))
    }

    @Test
    fun `COMPILING 只在编译还在路上时留着`() {
        assertEquals(SavedTaskGate.COMPILING, expiry(SavedTaskGate.COMPILING, ReadOutcome.Empty, compileBusy = true))
        assertNull(expiry(SavedTaskGate.COMPILING, ReadOutcome.Empty, compileBusy = false))
    }

    @Test
    fun `RUNNING 只在确实有任务在跑时留着`() {
        assertEquals(SavedTaskGate.RUNNING, expiry(SavedTaskGate.RUNNING, ReadOutcome.Empty, running = true))
        assertNull(expiry(SavedTaskGate.RUNNING, ReadOutcome.Empty, running = false))
    }

    @Test
    fun `请求绑定的拒因不随状态跃迁抹掉 - 那一次点击没成是事实`() {
        val requestBound = listOf(
            SavedTaskGate.BLANK_NAME,
            SavedTaskGate.DUPLICATE_NAME,
            SavedTaskGate.NAME_TOO_LONG,
            SavedTaskGate.EMPTY_LEDGER,
            SavedTaskGate.WRITE_FAILED,
            SavedTaskGate.NOT_FOUND,
        )
        requestBound.forEach { gate ->
            assertEquals(gate, expiry(gate, ReadOutcome.Empty), "$gate 说的是刚才那一次点击，状态归位不改事实")
        }
    }

    // ---- 5. 话术单源（L2-③ 错误必显示 + 军令 4 全英文） ----

    @Test
    fun `每一档都有独立非空话术且全英文`() {
        val copies = SavedTaskGate.entries.associateWith { it.userCopy() }
        copies.forEach { (gate, copy) ->
            assertTrue(copy.isNotBlank(), "$gate 没有话术：静默=黑洞")
            assertTrue(
                copy.none { it.code in 0x4E00..0x9FFF },
                "$gate 的上屏话术含中文（军令 4）：$copy",
            )
        }
        assertEquals(copies.values.size, copies.values.distinct().size, "两档共用一句：用户分不清是哪件事被拒")
    }

    @Test
    fun `编译档头一句与四个既有入口同源，RUNNING 档与模板来路同源 - 判据话术不许抄第二份`() {
        assertTrue(SavedTaskGate.COMPILING.userCopy().startsWith(COMPILE_HOLD_HEADLINE))
        assertTrue(SavedTaskGate.RUNNING.userCopy().startsWith(ledgerRunningCopy("saved task")))
        // 主语换掉就是另一句话：模板那一条来路只共用"为什么暂停"，不共用"哪一条被拒"
        assertFalse(SavedTaskGate.RUNNING.userCopy().contains("template"))
    }

    // ---- 6. 结构锁：没有第二条通道、没有凭据、没有网络 ----

    private val mainRoot = File("src/main/kotlin/com/anytouch/app")

    private fun src(rel: String): String = File(mainRoot, rel).readText()

    @Test
    fun `存档这条来路只经唯一落账口 - 接线里不许出现第二处写账`() {
        val main = src("MainActivity.kt")
        val block = main.substringAfter("「我的任务」三件操作").substringBefore("private fun submitTask(")
        assertTrue(block.length > 1_000, "锚点段落没抓到：文件结构变了，本锁要重新对拍")
        assertEquals(1, Regex("acceptModelActions\\(").findAll(block).count(), "载入这条来路开了第二个落账口")
        assertEquals(1, Regex("""origin = "saved"""").findAll(main).count())
        assertFalse(block.contains("publishSuggestion("), "接线自己发布建议=绕过落账口写屏")
        assertFalse(block.contains("AppState.submit("), "接线自己派发=绕过 submitTask 那一条通道")
        assertFalse(block.contains("taskRequests"), "直连任务总线=第三条通道")
        assertFalse(block.contains("compiledActions.value ="), "直接改账=第二条写口")
    }

    /** 只留代码行：文件头的中文注释本来就要写明"禁了什么"，扫注释会自己咬自己。 */
    private fun codeOnly(text: String): String = text.lines()
        .filterNot { it.trim().startsWith("*") || it.trim().startsWith("//") || it.trim().startsWith("/*") }
        .joinToString("\n")

    @Test
    fun `存档判据层与磁盘件零凭据零外部存储且 import 全在白名单内`() {
        val files = mapOf(
            "recorder/SavedTasks.kt" to src("recorder/SavedTasks.kt"),
            "platform/AndroidSavedTaskDisk.kt" to src("platform/AndroidSavedTaskDisk.kt"),
        )
        // 零网络**不在这里再抄一份禁词**：那条判据的唯一真源是 `scripts/ci-local.sh` 红线 F
        // （全仓 grep 网络符号，白名单只有 byok/ 与 tools/）。本用例补的是红线 F 管不到的那一半。
        val banned = listOf(
            "getSharedPreferences", "getExternalFilesDir", "getExternalStorageDirectory", "externalCacheDir",
            "CredentialRepository", "AndroidKeyVault", "MediaStore", "ContentResolver", "WebView",
        )
        val allowedImports = listOf(
            "android.content.Context", "com.anytouch.app.", "com.anytouch.contracts.",
            "kotlin.", "kotlinx.serialization.", "java.io.File", "java.util.",
        )
        files.forEach { (path, raw) ->
            val text = codeOnly(raw)
            banned.forEach { marker ->
                assertFalse(text.contains(marker), "$path 的代码行里出现了 $marker：零凭据/只落 filesDir 破口")
            }
            Regex("^import (.+)$", RegexOption.MULTILINE).findAll(raw).forEach { match ->
                val imported = match.groupValues[1].trim()
                assertTrue(
                    allowedImports.any { imported.startsWith(it) || imported.removeSuffix(".*").startsWith(it) },
                    "$path 引入了白名单外的依赖：$imported（存档面能碰的东西就此一格封死）",
                )
            }
        }
        assertTrue(files.getValue("platform/AndroidSavedTaskDisk.kt").contains("filesDir"),
            "磁盘件不落 filesDir 就是往红线 I 上撞")
    }

    @Test
    fun `注入通道与按钮同一入口 - 四枚 extra 各自转调那四个函数且不自带门禁`() {
        val main = src("MainActivity.kt")
        val q = "\"" // 引号本身要参与比对：拼错一处就是断言失明
        val calls = listOf(
            "task_save" to "saveCurrentTask(name, " + q + "adb_inject" + q + ")",
            "saved_load" to "loadSavedTask(name, " + q + "adb_inject" + q + ")",
            "saved_run" to "runSavedTask(",
            "saved_delete" to "deleteSavedTask(name, " + q + "adb_inject" + q + ")",
        )
        calls.forEach { (key, call) ->
            assertTrue(main.contains("\"$key\""),
                "注入键 $key 没在 companion 里钉名（脚本按字面量下发，改名等于断言失明）")
            assertTrue(main.contains(call), "$key 没有转调 " + call + "：通道自己判了一套门禁")
        }
        // 通道区段：四枚 extra 各自转调那一个入口，且区段内一律不出现门禁判断（门禁只在被转调处）
        val injected = main.substringAfter("EXTRA_TASK_SAVE)?.let").substringBefore("EXTRA_TASK_JSON")
        listOf(
            "saveCurrentTask(name, ",
            "loadSavedTask(name, ",
            "runSavedTask(",
            "deleteSavedTask(name, ",
        ).forEach { call ->
            assertTrue(injected.contains(call), "注入区段里没有 $call：那枚 extra 走的不是界面同一条入口")
        }
        listOf("compileBusy", "acceptModelActions", "savedTaskGateOf", "taskAdmission").forEach { marker ->
            assertFalse(injected.contains(marker), "注入通道里出现了 $marker：同一判据被抄成第二份（雷 18）")
        }
    }
}
