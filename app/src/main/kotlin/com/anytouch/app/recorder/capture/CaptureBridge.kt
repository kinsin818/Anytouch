package com.anytouch.app.recorder.capture

import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.anytouch.app.recorder.session.RecorderStore

/**
 * 采集桥接口（S2-ONDEVICE 划界）：把 Android 事件翻成 [CaptureEvent] 并路由进会话。
 * 实现只许薄到"翻译+快照"一行逻辑——裁剪/折叠/语义全在纯 JVM 的 CaptureAdapter，保持可测。
 */
interface CaptureBridge {
    fun capture(event: AccessibilityEvent)
}

/**
 * 真桥。逐边口径：
 * - 只认五类语义事件（窗态/点击/长按/文本变化/滚动）；typeWindowContentChanged 这类洪流
 *   不入配置也不入映射——每秒可产千条，全记成 UNKNOWN 会淹没 2000 条事件预算（通道噪声非用户动作，
 *   在"是否成事件"这一层就拒，而不是落进会话再等编译器丢弃留痕——留痕纪律护的是语义边，不是洪流）。
 * - 定位失败（句柄失联且内容取证不唯一）写 S2SMOKE skip 痕后跳过：这类边无用户动作语义可记，
 *   不存在"丢了的步骤"，故不构成编译面丢弃的对偶，但"这一下没录上"必须可见。
 *
 * [rootProvider] 由 service 注入"当前可遍历的窗口根集合"（与执行器 [com.anytouch.app.platform.AccessibilityDevice]
 * 同口径）：录进任务的 indexPath 必须与回放端 locator 的锚点同根，否则路径词汇形同虚设。
 */
class AndroidCaptureBridge(
    private val rootProvider: () -> List<AccessibilityNodeInfo> = { emptyList() },
) : CaptureBridge {

    override fun capture(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        // 自家浮层（录制球/停止球/确认面板）的点击永不入流：在取证**之前**挡掉，
        // 否则每次点球都要跑一遍窗口遍历取证，且"用户点了自己的开关"会被当成业务动作。
        if (pkg == RecorderStore.selfPkg) return
        val hint = when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> CaptureHint.WINDOW_CHANGED
            AccessibilityEvent.TYPE_VIEW_CLICKED -> CaptureHint.CLICK
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> CaptureHint.LONG_CLICK
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> CaptureHint.TEXT_CHANGED
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> CaptureHint.SCROLLED
            else -> return
        }
        val snapshot = if (hint == CaptureHint.WINDOW_CHANGED) {
            RawNodeSnapshot(
                resourceId = null, text = null, contentDesc = null, className = null,
                pkg = pkg, indexPath = emptyList(), hint = hint,
            )
        } else {
            val handle = event.source
            val meta = metaOf(event)
            val roots = runCatching { rootProvider() }.getOrNull().orEmpty()
            logEventMeta(event, handle)
            val located = locatedOf(handle, meta, roots, pkg)
            // 有路径→按路径所指节点读线索；无路径但有句柄→只许带线索入流（见 snapshotOf）；
            // 无句柄→只许吃事件当场拷贝的线索（见 snapshotFromEvent）。
            val snapshot = when {
                located != null -> snapshotOf(event, located.node, located, meta, roots, pkg, hint)
                handle != null -> snapshotOf(event, handle, null, meta, roots, pkg, hint)
                else -> snapshotFromEvent(event, meta, roots, pkg, hint)
            }
            if (snapshot == null) {
                // 句柄缺失/不可证且无可用线索：留痕不静默（用户看得见"这一下没录上"，才谈得上补通道；
                // 静默失踪=第 8 雷形态）
                Log.w(
                    TAG,
                    "S2SMOKE capture skipped: ${typeOf(event.eventType)} " +
                        (if (handle == null) "无 source 句柄且事件无可用线索" else "路径不可证且无可用线索") + " pkg=$pkg",
                )
                return
            }
            snapshot
        }
        // 时间戳取会话钟（生产=currentTimeMillis）：eventTime 是 uptimeMillis 时基，
        // 与编译器 300ms 去抖窗口的绝对毫秒语义不合，两套钟混用会让去抖永不/恒触发。
        RecorderStore.bridgeDeliver(snapshot)
    }

    /** 事件自带"当场拷贝"元数据：派发时框架从被操作节点复制，节点随后被转场销毁也仍在。 */
    private data class EventMeta(val className: String?, val texts: List<String>, val desc: String?)

    private fun metaOf(event: AccessibilityEvent) = EventMeta(
        className = event.className?.toString(),
        texts = runCatching { event.text?.mapNotNull { it.toString().orNull() } ?: emptyList() }
            .getOrDefault(emptyList()),
        desc = event.contentDescription?.toString().orNull(),
    )

    /** 定位成功的一个点：路径（与回放端同根）+ 该路径所指节点的句柄（线索从它读，绝不读别处的）。 */
    private data class Located(val indexPath: List<Int>, val node: AccessibilityNodeInfo)

    /**
     * 由句柄出快照。[located]=null 表示路径不可证：此时**只许**带线索（resourceId / text / contentDesc）
     * 入流，indexPath 退化为"句柄自身父链"（[rawChainPath]）。
     *
     * 为什么这样合法（读冻结编译器所得，不改其一行）：
     * - [com.anytouch.app.recorder.putLocatorClues] 只在 id/text/desc **三者皆空**时才写 `path` 词汇，
     *   所以带线索的步骤回放走线索命中 + nearestClickableSelfOrAncestor，那串未锚定的下标**永不参与定位**；
     * - 它对冻结层的两个作用都在语义安全侧：`DroppedInvalidPath` 只要非负（真断链给 [-1] 照旧丢弃），
     *   去抖/折叠键要求"同一路径 + 300ms 窗"——真实两行的父链下标天然不同。
     * 无线索且路径不可证 → 交回上层留痕丢弃：回放无从定位，宁缺不错点。
     */
    private fun snapshotOf(
        event: AccessibilityEvent,
        source: AccessibilityNodeInfo,
        located: Located?,
        meta: EventMeta,
        roots: List<AccessibilityNodeInfo>,
        pkg: String,
        hint: CaptureHint,
    ): RawNodeSnapshot? {
        val ownText = source.text?.toString().orNull()
        val ownDesc = source.contentDescription?.toString().orNull()
        var text = ownText
        var desc = ownDesc
        if ((hint == CaptureHint.CLICK || hint == CaptureHint.LONG_CLICK) && text == null && desc == null) {
            val clue = descendantClue(source)
            text = clue?.first
            desc = clue?.second
        }
        val resourceId = source.viewIdResourceName.orNull()
        var path = located?.indexPath
        if (path == null) {
            if (resourceId == null && text == null && desc == null) {
                // 句柄是**残缺拷贝**（设备实证：kids=2 而子树一个字都取不到、父链当场断裂 walkDepth=0），
                // 而事件派发当场拷走的文字仍完整（evt=[Bluetooth]）——退到事件这份证词，仍不算编造。
                val copy = pinEventClue(
                    eventCopyWords(hint, meta),
                    source.packageName?.toString() ?: pkg,
                    roots,
                    meta.desc,
                ) ?: return null
                text = copy.first
                desc = copy.second
            }
            val chain = rawChainPath(source) ?: return null
            path = chain
            Log.i(
                TAG,
                "S2SMOKE path=chain-unanchored(仅作去抖/折叠键，回放定位走线索): $chain " +
                    "id=$resourceId text=$text desc=$desc",
            )
        } else if (resourceId == null && text == null && desc == null) {
            // 路径已证、但产物只能用 path 词汇（军令 L2-5 最末档：目标树转场重建即失效）。
            // 事件当场拷贝的词若在当前窗口内唯一可解析，它是比 path 更强的定位词汇，换掉 path；
            // 钉不住（歧义/字段错位）则维持 path（宁保精确的脆词汇，不用可能指错行的文字）。
            val copy = pinEventClue(
                eventCopyWords(hint, meta),
                source.packageName?.toString() ?: pkg,
                roots,
                meta.desc,
            )
            if (copy != null) {
                text = copy.first
                desc = copy.second
                Log.i(TAG, "S2SMOKE clue=event-copy(窗口唯一，替代脆 path=$path): text=$text desc=$desc")
            }
        }
        return RawNodeSnapshot(
            resourceId = resourceId,
            text = text,
            contentDesc = desc,
            className = source.className?.toString(),
            pkg = pkg,
            indexPath = path,
            // 滚动量在**事件**上（AccessibilityRecord 口径），节点信息里没有 scroll 位置
            scrollDeltaX = event.scrollDeltaX,
            scrollDeltaY = event.scrollDeltaY,
            scrollX = event.scrollX,
            scrollY = event.scrollY,
            maxScrollX = event.maxScrollX,
            maxScrollY = event.maxScrollY,
            scrollable = source.isScrollable,
            hint = hint,
        )
    }

    /**
     * 事件"当场拷贝"里的候选词（句柄残缺/失联时的第二证词）。
     * 语义根据：框架派发点击事件时把**被操作节点的可读文本**按文档序拷进事件（实测偏好设置行
     * = [标题, 摘要]），所以列表里的词都出自被点那一行；取首项=文档序最前=偏好行的 `android:id/title`。
     * 只认点击类：打字类的 text 是**输入值**，不是定位线索，绝不可拿拷贝文字顶替。
     */
    private fun eventCopyWords(hint: CaptureHint, meta: EventMeta): List<String> = when {
        hint != CaptureHint.CLICK && hint != CaptureHint.LONG_CLICK -> emptyList()
        meta.texts.isNotEmpty() -> listOf(meta.texts.first())
        meta.desc != null -> listOf(meta.desc)
        else -> emptyList()
    }

    /**
     * 把事件拷贝的**词**钉成回放真能用的**定位词汇**——不是所有证词都长得像它所在字段：
     * 设备实证 工具栏返回键的点击事件 text 里带着 "Navigate up"，而活树中该串只存在于
     * `contentDescription` 字段（`text` 为空）。照抄成 text 词汇 → 回放 L2 零命中（NODE_NOT_FOUND，
     * 曾把 8 步链第 4 步打死）。
     *
     * 钉法与回放同口径（[com.anytouch.app.locator.NodeTreeLocator] 二阶只拿 text 比 `node.text`、
     * 拿 desc 比 `node.contentDescription`，不交叉）：当前窗口内按 text 全等恰一命中 → 记 text；
     * 否则按 desc 全等恰一命中 → 记 desc；两边都不唯一 → 弃用（宁缺不错点）。
     *
     * **0 命中单独待**：点击落下时页面已被自己换掉，此刻扫到的常是"新页树还没长全"的那一帧，
     * 0/0 不是"词歧义"的证据（设备实证：18 轮里 3 轮首步的 "Connected devices" 扫到 0/0，
     * 而同轮稍后再扫，句柄已在新的根里被 search 到）。这类帧重扫封顶 [PIN_SETTLE_RETRIES] 次
     * ×[PIN_SETTLE_MS]（与 [com.anytouch.app.service.AnytouchAccessibilityService] 的 roots 空帧重试同口径，
     * 主线阻塞上界 240ms，不出 ANR 预算）。命中>1 是真歧义，重扫不改结论，直接弃。
     */
    private fun pinEventClue(
        words: List<String>,
        wantPkg: String,
        roots: List<AccessibilityNodeInfo>,
        eventDesc: String?,
    ): Pair<String?, String?>? {
        if (words.isEmpty()) return null
        var current = roots
        var lastWord: String? = null
        var lastText = 0
        var lastDesc = 0
        var attempt = 0
        while (attempt <= PIN_SETTLE_RETRIES) {
            if (attempt > 0) {
                runCatching { Thread.sleep(PIN_SETTLE_MS) }
                current = runCatching { rootProvider() }.getOrNull().orEmpty()
            }
            var ambiguous = false
            for (word in words) {
                val (textHits, descHits) = countFieldPins(word, wantPkg, current)
                lastWord = word
                lastText = textHits
                lastDesc = descHits
                when {
                    textHits == 1 -> return pinDone(attempt, word, text = true)
                    descHits == 1 -> return pinDone(attempt, word, text = false)
                    textHits > 1 || descHits > 1 -> ambiguous = true
                }
            }
            if (!ambiguous && attempt < PIN_SETTLE_RETRIES) {
                attempt++
                continue
            }
            if (ambiguous || eventDesc == null || words.isEmpty()) {
                // 歧义（或事件压根没 desc）：没有任何一手证据能断定这词指哪一行，弃。
                Log.i(
                    TAG,
                    "S2SMOKE clue=event-copy 弃用(窗口内 text 命中=$lastText desc 命中=$lastDesc，" +
                        "皆需唯一${if (attempt > 0) "，含转场重扫 $attempt 次" else ""}): word=$lastWord",
                )
                return null
            }
            // 重扫后仍 0/0 且事件自带 desc：把 desc 原样记成 desc 词汇。**只有这一级是免窗口验证的**——
            // event.contentDescription 由框架从被点节点该字段直拷（不像 event.text 会把 desc 串抄进
            // text、也不像它会被整列表的子树文字聚合），所以字段归属不必靠活树裁断。
            // 设备实证：src=null 的返回键点击走这条路前整步被弃（录 8 步只出 7 步）。
            Log.i(TAG, "S2SMOKE clue=event-desc(转场后活树无证据，事件 desc 字段直读): word=$eventDesc")
            return null to eventDesc
        }
        return null
    }

    private fun pinDone(attempt: Int, word: String, text: Boolean): Pair<String?, String?> {
        if (attempt > 0) {
            Log.i(TAG, "S2SMOKE clue=event-copy 转场重扫第 $attempt 次落定: word=$word 字段=${if (text) "text" else "desc"}")
        }
        return if (text) word to null else null to word
    }

    /** 一词在两字段上各命中几个（与回放 L2 的 trim 全等、不交叉字段口径逐字对齐）。 */
    private fun countFieldPins(
        word: String,
        wantPkg: String,
        roots: List<AccessibilityNodeInfo>,
    ): Pair<Int, Int> {
        var textHits = 0
        var descHits = 0
        for (root in roots) {
            if (root.packageName?.toString() != wantPkg) continue
            val queue = ArrayDeque(listOf(root))
            var visited = 0
            while (queue.isNotEmpty() && visited < SEARCH_BUDGET && textHits < 2 && descHits < 2) {
                val node = queue.removeFirst()
                visited++
                if (node.text?.toString().orNull() == word) textHits++
                if (node.contentDescription?.toString().orNull() == word) descHits++
                for (i in 0 until node.childCount) {
                    val c = node.getChild(i) ?: continue
                    queue.addLast(c)
                }
            }
        }
        return textHits to descHits
    }

    /**
     * 句柄失联时的兜底：只吃事件**派发当场**从被操作节点拷来的元数据成快照（实证：同一轮点击里句柄
     * 时而是容器、时而是整个列表、时而直接 null，而事件里的 "Connected devices" 始终跟着被点那一行）。
     * 没有线索就没有定位词汇（path 无从证真），整条交回上层留痕丢弃。
     * 滚动量仍从事件读（无句柄时 isScrollable 未知，按 false 记；滚动方向判据另有 delta/pos 三档）。
     */
    private fun snapshotFromEvent(
        event: AccessibilityEvent,
        meta: EventMeta,
        roots: List<AccessibilityNodeInfo>,
        pkg: String,
        hint: CaptureHint,
    ): RawNodeSnapshot? {
        // 打字类无句柄：事件 text 就是**输入值**（框架在变更后当场拷的字段内容），不是线索而是载荷；
        // 点击类只吃"钉得住"的拷贝词（字段错位/歧义的一律弃，交上层留痕）。
        val clue = if (hint == CaptureHint.TEXT_CHANGED) {
            meta.texts.firstOrNull()?.let { it to null }
        } else {
            pinEventClue(eventCopyWords(hint, meta), pkg, roots, meta.desc)
        } ?: return null
        return RawNodeSnapshot(
            resourceId = null,
            text = clue.first,
            contentDesc = clue.second,
            className = meta.className,
            pkg = pkg,
            // 无句柄无从谈路径：空路径只可能出现在带线索步上（编译器不会为它写 path 词汇）
            indexPath = emptyList(),
            scrollDeltaX = event.scrollDeltaX,
            scrollDeltaY = event.scrollDeltaY,
            scrollX = event.scrollX,
            scrollY = event.scrollY,
            maxScrollX = event.maxScrollX,
            maxScrollY = event.maxScrollY,
            scrollable = false,
            hint = hint,
        )
    }

    /**
     * 句柄自身父链（不要求与窗口根同一身份）：设备实证父链能一路走到顶（walkDepth=10），
     * 失效的只是"顶端 equals 窗口根"这一步。断裂（返回 null）或含负下标（[-1]）都不采信。
     * 用途见 [snapshotOf] 注释：仅作去抖/折叠键与账目，绝不作回放定位词汇。
     */
    private fun rawChainPath(source: AccessibilityNodeInfo): List<Int>? = try {
        val path = computeIndexPath(
            source,
            parentOf = { it.parent },
            childCountOf = { it.childCount },
            indexOfChild = { parent, child -> indexOfIn(parent, child) },
            isRoot = { true },
        )
        if (path == null || path.any { it < 0 }) null else path
    } catch (e: Exception) {
        null
    }

    /**
     * 两条证据同框留痕（归因用，不参与判定）：
     * - `evt.*` = `event.className/text/contentDescription`，框架**派发当场**从被操作节点拷贝的元数据，
     *   节点随后被转场销毁也仍在（实证：同一轮三次点击里句柄时而行容器、时而整个列表、时而直接 null，
     *   而事件里的 "Connected devices" 始终在被点的那一行上）；
     * - `src.*` = 我们真正能遍历/定位的那棵树里的句柄读数。
     * 两者一致=可放心用文字线索；不一致=哪一条都不许单独采信（回放点错比点不到更糟），
     * 只能走"窗口内内容唯一命中"这条互证路（[locatedByContent]）。
     */
    private fun logEventMeta(event: AccessibilityEvent, handle: AccessibilityNodeInfo?) {
        val texts = runCatching { event.text?.joinToString("|") { it.toString() } }.getOrNull()
        Log.i(
            TAG,
            "S2SMOKE event-meta ${typeOf(event.eventType)} evt=${event.className}" +
                "/${texts.orNull()}/${event.contentDescription?.toString().orNull()}" +
                " src=${handle?.className ?: "null"}/${handle?.text?.toString().orNull()}" +
                "/${handle?.contentDescription?.toString().orNull()} id=${handle?.viewIdResourceName}",
        )
    }

    /**
     * 与被点节点同根的 indexPath。三条判据依次尝试，任一成立即采信；全败整条事件弃掉留痕。
     *
     * 设备实证（Android 14 模拟器，录制期零 uiautomator dump、前窗已断言为目标 App）：
     * ① 父链直走**能**走通（walkDepth=10，每步兄弟比对成立），但顶端与 `rootInActiveWindow` 的
     *    框架 equals 恒 false；
     * ② 从窗口根按框架身份正搜也搜不到——同一逻辑节点的两份副本 `uniqueId`/`window` 皆为 null，
     *    框架身份判据在这条 ROM 上根本不可用；
     * ③ 于是改用**内容**：类名 + 子树文字/描述集合在窗口内**恰好一个**节点相同 → 该节点即被点节点，
     *    其文档序路径就是要录的路径（句柄失联时以事件当场拷贝的文字参与同一判据）。
     * 不唯一=无法区分，宁弃不猜（录错行=回放到别的控件上，比失败更糟）。
     */
    private fun locatedOf(
        source: AccessibilityNodeInfo?,
        meta: EventMeta,
        roots: List<AccessibilityNodeInfo>,
        pkg: String,
    ): Located? {
        if (source != null) {
            walkParents(source, roots)?.let { return Located(it, source) }
            roots.firstNotNullOfOrNull { searchPath(it, source) }?.let {
                Log.i(TAG, "S2SMOKE path by root-search(父链断裂): $it roots=${roots.size}")
                return Located(it, source)
            }
        }
        locatedByContent(source, meta, roots, pkg)?.let { return it }
        Log.w(
            TAG,
            "S2SMOKE path unprovable: src=${source?.let { fingerprint(it) } ?: "null"} " +
                "evt=${meta.className}/${meta.texts}/${meta.desc} " +
                "walkDepth=${source?.let { walkDepthOf(it) } ?: -1} " +
                "roots=" + roots.joinToString(" | ") { fingerprint(it) },
        )
        return null
    }

    /**
     * 判据③：窗口内"类名 + 子树文字/描述集合"唯一命中才取证。命中 0 或 ≥2 一律不采信。
     * 只在①②都失败后才跑（零常态开销）。
     *
     * 内容键按**两份证词**依次尝试（设备实证两份都会单独失真）：
     * - 句柄子树内容：句柄可能是转场期的残缺拷贝（实测 kids=1、整棵子树一个字都没有），此时键为空跳过；
     * - 事件当场拷贝内容：派发时框架从被操作节点连子树文字一起拷走（实测句柄 null/残缺时事件仍带着
     *   "Connected devices"、"Connection preferences|Bluetooth, Android Auto"），以它反查活树，
     *   **唯一命中**即拿到活树里的那一行——线索与路径都从活树节点读（含 viewIdResourceName，
     *   事件拷贝里没有）。
     *
     * 两档松紧：
     * - 严档＝还要求子节点数相同（副本与树内节点的文字集相同但 kids 不同=两回事，先按严的来）；
     * - 严档 0 命中时放宽一档去掉 kids 再试，仍要求**唯一**（实证：被点开关行在派发副本里 kids=2、
     *   树内同一行 kids=1，严档把它整步丢了；放宽后该键在全窗仍只命中这一个节点）。
     */
    private fun locatedByContent(
        source: AccessibilityNodeInfo?,
        meta: EventMeta,
        roots: List<AccessibilityNodeInfo>,
        pkg: String,
    ): Located? {
        val cls = source?.className?.toString().orNull() ?: meta.className ?: return null
        val kids = source?.childCount ?: -1
        val fromSource = source?.let { contentOf(it) } ?: (emptyList<String>() to emptyList())
        val fromEvent = meta.texts.distinct().sorted() to listOfNotNull(meta.desc).distinct().sorted()
        val srcPkg = source?.packageName?.toString().orNull() ?: pkg
        var tried = false
        for ((label, content) in listOf("句柄子树" to fromSource, "事件拷贝" to fromEvent)) {
            if (content.first.isEmpty() && content.second.isEmpty()) continue
            tried = true
            val strict = scanByContent(source, cls, kids, content, roots, srcPkg, requireKids = true, keyFrom = label)
            if (strict != null) return strict
            val loose = scanByContent(source, cls, kids, content, roots, srcPkg, requireKids = false, keyFrom = label)
            if (loose != null) return loose
        }
        if (!tried) {
            Log.i(TAG, "S2SMOKE content clue absent: cls=$cls 句柄与事件皆无文字/描述，无从唯一取证")
        }
        return null
    }

    private fun scanByContent(
        source: AccessibilityNodeInfo?,
        cls: String,
        kids: Int,
        content: Pair<List<String>, List<String>>,
        roots: List<AccessibilityNodeInfo>,
        srcPkg: String,
        requireKids: Boolean,
        keyFrom: String,
    ): Located? {
        val nearMisses = ArrayList<String>()
        var classHits = 0
        for (root in roots) {
            // 只在与被点节点同包的窗口根内取证：跨包窗口的同形容器不构成证据
            if (root.packageName?.toString() != srcPkg) continue
            val queue = ArrayDeque<Pair<AccessibilityNodeInfo, List<Int>>>()
            queue.add(root to emptyList())
            var visited = 0
            var hits = 0
            var found: Located? = null
            while (queue.isNotEmpty() && visited < SEARCH_BUDGET && hits < 2) {
                val (node, path) = queue.removeFirst()
                visited++
                val childCount = node.childCount
                val shapeOk = !requireKids || source == null || childCount == kids
                if (node.className?.toString() == cls) {
                    classHits++
                    val cand = contentOf(node)
                    if (cand != content && nearMisses.size < 3) nearMisses += "$path kids=$childCount texts=$cand"
                    if (shapeOk && cand == content) {
                        hits++
                        if (hits == 1) found = Located(path, node) else node.recycleQuietly()
                    }
                }
                for (i in 0 until childCount) {
                    val c = node.getChild(i) ?: continue
                    queue.addLast(c to path + i)
                }
                // 首个命中要留给上层读线索（不回收）；其余按遍历序回收
                val isHit = found != null && found.node === node
                if (node !== root && node !== source && !isHit) node.recycleQuietly()
            }
            val hit = found
            when {
                hits == 1 && hit != null -> {
                    Log.i(
                        TAG,
                        "S2SMOKE path by unique content(${if (requireKids) "严" else "宽"}档/$keyFrom): " +
                            "${hit.indexPath} 窗口节点=$visited cls=$cls texts=${content.first}",
                    )
                    return hit
                }
                hits >= 2 -> Log.i(TAG, "S2SMOKE content ambiguous(同窗 $hits 个同内容节点，宁弃不猜): cls=$cls texts=${content.first}")
            }
        }
        // 0 命中也要可归因：同类名节点有几个、它们的内容差在哪（副本与树内不同形一眼可辨）
        Log.i(
            TAG,
            "S2SMOKE content no-match(${if (requireKids) "严档 kids=$kids" else "宽档"}/$keyFrom): " +
                "want=${content.first} 同cls节点=$classHits roots=${roots.size} near=${nearMisses.joinToString(" || ")}",
        )
        return null
    }

    /**
     * 内容取证键（**零坐标**，与军令 L1 同口径）：有界子树（[DESCENDANT_BUDGET]/[DESCENDANT_DEPTH]）内
     * 非空 text 集与 contentDescription 集，各自排序去重。两侧同预算同遍历序，一致可比。
     */
    private fun contentOf(node: AccessibilityNodeInfo): Pair<List<String>, List<String>> {
        val texts = ArrayList<String>()
        val descs = ArrayList<String>()
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.add(node to 0)
        var visited = 0
        while (queue.isNotEmpty() && visited < DESCENDANT_BUDGET) {
            val (n, depth) = queue.removeFirst()
            visited++
            n.text?.toString().orNull()?.let { texts += it }
            n.contentDescription?.toString().orNull()?.let { descs += it }
            if (depth < DESCENDANT_DEPTH) {
                for (i in 0 until n.childCount) {
                    val c = n.getChild(i) ?: continue
                    queue.addLast(c to depth + 1)
                }
            }
            if (n !== node) n.recycleQuietly()
        }
        return texts.distinct().sorted() to descs.distinct().sorted()
    }

    /**
     * 被点节点自身无线索时，向其**子树**求唯一线索（防滑轨=绝不编造）：
     * ① 子树内恰好一个 `android:id/title` 文本 → 取它（偏好设置行的标准构型：双行行的
     *    summary 文本不构成歧义，title 就是用户读到的那一行）；
     * ② 否则子树文本集**唯一**才取；③ 无文本时子树 contentDescription 唯一才取；
     * ④ 遍历触顶或结果不唯一 → 弃用并留痕，退回 path（宁要脆的 path，不要可能指向别行的 text）。
     * 回放命中文字后 nearestClickableSelfOrAncestor 走回同一可点行，语义等价（L2-5：text 优于 path）。
     */
    private fun descendantClue(source: AccessibilityNodeInfo): Pair<String?, String?>? {
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.add(source to 0)
        val texts = LinkedHashSet<String>()
        val descs = LinkedHashSet<String>()
        var titleCount = 0
        var titleText: String? = null
        var visited = 0
        var truncated = false
        while (queue.isNotEmpty()) {
            if (visited >= DESCENDANT_BUDGET) {
                truncated = true
                break
            }
            val (node, depth) = queue.removeFirst()
            visited++
            node.text?.toString().orNull()?.let { t ->
                texts.add(t)
                if (node.viewIdResourceName?.endsWith(TITLE_ID_SUFFIX) == true) {
                    titleCount++
                    if (titleText == null) titleText = t
                }
            }
            node.contentDescription?.toString().orNull()?.let { descs.add(it) }
            if (depth < DESCENDANT_DEPTH) {
                for (i in 0 until node.childCount) {
                    val child = node.getChild(i) ?: continue
                    queue.addLast(child to depth + 1)
                }
            }
            if (node !== source) node.recycleQuietly()
        }
        val picked = when {
            truncated -> null
            titleCount == 1 -> titleText?.let { it to null }
            texts.size == 1 -> texts.first()?.let { it to null }
            texts.isEmpty() && descs.size == 1 -> descs.first()?.let { null to it }
            else -> null
        }
        if (picked == null) {
            Log.i(
                TAG,
                "S2SMOKE clue=path source=${source.className} id=${source.viewIdResourceName} " +
                    "titles=$titleCount texts=${texts.size} descs=${descs.size}" +
                    if (truncated) " truncated(预算内未遍历完)" else "",
            )
        }
        return picked
    }

    /** 父链能走多深（只观测，不参与判定）：区分"父链断裂"与"身份判据失灵"两种失效形态。 */
    private fun walkDepthOf(source: AccessibilityNodeInfo): Int {
        var depth = 0
        var current: AccessibilityNodeInfo? = source
        while (true) {
            val parent = runCatching { current?.parent }.getOrNull() ?: return depth
            current = parent
            depth++
        }
    }

    /**
     * 节点身份判据：同窗口内 uniqueId（API30+ 框架口径）优先，其下退回 AccessibilityNodeInfo.equals。
     * 设备实证：Android 14 模拟器上 `event.source` 与树内同一逻辑节点的副本两者 uniqueId/window 皆 null，
     * equals 恒 false——所以它只能当"便宜先试"的一档，不能当唯一证据（补判据见 [locatedByContent]）。
     */
    private fun sameNode(a: AccessibilityNodeInfo, b: AccessibilityNodeInfo): Boolean {
        val wa = runCatching { a.window?.id }.getOrNull()
        val wb = runCatching { b.window?.id }.getOrNull()
        if (wa != null && wb != null && wa != wb) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val ua = runCatching { a.uniqueId }.getOrNull()
            val ub = runCatching { b.uniqueId }.getOrNull()
            if (ua != null && ub != null) return ua == ub
        }
        return a == b
    }

    private fun fingerprint(node: AccessibilityNodeInfo): String =
        "${node.className}(${runCatching { node.window?.id }.getOrNull()}," +
            "${if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) runCatching { node.uniqueId }.getOrNull() else "-"})" +
            "kids=${node.childCount}pkg=${node.packageName}text=${node.text?.toString().orNull()}"

    /** 父链直走（便宜路径）；返回 null=断裂或终点并非窗口根，不可采信。 */
    private fun walkParents(source: AccessibilityNodeInfo, roots: List<AccessibilityNodeInfo>): List<Int>? = try {
        computeIndexPath(
            source,
            parentOf = { it.parent },
            childCountOf = { it.childCount },
            indexOfChild = { parent, child -> indexOfIn(parent, child) },
            isRoot = { node -> roots.any { sameNode(it, node) } },
        )
    } catch (e: Exception) {
        null
    }

    /** 在父节点的直接子节点里按框架身份找 [child] 的文档序下标；找不到=-1。 */
    private fun indexOfIn(parent: AccessibilityNodeInfo, child: AccessibilityNodeInfo): Int {
        var found = -1
        for (i in 0 until parent.childCount) {
            val c = parent.getChild(i) ?: continue
            val same = sameNode(c, child) // 框架身份判据（uniqueId 优先，见 sameNode）
            c.recycleQuietly()
            if (same) {
                found = i
                break
            }
        }
        return found
    }

    /** 从窗口根广度遍历，按框架身份认节点，记录文档序下标路径。遍历完仍未命中=null。 */
    private fun searchPath(root: AccessibilityNodeInfo, source: AccessibilityNodeInfo): List<Int>? {
        if (sameNode(root, source)) return emptyList()
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, List<Int>>>()
        queue.add(root to emptyList())
        var visited = 0
        while (queue.isNotEmpty()) {
            if (visited >= SEARCH_BUDGET) return null
            val (node, path) = queue.removeFirst()
            visited++
            for (i in 0 until node.childCount) {
                val c = node.getChild(i) ?: continue
                if (sameNode(c, source)) return path + i
                queue.addLast(c to path + i)
            }
            if (node !== root && node !== source) node.recycleQuietly()
        }
        return null
    }

    private fun String?.orNull(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

    @Suppress("DEPRECATION")
    private fun AccessibilityNodeInfo.recycleQuietly() = runCatching { recycle() }

    private companion object {
        const val TAG = "AnytouchRun"

        /** 子树遍历预算：真实列表行子树极浅，超预算即视为"这行没唯一文字"，不做无界遍历。
         *  深度给到 8：设备实证 Settings 行容器在窗根树下第 10 层，而 title TextView 还要再深 3~4 层
         *  （row>icon_frame>text_frame>title），深度 3 时录到 texts=0 只能退 path。 */
        const val DESCENDANT_BUDGET = 32
        const val DESCENDANT_DEPTH = 8

        /** 句柄不可证时从根搜索/内容取证的预算：一层设置页通常几百节点，触顶即弃（宁弃不猜）。 */
        const val SEARCH_BUDGET = 4000

        /** 事件拷贝词"扫到 0 命中"时的转场落定重扫预算（封顶 2×120ms 主线阻塞，见 [pinEventClue]）。 */
        const val PIN_SETTLE_RETRIES = 2
        const val PIN_SETTLE_MS = 120L

        /** Android 偏好设置行的标题 view id 后缀（`android:id/title`）：子树唯一命中才采信。 */
        const val TITLE_ID_SUFFIX = ":id/title"

        fun typeOf(eventType: Int): String = when (eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> "windowStateChanged"
            AccessibilityEvent.TYPE_VIEW_CLICKED -> "viewClicked"
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> "viewLongClicked"
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> "viewTextChanged"
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> "viewScrolled"
            else -> "type$eventType"
        }
    }
}
