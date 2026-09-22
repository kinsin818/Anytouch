# T3-04 AVD 三档矩阵结案档（API 31/34/35 + MarvisPhone 基准）

> 口径=老板裁决 T3-R4（2026-09-21）：第一版成功率验证=既有真机（K40/K80）+ AVD 三档矩阵，不采购新真机。
> 结案时间：2026-09-22（设备时钟）。整轮输出见 `device-smoke-output.txt` 末段"AVD 三档矩阵收口轮"。

## 0. 结论

最终构建（扫树自证 + false 边重派 + 测试通道就绪轮询）下，四台全量 smoke **11/11 ALL PASS**：

| 实例 | 镜像 | 结果 |
|---|---|---|
| avd35 (emulator-5556) | API 35 | ALL PASS ×3（含收口轮） |
| avd34 (emulator-5558) | API 34 | ALL PASS ×2 |
| avd31 (emulator-5560) | API 31 | ALL PASS ×2 |
| MarvisPhone (emulator-5554) | API 34 Google APIs | ALL PASS（重启后） |

JVM 单测 122 全绿。军令三查不破：落字复核仍是终裁（扫树只是换"读哪份活树"，不是放宽判成）；
重派只发生在 performAction=false 边（false=设备明示没执行过，零副作用）；重试各边封顶一次。

## 1. 环境与搭建

- 镜像：`system-images;android-31/34/35;default;x86_64`（avd31/34/35），MarvisPhone=旧 API 34 Google APIs。
- 无头启动（ci-local 红线，桌面零窗口防误点）：
  `emulator -avd <名> -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect`
  注：`-quickboot-options none` 在本机 emulator 37.1.11 非法，已弃。
- SettingsIntelligence 版本：avd34/5554=1.1.0.536057574.sr，avd35=1.1.0.638941663.sr（搜索页独立进程）。

## 2. 分档参数（矩阵设备命令的 env 覆盖）

| 档 | RID_HOME | TXT_PASSWORDS | 备注 |
|---|---|---|---|
| avd31 | `com.android.settings:id/main_content_scrollable_container` | `Password` | API 31 首页容器 `settings_homepage_container` 不可滚（scrollable=false，第 12 雷族）；结果词条是 "Password" |
| avd34 | 默认 | 默认 `Passwords & accounts` | |
| avd35 | 默认 | `Password` | 结果页同时有 "Passwords, passkeys & accounts" 与 "Password"，取短者（L2=trim 全等，非 contains） |
| MarvisPhone | 默认 | 默认 | |

完整命令形如：
`ANDROID_SERIAL=<serial> [RID_HOME=… TXT_PASSWORDS=…] bash scripts/device-smoke.sh`

## 3. 本批新发现（按归因分层）

### 产品侧（已修，均有设备实证）
1. **句柄活读会把真落字判成假红**（avd35 搜索页，确定性复现 C2→C3→C5 链）：过渡期 SET_TEXT
   派发给旧句柄后 Compose 整节点换新，字已落进新 EditText（dump 亲见 `text="password"`，
   证据 `avd35-c5-dump-text-landed-on-new-node.xml`），旧句柄 `refresh()` 永远读不到。
   修复：`awaitLanded` 每轮加"扫活树可编辑节点含输入即判落"自证边（匹配键=输入本身，非线索；
   限定 EditText 类防搜索结果列表 TextView 假阳性）。此前"整步重试一次"仍是假红（重试线索已被
   hint 消失消耗），扫树边上线后重试不再触发（单测锁了不双派）。
2. **false 边陈旧句柄一次性重派**（avd35 C1 scroll、C7 click、MarvisPhone C7 type_text）：
   定位命中后异步重排整棵树，performAction 打在死句柄上被明示拒绝。false=没执行过、线索未消耗，
   按原线索重定位再派一次封顶；仍 false 收 perform_failed。K40/K80 雷 12 的持久拒绝只多付一次
   重派即收红，行为不变。

### 测试通道侧（脚本已修）
3. **SettingsIntelligence 独立进程残留**：搜索页任务赖在前台（含旧查询词态）毒化下条用例首步
   定位 → `stop_settings_ui()` 双 force-stop。
4. **宿主并跑 3+ 模拟器时固定 sleep 不够**：容器已在树里但条目未排布，scroll/click 明示拒绝假红
   → 前置 sleep 8 + `wait_home_scrollable()`（dump 轮询目标容器 `scrollable="true"`，15s 不判红）。
5. **红项明细随行**：`run_case` 失败分支现在直接带 S1SMOKE-DETAIL 尾两行——本批 C5 归因全靠它，
   不用再复跑碰运气。
6. **绝不在测试通道 force-stop 自家 App**：AOSP 上同样清掉无障碍绑定（雷 13 小米系行为在模拟器
   复现），任务注入后无声零回执，实验直接废一轮。
7. **模拟器闲置锁屏=全红轮**；KEYCODE_WAKEUP + wm dismiss-keyguard 恢复。

### 设备状态雷（不可按需复现，备案）
8. **MarvisPhone 一次性 SET_TEXT/PASTE 双拒态**：同轮内 C2 绿→C5/C7 起全红，重启后自愈且
   11/11。非产品、非脚本、非 autofill（autofill 置 null 无效）、非镜像版本（与 avd34 同版）。
   归因=设备累积状态（该机连跑多日 C8 解绑重绑）。S4 装机引导话术候选："执行异常先重启手机"。

## 4. 边界（诚实声明）

- AVD≠真机：swiftshader 渲染时序、无厂商魔改、GMS 组件版本固定，矩阵绿不覆盖 Samsung/Moto 缺口
  （T3-R4 已备案）。小米系=雷 12/13/14 专档（`t3-k80-hyperos.md`）。
- K80 整轮 8绿3红 同根雷 12（脚本化搜索框输入被小米系通病拒绝），属已知设备限制非回归。
- 高危链"确认放行"路径（路径3）只在 K40 由老板手点闭环；AVD 上 C7 证明面板真实弹出+挂起可停，
  未重复手点（gate 代码与设备无关）。
