# S5-d · 重复执行循环 设备面实证（avd34 · 4 轮收敛账）

> 军令件：`orders/ANYTOUCH-S5d-repeat-loop-ORDER.md`（第 1~6 条逐字在册）+ 裁决 S5-R11 补裁 **C**
> （界面一枚「Repeat without asking」开关，默认不勾＝安全口径不变）。
> 判据在册：`orders/RULINGS-20260922.md` §S5-R11 执行面钉 1~7（验证矩阵）。
> 靶机：`emulator-5554`（avd34，API 34，1080x2400）。**K40/K80 真机本批零指令。**
> 被测物：`app/build/outputs/apk/debug/app-debug.apk`，`versionCode=4 / versionName=1.0.3`，
> 构建 09-25 11:29:36、`md5 93b284d48fa52c2daa01b5ff6517e985`（9,335,775B），
> 机上 `lastUpdateTime=2026-09-25 03:29:35`（设备时钟）与构建时刻对得上＝跑的是本轮码，不是上一版。
> 执行期**零网络、零模型请求**（七格全为 wait 任务或自家 Settings 节点点击）。

## 1. 背书轮：`s5d-repeat-loop-smoke.sh` run 4 · 15 PASS / 0 FAIL · RC=0

raw：`evidence/S5/raw/s5d-repeat-20260925-114351.raw.txt`（总用时 125s，收尾服务在绑=1）

| 格 | 钉的是军令哪一条 | 逐字读数 |
|---|---|---|
| L1 | 单发口径不回归 | `S1SMOKE ok=2 total=2 stopped=false stop=-`（无 `round=` 段、无 `repeats:` 收口） |
| L2 | 第 1/2/3 条 | `跑满 3 轮 · 轮间等待 4000/4000/ms（3±1 档）· 墙钟 16s`；未勾选时 `askEveryRound=true`（安全默认） |
| L3 | 第 4 条（最难一档） | `等待期点球 0s 内停下，剩余 4 轮一轮没开` + `S5DSMOKE repeats: 1 of 5 round(s) ran, stopped early (stop_ball), asked for confirmation every round` |
| L4 | 裁决 C 正向 + 钉 3 | `面板 1 次 / 跳过 2 次 / 跑满 3 轮`；`askEveryRound=false`；**留痕 2 条 = 跳过 2 条**（逐字含 `auto-confirmed by user's "Repeat without asking"`） |
| L5 | 裁决 C 负向 + 超时默认拒 | `不勾=第二轮照旧弹面板（共 2 次），零跳过（开关没有被默认打开）`；`S5DSMOKE no further rounds after round=2 reason=round_stopped` |
| L6 | 第 1 条"越界是拒不是夹取" | 四档 `101 / 61 / 1e2 / interval=0` 全部 `submit refused gate=REPEAT_FIELD` **且零执行回执** |
| L7 | 钉 1 下夹 0 | `1 秒档等待=0/2000/ms（下夹 0 生效，无负延时）` |

面板钮两格（L4/L5）都是**第 1 拍按屏量出 `(730,1365)` 命中**，零退死坐标、零补点：

```
# [L4] 面板按钮按屏定位=(730,1365) 量自 s5d-L4-panel-try1-114442.png（第 1 拍命中）
# [L5] 面板按钮按屏定位=(730,1365) 量自 s5d-L5-panel-try1-114512.png（第 1 拍命中）
```

L1 的屏面半边**如实没记绿**（脚本原话）：`L1 :: 屏上未见 run_report（报告在折叠线以下）→ 屏幕面交 ui-smoke 判，本格不记这条绿`。
单次 dump 看不见折叠线以下，"读不到"两向都不可信，所以那条缺席改由 U16h/U16i 的逐屏扫视判（见 §2）。

## 2. 背书轮：`ui-smoke.sh` run 3 · 51 PASS / 0 FAIL / 0 SKIP · RC=0

屏幕侧新格 U16a~U16j（住 ui-smoke 而不是设备冒烟脚本，因为只有本脚本的 `ui_sweep` 会逐屏翻到底）：

```
PASS U16a 重复执行三枚控件在屏（轮数框/间隔框/开关）
PASS U16b 英文范围提示与开关解释同屏（军令 1 条的 1-100/1-60 写在屏上，不只写在代码里）
PASS U16c 框内默认值 1 轮 / 5 秒（军令 1 条缺省档，逐字对上 RepeatPolicy.DEFAULT_*）
PASS U16d 开关默认未勾选（安全默认：不勾=每轮该弹还弹，裁决 C 的负向半边在屏上）
PASS U16e 越界轮数被拒留痕 :: S5DSMOKE submit refused gate=REPEAT_FIELD field=repetitions via=adb_inject repetitions=101 interval=null noAsk=false
PASS U16f 拒而未放：本轮零执行回执（脏数字没被夹取成 100 轮）
PASS U16g 拒因上屏（用户看得见『为什么没跑』）
PASS U16h 单发执行报告上屏（run_report 节点可见）
PASS U16i 单发屏上无 repeats 字段（v1.0.2 字节面不回归；扫视到底才敢判缺席）
PASS U16j 多轮收口写进屏上报告（repeats: 3 of 3）
```

U1~U15 全族同轮复绿（旧口径零回归），汇总行逐字：`ui-smoke: ALL PASS（含 0 条 SKIP）`。

## 3. 前四轮的账（假红假绿同罪，逐轮登记）

| 轮 | 结果 | 红的是什么 | 修法 |
|---|---|---|---|
| run1 11:18 | 预检终止 RC=2 | 预检**自己的读数器**错：`dumpsys` 那行是 `versionCode=4 minSdk=26 targetSdk=34`，整行抹非数字得 `42634` → 比对永假 | 只切 `versionCode=` 后那段数字 |
| run2 11:23 | `fail=1 passed=9` | ① `rounds()` 只锚 `S1SMOKE ok=`，把多轮每条读成 0（L2/L3 轮数=0）；② L2 墙钟从全局 `T0ALL` 起算＝"至少 N 秒"永真（假绿，先于设备发现）；③ 高危前置 `RID_SEARCH` 用了 `android:id/search_src_text`，avd34 实际是 `com.google.android.settings.intelligence:id/open_search_view_edit_text` → 面板永不弹；④ L6 四连"拒因没上屏"= 折叠线以下不可证伪 | 正则收 `round=N/M` 可选段；改每格 `cell_start/cell_clock`；换真实 resource-id 且前置判据升级为"同一节点既有 id 又有 `text="password"`"；L6 屏面半边删除、交 U16g |
| run3 11:33 | `fail=1 passed=12` | ① 面板检测器在**浅底页**三拍全 `no-two-button-band-found`（根因见 `find-panel-confirm-light-page-regression.md`）；② 面板计数恒 0——`logs()` 只过滤 `AnytouchRun`，而 `second-confirm panel shown` 由 `OverlayUi` 打在 **`AnytouchOverlay`**（`跳过=2 轮数=3 面板=0` 三数自相矛盾才暴露） | 检测器改"先圈面板矩形再在里面找"；`logs()` 收两个 tag；两修都先在 26 张历史实截上离线对拍 |
| run4 11:45 | **`fail=0 passed=15` RC=0** | — | 本文件 §1 即背书轮 |

ui-smoke 亦有一轮中止（run2 11:52）：U16 的 JSON 串被我写成跨行 `+` 拼接（bash 当命令执行），
`set -u` 在 590 行炸掉，U1–U15+U16a~d 已 PASS 但后半族未跑——该轮**不作背书**，改单行赋值后整本重跑（§2）。

## 4. 军令第 6 条"零改动"的可查证形态

本批 diff 只碰：`app/build.gradle.kts`（版本号）、`MainActivity.kt`（两框一勾 + `run_report` testTag）、
`AnytouchAccessibilityService.kt`（循环外壳 + 确认回调外包一层）、`RepeatLoop.kt`/`RepeatPolicy`（新增纯函数判据层）
与两份测试文件 + 测试通道脚本。**`NodeTaskRunner` / `HighRiskMatcher` / `HardcodedHighRiskRules` / `OverlayUi` 判定链 / 编译链一行未改**；
`RepeatWiringLockTest` 机械禁止判据漏进 `safety/ executor/ locator/ compile/`。

## 5. 边界照登（不洗）

- L2 的 `4000/4000` 与 L7 的 `0/2000` 只是**两次抽样**，±1s 抖动是均匀随机，样本落在端点上不落在端点上都不构成判据；
  "抖动真发生"由 JVM 侧 `RepeatLoop` 的纯函数用例钉，设备面只钉"等待时长落在 [间隔−1, 间隔+1] 且不出现负数"。
- 设备面全部在 AVD。真机上停止球响应、面板几何、Settings 节点 id 都可能不同（K40 本批零触碰＝这一档没有证据，账面不许写成已验）。
- "避免行为太机械被平台检测"这句军令措辞**未进任何对外文案**（钉 5）；对外只写"每轮之间等待你设定的秒数，带 ±1 秒随机抖动"。
- 高危格用的是 Settings 搜索框（点它不换页），不是真删除动作；这只钉"门禁与循环的时序"，不钉模板业务效果。

## 6. 静态面与构建面（同批对同一份源码）

- `./scripts/ci-local.sh` **RC=0**：`[1/4]` 四模块构建、`[2/4]` 全部单测、`[3/4]` 红线 A~I 全清
  （A 网络关键字 / B 队友工作区引用 / F 网络代码只准住在 `byok/` `tools/` / G 执行路径看不见编译器模块 /
  H 无含 Key 日志 / I 无明文凭据存储通道）。
- **JVM 计数口径＝干净重跑 + 单变体分模块 XML**（禁目录 glob 求和）：
  `app/testDebugUnitTest` 32 文件 **364** + `byok/test` 8 文件 **69** + `core/contracts/test` 2 文件 **19**
  ＝ **452 例，failures=0 errors=0 skipped=0**（v1.0.2 账面 435 → 本批净增 17，全为 S5-d 判据层与接线锁）。
  `:tools:compiler:test` 无 test-results 目录（该模块无 JVM 用例，口径与 v1.0.2 一致，不计入）。
- **重打=同一字节**：CI 段 `:app:assembleDebug` 之后再取 md5 与装机件逐字相同
  （`93b284d48fa52c2daa01b5ff6517e985` / 9,335,775B，构建时刻 11:29 未变＝gradle UP-TO-DATE），
  所以 §1/§2 的设备证据与本批发布件（v1.0.3 Release 挂的那一份）是**同一份 APK**，不存在"验的是 A 包、发的是 B 包"。


## 7. `device-smoke.sh` 回归轮（同一份 APK 的另一块设备面账）

**背书轮**：宿主 14:30:48 起跑 · 15 格全绿 · `device-smoke: ALL PASS` · `DEV_RC=0` ·
raw `evidence/S5/raw/device-smoke-20260925-143048-green.raw.txt`（设备时钟读数 06:30~06:33）。
同型上一轮 14:25:34 亦全绿 RC=0＝**两连绿**（raw `device-smoke-20260925-142534-green.raw.txt`）；两连之间只改过 C7 的 FAIL 分支（追等终回执，见 §7.3），
PASS 路径零改动。跑前对账：机上 `base.apk` md5 = 本地构建 = `93b284d48fa52c2daa01b5ff6517e985`。

本批动过的格逐字读数（其余格为 v1.0.2 旧口径同轮复绿）：

| 格 | 钉什么 | 逐字 |
|---|---|---|
| C3 | 执行器能否写进自家 Compose 目标 | `S1SMOKE ok=2 total=2 stopped=false stop=-` |
| C3b | 本格零残留出格 | `屏上 repeat_count = [1]` |
| C5 | 高危无人应答=15s 超时默认拒 | `ok=2 total=3 stopped=true stop="PASSWORD:password"` |
| C5p | 面板**真的弹过**（回执说不出这个） | `rule=PASSWORD:password，共 1 次` |
| C6 | 执行中点球即停（面板不在场的对照格） | `实测球心(1006 1264) 3s :: stop="user_stop"` |
| C7 | 面板挂起期球不被面板吞 | `面板在第 3s 挂起，实测球心(1006 822) 点后 4s :: stop="user_stop"` |
| C7x | 输入通道洁净（自家 `input tap` 不经 /dev/input） | `触摸 0 次 = 预算 0` |

### 7.1 C3 的六条设备事实（脚本 :161-177 括注点名的落账处）

1. **高危探针读的是目标节点当场的 `text`**（`NodeTaskRunner.kt:215-221` 用 `hit.node.text` 造探针）。
   这格原先打 `task_input`，而任务框会留着任何一批装载过的模板 JSON → C3 被那张 JSON 里的词判高危，
   没有人在场答面板 → 15s 默认拒。**产品是对的（fail-closed），红的是测试通道欠前置。**
2. **harness 不许自带第二份高危词表**：照 `HighRiskRule.kt:47-48` 抄的那份判 discord 模板"零命中"，
   产品当场按 `SEND:send` 拒了它（词表还有 SEND/PAY 整类）。判据只有一份。
3. **"把任务框清成真空"实测走不通**：`task_input` 落点在 dump 里不可信（按其 bounds 落指，字符进了
   `repeat_count`；连发 DEL 字数纹丝不动），而收键盘要用的 BACK 一收就连带退出自家窗。
4. **`type_text` 是整段替换不是追加，走 `ACTION_SET_TEXT`**——不需要焦点、不需要键盘
   （`repeat_count` 实测 "1"→"X"→空→"1"，回执逐字 `ok=1 total=1 stopped=false`）。靶因此换成一枚
   屏上文本天然干净的自家 Compose 框。
5. **进格逐字读原值、出格写回并在屏上复核**（C3b），复位步恒下发（含"原值本就是空"这一路：
   `input:""` 是能落地的整段替换），所以期望恒为 `ok=2 total=2`。
6. **"节点不在树里" ≠ "节点文本是空的"**：折叠线以下的 Compose 节点未合成就不进无障碍树；
   前置必须先滚到它进树（预算 24 格 + "两次节点集相同=到底"判定），把前者当后者读就是假绿。

### 7.2 C5 换靶：这台 AVD 的 Settings 搜索索引死了（环境事实，不是产品事实）

- 04:44 那轮起，`com.google.android.settings.intelligence` 的搜索对**任何**查询只回 "No results"；
  旧链第 3 步点的 "Passwords & accounts" 是索引活着时 ROM 自带的行，此后拿不到。
- 恢复尝试三条全失败（如实登记，未愈）：① `pm clear com.google.android.settings.intelligence`；
  ② 强跑 AppSearch 索引 job（`cmd jobscheduler run -f` 报 job 不在册）；③ 整机 reboot（240s 沉降，
  期间 System UI 与 `com.android.phone` 各出一次 ANR，无障碍绑定存活）。
- 新靶不再要 ROM 送词：第 2 步把 `"password"` 整段替换进搜索框，第 3 步点这枚**外部 App 节点**，
  门禁读它当场的 text → 命中 PASSWORD 词表 → 面板 → 默认拒。C5/C7 共用单一真源 `$RISK_CHAIN`
  （两格曾各自抄一遍，抄到 C7 在索引死后静默退化成"定位轮询期点球"＝C6 的活儿，还记了绿）。
- 边界：本格钉的是"门禁与超时默认拒的时序"，**不钉 ROM 搜索索引可用性**；真机两档索引状态本批零证据。

### 7.3 C7 的账：先假绿、后两连假红、最后才真绿（假红假绿同罪，逐轮登记）

| 轮 | 现象 | 归因 | 状态 |
|---|---|---|---|
| 上午~午后多轮 | 记绿 | **假绿**：索引死后面板根本没弹，点球停的是定位轮询（C6 的判据）。修法＝前置判据必须看到 `second-confirm panel shown`（≥1 次），前置不成立直接红，不许记绿；同时加 C5p 计数格 | 错账已自纠 |
| 13:36（raw `...-133609-c5c7fail`） | `FAIL … 实际 [12s, ]` | 写死 `BALL_TAP=(1002,1272)`：当刻球在输入法顶起后的 frame `[957,765][1056,879]`，这一点落在输入法区＝空点 | 假红（测试通道） |
| 14:09（raw `...-140944-c7fail-paneltap`） | `FAIL … 实际 [12s, ]` | 已改"现读框心"，但**读的是面板的框**：执行期自家包同挂两个 overlay 窗，dump 里面板块排在小球前；旧 awk 的复位正则 `/^[ ]+Window\{/` 永不匹配（真实行是 `  Window #N Window{…}`）→ 块标志粘连 → 首条 `Frames:` 取到面板 `[120,912][960,1487]` → 点进面板正中 `(540,1199)`。面板没被理、15s 后才默认拒，与"回执为空"逐字吻合 | 假红（测试通道） |
| 14:25 / 14:30 | `ALL PASS` ×2 | `ball_xy()` 改按"每个 Window 块"重算归属 + 宽高双阈值(≤200) 排除面板/Activity 窗；读不到窗才退 `BALL_TAP` | 真绿 |

- 修后命中率实测（一次性探针 `.smoke-tmp/c7probe3.sh`，直接 `eval` 脚本里的 `ball_xy` 本体＝零漂移；
  面板挂起态**首点**）：`1006 1264` ×4 + `1006 822` ×1 ＝ **5/5 HIT**，每次 ≤2s 出 `stop="user_stop"`。
- 两连红的次生弱点已补：C7 判据窗口 10s 短于面板 15s 默认拒 → 红项说不出归因。现在 FAIL 分支追等终回执
  并打印现读球心（只动红项话术，判据与 PASS 路径一字未改，两连绿之间即此一处改动）。

### 7.4 产品侧观察（上报，本批不改代码）

急停球停靠位**不固定**：同一条链实测三枚 frame——无面板 `[901,1213][1068,1314]`、面板挂起
`[957,1207][1056,1321]`、输入法顶起后 `[957,765][1056,879]`（Δy=442px）。用户"记住球在哪"再伸手会落空；
球本体始终可见、始终可点，超时默认拒的 fail-closed 不变。军令第 5 条＝急停/门禁零改动，故只登记不动；
若要改需另裁（牵动 `OverlayUi` 的 gravity/IME 策略，以及 K40/K80 既有急停实证坐标锚——那两处锚在
`OverlayUi.kt:60-61` 的括注里被明确当作证据用）。

### 7.5 本轮环境与口径账（不洗）

- 三台设备同时在线（`7ae4bfee`=K40、`emulator-5554`、`emulator-5556`）；全程 `ANDROID_SERIAL=emulator-5554`
  定向，除 `adb devices` 只读列举外**对 K40/K80 零指令**（真机本批零触碰的账面不变）。
- 计时轮次期间未并行 Gradle；`bash -n` 通过后才放跑；脚本运行期未编辑该脚本（两处修改都在跑完之后、下一跑之前）。
- `.smoke-tmp/` 下 `c7probe.sh` / `c7probe2.sh` / `c7probe3.sh` 是一次性探针，不入回归资产。
  探针 #3 第一版忘 `logcat -c`，前两条读数把上一轮的旧回执当成了本轮的（作废）；补 `logcat -c` + 轮间隔 18s 后才作数。
  教训登记：**harness 清 logcat 的时机与被测回执窗口必须成对**，否则"读到东西"本身就会骗人。
- 面板是否真弹只认 `AnytouchOverlay` tag（§3 run3 的账：`logs()` 曾只过滤 `AnytouchRun` → 面板恒 0）。
