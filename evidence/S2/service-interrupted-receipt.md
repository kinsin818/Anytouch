# 第 8 项：服务重启取消的中断回执留痕（执行报告层"必留痕"补全）

日期：2026-09-22（设备时间 10:56–11:01 UTC）· 主窗自纠批次 · 关联：`kill-ball-device.md` 追加段（第 7 颗雷 running 悬挂）

## 缺陷定性

第 7 颗雷修复（runTask 收尾 finally 化）后残留的同类黑洞：任务被服务生命周期取消（系统重启/用户解绑无障碍服务）时，
日志层已留痕，但 **lastRunReport 不写**——用户界面停留在上一条陈旧回执上，在跑任务"无声消失"。
这与"回执必须诚实"同源：回执缺席本身就是一种不诚实。生产可达路径与第 7 颗雷相同（系统随时可重启无障碍服务）。

## 修复

- `PipelineStopCode` 新增 `SERVICE_INTERRUPTED`（该文件本身是"只增"出口，注释有明示，不属擅动冻结件）。
- `AnytouchAccessibilityService`：取消分支写 `interruptedRunReport(reason)` 入 `AppState.lastRunReport`，再向上抛。
  回执与 encodeReport 同构（`stopped=true, results=[]`），payload 带 `note:"results 缺失，不代表已执行/未执行内容"`——
  取消点之后的派发状态不可知，宁可声明不可信，也不给可能被误读为完整记录的假象（不伪造回执的红线在反面同样成立：不隐瞒中断）。
- 顶层 internal 函数，JVM 可直接测：`InterruptedRunReportTest` 2 例（结构+归因+note；commandId 唯一性）。

## JVM 回归

全套 app 117 例（115+2）绿；debug/release 双份共 234 XML 计数 fail=0 err=0；ci-local 红线 A–E PASS。

## 设备实证（模拟器 MarvisPhone，pid 12455）

复现序列（已固化为 smoke C8/C8b，下方为手打原始记录）：

```bash
adb logcat -c
adb shell "am start -f 536870912 -n com.anytouch.app/.MainActivity --es task_json '[...__no_such_node_interrupt__ 15s 定位中...]'"
sleep 4
adb shell settings put secure enabled_accessibility_services null   # 模拟系统在执行中解绑
# → 10:56:30.552 W/AnytouchRun: S1SMOKE run cancelled by service lifecycle (Job was cancelled),
#   回执缺席以此行为准 receipt={"stopped":true,...,"stop_code":"SERVICE_INTERRUPTED",...}
adb shell settings put secure enabled_accessibility_services "com.anytouch.app/.service.AnytouchAccessibilityService"  # 重绑
adb shell am start -n com.anytouch.app/.MainActivity --ez keep_fg true
adb shell uiautomator dump  # 树中含 SERVICE_INTERRUPTED 文本
adb shell "am start ... task_json '[...新任务...]'"
# → 10:57:04.179 I/AnytouchRun: S1SMOKE ok=0 total=1 stopped=true stop="NODE_NOT_FOUND"（照常执行=自愈成立）
```

关键观察：
- 解绑→取消→回执写入全程 **同进程**（pid 12455 未变），lastRunReport 存活并在 MainActivity 直接可见
  （截图 `img/interrupted-receipt-ui-1056.png`：报告区整段显示 SERVICE_INTERRUPTED JSON，状态行"空闲"）。
- 归因确定性：onDestroy 里 `KillSwitch.stop()` 与 `scope.cancel()` 在主线顺序执行，轮询协程无隙先看到 kill，
  故取消路径必产 SERVICE_INTERRUPTED 而非 user_stop——C8 断言可稳定成立（两轮实测验证）。

## 回归固化

`scripts/device-smoke.sh` 新增：
- **C8 执行中解绑留 SERVICE_INTERRUPTED 中断回执**（注入→+4s settings 解绑→8s 内日志断言→重绑恢复环境）；
- **C8b 解绑重绑后执行器自愈**（新任务照常执行并正确归因 NODE_NOT_FOUND，防第 7 颗雷复发）。

两轮 9/9 PASS：10:58–10:59、11:00–11:01（`device-smoke-output.txt` 末两段）。
脚本头豁免声明同步更新：C6/C7 触点豁免之外，新增 C8 真实切换服务开关属测试通道动作、case 尾自恢复。

## 教训

- **"不伪造回执"的对称义务是"不隐瞒中断"**：取消路径不写报告=把系统级失败伪装成"没有发生过"，与虚报成功同罪。
- 全局状态机（running/report）的每一条退出边都要问一遍：用户此刻看到什么？"什么都没看到"通常就是答案里的缺陷。
