# ANYTOUCH S2-框架军令：录制事件流 → 步骤 DSL 转换器（纯 JVM，无门禁部分）

Order ID: ANYTOUCH-S2-FRAMEWORK-20260922
Owner: Qoder 施工窗 ｜ 上位：总军令 S2（录制模式）+ `RULINGS-20260922.md` P0-1/P0-2 + `PROTO-lead-gate.md`
门禁状态：**本军令只含框架层**（纯 JVM 可测，不需 T2 模型 Key、不需真机）；S2 实测层（真机事件采集+录放闭环）**锁 T2**，届时另发军令且验收必含上设备证据。

## 定义钉死（消除总军令歧义，S3/S4 引用同此）

- "执行期零模型调用" = 执行任务时**零网络大模型调用、零 VLM、零 OCR**；录制全链路（采集→转步→回放）恒零模型。端侧 OCR 属 S3 兜底件，另行门票，不与本条混谈。
- 录制/导出在免费版常开（P0-2 冻结共识）；步数上限属业务门控，不在转换器内实现——转换器只负责"事件→步骤"的保真。

## L0 目标（一句话）

`RecorderCompiler`：把一段无障碍操作事件流（结构定义见 L2）编译成与 S1 执行器完全兼容的任务步骤 JSON（复用 `core/contracts` 序列化），全程纯 Kotlin、零 Android 依赖、可 JVM 单测。

## L1 承诺点（worker 不得偏离）

- 冻结文件（同 S1 清单，追加）：`core/contracts` 全部、`locator` 包（`LocatorRequest` 的 value 对象是本军令的**输出词汇表**，只读引用）、`safety` 包、全部 Gradle、`app/src/main/kotlin/com/anytouch/app/` 下非 recorder 文件
- STAGE-21 独占目录：`app/src/main/kotlin/com/anytouch/app/recorder/` + `app/src/test/kotlin/com/anytouch/app/recorder/`
- 依赖白名单：**零新增依赖**；recorder 包源码 `import android.*` 与 `java.net/HttpURLConnection/OkHttp` 必须为零（CI 红线 grep 判）
- 禁止坐标进入事件流结构：`RecEvent` 不含任何坐标字段（x/y/bounds 均不得出现）——定位词汇只走节点引用（resource-id/text/contentDescription/层级路径）

## L2 行为断言（军令即测试用例，全绿才谈验收）

### 输入数据结构（worker 在 recorder 包内定义，字段名以此为准）

```
RecEvent（密封类，均带 timestampMs）:
  WindowChanged(pkg, windowTitle?)
  NodeAction(kind: CLICK|SET_TEXT|SCROLL_FORWARD|SCROLL_BACKWARD,
             snapshot: NodeSnapshot, text: String? /*仅SET_TEXT*/, confirmed: Boolean /*高危二次确认结果*/)
NodeSnapshot: resourceId?, text?, contentDesc?, className?, pkg, indexPath: List<Int> /*父链文档序*/
```

### 转换断言（≥14 条 JVM 用例）

1. `compile(events, targetPkg)`：只保留 `snapshot.pkg == targetPkg` 且当前窗口在 targetPkg 内的动作；跨 App 事件丢弃并计数
2. SET_TEXT 保真：`text` 原样进入步骤 value，不做 trim/转义丢失（JSON 往返后全等）
3. `confirmed=false` 的高危动作：**不产步骤**，产 `DroppedBySafety(reason)` 记录（默认拒绝语义与 S1 SafetyGate 对齐）
4. 重复点击去抖：同 snapshot 索引路径且间隔 <300ms 的连续 CLICK 合并为一步，计数进 `merged` 字段
5. 定位词汇优先级直传：snapshot 有 resourceId → 步骤 value 只用 resourceId；无 id 有 text/desc → 用 trim 全等文本；只有路径 → `indexPath` 转层级路径串（形如 `className[i1]/className[i2]/…`，与 `PathPatternParser` 语法兼容，须往返测试证明）
6. 输出即契约：产物为 `core/contracts` 的 Action 列表 JSON，`Json.decode` 回读 == 原值（往返等值用例）
7. 空事件流/全丢弃 → 空步骤 + 归因记录，**不抛异常、不产半步**
8. 未知事件子类型（预留枚举）→ 丢弃+计数，不崩
9. 编辑操作（删步/改名/移序）为纯列表函数：删步后 indexPath 不受影响（步骤间无耦合）——用例证明"删第 2 步，第 5 步原样可回放"
10. 失败归因 6 类字段兼容：dropped 记录的原因枚举能映射 STAGE-02 回执 6 类（未找到节点/页面漂移/弹窗/权限中断/超时/其他）

### 主窗窄口集成（不派工）

- 真无障碍事件源 → RecEvent 适配器、录制开关悬浮球、采集会话持久化——**全部属 T2 后的实测层军令**，本军令不预埋 Android 代码
- 框架层验收命令（机械判定，逐条出证据）：
  1. `./gradlew :app:testDebugUnitTest --tests "*recorder*"` 输出含 `FAILED` 行数 =0 且用例数 ≥14
  2. `grep -rE "import (android|java\.net|okhttp)" app/src/main/kotlin/com/anytouch/app/recorder/ | wc -l` = 0
  3. `bash scripts/ci-local.sh` exit 0（红线 A–E 不回归）

## L3 实现留白

去抖窗口内部常量组织、丢弃记录数据结构、路径串生成算法——worker 自决，L2 断言全绿即可。

## 主窗裁决补记（验收时点 2026-09-22，STAGE-21 上报项）

1. **路径串编码**：军令示例 `className[i]` 形态经核与冻结定位器语义冲突（快照无祖先链类名；`[n]`=同类候选序而非文档下标，带同名兄弟必 miss）。worker 按 L3 留白改用 AnyNode 链 `*[i0+1]>*[i1]>…`（BFS 层序 rank 恒等换算，首段候选含 root 故 +1），并以 NodeTreeLocator 真实命中（assertSame）做三树形往返证明。**采纳**；示例形态不再视为硬承诺。
2. **空白 resourceId 按缺失处理**：字面直传会撞定位器 L1 INVALID_QUERY 且不降级=步骤必失败。按"空白=缺失"落 text/desc/路径，用例锁定。**采纳**，军令 L2-5"resourceId 独占"读作"非空白 resourceId 独占"。

## STAGE-22（随发，纯 JVM）：录制会话状态机 RecorderSession

上位同本军令；不需 T2/T3。**STAGE-21 已验收的 recorder 根包 5 文件一并冻结**（只读引用）。

- 独占目录：`app/src/main/kotlin/com/anytouch/app/recorder/session/` + 对应 test 目录；零新增依赖；`import android/java.net/okhttp` grep=0
- 行为：`IDLE→RECORDING⇄PAUSED→STOPPED` 状态机；`append(event)` 仅 RECORDING 态受理；事件缓冲上限 `maxEvents`（构造参），溢出**拒绝并计数**（禁静默丢）；会话可 `serialize()/deserialize()` 成 JSON（含 targetPkg、状态、事件序列——事件结构复用 STAGE-21 `RecEvent`，编解码可参照 `RecorderJson` 但不得改它）；deserialize 后回到 STOPPED 只读取档，`resumeAsRecording()` 续录
- 断言 ≥10：非法转移全表（每态×每操作→拒绝归因，不抛异常）、溢出计数、序列化 JSON 往返等值、断点续录后 compile 产物与原序列一致（喂 RecorderCompiler 证明）、时间戳由注入的 clock 参数控制（测试无真实 sleep）、serialize 在任意态可调、deserialize 输入损坏 JSON → 返回失败类型不崩
- 复用军令 L1 全部红线；验收三件套同框架层（`--tests "*recorder.session*"` 0 FAILED 且 ≥10 用例 / grep=0 / ci-local exit 0）；交付 evidence/S2/stage22-*.
- worker 标题：`Anytouch | STAGE-22 | 录制会话状态机`
- 冲突处理纪律（本条起为全体 worker 通则）：军令与已冻结代码语义冲突 → 反例+书面上报，主窗裁决；**擅改冻结文件=拒收**（STAGE-21 两条上报为正面范例）

## 交付纪律（PROTO-lead-gate 版）

- 交付物 = 代码 + `evidence/S2/stage21-test-output.txt`（测试命令原样输出）+ 自坑留痕；聊天回复不算交付
- 验收清单：主窗开工前把 L2 十条抄成 checklist 落 `evidence/S2/acceptance-checklist.md`，逐项对证据打钩，缺项即拒收
- 不 commit / 不 push（集成权在主窗）、禁区清单照抄 S1
- worker 标题：`Anytouch | STAGE-21 | 录制事件流转步骤DSL`

### STAGE-22 主窗裁决补记（2026-09-22，验收时生效）

worker 按军令字面提出的三条严读法全部采纳为契约：
1) `resumeAsRecording()` 仅 STOPPED 合法（PAUSED 唯一走 `resume()`，转移图无平行边）；
2) 存档 `state` 为审计字段，只记录不复活，读档运行态恒 STOPPED（字节幂等仅 IDLE/STOPPED 档承诺）；
3) 读档 fail-closed **全有或全无**：任一事件损坏=整档 Failed，抢救式部分读档属新增能力、需另发军令。
附带：时间戳边界"事件自带 ts、会话提供钟"，`append` 不改写调用方时间戳。
