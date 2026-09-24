# ANYTOUCH | S3-F 派单：编译互斥扩两入口（S31-B2）+ 动作词表收成单一真源（S31-B3）

> 落盘人：施工总窗口（主窗）　日期：2026-09-24
> 两条裁决同批派单（同一入口面，分两批会撞同一个文件的同一带），且**串行排在 STAGE-31-B 之后**——
> S31-B3 要新增"执行器支持集"真源，正落在 31-B 正在重构的那段代码边上，两批并行=把冲突留给集成，那是可预见的返工。
> 本文件性质=生效派单原文；worker 与主窗争议以本文件字面为准。

## §1 两件事各自要落到哪一行磁盘上（主窗 09-24 实读，不是回忆）

### F1｜待办 14 → 裁决 S31-B2"并进来"：编译在跑时，「执行任务」与「步骤编辑」也要拒

现状（磁盘事实）：
- 互斥判据只有一个真源：`app/src/main/kotlin/com/anytouch/app/platform/AccessibilityGate.kt:45-46` 的
  `stopCompileGateOf(compileBusy)` 与 `:49-61` 的 `recordGateOf(...)`（`RecordGate.COMPILING` 那一档，
  话术在 `userCopy()` `:75`）。这一条正是 E0 批按 S3-R4-1 落的，**只覆盖开录/停止两个入口**。
- 「AI 编译产物落账」口的门禁只看执行态：`recorder/session/RecorderStore.kt:200-204` 的
  `if (AppState.running.value) → RefusedRunning`，**没有 compileBusy 这一档**（它就是被编译那一跑自己调的，天然不互斥，别误改）。
- 「执行任务」与「步骤编辑」两个入口当前的门禁同样只认 RUNNING（编辑面见 `RecorderStore` 的 edit 拒因链与
  `revalidateEditRejection`；执行面见 `AppState.running`）。

要求：
1. 判据**复用** `RecordGate`/`AccessibilityGate` 那一格，**不许**在两个入口各写一份 `if (compileBusy)`
   （母单 `ANYTOUCH-S31-ORDER.md` §2-4 与 `AccessibilityGate.kt:41` 注释同一条纪律："两个入口若各写一份，
   改一处漏一处就是串状态"）。做法：新增纯函数（如 `runGateOf` / `editGateOf`，名字 worker 可议，写进自述偏差即可），
   把 `compileBusy` 与既有 `RUNNING` 档一起纳入同一张真值表。
2. **错误必显示**：拒了必须上屏一条拒因（与 `startRejection`/`step_edit_rejection` 同一套槽位与过期边纪律）。
   拒绝是**默认行为**，不弹窗不重试；话术要说清"AI 编译中，改账与执行此刻不动"，别让用户以为卡死。
3. **过期边**：编译那一跑回来（`compileBusy` 翻回 false）后，陈旧拒因必须自动作废（同 `stopRejectionAfterStateChange`
   `AccessibilityGate.kt:101-102` 那条纪律），否则就是假红。
4. **注入通道同样被拒**：adb 注入是测试通道，也是"绕过置灰按钮"的那只手——门禁必须落入口，不落按钮（本仓已定律）。
5. 设备断言：`scripts/byok-smoke.sh` 里补一档"编译在跑时投 `run_task`/`step_edit_*` → 账不动、执行不开始、红字在"，
   口径与既有 E0 那几条（`record stop rejection`）逐字同构；本批主窗会在模拟器 keyed 轮跑。

### F2｜待办 15 → 裁决 S31-B3"收紧词表（编译侧拒并显式）"

现状（磁盘事实）：
- 执行器支持集：`executor/NodeTaskRunner.kt:98-121`——`WAIT` 走延时、`CLICK/SCROLL/TYPE_TEXT` 走 `runNodeStep`，
  `else → unsupported_type + StopCode.EXECUTOR_ERROR`（:111-121）。
- 落账口：`RecorderStore.acceptModelActions`（`:200-216`）**不看动作类型**，模型交什么都照单全收并上屏。
- 设备侧已钉的判据：`scripts/byok-smoke.sh` 的 **E5i**（产物步内出现执行面不支持的类型即当场红）。
  模拟器 `[E1-*]` 轮抓到过 `key/back`，真机 `[K4]` 轮只交 click 故绿——**间歇红，不是"偶发即不存在"**
  （`evidence/S3/slice-e2-k40-key.md` §5）。

要求：
1. **单一真源**：把"执行器能跑哪些 ActionType"抽成一处 android-free 真源（建议紧邻 31-B 的新纯函数面，
   如 `executorSupportedActionTypes: Set<String>` 或 `supportsExecutorDispatch(type: String)`——
   **元素类型是 `String` 不是 `ActionType`**，理由见 S31-B5 与本文件 §4-3），
   并且**执行器的那个 `when` 与编译侧的拒都必须走它**。两处各抄一份字面量正是这条雷的成因（雷 18 同族）。
   **主窗 09-24 补充事实：全仓的动作词表实测是三份「互不相同」的抄本，不是一份**——
   | 位置 | 集合内容 | 与执行器真集的关系 |
   |---|---|---|
   | 执行器 `when`（`NodeTaskRunner.kt:99`、`:109`） | `wait, click, scroll, type_text` | **真集（基准）** |
   | 编译器授权集 `byok/src/main/kotlin/com/anytouch/byok/DslCompiler.kt:150` `ALLOWED_TYPES` + 提示词 `:50` "type 只允许 …" | `click, type_text, scroll, `**`key`** | **多一个 `key`**（执行面 `:111-121` 对它收 `EXECUTOR_ERROR`）＝E5i 那条雷的正身 |
   | 测试脚本 `scripts/byok-smoke.sh:465` `grep -vw -e click -e type_text -e scroll -e wait` | `click, type_text, scroll, wait` | 内容恰好等于真集，但是**手抄**——真源一改它就变成假门禁 |
   本批必须做到：**两处都不许再各自演化**——① `ALLOWED_TYPES` 与提示词那半句都改为从真源派生（`key` 因此从授权集消失，
   这是老板裁 S31-B3"收紧词表"的落点）；② 脚本那行搬不进 Kotlin，二选一写进证据：加一条 JVM 用例断言
   "真源的名字集合序列化后 == 脚本 `:465` 那四个字面"（把脚本当字符串钉住），或让脚本从构建产物读名单。
   **不许留着三份各自演化还判绿**（雷 18 同族：两处实现只有坏的那条会被看见，三处同理）。
2. **编译侧当场拒并显式**：`acceptModelActions` 收到不支持的类型 → 整本不落账（还是逐步落？本批按**整本拒**，
   与 `RefusedRunning` 同一形态，理由：半本账=屏上步骤与用户意图不一致而不报错，比整本拒更难发现），
   返回一个新的 `ModelLedger.RefusedUnsupportedType(index, type, supportedTypes)` 之类结论，
   UI 侧上屏拒因（含"支持的是哪几个"），日志留痕口径与既有 `S3SMOKE model ledger refused gate=…` 同构。
3. **提示词侧**：`:byok` 编译请求的动作集说明必须与真源同源（或由 JVM 用例断言两者逐字一致），
   不许提示词写一套、校验器认另一套。
4. **副作用照报不隐瞒**：拒一次=用户要重点「AI 编译」，命中率账要能显出这一类拒（与 S3 三档模型阈值待裁同一条线，
   见 `orders/ANYTOUCH-S3-byok-ORDER.md` §4-3——阈值仍未定，本批不自定阈值）。
5. 若 31-B 已把 `NodeTaskRunner` 的派发边重构过，本批的"真源"落点以重构后的形状为准；
   两条判据的先后**不许**互相改语义（谁的靶表谁负责）。
   **主窗自纠（09-24）**：本条原本连同 commit `2f00211` 的理由写的是"F2 的真源正落在 31-B 重构的那一带"——
   31-B 的实际 diff 出来后逐段核过，**这个前提是错的**：`when (action.type)` 支持集（`:98-121`）一行未动，
   31-B 的两个 hunk 只落在 `:302-351`（派发/重派边）与 `:448-463`（`awaitLanded` 兜底扫树）。
   串行仍然成立，但**换一条真理由**：① 本批 §2 要求 JVM 基线以"当时 main 的实测数"起跳，31-B 未落则这个数不存在；
   ② 模型服务当日已连切两条 worker，同机并发两批=把返工率再翻一倍。谁要改这条排程须另说一句。
6. **判据话术跟着裁决走**：`scripts/byok-smoke.sh:467` 的 E5i 红字现在写着"两侧支持集需一句裁决收口
   （收紧词表 vs 执行面补类型），本轮不擅自改任一侧"——裁决已下（S31-B3），本批落完必须把这句改成指到裁决与真源，
   不许在门禁里留一条"还在等裁决"的过期话术（过期边同一条纪律，只是这次过期的是脚本话术）。

## §2 交付与验收（沿用母单，不重写）

- 自检五关照 `orders/ANYTOUCH-S31-ORDER.md` §5；JVM 基线以**当时 main 的实测数**起跳（31-B 落完会是新基线，派单时主窗会写进本文件 §4），禁 glob、禁数日志行。
- 红线九线 + `scripts/redline-probe.sh` 双 RC=0；新增判据文件保持 android-free。
- 设备关：本批动了执行入口与落账口，必须跑 `device-smoke` + `ui-smoke` + `s2-smoke`（录→编→放三支）零回归；
  worker 若被主窗占机，按 §5-5 回报"待主窗排期"，不许跳过。
- 交付自述：`evidence/S3/slice-f-mutex-vocab.md`（本批属 S3 主线，不入 S31 目录），段结构照 31-A 那份。

## §3 明确不做

- 不自定三档模型命中率阈值（ORDER §4-3 待裁）。
- 不在执行器补 `KEY`/`BACK` 动作（老板已裁"收紧词表"，扩执行面这条路**没被选**，谁想改须另请一刀）。
- 不动 `core/` 契约、`safety/`、`RejectionReason` 枚举、`AnyNode` 路径编码与空白 resourceId 判据、
  `recorder/session/` 的既有已验收语义（STAGE-22）——本批只在 `RecorderStore` 的**模型落账口**加一档，不改会话采集面。
- 不改整页三屏排布（待办 13-②，属 S2 面视觉，另等一句话）。

## §4 基线（派单时由主窗填）

**JVM 分模块真数与 main commit 待 STAGE-31-B 集成后填**（31-B 落完是新基线，不许拿 363 冒领"当时 main 的实测数"）。

以下三条是**今天已在磁盘上核过、且不随 31-B 移动**的事实，worker 不必重新摸（31-B 的靶表只动 `runNodeStep` 里的派发边 A1/A2 与复核边 A3，`when (action.type)` 这个支持集本身一行不碰）：

1. **执行器支持集真源 = `app/src/main/kotlin/com/anytouch/app/executor/NodeTaskRunner.kt:98-121`**：
   `when (action.type)` 三档——`:99` `ActionType.WAIT`（走 `delay`，从不派发）、`:109`
   `ActionType.CLICK, ActionType.SCROLL, ActionType.TYPE_TEXT -> runNodeStep(...)`、`:111-121` `else ->`
   收 `StopCode.EXECUTOR_ERROR` + `message = "unsupported action type: ${action.type}"`（`:117`）
   + `gateReceipt(..., "unsupported_type:${action.type}", ...)`。
   **F2 的"真源"必须是这一处的单一集合**，编译侧与 prompt 从它派生，不许另抄一份字面量。
2. **模型落账口今天只有一档门禁** = `app/src/main/kotlin/com/anytouch/app/recorder/session/RecorderStore.kt:200-204`
   （只判 `AppState.running.value` → `ModelLedger.RefusedRunning`）：**既不看 `compileBusy`，也不看动作词表**。
   这正是 F1（补 `compileBusy` 一档）与 F2（补不支持类型整本拒一档）的两个插入点，各自一条、不并档。
3. **契约侧 `ActionType` 是 `object` + `const val String`**（`core/contracts/src/main/kotlin/com/anytouch/contracts/Constants.kt:21-30`，
   常量在 `:22-29`，自陈"非严格枚举，留扩展位"）→ 真源若做成集合，元素类型是 `String`；
   比较一律引 `ActionType.X` 常量而不写字面量（S31-B5 同一理由，worker 不要再想改成枚举——那要另一次放行动 `core/`）。

