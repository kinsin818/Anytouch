# S6 三屏商品图 — 施工窗交付报告

工单：`D:\Qoder\ops\orders\ANYTOUCH-S6-AVD三屏商品图-ORDER-20260926.md`
设备：avd34（API 34，1080×2340）emulator-5554｜构建：v1.0.6 / versionCode 7（当前源码，对账见 §3）
时间：2026-09-26 15:04 → 16:04（本地时钟）｜本件不含任何整激活码，指代只写尾四位。

## 0. 一句话结论

**按工单字面口径：只交 SHOT-1（免费态，真实三帧）**；SHOT-2/3 因 8443 直连不可达按 §1-1 分支出 `blocked-8443.md`。
**但两图已用 S5-g 受制裁中继（对面=真服务器）实际拍到手**，文件名带 `-PROUNLOCKED-pending-ruling` 后缀，
**是否可用交主窗裁决**，本窗不自裁、不冒"已交付"。

## 1. 交付物清单（全部真实在盘）

| 文件 | 字节 | 状态 |
|---|---|---|
| `shots/shot1-main-panel.png` | 221,176 | 正式交付（免费态顶帧） |
| `shots/shot1b-free-bottom-pro-hints.png` | 185,051 | 正式交付附件（免费态底帧，三处 Upgrade to Pro 话术） |
| `shots/shot1b-ai-panel-mid.png` | 189,572 | 早期帧（15:19，服务未绑定时段所拍，仅存档不作封面） |
| `shots/shot1c-run-task-bottom.png` | 192,676 | 同上 |
| `shots/shot2-repetitions-PROUNLOCKED-pending-ruling.png` | 252,818 | **待裁**（中继激活态） |
| `shots/shot3-mytasks-PROUNLOCKED-pending-ruling.png` | 218,948 | **待裁**（中继激活态） |
| `shots/blocked-8443.md` | 6,090 | 正式交付（探测原文 + 中继补记） |
| `raw/20260926-150402/` | 20+ XML/日志 | 逐帧 dump、探测原文、注入日志、中继收口 |

## 2. 逐图实述（图里真有什么，主窗已亲眼看 PNG）

- **shot1-main-panel.png**（15:58）：标题 `Anytouch executor`；`Activate` 按钮 + 右侧
  `Upgrade to Pro: not activated on this device. Recording, compiling and running tasks stay free.`（免费态如实）；
  `Accessibility service: connected`；`Idle`；`Package to record = com.android.settings`；
  `Start recording`（可点）/`Stop & compile`（灰）/`Not recording`；AI compile 面板标题 + 意图框 + 词表开关 + `AI compile` 钮；
  右缘半透明绿色 `● Rec` 悬浮球（空闲态设计如此，非缺陷）。
- **shot1b-free-bottom-pro-hints.png**（15:57）：模板三钮（其中 "Discord check-in" 钮被悬浮球挤压成竖排单字——见 §5 缺陷 D-2）；
  `My tasks` + `Upgrade to Pro to save and reuse your own tasks.`；`Save this task`/行内 Load/Run/Delete 全灰；
  `Upgrade to Pro to repeat rounds.` + `Repetitions (1-100)`/`Interval seconds (1-60)` 两框灰态；`Run task`。
- **shot2-repetitions-PROUNLOCKED-pending-ruling.png**（16:03）：`My tasks` 标题、
  `Bluetooth toggle` 行三钮（Load/Run/Delete 彩色可用）、`Repetitions (1-100)=1`、`Interval seconds (1-60)=5` **两框 enabled=true**、
  `Repeat without asking`、`Run task`；底部一格是上一次失败执行回执 JSON（`SAFETY_GATE_BLOCKED`，环境所致：任务在自家界面顶层跑）；
  悬浮球 `● Rec` 压在 Interval 输入框右下角（未遮数值"5"，见 §5 缺陷 D-2）。
- **shot3-mytasks-PROUNLOCKED-pending-ruling.png**（16:01）：`My tasks` + `Name for this task` + `Save this task`（可用态）+
  已存任务行 `Bluetooth toggle` 与 Load/Run/Delete 三钮；`Repetitions (1-100)`/`Interval seconds (1-60)` 标签行被悬浮球
  **压住 "(1-60)" 尾部字样**；输入框与数值可见。
- **SHOT-1 三合一帧不存在**（工单期望 vs 界面布局冲突，非本窗偷懒）：录制入口钮底边 y≈1072、`AI compile` 钮顶边 y≈1776、
  `Run task` 顶边 y≈1670~1846（随滚动），视口可用高 2138px —— 三者同帧物理不可能。故按"顶帧为主图 + 底帧作附件"交，
  bounds 证据在 `raw/20260926-150402/ui-dump-*.xml`。

## 3. 跑前对账三件（原文在 `raw/20260926-150402/precheck-apk.txt`、`git-status.txt`）

- 装机 APK：`app-debug.apk` 9,389,205B / md5 `d61b9867fcdf164d73ed236ce61f76e1` = v1.0.6 发布字节账面；
  机侧 `dumpsys package` 复读 `versionCode=7 versionName=1.0.6`；
- `git diff 70daffd..HEAD -- app/ core/ byok/ contracts/ …` = **空**（自发布 commit 起代码路径零改动，改的全是文档/账面件）；
- HEAD `34e8a5e9…`，`git status --short` 只有 `?? evidence/S6-shots/`（本批产物）。

## 4. worker 五关自检

| 关 | 判据 | 结果 | 证据 |
|---|---|---|---|
| 1 | 图真实在盘、字节>0、`file` 判 PNG | PASS | §1 表 + `file` 输出（六张全 `PNG 1080×2340`） |
| 2 | 每张图有对应 UI dump 断言 | **PASS，但判据本批被削弱**：见 D-1——`uiautomator dump` 对 Compose 文本节点会返回**陈旧缓存**（屏上"connected"、dump 里"not connected"）。顶帧文字断言因此改由**截图目视**兜底；shot2/3 的 enabled/bounds 断言仍来自 dump（该两类属性实测未滞后） | `ui-dump-shot1.xml`、`shots23/ui-dump-shot2-live.xml`、`shots23/ui-dump-shot3-live.xml` |
| 3 | 装机包=当前源码（md5+version+git status 原文） | PASS | §3 |
| 4 | 交付物内零整码 | PASS | 台账 120 枚整码逐枚 `grep -F` 扫 `evidence/S6-shots/` 全目录=**0 命中**；`ANY-段-段-段` 形态正则=0 命中（首版报告自述该正则时字面量自匹配过一次，已改措辞后复扫）；屏上/日志只出现尾四位（C0YN/9EXD/E7DA） |
| 5 | 8443 结论可被主窗同命令复现 | PASS（结论=不可达） | `raw/…/probe-8443.txt` §0–§12；反面对照（9999 同 rc=0）+ 工具自证（Cloudflare SPKI）+ 服务器侧 tcpdump 0 包，三层互证 |

## 5. 本批挖出的缺陷与测试通道新雷（如实登记，未改一字节产品代码）

- **D-1（测试通道，影响一切 dump 断言）**：Compose 文本节点在 `uiautomator dump` 里可读回**上一版本文案**——
  同一时刻 `screencap` 像素为 `Accessibility service: connected`，dump 为 `…is not connected…`；
  滚动/换帧后 dump 对**布局类属性**（bounds/enabled）依旧准确，唯 text 滞后。
  本窗一度据此误判"服务没绑上"两小时（其间 device-smoke C1–C7 全绿已证服务功能正常）。
  口径建议：**凡以 dump 文本作"屏上有没有 X"的判据，必须与同帧截图互证**；此雷与 S2"dump 会打断在跑任务"同族，建议入册。
- **D-2（产品面，视觉）**：悬浮录制球常驻右缘垂直居中（`OverlayUi` x=12、END|CENTER_VERTICAL，空闲态显示 `● Rec`），
  前景任何一帧它都盖在内容上：实测把模板钮 "Discord check-in" 挤成竖排单字（shot1b 底帧），并压住 "Interval seconds (1-60)" 标签尾部（shot3）。
  与 S2 已裁的"球压改名钮"同族，**商品封面素材受害**，建议主窗连同 S4 换皮一并裁（本窗不动）。
- **D-3（产品面，待复核）**：本窗曾见 dump 里 `saved_run_0 enabled=false` 而像素上 Run 钮彩色可用——
  与 D-1 同型（enabled 也可能滞后），**不据此下产品结论**，留主窗复核。
- **D-4（环境）**：`adb root` 重启 adbd 会**清掉 AVD 的 DNAT 规则**（中继半瘫：主机隧道在、靶机到不了）。
  本窗按脚本口径 `--down` 后重建即恢复。中继生命周期已在 `blocked-8443.md` 补记，收口留痕 `raw/…/relay-teardown.log`（RC=0，DNAT=0）。

## 6. 待主窗裁的三件事

1. **中继激活态两图是否可用**（`-PROUNLOCKED-pending-ruling` 双图）：对面是真服务器/真 SPKI/真 staging 码、
   产品代码一字未动、非 v1.0.5 本地判据——但**不是**工单 §1-1 口径的"AVD 直连通"。
2. **SHOT-1 三合一帧的字面要求**是否按 §2 的布局事实收窄为"顶帧 + 底帧附件"。
3. **D-2 悬浮球压面**是否立项（影响封面质量）。

## 7. 未做到 / 判不准（一句不洗）

- **图幅形态先说清**：六张全为**竖屏 1080×2340**（AVD 原生分辨率，未裁剪未拼合）。
  若商家后台封面位要的是**横向**画幅，本批图不能直接顶上，需主窗按目标位尺寸另裁/另排——本窗不做 P 图；
- 8443 对**真实买家**是否可达，本窗判不了（本机出网被透明拦截，需老板手机蜂窝或干净机器复测）；
- SHOT-2/3 未走"直连激活"这条工单原路，只走通了中继路；
- 真人手指/IME 路径零实证（全程 adb 注入 + 坐标 tap 仅点过悬浮球一次）；
- dump 文本滞后雷（D-1）本窗只登记了现象，未定位根因（框架缓存 vs 自家 semantics 未失效，二者未分）。

---

## 8. 出处与入册追加（施工总窗 09-26 傍晚，按主窗回批补写；上方 §1–§7 为 worker 原文，一字未改）

- **出处**：本批由主窗（执行总监，`D:\Qoder`）派给**可见 worker 窗**产出，工单
  `D:\Qoder\ops\orders\ANYTOUCH-S6-AVD三屏商品图-ORDER-20260926.md`；产物落 `D:\Anytouch\evidence\S6-shots/`，
  由本施工窗入册（单独一枚 S6 commit，不与 S7 混提）。**worker 全程未碰商家后台**——
  Gumroad 后台在本仓账上只被施工窗于 S7 批**只读**访问过（见 `../S7-gumroad-cover-8443/cover-upload-result.md`）。
- **口径校正（不改 worker 原文，只把转述对齐盘上事实）**：主窗回批转述为"shot2/3 标 BLOCKED，只交了 shot1 + shot1b + shot1c"。
  盘上实际是：按工单 §1-1 字面口径 shot2/3 判 BLOCKED 并出了 `shots/blocked-8443.md`；
  **但两枚图文件确实在盘**（`shots/shot2-repetitions-PROUNLOCKED-pending-ruling.png` 252,818 B、
  `shots/shot3-mytasks-PROUNLOCKED-pending-ruling.png` 218,948 B，走 S5-g 受制裁中继、对面=真服务器所拍），
  文件名自带 `-pending-ruling` 后缀，**是否可用仍挂主窗裁**（worker §6-1 原问）。
  转述若被当成"文件不存在"就会丢一格证据，故按实登记。
- **本窗入册前自跑的凭**（不采信 worker §4 的自述，独立复跑）：
  ① 在册整码 120 枚（私有件 `anytouch-activate-codes-20260926.txt`）逐枚 `in` 比对全目录 **55 份文本件**（XML dump／日志／报告）＝**命中 0**；
  ② 宽松形态扫描 `[A-Z0-9]{4}-[A-Z0-9]{4}-[A-Z0-9]{4}`（不带 ANY 前缀）＝**零命中**；`Activation code` 对话框文本在任何 dump 里＝**零命中**（⇒ 截屏时对话框不在场）；
  ③ 全目录出现的码指代只有**尾四位** `C0YN`；
  ④ 24 枚 PNG 无法文本扫，故**肉眼核了两枚激活层帧**：`raw/20260926-150402/scan/activation-probe-frame.png`、
    `.../activation-rejection-frame.png` —— 后者屏上红字为
    "Activation needs a network connection. The server could not be reached, so this code was not checked at all. Nothing was unlocked."，
    **无任何码上屏**；其余 22 枚本窗**未逐枚目视**（如实记），其风险由两条旁证压低而非由本窗看图穷尽：
    全目录 dump 里激活对话框（`Activation code` 输入层）**零在场**，且宽松码形扫描零命中——若主窗要"每枚图都看过"的凭，
    须另派一格逐枚目视，本窗不自称做过。
  ⑤ Key/Token 字面（`nvapi-*`／`ADMIN_TOKEN=`／`sk-*`）＝**零命中**。
- **指针（不复制）**：主窗从 `shot1b-ai-panel-mid.png`／`shot1c-run-task-bottom.png` 裁出的三张渲染封面嵌图在
  `D:\Qoder\ops\evidence\s6-cover\crop-A.png`（198,969 B）／`crop-B.png`（96,889 B）／`crop-C.png`（73,334 B），
  裁切与渲染账在主窗那本；最终三枚横版封面 `cover-1/2/3.png`（1280x720）同目录，字节与 sha256 已在
  `../S7-gumroad-cover-8443/cover-upload-result.md` §1 入册。
- **worker 自报四雷（D-1…D-4）本窗只复核不裁**：D-1（`uiautomator dump` 对 Compose **文本节点**读回旧版本案，
  bounds/enabled 实测不滞后）影响一切以 dump 文本作"屏上有没有 X"的判据，与 S2"dump 打断在跑任务"同族，
  值得入册为测试通道雷；D-2（悬浮球常驻右缘垂直居中，压模板钮与 Interval 标签尾部，**封面素材受害**）属产品面，待主窗裁。
