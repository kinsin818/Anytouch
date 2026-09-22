# ANYTOUCH S1 军令：核心执行器（节点树定位 + 安全阀）

Order ID: ANYTOUCH-S1-EXECUTOR-20260922
Owner: Qoder 施工窗 ｜ 上位：总军令第四节 S1 + `RULINGS-20260922.md`（P0-1/P0-3 生效）
门禁状态：S1 在允许开工范围内（T1 已完，本军令不依赖 T2/T3）；模拟器环境已实证（MarvisPhone AVD + API-34 google_apis 镜像 4.2G 在位）

## L0 目标（一句话）

真无障碍执行器：给定一份任务 JSON（STAGE-02 ClosedLoop 的 Action 序列语义），在模拟器上对**系统设置 App** 完成"打开→定位→点击→断言"全自动链路，执行期零网络零模型调用。

## L1 承诺点（钉到行，worker 不得偏离）

主窗已交付脚手架（commit 见 git log）：`:app` AGP8.11+Compose、`AppState.serviceConnected`、`AnytouchAccessibilityService` 骨架、manifest 权限集。
- **冻结文件（worker 零改动，接线由主窗窄口集成完成）**：`app/src/main/AndroidManifest.xml`、`app/src/main/kotlin/com/anytouch/app/service/AnytouchAccessibilityService.kt`、`AppState.kt`、`MainActivity.kt`、全部 Gradle 配置、`core/contracts`、`pipeline` 包
- STAGE-11 独占目录：`app/src/main/kotlin/com/anytouch/app/locator/` + `app/src/test/kotlin/com/anytouch/app/locator/`
- STAGE-12 独占目录：`app/src/main/kotlin/com/anytouch/app/safety/` + `app/src/test/kotlin/com/anytouch/app/safety/`
- 依赖白名单：两 worker **零新增依赖**（JVM 单测 only，Android API 经抽象接口注入）
- 定位方式硬约束：**禁止坐标定位**，一律节点引用（resource-id/text/contentDescription/层级路径），坐标仅作 `bounds.center` 派生（WorkBuddy 初审红线 1、总军令"节点引用而非坐标"）
- 悬浮窗实现通道：`TYPE_ACCESSIBILITY_OVERLAY`（服务提供，免 SYSTEM_ALERT_WINDOW 授权）——manifest 里那条旧权限位主窗在 S1 收口时移除

## L2 行为断言（验收测试级，军令即测试用例）

### STAGE-11 `NodeTreeLocator`（节点树三级定位）
输入抽象：`UiNode` 接口（`resourceId/text/contentDesc/children/bounds/clickable` 等最小集）——**Android AccessibilityNodeInfo 不直接进引擎**，由主窗适配器实现，保证 JVM 可测。
1. 一阶：resource-id 精确命中优先；多命中时按 `instance` 序号取
2. 二阶：text/contentDescription 等值匹配（trim 后全等，非 contains）
3. 三阶：层级路径匹配（如 `Window>ListView>clickable=true[2]`）
4. 三阶全空 → 返回 `LocatorMiss`（含三级尝试记录，供失败归因 6 类之"未找到节点"用），**不猜、不降级到坐标**
5. 每级命中写 `LocatorHit(level, nodeRef)`——回执卡/节点 diff（E2）的数据源
6. 断言用例 ≥12：每阶正反例、多命中、深层嵌套、miss 全记录

### STAGE-12 `SafetyGate` + `StopBall` 契约层（安全阀）
1. `HighRiskMatcher`：硬编码白名单（支付/转账/发送/删除/密码/购买/确认订单类关键词，中英文双语集）作用于节点 text+content-desc+resource-id 三字段
2. 命中 → 返回 `RequiresSecondConfirm(matchedRule)`；**默认拒绝**：规则装载失败时任何高危动作不放行
3. `KillSwitch`：全局停止状态单例（flow），任何 step 执行前查询，STOP 后队列即刻中止，产出 Command 风格停止回执（复用 STAGE-02 ClosedLoop 语义）
4. 断言用例 ≥10：白名单每类命中例、非命中例、"发送出去/发送邮件"不误杀为"支付"、装载失败默认拒、kill 后队列中止

### 主窗窄口集成（不派工，主窗亲自做）
- AccessibilityNodeInfo→UiNode 适配器 + 悬浮球 UI（Compose overlay）+ 二次确认对话框 + 服务接线
- 冒烟验收：`scripts/s1-smoke.sh`（起 AVD→装 APK→adb 开无障碍→跑 20 次"设置→关于→连点版本号区域"类任务）：定位成功率 ≥95%（模拟器口径；真机 ≥95% 属 T3，本 stage 不虚标）

## L3 实现留白
locator 内部算法、词表组织方式、overlay 视觉——worker 自决，但 L2 断言必须全绿。

## 红线（CI 硬检查，扩展 ci-local.sh）
执行期源码内零 `java.net/HttpURLConnection/OkHttp` 引用（S3 前）；无坐标硬编码点击；高危规则文件缺失=拒绝执行而非放行。

## 交付纪律（同 S0）
产物落盘 docs/evidence、坑自纠留痕、禁 fake success、不 commit、禁区清单照抄 S0。
worker 标题：`Anytouch | STAGE-11 | 节点树三级定位`、`Anytouch | STAGE-12 | 安全阀与全局停止`
