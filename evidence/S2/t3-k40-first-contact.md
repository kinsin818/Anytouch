# T3 首触：Redmi K40 真机接入与首轮实证（09-22 13:15–13:25 UTC）

## 0. 设备档案
- 序列号 7ae4bfee · M2012K11AC（alioth，Redmi K40 国行）· Android 13 (SDK 33) · MIUI V816.0.10.0.TKHCNXM · 1080x2400@440dpi · arm64-v8a
- 环境注记：机上另绑两家无障碍服务（系统无障碍菜单 + com.mmnn.oce"暴力熊"自动化 App），绑定必须**追加**不可覆盖；侧边栏常驻小窗（截图左侧）系 MIUI 智能侧边栏+暴力熊挂件，非我方浮层。

## 1. 安装与绑定（全绿）
- `adb install -r -g` Success（-g 顺带授悬浮窗权限）；`settings put secure enabled_accessibility_services` 追加式写入后，`dumpsys accessibility` Bound services 即见 **"Anytouch 执行器"（capabilities=33）**，无需 MIUI 弹窗人工确认——国行 MIUI 13 对 adb 通道写 a11y 配置不设卡。

## 2. 首轮动作矩阵（真机实证）
| 链 | 结果 | 回执/证据 |
|---|---|---|
| click "WLAN"（中文节点词） | ✅ ok=1/1 | 真实翻页到 WLAN 页（截图 `img/k40-wlan-page-after-click.png`），落字/导航复核通过 |
| 停止球（执行中） | ✅ 可见可点 | 挂起期截图红球在右缘停靠位（`img/k40-stop-ball-midrun.png`）；tap (1002,1272) → ≤2s `stop="user_stop"`，归因"用户在定位期间按下停止"——**球停靠坐标与模拟器默认值重合，BALL_TAP 无需改** |
| type_text 自家 Compose 框 | ✅ ok=1/1 | 执行器输入链（聚焦→SET_TEXT→落字复核）在真机正常 |
| type_text MIUI 搜索框 | ❌ 双通道被拒 | 见 §3 |
| scroll MIUI 首页容器 | ❌ 明示拒绝 | 见 §4 |

## 3. 真机第 1 雷（雷号 12）：SET_TEXT"明示拒绝"边无兜底
- 现象：`android:id/input`（标准 EditText，uiautomator 可证）上 ACTION_SET_TEXT 确定性返回 false（两轮复现），执行器直接 `perform_failed`——PASTE 兜底当时**只挂在"SET_TEXT 谎报 true 未落字"分支**，"false 明示拒绝"这条边从未派发兜底。
- 主窗修复（`NodeTaskRunner` TYPE_TEXT 派发边改为 `setText || pasteText`，成败终裁仍是落字复核）；119 例 JVM 绿；新包装机复测：PASTE 也被该框拒绝（无 ClipboardService 拒我们名的日志，拒绝发生在 View 侧）——**修复保留**（该边本就该兜底，对其他 ROM 有效），MIUI 搜索框记为 ROM 特例：两通道皆拒时回执诚实报 perform_failed，无虚报。
- 定性：产品输入通道 = a11y 双通道（SET_TEXT/PASTE），MIUI 定制控件可双拒；海外版（英文 Pixel/Samsung/Moto 口径）不受本案直接影响，**是否加第三通道（如 IME 代理）属老板裁决项，不自改**。

## 4. scroll 真机首案：MIUI 首页容器不吃 ACTION_SCROLL_FORWARD
- `com.android.settings:id/nestedheaderlayout`（scrollable=true 由 uiautomator 自证）上 ACTION_SCROLL_FORWARD 返回 false → `perform_failed` 诚实回执。
- 与 type_text 同理：a11y 滚动被拒后的兜底（dispatchGesture 属坐标注入红线区）**不动产品代码，上报待裁**。测试口径替代：真机 C1 可改点首页直显项（本轮 click 链已证）。

## 5. smoke 脚本真机化改造（本批已落）
- `ANDROID_SERIAL` 定向 + 多设备并存强制门禁（防打错靶）；改造后模拟器回归 **11/11 全绿**。
- 词表/几何参数化：`TXT_SEARCH/TXT_PASSWORDS/TXT_CONNECTED/RID_HOME/BALL_TAP`（默认=模拟器英文口径）。
- C8 安全化：开跑前**快照** enabled_accessibility_services，解绑只摘自家、收尾恢复原清单——真机上绝不硬覆盖别家服务。
- 已知未解：C5/C7 高危面板在中文 MIUI 上无 UI 途径触发（词表为英文子串，中文界面无 "password" 节点；此即海外产品定位——英文界面为主）。待：国际版 ROM 或英文语言的 C5 真机轮。

## 6. 结论
真机三大地基项（安装绑定 / 点击导航 / 急停球）**全数过验**，且两处失败均为诚实回执非虚报。输入与滚动的 MIUI 特拒、C5 中文语境不可触发三项记入 T3 台账。
