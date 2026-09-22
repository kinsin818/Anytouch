# Anytouch S0 STAGE-01 报告：契约移植（Kotlin）

Order: ANYTOUCH-S0-GITBASE-20260922 / STAGE-01
Worker: Anytouch | STAGE-01 | 契约移植
Date: 2026-09-22
契约唯一事实源（只读）: `D:\SuperMa-LeftRight\docs\module_contracts.md`

## Goal

在 `core/contracts`（包 `com.anytouch.contracts`）内将六类 JSON 契约（Observation / ActionPlan / Action / ActionResult / Command / StopReason）1:1 Kotlin 化，kotlinx.serialization 注解 + JUnit5 往返与真实样例反序列化测试全绿。

## Files changed（全部新增）

- `core/contracts/src/main/kotlin/com/anytouch/contracts/Contracts.kt` —— 六契约 data class + `Point` / `ActionSafety` / `ObservationEvidence` 子结构
- `core/contracts/src/main/kotlin/com/anytouch/contracts/Constants.kt` —— `ContractJson`（统一 Json 配置）、`ActionType`、`ActionSource`、`StopCode`、`StopSeverity` 常量对象
- `core/contracts/src/test/kotlin/com/anytouch/contracts/ContractsTest.kt` —— 13 个用例：六契约各 1 roundTrip + 1 文档样例反序列化，外加 1 个缺省语义/未知字段策略用例
- `docs/ANYTOUCH-S0-STAGE-01-report.md`（本文件）
- `evidence/S0/stage-01/build-output.txt`、`evidence/S0/stage-01/test-output.txt`

## Files intentionally not changed

- 根 Gradle 配置（`settings.gradle.kts` / 根与模块 `build.gradle.kts` / `gradle.properties`）—— 已冻结，未动一字；所需依赖 kotlinx-serialization-json 1.7.3 + JUnit5 5.11.4 骨架中已备，无需新增
- `D:\SuperMa-LeftRight\` —— 全程只读，样例手工誊抄进测试，运行时零读取
- `.dumate/`、`.workbuddy/`、`app/` —— 未触碰
- 未 git commit / push（集成权在主窗）

## 移植决策记录（主窗 diff 时请核对）

1. **snake_case 映射**：JSON 字段 `page_id`/`plan_id`/`action_id`/`command_id`/`page_type`/`stop_reason`/`fallback_policy`/`viewport_ok`/`click_enabled`/`requires_transition`/`training_bank` 以 `@SerialName` 映射到 camelCase Kotlin 属性，字段集合与样例 1:1，无增删改名。
2. **未定义结构的字段透传为 `JsonObject`**：`fallback_policy`、`details`、`payload`、`StopReason.evidence`、`ObservationEvidence.*`（六路证据源）、`Action.value`、`Observation.questions/buttons`（元素结构文档未定义）。理由：契约样例中为空对象/空数组，1:1 移植不允许发明内部结构。
3. **未知字段策略 = 忽略**：`ContractJson.instance` 设 `ignoreUnknownKeys = true`，由测试 `defaultSemanticsAndUnknownFields` 实证；本条即"文档记录"。
4. **缺省语义用默认值**：空数组→`emptyList()`；空对象→`emptyJsonObject()`；空串→`""`；可空对象（`target`/`value`/证据源）→`null`；`ActionSafety` 三门禁缺省全 `false`（未显式放行不可执行）。
5. **`Action.type`/`Action.source`/`StopReason.code`/`severity` 用字符串+常量对象**（非严格枚举，留扩展位）：`ActionType`（含样例 `select_dropdown`）、`ActionSource`（node/ocr/vlm/cache/template/vision，含样例 `vision`）、`StopCode`（含样例 `SELECT_RETRY_EXHAUSTED`）、`StopSeverity`（样例 `STOP`）。常量取值为参照项目常见值的预置集，非契约强制，可扩展。
6. **`ActionResult.recovery` 建模为可空 `StopReason`**：样例值 `null`；语义为恢复时引用的停止原因。若主窗认为应保持 `JsonElement?` 透传可再议，此为唯一一处带解释的建模选择。
7. **`Command.source`（`youshu_ui`）与 `ActionResult.backend`（`interception`）** 保持 String，未常量化——它们是运行时部署侧标识，不属定位链/停止原因语义。

## Commands run

```bash
# 于 D:\Anytouch
./gradlew :core:contracts:build                    # 首轮编译排错（2 次失败：reified 泛型 / import 缺失）
./gradlew :core:contracts:clean :core:contracts:build   # 最终全量构建 → evidence/S0/stage-01/build-output.txt
./gradlew :core:contracts:test --rerun-tasks       # 强制重跑测试 → evidence/S0/stage-01/test-output.txt
```

## Test results

- 构建：`BUILD SUCCESSFUL`（exit 0，零 warning——两份证据文件 grep `warning|FAILED` 无匹配）
- 测试：**13/13 PASSED，0 skipped，0 failed**（明细见 `evidence/S0/stage-01/test-output.txt`）：
  - Observation: `observationRoundTrip` / `observationSampleFromContractsDoc`
  - ActionPlan: `actionPlanRoundTrip`（嵌套 Action）/ `actionPlanSampleFromContractsDoc`
  - Action: `actionRoundTrip` / `actionSampleFromContractsDoc`
  - ActionResult: `actionResultRoundTrip`（含 recovery=null 分支）/ `actionResultSampleFromContractsDoc`
  - Command: `commandRoundTrip` / `commandSampleFromContractsDoc`
  - StopReason: `stopReasonRoundTrip` / `stopReasonSampleFromContractsDoc`
  - 附加: `defaultSemanticsAndUnknownFields`
- 每个样例用例均含逐字段实断言 + encode→decode→equals 往返断言，无空断言。

## Redline / security scan result

- 无 SuperMa 写操作；无 .dumate/.workbuddy 触碰；无根 Gradle 改动；无 Android/AGP/Flutter/KMP/RN 依赖；无执行器/OCR 功能代码；无 fake success。

## Known risks / 遗留

1. 决策记录第 6 条 `recovery: StopReason?` 是解释性建模，留给主窗终审（其余字段严格 1:1）。
2. `questions`/`buttons` 元素结构契约未定义，暂透传 `JsonObject`；S1 若定义其结构需在此处收紧类型（属"只增"，不违反 1:1）。
3. `ContractJson.instance` 为测试/生产共用入口；S1 跨进程通信若要求紧凑编码，当前 `prettyPrint=false` 已满足。

## Evidence Packet path

- `D:\Anytouch\evidence\S0\stage-01\build-output.txt`
- `D:\Anytouch\evidence\S0\stage-01\test-output.txt`
