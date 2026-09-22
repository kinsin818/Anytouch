# STAGE-21 验收清单（主窗开工前抄自军令，逐条对证据打钩）

军令：`orders/ANYTOUCH-S2-ORDER.md`（ANYTOUCH-S2-FRAMEWORK-20260922）L2 十条 + 三条机械验收。
判定规则：缺证据即拒收；任何"改判定/跳测试/放宽期望"= fake success 级拒收。

| # | 军令断言 | 证据要求（测试方法名/代码位置） | 主窗复核方式 | 结果 |
|---|---|---|---|---|
| 1 | 跨包事件丢弃+计数（targetPkg 过滤） | selfcheck 对照表 → 用例名 | 读码+跑测试 | ☑ |
| 2 | SET_TEXT 保真，JSON 往返全等 | 同上 | 读码+跑测试 | ☑ |
| 3 | confirmed=false 高危 → DroppedBySafety，不产步骤 | 同上 | 读码+跑测试 | ☑ |
| 4 | 同路径 CLICK <300ms 去抖合并，merged 计数 | 同上 | 读码+跑测试 | ☑ |
| 5 | 词汇优先级 id>text/desc>indexPath 路径串，与 PathPatternParser 兼容 | 同上 | 读码+往返测试 | ☑ |
| 6 | 产物=contracts Action JSON，decode 回读等值 | 同上 | 读码+跑测试 | ☑ |
| 7 | 空流/全丢弃 → 空步骤+归因，不抛异常 | 同上 | 读码+跑测试 | ☑ |
| 8 | 未知事件子类型 → 丢弃+计数，不崩 | 同上 | 读码+跑测试 | ☑ |
| 9 | 删步/改名/移序纯列表函数；删第2步第5步原样可回放 | 同上 | 读码+跑测试 | ☑ |
| 10 | dropped 原因枚举可映射回执 6 类 | 同上 | 读码 | ☑ |
| A | `:app:testDebugUnitTest --tests "*recorder*"` 0 FAILED 且 ≥14 用例 | stage21-test-output.txt | 主窗独立重跑 | ☑ |
| B | recorder 包 `import android/java.net/okhttp` grep = 0 行 | 证据文件 | 主窗独立 grep | ☑ |
| C | `bash scripts/ci-local.sh` exit 0（红线 A–E 不回归） | selfcheck 自跑段 | 主窗独立重跑 | ☑ |
| D | 冻结文件零改动 | — | `git status`/`git diff` 逐文件核对 | ☑ |
| E | 交付物在盘：代码+test-output.txt+selfcheck.md | evidence/S2/ | ls+读全文 | ☑ |

附加审查点（非军令但属主窗判断，拒收需引用条款）：用例是否真断言（无恒真）、去抖是否用 timestamp 而非列表位置、路径串转义（含空格/斜杠的 className）。

## 验收结论（主窗，2026-09-22）

**ACCEPTED，一次通过（0 拒收）。** 独立重跑：`--tests "*recorder*"` exit 0、16+4=20 用例 0 failures；grep import android/java.net/okhttp = 0；ci-local PASS；`git status` 证明冻结区零改动。worker 上报两项军令冲突均已裁决并补记入 `orders/ANYTOUCH-S2-ORDER.md`"主窗裁决补记"。自坑留痕 6 条质量高（去抖负间隔穿透为真实缺陷、自纠后补反例用例）。

## 补案 addendum（结案后，2026-09-22，主窗亲验）

- worker 结案后补交 `RecorderAdjudicationLockTest.kt`（3 用例，锁两条主窗裁决：空白 resourceId 按缺失、AnyNode 路径链形态）——**逐行读码核实为真断言**（含 assertSame 走真 NodeTreeLocator 回放 4 种 indexPath 形态），非恒真填充。
- 主窗独立重跑 `--tests "*recorder*"`：RecorderAdjudicationLockTest 3 + RecorderCompilerTest 16 + RecorderEditingTest 4 = **23/23，0 failures**（worker 通知里的"22（16+4+2）"计数仍不实，以磁盘 XML 为准）。
- 该补充件与同批落盘的 `app/build.gradle.kts` testLogging、MainActivity 自目标改造（testTag/keep_fg）一并由主窗集成 commit。
- 同轮设备实测发现 **ACTION_SET_TEXT 虚报**（performAction=true 但不落字），主窗已在 NodeTaskRunner 加落字复核并锁回归；详见 `stage21-device-action-coverage.md`。
