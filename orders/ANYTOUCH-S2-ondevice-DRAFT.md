# ANYTOUCH S2-实测军令（草案，锁门票）：真事件采集 + 录制→回放闭环

Order ID: ANYTOUCH-S2-ONDEVICE-DRAFT（未生效）
**开工前置门：T2 老板配给（BYOK 测试 Key）+ 老板"发"字。未过门本军令不得派工。**
Owner: Qoder 施工窗 ｜ 上位：总军令 S2 + RULINGS P0-1/P0-2 + PROTO-lead-gate

## L0 目标（一句话）

模拟器上：悬浮球开录 → 人工操作设置 App 8 步 → 停止 → RecorderCompiler 出步骤 JSON → 一键回放，全程零模型零网络，回放成功率 ≥90%（10 轮，模拟器口径；真机口径属 T3）。

## 与框架层的接口（STAGE-21 产物为唯一入口）

- 真机事件源：`AnytouchAccessibilityService` 采集钩子把 `AccessibilityEvent`/点击时节点快照转成 `RecEvent`（适配器在主窗窄口，不派 worker 进 service 文件）
- 采集白名单：仅 targetPkg 窗口内事件入流；IME/通知/悬浮窗自身事件恒丢弃

## STAGE-22（worker）可派范围：JVM 侧采集会话管理

- `recorder/session`：事件缓冲、背压上限（超限丢最旧+计数，禁静默丢帧）、会话序列化/反序列化（事件流落盘格式 v1）、断点续录
- 断言 ≥10；沿用 STAGE-21 冻结与验收纪律（测试 grep/ci-local 三件套）

## 主窗窄口（不派工）：Android 侧

- service 采集钩子 + 悬浮球录制开关 UI + 步骤列表编辑页（删步/改名/移序，调 STAGE-21 纯函数）
- `scripts/s2-smoke.sh`：adb 注入预置 RecEvent 会话文件（不经 UI）→ 触发转换 → 回放执行，判据 `S2SMOKE ok>=9 total=10 stopped=false`（模拟器口径）
- 双口径预备：MarvisPhone 保持 + 新增标准 AOSP 镜像 AVD（`pixel_smoke`）跑同链，ROM 锅/执行器锅分离

## 红线（机械判定）

- 执行/回放期零网络（ci 红线沿用）；录制产物 JSON 经 `Json.decodeToString` 全等往返
- 采集不到 targetPkg 外任何节点文本（隐私：跨 App 内容不落盘），单测+文件 grep 双验

## 交付纪律

同 PROTO-lead-gate：验收清单开工前立、evidence 落盘、worker 不 commit、上设备证据必含。
