# S3 切片 C · 屏上下文最小化（2026-09-23）

## §1 这片在地基里的位置

裁 2 的原话口径：**带屏上下文，可开关**——只上行可见 text 与 resource-id，不上行输入框内容、不截图、不上行用户数据。
本片把这件事做成两处：**判据全在 `:byok` 的纯函数里**（JVM 可锁），**取数只有一个设备缝**（读三个布尔）。

C 结束时 BYOK 端到端**仍然不可用**：没有 UI 入口、没有开关的屏上形态、编译器还没接进 APP 的任何一条链路。
本片交付的是"上行什么"的唯一判据，不是"能编译"。

## §2 文件与职责

| 文件 | 层 | 职责 |
|---|---|---|
| `byok/.../ScreenContext.kt` | 纯函数 | `ScreenNodeFact`（节点事实）→ `ScreenContextBuilder.build(facts, enabled)` → `ScreenContext`（上行词表 + 五条"为什么少了"的账 + `render()`/`notice()`） |
| `byok/.../DslCompiler.kt` | 纯函数 | `compile(intent, context)` 新增可选参；`CompilerPrompt.userMessage()` 把词表拼在意图后；SYSTEM 追加规则 9（只能取词表里的词、`input_field` 不含内容） |
| `app/.../compile/ScreenContextCollector.kt` | 纯函数 | `UiNode` 树（可多根）→ `List<ScreenNodeFact>`；旗标由参数注入，故这层本身 JVM 可测 |
| `app/.../compile/AccessibilityScreenFlags.kt` | **设备缝** | 三个布尔取自 `AccessibilityNodeInfo`（`isEditable`/`isPassword`/`isVisibleToUser`），一个文本字段都不读 |
| `app/build.gradle.kts` | 构建 | 加 `implementation(project(":byok"))`——**本片是第一个真实消费者**（切片 A/B 故意未加，"无消费者不预铺"） |

## §3 判据 → 用例映射（24 例，逐条对应军令 §3-5 / §3-6）

| 判据 | 用例 |
|---|---|
| 关闭开关 = **零条**上行（军令原文要求 JVM 锁死） | `关闭开关时零条上行且渲染为空串`、`开关关闭时屏上有字也零条上行`（取数层短路）、`关闭态编译时用户消息里没有任何屏幕词` |
| 输入框只上行"存在"这一事实，内容不上行 | `输入框只上行存在这一事实 其内容一个字都不上行`、`取数层漏给 editable 旗标时按类名兜住 内容照样不上行`（byok 侧兜底）、`取数层不判输入框 类名像输入框就不上行其内容`（app 侧同一事实）、`平台旗标说可编辑时 类名不像输入框也照样只报存在` |
| 密码/签名类整条剔除（连 resource-id 都不上行） | `密码类节点整条剔除 连资源id都不上行` |
| 白名单只有两样（可见 text、resource-id 的 entry 段） | `上行行只有白名单三种形态`（正则穷举三种合法行）、`resource-id 只上行 entry 段 不带包名前缀` |
| 条数封顶（默认 40）且超出可见 | `封顶默认40条 超出部分计入账而不是静默丢弃`、`cap 非法直接拒 不允许 0 或负数伪装成关闭` |
| 去重 / 屏外不占名额 / 稳定序 | `重复词只上行一条`、`不可见节点不占上行名额 且名额优先给可见项`、`排序稳定 可见项按文档序`、`多窗口根并扫 同词只上行一次` |
| **上屏条数必须是真数** | `话术里的条数必须等于真实上行条数`（notice 内插值取自 `uploadedCount`，与 `lines.size` 同处证明） |
| 泄露面 | `事实类的 toString 不回显节点文本`、`无文本无id的纯容器不产出一行` |
| 一份判据一处用 / 老链路零漂移 | `无上下文时用户消息逐字等于意图`（切片 A 的 prompt 不变）、`带上下文时意图在前 词表在后 且 SYSTEM 未被动过`、`整棵树的节点都被扫到 父先子后`、`根缺席时零条而不是空对象` |
| 加上下文不许削弱校验 | `带上词表不削弱校验 模型仍编坐标照样拒`（validate 一档不松） |

## §4 本批自查与平台事实（含一条自纠）

1. **平台事实按 jar 量，不按记忆写**：初版取法写 `info.isTextEditable` → `Unresolved reference`。
   `javap` 查 android-36 的 `AccessibilityNodeInfo` 实际只有 **`isEditable()`**（同批确认 `isPassword`/`isVisibleToUser` 为真名）。
   写错的那一版还顺手把"是不是输入框"整条推给类名判据，读 javap 之后才恢复成"平台旗标 ∪ 类名"两条并存的 fail-closed 判据。
2. **证据覆盖自纠（本条照实记）**：`evidence/S3/raw/ci-local-and-probe-s3c.log` 本文件对应的第一次跑（18:51）是**增量构建**
   （44 个 task up-to-date）——那次的数不能当计数基准；随后我用同名 `>` 重定向写了干净重跑的日志，**把 18:51 那份覆盖了**。
   违反"证据只追加不覆盖"。后果评估：被覆盖那份是同一批、同一棵代码树的增量跑，其唯一用途就是被本份（`--rerun-tasks` 干净跑）取代，
   无独有结论；但它确实没了。纪律重申：**同名重定向只允许写给"正在生成的那一份"**，要留两版就换名。
3. `:app` → `:byok` 依赖不是"提前接线"：它是本片第一个真实消费者（`compile/` 要用 `ScreenContext` 类型）。
   红线 G 的机器锁仍在——加完依赖后 `redline-probe.sh` 重跑，G 依旧**能 FAIL**（探针放在 `executor/` 下，见 §5）。
4. 词表进 prompt 只改 `userMessage`，SYSTEM 逐字不变，因此切片 A 的 12 项校验、host 探针链路零漂移（`compile(intent)` 单参调用仍编译通过、行为一致）。

## §5 真数账（单变体、分模块、`--rerun-tasks` 干净重跑）

```
./gradlew --offline --rerun-tasks :core:contracts:test :byok:test :app:testDebugUnitTest :app:assembleDebug
SKIP_GRADLE=1 bash scripts/ci-local.sh        # 九线 A–I
bash scripts/redline-probe.sh                 # F/G/H/I 逐条自证
```

| 模块 | tests | failures |
|---|---|---|
| `:app`（testDebugUnitTest） | 208 | 0 |
| `:byok` | 59 | 0 |
| `:core:contracts` | 19 | 0 |
| **合计** | **286** | **0** |

- 上批（切片 B，HEAD `7fc4ac6`）= 262 → **本片 +24**（byok +18、app +6）。
- BUILD_RC=0 / CI_RC=0 / PROBE_RC=0，原文与时间戳同文件：`evidence/S3/raw/ci-local-and-probe-s3c.log`。
- `:tools:compiler` 无 JVM 测源（切片 A 搬走后 NO-SOURCE），不计入合计。

## §6 诚实边界（不洗）

1. **`accessibilityFlagsOf` 的 JVM 覆盖 = 0**：它碰 `AccessibilityUiNode`（Android 类型）。三个布尔在真机上到底读到什么，
   要切片 D 上设备才知道——**"密码框真的被 `isPassword=true` 标出来"目前只是平台文档上的假设**，没有一条设备证据。
2. **根集合口径未定**：`collect(roots)` 支持多窗口根，但切片 D 到底传"当前活动窗"还是"全部应用窗"没定。
   这不是洁癖——雷 18 的形态就是"采集面的分母 ≠ 回放面扫的那棵树"；这里若把输入法窗、状态栏窗一起扫，
   词表会含用户看不见的词，模型照着编就等于凭空点。D 之前必须裁一次并把口径写进证据。
3. **开关与条数的屏上形态还没有**：`notice()` 是纯函数产物，UI 侧一行都没接（军令 §3-5"开关状态与实际条数必须上屏"目前只有判据侧）。
4. **封顶 40 是拍的**：没有 token 账单与命中率数据支撑，切片 E 的真机端到端跑一次后再定（可能要按模型上下文窗反推）。
5. **词表能否提高编译命中率未证**：本片只证"上行什么"，不证"上行之后编得更准"。命中率口径仍等老板定样本集与阈值（§4-3 待办）。
6. 词表行里 `text="…"` 会原样带上屏上任何可见文字（应用名、他人昵称、聊天内容里可见的部分都算）——
   这是"带屏上下文"的**固有代价**，不是漏实现；裁 2 已把它限定在"可见 + 非输入框 + 非密码 + 封顶 40"，UI 必须把条数讲清楚。

## §7 下一片

D（APP 接线）：意图框 → 取词表（含开关与条数上屏）→ `DslCompiler.compile` → 产物进 `RecorderStore.compiledActions` 单一真值源
→ 既有执行器一键跑；八档失败话术上屏；testTags 供 ui-smoke。执行器/定位/安全三包照旧一行不改（军令 §3-8）。
