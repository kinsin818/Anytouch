# Anytouch S1 收口报告（施工总窗口）

日期：2026-09-22 ｜ 门禁口径：军令 `orders/ANYTOUCH-S1-ORDER.md` L0
结论：**S1 完成**。task JSON → 模拟器设置 App 三级自动化 20/20 = 100%；执行期零网络、零模型调用（红线机检覆盖）。真机 ≥95% 口径属 T3，不在此虚标。

## 1. 交付链

| 环节 | 内容 | 证据 |
|---|---|---|
| STAGE-11（worker） | 节点树三级定位（resource-id → text/desc trim 全等 → 层级路径），29 JVM 测试 | `docs/ANYTOUCH-S1-STAGE-11-report.md`、`evidence/S1/stage-11/`，commit `f2819e6` |
| STAGE-12（worker） | 高危匹配 + KillSwitch + 安全门（默认拒绝），22 JVM 测试 | `docs/ANYTOUCH-S1-STAGE-12-report.md`、`evidence/S1/stage-12/`，commit `0c01e4f` |
| 主窗窄口接线 | UiNode 适配器 / NodeTaskRunner / 悬浮球停止（TYPE_ACCESSIBILITY_OVERLAY）/ 二次确认浮层 / StopReceipt→Command 无损映射 / 移除 SYSTEM_ALERT_WINDOW / CI 红线 C·D·E | commit `ccb8474`，`evidence/S1/lead-ci-local.txt` |
| 主窗收口 | 三雷修复（见 §2）+ 冒烟链校准 20/20 | commit `0c5ebbc`，`evidence/S1/smoke-20rounds.txt`、`lead-ci-local-final.txt` |

测试总量：`:core:contracts` 19 + `:app` 67 = 86 全绿；ci-local 红线 A–E 全 clean。

## 2. 真机（模拟器）联调挖出的三颗雷——均为单测测不出、只有上设备才现形

1. **doze 冻结**：`moveTaskToBack` 后进程转 cached，系统冻结进程 → Main 线程协程轮询整体停摆，`rootInActiveWindow` 永远"未就绪"。修法：执行期挂前台服务（FOREGROUND_SERVICE_SPECIAL_USE + specialUse 型，34 三参 startForeground，镜像拒绝该 type 则退两参）。
2. **viewIdResourceName 全 null**：`accessibility_config` 缺 `flagReportViewIds` → 一阶（resource-id）定位在真设备上形同虚设；同时补 `flagIncludeNotImportantViews`（被裁掉的非重要节点，如 Settings 网络页 "Airplane mode" 标题）。此雷不冒烟永不自知——JVM 假树永远带 id。
3. **StateFlow 重放**：任务总线用 `MutableStateFlow`，服务重绑时新 collect 会立即收到旧值 → 同一任务再执行一遍。修法：执行完 `AppState.consume(request)` 作废（按 id 比对，不误伤新请求）；解码 catch 补 `CancellationException` rethrow，取消不伪造失败回执。

## 3. 模拟器镜像观察（AVD "MarvisPhone" = 本机自建的 API-34 google_apis 官方镜像；记录在案，真机复核归 T3）

> 命名澄清（老板 2026-09-22 纠正）：`MarvisPhone` 只是 AVD 名，不代表任何厂商 ROM。以下行为属 Google 官方模拟器镜像/权限模型，真机大概率不复现——因此**不能当作"厂商兼容问题"记账，只能当作"模拟器保真度缺口"记账**。

- Settings "Internet" 页、permissioncontroller 角色页：窗口在场（focused/active）但 a11y 树恒不下发（`root=null`），uiautomator（UiAutomation 通道）可见——系统侧行为，非本产品缺陷；`root()` 已加 windows 按焦点/层级兜底扫描。
- 开关行同页二次点击有 RecyclerView 重绑动画竞态（performAction=false）→ 冒烟链改为**三级前进钻取**：Connected devices → Connection preferences → Bluetooth。
- 结论口径：以上属模拟器 fidelity 缺口；W 轨/真机（T3）需重跑同套冒烟脚本再签。

## 4. 对 worker 裁决请求的答复（结案）

1. **NODE_NOT_FOUND 是否入 contracts**：暂不入。现留 app 侧 `PipelineStopCode` 同形映射，S2 事件流若需要再走一道小军令扩枚举（扩冻结文件必须有军令依据）。
2. **PathPattern 语义**（`[n]` 0 基、仅认路径自带索引、instance 不参与三阶）：接受，维持现实现与测试。
3. **init-script 统一 testLogging**：接受，归 S2 基建小单，不占 S1。

## 5. 偏差留痕（相对军令文本）

- 悬浮球 UI：军令写 "Compose overlay"，实改纯 View——无障碍服务窗口挂 Compose 需手搭 LifecycleOwner，收益为负；行为不变（红■球 + 停止语义），此处即偏差声明。
- 冒烟任务链：军令样例 "System→About phone→Android version" 在本 ROM 无对应行，按脚本注释的替换链执行（同深度、同全节点定位）。

## 6. 下一步

S1 就绪待老板指令；S2 录制门票 = T2（BYOK 测试 Key），S3 = T3（真机预算），均在 `STATUS.md` 老板待办。
