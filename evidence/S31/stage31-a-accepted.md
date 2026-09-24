# STAGE-31 31-A 主窗独立验收：A4 + A5 采集层判据下沉

验收依据：`orders/ANYTOUCH-S31-ORDER.md` §4（五关，主窗独立做，不听口头）+ §5（worker 交付前自检门）
被验交付：`CaptureClues.kt`（新增，判据唯一住处）+ `CaptureBridge.kt`（121 增 / 110 删）+ `CaptureCluesTest.kt`（新增 31 例）
worker 自述：`evidence/S31/stage31-a-worker-report.md`（只读，基线 HEAD `19bf372`）
主窗验收时 HEAD：`759ba58`（切片 E 半批）——两次提交在 `recorder/capture/` 面**零交集**，diff 直接可用。
日期：2026-09-24（本机 01:5x–02:4x）
结论：**ACCEPTED（一次通过）**。判据搬移本身无返工；本批唯一返工在**主窗自己的设备读数层**（§4 关 4 那一条），记在我头上，不记 worker。

---

## §1 五关逐关（每关都给磁盘事实，不给"我看过了"）

| 关 | 判定 | 磁盘事实 |
|---|---|---|
| §4-1 `git diff` 逐行读：搬移非复制 | **过** | 见 §2：原判据分支在 `CaptureBridge` 已删净，只剩"摊平取数 + 转调 + 逐字留痕"三件事；`descendantClue` 现为表达式体（`CaptureBridge.kt:540-553`），`countFieldPins` 的比较一行交给 `fieldPinHits`（:276-280），`orNull()` 转调 `blankToNull`（:673） |
| §4-2 独立重跑分模块真数 | **过** | `--rerun-tasks` 单变体 XML 求和：app **285** / byok **59** / contracts **19** = **363**，failures=0 errors=0 skipped=0；与 worker 自述逐字相同（基线 332 → +31 例全在 `CaptureCluesTest`） |
| §4-3 九线红线 + 探针双 RC=0 | **过** | `bash scripts/ci-local.sh` → `==> [4/4] ci-local PASS`，CI_RC=0（红线 A–I 九条逐条 clean）；`bash scripts/redline-probe.sh` → PROBE_RC=0，F/G/H/I **逐条能 FAIL 且撤探针回 PASS** |
| §4-4 设备关零回归 | **过（本窗补跑，见 §5）** | 同一枚 31-A 构建（`app/build/outputs/apk/debug/app-debug.apk`，09-24 08:32 出包，晚于全部 31-A 源文件）装 emulator-5554（`install -r`，未 force-stop，无障碍绑定装前装后都核）：`device-smoke` **13/13 ALL PASS**、`ui-smoke` **41 PASS / 0 FAIL / 0 SKIP**、`s2-smoke` **ROUNDS=10 → 10/10 全绿 ratio=1.00 + SC 一次慢拖只成一步**；raw 五份在 `evidence/S31/raw/` |
| §4-5 交付账进 METRICS | **过（本轮落）** | `orders/METRICS.md` S31-A 行：一次通过 + "设备关由主窗补跑" + "主窗自伤/自修一条（ui-smoke 读数层）" |

关 2/3 的命令与输出全在这五份 raw 里；本轮**产品代码一行未改**（`git diff` 只含 worker 三件 + 主窗两份脚本 + 台账）。

---

## §2 读码结论：判序一条没换，两处"看着像换了"的逐条钉死

### ① A4 `descendantClue`（四档判序）
`git show HEAD:...CaptureBridge.kt` 的原实现与新 `descendantClueOf`（`CaptureClues.kt:54-90`）逐档对读：
`truncated → titleCount==1 → texts.size==1 → texts.isEmpty()&&descs.size==1 → else 弃` —— **判序、判据、集合类型（`LinkedHashSet` 去重）全部逐字照搬**；
留痕话术 `S2SMOKE clue=path source=… id=… titles=… texts=… descs=…[ truncated(预算内未遍历完)]` 一字未动（这关系到既有 47 条归档 `clue=path` 行还能不能按同一 grep 读到）。

### ② §5-D6 反向验证：`eventCopyWords: List<String>` → `eventCopyWord: String?`
搬移前那条 `List` 只可能是 0 或 1 个元素（`emptyList()` 或 `listOf(唯一首项)`），所以原判据里"逐词试"的 `for (word in words)` **永不进入第二轮**。
worker 把这条口径收窄写进了 KDoc（`CaptureBridge.kt:185-187`），没有拿"顺手简化"混过去。零语义变化。

### ③ §5-D7 反向验证（**这条最难，值得单独钉**）：摊平侧的预算上限会不会把"触顶否决"洗成"看全了"
风险形态是真的：`clueTreeOf` 自己就在 `visited < DESCENDANT_BUDGET` 处停手（`CaptureBridge.kt:570`），若它把树**裁平**了，纯判据那侧 `visited >= budget` 永不可能触发 → 搬移前"预算内没走完 = 整条证据作废"这一票否决就成了死代码（**假绿，比假红贵**）。

逐行推演后判它**不成立**，理由是取数侧一个不那么显眼的事实：
`clueTreeOf` 在停手时**不回收未消费的槽位**——最后一次展开挂进 `slot.children` 又留在队列里的那些 `ClueSlot` 仍然在树里（:582 `slot.children.add(childSlot)` 先于 :570 的循环条件生效，而 :720 `freeze()` 是**递归**冻结全部 `children`，不管槽位被消费过没有）。
于是"被摊出来的节点数 = 32 + 未消费的 p 个"，纯判据按同一 BFS 序走到第 32 个时队列仍非空 → `truncated=true` 照旧触发。
两种边界也各自核过：`visited==32 且队列恰好清空` → 两侧同判 `truncated=false`；深度维度两侧同用 `< DESCENDANT_DEPTH`（取数侧 `CaptureBridge.kt:573`、判据侧 `CaptureClues.kt:78`），超深节点压根不进树，等价。

**诚实边界（不写这句这段验证就只成功了一半）**：
- 这段等价性靠**逐行读 + 遍历形状同构论证**，JVM 覆盖=0（`clueTreeOf` 要 `AccessibilityNodeInfo`），worker §6-3 自己也这么声明；
- 本轮 10 轮真采的 raw 里 `clue=path` **0 条、`truncated` 0 条**（10 轮每一下点击都自带 text，没走到子树兜底）——这**只能说明"没触发"，不能当"不会触发"的证据**，也不得反过来给这段论证加分；
- 因此这条否决的可达性**依赖"未消费槽位仍在树里"这个隐蔽事实**。谁将来"顺手优化" `clueTreeOf`（比如停手时清空未消费 children、或把预算传成 `budget - p`），就会静默把否决洗掉。**修法上最省事的防护是给纯函数加一条"`root` 节点数 > budget"的用例，但那条用例锁的是判据不是取数侧**——真正锁得住的是本段文字，已在此登记。

### ④ A5 `pinEventClue` 四档落点
旧实现三档 `null` 混在一起（"压根没词"/"零命中待重扫"/"封顶无证据"共用一条出口），新实现拆成 `CluePin.{ByText,ByDesc,Ambiguous,Dropped(Cause)}`。
逐档对读结论：旧代码在 `words` 非空时，`textHits==1→text` / `descHits==1→desc` / 任一侧 `>1→歧义弃` / 否则 0/0 → 未封顶则 `attempt++` 重扫、封顶后 `eventDesc!=null` 走**唯一免窗口验证**那一档、`eventDesc==null` 走弃用留痕——**新路径与判序逐字相同**；豁免边那条日志 `clue=event-desc(转场后活树无证据，事件 desc 字段直读)` 原文照录（`CaptureBridge.kt:221`）。
唯一新增的取数侧事实：`word==null` 时不再扫活树（`CaptureBridge.kt:217` 的三元），比搬移前少一次无谓遍历，不改结论。

---

## §3 mutation 独立抽验（不信 worker 附录，自己放两针）

工具：`sed -i` 就地变异 → `./gradlew :app:testDebugUnitTest --tests "*CaptureCluesTest*" --rerun-tasks` → 反向 `sed` 还原 → `md5sum` 比对。

| 针 | 改法 | 期望（worker 自述） | 主窗实测 |
|---|---|---|---|
| M13（雷 15 本体） | `fieldPinHits` 两字段交叉：`(text 比 word) to (desc 比 word)` → 反过来 | 3 红 | **31 tests completed, 3 failed** |
| M1（A4 触顶边界） | `if (visited >= budget)` → `if (visited > budget)`（多读一个节点） | 1 红 | **31 tests completed, 1 failed** |

还原校验：`CaptureClues.kt` 变异前 `md5=6558bb16963201d916b4e8df362bf824`，两针各自还原后**同一 md5**（该文件是新增件、git 里还没有它，所以 md5 是唯一可用的逐字节凭据）；随后全量重跑回到 363/0 失败（§1 关 2 那行即还原后的数）。

---

## §4 关 4 的靶选择：为什么设备关打在模拟器而不是 K40

`ANYTOUCH-S31-ORDER.md` §4-4 只写"设备关"，没指定靶。已归档口径（`evidence/S2/t3-k40-first-contact.md:47`，本轮实读该行）：
> "**真机不适配模拟器脚本口径，R3 后按真机 profile 手测关键链为准**"

31-A 动的是 C/U/S2 三支脚本共用的采集判据，其词表与几何参数按**模拟器英文界面**为默认口径，K40 上这些脚本本就有已知红项归因（C1 RID 不匹配、C2/C5 MIUI 拒 SET_TEXT）。
所以"零回归"这把尺只能拿模拟器量——不是省事，是**只有它配当这把尺**；K40 在跑的是 E 系列（真 Key/真 HTTPS/真 Keystore），两批不抢同一台机器。
若将来要在真机上复证 31-A，需要的是**按真机 profile 的关键链手测**，不是把 C/U 脚本原样丢过去再记一堆已知红。

§4-4 括注"必须重跑录→编→放链"的读法在此钉死：字面点名的两支脚本（device/ui-smoke）**加**上真正的录→编→放链 `s2-smoke.sh`（ROUNDS=10 + SC）一起跑，三支同构建全绿才算关 4。
——**照字面 + 括注三条一起跑，不静默收窄成两支**。

---

## §5 本窗自伤一条（关 4 的返工，全记主窗头上，不记 worker）

**事实链**（五份 raw 全在 `evidence/S31/raw/`）：
1. 首轮 `ui-smoke` 打在 31-A 新装构建上：**36 PASS / 5 FAIL**（U1b、U6b、U8c、U10d、U11b 全是"读数 0"），而**同一条流的后面** U12d、U15c 却 PASS（`ui-smoke-31a-emulator-first-run-5red.log`）。
2. 我没有当场判产品。手工探针（`/tmp/probe-u1b.sh` 同配方）：`mCurrentFocus=com.anytouch.app/.MainActivity`、逐屏 dump 里 `run_task`/`step_delete_2`/`步骤 3` 全在，`resource-id="target_pkg"` 七屏**一条都没有**。
3. 中间我**没有改一行产品代码**，只把自家窗滚到顶，再整轮重跑：**41 PASS / 0 FAIL / 0 SKIP**（`ui-smoke-31a-emulator-guarded.log`）。同一枚 APK、同一份脚本、读数 0↔1 翻转。

**归因到哪一步、没到哪一步（照实说）**：
- 已排除：不是 31-A 的产品回归（构建同源、后面两条同类断言绿、重跑全绿、且这 5 条覆盖的屏态与 A4/A5 无关）；不是 dump 空返回（空 buffer 会让"扫视未到底"日志打印，而那条日志一条没出）。
- **未坐实**：那次"5 条读 0"的确切机制我**没拿到证据**。最贴合的假设是"那一轮开头那几次扫视读的不是自家窗"（页被上一批 C 系列停在底部／窗被导航换走），但**假设不是结论**，所以我不写"已归因"，只写"未归因，且已加锁让下一次能归因"。
- 真正被这条揪出来的缺陷在**我自己的读数层**，两处都是纪律级错误：
  1. `ui_sweep` 的"到底"结论（`SWEEP_OK`）此前**不检查读的是谁家的窗**——别人家的两屏签名相同一样算"到底"；
  2. U11b/U12d/U15c 原先拿 `run_task` 当"自家窗"代理：**被测项和读数器健康判据是同一个数**，`run_task` 真消失时会被误读成"没在读自家窗"（把红洗成 SKIP），而"屏上真没有 run_task"这条产品红永远判不出来 = 假门禁。

**修法（`scripts/ui-smoke.sh`，四条）**：
- 读数器健康的**独立**判据换成 `package="com.anytouch.app"`（自家每个节点都带它，与被断言的 testTag 无关）；扫视缓冲里一条都没有时 `SWEEP_OK` 直接压回 0，并把"本扫视 N 屏里 0 屏含自家窗节点"打成日志；
- 新增 `ui_expect`：命中 `hit` / 未命中 `miss` / **读数器不可用 `skip`** 三档；U1b、U8c、U10d、U11b、U12d、U15c 六处存在性断言改走三档（0 不再是"屏上没有"的唯一解释）；
- U6b/U12d/U15c 的缺席一侧继续走"没扫到底记 SKIP"，但 SKIP 话术里带上 `自家窗 x/y 屏`，下次能一眼分清"没到底"和"读错窗"；
- 页读纪律 §0 追加第 ④ 条（写在脚本头注释里，跟 ①②③ 同一段）。

**这条门禁能不能 FAIL，我当场验了**（不放能 FAIL 的门禁=放假门禁）：
`OWN_PKG=com.nonexistent.probe bash scripts/ui-smoke.sh`（`OWN_PKG` 是新增的覆盖变量，真机中文/换包名场景同一口径）→
输出 `| 本扫视 4 屏里 0 屏含 package="com.nonexistent.probe"：读的不是自家窗` + `SKIP U1b …`，**既不记绿也不记红**（`ui-smoke-31a-ownpkg-probe.log`）。
该轮被我 `timeout 150` 掐断（RC=124=超时，**不是脚本判红**），因为它的目的只是证明"撤掉判据→门禁必响"，不必跑完 41 条。

---

## §6 worker §6 未完成项逐条处置

| worker 申报 | 主窗处置 |
|---|---|
| §6-1 设备关未跑待主窗补 | **已补**（§1 关 4 + §4 靶选择 + §5 读数层返工） |
| §6-2 31-B 未动，等老板一句话 | 维持。目标模式**不**覆盖 `ANYTOUCH-S31-ORDER.md` §3 那道门（老板 09-24 已裁"不算，另等一句话"），本片之后不自动开工 |
| §6-3 平台缝（`AndroidCaptureBridge` 构造/`Log` 出屏）JVM 覆盖=0 | 接受这个边界，**不为其加 `isReturnDefaultValues`**（纪律 #4，worker 也没加）。判据侧 31 例锁死，取数侧只有设备证据——这句话照抄进本文件 §2③，不留"31 例=整条链覆盖"的误读空间 |
| §6-4 残留：预算触顶时未回收的 `getChild` 副本 | **裁决已落**：`orders/RULINGS-20260922.md` **S31-A1**——**不改**。三条磁盘根据 + 一条诚实边界（`recycle()` 的运行时效果本机不可验：android.jar 是 stub、compileSdk=36），并写明老板可覆 |
| §6-5 协程侧残留不在本片 | 与 E0 §7-3 无交集，不并账 |
| §6-6 未新建 `scripts/` 工具（mutation 驱动放临时目录） | 接受：一次性工具不入仓，与本项目既有做法一致；主窗 §3 两针同样在临时目录做，只把结论与 md5 落本文件 |

---

## §7 交付账（进 `orders/METRICS.md` 的那一行在此留底）

- 类别：**一次通过**（判据搬移本身：无返工、无虚报——worker 自述的分模块真数 363、mutation 15 针、"设备关未跑"三项与主窗独立重跑结果逐字对得上，尤其"未跑"是**主动声明**而非被查出）。
- 返工记在**主窗**：关 4 的 `ui-smoke` 读数层（首轮 5 假红 → 加独立自家窗坐实判据 + 三档读数 + 能 FAIL 的探针）。
- JVM：332 → **363**（app 254→285 / byok 59 / contracts 19，单变体 `--rerun-tasks` 分模块 XML 求和，同一口径不另起账）。
- 本批 #38 平台层缺口账：**A4/A5 计为完成**（31-A 收口），A1/A2/A3 随 31-B 另等一句话——**不重复计**（METRICS 里 A4/A5 那格从"未开始"转"已落"，缺口总数按 S3-R1 重定的 A1–A5 口径核减）。
