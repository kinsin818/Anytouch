# ANYTOUCH | STAGE-31 派单：平台层真实判据下沉（S3-R1 点名的 A 档）

> 落盘人：施工总窗口（主窗）　日期：2026-09-23　依据：`orders/RULINGS-20260922.md:60`（S3-R1 原文）
> **本文件性质=生效派单原文**。worker 与主窗一切争议以本文件字面为准；本窗此前引的"≥270 例"（S2-R2）已作废。

## §0 为什么先有这张表（不写这段就是虚靶）

S3-R1 裁定"按真实判据数记账、不设 270 硬阈值，任务 #38 范围改为**审计点名的 A1–A5**"。
主窗落盘前复核磁盘：`grep -rn "A5" --include=*.md orders evidence docs` → **全仓只有 RULINGS 那一行缩略语**
（"假边重派、awaitLanded 反向半边、采集层两判据"），**逐条审计表从未落盘**。
这与 S2-R1 结的那笔流程债同型（`orders/ANYTOUCH-S2-ondevice-DEVIATIONS.md` A1：条款只在会话里、
独立验收方只能判"虚靶"）。所以本批第一步不是写测试，是**把靶子钉在磁盘上**：§1 五行每条都带
`文件:行` + 原判据一句话 + 为什么现在 JVM 覆盖=0 + 下沉缝 + 正反例。
§1 的行号与代码事实**全部出自主窗本轮实读**（`NodeTaskRunner.kt:293-335/351-424/443-464`、
`CaptureBridge.kt:110-160/208-240/546-600`），不是回忆。

## §1 A1–A5 审计表（主窗落，worker 不许改判据语义）

| 号 | 判据住在哪儿 | 原判据一句话（磁盘实读） | 为什么 JVM 覆盖=0 | 下沉缝（本批唯一允许的改法） |
|---|---|---|---|---|
| **A1** | `executor/NodeTaskRunner.kt:309-335` | 派发被**明示拒绝**（`performed=false` 且 `type != WAIT`）时：沉降 `settleMs*2` → 按原线索重定位一次 → 重派；**仍 false 才收 `perform_failed`**。`false` 的含义=动作根本没执行过、线索未消耗、无输入落盘，故这一次重派是**零副作用自证**，且**封顶恰好一次**（无循环）。 | `runStep` 整条挂在 `device`/`locator`/`killSwitch` 与 `delay()` 上，JVM 里没有活树也没有协程调度器 | 把"该不该重派 + 派第几次"抽成纯函数：`fun redispatchPlan(performed: Boolean, type: ActionType, attempt: Int): Redispatch`（`Skip` / `RetryOnce` / `GiveUp`），平台侧只在 `if` 那几行调它。**动作派发本身不许搬进纯函数**（那是假下沉：把 `performAction` 塞进签名等于把 Android 带过去） |
| **A2** | `NodeTaskRunner.kt:300-306, 325-331` | `TYPE_TEXT` 的派发边是 `setText(t) \|\| pasteText(t)`：SET_TEXT **虚报 true** 与 **明示拒 false** 两种失败形态都要让 PASTE 上场（雷 12 的对称面），且**成败终裁不在这里**——`dispatched=true` 只代表"被接收"，落字与否由 A3 判。 | 同 A1（`device.setText` 是平台调用） | 与 A1 同一个纯函数面：`fun textDispatchRoute(setTextOk: Boolean, pasteOk: Boolean): Boolean`（真值表锁死"两通道各自与合计"），另锁一条**反向**：`dispatched==true` 不得被当成"已落字"（这条在 A3 的出口测） |
| **A3** | `NodeTaskRunner.kt:443-464`（`awaitLanded`） | 复核以**派发句柄活读**为准；句柄读不到才扫全树兜底，兜底匹配键=**输入本身**（不是原线索），且**必须同时**满足 `className` 含 `EditText` **且** `text` 含输入 → 才算"真落字"。正向半边（AVD 实证：过渡后节点换新，字落进新框）已有设备证据；**本批补的是反向半边**。 | 同上；且 `device.root()` 返回平台节点流 | 抽 `fun landedViaTreeScan(nodes: List<LandedNodeFact>, want: String): LandedNodeFact?`，`LandedNodeFact(className: String?, text: String?)` 为本文件内声明的最小数据类（**不许**新造公共 UiNode 抽象）。平台侧只做"把活树摊平成这个列表"，判据一行不留。**正例**：`EditText` 含词=判落。**反例四条**（ worker 必须逐条锁）：① `TextView` 含同词（搜索结果列表）不得判落；② `want` 为空白不得短路成命中；③ className 为 null 不得命中；④ text 含词但被 trim 后才等（首尾空格）必须仍命中。 |
| **A4** | `recorder/capture/CaptureBridge.kt:546-600`（`descendantClue`） | 被点节点自身无线索时向**子树**求唯一线索：① 子树内恰好一个 `android:id/title` 文本→取它；② 否则**子树文本集唯一**才取；③ 无文本时子树 `contentDescription` 唯一才取；④ **遍历触顶（`DESCENDANT_BUDGET`）或不唯一→弃用并留痕、退回 path**。原则：防滑轨=绝不编造线索（宁要脆的 path，不要可能指向别行的 text）。 | 参数与返回值是 `Pair<String?, String?>`（已够纯），但取数直接走 `AccessibilityNodeInfo.getChild/recycleQuietly` | 树走形与判据分离：`fun descendantClueOf(root: ClueNode, budget: Int, depth: Int): CluePick`，`ClueNode(text, desc, isTitleId, children)`。**判据必须整体搬走**（不许留"唯一性判断"在平台侧）。锁死五例：单 title / title 两个（歧义→弃）/ 无 title 但文本唯一 / 文本歧义但 desc 唯一 / 触顶截断→弃。`recycleQuietly` 归平台侧，纯函数**不得**接节点句柄。 |
| **A5** | `CaptureBridge.kt:110-160, 208-240`（`pinEventClue` + 雷 15/17 两条豁免边） | 事件自带 `text`/`desc` 时钉字段**按活树同口径、不交叉拷贝**（框架会把 `contentDescription` 抄进 `event.text`，照抄即回放 `L2 NO_MATCH`）；雷 17 半边：`src=null` 且重扫仍 0/0 时，只给 **`event.contentDescription` 直拷豁免**一级免窗口验证（text 侧**不给**豁免），否则该步必须**显形失踪**而不是静默不进会话。 | 与事件流 `AccessibilityEvent` 绑定（取 `pkg`/`className`/时间戳） | `fun pinEventClueOf(eventText: String?, eventDesc: String?, treeTextHits: Int, treeDescHits: Int, allowDescExemption: Boolean): CluePin`（枚举 `ByText`/`ByDesc`/`Ambiguous`/`Dropped`）。锁死：**text 侧永不走豁免边**（A5 最严的一条，锁的是雷 15/17 的分界）；0 命中与 >1 命中不得走同一条落点；`Dropped` 必须可被上层观测到（不得返回 null 让调用方猜）。 |

## §2 硬约束（worker 面，违反即拒收）

1. **不许 git commit / push**（集成权在主窗）。交付=工作树 + 一份 `evidence/S31/stage31-*.md` 自述（含你跑了什么命令、原始输出）。
2. **行为保全式抽取（extract, don't duplicate）**：判据从平台侧**搬进**纯函数、调用点转调它。
   **禁止"镜像一份 + 原逻辑保留"**——两函数两种实现，只有坏的那条会被看见（雷 18 同族，本仓已付过学费）。
   验收方式：主窗逐行 diff 搬移前后，判据分支必须**一一对应**，`git diff` 里原位置只剩调用点。
3. **不许动冻结语义**：`core/` 契约、`recorder/session/`（STAGE-22 已验收）、`AnyNode` 路径编码与空白 resourceId 判据、
   安全阀（`safety/`）、`RejectionReason` 枚举。
4. **禁恒真断言**：不许出现"构造一个对象再断言它不是 null"这类用例（RULINGS S3-R1 原文点名的凑数形态）。
   每条用例必须能因**判据被改坏**而红；自查方式=对每条判据做一次反向改动（翻转一个布尔/去掉一个条件）跑一遍，
   不红的用例不算用例，写进自述里。
5. **JVM 计数口径**：只报 `--rerun-tasks` 后**单变体分模块** `<testsuite tests>` 求和；禁目录 glob、禁日志行数。
6. 红线九线（A–I）必须仍然 PASS，且 `scripts/redline-probe.sh` 仍逐条能 FAIL。新增文件不得把网络字样带进 `app/src/main`
   （字面扫描会把 `:byok` 公开 API 名也算到调用方头上，见 `evidence/S3/slice-d-app-wiring.md` §4-1）。

## §3 本片范围与分批（为什么只有 A4/A5 现在能动）

- **31-A（本批，可立即派）**：A4 + A5，住 `recorder/capture/CaptureBridge.kt`——采集面、非回放热路径，
  抽取后回归成本主要是 JVM + 既有录→编→放设备链（脚本已固化，不新增设备动作）。
- **31-B（**待老板一句话**）**：A1/A2/A3，住 `executor/NodeTaskRunner.kt`——这是让 device-smoke 13/13 与
  三台真机证据成立的那段代码。**风险与代价照实报**：抽取本身零语义变化，但任何一次对派发/复核边的触碰
  都必须重跑模拟器全量 + K40/K80 复验才配得上"没改坏"这句话，而真机窗口目前还被 K40 的连接问题占着。
  主窗倾向：做（这是全仓最后一条大 JVM 盲区），但**排在切片 E 收口之后**，不与 E-key 抢设备。
- **不做**：`OverlayUi`/窗口系统类（JVM 天然覆盖不到，写了也是恒真）、未被调用的 Runner 壳子。

## §4 验收（主窗独立做，不听口头）

1. `git diff` 逐行读：判据是否**搬移**而非复制；平台侧是否只剩取数与转调。
2. 独立重跑：`./gradlew :app:testDebugUnitTest --rerun-tasks` → 记 app/byok/contracts 分模块真数，与自述对得上才算数。
3. `bash scripts/ci-local.sh`（九线）+ `bash scripts/redline-probe.sh` 双 RC=0。
4. `bash scripts/device-smoke.sh` + `bash scripts/ui-smoke.sh` 同构建零回归（A4/A5 动了采集面，必须重跑录→编→放链）。
5. 交付账进 `orders/METRICS.md`（一次通过记一行，返工记一行，类别照实）。
