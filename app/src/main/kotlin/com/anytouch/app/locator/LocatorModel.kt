package com.anytouch.app.locator

/** 定位三阶，order 即军令中的阶次，也是尝试顺序。 */
enum class LocatorLevel(val order: Int) {
    /** 一阶：resource-id 精确命中，多命中按 instance 取序 */
    RESOURCE_ID(1),

    /** 二阶：text / contentDescription trim 后全等（非 contains） */
    TEXT(2),

    /** 三阶：层级路径，如 `Window>ListView>clickable=true[2]` */
    PATH(3),
}

/**
 * 一次定位请求：携带任务 JSON 里已知的定位线索。
 * 某一阶线索为空则该阶记为 NOT_ATTEMPTED —— 不做任何猜测或降级。
 */
data class LocatorRequest(
    val resourceId: String? = null,
    val text: String? = null,
    val contentDesc: String? = null,
    val path: String? = null,
    /** 多命中时取第几个（文档序，0 基）；对三阶的路径段同样生效。 */
    val instance: Int = 0,
) {
    init {
        require(instance >= 0) { "instance must be >= 0 but was $instance" }
    }
}

/** 节点引用：可回放的定位留痕，不含"以坐标为输入"的语义。 */
data class NodeRef(
    /** 从根到该节点的子序号路径，如 `root/0/2/1`；根自身为 `root`。 */
    val indexPath: String,
    val level: LocatorLevel,
    val resourceId: String?,
    val text: String?,
    val contentDesc: String?,
    val className: String?,
    val packageName: String?,
    val clickable: Boolean,
    val scrollable: Boolean,
    /** 仅作节点自述信息透传给执行器派生 center 使用，定位链不读它。 */
    val bounds: UiBounds?,
) {
    companion object {
        fun of(root: UiNode, node: UiNode, level: LocatorLevel): NodeRef {
            val path = identityPath(root, node)
                ?: error("node is not part of the given tree")
            return NodeRef(
                indexPath = buildString {
                    append("root")
                    for (index in path) append('/').append(index)
                },
                level = level,
                resourceId = node.resourceId,
                text = node.text,
                contentDesc = node.contentDesc,
                className = node.className,
                packageName = node.packageName,
                clickable = node.clickable,
                scrollable = node.scrollable,
                bounds = node.bounds,
            )
        }

        /** 以对象引用（===）而非 equals 求路径，避免内容相同的兄弟节点串位。 */
        private fun identityPath(root: UiNode, node: UiNode): List<Int>? {
            if (root === node) return emptyList()
            val path = ArrayList<Int>()
            if (descend(root, node, path)) return path
            return null
        }

        private fun descend(parent: UiNode, node: UiNode, path: MutableList<Int>): Boolean {
            for ((index, child) in parent.children.withIndex()) {
                if (child === node) {
                    path += index
                    return true
                }
                path += index
                if (descend(child, node, path)) return true
                path.removeAt(path.lastIndex)
            }
            return false
        }
    }
}

/** 一阶尝试的结果。 */
enum class AttemptOutcome {
    /** 本阶命中，链路终止 */
    HIT,

    /** 本阶有候选但 instance 序号越界 */
    INDEX_OUT_OF_RANGE,

    /** 本阶零候选 */
    NO_MATCH,

    /** 请求未给出本阶线索，或上一阶已命中 */
    NOT_ATTEMPTED,

    /** 本阶线索语法非法（如未知谓词、布尔值非 true/false） */
    INVALID_QUERY,
}

/** 单阶尝试记录，失败归因"未找到节点"的数据源。 */
data class LocatorAttempt(
    val level: LocatorLevel,
    val query: String?,
    val outcome: AttemptOutcome,
    val candidateCount: Int,
    val detail: String,
)

sealed interface LocatorResult {
    val attempts: List<LocatorAttempt>
}

/**
 * 命中留痕：军令要求的 `LocatorHit(level, nodeRef)`，另附两项主窗接线要用的东西——
 * [node] 是活的节点句柄（执行器对它执行点击/滚动，`bounds.center` 由此派生），
 * [nodeRef] 是可回放、可写回执卡的引用快照（E2 节点 diff 的数据源）。
 */
data class LocatorHit(
    val level: LocatorLevel,
    val nodeRef: NodeRef,
    val node: UiNode,
    /** 本阶实际生效的查询描述，如 `text='WLAN'` 或 `path='Window>ListView>clickable=true[2]'`。 */
    val matchedBy: String,
    override val attempts: List<LocatorAttempt>,
) : LocatorResult

/** 三阶全空：只报 miss，不猜、不降级到坐标。 */
data class LocatorMiss(
    override val attempts: List<LocatorAttempt>,
) : LocatorResult {
    /** 供失败归因（"未找到节点"类）使用的稳定码。 */
    val code: String get() = NODE_NOT_FOUND

    val summary: String
        get() = attempts.joinToString(" | ") { "L${it.level.order} ${it.outcome.name} ${it.detail}" }

    companion object {
        /** contracts 的 StopCode 属冻结文件且非严格枚举，此码由主窗窄口集成时写入 StopReason.code。 */
        const val NODE_NOT_FOUND = "NODE_NOT_FOUND"
    }
}
