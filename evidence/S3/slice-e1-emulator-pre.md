# S3-E1（切片 E 的模拟器半边）：面板屏上形态 + 无凭据三档必拒 + 飞行模式对拍

批次：S3-E1（E 系列第一格；E-key 那一半移交老板手指，见 §8）
日期：2026-09-23　靶：`emulator-5554`（Android 14 / sdk_gphone64_x86_64 / 1080x2400）
HEAD 起点：`9e8958a` + `4c10ab0`（S3-E0 两笔），动工前 `git status --short` 为空
raw：`evidence/S3/raw/byok-smoke-emulator-e1.log`（S0 环境 / S1 首轮两条假红 / S2 探针 / S3–S5 三轮定版 / S6 飞行模式 / S7 门禁与账面）

---

## §1 一句话

新开 `scripts/byok-smoke.sh`（E 系列），把切片 D/E0 攒下的"只能上机判"的账**分成"零凭据就能判"和"必须有真凭据才能判"两组**：
前者 25 条在模拟器上全绿（含**裁决 S3-R4-1 的互斥残留半边**——被拒的那次编译没把 `compileBusy` 留在持有态，
开录立刻可用），后者 6 组一律大声 SKIP 不记 PASS；
另把军令裁决-1 里"飞行模式下 device-smoke 全绿"这条机器锁第一次跑出行为证据：**13/13 全绿，RC=0**。
本批**产品代码一行未改**。

## §2 文件账（改了什么，一眼可核）

| 文件 | 动作 | 为什么 |
|---|---|---|
| `scripts/byok-smoke.sh` | 新增 | E 系列断言。与 C 系列（`device-smoke.sh`）、U 系列（`ui-smoke.sh`）分账：混编会把"新面未验"混进旧基线 |
| `evidence/S3/raw/byok-smoke-emulator-e1.log` | 新增（只追加） | 四轮 byok-smoke + 探针 + 飞行模式 + 门禁 raw |
| `evidence/S3/slice-e1-emulator-pre.md` | 新增 | 本文件 |
| `STATUS.md` / `orders/METRICS.md` / `orders/ANYTOUCH-S3-byok-ORDER.md` | 记账 | 阶段面板 / 纪律账 / §5.1 切片行 |
| `app/**` `byok/**` `core/**` `executor/` `locator/` `safety/` `service/` | **一行未改** | 裁决 §3-8 的"执行器零改动"继续成立；E-pre 不需要动判据 |

## §3 断言账（25 条跑过 / 6 组跳过，全部来自 raw）

| 断言 | 锁的是哪件事 | 判据原本住在哪儿 |
|---|---|---|
| E1a–E1k | BYOK 面板九格 + 折叠线以下两个录制入口**滚得到**（`ai_intent/ai_compile/ai_ctx_switch/byok_base_url/byok_model/byok_key/byok_save/byok_clear/byok_key_tail` + `record_start/record_stop`） | 纯 Compose 形态，JVM 覆盖 0 |
| E1l | 凭据状态行是 either/or 两档之一（读到第三种=串了） | `ByokPanel` 单源文案 |
| E1m | 未配凭据时屏上**没有**地址回显格（不留"看起来还配着"） | `ByokPanelState.onCredentialsWiped` 同律 |
| E1n | 空闲不挂陈旧结论格 → **SKIP**（§4-F3） | `ByokPanelState` 那条 JVM 例已锁"新一次开始撤旧的" |
| E1o | 整页无 Key 形态字面（24+ 连续字母数字段=0） | 军令 §3-3 |
| E2a | 无凭据即拒且走的是**独立档** `gate=NO_CREDENTIAL` | `ByokPreflight.check` |
| E2b | 被拒不写账：`compile ok` 与 `S2SMOKE-TASK` 计数逐字不变 | `ByokCompileController` 任何一档不通都不落账 |
| E2c | 拒因上屏（错误必显示，静默禁用=黑洞） | L2-③ |
| E2d | 屏上那句 = 日志那句（取 `detail=` 整句，比对前 24 字节全等） | 话术单源 `ByokPreflight.copyOf` |
| **E2e** | **被拒的编译不许把互斥留在持有态**：紧接着开录立刻放行（`record start target=`） | `AppState.compileBusy` + `begin/endCompile`（E0 §7-3 那颗残留雷的设备半边） |
| **E2f** | 互斥释放后录制面本身照常转（停止并编译走通=空账清零，不是被拒） | `stopCompileGateOf` |
| E3a/E3b | 空意图独立档 + 两档各计各的（一档不许冒充另一档） | `ByokPreflight` 判序 |
| E4a | **执行中拒编译**（注入通道绕过置灰同样被拒，`gate=RUNNING`） | `ByokPreflight.check(running=…)` |
| E4b | 执行中那一次一个字都没发出去（`compile ok` 计数=0） | 裁决 §3-1 出门前门禁 |
| E4c | 本轮执行正常收尾（`S1SMOKE ok=0 total=1 stopped=true`） | 既有执行器语义 |
| E5/E8/E9/E10/E11 | **全组 SKIP**：真 HTTPS、编译期双拒与过期边、真 Keystore 读回、零条上行、清除双槽 | 需要本机已保存可用凭据（§8） |

SKIP 的判定不是"跑不动就算了"：脚本**先从屏上读凭据状态行**（`本机未保存 Key` / `本机已保存 Key：***NNNN`），
读不出 either/or 就直接终止（未知状态既不判过也不判不过），读到"未保存"才逐条记 SKIP。

## §4 三条设备事实（比断言本身值钱，都只能上机拿到）

**F-1　Compose 的 `testTag` 在 `uiautomator dump` 里是**裸** `resource-id`**：`resource-id="ai_intent"`，
不是 `com.anytouch.app:id/ai_intent`。系统节点的 id 含 `:` 与 `.`，因此 `resource-id="[a-z][a-z_0-9]*"`
这一条正则就足以把"我们家的格子"和别人的分开——`harvest_page` 的"无缝"判定就是靠它取节点集。

**F-2　dump 只含当前视口 ± 缓存，且滚动落点不可预测。**两半各有实测：定版前那轮（raw S1）扫了 8 屏，
`byok_save`、`byok_clear` 都拿到了，**偏偏紧邻其下的 `byok_key_tail` 一轮都没进过任何一屏**——这就是"屏与屏之间有缝"；
探针 P1（raw S2）页面停在下部，同一份 dump 里 `record_start` 命中=0
而 `byok_key_tail` 命中=1——**同一格"读不到"既可能是"没渲染"也可能是"没滚到"**，光看一次 dump 分不出来。
探针 P2/P3：同一坐标同一距离的拖动，250ms 一次滚了一次没滚（那一次"没滚"是因为**已经到底**）
→ 对照组 P4 用同样 250ms 在 Settings 能滚，说明输入通道没坏，坏的是"拿落点当覆盖"这件事。
所以"屏上没有"这类断言（E1m/E1o/E11c）在这轮起被两条机器锁住：
① **到底** = 连续两屏读数逐字相同；② **无缝** = 相邻两屏至少共享一个自家节点。
任一条不满足 → 缺席类断言直接判"读数不可信"（红），而不是判"屏上没有"。
这正是首轮那两条假的"面板缺格"红项的根因（见 §5-Z1）。

**F-3　常驻服务型进程 `am kill` 收不走**：HOME + `am kill` 之后 pid 不变（无障碍服务 + 悬浮窗让它永远
不算 empty）。这本身是产品事实（进程常驻，雷 7 家族），但代价是："换新进程后屏上不挂旧结论"这类断言
在测试通道里做不了——唯一办法是 force-stop，而测试通道禁 force-stop
（雷 13：force-stop 会**清空** `enabled_accessibility_services` 条目，不是"停一下"）。
→ E1n 记 SKIP，真机首装后"第一次进面板"那一次顺带覆盖（那次天然是新进程）。

## §5 主窗自纠三条（写下来才算修过）

**Z-1（脚本假红，且第一次修法是错的）** 首轮 E1i/E1n 两条红把 `byok_key_tail` 报成"面板缺格"。
我第一次的修法是"把拖动时长从 250ms 改成 500ms"——**这是在用参数掩盖机制不明**。
复测探针（raw S2）当场打掉这个说法：同一坐标 250ms 一次滚了一次没滚，500ms 那次"没滚"的原因是**已经到底**。
定版不再押时长，押可测终点（到底 + 无缝两条锁，§4-F2）。
教训入纪律：**快拖带惯性的滚动不能作为"读全页"的证据；缺席类断言必须自带"读到了底"的机器锁。**

**Z-2（措辞与实测不符）** E2d 起初写"屏上话术与日志话术**逐字**同源"，实际比的是前 24 字节。
标签已改成"整句取日志 `detail=`，比对前 24 字节全等"——不是 24 字节以外都验过了，就别写"逐字"。

**Z-3（一条还没上真机就先拆掉的假绿）** E8c 起初数的是 `S2SMOKE record start`，
而**被拒行本身就以这一串开头**（`S2SMOKE record start refused gate=COMPILING`）→
真机上这一条会把"被拒"数成"开录成功"，即 E8c 永远红（或更糟：改了判序之后永远绿）。
现改成数**成功行的字面** `S2SMOKE record start target=`。E8 整组本轮 SKIP，但雷已经在纸上拆了。

## §6 飞行模式对拍：裁决-1 那句"机器锁"第一次有行为证据

军令 S3 裁 1 原文（`orders/ANYTOUCH-S3-byok-ORDER.md` §0 表第 1 行）：
"另加机器锁：执行路径禁 import 编译模块 + **飞行模式下 device-smoke 全绿**"。
此前只有结构锁（红线 F/G/H/I），行为半边一直欠着。本轮：

```
airplane_mode_on=1 → ping 8.8.8.8: connect: Network is unreachable
bash scripts/device-smoke.sh → C1 C2 C3 C4 C5 C5x C6 C7 C7x C8 C8b C9 C10 = 13 PASS，DEVICE_SMOKE_RC=0
airplane_mode_on=0 → ping 8.8.8.8: 2 packets transmitted, 2 received（同一对探针反向自证"刚才真断过网"）
```

断/复网探针**双向都录**（raw S6）：只报"断网时全绿"而不证探针自己能失败，就等于拿一把没校过的尺子量。
这条证据的效力边界要如实说：C 系列走的是执行器/录制/门禁路径，**断网下全绿只证明"执行期不需要网络"**，
不证明 BYOK 编译链在断网下会怎么说话（那一档属 E-key 的 UNREACHABLE 话术，仍待真机）。

## §7 诚实边界（不许洗）

1. **模拟器 ≠ 真机**。老板点名的四个设备点里，本批只证到第 1 个（面板屏上形态）在无凭据那半边；
   **真 HTTPS、真 Keystore（硬件背书的读回/清除双槽）、词表真采几条、编译期双拒**四条**一条都没证**，
   原文 6 组 SKIP 在 raw 里逐条可查。脚本对 `ro.product.model` 打模拟器旗标，任何结论不得写成"真机已过"。
2. **E4 证的是外层门禁**。`ByokPreflight` 的 `RUNNING` 档排在凭据之前，所以无凭据也能量；
   而唯一写账口 `RecorderStore.acceptModelActions` 里那道 `model ledger refused gate=RUNNING` 是**内层纵深**，
   只有真产物能触发（要过预检=要真 Key）→ 仍属 E-key。两层的 JVM 覆盖：外层=有（纯函数），内层=0（object + Log）。
3. **屏上"置灰"本批不判**。Compose `enabled=false` 在 dump 的 `enabled` 属性里可读，但灰不置灰只是提示
   （门禁在入口），本批判的是"注入通道同样被拒"——把视觉当门禁是 S2 就付过学费的口径。
4. **E1o 只覆盖屏上**。日志侧的 Key 形态由红线 H + 本批 diff Key 形态扫描管（raw S7）；
   证据文件自身也在这条范围内：本批所有 raw 里出现的 Key 相关字面只有 `***` + 尾 4 位这一种 sanctioned 形态。
5. **没做人工截图取证**。面板的视觉规范（V 系列）不在本批账上：现在只证明"格子在屏上、话术同源、
   被拒有声音"，不证明"这块面板长得像产品"。待办 13②（整页变高、首屏放不下）仍未裁。

## §8 交给老板的动作（E-key，四件事一次跑完）

1. 接上 K40（或任何一台真机），装本轮 debug APK，开无障碍（小米系两开关：USB 调试（安全设置）+ 调试应用白名单，雷 14）。
2. **在手机上用手**填：服务地址（https 的 OpenAI 兼容端点）/ 模型名 / API Key → 点「保存」。
   脚本全程不下发 Key、也不读 Key 框（裁决 S3-R4-2），它只下发意图与读数。
3. `ANDROID_SERIAL=<serial> bash scripts/byok-smoke.sh` → E5~E11 自动接管。
4. 预算如实报：**这一组要打两次真请求**（E5 一次出账与词表条数、E8 一次量秒级互斥窗口），
   裁 4 原文是"一次真机真 Key"，多出的这一次是为了量裁决-1 的互斥，需老板点头（不点头就把 E8 段跳过）。
5. `E_WIPE=1` 才会跑 E11「清除双槽」——它会真删掉屏上那份凭据，删完得再手输一次，所以默认不动。
