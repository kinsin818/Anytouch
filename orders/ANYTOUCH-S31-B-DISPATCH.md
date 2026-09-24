# ANYTOUCH | STAGE-31-B 派单：执行器三条真实判据下沉（A1/A2/A3）

> 落盘人：施工总窗口（主窗）　日期：2026-09-24
> 放行依据：`orders/RULINGS-20260922.md` 的 **S31-B1**（老板 09-24 答语原文："放行，立刻派单"）。
> 判据靶表**不重写**：本批唯一范围定义 = `orders/ANYTOUCH-S31-ORDER.md` §1 的 **A1/A2/A3 三行原文**（含"下沉缝"那一列的字面签名）。
> 硬约束与自检门同样沿用该文件 §2、§5，本文件只写"本批与 31-A 不同"的部分；重复条款以原文件为准。

## §0 本批与 31-A 的两点不同（写清楚，别让 worker 猜）

1. **动的是回放热路径**。31-A 动采集面（录的时候），31-B 动派发与复核边（放的时候）——
   `device-smoke` 那 13/13 与三台真机（K40/K80/模拟器）的历史证据全部落在这段代码上。
   所以"抽取零语义变化"这句话**必须**由逐行 diff + 设备复验共同兑付，不接受"测试全绿"单独成证。
2. **设备关不在 worker 账上**（本批特例，理由与后果照实）：主窗正在跑切片 E 的 K40 真机轮（S31-B4 排的序），
   且 31-B 的复验本身就要求模拟器全量 + K40（K80 在位则同批）。
   因此本批 worker **禁止调用 adb、禁止起/连任何设备或模拟器**，只交 JVM 四关；
   §5-5 那一关由主窗补跑并**记在主窗头上**。
   这不是放宽 §5——§5-5 原文就写了"设备被主窗占用时回报'设备关待主窗排期'，由主窗补跑并记在谁头上"；
   与 31-A 的唯一区别是：这次是**派单时就知道**设备在主窗手上，不是 worker 跑到那一关才发现。

## §1 范围（三条，一条不多一条不少）

| 号 | 判据（原文位置） | 下沉后的纯函数面（字面照 §1 表） | 本批必须锁住的东西 |
|---|---|---|---|
| A1 | `executor/NodeTaskRunner.kt:309-335` 明示拒绝后"沉降 → 按原线索重定位 → 重派，仍 false 才收 `perform_failed`"，封顶恰好一次 | `redispatchPlan(performed: Boolean, type: ActionType, attempt: Int): Redispatch`（`Skip`/`RetryOnce`/`GiveUp`） | ① 只在"明示拒绝且非 WAIT"时才 `RetryOnce`；② `attempt` 到 1 即 `GiveUp`——**无循环**是这条判据的一半，必须有用例锁"第二次仍 false 不再重派"；③ `RetryOnce` 与 `GiveUp` 不得互换落点（`false` = 动作没执行过、线索未消耗、无输入落盘，所以这一次重派是零副作用自证——这句话在用例注释里要说清是**为什么允许**，不是装饰）；④ **动作派发本身不许搬进纯函数**（假下沉：把 `performAction` 塞进签名等于把 Android 带过去）。 |
| A2 | `NodeTaskRunner.kt:300-306, 325-331` 的 `setText(t) \|\| pasteText(t)` 派发边（雷 12 的对称面） | `textDispatchRoute(setTextOk: Boolean, pasteOk: Boolean): Boolean` | ① 真值表四格锁死"两通道各自与合计"：SET_TEXT **虚报 true** 与 **明示拒 false** 两种失败形态都必须让 PASTE 上场；② 另锁一条**反向**：`dispatched == true` 不得被当成"已落字"（这条在 A3 的出口测，A2 里只锁"成败终裁不在这里"）。 |
| A3 | `NodeTaskRunner.kt:443-464`（`awaitLanded`）复核以**派发句柄活读**为准，句柄读不到才扫全树兜底，兜底匹配键=**输入本身**且须 `className` 含 `EditText` **且** `text` 含输入 | `landedViaTreeScan(nodes: List<LandedNodeFact>, want: String): LandedNodeFact?`，`LandedNodeFact(className: String?, text: String?)` 在本文件内声明为最小数据类 | ① 正例：`EditText` 含词=判落；② **反例四条一条不许少**（§1 表点名要 worker 逐条锁）：`TextView` 含同词（搜索结果列表）不得判落／`want` 为空白不得短路成命中／`className` 为 null 不得命中／`text` 含词但首尾有空格、trim 后才等必须**仍**命中；③ **不许**新造公共 `UiNode` 抽象（越界=改契约面）。 |

**不做**：`OverlayUi`/窗口系统类（恒真）、未被调用的 Runner 壳子、`safety/` 与 `RejectionReason` 枚举、`core/` 契约、
`recorder/session/`（STAGE-22 已验收）、`AnyNode` 路径编码与空白 resourceId 判据（STAGE-21 已验收）。
A4/A5 已在 31-A 结案（commit `db7d90c`），本批**不得顺手改** `CaptureClues.kt`——要改就在自述 §6 里登记理由。

## §2 交付与验收差异

- 工作树：**`D:/Anytouch-31b`**（主窗建的 git worktree，基线=commit `db7d90c`）。**不在主树 `D:/Anytouch` 里写**，
  主树这段时间由主窗跑切片 E 与后续集成。workers 永不 `git commit`/`push`（集成权在主窗）。
- 自述文件：`evidence/S31/stage31-b-worker-report.md`（写在**你的工作树**里，主窗取回），
  段落结构照 31-A 那份：§1 改了哪些文件每处一句、§2 搬移前后判据对照（原行号 → 新纯函数名逐条）、
  §3 四关命令 + **原始输出粘贴**、§4 mutation 自查三列表、§5 与本文件的偏差、§6 未完成项与原因（含"设备关未跑=按 §0-2 交主窗"）。
- JVM 基线：**363 起跳**（app 285 / byok 59 / contracts 19，`--rerun-tasks` 后单变体分模块 `<testsuite tests>` 求和；
  禁目录 glob、禁日志行数）。总数下降=本批有测试被删或失效，直接视为未过。
- 红线：`bash scripts/ci-local.sh`（九线 A–I）与 `bash scripts/redline-probe.sh` 双 RC=0，后者仍须逐条能 FAIL。
  新增判据文件保持 **android-free**（不许 import `AccessibilityNodeInfo`/`AccessibilityEvent`/`Context`），
  并沿用红线 H 的字面口径：平台侧日志调用点若新增，必须写成 `Log.x(` 紧跟左括号那一档（旧写法匹配不上=假锁）。
- 验收由主窗独立做（§4 五关）：逐行 diff 读"搬移非复制"（原位置只剩取数与转调）→ 独立重跑 JVM → 双红线 →
  **设备关**：模拟器全量（device-smoke / ui-smoke / s2-smoke 录→编→放）+ K40 真机 byok-smoke 与 device-smoke 复验。
  31-B 若改了派发边，模拟器读数**不能**冒领真机结论（`evidence/S2/t3-k40-first-contact.md:47` 原文："真机不适配模拟器脚本口径"）。
