# Anytouch | STAGE-11 | 节点树三级定位 — 交付报告

- 军令：`orders/ANYTOUCH-S1-ORDER.md`（ANYTOUCH-S1-EXECUTOR-20260922）L2-STAGE-11
- 基线：主窗 commit `5049b05`（app 脚手架）
- 工人：STAGE-11 worker（与 STAGE-12 safety 包并行，文件集互斥）
- 结论：**29 个 JVM 断言用例全绿，BUILD SUCCESSFUL，:app:assembleDebug 未破坏**

## 1 改动（全部落在军令划定的独占目录）

新增 `app/src/main/kotlin/com/anytouch/app/locator/`：

| 文件 | 内容 |
| --- | --- |
| `UiNode.kt` | `UiNode` 抽象接口（resourceId/text/contentDesc/className/packageName/clickable/scrollable/bounds/children 最小集）、`UiBounds`、文档序遍历 `preOrder()`、层序遍历 `subtreeBreadthFirst()` |
| `LocatorModel.kt` | `LocatorLevel`（一/二/三阶）、`LocatorRequest`、`NodeRef`（含 `indexPath` 节点引用）、`AttemptOutcome`、`LocatorAttempt`、`LocatorResult`/`LocatorHit`/`LocatorMiss` |
| `PathPattern.kt` | 三阶层级路径语法定义、解析器与谓词匹配 |
| `NodeTreeLocator.kt` | 三级定位链主体与短路/留痕逻辑 |

新增 `app/src/test/kotlin/com/anytouch/app/locator/`：

| 文件 | 内容 |
| --- | --- |
| `TestUi.kt` | JVM 侧 `UiNode` 假实现 + 建树辅助（`ui()`/`deepChain()`）+ 断言辅助 |
| `NodeTreeLocatorTest.kt` | 29 个断言用例（军令要求 ≥12） |

冻结文件、Gradle 配置、`core/*`、`pipeline/*`、`safety/*`、`.dumate/.workbuddy`：**零改动**（`git status` 见 §3）。

## 2 实现要点与对外口径

1. **阶次与短路**：一阶 resource-id → 二阶 text/contentDescription → 三阶层级路径；任阶命中立即返回，后续阶不再尝试；三阶全空只返回 `LocatorMiss`。**不猜、不降级到坐标**：`NodeTreeLocator` 与 `PathPattern` 的任何匹配分支都不读 `UiNode.bounds`（`UiBounds` 只作为节点自述信息透传进 `NodeRef`，`centerX/centerY` 由主窗执行器派生）。
2. **一阶**：resource-id **整串全等**（trim 后），跨包同名 entry 不算命中；多命中按 `LocatorRequest.instance` 取文档序（深度优先、父先子后、同层按子序号）。
3. **二阶**：text 与 contentDescription 各自 trim 后**全等**，明确非 contains；两者同时给出时按节点先后取第一个成立的匹配，`matchedBy` 记录到底是哪条线索命中（`text='…'` / `contentDescription='…'`）。
4. **三阶路径语法**（L3 留白自决，此为冻结后的对外口径，主窗写任务 JSON 时按此拼）：
   - `path := segment ('>' segment)*`；`segment := pred ('&' pred)* ('[' digit+ ']')?`
   - `pred := '*' | key '=' value | bareToken`，`bareToken` 等价 `class=bareToken`
   - `key := class | id | text | desc | clickable | scrollable | package`
   - 比较规则：class 短名/全名大小写不敏感全等；id 全串或 entry 名全等；text/desc trim 全等；package 全等；clickable/scrollable 只接受 true/false
   - 段与段之间是**祖先—后代**关系（不是直接父子）：每段在上一段锚点的子树内按层序（浅的先）取候选，`[n]` 为该段候选集序号，缺省 `[0]`。军令原例 `Window>ListView>clickable=true[2]` 即"ListView 后代中第 3 个可点节点"，本实现命中埋在更深一层的 `更多` Button。
   - **三阶只认路径内的 `[n]`，`request.instance` 不参与**，避免同一阶出现两套序号；此规则有专门用例锁定。
5. **命中留痕**：`LocatorHit(level, nodeRef, …)`，另带 `node`（活的 `UiNode` 句柄，主窗对它执行动作）与 `matchedBy`；`NodeRef.indexPath` 为 `root/0/2/0` 形式的确定性节点引用（用对象引用 `===` 求路径，不用 equals，防同内容兄弟串位）。E2 回执卡/节点 diff 可直接消费。
6. **miss 记录**：`LocatorMiss.attempts` 三阶各一条，字段 `level/query/outcome/candidateCount/detail`；`outcome ∈ HIT / NO_MATCH / INDEX_OUT_OF_RANGE / NOT_ATTEMPTED / INVALID_QUERY`。语法非法、空串线索、序号越界三类**都进记录**，不静默跳过。`summary` 是人读一行式归因。
7. **与 contracts 的对齐方式（未 import）**：实测 `:app` 的 `debugCompileClasspath` 只有 `project :core:contracts`，而 contracts 把 `kotlinx-serialization-json` 声明为 `implementation` —— `StopReason`/`JsonObject` 不在 app 编译类路径上，`app/build.gradle.kts` 属冻结文件不可加依赖。故本包**在语义上对齐而非类型上依赖**：`LocatorMiss.NODE_NOT_FOUND` 为稳定码，主窗窄口集成时一行即可转 `StopReason(code = NODE_NOT_FOUND, severity = STOP, message = miss.summary, evidence = attempts)`；`LocatorHit` 转 `ActionResult(ok = true, …)`。军令 L1 已写明接线由主窗窄口完成。

## 3 命令与结果（Evidence Minimum）

```
$ cd /d/Anytouch && ./gradlew :app:testDebugUnitTest --tests "com.anytouch.app.locator.*" --rerun \
    -I "<仓库外临时 init 脚本>"
BUILD SUCCESSFUL in 2s        # 29 PASSED / 0 FAILED
$ ./gradlew :app:assembleDebug
BUILD SUCCESSFUL in 1s
$ grep -rn "java.net|HttpURLConnection|OkHttp|performGesture|dispatchGesture" <本包 main+test>
无网络/无坐标手势引用
$ grep -rn "^import" app/src/main/kotlin/com/anytouch/app/locator
（无输出：本包 main 侧零 import，Android 类与序列化库均未进入）
$ git status --porcelain（剔除 build 目录）
?? app/src/main/kotlin/com/anytouch/app/locator/     # 本工新增
?? app/src/test/kotlin/com/anytouch/app/locator/     # 本工新增（含在 ?? app/src/test/ 下）
?? app/src/main/kotlin/com/anytouch/app/safety/      # STAGE-12 工人，本工未碰
?? evidence/S1/                                      # 本工证据
?? scripts/s1-smoke.sh                               # 非本工产物
```

无 ` M ` 条目 → 冻结文件与 Gradle 配置零改动。

证据文件：`evidence/S1/stage-11/test-output.txt`（含 `BUILD SUCCESSFUL` 与 29 行逐用例 `PASSED`）。

## 4 坑自纠留痕

1. **逐用例 PASSED 行的取得**：`app/build.gradle.kts` 未配 `testLogging`，而它属冻结文件——直接跑只有 `BUILD SUCCESSFUL`，看不到用例名，不满足证据要求。处置：把 `testLogging` 注入写到**仓库外**的临时 init 脚本，用 `-I` 传入，并加 `--rerun` 防止任务被判 `UP-TO-DATE` 而不真跑。仓库零改动，证据是 Gradle 原生输出。
2. **Sequence 与 List 混用**：`preOrder()` 首版写成 `sequence {}`，导致 `.filter/.mapNotNull` 返回 Sequence，`isEmpty()` 与 `pick(List)` 三处编译失败。改为返回 `List`（遍历顺序不变，文档序语义更直观）。
3. **假实现签名**：建树辅助函数 `ui(...)` 首版用 `vararg children: UiNode`，调用点写成 `children = listOf(...)` 与 spread 混用不通过；改为普通 `children: List<UiNode> = emptyList()` 参数。
4. **一条会掩盖失败的断言**：谓词组合用例里曾写 `hit(...).node.text ?: "WLAN"`，elvis 会把 null 也判绿——删掉，改为直接断言节点文本。同处 `scrollable=true` 放在锚点自身之后作后段，语义上永远落空（后段只扫锚点后代），改写为 `id=list>clickable=true[0]`。
5. **两处断言暴露的实现口径**（改测试而非放宽实现）：① `NodeRef.text` 是节点原值快照（含首尾空格），不做二次 trim——留痕要忠实，故用例明确断言原值并在注释里写清；② 路径段零命中的 detail 里"扫描范围"显示的是**范围首节点**（`范围 ListView#list 起、共 5 个节点`），不是上一段的名字，用例按此锁定。
6. **测试方法名不写 `>`/`&`**：JVM 方法名不允许这些字符，军令原例的路径串只出现在用例体里，方法名用中文描述。
7. **`attempt-query` 记录一致性**：`INVALID_QUERY` 场景下 `LocatorAttempt.query` 必须保留原始非法串（含未 trim 的空白路径），否则失败归因拿不到原输入；已加断言锁定。

## 5 风险与遗留（交主窗裁决/接线）

1. **`NODE_NOT_FOUND` 尚未进 contracts**：`Constants.kt`/`PipelineStopCode.kt` 均冻结，码目前只存在于本包 `LocatorMiss.NODE_NOT_FOUND`。主窗窄口或后续军令负责把它并入统一码表；在此之前失败归因 6 类的"未找到节点"以该常量为准（有注释说明）。
2. **真实无障碍树的适配未做**（军令本就归主窗）：`AccessibilityNodeInfo → UiNode` 适配器需在主窗实现，注意 ① `getChild()` 返回的节点要按本接口 `children` 顺序一致（否则 `indexPath` 与 instance 语义漂移）；② 跨进程取到的 text/className 可能为 null，本包全部匹配对 null 安全；③ 每次 `bounds` 读取都不参与定位，故节点树刷新导致的坐标抖动不会影响定位结果。
3. **路径语法的取值分隔符**：谓词值中不允许出现 `&`、`>` 与结尾 `[n]`（文档已写明）。设置类页面文本满足此约束；若后续任务出现含这些字符的文案，需主窗定转义规则（属语法扩展，未自行发明）。
4. **三阶层序（就近优先）语义**是 L3 留白内的选择：好处是 UI 层级插层时路径不碎，代价是极深列表下每段扫描为 O(子树)，模拟器节点树规模下不构成问题；真机大数据量若成为热点，属 S2 优化项，已在此留痕。
5. **用例覆盖但无 UI 侧回归**：本 stage 只有 JVM 断言；模拟器上"设置→关于→连点版本号"链路成功率 ≥95% 的冒烟验收属主窗 `scripts/s1-smoke.sh` 收口，本工不虚标。
6. 与 STAGE-12 并跑确认：本次 `:app:compileDebugKotlin` / `compileDebugUnitTestKotlin` 连带编译了 `safety/` 包且通过，未观察到互相踩踏；本工未读写 `safety/` 任何文件。

## 6 完成定义自查

- 代码 + 29 用例落盘，命令可复现（`--rerun` 强制真跑）
- 证据：`evidence/S1/stage-11/test-output.txt`
- 报告：本文件
- 未 `git commit`；未碰禁区；无 fake success（首版 2 个用例真红，已按 §4.5 修正断言口径后全绿）
- `STATUS.md` 未更新：它是施工窗→总窗的里程碑汇总口，S1 行属阶段级聚合，且与 STAGE-12 并写有踩踏风险——留主窗收口时一并写（当前该文件仍记 S1 为"待开工"、"S1 军令未写"，与仓库实况已不符，主窗需刷新）
