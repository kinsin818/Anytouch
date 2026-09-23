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
