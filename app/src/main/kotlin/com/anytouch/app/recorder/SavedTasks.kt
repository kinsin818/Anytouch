package com.anytouch.app.recorder

import com.anytouch.app.activation.ProFeature
import com.anytouch.app.activation.ProGate
import com.anytouch.app.activation.userCopy
import com.anytouch.app.platform.COMPILE_HOLD_HEADLINE
import com.anytouch.contracts.Action
import com.anytouch.contracts.ContractJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 「我的任务」的**判据层**（S5-R12 要求 2 + 三裁①复确认"走同一个现有落账口，不另起存储"）。
 *
 * 本文件 android-free：不读文件、不碰 Context。磁盘只经 [SavedTaskDisk] 这一个窄口注入，
 * Android 侧适配器在 `platform/AndroidSavedTaskDisk.kt`（filesDir，与凭据件分目录分文件）。
 *
 * 三件刻意的事：
 * 1. **存的只是步序账本体 + 名字**。凭据、坐标、屏幕上下文一概不落盘：动作序列化沿用 [encodeActions]
 *    那唯一真值（`Action` 里根本没有坐标定位通道，`target` 不填不读——见 RULINGS S5-R12 裁 ①）。
 * 2. **载入不开第二条通道**。本文件只把条目交回调用方，进账仍必须走
 *    `RecorderStore.acceptModelActions(origin="saved")`，派发仍必须走 `submitTask`：
 *    RUNNING 档／词表档／V-3 步序账比对／TTL 一条都不会因为"这是存档"而绕开。
 * 3. **脏文件不猜**。整本解不开就是解不开（返回 [ReadOutcome.Corrupt]），既不半本放行也不就地覆写——
 *    覆写等于把用户存过的任务静默丢掉，那是比假绿更难看的一种。
 */

/** 一条已存任务：名字 + 步序账（与录制/AI 编译产物同结构，回放逻辑逐字相同）。 */
data class SavedTask(val name: String, val actions: List<Action>)

/** 窄口：读写那一个文件。JVM 用例用内存实现，Android 侧实现见 platform 适配器。 */
interface SavedTaskDisk {
    /** null = 还没有存过任何东西（文件不存在）。 */
    fun read(): String?

    /** 返回 false = 没写成或写完后读回不是这些字节（不信 IO 层的沉默，同凭据件那条纪律）。 */
    fun write(text: String): Boolean
}

/** 名字上限：屏上一行装得下，且一条名字不许把存储件撑爆。越界按 [SavedTaskGate.NAME_TOO_LONG] 拒。 */
const val MAX_SAVED_TASK_NAME = 64

/** 存档操作被拒的档（每档一条独立话术，见 [SavedTaskGate.userCopy]）。 */
enum class SavedTaskGate {
    /** 名字空白：无名条目在列表里既认不出也删不准（删错=丢任务）。 */
    BLANK_NAME,

    /** 重名：两条同名条目让"点哪条删哪条"变成猜谜，覆盖旧条目更是静默丢单。 */
    DUPLICATE_NAME,

    NAME_TOO_LONG,

    /** 空账不配存成任务（与编辑面 EMPTY_LEDGER 同一条禁律：空任务一旦可存就可放，假绿形态）。 */
    EMPTY_LEDGER,

    /** 存储件读不出：文件被改坏或写了一半。 */
    READ_FAILED,

    /** 存储件写不进/写完读回不符：不许把"没存上"报成"存好了"。 */
    WRITE_FAILED,

    /** 按名字找不到的删除/载入请求：列表已变而句柄仍指旧条目（与编辑面 OUT_OF_RANGE 同族）。 */
    NOT_FOUND,

    /**
     * AI 编译那一跑还在路上（裁决 S31-B2 的第四个入口之外的**第五个入口**：换账本的不止改账与执行，
     * 载入一条存档也是整本换账）。这一档与 `RecordGate.COMPILING` 同族——**纯状态档**，
     * 编译一归位就失去依据，所以它与 READ_FAILED、[SavedTaskGate.RUNNING] 一起被列为 [savedRejectionAfterChange]
     * 里要作废的那几类（共同点：说的都是"此刻的状态"，不是"你刚才那一次点击"）。
     * 话术的头一句与四个既有入口同源（[COMPILE_HOLD_HEADLINE]），尾巴才补本入口特有的那半句。
     */
    COMPILING,

    /**
     * 有任务正在跑（落账口 [com.anytouch.app.recorder.session.RecorderStore.acceptModelActions] 的
     * RUNNING 档转述）。与 [COMPILING] 同族——**纯状态档**：跑完这句话就失去依据，留在屏上即假红。
     * 判据**不在本文件复制**（那会成第二份真值），这里只存"这一格红字当初判的是哪一档"，
     * 话术与模板来路共用 [ledgerRunningCopy] 那一份。
     */
    RUNNING,

    /**
     * 「我的任务」是进阶功能而本机还没激活（S5-f 军令 §3 三枚进墙功能之一）。
     * 与 [COMPILING]、[RUNNING] 同族——**纯状态档**：激活一成功这句话就失去依据，挂着就是假红。
     * 话术不在本文件复制，转调 `activation/ActivationGate.kt` 那一份单源（三处入口共用一句）。
     */
    NOT_ACTIVATED,
}

/** 存一条的结果。[Rejected] 不带任何写副作用。 */
sealed class SaveOutcome {
    data class Saved(val task: SavedTask, val total: Int) : SaveOutcome()
    data class Rejected(val gate: SavedTaskGate, val name: String) : SaveOutcome()
}

/** 删一条的结果。 */
sealed class DeleteOutcome {
    data class Deleted(val name: String, val remaining: Int) : DeleteOutcome()
    data class Rejected(val gate: SavedTaskGate, val name: String) : DeleteOutcome()
}

/** 读整本的结果：[Corrupt] 与 [Empty] 必须分档——前者要上屏报错，后者是正常初始态。 */
sealed class ReadOutcome {
    data class Ok(val tasks: List<SavedTask>) : ReadOutcome()
    data object Empty : ReadOutcome()
    data class Corrupt(val detail: String) : ReadOutcome()
}

/**
 * 存入门禁（纯函数）：只判"这条该不该进账本"，不判状态机互斥——
 * RUNNING/COMPILING 那两档属于**载入**（会换步序账）那一侧，由既有落账口与入口门禁把关，
 * 这里重复判一次就是第二份真值（同 [firstUnsupportedModelAction] 只判词表、不判状态的分工）。
 */
fun savedTaskGateOf(existing: List<SavedTask>, name: String, actions: List<Action>): SavedTaskGate? = when {
    name.isBlank() -> SavedTaskGate.BLANK_NAME
    name.length > MAX_SAVED_TASK_NAME -> SavedTaskGate.NAME_TOO_LONG
    actions.isEmpty() -> SavedTaskGate.EMPTY_LEDGER
    existing.any { it.name == name } -> SavedTaskGate.DUPLICATE_NAME
    else -> null
}

/** 话术单源（L2-③ 错误必显示）。UI 与 adb 注入两条通道都从这里取，不许各抄一句。 */
fun SavedTaskGate.userCopy(): String = when (this) {
    SavedTaskGate.BLANK_NAME ->
        "Give the task a name first: an unnamed entry cannot be told apart in the list, and deleting the " +
            "wrong one would silently lose a task."
    SavedTaskGate.DUPLICATE_NAME ->
        "You already saved a task with this exact name. Saving would overwrite it and the old one would be " +
            "gone without a trace — pick a different name, or delete the saved one first."
    SavedTaskGate.NAME_TOO_LONG ->
        "That name is too long (up to $MAX_SAVED_TASK_NAME characters fits on the list row). Shorten it and " +
            "save again."
    SavedTaskGate.EMPTY_LEDGER ->
        "There are no steps to save: record some steps (or load a template) first — an empty task would be " +
            "runnable and would report success without doing anything."
    SavedTaskGate.READ_FAILED ->
        "The saved-tasks file could not be read, so nothing was written: overwriting an unreadable ledger " +
            "would throw away tasks you saved earlier. The file stays untouched — report this with the build."
    SavedTaskGate.WRITE_FAILED ->
        "The task was not saved: the device refused the write (or it did not read back the same bytes). " +
            "Nothing was added to your list — try again, and if it keeps failing check the device's free space."
    SavedTaskGate.NOT_FOUND ->
        "That saved task is no longer in the list (it was probably just deleted): pick it again from the " +
            "current list instead of acting on a stale entry."
    // 头一句与开录/停止并编译/执行任务/步骤编辑四个入口共用同一格常量（裁 S31-B2 的话术硬要求），
    // 只有尾巴按本入口改写——载入一条存档同样是**整本换账**，所以它和那四件事一起暂停。
    SavedTaskGate.COMPILING ->
        "$COMPILE_HOLD_HEADLINE — tapping “Load” or “Run” on a saved task replaces the whole step ledger too, " +
            "so it stays paused with the others. Please wait until the compile verdict is on screen " +
            "(both success and failure speak up)."
    // 与「模板装载」那条来路共用 [ledgerRunningCopy] 那一份文字（主语不同而已）：同一句判据抄两处=雷 18。
    SavedTaskGate.RUNNING -> ledgerRunningCopy("saved task")
    // 付费墙那句不在此抄一份：三处入口共用 activation 层那一份单源
    SavedTaskGate.NOT_ACTIVATED -> ProGate.NOT_ACTIVATED.userCopy(ProFeature.SAVED_TASKS)
}

/**
 * 存储件编解码（纯函数）。**整本一次读写**：条目数是个位数到几十的量级，逐条追加只会多出
 * "半本写成功"的破碎态（那正是 [SavedTaskGate.READ_FAILED] 要挡的事）。
 *
 * 文件形态：`[{"name":"…","actions":[ … ]}, …]`，其中 `actions` 就是 [encodeActions] 那一份数组
 * （经 [actionsToJsonElement] / [actionsFromJsonElement] 取，本文件不另立 serializer）。
 * 数组顺序即列表顺序（新条目在最前）。
 *
 * 解码**只认这个形状**：顶层不是数组、条目不是对象、缺 name／缺 actions、名字空白、同名两条，
 * 一律 [ReadOutcome.Corrupt]——`ignoreUnknownKeys` 是冻结配置（多余键忽略），但"少键"绝不能当没事。
 * 猜半本放行等于把残缺账本送去执行；就地覆写等于把用户存过的任务静默丢掉。两者都比报错难看。
 */
object SavedTaskCodec {

    const val NAME_KEY = "name"
    const val ACTIONS_KEY = "actions"

    fun encode(tasks: List<SavedTask>): String =
        JsonArray(tasks.map { task ->
            JsonObject(
                mapOf(
                    NAME_KEY to JsonPrimitive(task.name),
                    ACTIONS_KEY to actionsToJsonElement(task.actions),
                ),
            )
        }).toString()

    fun decode(text: String): ReadOutcome {
        val root = try {
            ContractJson.instance.parseToJsonElement(text)
        } catch (e: Exception) {
            return ReadOutcome.Corrupt("${e.javaClass.simpleName}: ${e.message?.take(160)}")
        }
        if (root !is JsonArray) return ReadOutcome.Corrupt("顶层不是数组（实际是 ${root.javaClass.simpleName}）")
        val tasks = ArrayList<SavedTask>(root.size)
        root.forEachIndexed { index, entry ->
            if (entry !is JsonObject) return ReadOutcome.Corrupt("条目不是对象（index=$index）")
            val name = (entry[NAME_KEY] as? JsonPrimitive)
                ?.takeIf { it.isString }
                ?.content
                ?: return ReadOutcome.Corrupt("条目缺 $NAME_KEY（index=$index）")
            if (name.isBlank()) return ReadOutcome.Corrupt("条目名字为空白（index=$index）")
            val actions = entry[ACTIONS_KEY] ?: return ReadOutcome.Corrupt("条目缺 $ACTIONS_KEY（name=$name）")
            // 动作数组本身解不开（缺 safety、type 不认识…）一并归到"整本读不出"：不猜半本
            val parsed = try {
                actionsFromJsonElement(actions)
            } catch (e: Exception) {
                return ReadOutcome.Corrupt("name=$name actions 解不开: ${e.javaClass.simpleName}")
            }
            tasks += SavedTask(name, parsed)
        }
        val dupes = tasks.groupBy { it.name }.filterValues { it.size > 1 }.keys
        if (dupes.isNotEmpty()) return ReadOutcome.Corrupt("同名条目 ${dupes.first()}（载入与删除会指向错的那条）")
        return ReadOutcome.Ok(tasks)
    }
}

/**
 * 存档本体的增删读（窄口 IO + 上面的纯判据）。
 * 每次操作都重新读盘再写：进程内不缓存第二份列表——缓存就是"屏上的列表"之外的第二个真值源
 * （冷启动、另一个通道刚写过文件，都会让缓存说谎；条目数量级也不需要它）。
 */
class SavedTaskStore(private val disk: SavedTaskDisk) {

    fun read(): ReadOutcome {
        val text = disk.read() ?: return ReadOutcome.Empty
        return SavedTaskCodec.decode(text)
    }

    /** 当前列表；读不出即空表（调用方要显示红字时读 [read] 拿档位，这里不替它决定）。 */
    fun list(): List<SavedTask> = (read() as? ReadOutcome.Ok)?.tasks ?: emptyList()

    fun save(name: String, actions: List<Action>): SaveOutcome {
        val trimmed = name.trim()
        return when (val current = read()) {
            is ReadOutcome.Corrupt -> SaveOutcome.Rejected(SavedTaskGate.READ_FAILED, trimmed)
            else -> {
                val existing = current.tasksOrNull()
                savedTaskGateOf(existing, trimmed, actions)?.let {
                    return SaveOutcome.Rejected(it, trimmed)
                }
                val task = SavedTask(trimmed, actions)
                // 新条目排在最前：刚存的就在眼前，用户不必滚到底找
                if (!disk.write(SavedTaskCodec.encode(listOf(task) + existing))) {
                    SaveOutcome.Rejected(SavedTaskGate.WRITE_FAILED, trimmed)
                } else {
                    SaveOutcome.Saved(task, existing.size + 1)
                }
            }
        }
    }

    fun delete(name: String): DeleteOutcome {
        val trimmed = name.trim()
        return when (val current = read()) {
            is ReadOutcome.Corrupt -> DeleteOutcome.Rejected(SavedTaskGate.READ_FAILED, trimmed)
            else -> {
                val existing = current.tasksOrNull()
                if (existing.none { it.name == trimmed }) {
                    DeleteOutcome.Rejected(SavedTaskGate.NOT_FOUND, trimmed)
                } else if (!disk.write(SavedTaskCodec.encode(existing.filterNot { it.name == trimmed }))) {
                    DeleteOutcome.Rejected(SavedTaskGate.WRITE_FAILED, trimmed)
                } else {
                    DeleteOutcome.Deleted(trimmed, existing.size - 1)
                }
            }
        }
    }

    /** 按名字取条目（不做任何放行判断——进账走 [com.anytouch.app.recorder.session.RecorderStore.acceptModelActions]）。 */
    fun find(name: String): SavedTask? = list().firstOrNull { it.name == name.trim() }
}

private fun ReadOutcome.tasksOrNull(): List<SavedTask> = when (this) {
    is ReadOutcome.Ok -> tasks
    ReadOutcome.Empty -> emptyList()
    is ReadOutcome.Corrupt -> emptyList()
}

/**
 * 存档红字的过期边（与 `AccessibilityGate` / `StepEditing` 那三条 `*AfterStateChange` 同一条纪律，
 * 判据住纯函数、接线只转调）。四条规则、一个函数，共同点：这四档说的都是"**此刻**的状态"，
 * 状态一归位，这句话就成了假红（V-2 那颗雷的形态：门禁说可以、话术说不行）。
 * - [SavedTaskGate.READ_FAILED]：盘恢复可读之后仍在红 = 谎报；反过来，读不出这件事此刻最要紧，
 *   它必须盖过任何陈旧话术（所以排第一，不看 `current` 是谁）；
 * - [SavedTaskGate.COMPILING]：编译一归位即作废；
 * - [SavedTaskGate.RUNNING]：这一跑一结束即作废（作废的是那句"现在有人在跑"，不是那条存档）；
 * - [SavedTaskGate.NOT_ACTIVATED]：激活成功那一瞬即作废（S5-f 军令 §3；那句 "Upgrade to Pro" 说的是
 *   "这台机器还没买"，买完了还挂着就是拿陈旧话术骗已经付了钱的人）。
 *
 * 其余各档（空名／重名／太长／空账／找不到／写不进）都是**请求绑定**的：说的是"你刚才那一次点击没成"，
 * 状态跃迁（执行归位、编译回来）不会让它失去依据，所以原样保留、由下一次操作覆盖，不许悄悄抹掉。
 * 这里刻意只列这四档而不逐档列白名单：多写一档等于把"哪些算状态档"交给接线侧自由发挥。
 */
fun savedRejectionAfterChange(
    current: SavedTaskGate?,
    outcome: ReadOutcome,
    running: Boolean,
    compileBusy: Boolean,
    activated: Boolean,
): SavedTaskGate? = when {
    outcome is ReadOutcome.Corrupt -> SavedTaskGate.READ_FAILED
    current == SavedTaskGate.READ_FAILED -> null
    current == SavedTaskGate.COMPILING && !compileBusy -> null
    current == SavedTaskGate.RUNNING && !running -> null
    current == SavedTaskGate.NOT_ACTIVATED && activated -> null
    else -> current
}
