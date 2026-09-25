# S5-e 收尾三功能 · 设备面终局账（v1.0.4）

日期：2026-09-25　批次：S5-e（老板 S5-R12 三裁 + 开工令"直接开 S5-e 工单书开工，写完发 v1.0.4"）　主窗：Qoder-Lead
工单：`orders/ANYTOUCH-S5e-final-three-features-ORDER.md`（军令 6 条 + 执行面判据 1–10 / 不做项）

## 0. 一枚字节贯穿全批（跑前对账）

| 项 | 值 |
|---|---|
| 发布件 | `app/build/outputs/apk/debug/app-debug.apk` |
| 大小 / md5 | 9,356,437 B / `eb4a1504b5373357a0773f838fee3da6` |
| 版本 | versionCode 5 / versionName 1.0.4（`dumpsys package` 当场读出，逐字进各脚本 raw 头） |
| 构建时刻 | 09-25 17:29；此后 `find app/src/main byok/src/main core -newer <apk> -name '*.kt'` **空集**＝机上包＝当前源码 |
| 靶机 | `emulator-5554`（AVD MarvisPhone，API 34，1080x2400）；**K40 / K80 本批零触碰** |

下面所有设备面判据（含 v1.0.3 旧账复绿）都在这一枚字节上跑，中途未重打、未换包。

## 1. 三裁落点（裁在账上，不在嘴上）

| 老板裁决（S5-R12 原文要点） | 落点 | 本批实证 |
|---|---|---|
| ①错误重试只碰非高危步骤，高危步骤不自动重试，每次高危照旧弹确认 | `StepRetryPolicy`（白名单三条失败可重试 / 高危档 `retryCeiling=0`） | F3b 高危步零 `step retry` 行；F6a 非高危恰 2 条（`retry=1/2`、`2/2`）；F8b 急停后额度当场归零 |
| ②"我的任务"走同一个现有落账口，不另起存储 | `SavedTasks.kt` + `AndroidSavedTaskDisk`（filesDir/saved_tasks）+ 唯一 `submitTask`/`acceptModelActions` | F9a 存的正是那本账；F10a `origin=saved` 走同一 `acceptModelActions`；F10b 载入即执行仍走那同一条派发口；F15/F16 载入不绕任何一档门禁 |
| ③手动补步骤第一版只允许节点/输入/等待类，不开放坐标点击入口 | `ManualStep` 类型形状（根本没有坐标格）+ `manualInsertableTypes` 三条白名单 | F2 产物四条 `target` 全 null、零坐标样键；F9b 盘上件同判；F14c 面板逐屏无坐标输入位；F14b 屏上明写"按节点树找目标，这里没有 x/y 可填" |

军令原句里"任务最开始预弹一次"与"填坐标"两处已被上面三裁逐条覆盖（军令原件不回改，覆盖关系在册）。

## 2. 判据 1–10 对号（EXECUTION §3）

| # | 判据 | 落在哪几格 | run6 读数 |
|---|---|---|---|
| 1 | 重试额度 ≤2 次重试 / 总尝试 ≤3 | F6a/F6b/F6c | `retry=1/2`+`2/2` 恰 2 条；`stop="NODE_NOT_FOUND"`；本格墙钟 48s ≥ 3×15s 定位窗（计数不是空转） |
| 2 | 高危步绝不自动重试 | F3b | `step retry` 计数 0 |
| 3 | 存→冷启→载入→执行整链 + 删除后盘上双缺席 | F9a/F9c/F9d/F10a/F10b/F11a/b/c | `op=save ok steps=4` → 换 pid → `op=load ok steps=4` → `ok=4 total=4 stopped=false` → `op=delete ok remaining=0` → 再载必拒 `NOT_FOUND` → 盘上件真没了 |
| 4 | 载入这条来路不绕门禁 | F15/F15b（RUNNING 档）、F16/F16b（UNSUPPORTED_TYPE 档） | `op=load refused gate=RUNNING` 且本轮零条 `op=load ok`；`gate=UNSUPPORTED_TYPE … type=swipe` 且落账口零条 `written` |
| 5 | 手动插步回放同形 + 越界拒 | F1a/b/c、F4a/F4b、F13 | `ok=insert index=3 before=3 after=4` → 回放 `ok=4 total=4`；五档拒因各一条且脏请求零放行；换进来的账仍可插（同一写口同一区间口径） |
| 6 | 手填高危文字照样弹 | F3a/F3c | `second-confirm panel shown` → 未获确认按现行 `stop="PASSWORD:password"` |
| 7 | 禁坐标可证伪（不是"嘴上说没有"） | F2 + F9b + F14c + JVM 结构锁 | 见 §1 第三行；四面同判 |
| 8 | 新 UI 文案全英文 | `ui-english-sweep.sh` 段 3 | 见 §3 |
| 9 | 旧账不回归（同一份 APK 字节） | C / U / L / Photos 四族 | 见 §3 |
| 10 | 发布账 | Release + 匿名回对 | 见 `orders/METRICS.md` S5-e 段（先建后记） |

**终局干净轮 run6**：`通过 36 / 跳过 0 / 失败 0`，脚本尾行 `s5e-final-three: ALL PASS`，`SCRIPT_RC=0`（直接重定向取码，不走管道——F-8 在册）。
原文：`evidence/S5/raw/v104-s5e-run6-20260925-191711.raw.txt`（控制台）＋ `evidence/S5/raw/s5e-final-three-20260925-191711.raw.txt`（逐行 logcat 与机器态）。

## 3. 旧账不回归（全部同一枚字节，时间戳在册）

| 族 | 时刻 | 读数 | 原文 |
|---|---|---|---|
| device-smoke（C 系列 15 格） | 17:30 | `device-smoke: ALL PASS` | `evidence/S5/raw/v104-regress-device-C-20260925-173049.raw.txt` |
| ui-smoke（U 系列全族） | 17:47 | `通过 50 / 跳过 1（前置未立）`，`ui-smoke: ALL PASS` | `…/v104-regress-ui-rerun-20260925-174720.raw.txt`（run1 五红那份一并留着：`…/v104-regress-ui-20260925-173602.raw.txt`） |
| s5d L1~L7（重复循环旧账） | 17:59 | `RESULT fail=0 passed=15` | `…/v104-regress-s5d-L-20260925-175922.raw.txt` |
| Photos 全链 5 轮 | 19:25–19:35 | `RESULT fail=0 passed=13 rounds=5`（逐轮 `ok=9/9` + 篓内外双真空） | `…/v104-photos-20260925-192511.raw.txt` + 五份 `s5a-emulator-5554-photos-round{1..5}-*.log` |
| ui-english-sweep（含新段 3） | 19:24 | 段 1 home 4 屏 / 段 2 ledger 7 屏 / **段 3 insert 8 屏**，`distinct on-screen strings = 85`，**CJK=0**，`UI-ENGLISH-SWEEP PASS` | `…/v104-regress-ui-english-sweep-20260925-192412.raw.txt` + dump/截图目录 `evidence/S5/raw/english-sweep/20260925-192412/` |
| 红线 A–I | 19:38 | `SKIP_GRADLE=1` 九条全 clean，`ci-local PASS`，RC=0 | `evidence/S5/raw/v104-ci-redlines-20260925-193844.txt`（九条逐字原文即在该件内） |
| JVM 干净重跑 | 19:40 | `--rerun-tasks` 全量：**app 427 / byok 69 / contracts 19 / :tools:compiler 0 = 515**，fail=0 err=0 skip=0（分模块 `testsuite` XML 自加，单变体口径） | `evidence/S5/raw/v104-jvm-rerun-20260925-194015.txt`（尾行 `BUILD SUCCESSFUL in 48s`、`32 actionable tasks: 32 executed`） |

段 3 是本批新加的一条腿：面板只能由屏上那一枚 "+ Step" 点开（注入通道不开面板），v1.0.4 之前这一层的上屏文案从未进过 dump。

## 4. 测试通道账：本批五把假红，逐把归因后才改判据（红记通道，不记产品）

| 轮 | 现象 | 归因 | 改法 |
|---|---|---|---|
| run1/run2 | F14a 读不到 "+ Step" | 折叠线以下压根不进无障碍树，只在页顶 dump 一次 | `scroll_until`（回顶六指 + 逐屏找） |
| run1 | F8a/F3c 断言不命中 | 脚本里的英文字面量与产品 anchor 不一致 | 逐字对 anchor 改判据文本 |
| run3 | F14b 面板展不开 | **`input tap` 落点比 dump 报的格心低约 85px**（四档实测同一偏量；View 面 Settings／悬浮球无此现象）＝注入通道与 Compose 命中层对不上 | 新增 `tap_open`/`tap_close`：按偏量档梯点，**命中一律由产品自己的状态变化验**（面板 testTag 上屏才算点亮），四档点不亮记通道红 |
| run4 | F3 前置读不到 `text=password` 的格子；F9c "重装后 pid 仍是 []" | 同一 85px 病（前置裸点侥幸中过两轮，这轮没中）；F9c 是把 `install -r` 后 `pidof` **读空**当成了失败——读空正是进程真被收走的正证 | F3 前置改走 `tap_open`（命中判据=搜索框节点进树）；F9c 先录空档、再显式拉起取新 pid，旧/空/新三枚 pid 全上账 |
| run5 | F14b 面板点亮却读不到那句英文 | 点亮的是第 0 行那一格，**面板比剩下的屏幕高**，那句英文和 Cancel 都在折叠线以下——只读点亮那一帧读到的是半张面板 | `panel_read`：逐帧取并集，但**每一帧都必须还看得见 panel 标记**（面板一收当场停手，绝不把面板不在的屏混进证据） |

**本批最值得留的一条过程事实**：自家 Compose 面的"手指点击"在 S5-e 之前**从未被任何设备面批次证明过**——历轮设备判据要么走 adb 注入 extras，要么点 View 面（Settings、悬浮球）。所以"点了没反应"从来不可能是产品缺陷的证据，只是通道未被证。这条已同步进脚本注释与协作记忆。

修复一律**只加不减**：五把假红全部改的是测试面（`scripts/s5e-final-three-smoke.sh`、`scripts/ui-english-sweep.sh`），产品代码在 run2 之后一行未动（`git diff` 可验：本批设备面阶段改动只落在 `scripts/`）。

## 5. 已知暴露与边界（照登不洗，不擅动）

1. `wait ms` 手动步未设上限：编译面本就接受任意非负 ms，手动面另立上限＝同一本账第二份口径 → **待裁**，不写进"已规避"。
2. 空账不给 "+"（`EMPTY_LEDGER`）：三裁③给的是"补步骤"，从零手搓一整本不在第一版；注入通道送进空账同样落 `EMPTY_LEDGER`。
3. `F5 :: 按球后未读到 USER_STOP 行` 间歇出现（run4/run6 有、run5 无）：只是机器态提示，不参与该格判据；归因＝按球与任务自然收口同窗竞争。
4. 主窗探针副作用披露：为归因 85px 而做的 y 扫描曾误载 Discord 模板、往 `step_rename_field_2` 打进垃圾字符串、把面板留在开启态——全是内存账，已由 `reset_ledger` 双注入重编复原（`compiled ok actions=3` 在册），产品资产未动。
5. `a5f1a63`（待办 1 勘误回禀）此前账面称"已在盘/已推送"＝**非盘事实**：本仓 `git cat-file -t a5f1a63` 当场 `fatal: Not a valid object name`，那枚短哈希在这条历史里根本不存在，不是"没推"而是"没有这个件"。当前真实未推数是 3 枚（`fb5fe20`／`2a739dc`／`2fa37c1`，`origin/main` 停在 `a1d7166`），本批随 `main` 一并推齐。
6. K40（`7ae4bfee`）19:24 后从 `adb devices` 掉线：本批对 K40/K80 零指令、零触碰，掉线与任何判据无关（真机批欠格照旧在册：#50、byok-smoke 7 行）。
7. 范围外不变：keystore 正式签名版前回炉、Gumroad 上架动作归老板、买家件"未经母语校对"、v1.0.0~v1.0.3 不回改、S4 额度 6/8 定格未动、本批真模型请求零消耗。

## 6. 一句话结论

三裁逐条落到产品形状上并被设备面读数证住（重试一本账／落账口一份／坐标在类型里就写不出来），十判据除"发布账"外全绿且旧账同字节复绿；剩下的账面动作只有一件：把这枚字节发成 v1.0.4 Release 并匿名回对。
