# 设备动作覆盖面实测（主窗，2026-09-22，模拟器 MarvisPhone / API-34 google_apis）

口径：全部为**真机（模拟器）回执**，非 JVM 单测。取证窗口 07:59–08:15 UTC+8 两轮。
设备：`emulator`（自建 AVD，官方镜像，非厂商 ROM——命名澄清见 S1 终报 §3）。

## 结论速览

| 动作 | 目标 | 结果 | 凭据 |
|---|---|---|---|
| click（text 二阶） | Settings "Connected devices" | ✅ ok | 链式回执 ok=2/2 |
| scroll（resource_id 一阶） | Settings `settings_homepage_container` | ✅ ok | 同上 |
| click 三级钻取 | Connected devices → … → Bluetooth | ✅ ok=3/3 | s1-smoke.sh / demo 录像 |
| type_text → 系统搜索框 | settings.intelligence OpenSearchView | ❌ performAction=false ×2 | 系统通道不接 SET_TEXT（记档，T3 真机复测） |
| type_text → 自家 Compose 输入框 | `com.anytouch.app` testTag 框 | ⚠️→✅ **虚报被当场抓获并修复** | 详见下文 |

## type_text 虚报事件（本轮最大发现）

1. **现象**：对自家 Compose `OutlinedTextField`（testTag=`task_input`）派发 ACTION_SET_TEXT，
   `performAction` 返回 **true**，回执 `ok=1, level=RESOURCE_ID`——但 uiautomator 重dump 全树，
   输入内容 `setText device proof` **零出现**。设备层谎报成功。
2. **修复**：`NodeTaskRunner.runNodeStep` 对 TYPE_TEXT 增加**落字复核**（沉降后在活树重定位、
   重读 text，须包含输入串，否则回执翻 `EXECUTOR_ERROR` + stop_reason=`set_text_unverified`）。
   回归测试锁定：`type_text虚报 performAction为true但未落字 回执翻失败并停机`。
   假设备同步仿真（setText 必须真的改树，否则新测试先红——假设备不许比真设备更诚实的反面教材）。
3. **修复后设备复跑**（同一条任务、同一台模拟器）：
   ```text
   08:12:39 S1SMOKE ok=0 total=1 stopped=true stop="set_text_unverified"
   08:12:39 S1SMOKE-DETAIL code=EXECUTOR_ERROR msg=SET_TEXT 未落字（performAction=true 为虚报）: 期望包含 "setText device proof"
   ```
   ——回执从"假绿"翻为"诚实红"。**S1 纪律升级：type_text 成功 = performAction ∧ 落字复核。**
4. **遗留（归 T2/T3 实测军令）**：Compose 输入框 SET_TEXT 不落字的落地通道未解
   （候选：focus+剪贴板+ACTION_PASTE；Android 13+ 无障碍剪贴板权限需实测）。
   两系统输入面（Settings 搜索）与自家 Compose 面均不能依赖 ACTION_SET_TEXT——
   含 type_text 的录制任务在解决前只能产"诚实失败"。

## 顺带钉死的三条设备事实

- **Compose testTag 暴露的 resource-id 是裸条目名**（`task_input`），无 `pkg:id/` 前缀；
  系统节点则带全名。一阶定位是全等匹配 → 录制/回放取自同一棵树，天然自洽；
  跨来源手写任务时按树内实况写。
- **`keep_fg` 只适用于自目标任务**：跨 App 链必须让位目标窗口（不带 keep_fg，走 moveTaskToBack）。
  带 keep_fg 打 Settings 任务 → 自家窗口抢占前台 → 二阶 text 零命中（NODE_NOT_FOUND 回执为证，行为正确）。
- **滚动+点击混合链**在 Settings 首页稳定：`ok=2 total=2 stopped=false`（08:14:38 新鲜回执）。

## 复核命令（可重跑）

```bash
# 混合链（滚动+点击）
adb shell "am force-stop com.android.settings" && adb shell "am start -a android.settings.SETTINGS"
adb logcat -c
adb shell "am start -n com.anytouch.app/.MainActivity --es task_json '[{\"action_id\":\"s1\",\"type\":\"scroll\",\"source\":\"node\",\"value\":{\"resource_id\":\"com.android.settings:id/settings_homepage_container\",\"direction\":\"forward\"},\"safety\":{\"viewport_ok\":true,\"click_enabled\":true}},{\"action_id\":\"c1\",\"type\":\"click\",\"source\":\"node\",\"value\":{\"text\":\"Connected devices\"},\"safety\":{\"viewport_ok\":true,\"click_enabled\":true}}]'"
adb logcat -d -s AnytouchRun:*   # 期望 S1SMOKE ok=2 total=2 stopped=false

# type_text 虚报抓获回执（落字复核生效的证明）
adb shell "am start -f 536870912 -n com.anytouch.app/.MainActivity --es task_json '[{\"action_id\":\"t1\",\"type\":\"type_text\",\"source\":\"node\",\"value\":{\"resource_id\":\"task_input\",\"input\":\"setText device proof\"},\"safety\":{\"viewport_ok\":true,\"click_enabled\":true}}]' --ez keep_fg true"
adb logcat -d -s AnytouchRun:*   # 期望 stop="set_text_unverified"（修复前此处是假绿 ok=1）
```
