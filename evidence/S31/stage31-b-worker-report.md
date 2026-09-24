# STAGE-31-B worker 自述（续跑）：A1 + A2 + A3 执行器判据下沉

> 派单：`orders/ANYTOUCH-S31-B-DISPATCH.md`（本批差异）+ 母单 `orders/ANYTOUCH-S31-ORDER.md` §1 表 A1/A2/A3 行、§2 硬约束、§5 自检门
> 裁决：`orders/RULINGS-20260922.md` 的 S31-B1（放行）/ B5（`type: String`）/ B6（保留半成品为基座、续跑）
> 工作树：`D:/Anytouch-31b`　分支 `wip/s31-b`　基线 HEAD `db7d90c`
> 本文件角色：**边做边写**。任何一步被打断，磁盘上这份就是最新状态。
> 纪律：全程不 `git commit` / `push` / `checkout` / `stash`；不调用 adb、不起/连任何设备或模拟器（派单书 §0-2）。

## §0 施工状态看板（每步追加，最新在上）

- [x] 3.x 四关自检：关1 编译 RC=0 / 关2 JVM 306+59+19=**384** 零失败（基线 363，净增 21）/
      关3 mutation 13 探针逐条能红、撤改后回绿且 sha256 逐字还原 / 关4 双红线 RC=0（原始输出见 §3、逐轮表见 §4）
- [x] 2.x JVM 用例：`ExecutorDecisionsTest.kt` **20 例落盘**（A1 七例含封顶与三档 / A2 四例含反向锁 / A3 九例含反例四条），
      另在 `NodeTaskRunnerTest.kt` 补 1 例队列级 WAIT 路由锁（§5-D1）
- [x] 1.x 平台侧接线（A1 转调 `redispatchPlan` / A2 转调 `textDispatchRoute` / A3 转调 `landedViaTreeScan`）
      —— 前一 worker 已完成，本轮主窗逐行 diff 复核 + 本 worker 以四关兑付；判据在平台侧零残留（§2 对照表逐条对盘）
- [x] 0.3 本轮续跑（第三任 worker）：写测 + 跑关2/关3/关4 + 更正前任 worker 自述的三处准确性缺陷（见 §7）
- [x] 0.2 建本自述（骨架）
- [x] 0.1 读盘四份：派单书 / 母单 §1 A1-A3 + §2/§5 / RULINGS S31-B5-B6 / 基座 `ExecutorDecisions.kt`（112 行）

---

## §1 改了哪些文件（每处一句话说为什么）

| 文件 | 状态 | 做了什么 |
|---|---|---|
| `app/src/main/kotlin/com/anytouch/app/executor/ExecutorDecisions.kt` | 基座（前一 worker 留，未入 git）**本批唯一判据实现** | A1/A2/A3 三条判据的 android-free 纯函数面。worker 未改一个字节（主窗 B6 已逐条对盘核过等价性），只做接线与加测 |
| `app/src/main/kotlin/com/anytouch/app/executor/NodeTaskRunner.kt` | 改动（接线） | 三条判据的**原分支删净**，原位置只剩"取数（沉降/重定位/设备派发/摊平活树）+ 转调"；无镜像、无原逻辑保留 |
| `app/src/test/kotlin/com/anytouch/app/executor/ExecutorDecisionsTest.kt` | 新增（**由本轮第三任 worker 落盘**；前任 worker 写 §1 这一行时该文件还不存在，见 §7-1） | 20 例 JVM 锁：A1 七例（三档选取、`performed=true`→Skip、WAIT→Skip、**`attempt` 0→RetryOnce / 1→GiveUp 的恰好一次**、模拟推进环锁"第二次仍 false 绝不第三派"、非 WAIT 类型含未支持类型照黑名单进重派、三档互不相同）；A2 四例（真值表四格、setTextOk=false 时 paste 单独决定合计、两种失败形态同在此层、**`dispatched==true` 不得当已落字的反向锁**）；A3 九例（正例含 AppCompatEditText 子串类名、**反例四条各一例**、文档序优先、空树返回 null、可编辑类无文本不命中、两条件矩阵）。语料全 ASCII，命名一律写成判据句 |
| `app/src/test/kotlin/com/anytouch/app/executor/NodeTaskRunnerTest.kt` | 改动（+1 例，25 → 26） | 新增 `wait步在任何设备拒答下也绝不产perform_failed`：设备全体拒答 + WAIT 携带 type_text 级线索的队列里，WAIT 连一次派发都不产生（`device.performed` 为空、两步全 ok、无停机回执）——这就是 §5-D1 那条"唯一分叉输入不可达"的落盘锁 |
| `local.properties` | 新增（gitignore 内，`build.gradle` 无关） | 工作树缺该文件，`:app:compileDebugJavaWithJavac` 报 "SDK location not found"；从主树**逐字复制**（本轮 `sha256sum` 复核：与 `D:/Anytouch/local.properties` 哈希逐字相同 `3d3d526b…5316`，内容 `sdk.dir=C\:\\Users\\Administrator\\AppData\\Local\\Android\\Sdk`），主树期间零写入 |

未碰（照 §2-3 冻结清单）：`core/` 契约、`safety/`、`RejectionReason`、`recorder/session/`、`AnyNode` 路径编码与空白 resourceId 判据、
`recorder/capture/CaptureClues.kt` 与 `CaptureBridge.kt`（31-A 已结案，A4/A5 一行未动）、`app/build.gradle.kts`。
`NodeTaskRunner.kt` 内不在本批三判据的其余分支（定位轮询 `locateWithRetry`、安全阀三档、落字复核 :351-425 的整步重试边、
回执映射）一行未动；`KEY`/`BACK` 执行档未补（老板裁 S31-B3 走"收紧词表"，那条路没被选）。

## §2 搬移前后判据对照（原行号 → 新纯函数名，逐条）

原行号 = 基线 HEAD `db7d90c` 的 `NodeTaskRunner.kt`（与派单书 §1 表的区间逐字吻合）。

### A1 明示拒绝后的重派计划（原 :309-335）

| 原判据分支（原行） | 新落点 | 平台侧剩下什么 |
|---|---|---|
| `if (!dispatched && action.type != ActionType.WAIT)`（:310）"该不该重派" | `redispatchPlan(performed, action.type, attempt)` → `Redispatch.RetryOnce`（ExecutorDecisions.kt:53-58，WAIT 档在 :55） | `when` 的三档**落点**，不含任何布尔/类型/轮数比较 |
| 沉降 `delay(settleMs * 2)`（:315） | 留平台侧（RetryOnce 分支内，原字面） | 真实等待是副作用，搬不进纯函数 |
| 按原线索重定位 `locator.locate(device.root(), request) as? LocatorHit`（:316）+ `if (again != null)`（:317） | 留平台侧（取数：定位结果不是判据） | 原样 |
| 重派派发 `when (action.type) { CLICK/SCROLL/TYPE_TEXT/else }`（:318-333） | 留平台侧（军令点名的假下沉形态：`performAction` 不进签名） | 原样，只把 TYPE_TEXT 那一档的合计边换成 A2 转调 |
| "仍 false 才收 `perform_failed`"：`if (!dispatched) { return StepOutcome(failure(EXECUTOR_ERROR …), gateReceipt("perform_failed")) }`（:336-349） | `Redispatch.GiveUp` 档（ExecutorDecisions.kt:56 `attempt >= MAX_REDISPATCH_ROUNDS`） | 该 failure/gateReceipt 构造整块搬进 GiveUp 分支，字面未改（`message`、`evidence.index_path`、`stop_reason="perform_failed"` 逐字同） |
| "封顶恰好一次"由 `if` 只写一遍隐式承担（无显式计数） | `MAX_REDISPATCH_ROUNDS = 1` + `attempt >= MAX`（ExecutorDecisions.kt:34,56）——轮数上限从此**只有一处** | 平台侧 `attempt += 1` 是自增取数，不再自己判"到没到顶" |

### A2 TYPE_TEXT 两通道派发边（原 :305 与 :329）

| 原判据分支（原行） | 新落点 | 平台侧剩下什么 |
|---|---|---|
| `device.setText(t, input) \|\| device.pasteText(t, input)`（:305 首派） | `textDispatchRoute(setTextOk, pasteOk)`（ExecutorDecisions.kt:73） | 两次设备调用的**短路顺序**：`val pasteOk = if (setTextOk) false else device.pasteText(...)`——`setTextOk=true` 时 PASTE 一次都不派（与 `\|\|` 逐字等价；把它表达进纯函数=要求无条件派发 PASTE，反而是语义变化） |
| `device.setText(again, input) \|\| device.pasteText(again, input)`（:329 重派） | 同一函数，同一短路写法 | 同上 |
| "成败终裁不在这里"（:304 注释所述；真裁在 :351 起的落字复核） | 未搬：`dispatched=true` 之后仍必须过 A3 才判成功。用例侧反向锁一条（`textDispatchRoute(true,false) 不代表已落字`） | :351-425 复核边一行未动 |

### A3 兜底扫树（原 :451-458，`awaitLanded` 内）

| 原判据分支（原行） | 新落点 | 平台侧剩下什么 |
|---|---|---|
| 外层 `if (want.isNotEmpty())`（:451） | `landedViaTreeScan` 首行 `if (want.isBlank()) return null`（ExecutorDecisions.kt:107） | 无（该早退是判据，已搬走） |
| `device.root()?.preOrder()`（:456 前半） | 留平台侧=取数 | `?: emptyList()` 后 `.map { LandedNodeFact(it.className, it.text) }` 摊平，只喂判据要读的两字段 |
| `firstOrNull { it.className?.contains("EditText") == true && it.text?.contains(want) == true }`（:456-458） | `landedViaTreeScan` 的 `firstOrNull`（ExecutorDecisions.kt:109-111，含 `EDITABLE_CLASS_MARKER` 常量 :87） | 无——两条件与文档序 `firstOrNull` 一条不留在平台侧 |
| `if (landedElsewhere != null) return landedElsewhere.text?.trim()`（:459） | 留平台侧（把命中节点的文本读回给调用方，属取数/返回形状） | 原字面 |
| `text = device.textOf(node)?.trim()` + `if (text?.contains(want) == true) return text`（:449-450）**句柄活读优先**那半边 | **按 B6 明令未搬**（那是活读设备句柄的取数，不是判据）；续跑时不动这条 | 原样 |

## §3 四关命令 + 原始输出粘贴

### 关 1：编译

```
$ ./gradlew :app:assembleDebug
> Task :app:compileDebugKotlin
> Task :app:compileDebugJavaWithJavac NO-SOURCE
> Task :app:dexBuilderDebug
> Task :app:packageDebug
> Task :app:createDebugApkListingFileRedirect
> Task :app:assembleDebug

BUILD SUCCESSFUL in 8s
39 actionable tasks: 22 executed, 17 from cache
RC=0
```

（首跑 RC=1：工作树无 `local.properties` ⇒ "SDK location not found"。按 §1 表最后一行从主树复制该 gitignore 文件后复跑 RC=0；主树 `D:\Anytouch` 期间零写入。）

（本轮第三任 worker 复验：关4 的 `bash scripts/ci-local.sh` 第 [1/4] 步就是
`./gradlew :core:contracts:build :byok:build :app:assembleDebug :tools:compiler:build`，
第 [2/4] 步跑四目标测试（contracts/byok/app `--rerun`/tools）。整脚本本轮 RC=0（尾部原始输出见下面关4 段）
⇒ `:app:assembleDebug` 在**含本批新代码与新测的终态工作树**上再次通过，关1 不止是前任 worker 当时那一次。
注意口径差别：ci-local 的 app 目标是 `--rerun` 而非 `--rerun-tasks`，所以**分模块计数以本段关2 的三次 `--rerun-tasks` 为准**。）

### 关 2：JVM

命令（工作树根 `D:/Anytouch-31b`，三条单变体模块目标全部 `--rerun-tasks`）：

```
$ ./gradlew :app:testDebugUnitTest --rerun-tasks
...
BUILD SUCCESSFUL in 23s
26 actionable tasks: 26 executed
RC=0

$ ./gradlew :byok:test :core:contracts:test --rerun-tasks   # 两个模块目标一次调用，同一 --rerun-tasks
...
BUILD SUCCESSFUL in 5s
8 actionable tasks: 8 executed
RC=0
```

分模块计数口径=**单变体目录**下每份 `<testsuite>` 的 `tests=` 属性求和（`failures/errors/skipped` 同读，全 0）；
不 glob 跨变体目录、不数日志行。工作树里 `test-results/` 下只有这三个目录（app 只有 `testDebugUnitTest` 一个变体）：

| 模块 | testsuite XML 目录 | suite 份数 | tests | failures | errors | skipped |
|---|---|---|---|---|---|---|
| `:app` | `app/build/test-results/testDebugUnitTest/` | 26 | **306** | 0 | 0 | 0 |
| `:byok` | `byok/build/test-results/test/` | 7 | **59** | 0 | 0 | 0 |
| `:core:contracts` | `core/contracts/build/test-results/test/` | 2 | **19** | 0 | 0 | 0 |
| **合计** | | 35 | **384** | **0** | **0** | **0** |

与基线对照：HEAD `db7d90c` 基线 285 / 59 / 19 = **363** → 本批 **384**，净增 **+21**，零失败。
增量逐类核到 XML：
`TEST-com.anytouch.app.executor.ExecutorDecisionsTest.xml` tests=**20**（本批新文件），
`TEST-com.anytouch.app.executor.NodeTaskRunnerTest.xml` tests=**26**（原 25，+1 = §5-D1 的 WAIT 路由锁）。
20 + 1 = 21 = 306 − 285，账面对得上；`:byok` 与 `:core:contracts` 一行未动。

### 关 3：mutation 自查

命令（每条探针一次独立运行，只跑新文件）：

```
$ ./gradlew :app:testDebugUnitTest --tests '*ExecutorDecisionsTest*' --rerun-tasks
```

探针脚本 `probe.sh` 放在**工作树之外**（`%TEMP%/s31b/probe.sh`，不入库）：每轮只做三件事——
按字节替换一处字面（先断言该字面全文件唯一）→ 跑上面那条目标 → `cp` 还原并按 sha256 校验。
P1/P2 两轮是手工改 + `cp` 还原（还原后 `sha256sum` 输出与原件逐字一致，另用 `sha256sum -c` 复验一次 OK）；
P3-P13 由脚本自动还原，每轮末尾打印 `RESTORED BYTE-EXACT sha256=0007e0ff…4ac1`。

全绿终态（撤改后复跑，原始输出）：

```
$ sha256sum app/src/main/kotlin/com/anytouch/app/executor/ExecutorDecisions.kt
0007e0ffef90c52465aef2e4303105d8ce0490dd27d23c7866e1ae4b077d4ac1 *app/.../ExecutorDecisions.kt
$ ./gradlew :app:testDebugUnitTest --tests '*ExecutorDecisionsTest*' --rerun-tasks
BUILD SUCCESSFUL in 7s
RC=0
```

### 关 4：双红线

```
$ bash scripts/ci-local.sh
...
BUILD SUCCESSFUL in 2s
6 actionable tasks: 6 executed
==> [3/4] 红线 grep
  redline A clean (no network-call keywords under core/)
  redline B clean (no teammate-workspace references)
  redline C clean (no network-call keywords under app/src/main)
  redline D clean (no gesture/coordinate injection)
  redline E clean (manifest free of SYSTEM_ALERT_WINDOW)
  redline F clean (network code confined to byok/ and tools/)
  redline G clean (execution path cannot see the compiler module)
  redline H clean (no key-bearing log statements)
  redline I clean (no plaintext credential storage channel in app)
==> [4/4] ci-local PASS
RC=0
```

```
$ bash scripts/redline-probe.sh
PROBE-F OK: 能 FAIL（REDLINE-F HIT: byok/ 与 tools/ 之外出现网络代码）
PROBE-G OK: 能 FAIL（REDLINE-G HIT: 执行路径 import 了编译模块（执行期零网络的结构锁失效））
PROBE-H OK: 能 FAIL（REDLINE-H HIT: 日志语句里出现密钥形态字段）
PROBE-I OK: 能 FAIL（REDLINE-I HIT: 出现明文落盘通道（Key 只能进 Keystore 加密 blob））
REDLINE-PROBE PASS: F/G/H/I 四条逐条独立验证能 FAIL，且撤探针后门禁回到 PASS
RC=0
```

本批新增/改动的判据文件确实 android-free：`ExecutorDecisions.kt` 的唯一 import 是 `com.anytouch.contracts.ActionType`
（母单 S31-B5 裁的 `type: String` 就靠它比较常量）。`executor/` 整目录 grep
`Log\.` / `AccessibilityNodeInfo` / `AccessibilityEvent` / `android.content.Context` 的命中只有
`ExecutorDecisions.kt:12` 那句"本文件 android-free"自陈注释，零真实引用；平台侧本轮也没有新增日志调用点，
故红线 H 的 `Log.x(` 字面口径无回归风险。

mutation 之后复跑 JVM（撤改回绿 + 计数复核，与 §3 关2 同口径重测一次）：

```
$ ./gradlew :app:testDebugUnitTest --rerun-tasks        → BUILD SUCCESSFUL in 21s   RC=0
$ ./gradlew :byok:test :core:contracts:test --rerun-tasks → BUILD SUCCESSFUL in 4s    RC=0
app: suites=26 tests=306 failures=0 errors=0 skipped=0
byok: suites=7 tests=59 failures=0 errors=0 skipped=0
core/contracts: suites=2 tests=19 failures=0 errors=0 skipped=0
```

工作树终态（`git status --short`，探针零残留：`probe_tmp/` 与 `app/.../probe/` 均不存在）：

```
 M app/src/main/kotlin/com/anytouch/app/executor/NodeTaskRunner.kt
 M app/src/test/kotlin/com/anytouch/app/executor/NodeTaskRunnerTest.kt
?? app/src/main/kotlin/com/anytouch/app/executor/ExecutorDecisions.kt
?? app/src/test/kotlin/com/anytouch/app/executor/ExecutorDecisionsTest.kt
?? evidence/S31/stage31-b-worker-report.md
```

## §4 mutation 自查三列表

新文件共 20 例，全部至少被一条探针打红过（下表"实际红"列是当轮 `tests completed, N failed` 的 N）。

| 探针（改了哪一行 / 怎么改） | 期望红的那几例 | 实际红的例数与例名 |
|---|---|---|
| P1 `ExecutorDecisions.kt:55` 去掉 WAIT 守卫（删 `if (type == ActionType.WAIT) return Skip`） | `WAIT永不进入重派` | **1** = `WAIT永不进入重派` |
| P2 `:56` `attempt >= MAX` 改成 `attempt > MAX`（放宽封顶） | `attempt到1即GiveUp` / `第二次仍false绝不第三派` / `三档落点互不相同` / `除WAIT外的类型…` | **4** = 上述四条，一条不差 |
| P3 `:34` `MAX_REDISPATCH_ROUNDS = 1` 改成 `2`（封顶搬去常量） | 同 P2（证明"恰好一次"这半条只住在常量一处） | **4** = `attempt到1即GiveUp` / `第二次仍false绝不第三派` / `三档落点互不相同` / `除WAIT外的类型…` |
| P4 `:54` `if (performed)` 反向成 `if (!performed)` | `派发已被接收则Skip` / `明示拒绝且非WAIT` 及所有下游档 | **6** = `除WAIT外的类型…` / `第二次仍false绝不第三派` / `三档落点互不相同` / `明示拒绝且非WAIT 首派后判为重派一次` / `attempt到1即GiveUp` / `派发已被接收则Skip 与轮数和类型无关` |
| P5 `:73` `setTextOk \|\| pasteOk` → `&&`（合计写成同真） | 真值表四格 + 三种含 paste 的格 | **4** = `两通道真值表四格锁死` / `SET_TEXT明示拒false时PASTE通道单独决定合计` / `虚报true与明示拒false两种失败形态都在同一合计里` / `dispatched为true只代表派发被接收不得当成已落字` |
| P6 `:73` → `= setTextOk`（PASTE 通道不上场） | 同 P5（少了那条反向锁，因为它不喂 A3） | **3** = `两通道真值表四格锁死` / `SET_TEXT明示拒false时PASTE通道单独决定合计` / `虚报true与明示拒false两种失败形态…` |
| P7 `:110` 两条件的 `&&` 翻成 `\|\|` | 反例①③ + 两条件矩阵 + 无文本 + 文档序 + A2 反向锁 | **6** = `可编辑类但无文本不得命中` / `判落要求两条件同时成立` / `反例三 className为null不得命中` / `反例一 TextView含同词不得判落` / `dispatched为true只代表派发被接收…` / `两个节点都合格时文档序第一个胜出` |
| P8 `:107` 删掉 `if (want.isBlank()) return null`（放 Kotlin 恒真陷阱进门） | 反例② | **1** = `反例二 want为空白不得短路成命中` |
| P9 `:108` `want.trim()` 去掉 trim | 反例④ | **1** = `反例四 含词但要trim后才等必须仍命中` |
| P10 `:109` `firstOrNull` → `lastOrNull` | 文档序优先 | **1** = `两个节点都合格时文档序第一个胜出` |
| P11 `:110` `className?.contains(..) == true` → `!= false`（把 null 类名放行） | 反例③（+ 反例①连带） | **2** = `反例三 className为null不得命中` / `反例一 TextView含同词不得判落` |
| P12 `:110` 文本侧 `contains(key)` → `trim() == key`（把子串包含写成全等） | 正例（含词但前后有字）+ 反例④ | **6** = `EditText含输入即判落` / `判落要求两条件同时成立` / `反例一 TextView含同词不得判落` / `dispatched为true只代表派发被接收…` / `两个节点都合格时文档序第一个胜出` / `反例四 含词但要trim后才等必须仍命中` |
| P13 `:109` 给空树兜一个假节点（`nodes.ifEmpty { listOf(LandedNodeFact(EDITABLE_CLASS_MARKER, want)) }`）=凭空编造命中 | 空树 sanity | **2** = `空树返回null而不是命中` / `dispatched为true只代表派发被接收…` |

**没有一条探针 stayed green**：13/13 至少打红一例（P13 是合成的"凭空出节点"探针，专为锁 20 例里唯一
没有自然反向改动的空树 sanity 而放），撤改后回绿（RC=0），且撤改前后 sha256 一致。
20 例逐条对表：**每一例都在上面某轮的红色名单里出现过**，无一例恒真（§2-4 的硬要求）。

## §5 与母单/派单字面的偏差

### §5-D0（已裁，照录，非 worker 新造）S31-B5：`redispatchPlan` 形参 `type` 的类型

母单 §1 A1 "下沉缝"列的字面签名是 `redispatchPlan(performed: Boolean, type: ActionType, attempt: Int)`。
该签名在磁盘上写不出来：`core/contracts/src/main/kotlin/com/anytouch/contracts/Constants.kt:21-30` 的
`ActionType` 是 `object` + `const val String`（注释自陈"非严格枚举，留扩展位"），`Action.type` 在契约里就是 `String`。
主窗 2026-09-24 裁 **S31-B5=采纳 `type: String`**，三档结论与"封顶恰好一次"照母单字面不动；
比较仍写 `type == ActionType.WAIT`，判据语义与原字面 `action.type != WAIT` 逐字同。
基座 `ExecutorDecisions.kt:53` 已按此落地（`fun redispatchPlan(performed: Boolean, type: String, attempt: Int): Redispatch`），
worker 侧仅登记，不重复裁。

### §5-D1（本轮自验，与主窗结论一致）A1 接线把两个 `if` 换成 `while(true)` + `redispatchPlan`：唯一分叉输入不可达

主窗已核得这条判断，本轮**自己重核一遍**（读 `NodeTaskRunner.kt` 全量 + `grep` 调用点）：

- **改法**：基线 `db7d90c` 的 `if (!dispatched && action.type != ActionType.WAIT) {…}`（:310-335）
  加 `if (!dispatched) { return failure(EXECUTOR_ERROR …) ; perform_failed }`（:336-349），
  现在是 `var attempt = 0; while (true) when (redispatchPlan(dispatched, action.type, attempt)) { Skip->break /
  RetryOnce->{attempt+=1; 沉降+重定位+重派} / GiveUp->return perform_failed }`（工作树 :317-369）。
- **可达输入逐格对盘同形**（`runNodeStep` 只有一个调用点 `NodeTaskRunner.kt:109`，只送 CLICK/SCROLL/TYPE_TEXT）：
  ① `dispatched=true` → 旧：不进 if、不进 `if(!dispatched)`，往下走复核；新：Skip→break，往下走复核。同。
  ② `dispatched=false` + `again!=null` + 重派成功 → 旧：出 if 后 `dispatched=true` 往下走；新：第二轮 Skip→break 往下走。同。
  ③ `dispatched=false` + `again==null`（重定位空手）→ 旧：`dispatched` 保持 false → 收 perform_failed；
  新：RetryOnce 内不改 `dispatched`，第二轮 attempt=1 → GiveUp → 同一份 failure/gateReceipt 字面。同。
  ④ `dispatched=false` + 重派仍 false → 同 ③ 的路径，收 perform_failed。同。
  轮数上界：每次 RetryOnce 必 `attempt += 1`，而 `attempt >= 1` 即 GiveUp（GiveUp 直接 return），
  故环最多两轮，不存在无限循环输入。
- **唯一分叉输入 = WAIT 进到 `runNodeStep`**：旧代码在 WAIT 且 `performed=false` 时会**收 perform_failed**
  （`when` 的 `else -> false` 让 WAIT 的 `performed` 恒 false，而 `if (!dispatched)` 不看类型）；
  新代码给 WAIT 判 Skip → 出环 → 一路走到 `success(...)`。两者对这条输入**不同形**。
  而它**不可达**：`run()` 的 `when (action.type)`（`NodeTaskRunner.kt:98-121`）把 WAIT 送去 :99-107 的 `delay` 分支，
  :109 只列 CLICK/SCROLL/TYPE_TEXT，:111 的 else 收其余类型；`runNodeStep` 是 private 且全仓仅此一处调用（已 grep 证实）。
- **落盘锁**：新增 `NodeTaskRunnerTest.wait步在任何设备拒答下也绝不产perform_failed`
  （设备 `succeed=false`，队列含一条裸 wait 与一条带 `resource_id`/`text`/`input` 全线索的 wait）：
  断言 `device.performed` 为空（WAIT 连一次派发都不产生）、两步全 ok、`report.stopped=false`。
  这条锁的正是上面那个"不可达"前提：将来谁给 WAIT 补派发分支、或把它接进 `runNodeStep`，此例必红，
  届时 :310 与 :336 的同形性不再自动成立，须重开偏差。**未改动执行器代码来迁就它**（判据一处不复制）。

### §5-D2（照母单字面接线，worker 未改签名）A3 的取数参数被**急切求值**：`want` 空白时平台多读整树

母单 §1 A3 规定的缝是 `landedViaTreeScan(nodes: List<LandedNodeFact>, want: String)`（列表形参，非惰性序列/lambda）。
接线后 `awaitLanded` 里的调用是（工作树 `NodeTaskRunner.kt:478-481`）：

```kotlin
val landedElsewhere = landedViaTreeScan(
    device.root()?.preOrder()?.map { LandedNodeFact(it.className, it.text) } ?: emptyList(),
    want,
)
```

第一参数是**实参**，Kotlin 求值策略是先算完整个表达式再进函数——所以 `landedViaTreeScan` 内 :107 那句
`if (want.isBlank()) return null` 早退**拦不住前面的摊树**。事实与爆炸半径照实登记（**不改签名**，见下面"为什么不顺手修"）：

- **哪些输入会走到**：只有 `type_text` 步（:371 的类型门）。`awaitLanded` 共三处调用：
  :378（首复核，任何 input 都到）、:389（paste 兜底复核）与 :406（整步重试后复核）——后两处包在
  :379 / :391 的 `input.isNotBlank()` 门里，**空白 input 到不了**。
  ⇒ 相对基线**净增**读树的机会只有"空白/纯空格 input 的 type_text 步"这一类（`want = input.trim()`，:465）。
- **多读多少次**：该轮循环每 250 ms 一圈（`locatePollMs` 默认 250，生产用默认值——
  `AnytouchAccessibilityService.kt:234-237` 只传 device 与 confirmer），复核窗口 4 s
  （`landedTimeoutMs` 默认 4_000）⇒ 单次 `awaitLanded` 最多 ⌈4000/250⌉+1 = **17 圈**，
  即最多 **17 次整树 `root()?.preOrder()` 摊平**，每圈分配数 = 活树节点数（无剪枝、无预算上限）。
  基线同一输入是 **0 次**（外层 `if (want.isNotEmpty())` 把整段跳掉）。
  其中还有个小缓冲：:470 的句柄活读 `text?.contains(want)` 在 `want=""` 时恒真，
  句柄读得到文本就第一圈直接 return（净增 1 次）；只有句柄活读全程返回 null（句柄失效/无文本）才会跑满 17 次。
- **非空白路径的形状差**：基线 `preOrder()?.firstOrNull { … }` 命中即停；现在是"整树 map 成 List 再 firstOrNull"，
  所以命中节点之后的节点也照样被摊一遍（每圈 ≤N 次 `LandedNodeFact` 分配）。**判据结论不变**
  （文档序第一个合格节点同一个），差在分配与遍历，不在语义。
- **上限由什么兜住**：`landedTimeoutMs`（4 s）+ 每圈圈首的 `killSwitch.isStopped()`（:468，按下停止 ≤250 ms 出环）；
  且 `preOrder()` 读的是无障碍树快照，不产生设备往返调用。
- **为什么不顺手修**：把 `want.isBlank()` 那道门往调用点复制一份（或在平台侧改成惰性序列）
  = 在平台层"为安全"再镜像一次 A3 的判据，正是母单 §2-2 与雷 18 禁的形态；把缝改成
  `nodes: () -> List<…>` / `Sequence<…>` 又超出母单钉的字面签名。故**只登记事实，不动签名**。
  若要消掉这 17 次，改法得由主窗裁（候选：母单允许的前提下把形参改成惰性，或在 :371 的类型门旁边加一条
  "空白 input 直接跳过复核"——后者会改判据落点，不推荐）。

### §5-D3（非偏差，登记以免主窗读 diff 时误判）`want` 的 trim 在两处

`awaitLanded` :465 `val want = input.trim()` 与 `landedViaTreeScan` :108 `val key = want.trim()` 各 trim 一次。
前者是基线就有的（:445），且它的产物还要喂句柄活读那半边（:470，本批按 B6 明令不搬）；
后者是母单 §1 A3 反例④要求纯函数**自身**必须扛住未 trim 的 `want`。trim 幂等 ⇒ 对任一可达输入两次
结果同一次，不构成"两种实现"，故不算 §2-2 的镜像；登记在此供逐行 diff 时对照。

## §6 未完成项与原因

- **设备关未跑 = 按派单书 §0-2 交主窗**：本批 worker 禁止调用 adb、禁止起/连任何设备或模拟器；
  母单 §5-5（零回归关：模拟器全量 device-smoke / ui-smoke / s2-smoke + K40 真机 byok-smoke / device-smoke 复验）
  由主窗补跑并**记在主窗头上**。worker 侧只交 JVM 四关。本批动了派发与复核边（派单书 §0-1），
  所以**这句"测试全绿"单独不成证**：§3 关2/关3 只锁判据本身，`NodeTaskRunner` 队列级只有那 1 条新 wait 路由锁 +
  既有 25 例回归，派发/复核边的真机形态（Compose 换节点、K40 持久拒）仍只有设备关能兑付。
- **§5-D2 的多读树未消**：按上面"为什么不顺手修"，等主窗裁；未裁前 worker 侧不动签名、不加平台侧早退。
- **`KEY`/`BACK` 执行档未补**：非本批范围（母单 §1 三行为限；老板裁的 S31-B3"收紧词表"那条路未被选，§1 末段已登记）。
- **worker 侧无法核 A3 正例的设备事实**：母单 A3 的"正向半边已有设备证据"引用的是既有设备档案，
  本批只补反向半边（四条反例全在 JVM 侧锁死），无新增设备断言。

## §7 对前任 worker 自述的准确性更正（本轮逐条对盘）

1. **§1 的 `ExecutorDecisionsTest.kt` 行**：前任 worker 写这一行时**该文件并不存在**（本轮开工前
   `ls app/src/test/…/executor/` 只有 `NodeTaskRunnerTest.kt`；`git status --short` 的未跟踪文件里也没有它）。
   那行是按"打算交付什么"预写的，不是"已经交付了什么"。本轮已把它改成实际落盘的 20 例清单，
   并新增 `NodeTaskRunnerTest.kt` 一行（前任的 §1 完全没登记这个文件的改动）。**§1 是先写于文件存在的**，照实说清。
2. **A1 常量的名字**：§2 原引 `MAX_REDISPATCH_ATTEMPTS`（两处），磁盘上的字面是
   `MAX_REDISPATCH_ROUNDS`（`ExecutorDecisions.kt:34`，用在 :56）。已全量改为真名。
3. **§0.1 的行数**：写的是"基座 `ExecutorDecisions.kt`（113 行）"，`wc -l` 实测 **112 行**。已改。
4. **§1–§2 全部 `文件:行号` 引用逐条重核**（对基线 `git show db7d90c:…` 与工作树分别核）：
   **其余引用全部为真**，无一虚指——
   `NodeTaskRunner.kt` 基线 :310 / :315 / :316 / :317 / :318-333 / :336-349（A1）、:300-306 与 :325-331、:305、:329、
   :304 注释、:351 起复核、:351-425（A2）、:443-464、:449-450、:451、:456-458、:459（A3）逐字吻合；
   `ExecutorDecisions.kt` :34 / :53-58（WAIT 档 :55）/ :56 / :73 / :87 / :107 / :109-111 逐字吻合；
   `core/contracts/…/Constants.kt:21-30`（`ActionType` 是 object + const val）与 §5-D0 所述一致；
   `local.properties` 与主树那份 `sha256sum` 相同（§1 最后一行的"逐字复制"为真，本轮补核）。
   唯一需改的是上面第 2、3 两条。

---

**结论**：关1 编译 RC=0、关2 JVM **384**（app 306 / byok 59 / contracts 19，0 失败，基线 363 净增 21）、
关3 mutation 13 探针 13 红（20 例逐条被红过、撤改后 sha256 逐字还原并回绿）、关4 双红线 RC=0 —— **worker 侧四关全绿**；
关5 设备关按派单书 §0-2 **未跑**（交主窗，记在主窗头上），§5-D2 的多读树事实按 §5-D2 登记等主窗裁。
