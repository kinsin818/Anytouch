# STAGE-31-B 主窗独立验收（模拟器半边）：A1 + A2 + A3 执行器判据下沉

验收依据：`orders/ANYTOUCH-S31-ORDER.md` §4（五关，主窗独立做，不听口头）+ §5 自检门 + `orders/ANYTOUCH-S31-B-DISPATCH.md` §5-5（设备关 = **模拟器全量三支 + K40 真机 byok-smoke 与 device-smoke 复验**）
被验交付：`ExecutorDecisions.kt`（新增，A1/A2/A3 判据唯一住处）+ `NodeTaskRunner.kt`（68 增 / 46 删，原三条判据分支删净、只剩取数与转调）+ `ExecutorDecisionsTest.kt`（新增 20 例）+ `NodeTaskRunnerTest.kt`（25→26，+1 条 WAIT 队列级锁）
worker 自述：`evidence/S31/stage31-b-worker-report.md`（只读，基线 HEAD `db7d90c`；三次派工——两次服务端切断、第三次交付）
主窗验收时基线：`db7d90c`（31-A 已 ACCEPTED 入树）；本批与 31-A 在 `recorder/capture/` 与 `executor/` **零交集**，diff 直接可用。
日期：2026-09-24（本机 04:0x–04:4x UTC）

## 结论：**代码 + JVM + 模拟器设备关 = 全绿；K40 真机半边未跑 → 本批暂不翻 ACCEPTED**

- 判据搬移本身无返工；worker 交付质量：§7 主动更正前任三处虚指、§5 老实登记 3 条待裁事实、§6 明写"测试全绿单独不成证、设备关欠在主窗头上"。
- **本批唯一"红"是设备读数层（主窗自己），且已归因 + 复现清态即绿**（见 §4）。
- **翻 ACCEPTED 的最后一格 = K40 真机 device-smoke/byok-smoke 复验**（老板手，任务 #50）——理由见 §5：K40 的雷12「持久明示拒绝」是模拟器复现不了的档，恰是本批 A1 封顶重派要兑付的那条边，不能拿模拟器绿冒顶。故本次先落**集成 + 模拟器关**，ACCEPTED 待 K40。

---

## §1 五关逐关（每关给磁盘事实，不给"我看过了"）

| 关 | 判定 | 磁盘事实 |
|---|---|---|
| §4-1 `git diff` 逐行读：搬移非复制 | **过** | A1：原 `if (!dispatched && type != WAIT)` + `if (!dispatched) return perform_failed` 两处判据删净，平台只剩 `while(true){ when(redispatchPlan(...)) }` 三档动作 + `attempt += 1` 自增（不自己数到顶）；沉降/重定位/`performAction` 派发留平台侧（假下沉禁令）。A2：两处 `setText \|\| pasteText` 合计交给 `textDispatchRoute`，**短路"何时真调 PASTE"留平台侧**（见 §2③）。A3：`if (want.isNotEmpty())` + `firstOrNull{EditText && contains(want)}` 整体进 `landedViaTreeScan`，平台只剩 `device.root()?.preOrder()?.map{LandedNodeFact(...)}` 摊平取数。一条判据分支不留在平台侧 |
| §4-2 独立重跑分模块真数 | **过** | **主树** `--rerun-tasks` 单变体 XML 求和：app **306** / byok **59** / contracts **19** = **384**，failures=0 errors=0 skipped=0；与工作树逐格相同（文件 sha256 逐字节一致），基线 363 → +21（`ExecutorDecisionsTest` 20 例 + `NodeTaskRunnerTest` 25→26 一条） |
| §4-3 九线红线 + 探针双 RC=0 | **过** | **主树** `bash scripts/ci-local.sh` → `==> [4/4] ci-local PASS` CI_RC=0（红线 A–I 逐条 clean）；`bash scripts/redline-probe.sh` → PROBE_RC=0，F/G/H/I **逐条能 FAIL 且撤探针回 PASS** |
| §4-4 mutation 自查（worker 侧，主窗采信前提=§4-2/§4-3 独立过） | **过** | worker 自述 §4：13 根探针逐条能红、撤改后文件 sha256 回到 `0007e0ff…`（主树拷来的 `ExecutorDecisions.kt` sha256 前缀即 `0007e0ff` ⇒ 还原版无残留污染）；20 例每条至少进一次红名单 |
| §5-5 设备关 | **模拟器半边过 · K40 半边待接机** | 见 §3/§4 |

## §2 读码结论：三条"看着像改了语义"的逐条钉死

**① A1 `while(true)` 改写 vs 原两个 `if`**：原判据 = "首派 false 且非 WAIT → 沉降+重定位+重派一次 → 仍 false 收 perform_failed"。新循环在 `RetryOnce` 里重派一次并 `attempt=1`，下一圈 `redispatchPlan(_,_,1)` → `attempt(1) >= MAX(1)` → `GiveUp` ⇒ **封顶恰好一次**住在常量 `MAX_REDISPATCH_ROUNDS=1`（`ExecutorDecisions.kt:34`）唯一一处，平台不再隐式数轮。WAIT 档：见 §2④。

**② A1 WAIT 不可达（D1）**：`runNodeStep` 只有一个调用点（`NodeTaskRunner.kt:109`），WAIT 在 `run` 里走 `delay` 分支、从不派发。故基线那句"WAIT 若抵达则 perform_failed"与新码"WAIT 抵达 → Skip → 走成功"**只在不可达输入上分叉**，可达输入零差异。worker 自己复核了这点并加队列级锁 `wait步在任何设备拒答下也绝不产perform_failed` 钉死 ⇒ **接受，不动代码**（裁决 S31-B7-D1）。

**③ A2 短路留平台侧 = 正确，不是没搬干净**：把"setTextOk 为真时是否还调 PASTE"写进纯函数=要求平台**无条件**派发 PASTE，那才是语义变化（setText 已接收时不该再贴一次）。合计判据（两通道任一被接收即 true）已在 `textDispatchRoute = setTextOk || pasteOk`（:73）；"何时真去派发"是设备副作用调用、必须留平台侧。**另锁一条反向**：`dispatched=true` ≠ 已落字，成败终裁仍在 A3 复核边（用例 `textDispatchRoute(true,false) 不代表已落字`）。

**④ A3 `want.isBlank()` 早退 vs 原 `want.isNotEmpty()`**：`want = input.trim()`，trim 后"空"与"空白"同集，故 `isBlank()==isEmpty()==!isNotEmpty()`，等价（S31-B6 已钉）。纯函数内多一次 `want.trim()`（:108）系母单 §1 A3 反例④要求"纯函数自身扛未 trim 输入"，幂等、非镜像（D3，非问题）。

**§5-D6/D7 类比（31-A 那两条反向验证本批无对应项）**：A3"句柄活读优先"半边（`device.textOf`→refresh，:449-450）按 B6 明令**未搬**（那是活读设备句柄的取数，不是判据），worker 未动这条，逐行 diff 复核确认。

## §3 设备关——模拟器全量（一支构建、一台 emulator-5554）

同一枚 31-B 构建（`app/build/outputs/apk/debug/app-debug.apk`，晚于全部 31-B 源文件；sha 与主树所集成源逐字节一致）`adb install -r -t`（未 force-stop，无障碍绑定装前装后都核 = `com.anytouch.app/.service.AnytouchAccessibilityService`）：

| 脚本 | 结果 | raw |
|---|---|---|
| device-smoke | **13/13 ALL PASS** RC=0（C2 经典 EditText、C3 Compose 自目标 ok=1/1 = A2/A3 边的活证据） | `raw/device-smoke-31b-emulator.log` |
| ui-smoke | **41 PASS / 0 FAIL / 0 SKIP** RC=0（首轮 36/RC=1，见 §4） | `raw/ui-smoke-31b-emulator.log` |
| s2-smoke 录→编→放 | **ROUNDS=10 → 10/10 全绿 ratio=1.00 + SC 一次慢拖只成一步(actions=1/events=3)** RC=0 | `raw/s2-smoke-31b-emulator-rounds10.log` |

## §4 本批唯一"红" = 设备读数层（主窗自己），已归因 + 清态复现

- **首轮 ui-smoke 36 PASS/RC=1、首轮 s2-smoke `FAIL SC 一次慢拖`（实际 0 步）/RC=1**，同一时刻点（紧接 device-smoke + ui-smoke 连跑之后）。
- **归因（不是判产品的罪，也不是编故事）**：两条红都在**采集/页面态前置**，非执行面——
  1. 31-B 的 diff 经 `git diff --name-only db7d90c..` 证明**只含 `executor/` 四个文件**，`recorder/`、`capture/` 一行未动；SC 慢拖与 ui 首轮红是录制/采集面断言，一个零录制改动的 diff 不可能引入。
  2. SC 尸检：三条 `viewScrolled 无位移证据 delta=0x0（布局重排，不成步）` + 拖后 dump 见 `Search settings` 在首屏 ⇒ 那一拖**列表未真位移**，采集层按雷18口径正确地一条不成步（设计行为非漏事件）；`home_to_top` 复核在连跑负载下未把页面摆到可拖出位移的态。
  3. **同款签名早于本批**：`db7d90c`（31-A 收口）自陈"首轮 36 PASS/5 FAIL、同 APK 同脚本零改产品代码复跑 41/0，确切机制仍未坐实"——即"紧接 device-smoke 后首轮 ui/s2 采集前置红、清态复跑即绿"是既有旧现象。
- **复现证明**：把 Settings `am start` 回根页后 `ROUNDS=2` 复跑 → SC `actions=1/events=3` PASS、2/2 全绿 RC=0（`raw/s2-smoke-31b-emulator-sc-repro.log`）；再 `ROUNDS=10` 全量 → 10/10 绿 RC=0；ui-smoke 复跑 41/0/0 RC=0。
- 诚实边界：**"连跑负载如何具体导致 delta=0"的确切机制仍未逐帧坐实**，按"未归因读数不得判产品的罪、也不得拿归因当已证"两条同时成立——本轮只主张"它不由 31-B 的执行面改动引起"（§4-1/1、2 已足），不主张已彻底解释该采集现象；旧账 31-A 同样挂此未坐实项。

## §5 翻 ACCEPTED 前欠的那一格：K40 真机复验（为什么模拟器不能冒顶）

派单书 §5-5 设备关字面含"**K40 真机 device-smoke 复验**"。本批 A1 = 明示拒绝后的**封顶重派**，而 K40/MIUI 搜索框的雷12是**持久**明示拒绝（`performAction` 恒 false），与模拟器上"沉降即自愈"的瞬态拒不同——
- 模拟器能证：瞬态拒 → 重派一次即落字（C2/C3 已过）、且不第三派（JVM A1 测锁）。
- 只有 K40 能证：**持久拒 → 恰好重派一次 → 诚实收 perform_failed，绝不无限重派**（封顶常量在真机持久拒下的实际行为）。
这正是"抽取零语义变化不接受测试全绿单独成证"（派单 §0-1）点名要真机兑付的那条边。故 **ACCEPTED 挂起至 K40 device-smoke 复验过**；该复验与切片 E 的 E11 同卡老板的手（任务 #50：一次接机同时收两批）。

## §6 主窗三条裁决（源自 worker 自述 §5，落 `RULINGS` S31-B7，老板可覆）

见 `orders/RULINGS-20260922.md` 的 S31-B7 段（D1 接受 / D2 不改登记诚实边界 / D3 非问题 + 更正 B6 的"113 行"为实测 112）。
