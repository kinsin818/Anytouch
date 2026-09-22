# STAGE-22 交付自检（worker 落盘，主窗按此打钩）

- 任务单：`Anytouch | STAGE-22 | 录制会话状态机` ｜ 军令：`orders/ANYTOUCH-S2-ORDER.md` §STAGE-22
- 独占目录：`app/src/main/kotlin/com/anytouch/app/recorder/session/`（2 文件）、`app/src/test/kotlin/com/anytouch/app/recorder/session/`（1 文件）
- 对表清单：`evidence/S2/stage22-acceptance-checklist.md` #1–#10 / 机器项 A–E
- 测试计数：16 用例（清单#10 通过线 ≥10），`failures=0 errors=0 skipped=0`，`assert*` 调用 124 处
- 复核命令（原样）：
  1. `./gradlew :app:testDebugUnitTest --tests "*recorder.session*" --rerun-tasks`
  2. `bash scripts/ci-local.sh`

## 1. 军令 #1–#10 → 用例名映射（用例名为测试 XML 里的字面串）

| # | 军令条款 | 主证用例 | 辅证 |
|---|---|---|---|
| 1 | 状态机仅声明转移合法；非法转移全表（每态×每操作）→拒绝+归因，不抛 | `S22-1a 合法转移逐条正例 IDLE→RECORDING⇄PAUSED→STOPPED` | `S22-1b 非法转移全表 14 组 …`（20 组合 − 6 合法边 = 14 组，逐组断言 Rejected(INVALID_STATE) + detail 点名操作 + 状态不变） |
| 2 | `append(event)` 仅 RECORDING 受理 | `S22-2 append 在 IDLE PAUSED STOPPED 三态均拒 事件不入缓冲且带归因` | 同例含 RECORDING 对照受理组；`S22-5` 内读档态 append 被拒 |
| 3 | `maxEvents` 溢出拒绝并计数，禁静默丢 | `S22-3 溢出 第N加1条被拒且归因OVERFLOW 计数可读 前N条完好` | `S22-3b maxEvents 为 0 …`（下界）；`S22-4c`（计数与被拒副本随存档往返） |
| 4 | serialize 含 targetPkg+状态+事件序列，任意态可调；往返 JSON 等值 | `S22-4 serialize 四态各调一次不抛 含targetPkg与state与事件序列 往返结构等值` | `S22-4b 带事件往返 特殊字符与null字段保真 STOPPED档字节全等`（STOPPED 档再序列化字节全等 + SET_TEXT 原文含 `\n \t " \\ 😀` 不丢） |
| 5 | deserialize 回 STOPPED 只读取档；`resumeAsRecording()` 续录 | `S22-5 读档恒 STOPPED只读 RECORDING或PAUSED档不复活 append被拒 resumeAsRecording后受理` | `S22-5b 续录后的时间戳来自读档时注入的 clock 而非墙钟` |
| 6 | 事件编解码复用冻结 `RecEvent`，未改 recorder 根包 5 文件 | `S22-6 事件结构复用冻结 RecEvent 子类 读档产物仍是同一批类型实例`（断言 `::class.qualifiedName == com.anytouch.app.recorder.WindowChanged` 等） | `git status --porcelain` 无 ` M` 行（见 test-output §C）；session 包只 import 引用 |
| 7 | deserialize 损坏 JSON → 失败类型不崩（≥2 种损坏形态） | `S22-7 损坏或非法存档 8 种形态均返回 CORRUPT_ARCHIVE 不抛异常`（空串/截断/顶层非对象/非 JSON 字面量/targetPkg 类型错/未知事件子类型/indexPath 非数字/版本超前） | 同例含"未知顶层键被容忍"（contracts `ignoreUnknownKeys=true` 冻结口径）；`S22-7b`（读失败不污染在录会话） |
| 8 | 时间戳由注入 clock 控制，测试零真实 sleep | `S22-8 clock 为会话唯一时间源 连续取号单调递增且测试零真实等待` | 构造签名 `RecorderSession(targetPkg, clock: () -> Long = { System.currentTimeMillis() }, maxEvents)`；测试目录 grep `Thread.sleep\|delay(\|runBlocking` = 0 行 |
| 9 | 断点续录后 compile 产物与原序列一致（喂 RecorderCompiler） | `S22-9 金链 断点续录后compile产物与一次性compile逐字段等值` | 录制 head→pause→serialize→deserialize→resumeAsRecording→录 tail；断言 `actions/drops/merged` 三段与一次性基准逐字段等值，并钉 `actions=4 merged=1 drops=2` 防"空对空"假绿 |
| 10 | 断言总数 ≥10 | 16 个 `@Test`（XML `tests="16"`） | 追加 `S22-10 会话只读视图 eventList 返回拷贝 …` 作独立第 16 例 |

## 2. 自坑留痕（自查抓到并修掉的真问题，含实现侧与测试侧）

**坑 1（实现侧真 bug，已修）：`deserialize` 未透传 clock → 续录时间戳跳回墙钟。**
初版 `deserialize(json)` 恢复会话时用缺省 `System.currentTimeMillis()`，调用方注入的假钟在续录那一刻失效——正是"看起来测了 clock，实际读档后就失效"的隐性断链。修法：`deserialize(json, clock)` 参数化并透传到 `SessionCodec.decode`。锁死用例：`S22-5b`（原档 ts=100 不改写，续录 ts=5/10 来自注入假钟）。

**坑 2（实现侧真 bug，编译期暴露）：`clock: () -> Long = System.currentTimeMillis()`。**
Kotlin 里这是"调用一次取 Long 值"当函数类型默认值，`:app:compileDebugKotlin` 报 `Initializer type mismatch: expected 'Function0<Long>', actual 'Long'`。改成 lambda 包裹 `{ System.currentTimeMillis() }`。教训：函数类型默认参数必须写花括号形式。

**坑 3（实现侧，`internal object` 内变量遮蔽 + 键名手滑）：**
`SessionCodec.decode` 里局部 `val events` 遮蔽了 `encode` 的参数名，配合 `val decoded = ArrayList<DecodedEvent>` 又写 `decoded += e`（对 val 重赋值），编译报 `'val' cannot be reassigned`；另一处 `KEY_TEXT_S`/`KEY_TEXT` 混用报 `Unresolved reference`。修法：局部改 `rawEvents/decodedEvents`，删掉 `DecodedEvent` 包装类（直接解成 `RecEvent`），键名统一为 `KEY_TEXT`（快照与 NodeAction 的 `text` 同名但作用域是不同 JsonObject，无冲突）。

**坑 4（测试侧假绿，最危险的一个）：在 IDLE 态调 `pause()`、在 PAUSED 态 append。**
初版四态往返用例写成 `session().also { it.pause() }` ——`pause` 只在 RECORDING 合法，被拒后状态仍是 IDLE，于是"四态各调一次 serialize"实际是 IDLE×4，而往返等值断言全票通过（空对空）。同一形状的错也出现在 PAUSED 档构造：`recording().also{it.pause()}` 之后 append 全被拒，`assertIs<Loaded>` 读回 0 事件。
抓到方式：用例末尾补了 `assertEquals(listOf("IDLE","RECORDING","PAUSED","STOPPED"), stateNames)` 与 `assertEquals(listOf(0,1,1,1), …事件数)`；跑出来是 `[IDLE, RECORDING, IDLE, IDLE]` 与 `expected:<2> but was:<0>`，当场暴露。
教训：**状态机测试的前奏必须逐跳断言 Accepted**，否则"非法前奏 + 只断言结果"就是恒真用例。修法：helper 拆成 `recording()`（内部 `assertEquals(Accepted, start())`），构造暂停/停止档一律"先录够事件再转移"，且转移返回值都断言。

**坑 5（覆盖面缺口，自查发现后补齐）：非法转移全表少了 2 组。**
我最初按"12 组"写表，注释里还把账算成"20 − 8"。实算是 4 态 × 5 操作 = 20 组合，合法边 6 条（start@IDLE、pause@RECORDING、resume@PAUSED、stop@RECORDING、stop@PAUSED、resumeAsRecording@STOPPED），非法应为 **14** 组——漏了 `resumeAsRecording@RECORDING`、`resumeAsRecording@PAUSED`。补齐后用例改名为 `S22-1b … 14 组 …`，并加 `assertEquals(14, cases.size)` 与去重集合断言，防止将来删行退化成缺项自满。
验证新行不是死重量：见 §4 变异 M4（把 PAUSED 放进 `resumeAsRecording` 后，恰为 1 例转红）。

**坑 6（预期算错，非实现 bug，留档以免后人误判）：金链首步 `actionId` 不是 `rec-0000`。**
冻结的 `RecorderCompiler` 用**输入事件下标**编 `rec-%04d`，head 首元素是 `WindowChanged`，故首个步骤为 `rec-0001`。已同时断言"续录产物首步 == 一次性基准首步"，把这条口径变成用例约束而非我的口算。

**坑 7（测试侧写法）：`eventList()` 返回 Kotlin 只读 List，`as MutableList` 再 add 抛 `UnsupportedOperationException`。**
初版"证明返回拷贝"的做法（强转后改）依赖 stdlib 返回实现，直接翻车。改成行为断言：抓旧视图 → 继续 append → 旧视图 size 不变、`eventList()` size 增长（`S22-10`）。

**坑 8（fakeClock 取号次数算错）：** 以为 `appendWindowChanged` 会复用上一次 `nextTimestampMs()` 的值，实际它内部自取一次 clock，故序列是 7（我显式取号）→ 14（append 内部）→ 21。`S22-8` 按真实语义钉死，并在 §3-D 记录"每次 appendWindowChanged 消耗一次 clock"这条对外语义。

## 3. 契约二义与决策留痕（未擅改任何冻结文件，请主窗过目）

**A. `resumeAsRecording()` 的作用域（二义，已按严口径实现）**
军令原文把 `resumeAsRecording()` 与"deserialize 得 STOPPED 只读取档"绑在一起写，未说 PAUSED/RECORDING 能否调。我实现为**仅 STOPPED 合法**：PAUSED 走 `resume()`（不跨过 RECORDING 语义），RECORDING 调用视为误用直接拒绝。理由：若允许 PAUSED→resumeAsRecording，等价于给 `resume` 开第二条别名，转移图的"⇄"就出现两条平行边，T2 真机接线时容易两头写状态。反例已锁 `S22-1b` 的两行（`resumeAsRecording@PAUSED`、`@RECORDING`）。若主窗要放宽，改 `RecorderSession.resumeAsRecording` 一行 + 删该两行用例即可。

**B. 存档里的 `state` 字段语义（明确为"只记录不复活"）**
军令同时要求"serialize 含状态"与"deserialize 后回到 STOPPED"。我的读法：文件里的 `state` 是**审计/人读字段**（记录停笔瞬间的真实态），运行态恒被强制为 STOPPED。副作用：RECORDING/PAUSED 档 serialize→deserialize→serialize 不字节等等（第二次写出的是 STOPPED）。为此 `S22-4` 只对 IDLE/STOPPED 两态断言字节全等，`S22-4b` 专门在 STOPPED 态断字节全等（幂等）。变异 M3'（读档不强制 STOPPED）会同时打红 8 例，说明该口径已被用例咬住。

**C. 读档 fail-closed 强度高于编译器（加严，非放宽）**
`RecorderCompiler` 对未知事件子类型是"丢弃+计数不崩"（军令 L2-8）。会话读档我选**全有或全无**：任一事件结构非法/子类型不认识（如 `"kind":"TELEPORT"`）→ 整档 `Failed(CORRUPT_ARCHIVE)`，不做"能读几条读几条"。理由：会话缓冲是"录制保真"载体，部分读档会让续录产物与原序列静默错位（compile 的 `actionId` 按输入下标编号，丢一条全串偏移），比丢步骤更坏；且真机侧（T2）拿到的是"档不可信"的明确信号而非半截数据。代价：损坏档无法抢救任何事件。若主窗要"抢救模式"，属新增能力（另发军令），我没有预埋开关。
未知顶层键例外：按 contracts 冻结配置 `ignoreUnknownKeys = true` 忽略（向前兼容），已用 `S22-7` 末段锁定。

**D. 时间戳职责边界**
`append(event)` **不改写**调用方给的 `timestampMs`（冻结 `RecEvent` 字段自带 ts，去抖依赖它），会话只在 `appendWindowChanged` 便捷口和 `nextTimestampMs()` 上提供注入 clock 取号。即"事件自带 ts，会话提供钟"，二者不重复计时。

**E. 无军令-冻结代码冲突上报项**
本期未发现与 recorder 根包 5 文件 / `core/contracts` 的语义冲突（不同于 STAGE-21 的两条）。序列化未复用 `RecorderJson`（它是 `List<Action>` 编解码，与本包无关），只借其"手工 JsonObject + ContractJson"做法，冻结件零改动。

## 4. 变异自检（证明用例不是死重量；每轮改完即 `cp` 还原，最终源码与变异前 diff 一致）

| 轮 | 变异（改坏什么） | 结果 | 判读 |
|---|---|---|---|
| M1 | `append` 态闸弱化为"仅 IDLE 拒"（PAUSED/STOPPED 也收） | 16 例 → **4 failed**（S22-2 / S22-3 / S22-5 / S22-9） | 清单#2 与金链同时咬住 |
| M2 | 溢出分支改 `if (false)`（静默收下、不计数） | 16 例 → **3 failed**（S22-3 / S22-3b / S22-4c） | 清单#3 禁静默丢有牙 |
| M3' | 读档落到 `RECORDING`（不强制 STOPPED 只读） | 16 例 → **8 failed** | 清单#5 是全链前置条件，牵一发动全身（另注：首版 M3 用未存在标识符 `state`，编译期即失败，不算用例判红，故换 M3'） |
| M4 | `resumeAsRecording` 额外允许 PAUSED 偷跑 | 16 例 → **1 failed**（S22-1b） | 证明 §2 坑 5 补的两行不是死行 |
| 还原后 | `diff /tmp/RS.bak* 现文件` 无差异，命令 A/B 复跑 | exit 0 / 16 PASSED，`ci-local` PASS | 最终态即交付态 |

## 5. 交付文件清单（绝对路径）

- `D:\Anytouch\app\src\main\kotlin\com\anytouch\app\recorder\session\RecorderSession.kt`（状态机 + SessionState/SessionOutcome/SessionRestore/RejectionReason）
- `D:\Anytouch\app\src\main\kotlin\com\anytouch\app\recorder\session\SessionCodec.kt`（`internal object` 手工 JSON 编解码 + `SessionSnapshot`）
- `D:\Anytouch\app\src\test\kotlin\com\anytouch\app\recorder\session\RecorderSessionTest.kt`（16 用例，包名含 `.recorder.session`）
- `D:\Anytouch\evidence\S2\stage22-test-output.txt`（两条命令原样输出 + XML 计数 + 红线/范围自查）
- `D:\Anytouch\evidence\S2\stage22-selfcheck.md`（本文件）

集成提示（主窗窄口，我不做）：本包零引用者，可安全作为 T2 实测层的会话底座；若接入，事件源只需按 `append(RecEvent)` + `nextTimestampMs()` 组事件，落盘走 `serialize()`/`deserialize(json, clock)`。
