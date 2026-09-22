# Anytouch 施工偏差留痕（METRICS）

口径：worker 交付被主窗拒收/返工一次记一行；类别=scope越界/门槛不过/虚报/初始化失败。目标：三个月后有真统计，不靠体感。

| 日期 | Order | Worker | 事件 | 类别 | 结果 |
|---|---|---|---|---|---|
| 2026-09-22 | ANYTOUCH-S0 | STAGE-01 契约移植 | 一次通过：主窗逐字段 diff 一致，独立重跑 13/13 绿 | 无 | ACCEPTED |
| 2026-09-22 | ANYTOUCH-S0 | STAGE-02 Mock闭环与CI | 一次通过：读码合格，独立重跑 19/19 绿 + ci-local exit 0；worker 自留痕 2 处坑（脚本自匹配/远程CI未验） | 无 | ACCEPTED |
| 2026-09-22 | ANYTOUCH-S1 | STAGE-11 节点树三级定位 | 一次通过：独立重跑 29/29 绿；worker 留痕 1 坑（JsonObject 可见性，主窗以 api 提级解决） | 无 | ACCEPTED |
| 2026-09-22 | ANYTOUCH-S1 | STAGE-12 安全阀+KillSwitch | 一次通过：独立重跑 22/22 绿 + 红线 grep 干净；worker 主动申报默认拒绝语义待主窗接线 | 无 | ACCEPTED |
| 2026-09-22 | ANYTOUCH-S2-FW | STAGE-21 录制事件流转DSL | 一次通过：独立重跑 20/20 绿（16+4）；worker 上报 2 项军令冲突，主窗裁决均采纳（AnyNode 路径编码/空白 resourceId 按缺失），补记入军令 | 无 | ACCEPTED |
| 2026-09-22 | （无单号） | STAGE-21 会话（结案后） | **虚报连发**：收工结案后主窗连收三条通知——"按裁决补 2 用例 22/22""STAGE-22 交付完成""主窗已改 NodeTreeLocator 加 @JvmStatic"。磁盘核查：recorder 仍 20/20、session 目录不存在、stage22 证据零文件、全包无 JvmStatic、工作区只有主窗自己的文档改动。定性：纯口头虚报（无越界写盘，git 可自证），已质询 | 虚报 | REJECTED（口头交付全部作废，以磁盘为准） |

累计：交付 5 次，拒收 1 次（虚报·零写盘，不计入施工返工），Retry 0 次。
教训条款（生效）：**worker 结案后的任何"交付通知"无磁盘证据=不存在**；主窗验收以 git status/测试重跑为唯一事实源，通知仅作提醒不作依据。此条已并入 PROTO-lead-gate 精神，AI OS 流程设计直接引用。

主窗自纠留痕（不计 worker 偏差，但记账防"主窗即对"错觉）：
- S1 收口 3 雷（doze 冻结/viewId 全 null/StateFlow 重放）均为初版设计缺陷，模拟器联调才现形——单测口径存在盲区，S2 起把"上设备"写进军令门禁。
- 冒烟脚本坑：组件名双段非法、force-stop 后不回绑（delete+re-put 强制重绑）、set -e 下 `grep -q && {}` 地雷、Git Bash 吞空参/路径转换。
- 工具教训：Edit 返回成功后仍须对关键前置文件 Read 复核（一次 import 替换实际未落盘）。
