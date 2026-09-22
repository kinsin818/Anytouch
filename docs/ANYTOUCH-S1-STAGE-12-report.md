# Anytouch | STAGE-12 | 安全阀与全局停止 —— 交付报告

日期：2026-09-22 ｜ 上位军令：ANYTOUCH-S1-EXECUTOR-20260922（L2-STAGE-12）
结论：**DONE**。`:app:testDebugUnitTest --tests "com.anytouch.app.safety.*" --rerun` 实跑 22/22 绿（零 fail / 零 error / 零 skip），证据见 `evidence/S1/stage-12/test-output.txt`。零新增依赖、零冻结文件改动、未 commit。

## 1. 交付文件（全部在 STAGE-12 领地内）

主代码 `app/src/main/kotlin/com/anytouch/app/safety/`：
- `NodeTextProbe.kt` —— 窄输入接口（resourceId/text/contentDesc 三字段）+ `SimpleNodeProbe`。不依赖 STAGE-11 的 UiNode，不触碰 Android 类。
- `HighRiskRule.kt` —— 七类 `HighRiskCategory`（声明序即主判优先级）、`HighRiskRule`、`HighRiskRuleSource` 装载口、`HardcodedHighRiskRules` 硬编码中英双语词表。
- `HighRiskMatcher.kt` —— 三态判定 `Clear / RequiresSecondConfirm(matchedRule, allMatches) / Denied(reason)`。
- `KillSwitch.kt` —— 全局停止单例（`MutableStateFlow<KillSignal?>`），stop 幂等保首信号、reset 复位。
- `SafetyQueueRunner.kt` —— 队列执行器：每步前查 kill、过安全阀，产出 Command 风格 `StopReceipt`。

测试 `app/src/test/kotlin/com/anytouch/app/safety/`：`HighRiskMatcherTest`(12) + `KillSwitchTest`(4) + `SafetyQueueRunnerTest`(6)。

## 2. 判定设计（军令点名留痕项）

- **匹配法**：三字段逐一"小写化子串匹配"。选子串而非词边界正则的原因：resource-id 普遍是 snake_case，`_` 在 `\b` 语义内算单词字符，词边界法会漏掉 `com.x:id/pay_confirm_button` 这类真实命中。为压误伤，英文词表刻意剔除短歧义词（drop/pin 等不收），只留 pay/transfer/delete/password/checkout 级低误伤词。
- **边界例①（"发送键盘"）**：text=`发送键盘` 命中 SEND 类，断言 allMatches 中**不含** PAYMENT——词表按类独立，"发送"绝不牵连"支付"。
- **边界例②（"已支付金额"）**：纯展示文本同样标高危（PAYMENT）。设计立场：执行器无法从文本可靠区分"展示"与"操作"，误伤的代价只是多一次二次确认，漏放的代价是花钱/删数，故**宁可过判**，不做展示白名单猜测。
- **多类同中**（如 text=`发送` + id=`delete_pay`）：不裁决互斥，主判 `matchedRule` 取类别声明序最高者（PAYMENT>TRANSFER>SEND>DELETE>PASSWORD>PURCHASE>CONFIRM_ORDER），全量命中留在 `allMatches` 供回执卡/归因。
- **默认拒绝（红线 3）**：装载抛异常（RULES_LOAD_FAILED）、装载空表（RULES_EMPTY，空词表视为坏装载）、未初始化（NOT_INITIALIZED）三态下，`inspect` 对**任何**节点（含普通"WLAN"）返回 `Denied`，判定路径零异常抛出。测试逐态覆盖。

## 3. KillSwitch 与停止回执语义

- 任意 step 前查询 `KillSwitch.snapshot()`；STOP 后队列即刻中止，剩余步不执行。
- 停止回执对齐 STAGE-02 ClosedLoop 的 Command 风格：`commandId="kill-switch-stop-<stepId>-<index>"、type="stop"、source、payload(step_index/step_id/stop_code=USER_STOP/stop_reason)、confirm="stopped"`；门禁中止则为 `source="safety_gate"、stop_code=PipelineStopCode.SAFETY_GATE_BLOCKED`（只读引用 pipeline 常量，未改动该文件）。
- 设计取向：**门禁不过 = 中止整条队列**而非跳过继续——高危上下文里剩余步骤同样可疑；`confirmer` 缺省 `{ false }`（fail-closed，无人确认即中止）。

## 4. 坑自纠留痕

1. **Command 构造陷阱（本阶段最大坑）**：`:core:contracts` 对 kotlinx-serialization-json 是 `implementation` 依赖，`JsonObject` 类型不暴露给 `:app`；在 :app 直接构造 `Command(payload=buildJsonObject{...})` 编译不过，补依赖又违反"零新增依赖"白名单。处置：`StopReceipt` 以纯 Kotlin 保持与 Command 完全同字段形状（payload 用 `Map<String,String>`），主窗收口时可在窄口适配层无损映射回 Command。**未动任何 Gradle 配置**。
2. **并行 worker 编译阻塞**：过程中 `:app:compileDebugKotlin` 一度被 STAGE-11 的 `locator/NodeTreeLocator.kt` 编译错误（`isEmpty` 未解析等 4 条）整体带崩。领地纪律不动 locator，改用 gradle 缓存里的 `kotlin-compiler-embeddable` 对 safety 主/测代码做独立编译+反射跑测自检（22/22），随后 STAGE-11 修复、官方路径复跑同样 22/22。两口径互为印证。
3. **`kotlin.test.assertIs` 参数序**：`(value, message)`，按 JUnit `assertEquals(message, value)` 的直觉写反导致 3 处编译错，已纠正。
4. **证据强度**：gradle 二次跑会整链 UP-TO-DATE，"BUILD SUCCESSFUL" 未必是真跑。最终证据用 `--rerun` 从零执行，并在文件头注明 PASSED 明细取自该次实跑生成的 JUnit XML（:app 未配 testLogging，stdout 无逐用例行；改 build 文件属禁区，未动）。
5. 控制台中文在 GBK 代码页下乱码，仅影响本窗观察，不影响落盘文件（均 UTF-8）。

## 5. 验收口径（主窗复跑）

```
cd /d/Anytouch
./gradlew :app:testDebugUnitTest --tests "com.anytouch.app.safety.*" --rerun
```
期望：BUILD SUCCESSFUL；`app/build/test-results/testDebugUnitTest/TEST-com.anytouch.app.safety.*.xml` 三个 suite 共 22 tests、failures=0、errors=0。

## 6. 越界自查

git status 显示本 worker 新增仅 `app/src/{main,test}/kotlin/com/anytouch/app/safety/` 与本报告、`evidence/S1/stage-12/`；manifest/AppState/Service/MainActivity/全 Gradle/core 契约与 pipeline/locator 目录零改动；`.dumate/.workbuddy` 未触碰；未执行 git commit。
