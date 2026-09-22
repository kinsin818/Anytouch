# 高危二次确认链 · 设备实证（模拟器 API-34，2026-09-22）

军令红线："高危动作必须二次确认，无确认默认拒绝"。JVM 层早已全绿（HighRiskMatcher 12 用例、
runner 超时/无人确认=拒），但**服务侧浮层通道从未上过设备**——本轮补证，当场揪出两个真 bug。

## 场景

任务链（全节点引用，零坐标）：click `Search settings` → type_text `password` →
click `Passwords & accounts`（text 含 "password" → 命中 PASSWORD 词表 → 应挂确认面板）。

## bug1：确认面板/停止球永远挂不上（修复：OverlayUi 必须用服务 context）

- 现象：命中高危后 ~2s 即拒（非 15s 超时），面板与停止球均未出现。
- 系统日志铁证：`W WindowManager: Attempted to add Accessibility overlay window with unknown token null. Aborting.`
- 根因：`AnytouchAccessibilityService.onServiceConnected` 里 `OverlayUi(applicationContext)`——
  TYPE_ACCESSIBILITY_OVERLAY 的窗口 token 挂在 AccessibilityService 自身的 WindowManager 上，
  application context 加视图必失败。修复=传 `this`（服务 context）。
- 安全侧评估：失败模式是 fail-closed（addView 失败→立即拒绝），**从未误放行**；
  但用户永远没机会确认，高危功能实际不可用。截图 `img/highrisk-no-panel-BUG1-beforefix.png`。
- 修复1后：停止球出现（`img/highrisk-stopball-visible-afterfix1.png`），面板出现
  （`img/highrisk-panel-shown.png`、`img/highrisk-panel-over-results.png`），
  不操作 15s → `SAFETY_GATE_BLOCKED`（09:41:40.390，回执全文 `highrisk-confirm-receipts.txt`）。

## bug2：点击"确认执行/取消"后面板不消失（修复：决策即 removePanel）

- 现象：确认放行成功（ok=3/3，09:51:40.857）但面板永久盖在结果页上
  （截图 `img/highrisk-panel-stuck-after-confirm-BEFOREFIX.png`）。
- 根因：按钮 onClick 只 `cont.resume(...)`，撤面板仅挂在 invokeOnCancellation（超时路径）上。
- 修复：两个按钮点击先 `removePanel(panel)` 再 resume。
- 修复2后双路径实证：
  - 取消 → 面板撤下（`img/highrisk-after-cancel-dismissed.png`）+ 即时 `SAFETY_GATE_BLOCKED`（09:53:20.863）
  - 确认 → 面板撤下（`img/highrisk-after-confirm-dismissed.png`）+ `ok=3 total=3 stopped=false`（09:54:03.481）

## 风险观察（未修，留档）

测试期间发现：确认面板挂起时并发跑 `uiautomator dump`，会出现同一请求被反复重放的
`S1SMOKE busy` 日志（每次 dump 触发服务重绑→新 collector 拿到未消费的 StateFlow 头），
且当轮 runTask 长时间不出回执（疑主线程在 overlay removeView 与 a11y 查询间僵持）。
生产环境无第三方 a11y 转储客户端时不可达；真机联调（T2/T3）若复现再立专项。
device-smoke 回归脚本刻意不使用 uiautomator dump，无此暴露。

## 代码改动

- `service/AnytouchAccessibilityService.kt`：OverlayUi 改传服务 context（含留痕注释）
- `service/OverlayUi.kt`：决策即撤面板；addView 失败路径补 `Log.w`（fail-closed 必须可归因）

## 门禁复核

`./gradlew :app:testDebugUnitTest` 全绿（108 用例）；`scripts/ci-local.sh` PASS
（红线 C 无网络关键词 / D 无坐标注入 / E 清单无 SYSTEM_ALERT_WINDOW——面板通道仍走无障碍 overlay）。

## 重跑口径

```
# 前置：模拟器+APK+服务绑定（同 s1-smoke.sh 步骤1）
adb shell am force-stop com.android.settings
adb shell am start -f 0x10008000 -a android.settings.SETTINGS; sleep 5
adb logcat -c
adb shell "am start -f 536870912 -n com.anytouch.app/.MainActivity --es task_json '[{\"action_id\":\"s1\",\"type\":\"click\",\"source\":\"node\",\"value\":{\"text\":\"Search settings\"},\"safety\":{\"viewport_ok\":true,\"click_enabled\":true}},{\"action_id\":\"s2\",\"type\":\"type_text\",\"source\":\"node\",\"value\":{\"text\":\"Search settings\",\"input\":\"password\"},\"safety\":{\"viewport_ok\":true,\"click_enabled\":true}},{\"action_id\":\"s3\",\"type\":\"click\",\"source\":\"node\",\"value\":{\"text\":\"Passwords & accounts\"},\"safety\":{\"viewport_ok\":true,\"click_enabled\":true}}]'"
# 面板约 10-12s 出现；不碰=15s 超时拒（stop="PASSWORD:password"）
# 点"取消"(390,1294) 或"确认执行"(702,1294)（adb input tap 仅测试操作，非产品通道）
```

## 补案（09-22 10:04 UTC）：上方'重放风险'已固化为 TTL 修复
风险观察里的 StateFlow 头重放不是不可达问题——它等价于'用户注入 4 分钟后服务恰好重绑，旧任务无人值守自动开跑'，违背用户意图驱动执行。已修：
- TaskRequest 带 submittedAtMs，TaskPolicy.TTL_MS=60s；服务 collect 时过期即 consume+Log.w 丢弃；busy 分支同样 consume（防滞留重放）
- JVM 新增 AppStateTest 3 用例（TTL 边界×2 + submit/consume 语义），全套 111 例绿
- 设备实证过期即弃：解绑服务(grep=0)→注入→8s 零执行→65s 后重绑→日志只出 'stale request ... expired, dropped'（10:03:22.882），无 S1SMOKE 执行行
- device-smoke 5/5 重跑无回归（10:03:40→10:04:27 全 PASS）
残余观察不变：面板挂起时并发 uiautomator dump 的回执僵持仍留档待 T2/T3。
