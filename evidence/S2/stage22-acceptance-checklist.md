# STAGE-22 验收清单（主窗开工前落盘，PROTO-lead-gate 第一条）

来源：`orders/ANYTOUCH-S2-ORDER.md` §STAGE-22。逐条抄自军令，验收时对证据打钩；缺项即拒收。
worker：`Anytouch | STAGE-22 | 录制会话状态机`（新会话；旧 STAGE-21 会话已结案，无权再施工）。

## L2 行为断言逐条

| # | 军令条款（字面） | 证据要求（可证伪口径） | 验收方式 | 状态 |
|---|---|---|---|---|
| 1 | 状态机仅 `IDLE→RECORDING⇄PAUSED→STOPPED` 声明的转移合法 | 每条合法转移有正例用例；非法转移全表（每态×每操作）→ 拒绝+归因，不抛异常 | 读码+跑测试 | ☑ |
| 2 | `append(event)` 仅 RECORDING 态受理 | 其余三态各一反例：事件不入缓冲、有拒绝归因 | 读码+跑测试 | ☑ |
| 3 | `maxEvents`（构造参）溢出**拒绝并计数**，禁静默丢 | 溢出用例断言：第 N+1 条被拒、overflow 计数可读、前 N 条完好 | 读码+跑测试 | ☑ |
| 4 | `serialize()` 含 targetPkg+状态+事件序列，任意态可调 | 四态各调一次 serialize 不抛；往返 JSON 等值断言 | 读码+跑测试 | ☑ |
| 5 | `deserialize()` 回 STOPPED 只读取档；`resumeAsRecording()` 可续录 | 断言恢复后态=STOPPED；append 被拒；resume 后 append 受理 | 读码+跑测试 | ☑ |
| 6 | 事件编解码复用 STAGE-21 `RecEvent`，**不得改** RecorderJson 及 recorder 根包 5 文件 | 冻结文件 git diff 零改动；session 包内只 import 引用 | git status/diff | ☑ |
| 7 | deserialize 损坏 JSON → 返回失败类型，不崩 | 至少 2 种损坏形态（截断/非法字段）断言返回失败值而非异常 | 读码+跑测试 | ☑ |
| 8 | 时间戳由注入 clock 控制，测试零真实 sleep | 构造签名含 clock 参数；测试文件 grep `Thread.sleep|delay(` 真实等待 = 0 | 读码+grep | ☑ |
| 9 | 断点续录后 compile 产物与原序列一致（喂 RecorderCompiler 证明） | 金链用例：录制→序列化→恢复→续录→compile，与一次性 compile 逐字段等值 | 读码+跑测试 | ☑ |
| 10 | 断言总数 ≥10 | 测试 XML `tests=` 计数 | 跑测试 | ☑ |

## 机器验收项（主窗独立重跑，不信 worker 转述）

| # | 命令/检查 | 通过线 | 状态 |
|---|---|---|---|
| A | `./gradlew :app:testDebugUnitTest --tests "*recorder.session*" --rerun-tasks` | exit 0、FAILED=0、用例 ≥10 | ☑ |
| B | `grep -rE "import (android\|java\.net\|okhttp)" app/src/main/kotlin/com/anytouch/app/recorder/session/` | 0 行 | ☑ |
| C | `bash scripts/ci-local.sh` | exit 0（红线 A–E 不回归，全量测试不破） | ☑ |
| D | `git status` 越界核对 | 改动仅在 `recorder/session/` 主+test 目录与 `evidence/S2/stage22-*`；冻结 5 文件零改动 | ☑ |
| E | 交付在盘 | `evidence/S2/stage22-test-output.txt`（命令原样输出）+ `stage22-selfcheck.md`（自坑留痕） | ☑ |

## 拒收红线（先写后验）

- 口头交付无磁盘文件 = 不存在（虚报案先例）
- 擅改冻结文件 / 独占目录外写盘 = 拒收
- 恒真填充用例（assertTrue(true) 类）不计入断言数
- 溢出/非法转移静默吞（无归因返回）= 拒收

## 验收结论（主窗，2026-09-22，独立重跑）

**ACCEPTED，一次通过（0 拒收）。** 凭据：`--tests "*recorder.session*"` 主窗重跑 16/16 PASSED、0 FAILED（≥10 达标）；session 包 android/java.net/okhttp import=0；测试目录 Thread.sleep/delay/runBlocking=0；`bash scripts/ci-local.sh` exit 0；`git status` 范围仅独占两目录+stage22 证据两件，冻结件零改动。读码抽查：RecorderSession.kt/SessionCodec.kt 全链 fail-closed、拒绝必带归因、无恒真填充；变异自检 4 轮（含 M3' 打红 8 例）证明用例有牙。worker 自坑留痕含两处真实缺陷（deserialize 未透传 clock、PAUSED 前奏假绿被当场抓出），质量同 STAGE-21 水准。

## 三条严读法裁决（主窗补记，生效为契约）

1. **`resumeAsRecording()` 仅 STOPPED 合法——采纳**。转移图不设平行边，PAUSED 恢复唯一走 `resume()`；真机接线期禁止用续录边绕动态暂停。
2. **存档 `state` 只记录不复活，读档运行态恒 STOPPED——采纳**。字节幂等仅对 IDLE/STOPPED 档承诺；RECORDING/PAUSED 档不承诺往返等值（审计字段语义）。
3. **读档全有或全无——采纳，且定为口径**：会话档任一事件损坏=整档 Failed，不做抢救式部分读档（actionId 按输入下标编号，半截档必致续录偏移，比不可读更坏）。"抢救模式"若 T2 需要=新增能力另发军令，本包不预埋开关。
   附带采纳 D 条时间戳边界："事件自带 ts、会话提供钟"，`append` 不改写调用方时间戳。
