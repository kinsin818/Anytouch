# S3 · E0 前置批：编译期状态机互斥（裁决 S3-R4-1 落地）

日期：2026-09-23（切片 E 设备批之前的一次小前置批，独立 commit）
裁决原文：`orders/ANYTOUCH-S3-byok-ORDER.md` §0.1（S3-R4，老板 09-23 第四批，本批先落盘再动工）

> "1. 编译在跑时要拒开录/停止，跟执行中禁编辑一个逻辑，状态机互斥，避免串状态"

## §1 这一批改了什么（一句话）

"AI 编译那一跑在路上" 从**只有面板知道**升格为**全仓一格真值**（`AppState.compileBusy`），
录制面两个入口（「开始录制」「停止并编译」）各自加一档门禁并显因，编译归位时两句红字各有一条过期边。

## §2 文件与职责

| 文件 | 职责（判据住在哪儿） |
|---|---|
| `app/.../AppState.kt` | 新增 `compileBusy: MutableStateFlow<Boolean>`——全仓唯一的"编译在跑"真值 |
| `app/.../platform/AccessibilityGate.kt` | `RecordGate.COMPILING` 新档 + 话术；`stopCompileGateOf(compileBusy)`＝**判据唯一住处**；`recordGateOf` 第四输入并转调它；`stopRejectionAfterStateChange` 过期边 |
| `app/.../recorder/session/RecorderStore.kt` | `start()` 传第四输入；`stopAndCompile()` 入口先过门禁（拒则**不动会话、不编译、不写账**）；新增 `stopRejection` + 档位格 + `revalidateStopRejection()` + `revalidateAfterCompile()` |
| `app/.../compile/ByokPanelState.kt` | `busy` 默认即 `AppState.compileBusy`（构造参可注入假流做用例隔离）；领取成功才翻格、被拒不翻 |
| `app/.../compile/ByokGateway.kt` | `endCompile` 之后调一次 `RecorderStore.revalidateAfterCompile()`（撤两句陈旧红字） |
| `app/.../MainActivity.kt` | 顶栏一行"AI 编译中…（录制与改账此刻不动）"；两按钮编译期置灰（灰只是提示）；新增 `record_stop_rejection` 格 |

executor/locator/safety/byok **一行未改**（§3-8 与红线 G 都不涉及本批：`recorder/` 只读 `AppState` 与自家 `platform/`）。

## §3 判据（为什么是这个形状）

1. **一格真值**：面板的"编译中"与录制门的"编译中"若是两份，就是雷 18 同族（两本账）。
   `ByokPanelState().busy` 默认**就是** `AppState.compileBusy` 那个实例，JVM 用例 `assertSame` 锁住。
2. **判据一处、两处用**（与 :byok 的"一份判据两处用"同律）：`recordGateOf` 不抄 `if (compileBusy)`，
   而是转调 `stopCompileGateOf`——两个入口的档位与话术必然逐字相等，用例直接断言相等。
3. **判序**：`SERVICE_OFF → RUNNING → BALL_UNAVAILABLE → COMPILING → READY`。
   执行中排在编译前（账本先归执行器，两把锁不许互相伪装）；球挂不上排在编译前是**为过期边服务的**：
   球是长期缺项（编译结束不会自己好），若先说"编译在跑"，编译归位后那句已失去依据的话术仍会留在屏上——
   过期边只在整门判 READY 时才撤字，判序错了就会自己造出一条假红。
4. **停止面拒 = 整条不动**：不许 `stop()` 会话、不许编译、不许写账，返回值沿用"无会话"那一档的空形态
   （`actions=[]`、json=`"[]"`），会话原样留着等编译归了再点。
5. **话术另起一格**：`stopRejection` 不与 `startRejection` 共用——两件事同时红着时不许互相盖
   （与 `ByokPanelState.configMessage` 和编译结论分格同一条理由）。
6. **拒因身份存枚举不存文本**：`stopRejectionGate` 与 `stopRejection` 成对写，过期边比对档位。

## §4 主窗原倾向被裁掉的地方（如实记，不改口）

切片 D 我登记的倾向是"**停止并编译拒、开始录制不拒**"（理由：开录只是会话还在录）。
老板裁决把**两个入口一起**纳进来（"状态机互斥，避免串状态"），倾向作废、按裁决落码。
这条自纠的价值不在"猜对了没有"，在于我先把倾向写在盘上，裁决下来时能看见自己原来站在哪儿。

## §5 JVM 账（真实判据数，不设阈值）

**+12 例、320→332**（app 254 / byok 59 / contracts 19；`--rerun-tasks` 单变体分模块 `<testsuite tests>` 求和，0 失败）。

| 用例 | 锁住什么 |
|---|---|
| `编译在跑即拒开录（其余三项齐备也不放行）` | 第四输入真能挡 |
| `执行中优先于编译中` | 两把锁不互相伪装 |
| `球挂不上优先于编译中` | §3-3 的判序理由 |
| `停止并编译两档` | 会话在不在不归门禁判 |
| `两个入口共用一份判据与一句话术` | 档位相等 **且** 话术逐字相等（抄第二份即红） |
| `录制面被挡 vs 编译侧不开第二跑 两句不同话` | 同屏两句必须分得开（`BUSY_COMPILE` ≠ `COMPILING`） |
| `READY 无话术，其余四档必须有人读归因` | 新档不许静默禁用 |
| `编译归位即作废停止话术`／`还在跑时原样保留`／`无拒因不凭空造`／`非持有档不许被这条边抹掉` | 过期边四条（与 V-2 同律） |
| `默认就是全局那一格`（`assertSame`） | 一格真值 |
| `领取成功才翻全局 被拒的第二跑不许凭空锁死录制面` | 翻格时序 + 编译中两入口同拒 + 归位撤格 |

`TaskAdmissionTest` 的"话术互不雷同"由 7 条扩到 8 条（含新档）——**新档若抄了执行中的话术即红**。

## §6 门禁与结构锁

`ci-local.sh` **CI_RC=0**：九线 A–I 全 clean（G 仍 clean＝`recorder/` 只读 `AppState`/`platform`，没看见 `:byok`）。
`redline-probe.sh` **PROBE_RC=0**：F/G/H/I 逐条能 FAIL、撤探针回 PASS（本批未改红线，重跑只为确认新代码没让任何一条变成假锁）。
原文见 `evidence/S3/raw/ci-local-and-probe-s3e0.log`（带时间戳、只追加）。

## §7 诚实边界（不许事后洗）

1. **`RecorderStore` 两条入口的 JVM 覆盖仍为 0**（object + `Log`，`unitTests` 未开 `returnDefaultValues`）。
   本批锁的是**判据**（纯函数 + 翻格时序），不是**接线**：
   "真机上编译在跑时点「停止并编译」= 会话没停、账没动、屏上有红字"这条**必须**在 E 用设备断言补，列进 E 断言清单（§E-互斥）。
2. 编译在跑期间「执行任务」与「步骤编辑」两个入口**没加门禁**——裁决原文只点名"开录/停止"。
   现状后果：编译归来会整本替换步序账，期间的编辑会被覆盖（不报错，但用户会看见"我改的那步没了"）。
   是否把这两个入口也并进互斥，等一句话；本批不擅自扩范围。
3. 过期边的触发点是"编译归位"那一次（`revalidateAfterCompile`），**故意不挂**在 `watchRecordBall` 的三股流上：
   那股流里没有"编译在跑"这一股，且服务未连时它根本不转——挂上去，编译红字就有一条永远等不到作废。
   代价如实说：若编译协程被进程杀死（非正常归位），`compileBusy` 留在 true、红字留在屏上，
   直到下一次 `endCompile` 或重启进程。这一格没有第三方能把它撤回来，属已知残留（E 若真机撞见即升级为待裁项）。
4. `compileBusy` 是进程内状态、不落盘——**故意的**：跨进程重启还"编译中"是假状态（那一跑早就没了）。
