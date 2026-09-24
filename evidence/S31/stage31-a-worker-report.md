# STAGE-31 31-A worker 自述：A4 + A5 采集层判据下沉

派单：`orders/ANYTOUCH-S31-ORDER.md` §1 表 A4/A5 行 + §2 硬约束 + §3-31-A + §4 验收 + §5 交付前自检门
工作树基线：HEAD `19bf372`（开工时为 `38e93eb`；主窗在我施工期间落了 §5，我按 §5 补跑了五关）
日期：2026-09-23（本机 22:4x–23:2x）
纪律：全程未 `git commit`、未 `git push`、未动 git 配置；A1/A2/A3 的 `executor/NodeTaskRunner.kt` 一行未碰。

---

## §1 改了哪些文件（每处一句话说为什么）

| 文件 | 状态 | 做了什么 |
|---|---|---|
| `app/src/main/kotlin/com/anytouch/app/recorder/capture/CaptureClues.kt` | 新增 175 行 | A4/A5 两条判据的**唯一实现**住这里；文件零 `import`（android-free 自证见 §3 关 1 附） |
| `app/src/main/kotlin/com/anytouch/app/recorder/capture/CaptureBridge.kt` | 改动 `git diff --numstat` = 121 增 / 110 删（净 +11 行） | 原判据分支删净，只留"摊平取数 + 转调 + 逐字留痕"；日志话术一条字都没改 |
| `app/src/test/kotlin/com/anytouch/app/recorder/capture/CaptureCluesTest.kt` | 新增 299 行 / 31 例 | A4 段 12 例 + A5 钉字段段 12 例 + 比较判据与空白口径段 7 例（语料全 ASCII） |

未碰（照 §2-3 冻结清单）：`core/`、`recorder/session/`、`AnyNode` 路径编码、空白 resourceId 判据、`safety/`、`RejectionReason`、
`app/build.gradle.kts`（**没有** `isReturnDefaultValues`，我没加，也不需要加——新用例全部打在零 Android 依赖的文件上）。
同文件内不在本片范围的 `locatedOf`/`locatedByContent`/`scanByContent`/`contentOf`/`searchPath`/`walkParents`/`sameNode` 一行未动。

---

## §2 搬移前后判据对照（原行号 → 新落点，逐条）

原行号=HEAD `38e93eb` 的 `CaptureBridge.kt`（与军令 §0 声明的实读区间逐字吻合）。

### A4 `descendantClue`（原 546-594，KDoc 538-545）

| 原判据分支（原行） | 新落点 | 备注 |
|---|---|---|
| 广度队列 + `visited >= DESCENDANT_BUDGET → truncated=true; break`（555-559） | `descendantClueOf` 同一 while（CaptureClues.kt:62-66） | 判据搬走；平台侧 `clueTreeOf` 的 `visited < BUDGET` 只是"不再向框架要句柄"的取数上限，见 §5-D7 |
| `node.text.orNull()` 入 `texts` + `viewIdResourceName.endsWith(TITLE_ID_SUFFIX)` 时 `titleCount++/titleText` 记录（562-568） | `descendantClueOf` 同一嵌套（CaptureClues.kt:69-76） | "title 只数有文字的"这一层嵌套关系原样保留（M6 锁死） |
| `node.contentDescription.orNull()` 入 `descs`（569） | CaptureClues.kt:77 | — |
| `if (depth < DESCENDANT_DEPTH)` 才放行子节点（570-575） | CaptureClues.kt:78-80 `if (level < depth)` | M5 锁死 |
| `if (node !== source) node.recycleQuietly()`（576） | 留在平台侧 `clueTreeOf`（CaptureBridge.kt:586） | 军令 A4 明令 recycle 归平台 |
| `picked = when { truncated→null; titleCount==1→titleText; texts.size==1; texts.isEmpty()&&descs.size==1; else→null }`（578-584） | CaptureClues.kt:82-88 同序同条件，返回 `CluePick` | 四档判序一条没换 |
| 弃用留痕 `Log.i("S2SMOKE clue=path source=... titles= texts= descs=... truncated(...)")`（585-592） | 留平台侧（CaptureBridge.kt:544-552），字符串逐字相同 | 计数由 `CluePick.Rejected` 带回（CaptureClues.kt:89），不是重算 |

### A5 `pinEventClue` + 钉字段 + 雷 17 豁免边（原 110-160 调用面、208-240 判据、267-291 计数）

| 原判据分支（原行） | 新落点 | 备注 |
|---|---|---|
| `if (words.isEmpty()) return null`（214） | `pinEventClueOf` 首档 `eventText == null → Dropped(NoWord)`（CaptureClues.kt:154） | 平台侧对应 `word == null` 时连树都不扫（CaptureBridge.kt:217） |
| `textHits == 1 → 记 text`（232） | CaptureClues.kt:157 `CluePin.ByText` | — |
| `descHits == 1 → 记 desc`（233） | CaptureClues.kt:158 `CluePin.ByDesc(viaEventFieldExemption=false)` | 词来自 `event.text`、落点却是 desc＝雷 15 本体 |
| `textHits>1 \|\| descHits>1 → ambiguous=true`（234） | CaptureClues.kt:159 `CluePin.Ambiguous` | 判序（text→desc→>1）整体搬，M7/M8 锁死 |
| `if (!ambiguous && attempt < PIN_SETTLE_RETRIES) {attempt++; continue}`（237-240） | 纯函数给 `Dropped(AwaitingTree)`（CaptureClues.kt:164-166），封顶次数与 sleep 留平台侧（CaptureBridge.kt:229-232） | 0/0 与 >1 分档＝军令点名的反例 |
| `if (ambiguous \|\| eventDesc == null \|\| words.isEmpty()) → 弃用留痕`（241-249） | 纯函数给 `Ambiguous`/`Dropped(NoEvidence)`，留痕函数 `clueDropped`（CaptureBridge.kt:240-248） | 日志串逐字不变（含"含转场重扫 N 次"后缀） |
| 末尾 `return null to eventDesc` 一级免窗口验证豁免（250-255） | CaptureClues.kt:167-174 `ByDesc(desc, viaEventFieldExemption=true)` | **只给 desc 侧**；M9 锁"text 侧永走不到豁免边" |
| `pinDone`（260-265） | 留在平台侧未搬 | 它只做 Pair 拼装 + 重扫落定日志，无判据 |
| `countFieldPins` 里 `node.text.orNull()==word` / `node.contentDescription.orNull()==word`（282-283） | `fieldPinHits(word, PinFieldFact)`（CaptureClues.kt:107-108） | "各比各的字段 + trim 全等"是判据，搬走才有 JVM 覆盖；见 §5-D5 |
| `countFieldPins` 的树走形（每根 `SEARCH_BUDGET`、两侧见满 2 即停、同包过滤）（275-289） | 原样留平台侧 | 军令签名把 `treeTextHits/treeDescHits` 定为取数；日志里的命中数因此逐字不变 |
| `eventCopyWords: List<String>`（185-190） | 收成 `eventCopyWord: String?`（CaptureBridge.kt:189-194） | 见 §5-D6，可证零语义变化 |
| `String?.orNull()`（677） | 转调 `blankToNull`（CaptureBridge.kt:673） | 空白即缺失的口径全仓只留一份 |
| 常量 `DESCENDANT_BUDGET/DEPTH`、`PIN_SETTLE_*`、`TITLE_ID_SUFFIX`（685-699） | 原住平台侧，作为参数传给纯函数 | 数值单一来源，纯函数不抄第二份 |

### 复查：判据在平台侧不残留（机器化，非口头）

§5 的 "extract, don't duplicate" 不许我用嘴保证，所以对本批两个纯函数的**判据词汇**在
`CaptureBridge.kt` 里的所有残留做一次全量扫描（当前工作树，2026-09-23 23:2x）：

```
$ grep -n "titleCount\|viaEventFieldExemption\|blankToNull\|texts\.size\|descs\.size\|allowDescExemption" \
    app/src/main/kotlin/com/anytouch/app/recorder/capture/CaptureBridge.kt
220:                is CluePin.ByDesc -> return if (pin.viaEventFieldExemption) {
548:                        "titles=${pick.titleCount} texts=${pick.textCount} descs=${pick.descCount}" +
672:    /** 空白即缺失的口径只有一份（STAGE-31：判据住 [blankToNull]，本处转调）。 */
673:    private fun String?.orNull(): String? = blankToNull(this)
----- RC=0
```

四处逐条归因（这正是"原位置只剩取数与转调"的机器化形态）：
- 220 = 读 `CluePin.ByDesc` 上那个**已由纯函数算出的布尔**，决定"豁免那次用哪条日志、哪个 Pair 落点"；
  没有"要不要豁免"的判断在这里。
- 548 = `CluePick.Rejected` 带回的三个计数**只拼进日志串**（串逐字同原文），不参与判断。
- 672/673 = `orNull()` 已退化成一转调（"空白即缺失"口径的唯一份住在 `blankToNull`）。

再扫一遍"唯一性判断"可能藏身的比较形态，确认剩下的命中全在**本批范围之外**：

```
$ grep -nE "== 1|>= 1|> 1|distinct\(\)|unique" app/src/main/kotlin/com/anytouch/app/recorder/capture/CaptureBridge.kt
374:     * ② 从窗口根按框架身份正搜也搜不到——同一逻辑节点的两份副本 `uniqueId`/`window` 皆为 null，
429:        val fromEvent = meta.texts.distinct().sorted() to listOfNotNull(meta.desc).distinct().sorted()
477:                        if (hits == 1) found = Located(path, node) else node.recycleQuietly()
490:                hits == 1 && hit != null -> {
493:                        "S2SMOKE path by unique content(${if (requireKids) "严" else "宽"}档/$keyFrom): " +
533:        return texts.distinct().sorted() to descs.distinct().sorted()
603:     * 节点身份判据：同窗口内 uniqueId（API30+ 框架口径）优先，其下退回 AccessibilityNodeInfo.equals。
604:     * 设备实证：Android 14 模拟器上 `event.source` 与树内同一逻辑节点的副本两者 uniqueId/window 皆 null，
612:            val ua = runCatching { a.uniqueId }.getOrNull()
613:            val ub = runCatching { b.uniqueId }.getOrNull()
621:            "${if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) runCatching { node.uniqueId }.getOrNull() else "-"})" +
642:            val same = sameNode(c, child) // 框架身份判据（uniqueId 优先，见 sameNode）
```

按函数边界（`grep -nE "^    private fun "` 实读）逐条归因，**无一条属 A4/A5**：
374/368 是 `locatedOf` 的 KDoc；429 在 `locatedByContent`（420-445）；477/490/493 在 `scanByContent`
（446-509）；533 在 `contentOf`（514-535）；603/604/612/613 在 `sameNode`（607-618）；621 在 `fingerprint`
（619-623）；642 在 `indexOfIn`（638-651）的注释。`scanByContent` 的 `hits == 1` 是**按内容定位**那套
判据（S2 期已定、军令 §1 未点名），本批冻结未动——把它一起搬走=越界，故只记不搬。

---

## §3 五关命令 + 原始输出

### 关 1 编译关

```
$ ./gradlew :app:compileDebugKotlin --rerun 2>&1 | tail -6
> Task :app:processDebugManifestForPackage UP-TO-DATE
> Task :app:processDebugResources UP-TO-DATE
> Task :app:compileDebugKotlin

BUILD SUCCESSFUL in 4s
18 actionable tasks: 1 executed, 17 up-to-date

$ ./gradlew :app:assembleDebug
> Task :app:packageDebug UP-TO-DATE
> Task :app:createDebugApkListingFileRedirect UP-TO-DATE
> Task :app:assembleDebug UP-TO-DATE

BUILD SUCCESSFUL in 1s
39 actionable tasks: 39 up-to-date
ASSEMBLE_RC=0
```

照实登记：`assembleDebug` 那次全 UP-TO-DATE，因为同一棵树刚在关 2 的 `--rerun-tasks` 里真编译过一遍
（那次 30 个 task 全部实际执行，含 `compileDebugKotlin`），所以它证明的是"当前树可出包"，真正的编译证据是上面强制重跑的 `compileDebugKotlin --rerun`
（`1 executed`）。

**android-free 自证**（纯函数文件里搜平台类型与日志）：

```
$ grep -nE "^import|Log\.|AccessibilityNodeInfo|AccessibilityEvent|Context" \
    app/src/main/kotlin/com/anytouch/app/recorder/capture/CaptureClues.kt
6: * 本文件 android-free：不接 AccessibilityNodeInfo / AccessibilityEvent / Context，也不写日志。
```

唯一命中=文件头注释本身。全文件**没有任何 import 行**（连 Kotlin 扩展都用的默认包）。

**关 1 复跑（23:34，含主窗 `GcmBlobCipher` 修复的当前树）**：

```
$ ./gradlew :app:assembleDebug 2>&1 | tail -8
> Task :app:mergeProjectDexDebug
> Task :app:packageDebug
> Task :app:assembleDebug
BUILD SUCCESSFUL in 1s
39 actionable tasks: 3 executed, 36 up-to-date
GATE1_RC=0

$ ls -l --time-style=+%F_%H:%M:%S app/build/outputs/apk/debug/*.apk
-rw-r--r-- 1 ... 9458528 2026-09-23_23:34:44 app/build/outputs/apk/debug/app-debug.apk
```

这次不是全 UP-TO-DATE：`packageDebug`/`mergeProjectDexDebug` 实际执行（因主源码变了），
所以 23:34 这枚 APK 就是"我的抽取 + 主窗那笔修复"的合体包。上面 22:5x 那轮 `compileDebugKotlin --rerun`
的记录保留不动，两轮各测各的树。

### 关 2 JVM 关（`--rerun-tasks` 一次干净重跑，单变体分模块 `<testsuite tests>` 求和）

```
$ ./gradlew :app:testDebugUnitTest :byok:test :core:contracts:test --rerun-tasks 2>&1 | tail -6
BUILD SUCCESSFUL in 25s
30 actionable tasks: 30 executed
RC=0

$ # 每个模块目录单独求和（files=该目录 xml 个数，同一时刻产出）
app/build/test-results/testDebugUnitTest files=25 tests=285 failures=0 errors=0 skipped=0
byok/build/test-results/test files=7 tests=59 failures=0 errors=0 skipped=0
core/contracts/build/test-results/test files=2 tests=19 failures=0 errors=0 skipped=0
```

分列：**app 285 / byok 59 / contracts 19 = 363**。
基线（开工前同口径、同命令跑的干净重跑）：**app 254 / byok 59 / contracts 19 = 332**
（当时 24/7/2 个 xml 全部 09-23 22:52 同一分钟产出）。
净增 = 31 = `CaptureCluesTest` 一例不差：

```
$ head -c 300 app/build/test-results/testDebugUnitTest/TEST-com.anytouch.app.recorder.capture.CaptureCluesTest.xml
<testsuite name="com.anytouch.app.recorder.capture.CaptureCluesTest" tests="31" skipped="0" failures="0" errors="0" timestamp="2026-09-23T15:09:40.694Z" ...
$ ls -l --time-style=+%F_%H:%M app/build/test-results/testDebugUnitTest/*.xml | awk '{print $6}' | sort | uniq -c
     25 2026-09-23_23:09
```

总数无下降（只升不降），25 个 xml 同分钟=单一口径。**没用整目录 glob，也没数日志行数。**

**关 2 复跑（按主窗 23:3x 的口头要求：主窗那笔 `GcmBlobCipher.kt` 修复落进工作树之后，再跑一次干净重跑）**
——下面这组才是本批交付口径的**最后一次**测量，上面 23:09 那组保留在自述里做对照，不覆盖：

```
$ ./gradlew :app:testDebugUnitTest :byok:test :core:contracts:test --rerun-tasks 2>&1 | tail -25
KillSwitchTest > stop后isStopped与flow同时翻转并携带现场 PASSED
SafetyQueueRunnerTest > ...（6 行 PASSED 回显）
InterruptedRunReportTest > 忙中丢弃回执注明会被执行中任务覆写 PASSED
BUILD SUCCESSFUL in 24s
30 actionable tasks: 30 executed
GATE2_RC=0

$ # 三个模块目录各自单独求和 + 该目录 xml 时间戳分布（同一分钟产出=单一口径）
== app/build/test-results/testDebugUnitTest   files=25  时间戳分布: 23:32=25   tests=285 failures=0 errors=0 skipped=0
== byok/build/test-results/test               files=7   时间戳分布: 23:32=7    tests=59 failures=0 errors=0 skipped=0
== core/contracts/build/test-results/test     files=2   时间戳分布: 23:32=2    tests=19 failures=0 errors=0 skipped=0
```

分列 **app 285 / byok 59 / contracts 19 = 363**，与 23:09 那组逐列相同 ⇒
主窗那笔 `GcmBlobCipher` 修复（20 增 / 3 删）没有让我这批任何一例变红，也没改变用例条数
（它自己的测试计数本来就已在 254/59/19 与 285 的差值之外）。
**我没有碰过那个文件**：`git diff --numstat` 里它仍是主窗落好的那一笔，见"附"节原文。

### 关 3 恒真关（反向改动自检）

工具与两轮原始输出全文见本节表格（§4），驱动脚本临时放在
`%TEMP%/s31-mutation-selfcheck.sh`（一次性工具，不入库；输出全文已贴进本文件末尾"附录 A"）。
每一轮都是：备份 → 施加一处变异（锚点必须全文唯一，否则不施加）→ 跑 `CaptureCluesTest` → 记录红项 →
`cp` 还原 → `cmp` 逐字节校验。**15 处变异全部把指名用例打红，15 次还原全部逐字节一致，最后复跑回绿。**

### 关 4 红线关

```
$ bash scripts/ci-local.sh 2>&1 | tail -22   # 前 6 行是 :tools:compiler 的构建回显，此处从 [3/4] 红线段落起贴
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
CI_RC=0

$ bash scripts/redline-probe.sh 2>&1 | tail -30
PROBE-F OK: 能 FAIL（REDLINE-F HIT: byok/ 与 tools/ 之外出现网络代码）
PROBE-G OK: 能 FAIL（REDLINE-G HIT: 执行路径 import 了编译模块（执行期零网络的结构锁失效））
PROBE-H OK: 能 FAIL（REDLINE-H HIT: 日志语句里出现密钥形态字段）
PROBE-I OK: 能 FAIL（REDLINE-I HIT: 出现明文落盘通道（Key 只能进 Keystore 加密 blob））
REDLINE-PROBE PASS: F/G/H/I 四条逐条独立验证能 FAIL，且撤探针后门禁回到 PASS
PROBE_RC=0
```

九线 A–I 全 clean、双 RC=0。新文件未把任何网络字样带进 `app/src/main`（红线 C/F 亲自复扫过一遍）。

**关 4 的复扫（工作树漂移之后，口径不同、不冒充完整关）**：上面那次是含 gradle 的完整跑；
23:26 磁盘上多出别人的一笔 `GcmBlobCipher.kt` 之后（见"附"节），我用脚本自带的 `SKIP_GRADLE=1`
只复扫了红线段，确认那一笔没把九线弄红——**这次不含 gradle 编译/测试，也不含 redline-probe**：

```
$ SKIP_GRADLE=1 bash scripts/ci-local.sh 2>&1 | tail -25
==> [1/4][2/4] gradle SKIPPED (SKIP_GRADLE=1, 只验红线)
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
CI_REDLINE_ONLY_RC=0
```

### 关 5 零回归关 —— **设备关未跑，待主窗补**

`bash scripts/device-smoke.sh` 与 `bash scripts/ui-smoke.sh` 我**没有执行**。原因：
① 派单纪律 #7 明令本片 worker 不碰设备/模拟器；② §5-5 记明现在 K40 在跑 E 系列，
且明令"绝不允许我自己去动 adb（任何一台）"。本批动了采集面（A4/A5），按 §4-4 这两条
必须重跑录→编→放链，**照 §5-5 的口径上报为"前四关已全绿 + 设备关待主窗排期补跑"，
记在主窗头上**，不写成通过。我全程未调用 `adb`、未启动/操作模拟器与真机（`git status` 快照见"附"节）。

**为此保留了什么**（让主窗补跑这一关时零等待、零重建）：

1. **就是这棵树（我这笔）**：改动全部留在工作树，未 commit / 未 push / 未 stash，我这三个文件的
   `git diff --numstat` 与 mtime 收工前后未变（`CaptureBridge.kt` 121 增 / 110 删，另两个新文件）。
   主窗不必 apply 任何补丁，直接在当前工作树上跑设备脚本即可。
   **但同一棵树在 23:26 起还带着别人的一笔 `GcmBlobCipher.kt`**（下面第 2 条与"附"节各点一次）。
2. **设备包与判绿的树同源（23:34 已重出）**：关 1 我在 23:34 于当前树上重跑过 `:app:assembleDebug`，
   这次 `packageDebug`/`mergeProjectDexDebug` 真执行（`3 executed`，非全 UP-TO-DATE 空转），
   APK mtime `2026-09-23 23:34:44`——**这枚包同时含我的 A4/A5 抽取与主窗那笔 `GcmBlobCipher` 修复**。
   两点照实说：① 主窗说"出包验证走 HEAD 的隔离 worktree"，那枚包**不含我这笔未提交的抽取**，
   所以设备验 A4/A5 采集面必须用工作树这枚 APK（或先 integration），别拿隔离树那枚的结果记我的账；
   ② 用这枚包跑 E-key 若通过，那是主窗那笔修复的功劳，不是我这批的——两笔在同一次设备上一起变绿时，
   归因要分开写。
3. **测量窗口对得上**：三个源文件的 mtime 全部早于关 2 那次干净重跑（当前口径=23:32 的 XML 时间戳；
   首轮 23:09 那组保留在自述里做对照），即 285/59/19
   度量的正是交付态；此后我再没改过任何 `.kt`（只改本自述）。收工前重读磁盘的原始输出：
   ```
   $ git diff --numstat -- app/src/main/kotlin/com/anytouch/app/recorder/capture/CaptureBridge.kt
   121	110	app/src/main/kotlin/com/anytouch/app/recorder/capture/CaptureBridge.kt
   $ stat -c '%n %y' <三个源文件全路径>
   app/src/main/kotlin/com/anytouch/app/recorder/capture/CaptureBridge.kt 2026-09-23 22:59:49.883732500 +0800
   app/src/main/kotlin/com/anytouch/app/recorder/capture/CaptureClues.kt 2026-09-23 23:07:17.682062300 +0800
   app/src/test/kotlin/com/anytouch/app/recorder/capture/CaptureCluesTest.kt 2026-09-23 23:02:29.656921500 +0800
   ```
4. **设备补跑真正需要重证的部分已被收窄**：A4/A5 的**判据**现在住纯函数且已有 JVM 覆盖，
   平台上只剩"把活树摊平成 `ClueNode`/`PinFieldFact`"的取数与逐字留痕的日志。所以设备侧要看的
   不是"判断对不对"（JVM 已锁），而是两件 JVM 天然测不到的事：
   - `clueTreeOf` 摊平的节点集是否仍等于原判据循环入队的节点集（尤其 `getChild` 期读字段 vs 弹队期读字段，
     见 §5-D7）；
   - 日志串是否逐字未变——`S2SMOKE clue=path ...`（含 ` truncated(预算内未遍历完)` 后缀）、
     `S2SMOKE clue=event-copy 弃用(...)`、`S2SMOKE clue=event-desc(转场后活树无证据，事件 desc 字段直读)`。
     这三条是 E 系列脚本已在读的信号，建议补跑时按字面 grep 断言，不必新写判据。
5. **未留悬念**：转场重扫的时序（`PIN_SETTLE_RETRIES=2 × 120ms`）与 `SEARCH_BUDGET` 取数上限原样未动，
   若设备复验出现"线索少了一条"，先查摊平侧（保留 4 第一条）而不是判据侧——判据侧红了 JVM 会先红。

### 附：工作树与越界自证

```
$ git status --short
 M app/src/main/kotlin/com/anytouch/app/recorder/capture/CaptureBridge.kt
 M evidence/S2/emu-boot-marvisphone.log
?? app/src/main/kotlin/com/anytouch/app/recorder/capture/CaptureClues.kt
?? app/src/test/kotlin/com/anytouch/app/recorder/capture/CaptureCluesTest.kt
?? evidence/S3/raw/byok-smoke-k40-e1pre.log
?? evidence/S31/
?? scripts/byok-credential-inject.sh

$ git diff --stat
 .../anytouch/app/recorder/capture/CaptureBridge.kt | 231 +++++++++++----------
 evidence/S2/emu-boot-marvisphone.log               |  62 ++++++
 2 files changed, 183 insertions(+), 110 deletions(-)

$ git log --oneline -1
19bf372 orders(S31): 老板硬约束落成 §5 交付前自检门——worker 五关...
```

（末行 `evidence/S31/` 就是本自述，收工快照里它必然在列。）

**不是我改的三项，照实点出来（不静默、也不顺手回退别人的东西）**：
`evidence/S2/emu-boot-marvisphone.log`（模拟器自家追加，主窗在上文明确"故意不入库"）、
`evidence/S3/raw/byok-smoke-k40-e1pre.log`、`scripts/byok-credential-inject.sh`（E 系列那批留下的未跟踪文件）。
我本批的账只有：`CaptureBridge.kt` 一个 M，加两个 `??` 新文件。

**收工快照之后工作树又动了（不是我的账，但会影响主窗独立重跑的对照）**：
上面那份 `git status` 是我关 5 之前拍的；23:26 磁盘上多出一个 ` M app/src/main/kotlin/com/anytouch/app/compile/GcmBlobCipher.kt`
（`git diff --numstat` = 20 增 / 3 删，mtime `2026-09-23 23:26:14 +0800`）。
**我没碰过它**——原判："也没为它重跑任何一关；我的 285/59/19 是 23:09 那棵树上度的，不含这次改动。"
**【23:3x 更正】**那句已不成立，按主窗指示在当前树上复跑，照实改成：

- **关 2 JVM 关已复跑**（23:32，`--rerun-tasks` 一次干净重跑，25/7/2 个 xml 全在同一分钟）：
  **app 285 / byok 59 / contracts 19 = 363，与 23:09 那组三列逐列相同，0 failures / 0 errors / 0 skipped**
  ⇒ 这笔修复既没让我这批任何一例变红，也没改变条数。原始输出见"关 2 复跑"一节。
- **关 1 编译关已复跑**（23:34）：`./gradlew :app:assembleDebug` → `39 actionable tasks: 3 executed,
  36 up-to-date`、`GATE1_RC=0`；这次真执行的是 `mergeProjectDexDebug`/`packageDebug` 那几步（不是全
  UP-TO-DATE 空转），产物 `app/build/outputs/apk/debug/app-debug.apk` mtime `2026-09-23 23:34:44`。
  **所以当前这枚 APK 里同时含我的 A4/A5 抽取与主窗这笔修复**（主窗若走隔离 HEAD worktree 出包，
  那枚 APK 不含我这笔未提交的抽取——设备验采集面必须用工作树这枚，或先 integration，别混着记账）。
- **关 4 红线段已复扫**（`SKIP_GRADLE=1`，九行 clean），但它不含 gradle 与 redline-probe，见关 4 补记。
- **关 3 恒真关未复跑**：15 轮变异只打在 `CaptureCluesTest`（判据住 `recorder/capture/`），与 `compile/`
  零耦合；主窗若认为"树动过就要重证 mutation 能红"，这条归我补，但我不写成跑过。

所以现在的工作树 = 我的 A4/A5 抽取 + 主窗这一笔 `GcmBlobCipher.kt`；两笔的账在上面分开记。
（我自己的三个文件 mtime 仍是 22:59 / 23:07 / 23:02，未再变。）

收工时（23:3x，漂移之后）再拍一次的原文，供主窗比对：

```
$ git status --short
 M app/src/main/kotlin/com/anytouch/app/compile/GcmBlobCipher.kt
 M app/src/main/kotlin/com/anytouch/app/recorder/capture/CaptureBridge.kt
 M evidence/S2/emu-boot-marvisphone.log
?? app/src/main/kotlin/com/anytouch/app/recorder/capture/CaptureClues.kt
?? app/src/test/kotlin/com/anytouch/app/recorder/capture/CaptureCluesTest.kt
?? evidence/S3/raw/byok-smoke-k40-e1pre.log
?? evidence/S31/
?? scripts/byok-credential-inject.sh

$ git log --oneline -1
19bf372 orders(S31): 老板硬约束落成 §5 交付前自检门——...
```

**Key 形态自查**（本批新增行 + 两个新文件全行，共 596 行）：

```
$ git diff -U0 -- .../CaptureBridge.kt | grep '^+' > /tmp/s31-added.txt
$ cat .../CaptureClues.kt .../CaptureCluesTest.kt >> /tmp/s31-added.txt
$ grep -oE '[A-Za-z0-9]{24,}' /tmp/s31-added.txt | sort | uniq -c
      1 AnytouchAccessibilityService
```

命中 1 处，逐条人工归因（不写"0 命中"）：`CaptureBridge.kt:201`
`+ * [com.anytouch.app.service.AnytouchAccessibilityService] 的 roots 空帧重试同口径，`
——搬移 `pinEventClue` 的 KDoc 时从原文带过来的**服务类名链接**，不是字面量凭据。
`CaptureClues.kt`、`CaptureCluesTest.kt` 两个新文件各自 0 命中（测试语料全是 "Sound"/"Wi-Fi"/"Navigate up" 这类海外英文词）。

---

## §4 mutation 自查三列表（15 条，全部"改了哪一行 → 哪条用例红 → 撤改后回绿"）

| # | 改了哪一行（变异） | 哪条用例红（指名那条打 ★） | 撤改后 |
|---|---|---|---|
| M1 | A4 触顶边界 `visited >= budget` → `>`（多读一个节点） | ★预算内没轮到的节点绝不进线索集（1 红） | 回绿 |
| M2 | A4-② `titleCount == 1` → `>= 1`（两个 title 取第一个） | ★两个 title 就是歧义 整条弃用而不是取第一个、弃用带着证据计数（2 红） | 回绿 |
| M3 | A4-③ `texts.size == 1` → `>= 1`（歧义取第一个） | ★文本歧义时 desc 不救场、两个 title 就是歧义、弃用带着证据计数（3 红） | 回绿 |
| M4 | A4-④ 删前置 `texts.isEmpty() &&`（让 desc 救场） | ★文本歧义时 desc 不救场（1 红） | 回绿 |
| M5 | A4 深度 `level < depth` → `<=`（多下一层） | ★深度上限之外的子树不参与取证（1 红） | 回绿 |
| M6 | A4 把 `isTitleId` 计数移出"有文字"分支（空 title 也计数） | ★空白的 title 不算 title 计数（1 红） | 回绿 |
| M7 | A5 判序调换：desc 唯一排在 text 唯一之前 | ★两字段各命中一个时判序走 text（1 红） | 回绿 |
| M8 | A5 `>1` 歧义档改成 `>2`（歧义掉进零命中档） | ★多命中是真歧义 封顶也不会退到豁免边、四档落点互不相同（2 红） | 回绿 |
| M9 | **A5 豁免边落到 text 词汇**（`ByDesc(desc,true)` → `ByText(desc)`） | ★text 侧永远走不到豁免边、豁免边只给 desc 侧（2 红） | 回绿 |
| M10 | A5 豁免不再要求重扫封顶（`if (!allowDescExemption)` → `if (false)`） | ★零命中在未封顶时不是歧义、豁免边只在封顶后给（2 红） | 回绿 |
| M11 | A5 "压根没词"不再单独成档（`NoWord` → `NoEvidence`） | ★压根没词不与零命中同档、四档落点互不相同（2 红） | 回绿 |
| M12 | A5 活树比较全等 → 包含 | ★包含不算命中（1 红） | 回绿 |
| M13 | **A5 两字段交叉**（text 命中报成 desc 命中）=雷 15 本体 | ★词只与 text 字段全等、词只与 desc 字段全等、活树字段首尾空格（3 红） | 回绿 |
| M14 | `blankToNull` 去掉 trim | ★活树字段首尾空格仍算全等、空白即缺失的口径只有一份、首尾空格同一个词、空白的 title（4 红） | 回绿 |
| M15 | A5 事件无 desc 时不弃用（`?: return NoEvidence` → `?: ""`） | ★封顶后仍零命中且事件没 desc 时弃用、四档落点互不相同（2 红） | 回绿 |

零红项：**没有一条用例是恒真的**——每条判据分支至少被上面一处变异打红；
变异锚点若不唯一脚本直接拒绝施加（15 轮都没触发，说明改的都是那一处、没顺手改到别处）。

---

## §5 与 §1 靶表的偏差（先记偏差，再动代码；无静默替换）

- **D1**：军令 A4 写 `CluePick`、A5 写"枚举 ByText/ByDesc/Ambiguous/Dropped"。裸枚举带不了
  `word` 与 `titleCount/textHits/descHits`，而留痕话术要求逐字不变（`clue=path ... titles=2 texts=2 descs=0`、
  `弃用(窗口内 text 命中=2 desc 命中=0，皆需唯一)`），所以落成**同名四态的 sealed interface**。
  四档名字与军令一字不差，没加第五档。
- **D2**：`CluePin.Dropped` 多带一个 `cause`（`NoWord`/`AwaitingTree`/`NoEvidence`）。军令签名没给 `attempt` 入参，
  而"0 命中要重扫、歧义不重扫"这条判据必须有处落：我用 `allowDescExemption` 表达"是否已到重扫封顶"，
  把**重扫次数与 sleep 留在平台侧**（那是时序不是判据），把"这一档叫什么"交给纯函数。
  若不给 `AwaitingTree` 一档，0/0 与真弃就混成同一条落点——正是军令点名的反例。
- **D3**：军令 A4 的签名 `descendantClueOf(root, budget, depth)` 我**逐字照抄**（参数名、顺序、返回类型同名）。
- **D4**：军令 A5 的签名 `pinEventClueOf(eventText, eventDesc, treeTextHits, treeDescHits, allowDescExemption)`
  我**逐字照抄**。
- **D5**：军令把 `treeTextHits/treeDescHits` 定为取数（int 入参），但"按活树同口径、不交叉拷贝"这条判据的
  **本体**就是那两行比较（`node.text==word` 与 `node.contentDescription==word` 各比各的 + trim 全等），
  留在平台侧=这一条仍零 JVM 覆盖。故额外抽 `fieldPinHits(word, PinFieldFact)`；
  `PinFieldFact` 住本片文件内，**没有新造公共 UiNode 抽象**。每侧见满 2 即停与 `SEARCH_BUDGET` 留平台侧
  （取数边界），日志里的命中数字因此与搬移前逐字相同。
- **D6**：`eventCopyWords` 原签名 `List<String>`，但两条分支各自只可能交出 0 或 1 个词（`listOf(唯一首项)`），
  原判据里那个"逐词试"的 `for (word in words)` **永不进入第二轮**。为与 A5 的单词签名一一对应，收成
  `eventCopyWord: String?`。可证零语义变化，但确实是签名改动，登记在此。
- **D7**：军令要求 `recycleQuietly` 归平台侧、纯函数不接句柄 → 平台侧 `clueTreeOf` 必须在 `getChild` 当场读
  text/desc（原判据在"弹出"时才读）。差异只在"预算内没轮到的节点"：它们的字段被读进了 `ClueNode`，但纯函数按
  **同一个 budget** 走到 32 就停步 → 这些节点依旧不进线索集，结论与日志逐字相同（M1 锁死这条边界，
  `刚好用满预算不算触顶` 锁另一侧）。`getChild` 调用次数与回收对象集合与搬移前一致。
- **D8**：军令 §0 说 A4 住在"546-600"、A5 住在"110-160、208-240"。实读磁盘：A4 判据体是 546-594（595-600 是
  `walkDepthOf` 的注释与函数头，无判据）；A5 的 110-160 是**两个调用点**（`snapshotOf` 内 path 不可证 / path 已证
  但产物只能用 path 词汇），判据体在 208-258 与 267-291。我按磁盘事实搬，**没有为此改任何判据语义**，只登记行号差。
- **D9**：开工时 HEAD 是 `38e93eb`，中途主窗提交 `19bf372` 加了 §5 五关门（我读到的军令原文没有 §5）。
  我没有回头改已写的代码去迁就，而是按 §5 把五关补跑了一遍（含把全 UP-TO-DATE 的编译关强制重跑）。

**未发现"军令与磁盘代码语义不符"的实质冲突**；§1 表格 A4/A5 两行的判据描述与代码事实逐条对得上，
所以我没有停在半路待裁，也没有改判据一个字。

---

## §6 未完成项与原因（没做的不写成做了）

1. **设备关未跑，待主窗补**（§5-5 / §4-4）：`device-smoke.sh` + `ui-smoke.sh` 零回归证据缺，待主窗排期补跑（K40 在跑 E 系列，
   派单也禁止本片 worker 碰设备）。这是本批唯一未过的关，且它恰好覆盖 A4/A5 的真机形态（句柄残缺、转场 0/0 帧）。
   我为这一关保留了什么，见关 5 一节第 1–5 条（工作树即交付态、设备包同源、判据已被 JVM 锁住、
   设备侧只需重证"摊平 + 三条日志逐字未变"）。
2. **31-B（A1/A2/A3）未动**：`executor/NodeTaskRunner.kt` 一行未碰，等老板那句话（§3-31-B）。
3. **`descendantClue`/`pinEventClue` 本身在 JVM 里仍覆盖=0**：它们住在 `AndroidCaptureBridge`（构造即要
   `AccessibilityNodeInfo`、判据结论要 `Log.` 出屏），我没有为跑通测试去加 `isReturnDefaultValues`（纪律 #4）。
   搬走之后**判据**在 JVM 里 31 例锁死，剩下的"摊平是否读对了字段"这一层仍只有设备证据——这句必须照实说，
   不让"31 例"冒充整条链的覆盖。
4. **已知残留（搬移前后同型，我没顺手改）**：`clueTreeOf` 与原判据一样，预算触顶时队列里剩下的那几个
   `getChild` 副本不回收（原代码也漏，`contentOf` 同型）。改了就是动取数语义，留给主窗裁。
5. **`pinEventClue` 的编译/协程侧残留不在本片范围**（E0 已记 §7-3 那条与本批无关）。
6. **未新建 `scripts/` 工具**：mutation 驱动脚本放在临时目录（一次性工具），不入仓；全文输出见附录 A。

---

## 附录 A：反向改动自检两轮原始输出全文

工具：`%TEMP%/s31-mutation-selfcheck.sh`（备份→变异→跑→还原→`cmp` 逐字节校验）
命令：`./gradlew :app:testDebugUnitTest --rerun --tests "com.anytouch.app.recorder.capture.CaptureCluesTest"`

```
===== 变异前基线（必须全绿）=====
        BUILD SUCCESSFUL in 2s
----- M1 变异: A4-① 触顶边界 visited>=budget 改成 visited>budget（多读一个节点）
      改为: if (visited > budget) {
      原始输出:
        CaptureCluesTest > 预算内没轮到的节点绝不进线索集 FAILED
        31 tests completed, 1 failed
        BUILD FAILED in 3s
      还原校验: 与变异前逐字节相同 OK
----- M2 变异: A4-② title 唯一改成 title 存在即取（两个 title 时取第一个）
      改为: titleCount >= 1 -> titleText?.let { CluePick.Text(it) }
      原始输出:
        CaptureCluesTest > 两个 title 就是歧义 整条弃用而不是取第一个 FAILED
        CaptureCluesTest > 弃用带着证据计数 上层能原样留痕 FAILED
        31 tests completed, 2 failed
        BUILD FAILED in 3s
      还原校验: 与变异前逐字节相同 OK
----- M3 变异: A4-③ 子树文本集唯一改成存在即取（歧义时取第一个）
      改为: texts.size >= 1 -> texts.first()?.let { CluePick.Text(it) }
      原始输出:
        CaptureCluesTest > 两个 title 就是歧义 整条弃用而不是取第一个 FAILED
        CaptureCluesTest > 弃用带着证据计数 上层能原样留痕 FAILED
        CaptureCluesTest > 文本歧义时 desc 不救场 FAILED
        31 tests completed, 3 failed
        BUILD FAILED in 3s
      还原校验: 与变异前逐字节相同 OK
----- M4 变异: A4-④ 删掉「无文本」前置条件（文本歧义时让 desc 救场）
      改为: descs.size == 1
      原始输出:
        CaptureCluesTest > 文本歧义时 desc 不救场 FAILED
        31 tests completed, 1 failed
        BUILD FAILED in 3s
      还原校验: 与变异前逐字节相同 OK
----- M5 变异: A4 深度上限 level<depth 改成 level<=depth（多下一层）
      改为: if (level <= depth) {
      原始输出:
        CaptureCluesTest > 深度上限之外的子树不参与取证 FAILED
        31 tests completed, 1 failed
        BUILD FAILED in 3s
      还原校验: 与变异前逐字节相同 OK
----- M6 变异: A4 title 计数移出「有文字」分支（空 title 也计数）
      改为:         if (node.isTitleId && blankToNull(node.text) == null) titleCount++
        blankToNull(node.desc)?.let { descs.add(it) }
      原始输出:
        CaptureCluesTest > 空白的 title 不算 title 计数 FAILED
        31 tests completed, 1 failed
        BUILD FAILED in 3s
      还原校验: 与变异前逐字节相同 OK
----- M7 变异: A5 判序调换：desc 唯一排在 text 唯一之前
      改为:         treeDescHits == 1 -> return CluePin.ByDesc(eventText, viaEventFieldExemption = false)
        treeTextHits == 1 -> return CluePin.ByText(eventText)
      原始输出:
        CaptureCluesTest > 两字段各命中一个时判序走 text FAILED
        31 tests completed, 1 failed
        BUILD FAILED in 3s
      还原校验: 与变异前逐字节相同 OK
----- M8 变异: A5 删掉「任一侧 >1 即歧义」这一档（歧义掉进零命中档）
      改为:         treeTextHits > 2 || treeDescHits > 2 -> return CluePin.Ambiguous(eventText, treeTextHits, treeDescHits)
      原始输出:
        CaptureCluesTest > 四档落点互不相同 上层无需猜 null FAILED
        CaptureCluesTest > 多命中是真歧义 封顶也不会退到豁免边 FAILED
        31 tests completed, 2 failed
        BUILD FAILED in 3s
      还原校验: 与变异前逐字节相同 OK
----- M9 变异: A5 豁免边落到 text 词汇（本批最严一条：text 侧永走不到豁免边）
      改为:     return CluePin.ByText(desc)
      原始输出:
        CaptureCluesTest > 豁免边只给 desc 侧 且只在重扫封顶后给 FAILED
        CaptureCluesTest > text 侧永远走不到豁免边 FAILED
        31 tests completed, 2 failed
        BUILD FAILED in 3s
      还原校验: 与变异前逐字节相同 OK
----- M10 变异: A5 豁免不再要求重扫封顶（0/0 首帧即免窗口验证）
      改为:     if (false) {
      原始输出:
        CaptureCluesTest > 豁免边只给 desc 侧 且只在重扫封顶后给 FAILED
        CaptureCluesTest > 零命中在未封顶时不是歧义 而是还要再扫一次树 FAILED
        31 tests completed, 2 failed
        BUILD FAILED in 2s
      还原校验: 与变异前逐字节相同 OK
----- M11 变异: A5 「压根没词」不再单独成档（与零命中混档=上层无法分别留痕）
      改为:     if (eventText == null) return CluePin.Dropped(CluePin.Cause.NoEvidence, null, treeTextHits, treeDescHits)
      原始输出:
        CaptureCluesTest > 四档落点互不相同 上层无需猜 null FAILED
        CaptureCluesTest > 压根没词不与零命中同档 也吃不到豁免 FAILED
        31 tests completed, 2 failed
        BUILD FAILED in 3s
      还原校验: 与变异前逐字节相同 OK
----- M12 变异: A5 活树比较由全等放宽成包含
      改为: (blankToNull(node.text)?.contains(word) == true) to (blankToNull(node.desc)?.contains(word) == true)
      原始输出:
        CaptureCluesTest > 包含不算命中 FAILED
        31 tests completed, 1 failed
        BUILD FAILED in 3s
      还原校验: 与变异前逐字节相同 OK
----- M13 变异: A5 两字段交叉（text 命中记成 desc 命中）=雷 15 本体
      改为: (blankToNull(node.desc) == word) to (blankToNull(node.text) == word)
      原始输出:
        CaptureCluesTest > 活树字段首尾空格仍算全等 纯空白不算命中 FAILED
        CaptureCluesTest > 词只与 text 字段全等时算 text 命中 FAILED
        CaptureCluesTest > 词只与 desc 字段全等时算 desc 命中 不许串到 text FAILED
        31 tests completed, 3 failed
        BUILD FAILED in 3s
      还原校验: 与变异前逐字节相同 OK
----- M14 变异: 空白即缺失口径去掉 trim（首尾空格不再全等）
      改为: fun blankToNull(value: String?): String? = value?.takeIf { it.isNotEmpty() }
      原始输出:
        CaptureCluesTest > 空白的 title 不算 title 计数 FAILED
        CaptureCluesTest > 空白即缺失的口径只有一份 FAILED
        CaptureCluesTest > 首尾空格同一个词算同一条文本 全空白算没有文本 FAILED
        CaptureCluesTest > 活树字段首尾空格仍算全等 纯空白不算命中 FAILED
        31 tests completed, 4 failed
        BUILD FAILED in 3s
      还原校验: 与变异前逐字节相同 OK
----- M15 变异: A5 事件无 desc 时不再弃用（凭空造一格空 desc 线索）
      改为:     val desc = eventDesc ?: ""
      原始输出:
        CaptureCluesTest > 封顶后仍零命中且事件没 desc 时弃用 该步显形失踪 FAILED
        CaptureCluesTest > 四档落点互不相同 上层无需猜 null FAILED
        31 tests completed, 2 failed
        BUILD FAILED in 2s
      还原校验: 与变异前逐字节相同 OK
===== 全部变异还原后复跑（必须回绿）=====
        BUILD SUCCESSFUL in 2s
===== 还原后与基线备份比对 =====
CaptureClues.kt 逐字节还原 OK
```

（照实登记转写口径：附录 A 每轮我只贴了「改为 / 红项 / 计数 / 还原校验」四类原始行，省略了驱动脚本另外打印的
"期望变红用例(关键词)"提示行（那是脚本里写死的期望，不是 Gradle 输出）。三列表第四列"撤改后回绿"=每轮末
`还原校验: 与变异前逐字节相同 OK` 加上最后那次 `BUILD SUCCESSFUL` 全绿复跑。）

---

## 待主窗独立重跑

本批改动**全部留在工作树**（未 commit、未 push、未 stash）。请主窗按 §4 独立做：
逐行 diff 验"搬移非复制"（§2 末尾"判据不残留"那节给了机器化复查的原始 grep 输出，可直接对）、
`--rerun-tasks` 重跑对 app 285/byok 59/contracts 19 三列真数、
ci-local + redline-probe 双 RC=0、**并补跑 §6-1 欠的设备关**（`device-smoke.sh` + `ui-smoke.sh` 录→编→放零回归）。

**重跑前先看这一句**：23:26 起工作树不止我这笔——`app/src/main/.../compile/GcmBlobCipher.kt` 多出一个
` M`（20 增 / 3 删，不是我改的，见"附"节）。要么先 integration 那一笔再连我的一起重跑，
要么把它 stash/隔离出去；否则我这三列数与主窗那三列数不同的第一归因应是它，不是本批抽取。
