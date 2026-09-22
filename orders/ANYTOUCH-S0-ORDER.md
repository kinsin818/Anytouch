# ANYTOUCH S0 军令：工程奠基与契约移植

Order ID: ANYTOUCH-S0-GITBASE-20260922
Owner: Qoder（施工总窗口／主写）
上位文件: `C:\Users\Administrator\Desktop\Anytouch-立项大会\Anytouch-最终综合立项方案-Doubao汇总.md`（总军令，S0 定义见其第四节）
参照 SOP: `D:\codex\17-Codex多窗口调度标准流程.md`

## Objective

把空仓库变成"六类契约已 Kotlin 化、可序列化往返、构建全绿、目录已冻结"的 S0 基线，为 S1 真执行器提供不可再议的接口层。

## 契约来源（唯一事实源）

`D:\SuperMa-LeftRight\docs\module_contracts.md` 的六个 JSON 契约，1:1 翻译，**字段只增不改不删**：
Observation / ActionPlan / Action / ActionResult / Command / StopReason。
移植规则沿用 T1 审计报告结论：**契约移植 + 实现重写**，不 import 任何 Python 逻辑。

## Scope（允许改动）

- `D:\Anytouch\` 全目录（除下列禁区）
- 新增 `core/contracts/`（纯 Kotlin JVM 模块）、`orders/`、`docs/`、`evidence/`、`.gitignore`、Gradle 骨架文件
- CI 配置文件（S0 允许 `.github/`，但 CI 可跑性属 STAGE-02）

## Scope forbidden（禁区，违者拒收）

- `.dumate/`、`.workbuddy/` —— 队友工作区，**不删除、不修改、不提交**
- `D:\SuperMa-LeftRight\` —— 只读，禁止任何写操作
- 不引入 Android/AGP 依赖（S0 为纯 JVM 模块，:app 脚手架归 S1）
- 不引入 Flutter/KMP/RN；不做注册登录云同步任务市场（总军令红线）
- 不写任何执行器/录制/OCR 功能代码（那是 S1–S3 的活）
- 无 fake success：测试不许空断言、不许 skip 后报绿

## Required visible workers

- `Anytouch | STAGE-01 | 契约移植`（本军令下发后立即开）
- `Anytouch | STAGE-02 | Mock闭环与CI`（01 验收通过后由主窗开）

## STAGE-01 任务书（派给可见 worker）

1. `core/contracts/src/main/kotlin/com/anytouch/contracts/`：六契约 data class + 枚举（含 `StopReason.code` 常量化、`Action.safety` 三字段、`Action.source` 定位来源枚举：node/ocr/vlm/cache）
2. kotlinx.serialization 全量注解；JSON 往返测试：每条契约 ≥1 个 roundTrip 用例 + 1 个左舵真实样例 JSON 反序列化用例（样例从 `module_contracts.md` 手工誊抄为测试资源，**运行时不读 SuperMa 目录**）
3. 交付产物（落盘，聊天回复不作数）：
   - 代码：上述模块
   - `docs/ANYTOUCH-S0-STAGE-01-report.md`：改动清单 / 执行命令 / 测试结果 / 风险（Evidence Minimum 格式，参照 `D:\SuperMa-LeftRight\AGENTS.md`）
   - `evidence/S0/stage-01/`：`gradlew :core:contracts:build` 与 `:test` 的完整输出文本
4. 硬验收门槛（主窗复核标准）：
   - `gradlew :core:contracts:build` 退出码 0，无 warning-as-error 之外的失败
   - 六类契约 roundTrip 测试 6/6 绿
   - 字段与 `module_contracts.md` 逐一对得上（主窗逐字段 diff）

## Retry / 留痕规则

- worker 初始化失败或产物不过门槛 → 主窗记 `orders/METRICS.md` 一行（类别：scope越界/门槛不过/虚报/初始化失败），重开 `STAGE-01-Retry-01`，证据不删。
- 交付被吸收后 worker 归档（Qoder 侧归档由用户在 UI 操作，主窗只提示）。

## S0 完成定义（DoD）

`git log` 含骨架 commit + STAGE-01 commit（+STAGE-02 commit）；全仓 `gradlew build` 绿；本文件顶部 Objective 达成。达成后主窗写 `docs/ANYTOUCH-S0-final-report.md` 并向总军令 Owner（用户）报告，等用户终审指令后再开 S1。
