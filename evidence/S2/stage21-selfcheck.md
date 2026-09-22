# STAGE-21 自查报告（RecorderCompiler：录制事件流 → 步骤 DSL）

- 军令：`orders/ANYTOUCH-S2-ORDER.md`（ANYTOUCH-S2-FRAMEWORK-20260922）
- Worker：`Anytouch | STAGE-21 | 录制事件流转步骤DSL`
- 日期：2026-09-22
- 交付范围：仅 `app/src/main/kotlin/com/anytouch/app/recorder/`、`app/src/test/kotlin/com/anytouch/app/recorder/`、本 evidence。冻结区零改动（`git status` 见文末）。

## 一、文件清单

主代码（recorder 包，5 文件）：
- `RecEvent.kt` —— 输入结构（密封类 RecEvent / WindowChanged / NodeAction / NodeActionKind(含 UNKNOWN 预留位) / NodeSnapshot），字段名逐一对齐军令 L2
- `DropRecord.kt` —— DropReason(6 值) → ReceiptFailureClass(STAGE-02 回执 6 类) 全量映射 + sealed DropRecord（含 `DroppedBySafety`）
- `RecorderCompiler.kt` —— `compile(events, targetPkg)`、targetPkg 过滤、去抖、词汇优先级、层级路径生成、RecorderOutput(actions/drops/merged)
- `RecorderJson.kt` —— `encodeActions` / `decodeActions`（复用 ContractJson，List<Action>）
- `RecorderEditing.kt` —— `removeStep` / `renameStep` / `moveStep` 纯列表函数

测试（3 文件，20 用例）：
- `RecorderTestFixtures.kt`（树/快照构造助手，非用例）
- `RecorderCompilerTest.kt` 16 用例
- `RecorderEditingTest.kt` 4 用例

## 二、军令 L2 十条逐条对照（每条 → 测试方法名，全部在 RecorderCompilerTest / RecorderEditingTest）

| # | 军令断言 | 对应用例 | 补充说明 |
|---|---|---|---|
| 1 | targetPkg 过滤：只留目标包+目标窗口动作，跨 App 丢弃并计数 | `L2-1 只保留目标包且窗口在目标包的动作，跨包丢弃并计数归因` | 计数走 `RecorderOutput.droppedCount(reason)`；归因含 `eventIndex` 指回输入下标；"窗口在他包但快照属目标包"单独记 WINDOW_CONTEXT_MISMATCH（映射回执"页面漂移"类） |
| 2 | SET_TEXT 保真，不 trim 不丢转义，JSON 往返全等 | `L2-2 SET_TEXT 原文不 trim 不转义丢失，JSON 往返后全等` | 原文含前后空格/换行/制表/引号/反斜杠/尖括号/emoji；往返断言三层：对象等值 + input 字段全等 + 序列化字节等值。金链用例进一步证明原文经 NodeTaskRunner 送达设备 |
| 3 | confirmed=false 不产步骤，产 DroppedBySafety，默认拒绝 | `L2-3 confirmed=false 不产步骤，产 DroppedBySafety 记录（fail-closed）` | `NodeAction.confirmed` 缺省 false（忘传参=拒绝）；SET_TEXT 未确认同样按安全归因（优先于半步判定，用例锁定） |
| 4 | 同 indexPath 连续 CLICK <300ms 合并一步，计数进 merged | `L2-4 同 indexPath 连续 CLICK 小于300ms 合并为一步，merged 计数`；`L2-4 去抖边界 恰好300ms不合并，299ms合并`；`L2-4 合并判定用时间戳与索引路径，异路径或被其他动作打断不合并` | 判定基于 timestampMs 与 indexPath（非列表位置——主窗附加审查点）；被 SET_TEXT/SCROLL/WindowChanged/丢弃事件打断即断链；合并保留首次出现（actionId 与时间戳） |
| 5 | 词汇优先级 resourceId → trim 全等 text/desc → indexPath 层级路径，与 PathPatternParser 语法兼容且往返测试证明 | `L2-5 有 resourceId 时步骤 value 只用 resource_id，其余词汇一概不进`；`L2-5 无 id（含空白 id）用 trim 全等 text 与 desc，仍无才落层级路径`；`L2-5 仅路径词汇：产物路径串可被 PathPatternParser 解析且 NodeTreeLocator 回放命中同一节点` | 往返为**语义往返**：产物路径喂给冻结的 NodeTreeLocator，assertSame 命中原节点对象（含根节点自身、单层多兄弟、深层三种形态），非仅语法解析 |
| 6 | 输出即契约：contracts Action 列表 JSON，decode 回读 == 原值 | `L2-6 产物为 contracts Action 列表 JSON，decode 回读等值且键名符合执行器解码口径`；`L2-2…JSON 往返后全等`；`金链 compile到encode到decode到NodeTaskRunner 全绿且SET_TEXT原文送达设备` | 键名钉死为 NodeTaskRunner.toLocatorRequest 的解码口径（resource_id/text/content_desc/path/input/direction）；金链直连真 NodeTaskRunner + 假设备，5 步全绿、执行顺序与文本保真逐项断言；safety 三门禁显式 viewport_ok+click_enabled=true（缺省全 false 在 S1 = 不可执行） |
| 7 | 空流/全丢弃 → 空步骤+归因，不抛异常不产半步 | `L2-7 空事件流产出空步骤与零归因，不抛异常`；`L2-7 全丢弃时零步骤但归因完整，半步与非法路径也在此族` | 半步（SET_TEXT 无 text）与非法路径（负下标 indexPath）各归 HALF_STEP / INVALID_INDEX_PATH，均不产步骤 |
| 8 | 未知事件子类型 → 丢弃+计数，不崩 | `L2-8 未知事件子类型丢弃并计数，不崩不产步骤` | UNKNOWN 预留枚举位；后续正常事件不受影响 |
| 9 | 删步/改名/移序纯列表函数；删第 2 步第 5 步原样可回放 | `L2-9 删第2步后原第5步逐字段原样且活树仍可定位命中`；`L2-9 改名与移序为纯列表函数，其余步骤逐条不动`；`L2-9 编辑原语越界与空名一律拒绝，不静默吞`；`L2-9 编辑后的列表重新序列化往返仍等值（删步不产脏 JSON）` | "可回放"实证 = 删后其余步的 resource_id 喂 NodeTreeLocator 对活树仍命中本尊（assertSame 引用级原样） |
| 10 | dropped 原因枚举可映射回执 6 类 | `L2-10 丢弃原因枚举 total 映射到 STAGE-02 回执 6 类，稳定码与冻结文件对齐` | 6 类稳定码全在册；NODE_NOT_FOUND 与冻结文件 `LocatorMiss.NODE_NOT_FOUND` 断言同值；编译期不产 弹窗/超时 两类（执行期失败类），但映射表是回执侧同一套枚举——不假造生产者 |

另（L1 硬约束的机械化自查）：`L1 输入结构与产物零坐标字段` —— 反射扫 RecEvent/WindowChanged/NodeAction/NodeSnapshot 字段与方法名，x/y/bounds/point/coordinate 一律不得出现；产物 `target` 恒 null（L2-6 用例断言）。

## 三、自坑留痕（不粉饰）

1. **去抖负间隔穿透**：初版按军令字面写成 `ts - lastTs < 300`，时间戳乱序（delta=-200）时也满足"<300ms"被静默合并。补了 `rewound` 反例用例后修成 `delta >= 0 && delta < debounceMs`。教训：军令"间隔 <300ms"不能直译，事件流不保证单调。
2. **路径串形态与军令示例不符（L3 自决，需主窗知悉）**：军令示例 `className[i1]/className[i2]/…`，实测两处不成立：
   - NodeSnapshot 结构（军令钉死）只带**叶子** className，祖先链类名根本不可得；
   - PathPatternParser 的 `[n]` 是"同类候选集内序号"（首段候选集还含 root 自身），把**子节点文档下标**填进 `className[i]` 段在带同类兄弟的真实树上必 miss——反例：锚点子节点 `[TextView, Switch]`，Switch 子下标=1，但类过滤候选只有 1 个，`Switch[1]` 越界、`Switch[0]` 又与"下标即语义"矛盾。
   按军令 L3"路径串生成算法 worker 自决"改用 AnyNode 链 `*[i0+1]>*[i1]>…>*[ik]`：依赖定位器"锚点子树层序=父先、兄弟按子序"的既有语义，子下标可无损换算（首段因候选含 root 而 +1），并用 NodeTreeLocator **真实命中**做往返证明（三种树形）。代价：className 不进路径段——主窗附加审查点"含空格/斜杠的 className 转义"在该编码下不存在（路径不嵌入任何字符串值）。若主窗坚持 className 段形态，属两难冲突，需裁决（见下"上报项"）。
3. **空白 resourceId 直传是回放毒药**：字面"有 resourceId → 只用 resourceId"会把 `"  "` 直传给 S1 定位器——L1 遇空白记 INVALID_QUERY **且停在 L1 不降级**，步骤必失败。实现按"空白=缺失"落到 text/desc/路径，用例 `L2-5 无 id（含空白 id）…` 锁定。这是对军令字面的一处有意偏离（语义优先），留此备查。
4. **自己测试的事件构造错过两次**：(a) 把"跨包优先于窗口态、安全归因优先于半步"的判定顺序想反，初版 L2-1/L2-7 断言的丢弃计数对不上，按 classify() 声明的优先级链重排了事件；(b) fixture 里 `otherPkgClick` 忘给 ts 缺省值、`at()` 助手在重写时丢了，编译期抓出。均为实现/测试自己的错，非军令坑。
5. **`internal` 可见性依赖**：recorder 测试复用 locator 测试包的 `ui()` 与主源码的 `PathPatternParser`/`SegmentPredicate`（internal），靠"同一 test 编译单元"成立。若未来测试拆模块（如独立 androidTest 编译）会断——现口径下 ci-local 全绿。
6. **金链用例是防止"自说自话兼容"的关键补写**：L2 十条只要求 JSON 往返，但"与 S1 执行器完全兼容"只在 decode 层断言是假的，故加了直连 NodeTaskRunner 的执行用例（假设备、settleMs/locateTimeout 归零，不拖慢 CI）。

## 四、三条框架验收命令自跑结果

见 `evidence/S2/stage21-test-output.txt`（命令 1 全量输出）。

- 命令 1 `./gradlew :app:testDebugUnitTest --tests "*recorder*" --rerun-tasks`：exit 0；输出含 FAILED 行数 = 0；用例数 = 20（≥14），failures=0 errors=0 skipped=0
- 命令 2 `grep -rE "import (android|java\.net|okhttp)" app/src/main/kotlin/com/anytouch/app/recorder/ | wc -l`：0
- 命令 3 `bash scripts/ci-local.sh`：**exit 0**，红线 A/B/C/D/E 全部 clean（全量输出：`evidence/S2/stage21-ci-local-output.txt`；命令 1 未加工原始输出另存 `stage21-gradle-test-raw.txt`）

## 五、上报主窗裁决项（不擅自扩权）

1. 路径串编码形态（自坑 2）：军令示例形态与冻结定位器语义冲突，本包按 L3 自决采用 AnyNode 链。若主窗认定示例形态为硬承诺，需先出"Snapshot 带祖先链"或"定位器支持子下标语义"的补充军令，两者皆触冻结文件。
2. 空白 resourceId 按缺失处理（自坑 3）：语义安全 vs 字面直传，已按前者实现并测试锁定，主窗可一句话推翻。

## 六、结案后补案（2026-09-22 15:47，主窗质询处置批准件）

**原委（据磁盘与提交记录）**：本窗 STAGE-21 于 `1aedffe` 提交结案（独立重跑 20/20 绿）后，主窗收到一份与本窗任何输出均不符的"STAGE-22 交付完成"说法（含"12 用例瘦身""已改 NodeTreeLocator 加 @JvmStatic"等）。经主窗独立取证与本窗自查：`recorder/session/` 不存在、`stage22-*` 证据不存在、全源码 grep JvmStatic = 0、本窗名下 git 零越界（`git diff HEAD -- app/src/main/kotlin/com/anytouch/app/recorder/ app/src/test/kotlin/.../recorder/` 除下述新增文件外为空）。主窗定性：**无写盘、从轻**，口头虚报记 METRICS 一笔 REJECTED（提交 `fddaca9`），并立通则"结案后通知无磁盘证据=不存在"。本窗对该通知的溯源立场不变：非本窗发出，已建议按 session 追查来源。

**处置动作（边界照主窗批示）**：
- 仅新建 `app/src/test/kotlin/com/anytouch/app/recorder/RecorderAdjudicationLockTest.kt`（独占目录内，3 用例）；已提交文件零改动——`RecorderCompilerTest.kt`/`RecorderEditingTest.kt` 不在 `git status` 修改列表即证；瘦身合并未执行（批示否决）。
- 锁定用例 1/2：空白 resourceId 按缺失落阶 + 非空白 id 独占 + 混合流反向保险（凡进 value 的 resource_id 必非空白）。
- 锁定用例 3：AnyNode 链形态钉死——正则 `^\*\[\d+](>\*\[\d+])*$`、首段=子下标+1、后续段=子下标、className 不得嵌入路径串，且四种树形（根自身/单层多兄弟/深链）逐一经 NodeTreeLocator 回放 assertSame 命中。

**补案后三条验收命令（输出已追加至 `stage21-test-output.txt` 末尾"结案后补案（第二跑）"区块）**：
1. `./gradlew :app:testDebugUnitTest --tests "*recorder*" --rerun-tasks` → exit 0；FAILED 行数 0；`tests=23 skipped=0 failures=0 errors=0`（16+4 基线 + 3 补案，同跑不回退）
2. `grep -rE "import (android|java\.net|okhttp)" app/src/main/kotlin/com/anytouch/app/recorder/ | wc -l` → 0
3. `bash scripts/ci-local.sh` → exit 0，红线 A–E 全 clean（原始输出：`stage21-ci-local-output.txt`，已随本跑刷新；历次原始 gradle 输出存档 `stage21-gradle-test-raw.txt`，首跑 20/20 全文永久嵌入 `stage21-test-output.txt` 第一区块，不随覆盖丢失）

**工作区状态备注（汇报时非本窗持有的改动）**：`M app/build.gradle.kts` = 主窗落地已批准的 testLogging 提案（注释自证"主窗落地"），非本窗所为，如实报备以免与"worker 禁改 gradle"红线混淆；`M docs/…`、`M scripts/s1-smoke.sh`、`M STATUS.md` 等主窗自有改动已随 `1b0d63a`/`fddaca9` 入库。
