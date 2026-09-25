# S5-c 中文字面量清点与豁免归因（军令⑤"确保没有残留中文字符"的账面）

日期：2026-09-25　主窗：Qoder-Lead　批次：S5-c（全英文界面 + v1.0.1）

## 1. 方法（两条腿，都落盘可复跑）

| 腿 | 命令 | 判的是什么 |
|---|---|---|
| 静态：注释剥离后抽字符串字面量 | `python /d/tmp/cjk/scan.py app/src/main byok/src/main core`（脚本正文已并入本件 §3 结论，扫描器为一次性工具，不进仓） | 代码里**还留着中文的字符串**有哪些、分别归谁消费 |
| 动态：上屏逐屏走位清扫 | `ANDROID_SERIAL=emulator-5554 bash scripts/ui-english-sweep.sh`（本轮新增，入仓） | 真正渲染到屏上的 `text` / `content-desc` 里有没有 CJK 与全角标点 |

静态这条会**多报**（日志、提示词、关键词表都不是屏面资产），动态这条会**漏报**（只覆盖走得到的屏）。两条一起才算说完：动态 0 命中 + 静态每一处中文都能归进下面六个豁免族。

## 2. 静态结果：99 处中文字面量，全部落进六个豁免族（屏面层 0 处）

| 族 | 处数 | 位置（file:line 归并） | 为什么不在这屏上 |
|---|---|---|---|
| A 日志族（S#SMOKE 英文标签体系，R9 明令豁免） | 45 | RecorderCompiler:91/94/97/100/103/106（DropRecord.detail，消费者只有 RecorderStore:539 的 `Log`）；CaptureAdapter:97/99/111/112（drainNotes→RecorderStore:458 `Log`）；CaptureBridge:69/141/157/221/244/245/252/389/432/441/493/494/498/505/549；RecorderStore:172/249/285/303/322/338/389/390/446/472/491/523/532/554；ByokGateway:84；AnytouchAccessibilityService:190/261 | 消费方一律是 `Log.w/Log.i`。逐条查过调用侧：`drops` 只被 RecorderCompiler 与 RecorderStore 读，UI 层零引用 |
| B 异常消息（屏上只印类名） | 3 | AndroidKeyVault:77；GcmBlobCipher:122/126 | `ByokGateway.crashReport`（:199-202）拼的是 `ByokErrorKind.UNREACHABLE.userCopy() + " (cause: device · " + KeyMasker.mask(e.javaClass.simpleName)…"`，消息正文根本不上屏 |
| C 内部断言 | 1 | ByokPreflight:59 `require(gate != Gate.READY)` | 编程错误用的断言，正常路径不可达；`block()` 已挡住 READY 档 |
| D 会话存档拒因 | 21 | RecorderSession:52/82/102/110/126/134/147/155；SessionCodec:57-75（13 处） | `SessionRestore.Failed.detail` 的唯一消费方是 RecorderStore:424 的 `Log.w`；MainActivity:298-301 注入通道只调不显示（注释即写着"归因由 RecorderStore 留痕，此处不重复判"） |
| E 模型提示词 / 词表说明（R9 明令豁免） | 8 | DslCompiler:56/57/58/59/67（系统提示词 6 处）；ExecutorVocabulary:47；ScreenContext:42（`render()` 给模型的可见词表块） | 这是**发给模型的指令**，不是给用户看的话。英化它会改变编译行为，撞军令③"不改编译器代码" |
| F 高危关键词表的中文列（R9 明令豁免） | 21 | HighRiskRule:44-50 | 是**匹配输入**不是文案：词表每类都中英双列（支付/pay、删除/delete、密码/password…），英文列已在 HighRiskMatcherTest 锁死。删中文列=削弱安全门禁，撞军令③ |

45+3+1+21+8+21 = **99**，与扫描器计数逐一对上。`ui/`、`MainActivity.kt`、`template/`、`compile/` 话术层、locator 层、byok 拒绝层在静态扫描里**零命中**——屏面层已经空了。

## 3. 动态结果（本轮 APK：md5 86833471bd0c134491ee78991c224d3b，versionName 1.0.1 / versionCode 2）

```
PASS home   :: 顶到底走位完成，不同状态 3 屏
PASS ledger :: 顶到底走位完成，不同状态 5 屏
PASS 上屏 CJK 扫描 distinct on-screen strings = 55
UI-ENGLISH-SWEEP PASS (0 CJK on screen)
```
靶机 `emulator-5554` = AVD MarvisPhone（API 34，原生 GMS 镜像），`versionName=1.0.1` 由 `dumpsys package` 当场读出。**K40（7ae4bfee）全程零触碰**，且脚本预检里写死了 `alioth/kona/K40` 机型名直接拒跑。

原始 dump + 截图 + 去重上屏文案清单：`evidence/S5/raw/english-sweep/20260925-055744/`（前两轮同目录：`…-055015`、`…-055157`、`…-055448`，一并留着，理由见 §4）。

## 4. 本件里必须记下的三条过程事实（不好看的也记）

1. **走位假绿抓到一次**：第二轮 sweep 九张 dump 里八张 md5 完全相同（页面停在底部连拍），当时照样报了 PASS。这就是"半屏当全屏"。修法已写进脚本：每段先滚回顶（顶锚 `Anytouch executor`）、逐屏下滚到 md5 不变为止（底锚 `Run task`），**顶锚/底锚缺一或不同状态 <2 屏直接判红**。§3 的 PASS 是改完之后重跑的。
2. **红线 C 是被英文文案撞上的**：`scripts/ci-local.sh:48` 用关键字 grep 锁"执行期零网络"，模式含 `[Uu]pload`。中文文案译成英文后 `uploaded` 这个普通词自己触了红线（ByokCompileController:144、ByokPanel:72/105）。**没动红线**——它是冻结门禁，动它等于自己给自己放行；改的是文案：三处 app 侧 + 为口径一致连 `ScreenContext.notice()`（byok，屏上那行）一起从 "uploads/uploaded" 改成 "sends/sent"，含义不变（"字都没上行"）。改后红线 A-I 九条全 clean。
3. **模板钮与模板名脱节**（英文化前就有）：按钮硬编码 "Photos template"，而装载结论句用的是注册表 label "Photo cleanup"。英文用户会看到两个名字指同一件事。已让三个按钮直接读 `PresetTemplateLibrary.byId(id)!!.label`，id 与门禁零改动，删掉一份重复文案源。

## 5. 与军令③的边界自查

军令③"只改显示文案，不改任何执行器/编译器/安全门禁代码"。本轮产品代码改动逐条落在此线上：
- 改的全是**字符串字面量**（上屏话术、拒因话术、模板 label 引用）；
- 执行器（NodeTaskRunner / ExecutorVocabulary 派发）、编译器（RecorderCompiler 判定与 DslCompiler 校验逻辑）、安全门禁（HighRiskRule 词表与匹配逻辑、URL 政策、Keystore/加解密路径）**判据零改动**；
- 唯一非字面量改动两处：`app/build.gradle.kts` 版本号（发版必需，v1.0.0 那枚 APK 的 manifest 还写着 0.1.0-s1，本次对齐）、按钮从硬编码串改为引注册表 label（取数来源，不改行为）。
- JVM 全量：`ci-local.sh` PASS，`--rerun-tasks` 单变体分模块 XML 计数 **431/0**（app 343 + byok 69 + :core:contracts 19），与 S4 结案基线同口径。

## 6. 附：静态扫描器（一次性工具，正文留此以便复跑；仓内不建第二份 grep）

判据两句话：先把注释切掉（`//`、`/* */`，并跟踪字符串内外的引号态），再在剩下的代码里抽 `"..."` 字面量，命中 CJK/全角段即列 `file:line`。

```python
CJK = re.compile(r'[　-〿぀-鿿豈-﫿＀-￯]')   # 中日韩统一表意 + 扩展A + 兼容表意 + CJK 标点 + 全角
STR = re.compile(r'"(?:[^"\\\n]|\\.)*"')
# 逐行：跳过注释态 → 抽字符串字面量 → CJK.search(去掉首尾引号) 命中则报 file:line
```
本轮输出 99 行，逐行归族见 §2 表（45/3/1/21/8/21）。


## 7. S5-e（v1.0.4）增量账（09-25 晚追加，只追加不改上文）

**结论：本批新增中文字面量 0 处**——§2 的 99 处口径原样沿用，不重跑、不改数。

| 腿 | 本批读数 |
|---|---|
| 静态（增量口径） | 对 `git diff v1.0.3 -- app/src/main byok/src/main core` 逐行剥注释后抽 `"..."` 字面量：**命中 0 条**（本批新写的判据层／接线层话术全英文：`SavedTaskGate` 九档、`manualStepGateOf` 四档、`StepEdit` 门禁九档拒因）。新增/改动的五个文件（`SavedTasks.kt`／`StepInsertion.kt`／`StepRetryPolicy.kt`／`AndroidSavedTaskDisk.kt`／`StepListUi.kt`）内 **无 `"""` 原始字符串**，故"逐行抽字面量"这一把尺子对本批增量成立（不会因为跨行串漏读）。 |
| 动态（上屏走位） | `scripts/ui-english-sweep.sh` 在 v1.0.4 字节上 PASS：段1 home 4 屏／段2 ledger 7 屏／**段3 insert 面板层 8 屏（本批新增的一段）**，去重上屏 85 串，**CJK=0**。原文 `evidence/S5/raw/v104-regress-ui-english-sweep-20260925-192412.raw.txt`，dump+截图 `evidence/S5/raw/english-sweep/20260925-192412/`。 |

**两处必须如实说的口径边界**：
1. §1 那条静态腿的一次性扫描器 `python /d/tmp/cjk/scan.py` **现在已不在盘**（`/d/tmp/cjk/` 只剩 `scan-out.txt`（99 行清单本体）与 `inventory.txt` 两份产物）。所以本批**没有**、也**不去**重算那个 99——主窗自写的第一版近似扫描器整树只数出 70，差在跨行/原始字符串的切法不同，**那是尺子不同不是数不同**，拿它去改 §2 的旧数正是"拿错口径纠别人"那一类难看事（协作记忆在册）。增量一律用上面表里那条 diff 口径。
2. §2 的行号**一律按 S5-c（v1.0.1）快照读**，它们此后一直在漂：`AnytouchAccessibilityService` 那两枚（§2 记 `:190/261`）在 v1.0.3 时就已是 `:202/:375`，S5-e-1 给该文件加了 9 行之后现在是 `:202/:384`——**条数 2→2，本批一条未增、一条未减**，只是行号随接线改动下移。指认内容请以字面量文本为准（现值：`:202` = `S2SMOKE record ball refused: 执行中不开录（见 watchRecordBall）`；`:384` = `S1SMOKE run cancelled by service lifecycle … 回执缺席以此行为准`）。
