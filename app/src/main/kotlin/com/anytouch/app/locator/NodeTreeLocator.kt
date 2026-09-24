package com.anytouch.app.locator

/**
 * 节点树三级定位（军令 L2-STAGE-11）。纯 JVM、零 Android 依赖、零新增依赖。
 *
 * 阶次与短路：一阶 resource-id 精确 → 二阶 text/contentDescription trim 全等 → 三阶层级路径。
 * 任一阶命中即终止，其余阶记 NOT_ATTEMPTED；三阶全空只返回 [LocatorMiss]，
 * 不猜、不降级到坐标（军令 L1 硬约束：本类任何匹配都不读 [UiNode.bounds]）。
 *
 * 序号语义：一阶、二阶的多命中用 [LocatorRequest.instance]（文档序 0 基）取；
 * 三阶只认路径段自带的 `[n]`，[LocatorRequest.instance] 不参与三阶，避免二次索引歧义。
 */
class NodeTreeLocator {

    fun locate(root: UiNode?, request: LocatorRequest): LocatorResult {
        if (root == null) {
            return LocatorMiss(
                LocatorLevel.entries.map { level ->
                    LocatorAttempt(level, queryFor(level, request), AttemptOutcome.NOT_ATTEMPTED, 0, "root is null (accessibility tree not ready)")
                },
            )
        }
        val attempts = ArrayList<LocatorAttempt>(3)
        for (level in LocatorLevel.entries) {
            val outcome = when (level) {
                LocatorLevel.RESOURCE_ID -> byResourceId(root, request)
                LocatorLevel.TEXT -> byTextOrDesc(root, request)
                LocatorLevel.PATH -> byPath(root, request)
            }
            when (outcome) {
                is LevelOutcome.Found -> {
                    attempts += LocatorAttempt(level, queryFor(level, request), AttemptOutcome.HIT, outcome.candidateCount, outcome.detail)
                    return LocatorHit(
                        level = level,
                        nodeRef = NodeRef.of(root, outcome.node, level),
                        node = outcome.node,
                        matchedBy = outcome.matchedBy,
                        attempts = attempts.toList(),
                    )
                }
                is LevelOutcome.Missing -> attempts += LocatorAttempt(
                    level = level,
                    query = queryFor(level, request),
                    outcome = outcome.outcome,
                    candidateCount = outcome.candidateCount,
                    detail = outcome.detail,
                )
            }
        }
        return LocatorMiss(attempts)
    }

    private fun queryFor(level: LocatorLevel, request: LocatorRequest): String? = when (level) {
        LocatorLevel.RESOURCE_ID -> request.resourceId
        LocatorLevel.TEXT -> listOfNotNull(
            request.text?.let { "text=$it" },
            request.contentDesc?.let { "desc=$it" },
        ).joinToString(",").ifEmpty { null }
        LocatorLevel.PATH -> request.path
    }

    /* ---- 一阶：resource-id 精确 ---- */

    private fun byResourceId(root: UiNode, request: LocatorRequest): LevelOutcome {
        val wanted = request.resourceId ?: return missing(AttemptOutcome.NOT_ATTEMPTED, "request carries no resource-id")
        if (wanted.isBlank()) return missing(AttemptOutcome.INVALID_QUERY, 0, "resource-id is blank")
        val candidates = root.preOrder().filter { it.resourceId?.trim() == wanted.trim() }
        if (candidates.isEmpty()) return missing(AttemptOutcome.NO_MATCH, 0, "resource-id '$wanted' zero hits")
        return pick(candidates, request.instance) { "resource-id='${wanted}'" }
    }

    /* ---- 二阶：text / contentDescription trim 全等 ---- */

    private fun byTextOrDesc(root: UiNode, request: LocatorRequest): LevelOutcome {
        val text = request.text
        val desc = request.contentDesc
        if (text == null && desc == null) {
            return missing(AttemptOutcome.NOT_ATTEMPTED, "request carries no text/contentDescription")
        }
        val trimmedText = text?.trim()?.takeIf { it.isNotEmpty() }
        val trimmedDesc = desc?.trim()?.takeIf { it.isNotEmpty() }
        if (trimmedText == null && trimmedDesc == null) {
            return missing(AttemptOutcome.INVALID_QUERY, 0, "text/contentDescription are both blank")
        }
        val matched = root.preOrder().mapNotNull { node ->
            when {
                trimmedText != null && trimmedText == node.text?.trim() -> node to "text='$trimmedText'"
                trimmedDesc != null && trimmedDesc == node.contentDesc?.trim() -> node to "contentDescription='$trimmedDesc'"
                else -> null
            }
        }
        if (matched.isEmpty()) {
            return missing(
                AttemptOutcome.NO_MATCH,
                0,
                "text='$trimmedText'/desc='$trimmedDesc' zero trimmed-equality hits (not contains)",
            )
        }
        return pick(matched.map { it.first }, request.instance) { node -> matched.first { it.first === node }.second }
    }

    /* ---- 三阶：层级路径 ---- */

    private fun byPath(root: UiNode, request: LocatorRequest): LevelOutcome {
        val path = request.path ?: return missing(AttemptOutcome.NOT_ATTEMPTED, "request carries no hierarchical path")
        val segments = try {
            PathPatternParser.parse(path)
        } catch (e: PathSyntaxException) {
            return missing(AttemptOutcome.INVALID_QUERY, 0, "path '$path' has invalid syntax: ${e.message}")
        }
        var scope: List<UiNode> = root.subtreeBreadthFirst()
        for ((depth, segment) in segments.withIndex()) {
            val candidates = scope.filter(segment::matches)
            if (candidates.isEmpty()) {
                return missing(
                    AttemptOutcome.NO_MATCH,
                    0,
                    "segment ${depth + 1} '${segment.raw}' zero hits (${scopeLabel(scope)})",
                )
            }
            val anchor = candidates.getOrNull(segment.index) ?: return missing(
                AttemptOutcome.INDEX_OUT_OF_RANGE,
                candidates.size,
                "segment ${depth + 1} '${segment.raw}' hit ${candidates.size}, index ${segment.index} is out of range",
            )
            if (depth == segments.lastIndex) {
                return LevelOutcome.Found(
                    node = anchor,
                    matchedBy = "path='$path'",
                    candidateCount = candidates.size,
                    detail = "last segment '${segment.raw}' at ${scopeLabel(scope)} hit ${candidates.size}, taking [${segment.index}]",
                )
            }
            scope = anchor.subtreeBreadthFirst().drop(1)
        }
        return missing(AttemptOutcome.INVALID_QUERY, 0, "path '$path' has no matchable segment")
    }

    private fun scopeLabel(scope: List<UiNode>): String {
        val head = scope.firstOrNull()?.let { NodeLabel.of(it) } ?: "empty scope"
        return "scope starts at $head, ${scope.size} node(s)"
    }

    /* ---- 共用：多命中按文档序取 instance ---- */

    private fun pick(
        candidates: List<UiNode>,
        instance: Int,
        matchedBy: (UiNode) -> String,
    ): LevelOutcome {
        val node = candidates.getOrNull(instance) ?: return missing(
            AttemptOutcome.INDEX_OUT_OF_RANGE,
            candidates.size,
            "${candidates.size} hit(s), instance=$instance is out of range",
        )
        return LevelOutcome.Found(
            node = node,
            matchedBy = matchedBy(node),
            candidateCount = candidates.size,
            detail = "${candidates.size} hit(s), taking instance=$instance",
        )
    }

    private fun missing(outcome: AttemptOutcome, detail: String) = missing(outcome, 0, detail)

    private fun missing(outcome: AttemptOutcome, candidateCount: Int, detail: String) =
        LevelOutcome.Missing(outcome, candidateCount, detail)

    private sealed class LevelOutcome {
        data class Missing(val outcome: AttemptOutcome, val candidateCount: Int, val detail: String) : LevelOutcome()

        data class Found(
            val node: UiNode,
            val matchedBy: String,
            val candidateCount: Int,
            val detail: String,
        ) : LevelOutcome()
    }
}

/** 尝试记录里的人读节点标签（不含坐标，坐标不是定位依据）。 */
internal object NodeLabel {
    fun of(node: UiNode): String = buildString {
        append(node.shortClassName ?: "Node")
        node.resourceIdEntryName?.let { append('#').append(it) }
        node.text?.trim()?.takeIf { it.isNotEmpty() }?.let { append('[').append(it).append(']') }
    }
}
