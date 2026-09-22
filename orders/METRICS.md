# Anytouch 施工偏差留痕（METRICS）

口径：worker 交付被主窗拒收/返工一次记一行；类别=scope越界/门槛不过/虚报/初始化失败。目标：三个月后有真统计，不靠体感。

| 日期 | Order | Worker | 事件 | 类别 | 结果 |
|---|---|---|---|---|---|
| 2026-09-22 | ANYTOUCH-S0 | STAGE-01 契约移植 | 一次通过：主窗逐字段 diff 一致，独立重跑 13/13 绿 | 无 | ACCEPTED |
| 2026-09-22 | ANYTOUCH-S0 | STAGE-02 Mock闭环与CI | 一次通过：读码合格，独立重跑 19/19 绿 + ci-local exit 0；worker 自留痕 2 处坑（脚本自匹配/远程CI未验） | 无 | ACCEPTED |
| 2026-09-22 | ANYTOUCH-S1 | STAGE-11 节点树三级定位 | 一次通过：独立重跑 29/29 绿；worker 留痕 1 坑（JsonObject 可见性，主窗以 api 提级解决） | 无 | ACCEPTED |
| 2026-09-22 | ANYTOUCH-S1 | STAGE-12 安全阀+KillSwitch | 一次通过：独立重跑 22/22 绿 + 红线 grep 干净；worker 主动申报默认拒绝语义待主窗接线 | 无 | ACCEPTED |

累计：交付 4 次，拒收 0 次，Retry 0 次。

主窗自纠留痕（不计 worker 偏差，但记账防"主窗即对"错觉）：
- S1 收口 3 雷（doze 冻结/viewId 全 null/StateFlow 重放）均为初版设计缺陷，模拟器联调才现形——单测口径存在盲区，S2 起把"上设备"写进军令门禁。
- 冒烟脚本坑：组件名双段非法、force-stop 后不回绑（delete+re-put 强制重绑）、set -e 下 `grep -q && {}` 地雷、Git Bash 吞空参/路径转换。
- 工具教训：Edit 返回成功后仍须对关键前置文件 Read 复核（一次 import 替换实际未落盘）。
