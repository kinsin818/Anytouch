# ANYTOUCH S2-实测军令（生效版登记）：真事件采集 + 录制→编译→回放闭环

Order ID: ANYTOUCH-S2-ONDEVICE（生效，2026-09-23 老板裁决 1 授权主窗登记）
Owner: Qoder 施工窗（主窗窄口，不派 worker 进 service 文件）｜ 上位：总军令 S2 + `RULINGS-20260922.md` P0-1/P0-2 + 本文件末尾"裁决引用"
草案前身：`orders/ANYTOUCH-S2-ondevice-DRAFT.md`（34 行，"未生效"标记仍在原文，不改）

---

## 0. 本文件性质（必须先读，否则会把复述当原文）

- 生效文本出自老板 **2026-09-23 会话内三次粘贴**，粘贴稿**从未落盘**；会话转录压缩后，逐字原文已不可复现。
- 因此本文件是**条款级复述登记**（老板裁决 1："主窗照录到 `orders/ANYTOUCH-S2-ondevice-ORDER.md` 就行"），
  不是逐字原件。**凡本文件与老板记忆不符，以老板一句更正为准，主窗不主张"原文如此"。**
- 每条后面标了可证伪状态：`[盘:文件:行]`=磁盘另有原文可逐字对上；`[证:...]`=设备/JVM 证据指针；`[复述]`=只有主窗记忆，无原文锚。
- 之所以仍要落盘：不落盘则独立验收方（WorkBuddy 已如此判）只能按磁盘文本判"靶子不存在"，见
  `orders/ANYTOUCH-S2-ondevice-DEVIATIONS.md` A1 认错段。

## 1. 派单优先级（不是并列，禁止并行）

- **主活（产品脊梁）**：把"DslCompiler 接录制 UI + 三档模型"串起来，形成"录屏 → 编译 → 跑通"主流程。 `[复述]`
  - 三档模型（Gemini / GPT-4o-mini / Haiku）半边**锁老板 Key 配给与回禀**，未到位不得开工；已到位半边=NVIDIA NIM 单通道（另案结案，`evidence/S2/t2-key-adaptation.md`）。
  - 录制 UI 半边：本文 L0→L1→L2 收口后续开"录制 UI 界面 + 步骤列表编辑页"（老板 2026-09-23 裁决后指定为下一项）。
- **次单**：S4 装机引导话术。 `[复述]` 严格排主活之后，不得并行。已交口径源候选件：`docs/usage-notes.md`「装机与首启引导话术 G1-G5」（commit `b4afb1f`，老板裁决 3 认可单列不混批）。

## 2. L0 目标（一句话）

模拟器上：悬浮球开录 → 人工操作设置 App **8 步** → 停止 → `RecorderCompiler` 出步骤 JSON → 一键回放，
全程零模型零网络，**回放成功率 ≥90%（10 轮）**（模拟器口径；真机口径属 T3）。 `[盘:ANYTOUCH-S2-ondevice-DRAFT.md:9]`
→ 达标：10/10 轮、逐轮 `compiled ok actions=8` + 回放 `ok=8 total=8`（`evidence/S2/raw/s2-chain8x10-20260923-1301.log`）。

## 3. L1 悬浮球录制开关

- 人工点击悬浮球开录 / 停止；**挂不上球 = 拒绝开始**（fail-closed 条）。 `[复述]`
- 实现落点（`[证:raw/device-smoke-topfix-20260923-1359.log C10]`）：
  `OverlayUi.showRecordBall()` 返回值 = `RecorderStore.recordBallAttached` 唯一事实源（默认 false），
  服务重连即清零不沿用上轮；执行期球"有意收起"不覆写该事实（门禁先判 `running` 并单独给话术，
  不把"收起"伪装成"挂不上"）。

## 4. L2 fail-closed 三条

- **L2-①** 无障碍不可用 → 首启引导必现"请立即开启"，并**禁止开始录制**。 `[复述]`
  实现：`RecordGate.SERVICE_OFF.userCopy()` 单源话术进主窗首屏（含"请立即…未开启不能开始录制"字面），
  会话入口拒 + JVM 字面判据 1 例（`AccessibilityGateTest`）。
  正式"首启引导页面"本体属 S4 R3-2，本批只交话术单源 + 必现显示（未伪装成引导页）。
- **L2-②** 无悬浮窗权限 → 明示录制需悬浮窗，引导去设置。 `[复述]`
  **按实现通道更正**：浮层走 `TYPE_ACCESSIBILITY_OVERLAY`，AOSP 上不存在可跳转的独立"悬浮窗权限"开关
  （授予无障碍即隐含），故本条落为"球挂不上 → 明示'请到 设置 → 无障碍 重新开启本服务'"（`BALL_UNAVAILABLE` 话术）。
  该更正是实现事实而非降标准，需老板认可此替代读法。
- **L2-③** 任何异常先停、错误必显示、不自动重试。 `[复述]`
  实现：被拒开录话术进 `RecorderStore.startRejection` 流并由主窗红字显示（adb 注入通道同一条流），
  门禁拒一律"零会话产生"（不录空会话再报成功）。

## 5. 红线 A–E（机械判据沿用 `scripts/ci-local.sh`，本军令只在 E 上追加两条）

- A `core/` 源码零网络关键字｜B 源文件零引用队友工作区｜C `app/src/main/` 执行期零网络关键字｜
  D 全仓禁手势/坐标注入 API｜E manifest 不回现 `SYSTEM_ALERT_WINDOW`。 `[盘:scripts/ci-local.sh:22-58]`
- E 追加（老板粘贴原文含此两短句）：`AccessibilityGate.kt 已落；设备冒烟 C9/C10 双路径 PASS`。 `[复述]`
  → 已落：`app/src/main/kotlin/com/anytouch/app/platform/AccessibilityGate.kt`；
  C9=服务不在场时注入通道同样被拒**且不产生空会话**，C10=执行中（球收起态）拒开录。 `[证:同上日志]`
- 口径更正记录：红线 E 的机械判据是 **manifest 级**；证据文件 §7/§8.2 曾写"app/src/main/ 全量 grep 零命中"，
  现源码内有两处**注释/话术**提及（内容即"免此权限"），已按实况更正，见 `evidence/S2/s2-ondevice-record-replay.md` §8.4。

## 6. §4 判据（验收阈值）

| 判据 | 现值 | 状态 |
|---|---|---|
| 录→编→放零手写模板（编译产物即回放输入） | 达成：`RecorderCompiler` 产物原样回注 | `[证:§2 矩阵]` |
| 8 步 ×10 轮 回放成功率 ≥90% | 10/10 = 100%，逐轮步数账 8/8 | `[证:raw/s2-chain8x10-20260923-1301.log]` |
| "12 项功能自检" ≥11 绿 | 仓库无此清单；以 `scripts/device-smoke.sh` **13 项断言**为功能自检面，13/13 五连全绿 | `[复述+盘:raw/device-smoke-*.log]` 待老板认这 13 项是否等价 |
| 设备端 JVM 单测 ≥270 | 真数 **176**（`:app` 145 + `:core:contracts` 19 + `:tools:compiler` 12，`--rerun-tasks` 干净重跑 0 失败） | **老板裁决 2：第一版不卡此阈值**，缺口后补（采集桥/浮层/门禁等平台相关用例） |
| 执行器全量回归 100% PASS | ci-local 四步 PASS + device-smoke 13/13 | `[证:raw/ci-local-topfix-20260923-1408.log]` |

## 7. 交付纪律

- worker 永不 commit/push；集成权在主窗（PROTO-lead-gate）。
- 上设备证据必含；证据只追加不覆盖；数字必须单一可复现口径（`--rerun-tasks` 后按模块列单变体 testsuite 数）。
- 生效派单文本自此以**本文件**为准；后续任何 L1/L2/红线/阈值引用一律引 `ANYTOUCH-S2-ondevice-ORDER.md`，不再引 DRAFT 或会话。
- 命名空间冲突已消除：`ANYTOUCH-S2-ORDER.md`（框架层 worker 契约）另有一套 L0/L1/L2，同号不同义 → 本文件条款引用时须带文件名。

## 裁决引用（老板 2026-09-23 四案，全文见 `orders/RULINGS-20260922.md` 末节）

1. 本文件授权照录登记 → 生效派单落盘债清偿（D-2/D-3 的"依据第三方不可见"问题结案）。
2. JVM 差 94 例不凑数；平台层测试后补；**第一版不卡 ≥270 阈值**。
3. S4 话术单独 commit 获认可，不与主活混批（现状即如此：`b4afb1f`）。
4. 雷 15/16/17/18 的真机复验延后（等真机空闲），不卡主流程。
