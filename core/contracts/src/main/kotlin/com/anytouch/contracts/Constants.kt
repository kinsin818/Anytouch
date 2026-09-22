package com.anytouch.contracts

/**
 * 契约通用 Json 配置:
 * - ignoreUnknownKeys = true —— 未知字段策略为"忽略"（详见 docs/ANYTOUCH-S0-STAGE-01-report.md 记录）
 * - encodeDefaults = true —— 默认值字段也序列化输出，保证与 JSON 样例 1:1
 * - explicitNulls = true —— 样例中显式出现的 null（如 ActionResult.recovery）必须保留输出
 */
object ContractJson {
    val instance = kotlinx.serialization.json.Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = true
        prettyPrint = false
    }
}

/**
 * Action.type 常量（非严格枚举，留扩展位；已见值取自契约样例与左舵项目）。
 */
object ActionType {
    const val CLICK = "click"
    const val MOVE = "move"
    const val SCROLL = "scroll"
    const val TYPE_TEXT = "type_text"
    const val KEY = "key"
    const val WAIT = "wait"
    const val SELECT_DROPDOWN = "select_dropdown"
    const val SUBMIT = "submit"
}

/**
 * Action.source 定位链来源常量。
 */
object ActionSource {
    const val NODE = "node"
    const val OCR = "ocr"
    const val VLM = "vlm"
    const val CACHE = "cache"
    const val TEMPLATE = "template"
    const val VISION = "vision"
}

/**
 * StopReason.code 常量（非严格枚举，留扩展位）。
 */
object StopCode {
    const val NONE = ""
    const val USER_STOP = "USER_STOP"
    const val PAGE_UNKNOWN = "PAGE_UNKNOWN"
    const val SELECT_RETRY_EXHAUSTED = "SELECT_RETRY_EXHAUSTED"
    const val SAFETY_VIOLATION = "SAFETY_VIOLATION"
    const val VIEWPORT_OUT_OF_BOUNDS = "VIEWPORT_OUT_OF_BOUNDS"
    const val EXECUTOR_ERROR = "EXECUTOR_ERROR"
    const val TIMEOUT = "TIMEOUT"
}

/**
 * StopReason.severity 常量。
 */
object StopSeverity {
    const val INFO = "INFO"
    const val WARN = "WARN"
    const val STOP = "STOP"
}
