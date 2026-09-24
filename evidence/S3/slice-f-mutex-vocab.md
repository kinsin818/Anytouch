# 切片 F · 编译互斥扩到「执行任务/步骤编辑」+ 动作词表收成单一真源（S3-F 验收）

军令：`orders/ANYTOUCH-S3-F-DISPATCH.md`（含 §5 模块方向更正）；裁决：`S31-B2`（编译在跑并禁「执行任务/步骤编辑」两入口，主窗倾向并入）、`S31-B3`（收紧词表：编译侧拒并显式，从单一真源取）、`S31-B5`（`type: String` 引常量不引字面量）。
worker 树 `D:/Anytouch-s3f`（分支 `wip/s31-f`，与主树同一 dispatch 提交 `82d55fd`）。**worker 未干净自检**：后台代理跑到第 150 次工具调用触顶被切断，最后一次落盘是补两条 watch 用例（13:36 `DslCompilerTest`），其自述那句"Now I'll write the F2 source"是切断前的半句、不代表产物缺失——磁盘为准：`ExecutorVocabulary.kt` 等五个新增件全在。**因此本批的五关由主窗独立重跑背书，不拿未完成的 worker 自检冒领绿**（"主窗仍独立重跑"这条纪律在本批第二次兑现价值，见关 3 自纠）。

## 一句话结论

**五关全过：F1（编译互斥从录制两面扩到「执行任务」「步骤编辑」两入口，判据共用同一格、四入口头一句同源）与 F2（执行器授权词表收进 android-free 的 `:byok` 单一真源、编译侧与落账侧反向引用、越权 type 整本点名拒）落进主树；JVM 384→423（app 335 / byok 69 / contracts 19，0 失败）、九线 CI_RC=0、F/G/H/I PROBE_RC=0、模拟器同构建 device 13/13 + ui 41/0/0 + s2 10/10 ratio=1.00 + keyed byok-smoke 40 条断言 0 失败（E8g/E8g2/E8h/E8h2 当场验编译在跑拒派发/拒编辑、E5i 不再间歇）。S3-F 在其范围内 ACCEPTED；唯一残口 E8i/E8j 的"两入口新红字上屏像素"本轮 dump 未命中，由日志正向 + JVM 锁 + 与录制面共用同一渲染槽三方兜住，屏上像素复验并入待办 17（K40 byok-smoke）。**

## 关 1 · 逐行 diff 终审（21 文件：16 改 + 5 新）

模块依赖方向是这批的命门：`:app implementation(:byok)`、`:byok api(:core:contracts)`，依赖单向 `:app → :byok → contracts`，`:byok` 引不到 `:app` 符号。故**词表真源必须住 `:byok`（android-free）**，`:app` 的 `when` 与 `acceptModelActions` 反向引真源——§1-F2-1 原建议"真源住 `:app/executor`"会循环依赖，派单 §5 已更正为硬约束，本批照更正落。

- `byok/…/ExecutorVocabulary.kt`（新，真源）：`executorSupportedActionTypes: Set<String> = setOf(WAIT, CLICK, SCROLL, TYPE_TEXT)`——`key` 出、`wait` 入，四元素全部由 `ActionType` 常量组成（不抄字面量，守 S31-B5）；`supportsExecutorDispatch(type)` / `executorSupportedTypesSerialized(sep)` / `executorSupportedTypesPromptClause()`。android-free。
- `byok/…/DslCompiler.kt`：提示词第 3 条由 `executorSupportedTypesPromptClause()` 现拼；`val ALLOWED_TYPES get() = executorSupportedActionTypes`（不手抄第二份）；删 key 形状判据、加 WAIT 非负 ms 形状判据、`else -> reject` 兜源校脱节；比较一律引 `ActionType.*` 常量。
- `app/…/recorder/ModelLedgerGate.kt`（新）：词表档判据本体（`firstUnsupportedModelAction` / `modelLedgerSupportedTypesCopy` / `unsupportedModelLedgerCopy`）。**刻意不 import `:byok`**（住 `recorder/` 执行路径，红线 G），支持集由调用方注入；整本点名第一条 `index`；话术含支持集 + 整本不落账 + "拒一次=重编一次不省钱" + 裁决号 S31-B3。
- `app/…/compile/ByokGateway.kt`：`publish = { actions -> acceptModelActions(actions, executorSupportedActionTypes) }`——真源在 `compile/`（红线 G 允许）注入落账口，不留第四份词表。
- `app/…/recorder/session/RecorderStore.kt`：`ModelLedger` 加 `RefusedUnsupportedType(index, type, supportedTypes)`（与 `RefusedRunning` **并档不并句**，各说各话）；`acceptModelActions(actions, supportedTypes)` 先 RUNNING 档、再词表档；**刻意不看 compileBusy**（它正在编译中被调，加此门禁=自锁死，正是派单 §1-F1"别误改"）；`revalidateAfterCompile()` 扩到四格复核（含 `revalidateRunRejection()`）。
- `app/…/platform/AccessibilityGate.kt`：`COMPILE_HOLD_HEADLINE` 单源；`runGateOf(running, compileBusy) = if (running) RUNNING else stopCompileGateOf(compileBusy)`（不写第二份 `if (compileBusy)`）；`runUserCopy()` 仅 RUNNING 改档；`taskRejectionAfterStateChange` 枚举传参、V-3 请求绑定存 null 不被状态跃迁抹。
- `app/…/recorder/StepEditing.kt`：`StepEditGate` 加 `COMPILING`；**删三参 `stepEditGateOf`，四参为唯一带状态入口**（编译逼接线），COMPILING 转调同一格 `stopCompileGateOf`。
- `app/…/AppState.kt`：`taskRejectionGate: RecordGate?` + `setTaskRejection(gate, copy)` 配对同写，骑既有 plain-String `taskRejection` 槽——**不碰 core / 不加 PipelineStopCode**。
- `app/…/MainActivity.kt`：状态文本点名四入口；`editable = !running && !compileBusy`、`run_task enabled = connected && !compileBusy`；`submitTask` 前置 `runGateOf`，若 `== COMPILING` → `setTaskRejection` + 日志 `S1SMOKE submit refused gate=` 后**先于** taskAdmission return；RUNNING **不在派发口拒**（保串行语义，§3 范围纪律）。
- `app/…/service/AnytouchAccessibilityService.kt`：总线循环 `runGateOf(running, compileBusy)`，非 READY 落 `droppedRunReport(REQUEST_BUSY)` + `setTaskRejection` + 消费——兜"任务已排队、用户随后点 AI 编译"这条可达串状态；**复用 REQUEST_BUSY 不加 core 常量**；状态跃迁后加 `revalidateRunRejection()`。
- `app/…/compile/ByokCompileController.kt`：`when(ledger)` 加 `RefusedUnsupportedType` 分支（`published=false` / `errorKind=COMPILE_REJECT` / `stage="ledger"` / 复用 `unsupportedModelLedgerCopy`），与 RUNNING 各一档。
- `app/…/executor/NodeTaskRunner.kt`：**仅加注释、不 import `:byok`**（守红线 G），行为定义 + 指向双向对拍锁。
- `scripts/byok-smoke.sh`：E5i 字面==真源、话术去过期边指 S31-B3 + `ExecutorVocabulary`；新增 E8g/E8g2（编译拒派发→`submit refused gate=COMPILING` 且 `S1SMOKE ok=` 计数 0）、E8h/E8h2（编译拒编辑→`step edit refused gate=COMPILING` 且零次成功编辑）、E8i/E8j（两入口拒因上屏，正向取证优先、dump 未命中记 SKIP 不记红）。

worker 交付前自补的两处 watch 锁（关 3 之前主窗登记的"新门禁必须能响"）：
- `app/…/platform/AccessibilityGateTest.kt` +8 例：`runGateOf` 四格真值表 + 与 `stopCompileGateOf` 同判据对拍 + RUNNING 优先 + `runUserCopy` COMPILING 引共用头一句 + 派发口话术 ≠ 开录口 + 过期边 + 请求绑定 null 不被抹。
- `app/…/recorder/ModelLedgerGateTest.kt` 新 9 例：`firstUnsupportedModelAction`（全支持返 null / 空账不越权 / 取位序最小 / 首条即 index0 / 支持集空必拒）+ `modelLedgerSupportedTypesCopy` 字典序 + 拒因点名第 N 步与 type + "支持集只一员也不许写死四个"反抄锁 + S31-B3 号上屏。
- `app/…/executor/ExecutorVocabularyDispatchLockTest.kt` 新：**真双向可执行对拍**——反射取全部 `ActionType` 常量 + 实跑 `NodeTaskRunner.run`（RecordingDevice 假设备）+ "when 判 unsupported 集 == 真源补集"两方向 + 未知串两侧同拒 + 空集/漂移兜。
- `byok/…/ExecutorVocabularyTest.kt`（新 9-10 例）：源==四常量 + 提示词半句逐字 + 脚本 `-e` 名单逐字对拍 + E5i 不留过期边 + key 编译即拒 + wait 形状。
- `app/…/compile/ByokCompileControllerTest.kt` +行：`RefusedUnsupportedType → ByokReport` 接线且与 `RefusedRunning` 各说各话（§4-2 不并档）。
- `byok/…/DslCompilerTest.kt` / `ScreenContextTest.kt`：随批适配（reject 话术点名支持集；ScreenContext fixture `key/back`→`click`，因 key 已出授权集——测的是闭合态上行内容不受影响，属正确适配非削弱）。

## 关 2 · JVM（`--rerun-tasks`、单变体、分模块 `<testsuite tests>` XML 求和）

| 模块 | 数 | failures | errors |
|---|---|---|---|
| app | 335 | 0 | 0 |
| byok | 69 | 0 | 0 |
| contracts | 19 | 0 | 0 |
| **合计** | **423** | **0** | **0** |

基线 384（app306/byok59/contracts19，31-B）→ 423，**+39**（app +29＝F1/F2 派发面+词表档新例，byok +10＝`ExecutorVocabularyTest`+`DslCompilerTest`，contracts 冻住不动 19）。残留 1-arg `acceptModelActions` / 3-param `stepEditGateOf` 全树扫无（`acceptModelActions` 生产唯一调用者 `ByokGateway:63` 两参）。

## 关 3 · 双红线 + 一处主窗自纠（本批价值点）

`redline-probe.sh` **PROBE_RC=0**：F/G/H/I 逐条独立验能 FAIL、撤探针回 PASS。

**主窗独立重跑当场抓到 worker 未抓的一处假红并自纠**（worker 触顶未自检，这条只有独立重跑能兜——纪律第二次兑现）：首跑 `ci-local.sh` **RC=1**，红在红线 G——但它命中的是 `ModelLedgerGate.kt:13` 的**注释散文**"本文件刻意不 import `com.anytouch.byok`"，红线 G 的 grep 逐字扫 `com\.anytouch\.byok` 分不清注释与 import。**全树扫执行路径六目录 + `core/`：无一条真 `import com.anytouch.byok`（`grep '^[[:space:]]*import …com\.anytouch\.byok'` 零命中）⇒ 结构合规，纯假红。** 处置守"改选手不改裁判"：只把该行注释里的包路径字面量换成依赖线记号 `:byok`（同文件 :15 已用此记号，语义不变），**不放宽红线 G 本体**。复跑 `ci-local.sh` **CI_RC=0** 九线全清；且 `redline-probe` 的 **PROBE-G 复证真注入仍会 FAIL** ⇒ 撤字没把锁撤松。

## 关 4 · 模拟器设备关（emulator-5554，与待办 17 的 K40 半边不抢真机）

同构建 `app-debug.apk`（13:39 由 `:app:assembleDebug` 产）`adb install -r`（**禁 force-stop**，`lastUpdateTime` 复新、无障碍绑定存活、INTERNET 在册）。

- **device-smoke.sh**：ALL PASS 13/13。**首轮 C5 假红已归因复跑即绿**：首轮 C5「高危超时默认拒绝」读 `NODE_NOT_FOUND`（L2 系统 Settings「Passwords & accounts」零命中，是定位 miss 而非高危逻辑失效，用的 click/scroll/type_text 全在授权集内）→ 同构建原样重跑 `stop="PASSWORD:password"` 命中、13/13。签名与 31-A/31-B 已归档的"装后首趟冷 dump 少读"**同款**（自家 Compose 折叠线以下节点不进无障碍树 + 冷进程视口未坐稳），非产品缺陷。`device-smoke.sh` 本批未改，排除面测项回归。
- **ui-smoke.sh**：ALL PASS 41/0/0（0 SKIP）。**同样首轮 5 条"读数 miss"假红**（U1b/U6b/U8c/U10d/U11b）复跑即全绿——同"冷进程首趟 a11y 树未坐实"签名，已按"未归因读数不得判产品的罪"复跑背书，不据此判 S3-F 回归。
- **s2-smoke.sh ROUNDS=10**：PASS 闭环达标 **10/10 轮全绿 ratio=1.00**（录→编→放整链零回归——F1/F2 恰动了落账口/编辑门禁/执行分派，这条最重）。
- **keyed byok-smoke.sh**（`byok-credential-inject.sh` 代填尾 4 `***JgYp`，屏上只出尾 4；无 `E_WIPE`；`E9_APK` 同构建）：**断言 40 条 / 跳过 6 / 无失败项 / RC=0**。S3-F 关键：
  - **E8g PASS** `S1SMOKE submit refused gate=COMPILING via=adb_inject` + 全句点名「执行任务」⇒ F1 派发口编译互斥上设备；**E8g2 PASS** `S1SMOKE ok=` 计数 0 ⇒ 被拒=执行真没开始。
  - **E8h PASS** `S2SMOKE step edit refused gate=COMPILING` ⇒ F1 编辑口；**E8h2 PASS** 零次成功编辑 ⇒ 账真没动。
  - **E5i PASS 不再间歇** `types=[click,]` 全在 `ExecutorVocabulary` 真源内 ⇒ F2 端到端。
  - **E8i/E8j SKIP（诚实，不记红）**：两入口新红字的**单屏 dump 未命中**（编译在跑禁 dump + BYOK 面板在折叠线以下），日志侧 E8g/E8h 已取正向证据；屏上像素复验并入待办 17 K40。
  - E9a PASS（`install -r` 收走进程 pid 换、Keystore 与 Key 跨进程留住）；E11a~d SKIP（未 `E_WIPE=1`，属 K40 待办 17）。

## 关 5 · 交付账与状态

- S3-F 判其范围内 **ACCEPTED**：F1/F2 的功能行为在设备（模拟器）已验（拒派发/拒编辑/词表真源），编译互斥无 MIUI 特异边（雷12 是 TEXT 派发持久拒，与本批的互斥/词表无关），模拟器设备关对本批范围充分，不像 31-B 那样需挂 K40 才敢翻。
- **残口如实钉**：E8i/E8j"两入口红字上屏像素"仅日志 + JVM（`ByokCompileControllerTest`/`AccessibilityGateTest` 锁 userCopy 现算与 `setTaskRejection`）+ 与录制面共用同一 plain-String 渲染槽 三方兜，屏上像素待 K40 byok-smoke（待办 17）。
- **不翻 31-B ACCEPTED、不关切片 E 的 E11、不碰 core 冻结面**——三件同卡老板的手（待办 17：K40 一次接机，`E_WIPE=1` 跑 E11 + 31-B device-smoke K40 复验）。
- 消费裁决：`S31-B2`（并入两入口）、`S31-B3`（收紧词表编译侧拒并显式）、`S31-B5`（String 引常量）——均落地，无新增裁决。

## 诚实边界（不洗）

- worker 后台代理**触顶 150 次工具调用被切断、未干净自检**——本批绿全由主窗独立重跑背书；假红一处（红线 G 命中注释）只有独立重跑抓得到。
- 模拟器首趟冷 dump 少读（C5 + ui 五条）**两轮设备关各重现一次**、清态/复跑即绿——归因为读数层冷启动，非产品；但"首趟不稳"本身是已知设备事实，脚本未加冷启动自复核前置（延后项，非本批范围）。
- `GcmBlobCipher` 与真 HTTPS/真 Keystore 三条 JVM 覆盖=0（设备缝只能设备上判，本批模拟器 keyed 判到 E5/E8g/h/E9a）；E11 双槽清除 + K40 屏上红字复验待老板手指。
