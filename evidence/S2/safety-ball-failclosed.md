# 第 10 项：停止球挂不上 = 拒绝执行（fail-closed 补齐安全模型前提）

日期：2026-09-22（11:11–11:15 UTC）· 主窗自纠批次 · 关联：`kill-ball-device.md`（球响应性）、`service-interrupted-receipt.md`（留痕原则）

## 缺陷定性

全项目安全模型建立在"执行期悬浮停止球永远可点"之上（C6/C7 双回归锁都以此为前提），但支撑这个前提的代码
`OverlayUi.showStopBall` 曾把 `addView` 失败**静默吞掉**（`runCatching{}.onSuccess{}`，失败分支无痕）——
意味着 ROM 拒绝浮层时，执行器会在**唯一急停手段缺席**的状态下照常开跑。这是结构性不对称：
高危步没有确认面板会 fail-closed 拒执行，安全阀本身挂不上却静默降级。

## 修复

- `showStopBall` 返回 `Boolean`（已挂上/本就在位=true；addView 败=false 并留 W 痕）。
- `runTask`：挂不上球 → 写 `SAFETY_BALL_UNAVAILABLE` 丢弃回执（复用第 9 项 `droppedRunReport`）→ **拒跑**。
  拒跑的 `return` 特意置于 try/finally 之内——收尾四件套（撤球/running 落位/退前台/消费队列）对早退同样成立。
- `startForegroundCompat` 双形态皆败补 W 痕（doze 冻结=第 1 颗雷形态，任务继续但可归因）。
- `PipelineStopCode` 只增 `SAFETY_BALL_UNAVAILABLE`。

## 施工自愈留痕（如实记）

首版 edit 把拒跑 `return` 插在 try **之前**——若合入，running 永挂，恰好复刻第 7 颗雷。编译前读回自查发现，
移入 try 内并加注释"拒跑 return 也必须在 try 内"。教训：**给"必留痕"批次写代码时，自己先踩了一遍无痕坑**；
凡在 finally 化函数里新增早退分支，先问"这条 return 走得到 finally 吗"。

## 验证

- JVM：全套 app 119 例绿（改动后重跑）；ci-local 红线 A–E PASS。
- 设备：installDebug+重绑后 device-smoke **9/9 PASS**（11:13–11:14）——C6/C7 顺带证明布尔化改造后
  球的正常路径零回归（球挂不上时这两例根本无法存在）。
- 拒跑分支本体模拟器不可达（服务 context 的 TYPE_ACCESSIBILITY_OVERLAY 从未失败过，三雷批次反复实证）；
  按"guard 路径"处理：语义由代码审查+回执 builder 的 JVM 测覆盖，正常路径由 C6/C7 设备锁覆盖。真机 ROM
  若触发（T3 场景），日志/UI 双痕可直接归因。

## 结论

安全模型前提从"默认成立"改为"每次执行前自证"：证明不了急停手段在场，就不开始执行。与落字复核
（不证明落字就当没落）、二次确认（不证明用户同意就当拒绝）同构——执行器三处 fail-closed 至此齐形。
