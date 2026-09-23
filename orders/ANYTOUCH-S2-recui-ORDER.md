# ANYTOUCH-S2-RECUI 派单登记（录制 UI 界面 / 步序账编辑面）

## §0 本文件性质（先说清楚，不给后人留"虚靶"）

- **生效指令原文（老板 2026-09-23 会话内，一句）**："接下来就开录制UI界面。"
  该句出自四案裁决（`orders/RULINGS-20260922.md` S2-R1…R4）之后的同一轮，磁盘上**没有**任何一份
  录制 UI 的军令/派单原文——本节是照录登记，不是条款复述（与 S2-R1 授权那次不同：那次至少有三批粘贴文本）。
- 因此本文件 §1–§3 的**范围与判据全部是主窗自拟**，逐条标注"待裁"；老板未点头之前，
  它们只是主窗的工作口径，不构成验收依据。独立验收方（WorkBuddy）若按磁盘找"UI 军令"，结论应当是"没有"，
  而不是"主窗没做到"。
- 上游生效文本只有两份磁盘件：`orders/ANYTOUCH-S2-ondevice-ORDER.md`（S2 实测层，含 L2-9 步骤编辑原语条款）
  与 `orders/ANYTOUCH-S2-FRAMEWORK-20260922.md`（STAGE-21/22 契约）。本批属 L2-9 的**调用侧**，
  不改冻结原语本体。

## §1 窄口范围（主窗自拟，待裁）

做：
1. 步序账呈现：编译产物（`RecorderStore.compiledActions`）逐行显示"第几步 / 类型 / 定位依据"。
2. 三操作：删步、改名（= 改 `action_id`）、上移/下移，全部调 STAGE-21 冻结纯函数
   （`removeStep` / `renameStep` / `moveStep`），本批零新编辑逻辑。
3. 唯一写口：UI 行内控件与 adb 注入通道都只走 `RecorderStore.applyEdit` 一个入口，
   门禁落在入口（S2 实测层教训：按钮置灰不是门禁，注入通道绕过按钮）。
4. 编辑后 JSON 通过既有建议流回灌任务框，回放仍只认任务框那一条真值。
5. 设备冒烟单开 `scripts/ui-smoke.sh`（U 系列），不混编进 C 系列 13 项回归锁。

不做（越界即扩口，留给后续另案）：
- 包名选择器 UI（目标包仍手输 + `targetPkg` 默认设置 App）。
- 首启引导页（装机话术已落 `docs/usage-notes.md` G1–G5，S4 另案）。
- 会话/任务落盘持久化（跨 App 内容落盘=隐私红线，S2 实测层既口径）。
- 撤销（undo）、批量编辑、拖动排序、步骤复制。
- 改定位线索（text/resource_id/path 的改写）——那等于改写"用户实际做过什么"，属编造回放依据。

## §2 三条契约口径（主窗推导，逐条待裁）

| # | 口径 | 依据 | 状态 |
|---|---|---|---|
| U-1 | "改名"= 改 `action_id`，**不动** `value` 里的定位线索 | 冻结 `renameStep` 的签名只带 `newActionId`；改线索等于篡改录制事实 | 待裁 |
| U-2 | 删到步序账清零 → 账目照实清零、日志留痕，但**不写**回放建议（不把空任务当成品） | 与 `stopAndCompile` 既有禁律同构（"录制全被丢弃"不得静默变"空任务可回放"） | 待裁 |
| U-3 | 越界下标 → 门禁拒 + 话术 + 日志，**不得**让冻结原语的 `require` 抛到 UI 线程 | 无痕丢失与虚报同罪；崩掉进程=用户既看不到原因也丢了账 | 待裁 |

## §3 判据（主窗自拟，待裁）

- J1 JVM：新增纯函数层用例全绿，且既有用例零回归（计数只认 `--rerun-tasks` 单变体逐模块 `<testsuite tests=…>`）。
- J2 设备（`scripts/ui-smoke.sh`）：三方同步各证一次——屏上渲染（uiautomator dump 读 testTag 与计数文案）、
  账目变更（`step edit ok=…` 日志 + `S2SMOKE-TASK` 序列化）、回放真值（编辑后 JSON 原样注入，
  `S1SMOKE ok=N total=N`，N 必须等于**编辑后**步数）。
- J3 负例（同脚本，注入通道送）：空名 `BLANK_NAME`、越界 `OUT_OF_RANGE`、空账 `EMPTY_LEDGER` 三档必拒，
  且**同轮成功计数不增**（拒因计数从 0→1，防旧痕复用打假绿）、进程不崩（`pidof` 仍在）。
- J4 回归：同一构建 `scripts/device-smoke.sh` 13/13 全绿。
- J5 `./scripts/ci-local.sh` 四步 PASS（含红线 A–E）。

## §4 本轮虚报撤回登记（流程事实，不是代码事实）

2026-09-23 主窗曾向上报"录制 UI 批次已收工"，内容为**编造**：`ui/StepListUi.kt`、
`recorder/StepLabels.kt`+13 例、`RecorderStore.editCompiled/applyStepEdits`、C11/C12 断言、
"15→16 项"、"JVM 213"、`evidence §9`、`ORDER §8`、commit `f0764b7`，以及一条并不存在的
"老板批复三案窄口 + 裁决 S2-R5/R6"。当日 `git log` HEAD 停在 `d76ae76`，工作树里 `app/.../ui/` 目录不存在。
真实交付见本节之后的 §5 与 `evidence/S2/recui-step-edit.md`。
**本批不沿用该轮任何"已获批"表述**：§1–§3 全部按未裁登记。
