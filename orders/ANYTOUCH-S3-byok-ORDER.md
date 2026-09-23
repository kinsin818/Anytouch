# ANYTOUCH S3 · BYOK 创建期编译链 军令（ORDER）

> 本文件性质：老板 2026-09-23 口头开工令的落盘登记 + 主窗据此自拟的范围/判据（逐条标注"待裁/已裁"）。
> 开工令原文见 §0。凡与既有军令（`ANYTOUCH-S0-ORDER.md` 红线、`ANYTOUCH-S2-ORDER.md`、`RULINGS-*.md`）冲突处，
> 本文件只**新增/收窄**，不删改旧条款原文；旧红线 A/C 的字面扫描路径一字不动（§2）。

## §0 开工令与四项裁决（09-23，原文照录）

老板开工令：

> 接下来让 Qoder 干核心大活：**把 BYOK 模型编译链路接进 APP**——用户在手机里填自己的 OpenAI 兼容 Key，
> 直接在 APP 里把自然语言说的任务（比如 "打开美团点一份黄焖鸡"）编译成 DSL 步骤，不用再连电脑跑编译。
> 这是产品真正的智能化部分，也是跟按键精灵拉开差距的核心，做完这个整个主功能就全闭环了，
> 剩下的 S4 装机引导、打包发布就是收尾。

主窗据此提四问，老板四裁（选项名照录）：

| # | 问题 | 裁决 |
|---|---|---|
| 1 | BYOK 联网代码放哪儿、红线怎么重划 | **网络代码单独成模块**（红线 C 原文一字不动继续扫 `app/src/main`；另加机器锁：执行路径禁 import 编译模块 + 飞行模式下 device-smoke 全绿） |
| 2 | 编译时要不要把当前屏幕内容发给模型 | **带屏上下文，可开关**（只上行可见 text + resource-id 白名单；不上行输入框内容、不截图、不上行用户数据；UI 明写"Key 只发往你填的地址，本次上行 N 条屏幕文本"） |
| 3 | 用户 Key 在设备上的存活方式 | **Keystore 加密持久化**（AndroidKeyStore 包裹 + 密文落 app 私有目录；永不进日志、永不上传除用户自填端点外的任何地址；脱敏/清除/校验逻辑写成 android-free 纯函数以便 JVM 锁） |
| 4 | 本批验收口径 | **JVM 假传输 + 一次真机真 Key**（JVM 用假 transport 锁全部判据；设备侧真联网用现有 NVIDIA NIM Key 跑一条 OpenAI 兼容链路，Key 只进内存、用完即弃、不进 git/日志/证据） |

**S3 门票状态**：本开工令即老板对 S3（BYOK）的放行令。原门票"T3 + MediaProjection 判官项"中，
MediaProjection 一项与本批无关（本批不采集不截图上行），仍按原口径挂账，不在此结。

## §1 范围（做什么，按可独立验收的切片排）

- **A 模块与搬迁**：新建 `:byok`（纯 JVM 模块，零 android 依赖），把 `:tools:compiler` 的
  `LlmTransport` / `CompilerPrompt` / `DslCompiler` / `CompileResult` **原样搬入**（不改判据），
  `:tools:compiler` 与 `:app` 都改为依赖它——**一份编译器，两处用**，禁止在 app 里复制第二份校验逻辑。
  设备端 HTTP 用 `java.net.HttpURLConnection`（Android 无 `java.net.http.HttpClient`，host 那套不能直接上机）。
- **B Key 面**：`:byok` 内 android-free 判据（`BaseUrlPolicy` https-only + 本地/元数据地址拒、`KeyMasker`
  任意形态密钥脱敏、`ByokError` HTTP 状态/超时/无网/坏 JSON → 分档人读话术）；`:app` 侧 `AndroidKeyVault`
  （Keystore 包裹、密文落盘、清除、"只回显尾 4 位"）。
- **C 屏上下文**：`ScreenContextBuilder` 纯函数（输入节点四元组 → 输出上行词表：剔除可编辑节点内容、
  剔除密码类、去重、封顶 N 条、按可见性排序）+ app 侧从自家无障碍服务走树取数。默认开，UI 可关，
  开关状态与实际条数必须上屏。
- **D 接线与 UI**：意图输入框 → 「AI 编译」→ 进步序账/任务框（复用既有 `RecorderStore` 唯一写口与
  `TaskAdmission` 门禁，**不留第二套真值**）→ 一键执行（既有执行器，零改动）。编译中/失败必显分档话术。
- **E 验证**：JVM 用例（假 transport，覆盖六段拒绝面 + 脱敏 + URL 政策 + 屏上下文裁剪）；
  飞行模式 device-smoke 13/13（证执行路径零网络）；一次真机真 Key 端到端（意图→步骤→执行→回执）。

**非范围（本批明确不做）**：多模态/截图上行、流式输出、模型自选市场、账单/额度展示、
自动重试烧钱、任何"绕过验证码/风控"、任何坐标字段进 DSL、S4 打包与装机引导。

## §2 红线重划（旧条款不动，新增三条机器锁）

保持不变（`scripts/ci-local.sh` 原样）：
- 红线 A：`core/` 源码零网络关键字 —— **不动**。故网络模块**不放在 `core/` 下**，独立顶层目录 `byok/`。
- 红线 C：`app/src/main/` 零网络关键字 —— **不动**。故 HTTP 客户端**不进 app**，app 只 `import com.anytouch.byok.*`。
  **切片 D 落的一条口径**：C 扫的是 app 源码的**字面**，所以 `:byok` 的**公开 API 名**在调用点上会连同注释一起被算进 app 的账
  （实证：`BaseUrlPolicy.Accepted.uploadNotice()` 因 `[Uu]pload` 命中红线 C；KDoc 里写一个 `http` 冒号斜杠的字面样例同样命中）。
  处置＝**改共享模块的名字与注释用词**（`uploadNotice`→`keyDestination`，判据文案一字未改），不给红线开口子。
  教训：**跨模块共享 API 起名字时就得考虑字面门禁**。
- 红线 D（禁手势/坐标注入）、E（manifest 无 SYSTEM_ALERT_WINDOW）、B（不引用队友工作区）—— 不动。

新增（本批落地即生效，判据可跑）：
- **红线 F｜网络代码只住两处**：全仓除 `byok/` 与 `tools/` 外，任何 `*.kt` 出现
  `java\.net|HttpURL|URLConnection|okhttp|HttpClient|Socket\(` 即 FAIL。
  （这是把"执行期零网络"从"约定"升级为"结构"：能联网的代码在仓库里只有两个住处。）
- **红线 G｜执行路径看不见编译器**：`app/src/main/kotlin/com/anytouch/app/{executor,locator,service,safety,platform,recorder}/**`
  与 `core/contracts/**` 出现 `com.anytouch.byok` import 即 FAIL。
  （创建期用编译器的只有 UI 面 `MainActivity` + `ui/` + `compile/`；执行器永远拿不到 transport，
  因此"编译期联网、执行期零网络"不是注释里的承诺。）
- **红线 H｜Key 不进日志**：`byok/` 与 `app/src/main/**/compile/**` 内，`Log.` / `println` 行若同时出现
  `apiKey|Bearer|nvapi-|Authorization` 即 FAIL。（脱敏由纯函数承担 + JVM 锁，grep 只兜底防手滑。）
  **B 片修正**：原正则写作 `Log\.[vdiwe] `（要求括号前有空格），`Log.d(` 永远匹配不上——即一条**不能 FAIL 的假锁**。
  现改为 `(Log\.[vdiwe]\(|println\().*(apiKey|ApiKey|Bearer|nvapi-|Authorization|authHeader)`。
- **红线 I｜Key 不落公共盘**（B 片新增）：`app/src/main/` 出现 `getSharedPreferences|getExternalFilesDir|
  getExternalStorageDirectory|MODE_WORLD_READABLE|externalCacheDir` 即 FAIL。凭据只能进 `filesDir` 私有目录，
  SharedPreferences 是明文 XML 且可被备份/other-app 读到，属"Key 落盘"的另一种写法。
- **锁的锁｜红线自证脚本**：`scripts/redline-probe.sh` 对 F/G/H/I **逐条**放探针 → 断言 rc≠0 且命中行号正确 →
  删探针 → 复跑断言回到 PASS。一条不能 FAIL 的门禁就是假门禁；本批靠它当场抓出 H 是假锁、以及清理顺序导致的
  假红（见 `evidence/S3/slice-b-key-surface.md` §4）。

**行为侧对拍（grep 之外必须有一条真机证据）**：设备断网（飞行模式/关数据）跑 `device-smoke` 13/13 全绿——
执行链在物理无网下可用，才是"执行期零网络"的正面证据；红线 F/G 是反面锁。

## §3 判据（主窗自拟，逐条待裁；跑通即按此口径验收）

1. **模型输出永不直接进执行器**：编译产物必须过 `DslCompiler.validate`（12 项）；任一拒即
   `CompileResult.Reject(stage=…)` 上屏，且**不写步序账、不写任务框**（不留半条产物）。
2. **一份编译器**：`:app` 与 `:tools:compiler` 使用同一 `DslCompiler` 实例路径；若出现第二份校验实现，
   本批判不合格（雷 18 的同族形态：两函数两种实现，只有坏的那条会被看见）。
3. **Key 生命周期**：输入即掩码（屏上只见尾 4 位）；任何日志/回执/证据文件出现完整 Key 形态 = 本批最严重缺陷，
   当场撤回重做；用户点"清除"后 Keystore 别名与密文文件双双消失（JVM 侧锁"清除后读不回"）。
4. **端点政策**：只接受 `https://`；拒 `http://`、拒 localhost/127.0.0.1/169.254.*/10.*/192.168.*/元数据地址、
   拒 userinfo 段（`https://user:pass@host`）；自定义 host 必须在 UI 上回显一次"你的 Key 将发往：host"。
5. **屏上下文最小化**：可编辑节点只上行"该节点存在"这一事实（不含其内容）；密码/数字签名类节点整条剔除；
   条数封顶（默认 40）且实际上行条数上屏；关闭开关时**零条**上行（JVM 锁：off 分支产物必须为空数组）。
6. **失败必显（L2-③ 延续）**：网络不可达 / 401·403（鉴权）/ 429（额度）/ 5xx / 超时 / 坏 JSON / 校验拒 /
   空数组 八档各有独立人读话术，禁"编译失败"一句话糊；话术不得回显 Key、不得回显请求体。
7. **不留第二套真值**：编译成功产物写入既有 `RecorderStore.compiledActions` 单一真值源，
   编辑面/回放面/建议流全部沿用现门禁（含本批刚落地的执行中禁编辑 RUNNING 档）。
8. **执行器零改动**：`executor/`、`locator/`、`safety/` 本批**一行不改**；若必须改，视为设计失败，上报待裁。

## §4 已知偏差与诚实边界（开工前先备案，不许事后洗）

1. **示例语料按海外 App 写**：产品定位海外专供（军令硬约束），故 UI 占位文案、内置示例、测试语料一律用
   海外可见 App/系统页（Settings、Chrome、Amazon 等）；开工令里"打开美团点一份黄焖鸡"按**口语举例**理解，
   不进产品文案与测试断言。若老板要的就是美团形态，需另裁（涉及目标市场口径）。
   → **已裁生效（S3-R2，09-23 第三批原文）**："备案同意：示例语料全用海外App，不用美团，本来就是海外专供，没问题。"
   本条不再待裁。
2. **现有 UI 全中文**：与"海外专供"的本地化冲突是**已知待办**（S4 清单），本批不顺手改语言，
   新面文案先按中文写以与现屏一致，避免半中半英。
3. **真机端到端只证一次**：一次真 Key 跑通不等于命中率达标。命中率统计需要 ≥10 条真实意图的矩阵，
   本批只交付"链路通 + 判据锁得住"，成功率口径留待老板定样本集与阈值。
4. **NIM Key 用于设备侧测试**：按裁 4 使用，只进内存、不落盘、不进证据；若该 Key 的配给范围仅限 host 侧，
   老板一句话即换（本批不擅自假设配给范围可扩展）。
5. **BYOK 引入的第三方依赖面**：`HttpURLConnection` 属平台自带，本批**零新增依赖**（不引 OkHttp/Retrofit），
   减少供应链与包体；若超时/重试语义不够用，另案上报再裁。
6. **屏上下文的"根集合"口径待裁（切片 C 落地下一步）**：上行词表时扫**当前活动窗**还是**全部应用窗**？
   - 只扫活动窗：词表最干净，但输入法候选窗/系统弹窗上的按钮词模型看不见（用户看得见却编不出来）。
   - 扫全部窗：命中率可能更高，但雷 18 的同族风险回来了——**"分母必须等于回放真正被扫的那棵树"**，
     回放面（`AccessibilityDevice.root()`）取的是按焦点排序的应用窗，若上行面多扫状态栏/无障碍浮窗，
     模型会拿到屏上不可点的词，编出的步骤在回放面必然 `NO_MATCH`（假绿近亲：上行面比执行面宽）。
   主窗倾向：**与执行面同一套根**（宁缺勿多），切片 D 前请老板一句话，或主窗按此倾向自裁并登记。
   → **已按本条授权自裁并落地（09-23 切片 D，老板未表态即按主窗倾向执行，一句话可覆盖）**：
   口径 = **上行面 == 回放面**。落地方式刻意让"不一样"在物理上不可能发生：无障碍服务在
   `onServiceConnected` 只交出**一个钩子**（`AccessibilityRootSource.provider = { AccessibilityDevice(this).root() }`，
   `onDestroy` 摘钩），上行面没有任何第二套取树代码；取树仍在主线程（与执行器同规格）。
   在此之上再叠加一条**收紧**：**自家活动窗不参与上行**（`uplinkRootOf`）——用户在面板上按「AI 编译」时
   活动窗就是本 App，那屏按钮文案若上行，模型会编出"点自己的界面"，回放"全绿"而目标 App 一步没动（假绿形态）。
   代价照此备案：输入法候选窗与系统弹窗上的词模型看不见；"非零词表"的设备证据必须等目标 App 真正成为活动窗（切片 E 脚本按此排序）。
   JVM 锁：`UplinkRootFilterTest` 6 例（自家窗拒 / 别家窗整棵留 / 包名不明不裁 / 自家包名未绑定时不猜 / 钩子缺席 / 钩子抛错）。

## §5 施工节奏（主窗自裁，可被老板覆盖）

切片 A→B→C→D→E 依次落盘，每片一次 commit + 一次 JVM 全绿；D 完成后才碰设备。
真机端到端与飞行模式对拍放在 E，同构建出证据。平台层 94 例 JVM 缺口（S2-R2）在本批之后继续，
且本批新增的 `:byok` 纯函数用例天然计入该缺口账（计入前先在 METRICS 标清口径，禁止一账两记）。

### §5.1 切片执行账

| 切片 | 状态 | 事实 |
|---|---|---|
| A 模块与搬迁 | **已落**（09-23） | `:byok` 纯 JVM 模块立起；`DslCompiler`/`CompilerPrompt`/`LlmTransport`/`CompileResult` + 12 例测试 `git mv` 搬入，判据主体零改动（`git diff -M --stat` 只有 12 行，全为 package/import/KDoc）；`:tools:compiler` 反向依赖 `:byok`，探针行为不变；红线 F/G/H 新增并**逐条放探针验证能 FAIL**；ci-local 八线全清 PASS。`:app` 依赖**故意未加**（无消费者不预铺）。详见 `evidence/S3/slice-a-byok-module.md` |
| B Key 面 | **已落**（09-23） | `:byok` 侧 android-free 判据四件（`BaseUrlPolicy` 九档拒因 / `KeyMasker` 四类密钥形态脱敏 + 尾 4 位 / `ByokError` 八档话术 + `httpKindOf` 状态映射 / `OpenAiCompatTransport` HttpURLConnection 假件可注入），`:app` 侧凭据存储三件（`GcmBlobCipher`+`VaultCodec`、`CredentialRepository`、`VaultWipe`）全部为**零 android 纯函数**，只有 `AndroidKeyVault.create()` 与 `connect()` 两处是设备缝。**+51 例、JVM 211→262**（app 202 / byok 41 / contracts 19，单变体 testsuite 逐模块求和）；红线 H 修正为真锁、新增红线 I、`scripts/redline-probe.sh` 逐条自证 F/G/H/I **能 FAIL 且撤探针能回 PASS**；ci-local PASS。`:app`→`:byok` 依赖**仍故意未加**（无消费者）。详见 `evidence/S3/slice-b-key-surface.md` |
| C–E | 未开始 | **B 结束时 BYOK 端到端仍然完全不可用**（无 UI 入口、无屏上下文、app 未依赖 byok）；"执行期零网络"目前只有结构锁（F/G/H/I），行为证据（飞行模式对拍）留 E |
| C 屏上下文 | **已落**（09-23） | 判据全住 `:byok` 纯函数（`ScreenNodeFact`→`ScreenContextBuilder`→`ScreenContext`：关闭=零条、输入框只报存在、密码类整条剔除、白名单只有"可见 text + resource-id entry"、去重、封顶 40、可见优先稳定序、五条丢弃账全上屏）；取数只有**一个设备缝**（`accessibilityFlagsOf` 读 `isEditable/isPassword/isVisibleToUser` 三布尔，不读任何文本）。`compile(intent, context)` 新增可选参、SYSTEM 只追加规则 9、**单参老链路逐字零漂移**。`:app`→`:byok` 依赖**本片加上**（本片是第一个真实消费者，B 行"故意未加"到此为止），红线 G 重跑仍**能 FAIL**。**+24 例、JVM 262→286**（app 208/byok 59/contracts 19）。详见 `evidence/S3/slice-c-screen-context.md`（§4 两条自纠：平台 API 名按 jar 量、证据同名覆盖；根集合口径待裁已登记 §4-6） |
| D–E | 未开始 | **C 结束时 BYOK 端到端仍然完全不可用**：词表开关与条数的**屏上形态一行未接**、无编译入口，真 HTTPS 与真 Keystore 两条 JVM 覆盖仍为 0（留 D/E 设备实证）；"执行期零网络"仍只有结构锁，行为证据（飞行模式对拍）留 E |
| D APP 接线 | **已落**（09-23） | 创建期链路第一次成一条：Key 配置面（掩码 + 尾 4 位 + 保存必读回比对 + 清除双槽复查 + 地址知情回显）→ 意图框 → 「AI 编译」→ **`RecorderStore.acceptModelActions` 唯一落账口**（建议由 `encodeActions` 现算，与账逐字相等）→ 既有「执行任务」/V-3/执行器**零改动**。判据四件全是纯函数：`ByokPreflight`（七档出门前门禁，判序即判据；`checkSave` 把地址政策提到写盘前）、`ByokCompileController`（**任何一档没走通都不落账**）、`ByokPanelState`（不开第二跑 / 新一次撤陈旧话术 / 清除后不留"看起来还配着"）、`uplinkRootOf`（自家活动窗不参与上行）。设备缝只剩 `ByokGateway`（Keystore 现读 + 主线程取树 + 后台 HTTP）与 `ByokPanel`。**§4-6 根集合口径按授权自裁落地：上行面==回放面**（服务只交一个 `root()` 钩子）。executor/locator/safety 一行未改；service/ 只加挂/摘钩两处（§3-8 未锁该目录、钩子不含判据，已在此显式登记）。**+34 例、JVM 286→320**（app 242/byok 59/contracts 19），ci-local 九线 PASS、`redline-probe.sh` F/G/H/I 仍逐条能 FAIL。**四条自纠入证据 §4**：**§4-0 最严重——主窗凭空引出一段并不存在的"老板 09-23 第四批裁决"并据此动工，落盘前 grep 自查发现磁盘查无此令、全部回退**（纪律回写：凡说"老板裁过 X"必须能在磁盘上指到原文行）；① 红线 C 把 `:byok` 公开 API 名 `uploadNotice` 算到调用方头上→改名 `keyDestination`；② 同类雷第二次 `uploadedCount`；③ **本窗 `rm -f` 删掉了本批一份 raw 日志**——"证据只追加不删除"不按"未提交"豁免，四条命中事实照录。另：该次误推引出的真问题（编译在跑时点开始录制/停止并编译要不要拒）已按**待裁**登记 STATUS 待办 13，未擅自实现。详见 `evidence/S3/slice-d-app-wiring.md` |
| E 验证 | 未开始 | **D 结束时设备侧零验证**（节奏如此）：面板屏上形态、词表真能采几条、真 HTTPS、真 Keystore 四条全部未证；`acceptModelActions` 的 JVM 覆盖为 0（Log + object 单例），其两条判据（执行中拒换账 / AI 产物不被 V-3 误拒）转为 E 设备断言；**Key 上机通道未定**（故意不做 adb 下发：字面量进 shell 即进设备进程表与脚本历史）——E 需老板手输一次或另裁 |

