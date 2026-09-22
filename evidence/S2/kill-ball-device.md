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
