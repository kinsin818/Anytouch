# S5-e 施工工单（第一版收尾三功能 → v1.0.4）

**开单**：2026-09-25 · 主窗（施工总窗口）
**军令原文件**：`orders/ANYTOUCH-S5e-final-three-features-ORDER.md`（老板本人所写，本窗不回改）
**裁决**：`orders/RULINGS-20260922.md` §S5-R12（军令六条逐字 + 主窗呈报三处相撞 + 老板三裁 + 执行面钉 1~10）
**顺序**：v1.0.3（S5-d 重复执行）先按 S5-R11 钉 6 收口发布 → 本批在已发布基线上开工（裁 ③）。

## 0. 三裁摘要（细则以 RULINGS 原文为准）

1. **手动步只准节点字段，禁坐标**（军令"填坐标"三字不照字面执行）。
2. **只重试非高危；高危每次命中照旧弹，不做任务开头预弹**。
3. **先发 v1.0.3，再发 v1.0.4**。

## 1. 摸底回填（全部为盘上事实，行号取自 09-25 主窗摸底）

| 面 | 盘上真值 | 对本批的意义 |
|---|---|---|
| 步骤模型 | `core/contracts/src/main/kotlin/.../Contracts.kt:68` `Action(action_id, type, target: Point?, value: JsonObject?, source, safety)`；序列化真值 `RecorderJson.kt:13 encodeActions` | `target` 确有 x/y，但**执行路径从不读它定位**（只进 `landed` 回执）。本批不新增坐标通道 |
| 定位解码 | `executor/NodeTaskRunner.kt:574-586`（resource_id／text／content_desc／path／instance）；无线索即拒 `NodeTaskRunner.kt:156`；三阶定位 `locator/NodeTreeLocator.kt` + `LocatorModel.kt:4-13`；失败码 `LocatorMiss.code=NODE_NOT_FOUND`（`LocatorModel.kt:141,148`） | 手动步的可填字段=这一组，一字不加 |
| 既有重试 | 明示拒→重派**恰 1 次封顶** `executor/ExecutorDecisions.kt:53 redispatchPlan`（执行环 `NodeTaskRunner.kt:328-378`，`perform_failed` 回执 :365-376）；SET_TEXT"整步重试一次"兜底 :400-418（仍败 `set_text_unverified` :451）；定位轮询超时 `locateWithRetry` :500-510（默认 15s）；落字复核 `awaitLanded` :473-496（4s） | 军令"最多 2 次"须与这两处**合并成一本账**：单步总尝试 ≤ 3，既有那次计入额度（RULINGS 钉 9） |
| 失败后行为 | STOP 级失败=中止整队（`NodeTaskRunner.kt:138`），不跳下一步；收口行 `ok= total= stopped= stop=`（`RepeatLoop.kt:174`），步级 `S1SMOKE-DETAIL code=… msg=…`（`AnytouchAccessibilityService.kt:311`） | 重试判据的新日志 anchor 与本族同风格，禁中文上屏 |
| 高危确认 | 触发点 `NodeTaskRunner.runNodeStep:221-276`（定位命中后 `matcher.inspect`，15s 超时默认拒 :263-274）；`RepeatConfirmCache` 语义与调用方 `RepeatLoop.kt:135-160` / `AnytouchAccessibilityService.kt:283,386-410` | 高危步失败**不进重试额度**；确认链一行不动 |
| 持久化 | `recorder/session/RecorderStore.kt:46` 是 **object 内存单例**（:86 存 `compiledActions`），:41 注释"会话在内存不落盘，持久化另案待裁"；全仓无任务类磁盘存储（仅凭据件 `platform/AndroidKeyVault.kt:36` 用 filesDir） | "我的任务"是**新增面**：写口 + 列表 + 删除；落盘位置 `filesDir`，与凭据件分文件（红线 I 照吃） |
| 落账口 | `RecorderStore.kt:230-234 acceptModelActions(actions, supportedTypes, origin="model"): ModelLedger`；调用方仅 `ByokGateway.kt:63`（model）与 `MainActivity.kt:415`（template）；门禁：RUNNING 档 :235、词表档 :242（判据 `recorder/ModelLedgerGate.kt:28`）；派发侧另有 COMPILING 档／`RepeatPolicy.parse`／V-3 `taskAdmission`（`MainActivity.kt:456,478,492`，判据 `TaskAdmission.kt:31`）+ 总线 TTL 60s（`core/state/AppState.kt:26,84`） | 载入已存任务必须走同一口，origin 加 `"saved"`，**不许开第二条通道** |
| 步骤编辑 | `recorder/StepEditing.kt:16-26` 现有 Remove/Rename/Move 三型；唯一写口 `RecorderStore.kt:150 applyEdit`（门禁 `stepEditGateOf:71-80`，RUNNING 档 :77）；UI `ui/StepListUi.kt:65-112` 行内四钮；编辑成功自动重发建议 :169-184；入口可编辑判据 `MainActivity.kt:178` | 插步=新增 `StepEdit` 子类型挂同一写口，RUNNING 档照常拒 |
| 首页 UI | 只有 `MainActivity` 单 Column（无导航），列表雏形=`ui/StepListUi.kt:38 StepListEditor` | "我的任务"列表并置在同一 Column 内，不引路由 |
| 必对的锁 | `app/src/test/.../service/RepeatWiringLockTest.kt:44,71`（**源码文本锁**，禁判据漏进 `safety/` `executor/` `locator/` `compile/`）；JVM 既有：`StepEditingTest`／`RecorderEditingTest`／`ModelLedgerGateTest`／`TaskAdmissionTest`／`RepeatLoopTest`／`NodeTaskRunnerTest`／`executor/ExecutorVocabularyDispatchLockTest` | 动确认/循环/落账接线前先过这些锁 |

## 2. 改动面清单（预期）

| # | 文件 | 动作 |
|---|---|---|
| A | `app/src/main/kotlin/com/anytouch/app/executor/StepRetryPolicy.kt`（新增，android-free） | 重试额度与不可重试白名单的**唯一判据源** |
| B | `executor/ExecutorDecisions.kt` + `NodeTaskRunner.kt` | 两处旧兜底改为向 A 取额度；高危/急停/解绑类失败一律不重试 |
| C | `recorder/SavedTaskStore.kt`（新增） | 命名存／列表／删／按名载入的落盘写口（filesDir，JSON 走 `RecorderJson` 同一序列化真值） |
| D | `MainActivity.kt` + `ui/` | "Save this task" 命名框 + "My tasks" 列表（载入＝`acceptModelActions(origin="saved")`）+ 每行删除 |
| E | `recorder/StepEditing.kt` + `ui/StepListUi.kt` | 新增 `StepEdit.Insert`（类型=点击/输入/等待；字段只准 `value` 那五类节点线索）+ 每步后 "+" 钮 |
| F | `app/build.gradle.kts` | `versionCode 5` / `versionName 1.0.4` |
| G | 测试：新增 JVM 锁 + `scripts/` 新格 | 见 §3 |

**不动面（军令 5 条 + 裁 ②）**：`safety/HighRiskMatcher`、`safety/HardcodedHighRiskRules`、`safety/HighRiskPanelCopy`、
`ui/OverlayUi` 判定与放行链、`AnytouchAccessibilityService` 的循环外壳与急停接线、编译链（`:byok` / `:tools:compiler`）、
`TaskAdmission` V-3 判据。执行期零网络、零模型请求。

## 3. 判据（每格都要能在设备或 JVM 上被证伪）

1. **重试额度**：单步总尝试 ≤ 3（原始 1 + 重试 2），既有重派那次**占额度**；重试次数与最终失败码上日志；重试期间按急停球=当次即停、不再重试。
2. **高危不重试**：构造一次高危步失败（点它不换页的自家标定 fixture，沿用 S5-d 的 Settings 搜索框法），断言日志里**没有**该步的第二次派发，且按现行口径中止整队。
3. **存 → 载入 → 执行全链**：命名存一条 → 冷启（进程重进）仍在 → 点载入走 `origin=saved` 落账口 → 直接执行回执逐字对账；删除后列表与磁盘双无。
4. **载入不绕门禁**：执行中（RUNNING）点载入必拒；含不支持动作类型的存档必被词表档拒；载入后 V-3 步序账比对照样吃。
5. **手动插步回放同形**：插一步"点击（按 resource_id）"与录制步跑同一定位器；插步后账内序号连续、越界索引拒。
6. **手填高危文字照样弹**：手动步把 `password`／`delete` 一类词填进 text/content_desc → 执行到该步必弹二次确认，不答 15s 默认拒。
7. **禁坐标可证伪**：新增 JVM/设备锁断言手动步的写入体里 `target` 为空且产品代码路径无坐标定位入口（不是"嘴上说没有"）。
8. **全英文**：`scripts/ui-english-sweep.sh` 上屏 CJK=0 + 静态字面量清单跟新（S5-R9 豁免面口径不变）。
9. **旧账不回归**：C 系列（device-smoke 13）／U 系列（ui-smoke 全族）／L1~L7（s5d）／Photos 全链 5 轮，**同一份 APK 字节**复绿。
10. **发布账**：`versionCode 5`／1.0.4，重打 debug APK，推 Release + **匿名下载回对 md5**，先建后记。

## 4. 不做项（写死，防"顺手做了"）

- 不做坐标点击通道、不做截图取点、不做"填 x/y"的手动步。
- 不做"任务开头预弹一次"高危确认。
- 不做高危步自动重试。
- 不做第二条落账/派发通道（"我的任务"只能经 `acceptModelActions` + `submitTask`）。
- 不动安全门禁本体、急停、S5-d 循环；不碰 K40/K80 真机；S4 额度 6/8 定格不花。
- 不承诺"重试可绕过平台检测"一类对外措辞（同 S5-R11 钉 5 的虚标纪律）。

## 5. 遗留与依赖

- v1.0.3 收口（Release／匿名回对／三本账）先行，本工单在其后开工。
- 既有欠格不变：byok-smoke 7 行欠格并真机批、keystore 正式签名版前回炉、Gumroad 上架动作归老板面、买家件未经母语校对、K40 欠格（#50）。
- 持久化（§1 中 `RecorderStore.kt:41` 的"另案待裁"）本批**只裁"我的任务"这一条**：录制会话本身的落盘策略仍留原案，不顺手扩面。
