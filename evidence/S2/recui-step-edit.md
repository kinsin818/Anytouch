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
本次探针用 `logcat -d -v brief`（不带时间戳），导致"紧邻两行"无法证明"前后两行"，一条上一轮残留行差点被我算进本轮证据（已在 `raw/recui-probe-stale-red-20260923.log` 末尾如实标注为不采信）。**规则：探针类取证一律 `-v time`，并在起停各打一条标记行。**
