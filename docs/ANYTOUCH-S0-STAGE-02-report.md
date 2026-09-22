# Anytouch S0 | STAGE-02 报告：Mock 闭环与 CI

- Order: ANYTOUCH-S0-ORDER.md（上位），本 STAGE 派工：Mock 闭环 + 本地可验 CI
- Owner（worker）：Anytouch | STAGE-02 | Mock闭环与CI
- 验收方：主窗（Qoder 施工总窗）
- 日期：2026-09-22
- 格式：Evidence Minimum（改了什么 / 命令 / 测试结果 / 风险遗留）

## 1. 改了什么（全部为新增文件，未改动根 Gradle 配置与 contracts 现有源文件）

### A. Mock 闭环（均在 :core:contracts 内，无新增 Gradle 模块、无新依赖）

main 侧新增包 `com.anytouch.pipeline`：

| 文件 | 作用 |
| --- | --- |
| `core/contracts/src/main/kotlin/com/anytouch/pipeline/ExecutorBackend.kt` | 执行器后端极简接口 `act(Action): ActionResult`，S1 扩展位 |
| `.../pipeline/PipelineStopCode.kt` | 只增补充停止码 `SAFETY_GATE_BLOCKED`、`INVALID_INPUT`（禁区不改 Constants.kt，故新建；severity 复用 contracts 的 StopSeverity） |
| `.../pipeline/MockBackend.kt` | 三门禁判定实现：`viewport_ok`/`click_enabled` 任一 false → `ok=false` 的 ActionResult，`recovery` 携带 `STOP` 级 `SAFETY_GATE_BLOCKED`；通过则回显 `landed=target`；纯内存，零真实系统 API |
| `.../pipeline/ClosedLoop.kt` | 闭环驱动器：解析 JSON 指令数组（`List<Action>`）→ 逐个过 `ExecutorBackend` → 输出 `List<ActionResult>` JSON；遇 `STOP` 级 `StopReason` 立即中断并产出 `Command` 风格停止回执；非法 JSON 抛 `IllegalArgumentException`（脏输入报失败，不假绿） |

test 侧新增：

| 文件 | 作用 |
| --- | --- |
| `core/contracts/src/test/kotlin/com/anytouch/pipeline/PipelineTest.kt` | 6 个 JUnit5 用例（指令 JSON 全手写，同时钉死 SerialName 解码）|
| `core/contracts/src/test/kotlin/com/anytouch/contracts/PipelineFixtures.kt` | Action 构造助手（仅 test 源码集） |

PipelineTest 用例清单（≥4 要求，实做 6）：
1. 正常链：3 动作全成功、landed 回显正确、`requires_transition` 仅标记不拦截
2. viewport 门禁拦截链：第 2 动作 `viewport_ok=false` 触发 STOP，第 3 动作不执行，产出停止回执（Command，含 `SAFETY_GATE_BLOCKED`）
3. click_enabled 门禁拦截链：被拦动作 `landed=null`，不回显落点
4. 空输入 `[]`：空结果、不停止
5. 脏输入 4 类（截断 JSON / 缺必填字段 / 顶层非数组 / 字段类型错）均 `assertFailsWith<IllegalArgumentException>`
6. MockBackend 直测：门禁 + 回显

### B. CI（本地可验部分）

| 文件 | 作用 |
| --- | --- |
| `.github/workflows/ci.yml` | push/PR 触发，JDK17 temurin，`./gradlew build`；注释标明依赖走阿里云镜像源、无 secrets、远程可跑性属预期遗留 |
| `scripts/ci-local.sh` | 依次：`:core:contracts:build` → `:test --rerun-tasks` → 红线 grep（A：core 内 .kt 无网络调用关键字 http/java.net/Socket/upload 等；B：受管源文件无队友工作区目录引用），任一步失败 `exit 1`；`set -euo pipefail`；首行 shebang，已 `chmod +x` |

## 2. 执行命令

```bash
# 均从仓库根目录 D:\Anytouch（Git Bash）
./gradlew :core:contracts:build --rerun-tasks
./gradlew :core:contracts:test --rerun-tasks
chmod +x scripts/ci-local.sh
./scripts/ci-local.sh   # 实跑通过
```

## 3. 测试结果

- `:core:contracts:test --rerun-tasks`：**19/19 全绿**
  - ContractsTest 13（STAGE-01 契约用例，回归未破坏）
  - PipelineTest 6（本次新增）
- `./scripts/ci-local.sh`：退出码 **0**，红线 A/B 均 clean
- 证据文件（各含完整输出与结束行 `EXIT CODE: 0`）：
  - `evidence/S0/stage-02/build-output.txt`
  - `evidence/S0/stage-02/test-output.txt`
  - `evidence/S0/stage-02/ci-local-output.txt`

## 4. 风险与遗留

- **远程 CI 未验证**：本任务无 GitHub remote，`.github/workflows/ci.yml` 的云端可跑性未实测，属预期遗留；主窗接入远程后需跑一次确认。
- **镜像源依赖**：build 依赖 settings.gradle.kts 的阿里云镜像源（未在红线 grep 扫描范围，属网络配置非源码调用）；离线环境构建会失败。
- **红线 B 自匹配处理**：`ci-local.sh` 内队友工作区关键字用 `$(echo ..)` 拆分拼接以避免脚本 grep 命中自身模式；展开后仍是完整关键字匹配，逻辑正确，已在实跑验证。
- **停止码扩展位**：`SAFETY_GATE_BLOCKED`/`INVALID_INPUT` 放在新建 `PipelineStopCode`（禁区约束下只增不改），与 contracts `StopCode` 并存；S1 若统一停止码需评估合并。
- **未触碰项**：无 Android/无障碍/OCR；无 git commit/push（集成权在主窗）；队友工作区目录未访问；根 Gradle 配置与 contracts 现有源文件零改动。

## 5. 完成定义自检

- [x] 代码 + 测试落盘
- [x] 报告落盘（本文件）
- [x] evidence/S0/stage-02 三份完整输出（含结束行）
- [x] ci-local.sh 实跑退出码 0
- [ ] 主窗亲跑 :test 全绿 + 逐文件读码 + ci-local.sh 复核（留给主窗）
