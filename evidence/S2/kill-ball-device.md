# 悬浮停止球响应性 · 设备实证（Task #14）

日期：2026-09-22（设备时钟 UTC+8 显示 18:16-18:19；logcat 时间戳为设备本地 10:1x）
设备：MarvisPhone（API-34 google_apis 模拟器，1080x2400）
APK：本轮 `:app:installDebug`（含 NodeTaskRunner kill 响应性修复），服务重绑 Bound=Anytouch 确认。

## 缺陷（修复前，10:06:26 实测）

链 `[click "Connected devices", click "__no_such_node__"]`，第二步 15s 定位轮询期间（+4s）点球：
- 回执 `09-22 10:06:37.132 S1SMOKE ok=1 total=2 stopped=true stop="NODE_NOT_FOUND"`
- 停止信号被定位轮询/落字复核/确认等待三类长循环无视，只能等下一步步首才被看见；
  若 kill 落在**末步**定位期，整队以错误归因收官（user_stop 丢失）。

## 修复（主窗自纠，NodeTaskRunner.kt）

1. `locateWithRetry`：每轮（250ms）查 `killSwitch.isStopped()`，命中即返回 null → 该步产 `USER_STOP` 失败 + killReceipt（source=kill_switch）。
2. 二次确认等待：`coroutineScope` 内 waiter(async)+killer(launch) 竞速，kill 即 cancel waiter（面板经 invokeOnCancellation 自动收起），归因 user_stop 回执；外层取消语义保留（CE 非本因则重抛）。
3. `awaitLanded` 落字复核轮询：每轮查 kill 提前返回；未落字失败回执前先查 kill，防 `set_text_unverified` 抢占 user_stop 归因。
4. 步界补发：末步执行成功但期间被 kill 时，run 循环在步界补 killReceipt，防整队以 stopped=false 收官；中间步 kill 仍由下一步步首检查归因（回执指向被打断的下一步，语义不变，既有测试锁住）。
5. coroutines 1.9.0 无 `Deferred.awaitOrNull`，改 `await()` + 定向 catch `CancellationException`（首版编译失败即纠）。

JVM 新增 4 测（NodeTaskRunnerTest，共 22 测）：定位期 kill（<10s 内出 USER_STOP 回执、非 NODE_NOT_FOUND）、确认等待期 kill（高危步零派发）、落字复核期 kill（不误报未落字）、末步 kill 步界补发。全套 115/115 绿；ci-local 红线 A–E PASS。

## 设备实证（修复后）

注入同链，+4s 点球 (1002,1272)：

```
injected at: 18:17:19.581
tapping stop ball at: 18:17:23.654
09-22 10:17:23.094 I/AnytouchRun: S1SMOKE ok=1 total=2 stopped=true stop="user_stop"
09-22 10:17:23.095 I/AnytouchRun: S1SMOKE-DETAIL code=USER_STOP msg=用户在定位期间按下停止
```

- 回执在点球后 ≤0.5s 轮询窗内出现（修复前需再等 ~11s 且归因 NODE_NOT_FOUND）。
- 截图 `img/kill-ball-101723-receipt.png`：第一步已落在 Connected devices 页（ok=1 吻合），停止球已随任务收尾移除，无浮层残留；dumpsys 确认服务仍 Bound。
- 注：10:16:37 有一次注入因主机侧引号被设备 shell 拆散而 JSON 解析失败（回执含 "Unexpected JSON token"），属测试通道用法问题，改用 `MSYS_NO_PATHCONV=1 adb shell "... '$TASK'"` 外层双引号内层单引号后正常；产品侧解析失败即拒、零派发，行为正确。

## 回归

`scripts/device-smoke.sh` 第 3 轮 5/5 PASS（见 `device-smoke-output.txt` 09-22 10:17-10:19 段），C4 负例仍正确归因 NODE_NOT_FOUND（无 kill 时不受影响），C5 高危超时默认拒绝不受确认竞速改造影响。

## 复现命令

```bash
adb shell am force-stop com.android.settings && adb shell am start -a android.settings.SETTINGS && sleep 5
adb logcat -c
TASK='[{"action_id":"k1","type":"click","source":"node","value":{"text":"Connected devices"},"safety":{"viewport_ok":true,"click_enabled":true}},{"action_id":"k2","type":"click","source":"node","value":{"text":"__no_such_node__"},"safety":{"viewport_ok":true,"click_enabled":true}}]'
MSYS_NO_PATHCONV=1 adb shell "am start -f 536870912 -n com.anytouch.app/.MainActivity --es task_json '$TASK'"
sleep 4 && adb shell input tap 1002 1272   # 停止球；测试通道专用，非产品路径
adb logcat -d -v time -s AnytouchRun | grep 'S1SMOKE'
```

## 残留观察（移交 T2/T3 议题）

- kill 响应粒度 = locatePollMs(250ms)；`settleMs/focusSettleMs` 固定沉降内的 kill 延迟到沉降结束（≤800ms），可接受。
- 停止球坐标命中依赖悬浮球默认停靠位；拖拽后坐标会变（本轮未测拖拽）。

---

# 追加：确认面板挂起期点球（Task #15）· 09-22 10:26 UTC 结案

## 又一颗浮层雷（设备实证发现并修复）

面板挂起时点停止球 → **触点被吞**，等待满 15s 走超时默认拒绝（`stop="PASSWORD:password"`），球完全不可达。
根因：`OverlayUi.overlayParams` 面板分支 focusable 但未加 `FLAG_NOT_TOUCH_MODAL`——Android 上 focusable 窗口默认
touch-modal，会吃掉**自身边界外**的全部触点，压在 z 序下方的停止球收不到任何事件。修复一行：面板 flags 加
`FLAG_NOT_TOUCH_MODAL`（面板只吃边界内触点）。JVM 单测对窗口 flag 无感，再次印证"浮层平台强耦合必须设备亲验"。

## 修复后设备实证（10:26:29 注入，同 PASSWORD 链）

```
injected 18:26:29.496
ball tap  18:26:37.938   # 面板挂起期点球 (1002,1264)
09-22 10:26:37.638 I/AnytouchRun: S1SMOKE ok=2 total=3 stopped=true stop="user_stop"
09-22 10:26:37.639 I/AnytouchRun: S1SMOKE-DETAIL code=USER_STOP msg=用户在二次确认等待期按下停止
```

- 回执在点球后 ≤1s 出现；`panel3-pending.png` 为挂起期面板+球同屏证据，`panel3-after-ball-kill.png` 显示
  面板/球全部撤净、高危步零派发（未进入 Passwords 页），fail-closed 语义保持。
- 归因走 runner 侧 waiter/killer 竞速取消，面板经 `invokeOnCancellation` 自动收起（bug2 修法的复利）。

## 过程留痕（两次无效尝试，均测试通道问题，非产品缺陷）

1. `android:id/search_src_text` 定位 12s 不中：该页输入框真名 `com.google.android.settings.intelligence:id/open_search_view_edit_text`
   （uiautomator dump 实证）；此前 C2 冒烟通过的是另一入口。修正线索后链路即通。
2. 首轮重测时顺手 `force-stop com.anytouch.app` 令服务解绑、注入无人消费（`enabled_accessibility_services` 被清成 null，
   已知行为再次踩中）；重绑后正常。附带收获：陈旧请求在重绑后按 60s TTL 正确丢弃，未偷跑。
3. 挂起期球实际中心 ≈(1003,1264)（此场景面板布局使球位略低于默认）；坐标仍属测试通道专用。
   过程截图：`img/probe-wrongid-locate-fail-{1,2,3,4}.png`（错误线索期页面态）、
   `img/panel-swallow-ball-BEFOREFIX-pending.png`（面板挂起+球可见但点不动）、
   `img/panel-swallow-ball-BEFOREFIX-timeout-deny.png`（超时拒绝后残留态）。

## 回归

OverlayUi 修复后全套 JVM 115/115 绿、ci-local 红线 A–E PASS（本文件所附 NodeTaskRunnerTest 22/22）。

---

# 追加：dump-under-panel 残留风险正名与修复（第 7 颗雷·running 悬挂）· 09-22 10:47 UTC

## 复测结论（TTL 修复后重跑当年风险场景）

面板挂起期连发 `uiautomator dump`：
- **重放风暴已死**：只出现一条 `S1SMOKE busy, request … dropped`（新采集器对队头重放即弃），无循环。TTL/busy-consume 兑现。
- **回执僵持是真的，且根因比想象严重**：dump 触发服务进程重启（旧 pid 1658→新 10326），旧 runTask 协程被取消——
  取消路径 `throw CancellationException` 跳过了收尾五连（hideStopBall/running=false/stopForeground/consume），
  `AppState.running` 永挂 true。设备实证后果：**此后注入的新任务全部被 "busy" 静默吞掉，执行器永久失能**
  （10:44:04 `S1SMOKE busy, request 19604395001200 dropped`，任务实际从未执行）。生产可达：系统随时可重启无障碍服务。

## 修复（主窗自纠）

`AnytouchAccessibilityService.runTask` 收尾改 `try/finally`：悬浮球/前台态/running/队列消费无条件执行；
取消路径保留"不伪造 S1SMOKE 回执"纪律，但必打 `S1SMOKE run cancelled by service lifecycle … 回执缺席以此行为准` 留痕。

## 修复后设备实证（同场景重放）

```
09-22 10:45:13.044 W/AnytouchRun(10326): S1SMOKE run cancelled by service lifecycle (Job was cancelled), 回执缺席以此行为准
（ hazard 后注入新任务 ）
09-22 10:45:33.727 I/AnytouchRun(10326): S1SMOKE ok=0 total=1 stopped=true stop="NODE_NOT_FOUND"   ← 执行了，不再 busy 吞
```
（NODE_NOT_FOUND 属预期：hazard 后画面停在搜索结果页，"Connected devices" 不在屏——归因正确即恢复成功。）
全套 JVM 115/115 绿、ci-local 红线 A–E PASS、device-smoke 7/7（10:46-10:47 段归档）。

## 残余观察更新

- 取消窗口内面板若已弹出：`onDestroy→overlay.dispose()` 撤面，无残留触点（截图 `img/dump-under-panel-HANG-check.png` 为修复前现场：面板/球/回执全无 = 静默死）。
- "取消期最后一条 performAction 是否可能已派发"——取消只发生在挂起点（delay/locate），派发本身原子短促；fail-closed 语义不受损。
- dump 触发进程重启仍属测试通道自伤（生产无 uiautomator），但**服务被系统重启**是生产可达事件，本修复对两者同时生效。
