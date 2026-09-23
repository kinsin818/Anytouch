# S2 实测层首批：录 → 编 → 放 设备端闭环（军令 ANYTOUCH-S2-ONDEVICE L0）

窗口：主窗（施工总窗）｜日期：2026-09-23（设备时钟 02:3x–04:2x UTC）｜设备：`emulator-5554`
（MarvisPhone / sdk_gphone64_x86_64 / Android 14 / 1080×2400）｜构建：`:app:assembleDebug` 本批最终产物

## 1. 军令 L0 对账

| 判据 | 结果 | 证据 |
|---|---|---|
| 悬浮球开录 | ✅ | `raw/record-ball-101945.png`（绿色"●开录"球压在 Settings 之上）+ §5 |
| 人工操作设置 App | ✅ 3 步链 ×10 轮 + 8 步链 ×1 轮 | `raw/s2-matrix-*.log`、`raw/s2-chain8-*.log` |
| 停止 → RecorderCompiler 出步骤 JSON | ✅ `S2SMOKE-TASK` 产物原样回注（不经任何手写模板） | 同上 |
| 一键回放 | ✅ 录出的 JSON 直接进 S1 执行链 | `S1SMOKE ok=N total=N stopped=false` |
| 全程零模型零网络 | ✅ ci-local 红线 A/C 覆盖路径零网络关键字；本批未接任何编译期模型 | §7 |
| 回放成功率 ≥90%（10 轮，模拟器口径） | ✅ **10/10 轮全绿（ratio=1.00）** | `raw/s2-matrix-20260923-1213.log` |

## 2. 达标矩阵（本批收口构建：`79b6f82` 之后的工作树，含本批采集面三修 + 测试通道收口）

- **10 轮录→编→放（3 步链）：10/10 全绿**，每轮 `S2SMOKE compiled ok actions=3 drops=0 merged=0` →
  录出 JSON 原样回注 → `S1SMOKE ok=3 total=3 stopped=false stop=-`。成功率 100% ≥ 军令 L0 的 90%。
  证据 `raw/s2-matrix-20260923-1213.log`（含同批 SC 滚动回归锁 PASS）。
- **SC 滚动采集（一次慢拖只成一步）**：`compiled ok actions=1 merged=0` → `scroll resource_id=main_content_scrollable_container`，回放该步 ok。
- **8 步链（含 4 次返回键、1 次 "Pair new device"）：8 录 → 8 编 → 8 放全绿**，
  `actions=8 drops=0 merged=0 events=19` + `S1SMOKE ok=8 total=8 stopped=false`；
  步序词汇逐条为 `text`/`content_desc` 线索，**零步降级成 `path`**（雷 15/16/17 三条修完后本批最终形态）。
  证据 `raw/s2-chain8-20260923-1213.log`（`S2_VERBOSE=1` 整轮采集面日志）。
- **JVM 全套**：`:app` 258 + `core/contracts` 19 + `tools/compiler` 12 = **289 例全绿、零失败**；`scripts/ci-local.sh` 四道红线 PASS。
- **同夜四台 AVD 未复跑**：本批只改测试通道脚本 `scripts/s2-smoke.sh`（雷 16/17 之后的收口构建），
  未再跑三档矩阵——收口矩阵的跨档位复验记在 T3 复验项，不外推为"四台全绿"。

## 3. 交付面（全在主窗窄口，worker 未参与）

| 文件 | 职责 |
|---|---|
| `recorder/capture/RawNodeSnapshot.kt`（新） | 采集层最小读形：无坐标字段（军令 L1），滚动量只带"方向+位移+上下限"事件语义 |
| `recorder/capture/CaptureAdapter.kt`（新） | 纯 JVM 采集适配器：targetPkg 白名单/自家包屏蔽/打字折叠/无位移滚动拒收+留痕；`CaptureAdapterTest` 17 例 |
| `recorder/capture/CaptureBridge.kt`（新） | 薄桥：`AccessibilityEvent` → `RawNodeSnapshot`。五型事件映射、句柄取证、线索钉字段、留痕归因（**JVM 覆盖不到，只能设备证**） |
| `recorder/session/RecorderStore.kt`（新） | 会话仓库：开录/停止/编译、折叠账（`foldedAway`）与编译账分离、`compiledActions`/`suggestedTaskJson` 出口 |
| `service/AnytouchAccessibilityService.kt` | 采集钩子（仅 RECORDING 期开工、runCatching 留痕）、roots 提供（空帧重试 3×80ms）、录制球订阅 |
| `service/OverlayUi.kt` | 录制开关球（与停止球分居两侧、执行期收起） |
| `MainActivity.kt` | 录制面板（targetPkg 输入 + 开录/停止 + 建议任务回填）与 adb 触发口 `record_start/record_stop/session_json/task_json` |
| `res/xml/accessibility_config.xml` | 事件型补 `typeViewClicked/LongClicked/TextChanged/Scrolled`；显式不配 `typeWindowContentChanged`（洪流淹没 2000 条预算）；`flagRequestInitialAccessibleContent` AAPT 不收（写了直接 linking failed） |
| `scripts/s2-smoke.sh`（新） | 测试通道：预走取坐标→录制期零 dump→编译产物回注→逐轮回放断言；红项随行明细 + `S2_VERBOSE=1` 全量采集日志 |

冻结层零修改：**worker 交付的四件**（`RecorderCompiler`/`RecorderSession`/`NodeTreeLocator`/`RecorderEditing`）一行未动，
本批新建的 `capture/` 三件（`RawNodeSnapshot`/`CaptureAdapter`/`CaptureBridge`）全是主窗自己的采集面，
本批全部口径冲突都以"读冻结语义 + 在薄桥侧取证"解决（详见 §4 与军令对照记录）。

## 4. 设备实证：事件源不可信到什么程度（本批全部设计的前提）

同一轮点击里，`event.source` 的四种形态全部真实出现（`raw/s2-lossprobe-*.log`、`raw/s2-chain8-*.log`）：

1. 完整句柄：`src=LinearLayout/null/null`，子树能读到 `android:id/title` → `descendantClue` 直接成线索；
2. **残缺拷贝**：`kids=1/2` 而子树一个字都取不到、父链 `walkDepth=0`（顶端却声称有父）；
3. **null 句柄**：`src=null/null/null`，事件字段仍在（返回键 4 次里 2 次如此）；
4. 句柄身份判据全灭：跨拷贝 `equals` 恒 false（uniqueId/window 皆 null），`getRoot` 顶端对不上窗根，
   但**父链能一路走到顶**（`walkDepth=10`）——所以路径只作去抖/折叠键，绝不当回放定位词汇。

活树侧另有两笔：点击的处理函数常在我们扫描**之前**就把页面换掉（`want=[Bluetooth]` vs 活树
`[Bluetooth, Use Bluetooth]`）；转场进行中 `roots` 会返回"还没长全"的一帧（FrameLayout kids=1、零文本）。

## 5. 悬浮球录制开关（军令 L0 原判据）

- 球位实测（不猜坐标）：绿框像素 `24,1212→192,1314` → 球心 **(108,1263)**；录制态红框 `24,1212→200,1314`。
- 状态由 `RecorderStore.activeSession` + `AppState.running` 合成：执行期**收起录制球**——
  否则录进去的是执行器自己的手（球点击虽被 `pkg==selfPkg` 挡在采集之前，执行动作本身会入会话）。
- 与停止球互不遮挡：录制球 `Gravity.START or CENTER_VERTICAL, x=24`，停止球沿用右侧停靠位。
- 挂不上球不拒绝开录（录制无安全后果，主窗按钮仍是通道），但 `addView` 失败写 W 痕。

## 6. 本批挖出的雷（采集面，全部设备实证）

**雷 15｜假线索：事件拷贝词的字段错位。** 冻结执行器只按 `text`/`content_desc` 两字段分别 trim 全等检索，
而框架会把节点的 `contentDescription` **抄进 `event.text`**。照抄事件字段 → 8 步链第 4 步
`L2 NO_MATCH text='Navigate up'`（活树里该串只在 desc 字段），`ok=3 total=4 stop=NODE_NOT_FOUND`。
修：`pinEventClue` 用活树按"与回放同口径、不交叉字段"裁断该词该记成 text 还是 desc；两边都不唯一 → 弃（宁缺不错点）。

**雷 16｜0 命中帧被当成歧义证据。** 上条修好后矩阵仍出现 18 轮 6 轮首步降级成最脆的 `path` 词汇：
钉字段时扫到的是"新页树还没长全"的那一帧（`text 命中=0 desc 命中=0`），0 命中不等于歧义，
却被当成"这词不在窗口里"→ 唯一可用的线索被扔掉。修：0/0 帧转场落定重扫封顶 2×120ms
（与 roots 空帧重试同口径，主线阻塞上界 240ms）；命中>1 是真歧义，重扫不改结论直接弃。

**雷 17｜无句柄 + 转场后无证据 = 整步失踪。** 8 步链里 2 次返回键的 `src=null`，
一次靠活树钉成 desc 线索、一次连扫 3 帧都 0/0 → `capture skipped` → **录 8 步只出 7 步**
（回放 `ok=7 total=7` 全绿＝假绿的近亲：步数账替它兜住了，红在"录到 7 步 ≠ 人工 8 步"）。
修：只补一级免窗口验证的证据——`event.contentDescription` 是框架从被点节点该字段**直拷**
（既不像 `event.text` 会串抄 desc，也不像它会被整列表子树聚合），字段归属无需活树裁断，
故重扫后仍 0/0 且事件自带 desc 时按 desc 入流。text 侧不给这条豁免（字段归属正是它出过错的地方）。

三条共同的教训：**"这一下没录上"必须是可见的步数账，不是日志里的一行 W**——
测试通道因此加了 ①步数账断言（录到 N≠人工 N 即 FAIL）②`S2_VERBOSE=1` 整轮采集面全量日志
（tail-8 只看尾部，丢的是第一下时它一行都不留，R4 漏录最初就是这么找不着的）。

### 测试通道同批三件（不是产品雷，但同样只可能靠设备暴露）

- **坐标学到"大容器"**：预走阶段 `find_node_center` 原本取"文档序第一个匹配"。设备实证 Settings
  **二级页**顶栏 `collapsing_toolbar` 自带 `content-desc="Connected devices"`、bounds `[0,0][1080,598]`
  （占屏 25%），而首页真行是 `[189,1055][636,1126]`（0.9%）。页面一旦被残手留在二级页，
  "唯一匹配"就是那条空区 → 手指学到 (540,299) 什么也点不开，而下一步 "Connection preferences"
  在二级页照样找得到 → 整轮页面级联错位却一路"学到坐标"（录 8 步只出 2~3 步，还串出 `search_action_bar`）。
  修两条：① 取**面积最小**的匹配节点；② 面积 >300,000px（1080×2400 实测口径，整行卡片 ≤270k）一律拒收，
  宁可让预走响"没找到 [X]（测试通道未就位，不计产品失败面）"，也不学一个假坐标下去。
  离线对拍三例（同树含头图+真行 / 只剩头图 / 无匹配）→ `412 1090` / 空 / 空，与历次绿轮落点逐字一致。
- **预置后焦点自证（本条同批自我修正两次，最终形态见下两条）**：`reset_settings_home` 强置后轮询 `mCurrentFocus`
  落在目标 App 才算就位；不在则再强置一次。
- **假红 ①：焦点串解析漏了 userId 段**。`dumpsys window` 的窗体写作 `Window{hash u0 pkg/cls}`，
  而 sed 只剥了一段 token，结果留下 `u0 com.android.settings/...` —— `^$TARGET_PKG/` **永远不匹配**，
  于是每次预置都白做一次 force-stop 冷启（页面时序整体后移，预走学不到后面的行）。
  改为按 `pkg/cls` 整段取值；`wait_focus` 用的是子串匹配所以一直没暴露——**同一条判据在两个函数里两种实现，只有假红的那条会被看见**。
  另外该断言原本是单次读：按 BACK 的转场瞬间 `mCurrentFocus=null`，单读必假红，改成 10 秒轮询到落定。
- **雷（测试通道侧，但只有设备能报）：Settings 会"恢复上次页面"**。force-stop 之后 `am start -n com.android.settings/.Settings`
  **不保证开在首页**——设备实证直接开在上次看过的二级页（window class = `.SubSettings`，当时是"打印服务"页）。
  后果与上一颗大容器假坐标同形：首页独有的行学不到（响亮报"预走阶段没找到"），或二级页同名行学成假坐标（级联错位）。
  修：预置后按 window class 判身份，只在 `SubSettings`/`SettingsIntelligence` 两种串上按返回键回首页（封顶 8 次，
  别的 ROM 若不叫这名字就一次都不按，行为与改前一致），再下拉两格把首页滚回顶部。
  验证：同构建同链改前 `0/1 轮`（预走第 6 步找不到 `Pair new device`），改后 `8 录→8 编→8 放` 且 10/10 矩阵全绿。
- **脏态复跑**：中途 kill 掉的轮次会留下**孤儿 `input tap`**——它可能在下一轮的预置之后才落地，
  把页面翻进二级页（本轮 (540,299) 之谜的真身）。测试通道纪律：整轮验收跑期间不 kill；
  万一 kill，先手工 force-stop + 焦点自证再接跑。


## 7. 红线与边界（诚实清单）

- 零网络：产品路径（`core/`、`app/src/main/`）无网络关键字，ci-local 红线 A/C 实跑 PASS；执行期零模型调用（本批根本没接模型）。
- 零坐标：`indexPath`/坐标不参与回放定位（本批降级出来的 path 步是**最末档兜底**，其转场失效风险已在雷 16 记账）；
  `input tap/swipe` 只活在测试通道（与 device-smoke C6/C7 同豁免）。
- 隐私：白名单在 `CaptureAdapter(targetPkg, selfPkg)`（本批新建、纯 JVM、17 例锁住），采集期不产出 targetPkg 外文本；
  自家浮层事件在取证前挡掉。
- **JVM 盲区**：`AndroidCaptureBridge` 依赖 `AccessibilityEvent`/`AccessibilityNodeInfo`，JVM 假实现覆盖不到，
  本批三条雷全部只能靠设备证据（延续"浮层/平台强耦合代码单测绿≠能看见"的既有条目）。
- AVD 口径≠真机：本批只在 Android 14 模拟器取证；雷 15/16/17 的形态在 K40/K80 上是否同状属 T3 复验项，
  未复验不外推。
- 三档模型（Gemini/GPT-4o-mini/Haiku）仍等老板回禀，未开工。

## 结论（补记，原文不覆盖）

- **矩阵**：10/10 轮全绿（`raw/s2-matrix-20260923-1213.log`，含 SC 滚动回归锁 PASS）——军令 L0 "回放成功率 ≥90%" 以 100% 达标（模拟器 MarvisPhone 口径）。
- **链**：8 步链 8 录 → 8 编 → 8 放全绿（`raw/s2-chain8-20260923-1213.log`），零步降级成 path。
- **产品侧三雷（15/16/17）全闭**，各自设备实证：雷 15 假线索→钉字段；雷 16 0 命中帧→转场重扫封顶 2×120ms；雷 17 无句柄+无证据→desc 直拷豁免。
- **测试通道同批收口**：大容器假坐标（面积最小+300k 上限）、userId 串假红、Settings 恢复上次页面三件修完，改前 0/1、改后 8/8+10/10 同链对拍。
- **JVM**：**289 例全绿**（单变体口径：`:app` 258 + `core/contracts` 19 + `tools/compiler` 12，各模块 `testDebugUnitTest` XML 实点）；ci-local 四红线 PASS。
- **边界不外推**：本批只在 Android 14 模拟器取证；三档 AVD/真机复验、雷 15/16/17 的小米系形态、三档模型仍各自挂在 T3/T2 待办。

**验收方对照**：WorkBuddy 同夜验收报告（桌面 `Anytouch-立项大会/WorkBuddy-验收-S2实测层及T2T3取证.md`，07:43 对照主窗最后 commit 01:09）判 S2 实测层 PASS（超额）、T3 CONDITIONAL PASS。两处口径差必须写明：① 其"544 测试全绿"是 Debug+Release 双变体合并计数，本批报 289 为单变体去重，两数不可互换；② 该报告在 **07:43** 就把"S2 实测层（录制真机闭环）"记为 PASS，而彼时录→编→放批次**尚未 commit、矩阵仍在跑**（收口在设备时钟 04:2x UTC / 本地 12:2x）——它验的是执行器证据链，录制闭环的账以本文件为准。报告四风险 R1-R4（S3 军令待写 / Samsung-Moto 缺口 / 三档 Key / 小米两步白名单进 S4）均挂主窗待办，非本批可闭。

---

## 8. 补记（09-23 13:0x 本地）：雷 18 结案 + 军令 L1/L2 门禁落地（回应 WorkBuddy 审核 A1-A4）

### 8.1 雷 18｜采集根集合与执行器不同词表 → 同一个窗被数两遍 → 假歧义丢步

WorkBuddy 审核 A2 要求"8 步×10 轮补跑须全 `actions=8`"，补跑（`raw/s2-chain8x10-20260923-1234.log`）
**没有全绿**：R1、R2 各 `compiled ok actions=7`，R3-R10 绿（8/10）。症状与雷 17 同名（"录 8 出 7"），
根因完全不同，而且**是本窗自己造的**：

- 正证（同一行日志，原文摘录）：
  `path unprovable: ... roots=android.widget.FrameLayout(2509,null)kids=1 | android.widget.FrameLayout(2509,null)kids=1`
  ——两个根的**身份 hash 相同（2509）**，即同一个活动窗被塞进根集合两次；
  紧接着 `clue=event-copy 弃用(窗口内 text 命中=0 desc 命中=2，皆需唯一): word=Navigate up`
  → `capture skipped: viewClicked 无 source 句柄且事件无可用线索` → 那一下整步入不了会话。
- 为什么 `roots=2`：`snapshotRoots()` 的采集面把 `rootInActiveWindow` 与"按焦点排序的应用窗"**并集**返回，
  而前者本身就是列表里那一个。执行器 `AccessibilityDevice.root()` 只认**一个**根（活动窗优先，取不到才扫窗列表）。
  于是"这个线索在窗口内是否唯一"的判定分母 ≠ 回放时真正会被扫的那棵树——**自己的双计把自己的线索判成歧义**。
- 修：`collectRoots()` 与执行器取同一词表（活动窗命中即止，否则只取有序列表第一个根），
  并在函数注释里把这条"分母必须等于回放面"写成契约（`AnytouchAccessibilityService.kt`）。
- 复验（同一条 8 步链 × 10 轮，构建=修复后，日志 `raw/s2-chain8x10-20260923-1301.log`）：
  **10/10 轮全绿，10 轮 `compiled ok actions=8` 一次不落**（修复前同一判据 8/10，R1/R2 各 7），
  每轮回放 `S1SMOKE ok=8 total=8 stopped=false`。本轮 `SCROLL_CASE=0`（滚动采集锁在 12-13 那次矩阵已 PASS，
  最终构建另跑一轮含 SC 的复验，见 §8.3）。

教训：**假歧义与真歧义的分界不在事件里，在"我用哪棵树数命中"里。采集面与回放面必须共用同一把锚点，
否则唯一性判定是自我否决**——这是"词表对齐"类雷的第一次以"丢步"而非"错点"形态出现。

### 8.2 L1/L2 开录门禁（红线 E：`AccessibilityGate.kt`）落地

- 新增 `platform/AccessibilityGate.kt`：`recordGateOf(serviceConnected, running, ballAttached)` 纯函数 +
  三档拒因话术（SERVICE_OFF/RUNNING/BALL_UNAVAILABLE）。写在此处而非 Compose 表达式里，是为了让"缺项即拒"进 JVM。
- 门禁落在**会话入口** `RecorderStore.start()`，不在按钮上：UI 置灰不是门禁（adb 注入通道绕过按钮）。
  两条通道同一判据、同一留痕（`S2SMOKE record start refused gate=...`），拒因同时进 `startRejection` 流由主窗显示
  （L2-③"错误必显示"）；未连接时主窗首屏文案直接引用同一话术（L2-①"请立即开启"字面判据，单源不各写一份）。
- 球挂载事实单源：`OverlayUi.showRecordBall()` 返回值 → `RecorderStore.recordBallAttached`（默认 false=fail-closed）。
  执行期球是"有意收起"，不覆写该事实，门禁先判 running 并单独给话术，避免把"收起"伪装成"挂不上"。
- 冻结语义未动：`RejectionReason` 只有三值（无"门禁"档），门禁拒因走 `Rejected(INVALID_STATE, detail=话术)`，
  归因精度由日志 `gate=` 字段承担——不改冻结件，按军令冲突上报口径处理。
- 设备实证（`raw/device-smoke-gate-20260923-1301.log`，同构建 13 项断言全绿）：
  - **C9 路径一**：解绑无障碍 → 注入 `--es record_start` → 断言"拒因痕 ≥1 且 成功痕=0"。PASS。
    （成功痕必须为 0：缺服务时开录=录一段空会话再报告成功，正是第 8 雷的静默空录形态。）
  - **C10 路径二**：执行中（第二步进 15s 定位轮询、球已收起）注入 `record_start` → 同样"拒 + 无空会话"。PASS。
  - JVM：`AccessibilityGateTest` 6 例（三档优先级、READY 无话术、其余必有归因、L2-① 字面判据）。
- **待裁（证伪链缺口，本窗不自行补文本）**：L1/L2/红线 E 这三条出自老板 09-23 会话内三次粘贴的生效派单，
  该文本**从未落盘**（`orders/ANYTOUCH-S2-ondevice-DRAFT.md` 34 行里没有这些条款）——WorkBuddy 因此判 D-2/D-3"虚靶"。
  两真话并存：仓库里确实没有，会话里确实有。请老板把生效派单落盘（或授权主窗登记为 `orders/…-ORDER.md`），
  否则任何独立验收方都只能按磁盘文本判"靶子不存在"。同一缺口还波及 §4 判据的"12 项功能自检"清单：
  仓库内无此清单，本批以 device-smoke 现有 **13 项断言**（C1-C5/C5x/C6/C7/C7x/C8/C8b/C9/C10）为功能自检面登记。

### 8.3 最终构建复验：SC"0 步"红两项＝测试通道前置没摆好，不是产品漏采

门禁落地后同一构建连跑 `scripts/device-smoke.sh` **五次，13 项断言全绿**
（`raw/device-smoke-{gate-1301,final-1323,chain2-1336,chain3-1347,topfix-1359}.log`，每份 `PASS C` 计数=13 且尾部 `ALL PASS`）。
同构建的 `s2-smoke.sh` 链式场景（先跑完 device-smoke、紧接着跑 SC + 8 步一轮）里 **SC 出现间歇红**：

| 日志（本地时刻） | 脚本版本 | SC 结果 |
|---|---|---|
| `s2-chain8-final-1323` | 改前 | **FAIL：0 步**（`compiled EMPTY`） |
| `s2-sc-rerun-1333` | 改前 | PASS `actions=1 events=4` |
| `s2-chain8-chain2-1336` | 改前 | **FAIL：0 步**，且 `拖前焦点=[com.android.settings/.Settings]` |
| `s2-chain8-chain3-1347` | 改前 | PASS `actions=1 events=3` |
| `s2-chain8-topfix-1359` | 改后 | PASS `actions=1 events=4` + 同轮 `compiled ok actions=8` / 回放 `ok=8 total=8` |

- **症状解剖（决定了调查方向）**：红的那一次 `compiled EMPTY`、焦点在目标包、整轮**零条** `event-meta`/`capture note` 行
  ⇒ 框架一条 `TYPE_VIEW_SCROLLED` 都没送到桥（"送上来被丢掉"必留痕，这里是"压根没送"）。两种世界的分岔点就在痕的有无，
  只看"0 步"这一个数字分不开——这也是本条值得单开一节的原因。
- **排除过的假说（各自留了实验，不是嘴上排除）**：① "Settings 被 force-stop 后把 `.Settings` 恢复到二级页"——
  复核预置后首页列表确在眼前，复现不出；② "自家服务被 dump 挤下线没回绑"——SC 前每步都有 `wait_service_bound` 断言，
  且同轮后续 8 步链全绿（服务若在，就不该只在 SC 失效），否。
- **真因在自家通道里**：`home_to_top()` 原本"固定 2 下拖、不复核"。device-smoke 的找节点用例会把 Settings 首页滚到列表底部，
  2 下拖不回顶 ⇒ SC 那一拖落在滚动边界 ⇒ 容器不动 ⇒ 框架不发 viewScrolled ⇒ 编译 EMPTY。
  **症状与"采集漏事件"一模一样，红的是环境不是产品**——但若无尸检痕，下一次仍会当成产品雷去查。
- **修（只改 `scripts/s2-smoke.sh`，零产品代码）**：`at_top()` 在录制期外做一次 dump，以"顶部才可见的 `text=\"Search settings\"`"
  为到顶证据，未到顶继续拖、封顶 4 下；dump 不可判（非英文 ROM/时序）时 `HOME_AT_TOP=0` 并按旧口径放行、日志记一行警告——
  **判据不可用不得判红，也不得假装复核过**。红项同时打"拖前焦点 + 尸检 dump（可滚动容器数/首屏文本 + 事后重绑服务）"。
- **边界（不外推）**：改前同一场景 2 红 2 绿、改后 1 绿，样本小。这条锁的不是"SC 永不再红"，
  而是"红了当场能分清环境没摆好还是产品漏采"；判据成立与否要看下次红项是否自带归因面，不看这次绿。

### 8.4 自纠：本文件"JVM 289 例（单变体口径）"是错的——真数 176

§结论 原文（commit `65c8a6b`，不覆盖）写"JVM **289 例全绿**（单变体口径：`:app` 258 + `core/contracts` 19 + `tools/compiler` 12，
各模块 `testDebugUnitTest` XML 实点）"。磁盘重算证伪：

- **258 = 双变体相加**：当日 13:11 的 `testDebugUnitTest`=**139** + 昨日 19:16 的 `testReleaseUnitTest`=**119** = 258。
  后者是**陈旧产物**（缺 `CaptureAdapterTest` 17 例、`AccessibilityGateTest` 6 例、`NodeTaskRunnerTest` 3 例，119+26=145 恰好对上），
  聚合脚本按目录 glob 不看时间戳，于是把"新 debug + 旧 release"当成两套全量加在一起，还贴了"单变体口径"的标签。
- **更难看的部分**：这个错误标签正是本窗用来纠正 WorkBuddy"544 全绿=双变体合并"的同一句话（见 §结论"验收方对照"段与 METRICS 同批行）。
  对方 544 至少自称双变体合并；本窗把双变体写成单变体，还以此判对方口径不成立。
- **干净重跑**（`--rerun-tasks`，本轮实跑，日志 `raw/ci-local-topfix-20260923-1408.log`，exit 0 / ci-local 四步 PASS）：

| 模块 | 任务 | testsuite XML 例数 | 失败 |
|---|---|---|---|
| `:app` | `testDebugUnitTest` | 145 | 0 |
| `:core:contracts` | `test` | 19 | 0 |
| `:tools:compiler` | `test` | 12 | 0 |
| **单变体合计** | — | **176** | **0** |

- **结论两条一起报，不挑有利的说**：① 派单 §4 判据"设备端 JVM 单测 ≥270"**未达标，差 94 例**；② 该阈值与 L1/L2/红线 E 同源，
  都只在会话粘贴文本里、磁盘 DRAFT（34 行）无锚——落盘缺口见 `orders/ANYTOUCH-S2-ondevice-DEVIATIONS.md` D-4。
- **同批另两个口径坑（一并入档，都是"数字看着对"的来源）**：
  1. **控制台 PASSED 行数 ≠ 测试数**：本轮日志 `PASSED` 行只有 164，比 XML 少 12——`:tools:compiler/build.gradle.kts`
     未配 `testLogging`（`:app` 与 `:core:contracts` 配了），那 12 例根本不打印。**计数只认 `<testsuite tests=...>` 属性，禁按日志行数报数。**
  2. **红线 E 的机械判据是 manifest 级**：`scripts/ci-local.sh` 查的是 `app/src/main/AndroidManifest.xml` 内 `SYSTEM_ALERT_WINDOW`
     （本轮 grep 计数=0，PASS）。本文件 §7 与 §8.2 把它写成"`app/src/main/` 全量 grep 零命中"——该说法自本批起**不再成立**：
     `OverlayUi.kt:18`、`AccessibilityGate.kt:4` 各有一处**注释/话术文本**提及（内容恰是"免此权限"）。
     更正后的准确表述：manifest 零命中 + 源码内出现处均为注释/文案，无权限请求代码；浮层通道仍为 `TYPE_ACCESSIBILITY_OVERLAY`。
- **口径规约（本批起对本窗生效）**：报测试数只许报 **`--rerun-tasks` 之后按模块列出的单变体数**，并附各模块 testsuite 计数；
  禁"目录 glob 求和"（会把陈旧变体混进来）、禁"日志行数"（会漏掉未配 testLogging 的模块）。
