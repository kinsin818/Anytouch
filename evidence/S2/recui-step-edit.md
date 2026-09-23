# 录制 UI 面（步序账编辑）批次证据

日期：2026-09-23（本地时钟）｜设备：emulator-5554（MarvisPhone 基准 AVD，英文 Settings）
派单登记：`orders/ANYTOUCH-S2-recui-ORDER.md`（**范围与判据全为主窗自拟、逐条待裁**，勿当验收依据）
前置事实：本批开工前，主窗曾虚报本批"已收工"（详见 ORDER §4 撤回登记），本文件里的每一条都重写重跑。

## 1. 交付面（磁盘可查，逐条落字）

| 件 | 内容 |
|---|---|
| `app/src/main/kotlin/com/anytouch/app/recorder/StepEditing.kt` | 纯函数层：`StepEdit`（Remove/Rename/Move）、`stepEditGateOf` 四档门禁（READY/EMPTY_LEDGER/OUT_OF_RANGE/BLANK_NAME）、`StepEditGate.userCopy()` 话术单源、`applyStepEdit`（转调冻结 `removeStep/renameStep/moveStep`）、`suggestionAfterEdit`（空账不发建议）、`stepLabel`（人读标题）、`opName`（留痕用） |
| `app/src/main/kotlin/com/anytouch/app/ui/StepListUi.kt` | Compose 编辑区：每行"第几步 + 类型 + 定位依据"、步骤名输入框、上移/下移/删除。组件内**零判据**（门禁不在按钮上），只构造 `StepEdit` 送唯一写口 |
| `RecorderStore`（session/） | 新增唯一写口 `applyEdit(StepEdit): Boolean` + `editRejection` 流；`stopAndCompile` 空产物时**步序账照实清零**（旧版只在非空时写，留下"屏上挂着上一次"的第二套账）；`start()` 作废旧账留痕 `作废旧步序账 steps=N` |
| `MainActivity` | 编辑区上屏 + 被拒话术显示（`step_edit_rejection`）+ adb 注入通道 `step_remove/step_rename(_to)/step_move(_to)`（缺目标位送 -1 哨兵，交门禁拒，不猜意图） |
| `scripts/ui-smoke.sh` | U 系列 23 条断言（新脚本，不混进 C 系列 13 项回归锁） |
| `evidence/S2/ui-smoke-session-fixture.json` + `UiSmokeSessionFixtureTest` | 预置会话档 = 冻结 `RecorderSession.serialize()` 真产物；测试两侧锁：读档→编译 3 步→再序列化逐字回原档（往返无损）+ **脚本内字面量与本测试字面量逐字一致**（防两拷贝漂移） |
| `StepEditingTest` | 14 例 JVM 锁 |

## 2. 三条契约口径的实现位置（均待裁，见 ORDER §2）

- U-1 改名只动 `action_id`：`applyStepEdit` 走冻结 `renameStep`（签名里就没有线索字段）；
  设备正证 U3b——改名后任务 JSON 里 `u3renamed` 在、`Connected devices` / `Connection preferences` / `Bluetooth` 三条线索一条不少。
- U-2 删到清零不写建议：判据住在纯函数 `suggestionAfterEdit`（JVM 锁得住），`RecorderStore` 只按 null/非 null 分流；
  设备正证 U8a（`步序账清零` 留痕）+ U8b（本轮 `S2SMOKE-TASK []` 计数=0）+ U8c（屏上同步为空态文案）。
- U-3 越界必拒不崩：`stepEditGateOf` 先于原语；JVM 里用"非 READY 判据下 `applyStepEdit` 必抛
  `IllegalArgumentException`"反向锁住门禁不漏（若哪天原语放宽，测试先红）；设备正证 U5a/b/c（三条越界全拒 + `pidof` 仍在）。

## 3. 验证账（真数）

- **JVM**：`--rerun-tasks` 单变体逐模块 testsuite 计数：**:app 161 / :core:contracts 19 / :tools:compiler 12 = 192，failures=0 errors=0 skipped=0**。
  本批新增 = `StepEditingTest` 14 例 + `UiSmokeSessionFixtureTest` 2 例（145→161，其余零改动零回归）。
- **ci-local**：四步 PASS，红线 A–E clean（日志 `evidence/S2/raw/ci-local-recui-20260923.log`（改测试前）/ `raw/ci-local-recui-20260923-2.log`（最终树重跑，四步 PASS + 红线 A–E clean））。
- **ui-smoke（新面）**：**23 条断言 × 两轮全绿（0 FAIL）**，全文
  `evidence/S2/ui-smoke-run1-20260923.txt` / `-run2-`。第二轮是必需的：第一轮"一次全绿"本身不构成判据，
  重复跑同时验了幂等（进程不重启、步序账从空→3→2→0→空 再走一遍仍全绿）。关键回执：
  - `S2SMOKE compiled ok actions=3 drops=0 merged=0 events=4`
  - 移序 → `step edit ok=move index=0 before=3 after=3`，任务 JSON 顺序 `rec-0002 rec-0003 rec-0001`，移回还原 `rec-0001 rec-0002 rec-0003`
  - 删末步 → `ok=remove index=2 before=3 after=2`，dump 里 `step_delete_2` 消失、`step_delete_1` 在、计数文案改口"步骤 2"
  - **编辑真进回放** → 编辑后 JSON 原样注入：`S1SMOKE ok=2 total=2 stopped=false`（3 步链删 1 步后 total 变 2，不是 3）
  - 三档拒因逐条带话术入日志：`refused gate=BLANK_NAME/OUT_OF_RANGE/EMPTY_LEDGER` + `ledger=N`，
    且 U4b 证明被拒那次没有产生 `ok=rename`（计数恒 1）、U9b 证明 `EMPTY_LEDGER` 计数本轮从 0→1（不拿旧痕充数）
  - **dump 读数互控**：同一 `ui_has` 对 `step_delete_2` 在 U1b 读到 1、在 U6b 读到 0——
    读数器既能命中又能不命中，才允许把后面的"0"当证据（单边 0 或单边 1 都不自证）。
- **C 系列回归（同构建）**：`scripts/device-smoke.sh` **13 项 PASS / 0 FAIL / ALL PASS**
  （`evidence/S2/device-smoke-recui-20260923.txt`）。UI 面改动未碰采集/执行/安全三条链，此项是防"新面把旧面碰坏"。

## 4. 诚实边界（本批没验到的）

1. **UI 手势路径未跑**：23 条断言全部走 adb 注入通道（与按钮同写口的证明靠 JVM + 代码路径同构），
   真手指点 `step_delete_0` 的 Compose 点击链在设备上是零覆盖。原因：本会话内没有可用的坐标注入通道授权
   （红线 D 禁产品坐标，测试通道 `input tap` 需要球位以外的坐标学习），且这条链的失效面（标签丢失/重组合错位）
   与注入通道不完全重合。要么老板授权"UI 面用 `input tap` + dump 学坐标"开一条测试通道，要么留到真机轮补。
2. **改名输入框（`step_rename_field_*`）只验了"送进去的值"**：IME 打字、焦点切换、`remember(action.actionId)`
   草稿回落这三段没有设备证据；草稿回落有 JVM 侧的等价论证（下标漂移后行内容即真值）但那是推理不是实证。
3. **执行中编辑（RUNNING 期改账）未做门禁**：现口径只拦"球挂不上/服务不在/执行中"三条**开录**边，
   执行中改步序账当前是允许的（改的是内存里那份产物，不影响在跑任务的已解码 JSON）。是否要加"执行中禁编辑"待裁。
4. **AVD≠真机**：本批全部证据来自 API 35 模拟器；小米系两真机（雷 12/13 族）与 Samsung/Moto 缺口未覆盖，
   按 S2-R4 延后不卡主流程。
5. 步序账与任务框之间仍是"建议流回灌"一条路：用户手改过任务框后再编辑步骤，编辑会把框里的文本**覆盖**掉
   （`LaunchedEffect(suggestion)` 语义）。当前判定是"编辑是显式动作、覆盖即预期"，但这条没进任何判据，记档待裁。

## 5. 视觉实证与三条 UI 缺陷（09-23 同构建 a027640，emulator-5554 / API 35，全部待裁未擅改）

功能断言（§3）全走日志与 dump，看不出"屏上好不好用"。本段补五张截图（两张整页 + 三张探针）+ 一次开录探针，把两条推理升级为实证。

| 图 | 文件 | 看到什么 |
|---|---|---|
| A | `img/recui-step-list-3steps.png` | 步骤 3 上屏：`#0 click · Connected devices` + 步骤名框 `rec-0001` + 改名/上移(灰)/下移/删除；`#2` 行下移为灰（末位）——**首末位禁用边在屏上成立** |
| B | `img/recui-step-list-empty.png` | 步骤 0 + 空态文案 + EMPTY_LEDGER 红字同屏；**但任务框仍挂着 rec-0003 单步 JSON** |
| C | `img/recui-probe-A-idle-stale-red.png` | 空闲态同时挂两条红字：`任务执行中不能开录…`（陈旧）与 `当前没有可编辑的步骤…`（有效）；球压住"…后才能增删改。"尾行与任务框首字 |
| D | `img/recui-probe-B-recording-accepted.png` | 探针 T1 后：球变「停录」= 开录被放行（与 C 的红字直接矛盾） |
| E | `img/recui-probe-C-orphan-task-json.png` | 重新录→编译空产物后：账=0 步、框内仍是旧 rec-0003 单步、"执行任务"可点 |

### 缺陷①：悬浮球压内容（A/C/E 三处同现）
球位固定在屏幕左中，正好盖住"步骤名"输入框前缀（`rec-0001` 被吃成 `0001`）、拒因红字尾行、任务框第二行首字。
文字被遮不是美观问题：**编辑页的定位线索与拒因话术都是"必须显示"的东西**（L2-③），遮住=信息缺失。
候选修法（未动）：球改右缘 + 边缘吸附/半透明，或列表与任务框左侧留球位内边距。产品坐标红线不涉及此项（禁的是"用坐标定位节点"，不是布局）。

### 缺陷②：`startRejection` 没有过期边 ⇒ 空闲态误报（实证，非推理）
- 屏上（图 C，07:28）：状态行"空闲 / 未在录制"，红字却写着"任务执行中不能开录：执行器的手会被录成你的意图…"。
- 探针（图 D，07:29）：`am start … --es record_start com.android.settings` → 日志只有 `S2SMOKE record start target=com.android.settings`、**无 `refused gate=`**，球变「停录」。
- 结论：这条红字唯一覆盖边是"下一次开录（成功或失败）"，缺"状态已变即作废"。用户按红字理解会以为现在不能录，实际门禁判 READY——**误报即假红**，与"假红与假绿同罪"同族。
- 候选修法（未动）：`running` 由真变假、或录制会话结束时把 `startRejection` 置 null（与 `editRejection` 在 start 时清零对称）；JVM 侧可锁"状态跃迁后话术必为空"。

### 缺陷③：账清零后任务框成为"账外孤儿任务"（与 §4 第 5 条"不夺字"正面冲突）
- 图 E 实证：步序账 0 步（"步序账为空"），框内却是上一次非空编辑写下的 `[{"action_id":"rec-0003",…}]`，"执行任务"按钮可点。
- 也就是说：**屏上此刻有两套可回放真值**——账（0 步）与框（1 步）。§2 的"不留第二套真值"只锁住了"编辑不另存一份账"，没锁住"框内文本可来自已作废的账"。
- 这不是 §4 第 5 条那种良性表现（那条讲"编辑不夺用户手改的字"）：这里框内文本本身是机器写的、且对应的账已不存在。
- 候选修法（需裁，三条互斥）：
  (a) **执行前比对**：点"执行任务"时若框内 JSON ≠ 当前步序账序列化，则拒执行并显示话术（fail-closed，主窗推荐）；
  (b) 框旁常驻标注"框内文本不代表当前步序账"（不夺字、不拦执行）；
  (c) 账清零即清空框（违"不夺字"，需老板明示才做）。

### 撤回一条本打算入账的"缺陷"
准备上报"改名输入框拉起 IME 键盘压住列表"——A/C/E 三张图里**均无键盘**（测试通道注入不经过输入框，键盘从未弹起）。该条不成立，撤回不入账；同时说明它反过来证明：**IME 路径至今零覆盖**（§4 第 2 条维持原判）。

### 取证纪律补条
本次探针用 `logcat -d -v brief`（不带时间戳），导致"紧邻两行"无法证明"前后两行"，一条上一轮残留行差点被我算进本轮证据（已在 `raw/recui-probe-stale-red-20260923.log` 末尾如实标注为不采信）。**规则：探针类取证一律 `-v time`，并在起停各打一条标记行。**（老板裁决 4 追认：以后探针日志必须带时间戳。）

## 6. 三修落码与最终构建复验（09-23，老板裁决 1/2/3 落地）

裁决原文与落点对照：

| 缺陷 | 裁决 | 落码位置（纯函数在门禁侧，UI/存储只按结果路由） | 新增锁 |
|---|---|---|---|
| ① 球压字 | "悬浮球移右缘+半透明，别压字" | `OverlayUi.showRecordBall`：`gravity=END\|CENTER_VERTICAL, x=12, alpha=0.7f` | U13 像素判据（`scripts/ball_position.py` 扫深绿球中心横占比 >0.75，匹配像素 <40 直接 exit 2 响亮失败） |
| ② 空闲态假红 | "running 转假/会话结束时清空拒因状态，加 JVM 锁" | 纯函数 `AccessibilityGate.startRejectionAfterStateChange(current, gate)`；`RecorderStore.revalidateStartRejection()` 由 `watchRecordBall` 三流合流处调用 | `AccessibilityGateTest` +4 例，含"设备实证过的假红形态"按探针时序回放 |
| ③ 两套真值 | "同意你推荐的方案——执行前比对步序账和任务框 JSON，不一致就拒放提示用户" | 纯函数 `TaskAdmission.taskAdmission(boxJson, ledgerJson, lastSuggestion)`；`MainActivity.submitTask(json, via)` 单一漏斗（按钮与 adb 注入同路）；`AppState.taskRejection` 上屏红字 | `TaskAdmissionTest` 11 例（放行面 4 / 拒放面 3 / 序列化 1 / 话术 3）+ U10/U11 设备面 |

### 6.1 ③ 的口径收窄（与裁决字面不同，必须显式上报）
字面判据"框内 JSON ≠ 当前步序账 ⇒ 拒放"会把两条正当路径一起打死：
- S1 主路径是**用户手敲 JSON 直接执行**，此刻步序账常为 0（录→编→放之外的老路径，device-smoke 全部 13 项与 C 系列都以此注入）；
- 于是"账=0、框=1 步"既可能是孤儿建议（该拒），也可能是用户手敲（该放）——**单靠文本比对无法区分**。

收窄后的判据：只有当"框内文本 == 机器上一次发布的建议"且"该建议 ≠ 当前账"时才拒（来源判定，不是文本相等判定）。为此在 `RecorderStore` 区分两个字段：`suggestedTaskJson`（一次性、被 UI 消费后置空）与 `lastSuggestedJson`（机器发布过什么的事实，不消费）。反面锁：U11a 手敲 JSON 必须跑成 `ok=1 total=1`、U11b 放行后红字必撤。裁决原意"不夺用户输入、不常驻标注挡 UI"两条均照办；偏差仅在"什么算不一致"。

### 6.2 最终构建复验（停球补半透明后重装，全部同构建）
构建：`:app:installDebug` 后 emulator-5554（API 35）。停球只加 `alpha=0.7f`、**位置有意不动**（`device-smoke` 的 `BALL_TAP=1002 1272` 与 K40/K80 两台真机实证坐标沿用同一停点，挪球=作废三台设备的急停证据）。

- `scripts/ui-smoke.sh`：34 项 × 2 轮，两轮各 **34 PASS / 0 FAIL**（`raw/recui-uismoke-final-20260923-1623.log`、`-1628.log`；前一轮 23 项，本轮新增 U10a-d / U11a-b / U12a-d / U13 共 11 项）
- `scripts/device-smoke.sh`：**13/13 ALL PASS**（`raw/recui-devicesmoke-final-20260923-1626.log`）——C6/C7 在停球半透明后仍按原坐标点球生效，证明 alpha 未伤及急停
- `scripts/ci-local.sh`：见 §6.4 结论行
- V-2 过期延迟实测：`08:33:12.784` 执行结束（`ok=0 total=1 stopped=true`）→ `08:33:12.799` `record rejection expired`，**15 ms**（另一轮 16 ms）
- V-3 拒放留痕原文：`S1SMOKE submit refused gate=STALE_SUGGESTION via=adb_inject ledger=0 machineSuggestion=179`

### 6.3 截图（final 为本批交付口径，build1 保留不删）
| 图 | 文件 | 看到什么 |
|---|---|---|
| V-1 | `img/v1-ball-right-steplist.png` | 录制球「●开录」在右缘、半透明；三行步骤名框 `rec-0001/0002/0003` 全字可读，无一字被压 |
| V-2a | `img/v2a-running-legit-red.png` | 执行中 + 红字"任务执行中不能开录…"（**合法红**：门禁真拒）；停球半透明压在 `#0` 行"改名"右缘 |
| V-2b | `img/v2b-idle-red-expired.png` | 同一次执行结束后：状态"空闲"、红字已自动作废、球回右缘——§5 图 C 的假红形态不复现 |
| V-3 | `img/v3-orphan-refused-on-screen.png` | 账=0（"步序账为空"）而框内仍挂 `rec-0003` 单步；点执行 → 红字完整上屏，任务未派发 |

`-build1` 四张是同批前一个构建（停球未加 alpha）的取证，按"证据只追加不覆盖"留在原位；`img/v3-shot1-miscounted-removes-legit-run.png` 是我自己数错删步数（账=1 恰等于最后建议，属合法放行）拍到的一张误图，改名留档不删。

### 6.4 遗留与边界（未修，待裁/待派）
1. **停球与"改名"按钮右缘仍有重叠**：alpha 只解决"看得见字"，不解决"点得到按钮"——执行期间该行的改名本就无意义（执行中禁编辑门禁尚未落地，见 STATUS 待办），故登记为待裁：要么执行期把球位上移出列表区（作废三台设备坐标证据），要么落地"执行中禁编辑"门禁后此项自然消解。**未擅自挪球。**（09-23 老板裁决 2 选后者，已落地并复验，见 §7；本条原文不改。）

2. `scripts/ci-local.sh`：**PASS**，红线 A–E 全清（`raw/ci-local-final-20260923-1635.log`）。JVM 总数按"单变体（app=debug）逐模块 `<testsuite tests>` 求和 + `--rerun`"口径：**207 = app 176 / contracts 19 / compiler 12，failures=0**。注记一条口径坑：`app/build/test-results/` 下同时躺着 09-22 的 `testReleaseUnitTest` 残留 119 例，整目录 glob 会读出 295——**只认 `testDebugUnitTest/` 那 16 个文件**。
3. **IME 路径仍零覆盖**：本批四张图里键盘从未弹起（注入通道不经过输入框），§5 撤回的那条"缺陷"反过来仍是这条边界。真实手指操作（拖球、点列表按钮、键盘改名）全部未验。
4. JVM 总数与红线口径见 METRICS 本批条目；`94 例平台层缺口`（S2-R2）与雷 15–18 真机复验（S2-R4）仍延后。

## 7. 执行中禁编辑门禁（09-23 老板裁决第二批落地，同构建复验）

裁决原文（09-23 第二条消息，三条，逐字照录）：

> 1. **V-3改成来源判定认可**——没打死手敲路径和冒烟注入，比字面执行更稳，没问题
> 2. 新待裁同意你推荐的方案：**做执行中禁编辑门禁**，不动停止球位置，保留三台真机急停的坐标证据，重叠区因为执行期不让编辑自然就消了
> 3. 现在JVM 207例、UI smoke 34断言两轮全绿、device-smoke 13/13，数字全是真数，认可
> 继续往下做：先把执行中禁编辑门禁加上，再补平台层那94例JVM缺口。

三条的执行账：① V-3 收窄**转为已裁**（§6.1 的"待确认"标记作废，实现不改）；② 门禁本批落地，见 §7.1–§7.5，停止球坐标一个字节没动（`OverlayUi` 本批零改动）；③ 数字已按真账上报，本批在其上叠加。

### 7.1 门禁落点（判据在纯函数，UI 置灰只是提示）

| 面 | 改动 | 位置 |
|---|---|---|
| 判据 | 编辑门禁第五档 `RUNNING`；三参重载 `stepEditGateOf(actions, edit, running)`，**RUNNING 排在其余三档之前**（执行中空账也报"执行中"，不报"先录制"——那时用户该等，不该去点录制） | `app/.../recorder/StepEditing.kt` |
| 写口 | `applyEdit` 读 `AppState.running.value` 后判门禁；非 READY 一律 `rejectEdit(gate)` + 留痕 `step edit refused gate=… op=… index=… ledger=…`，**UI 按钮与 adb 注入同一条路**（置灰不是门禁） | `RecorderStore.applyEdit` |
| 过期边 | 纯函数 `editRejectionAfterStateChange(current, running)`：仅"当前挂 RUNNING 且已不跑"才作废；接线在 `watchRecordBall` 三流合流处，紧跟 V-2 的 `revalidateStartRejection()` 之后 | `StepEditing.kt` + `AnytouchAccessibilityService.kt` |
| 话术 | "任务执行中不能改步骤：账本一边跑一边改，回放依据就对不上执行现场了。请等本轮结束（或点悬浮球停止）后再删改。" | `StepEditGate.userCopy()` |
| 呈现 | `StepListEditor(actions, onEdit, modifier, editable = !running)`：行内改名/上移/下移/删除四钮置灰，并在列表头显示同一条 RUNNING 话术（`testTag=step_edit_locked_hint`） | `ui/StepListUi.kt` + `MainActivity` 传参 |

两条设计约束照旧兑现：**冻结层零改动**（`RejectionReason` 无新档，归因走日志 `gate=` 字段）；**门禁落入口不落按钮**（与 V-2/V-3 同构）。

### 7.2 拒因状态存枚举、不存文本
`RecorderStore` 新增 `@Volatile editRejectionGate: StepEditGate?`，与 `editRejection: String?` 成对写（`rejectEdit`/`clearEditRejection` 两个私有口，全仓再无第三处赋值）。理由：过期边要判"挂的是不是纯状态档"，拿文本比对就是**字符串当身份**——话术改一个字，过期逻辑就静默失效。三处原有 `editRejection.value = null` 全部改走 `clearEditRejection()`。

### 7.3 JVM 锁（`StepEditingTest` 14 → 18 例，本批 +4）
1. `执行中一律拒为 RUNNING 且排在其余三档之前`——四档请求在 running=true 下全部转 RUNNING（含空账，锁"优先级"这条口径）
2. `非执行中三参判定与两参逐档同果（禁编辑不得顺手改坏旧判据）`——running=false 时三参必须与两参一字不差，防"加一档顺手改坏老三门禁"
3. `跑完即可编 同一请求由 RUNNING 转 READY`——同一 `Remove(0)` 请求两态互转
4. `过期边只作废纯状态档 请求档不得被状态跃迁顺手抹掉`——RUNNING+不跑=null；RUNNING+跑=RUNNING；EMPTY/OUT_OF_RANGE/BLANK_NAME 三档在状态跃迁下**原样保留**（它们绑在那次请求上）；current=null 时不得凭空造话术
另：原"三档拒因各有话术"扩为**四档**，新增断言 RUNNING 话术必须含"执行中"与"停止"（用户要能在话术里读到出路）。

### 7.4 同构建复验真数（emulator-5554 / API 35，PID 24388）
- `scripts/ui-smoke.sh`：34 → **41 条断言 × 2 轮，两轮各 41 PASS / 0 FAIL**（`raw/recui-uismoke-gate-20260923-1717.log`、`recui-uismoke-gate2-20260923-1717.log`）
- `scripts/device-smoke.sh`：**13/13 ALL PASS**，C 系列零回归（`raw/recui-devicesmoke-gate-20260923-1717.log`）
- `scripts/ci-local.sh`：**PASS**，红线 A–E 全清（`raw/ci-local-gate-20260923-1717.log`）。JVM 单变体口径：**211 = app 180 / contracts 19 / compiler 12，failures=0 errors=0**（app 16 个 testsuite 文件，本批 +4 例，与 §6.4-2 的 207 同一口径）
- 新增 7 条设备断言（U14/U15，第一轮原文）：
  - U14a `09:07:46.460 step edit refused gate=RUNNING op=remove index=0 ledger=3`（注入通道，绕过置灰按钮同样被拒）
  - U14b 执行期 `ok=remove` 计数 = 0（拒了就是零放行，不许"拒了但改了"）
  - U14c 拒因行自带 `ledger=3` → 门禁读到的是真账，三步没被删掉
  - U15a `09:07:58.524 ok=0 total=1 stopped=true`（本轮结束回执）
  - U15b `09:07:58.540 edit rejection expired gate_was=RUNNING` → **过期延迟 16 ms**，与 V-2 的 15 ms 同量级
  - U15c 三方 dump 互控读数：自家窗 `run_task`=1（证明在读自己的窗）、`step_edit_rejection`=0、`step_edit_locked_hint`=0（红字与置灰提示双双撤净）
  - U15d `09:08:09.784 step edit ok=remove index=0 before=3 after=2` → **跑完立刻可编**，同一请求由拒转放
- 屏上实证：`img/v4-running-edit-locked.png`（"任务执行中…（可点悬浮球停止）"+ RUNNING 红字 + 四钮全灰；停球仍压在"改名"右缘，但该钮已禁用 → 裁决 2 说的"重叠区自然消解"目视成立）

**构建一致性如实记一笔**：上表设备断言跑完之后，源码只再改了一行 **KDoc 注释**（`StepEditGate.RUNNING` 的注释从"V-1 待裁项"改为"裁决 2"，措辞纠错、零语义）。改后重跑 `:app:assembleDebug` + `:app:testDebugUnitTest --rerun-tasks`：**180 例 / 0 失败**（与上表同数）。设备侧未因这一行注释复跑——若按"改一件重拍一件"的严格口径，这条属已知偏差，如实登记不掩盖。

### 7.5 测试通道新律：**dump 会打断正在跑的任务**（本批踩到，代价一轮整跑）
旧口径只知"`uiautomator dump` 注册 UiTestAutomationService 会挤掉自家服务，不复绑则**下一条**用例假红"。本批第一次写 U14 时在**在跑任务期间** dump 读屏上红字，设备实证它不止挤掉服务，还会**取消当轮 runTask**：`09:01:32.372 S1SMOKE run cancelled by service lifecycle … SERVICE_INTERRUPTED`（results 为空），随后过期边正常作废，两条读数同时 0/0——看起来像"红字从没出现过"，实为**探针自毁现场**。若当时按"0=通过"写死断言，就是一枚假绿。
处置：执行期那一段**零 dump**（`scripts/ui-smoke.sh` U14 段注释已写明），"红字出现过"改由人眼截图为证（§7.4 的 PNG），"红字消失"那一半仍留机器断言（U15c 在无在跑任务时 dump 无害）。原始日志节选留档：`raw/recui-gate-dump-cancels-run-20260923-1717.log`（末尾附不采信声明）。
**律：任何 dump 都是一次服务抢占——只在"无在跑任务、无在跑录制"时读屏；要证执行中的屏上状态，用截图，不用 dump。**

### 7.6 本批诚实边界（未验，别当已验）
1. **真实手指点击**：U14 走的是 adb 注入通道（这正是"置灰不是门禁"的证明），但**手指点置灰按钮**这条 UI 路径未做设备断言——Compose 的 `enabled=false` 拦截只有代码依据，无触摸实证。
2. **IME 仍零覆盖**（§6.4-3 原样维持）：截图里键盘从未弹起，改名框内的 `rec-0001` 是会话档预置值，非用户键入。
3. **执行中改名草稿的存活**未断言：跑完置灰解除后草稿是否仍是用户输入的那份（`remember(action.actionId)` 只在步骤身份变化时回落真值），本批只锁了 JVM 侧的回落语义，设备侧未测。
4. 停止球坐标证据沿用不变；雷 15–18 真机复验（S2-R4）与平台层 94 例缺口（S2-R2）仍延后，后者是老板指定的下一步。

