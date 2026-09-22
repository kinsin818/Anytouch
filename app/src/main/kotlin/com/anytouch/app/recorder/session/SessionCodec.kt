package com.anytouch.app.recorder.session

import com.anytouch.app.recorder.NodeAction
import com.anytouch.app.recorder.NodeActionKind
import com.anytouch.app.recorder.NodeSnapshot
import com.anytouch.app.recorder.RecEvent
import com.anytouch.app.recorder.WindowChanged
import com.anytouch.contracts.ContractJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 存档快照：serialize 的中间表示（字段即军令 STAGE-22 要求：targetPkg + 状态 + 事件序列 + 计数）。 */
data class SessionSnapshot(
    val targetPkg: String,
    val state: SessionState,
    val events: List<RecEvent>,
    val overflowCount: Int,
    val rejectedEvents: List<RecEvent>,
)

/**
 * 会话存档编解码（手工 JsonObject，做法参照冻结的 RecorderJson/RecorderCompiler 的 value 编码，
 * 不复用不修改它们）。与"转换产物只忽略未知键"不同，读档口径是**全有或全无**：
 * 会话保真是状态机契约的一部分，任一事件损坏即整档 Failed，绝不部分受理（fail-closed）。
 */
internal object SessionCodec {
    const val FORMAT = "anytouch.recorder.session"
    const val VERSION = 1

    fun encode(snapshot: SessionSnapshot): String {
        val root = buildJsonObject {
            put(KEY_FORMAT, FORMAT)
            put(KEY_VERSION, VERSION)
            put(KEY_TARGET_PKG, snapshot.targetPkg)
            put(KEY_STATE, snapshot.state.name)
            put(KEY_OVERFLOW_COUNT, snapshot.overflowCount)
            put(KEY_EVENTS, encodeEvents(snapshot.events))
            put(KEY_REJECTED_EVENTS, encodeEvents(snapshot.rejectedEvents))
        }
        return ContractJson.instance.encodeToString(JsonObject.serializer(), root)
    }

    /** 只负责结构解析（JSON 语法层）；语义校验在 [decode]，两层异常都由调用方兜。 */
    fun parse(json: String): JsonElement = ContractJson.instance.parseToJsonElement(json)

    fun decode(root: JsonElement, clock: () -> Long): SessionRestore {
        val fail: (String) -> SessionRestore.Failed = { detail ->
            SessionRestore.Failed(RejectionReason.CORRUPT_ARCHIVE, detail)
        }
        val obj = root as? JsonObject ?: return fail("顶层不是 JSON 对象: ${root::class.simpleName}")
        if (str(obj, KEY_FORMAT) != FORMAT) return fail("格式标识缺失或不为 $FORMAT")
        val version = intOrNull(obj, KEY_VERSION) ?: return fail("version 缺失或非整数")
        if (version != VERSION) return fail("不支持的存档版本 $version（本 reader 只认 $VERSION）")
        val targetPkg = str(obj, KEY_TARGET_PKG)?.takeIf { it.isNotEmpty() }
            ?: return fail("targetPkg 缺失或为空")
        val stateName = str(obj, KEY_STATE) ?: return fail("state 缺失")
        if (SessionState.values().none { it.name == stateName }) return fail("state 非法: $stateName")
        val overflowCount = intOrNull(obj, KEY_OVERFLOW_COUNT) ?: return fail("overflowCount 缺失或非整数")
        if (overflowCount < 0) return fail("overflowCount 为负: $overflowCount")
        val rawEvents = eventsOrNull(obj, KEY_EVENTS) ?: return fail("$KEY_EVENTS 缺失或不是数组")
        val rawRejected = eventsOrNull(obj, KEY_REJECTED_EVENTS) ?: return fail("$KEY_REJECTED_EVENTS 缺失或不是数组")
        val decodedEvents = ArrayList<RecEvent>(rawEvents.size)
        for ((index, element) in rawEvents.withIndex()) {
            decodedEvents += decodeEvent(element) ?: return fail("$KEY_EVENTS[$index] 结构非法")
        }
        val decodedRejected = ArrayList<RecEvent>(rawRejected.size)
        for ((index, element) in rawRejected.withIndex()) {
            decodedRejected += decodeEvent(element) ?: return fail("$KEY_REJECTED_EVENTS[$index] 结构非法")
        }
        val session = RecorderSession(
            targetPkg = targetPkg,
            clock = clock,
            initialState = SessionState.STOPPED,
            initialEvents = decodedEvents,
            initialOverflowCount = overflowCount,
            initialRejectedEvents = decodedRejected,
        )
        return SessionRestore.Loaded(session)
    }

    /* ---------------- 事件编码 ---------------- */

    private fun encodeEvents(events: List<RecEvent>): JsonArray = buildJsonArray {
        events.forEach { add(encodeEvent(it)) }
    }

    private fun encodeEvent(event: RecEvent): JsonObject = when (event) {
        is WindowChanged -> buildJsonObject {
            put(KEY_TYPE, TYPE_WINDOW)
            put(KEY_PKG, event.pkg)
            put(KEY_WINDOW_TITLE, event.windowTitle?.let { JsonPrimitive(it) } ?: JsonNull)
            put(KEY_TS, event.timestampMs)
        }
        is NodeAction -> buildJsonObject {
            put(KEY_TYPE, TYPE_ACTION)
            put(KEY_KIND, event.kind.name)
            put(KEY_TEXT, event.text?.let { JsonPrimitive(it) } ?: JsonNull)
            put(KEY_CONFIRMED, event.confirmed)
            put(KEY_TS, event.timestampMs)
            put(KEY_SNAPSHOT, encodeSnapshot(event.snapshot))
        }
    }

    private fun encodeSnapshot(snapshot: NodeSnapshot): JsonObject = buildJsonObject {
        put(KEY_RESOURCE_ID, snapshot.resourceId?.let { JsonPrimitive(it) } ?: JsonNull)
        put(KEY_TEXT, snapshot.text?.let { JsonPrimitive(it) } ?: JsonNull)
        put(KEY_CONTENT_DESC, snapshot.contentDesc?.let { JsonPrimitive(it) } ?: JsonNull)
        put(KEY_CLASS_NAME, snapshot.className?.let { JsonPrimitive(it) } ?: JsonNull)
        put(KEY_PKG, snapshot.pkg)
        put(KEY_INDEX_PATH, buildJsonArray { snapshot.indexPath.forEach { add(JsonPrimitive(it)) } })
    }

    /* ---------------- 事件解码 ---------------- */

    private fun decodeEvent(element: JsonElement): RecEvent? {
        val obj = element as? JsonObject ?: return null
        return when (str(obj, KEY_TYPE)) {
            TYPE_WINDOW -> {
                val pkg = str(obj, KEY_PKG) ?: return null
                val ts = longOrNull(obj, KEY_TS) ?: return null
                WindowChanged(pkg, str(obj, KEY_WINDOW_TITLE), timestampMs = ts)
            }
            TYPE_ACTION -> {
                val ts = longOrNull(obj, KEY_TS) ?: return null
                val kind = str(obj, KEY_KIND)?.let { runCatching { NodeActionKind.valueOf(it) }.getOrNull() }
                    ?: return null
                val confirmed = (obj[KEY_CONFIRMED] as? JsonPrimitive)?.booleanOrNull ?: return null
                val snapshot = decodeSnapshot(obj[KEY_SNAPSHOT]) ?: return null
                NodeAction(
                    kind = kind,
                    snapshot = snapshot,
                    text = str(obj, KEY_TEXT),
                    confirmed = confirmed,
                    timestampMs = ts,
                )
            }
            else -> null
        }
    }

    private fun decodeSnapshot(element: JsonElement?): NodeSnapshot? {
        val obj = element as? JsonObject ?: return null
        val pkg = str(obj, KEY_PKG) ?: return null
        val path = obj[KEY_INDEX_PATH] as? JsonArray ?: return null
        val indexPath = ArrayList<Int>(path.size)
        for (element2 in path) {
            val v = (element2 as? JsonPrimitive)?.intOrNullRaw() ?: return null
            indexPath += v
        }
        return NodeSnapshot(
            resourceId = str(obj, KEY_RESOURCE_ID),
            text = str(obj, KEY_TEXT),
            contentDesc = str(obj, KEY_CONTENT_DESC),
            className = str(obj, KEY_CLASS_NAME),
            pkg = pkg,
            indexPath = indexPath,
        )
    }

    /* ---------------- 字段读取小工具（严格：类型不符即 null，不猜） ---------------- */

    private fun str(obj: JsonObject, key: String): String? =
        (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun longOrNull(obj: JsonObject, key: String): Long? =
        (obj[key] as? JsonPrimitive)?.let { it.content.toLongOrNull() }

    private fun intOrNull(obj: JsonObject, key: String): Int? =
        (obj[key] as? JsonPrimitive)?.let { it.content.toIntOrNull() }

    private fun JsonPrimitive.intOrNullRaw(): Int? = content.toIntOrNull()

    private fun eventsOrNull(obj: JsonObject, key: String): List<JsonElement>? = (obj[key] as? JsonArray)?.toList()

    private const val KEY_FORMAT = "format"
    private const val KEY_VERSION = "version"
    private const val KEY_TARGET_PKG = "targetPkg"
    private const val KEY_STATE = "state"
    private const val KEY_OVERFLOW_COUNT = "overflowCount"
    private const val KEY_EVENTS = "events"
    private const val KEY_REJECTED_EVENTS = "rejectedEvents"
    private const val KEY_TYPE = "type"
    private const val KEY_TS = "timestampMs"
    private const val KEY_PKG = "pkg"
    private const val KEY_WINDOW_TITLE = "windowTitle"
    private const val KEY_KIND = "kind"
    private const val KEY_CONFIRMED = "confirmed"
    private const val KEY_SNAPSHOT = "snapshot"
    private const val KEY_RESOURCE_ID = "resourceId"
    private const val KEY_TEXT = "text"
    private const val KEY_CONTENT_DESC = "contentDesc"
    private const val KEY_CLASS_NAME = "className"
    private const val KEY_INDEX_PATH = "indexPath"
    private const val TYPE_WINDOW = "window"
    private const val TYPE_ACTION = "action"
}
