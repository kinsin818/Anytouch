# Anytouch 施工偏差留痕（METRICS）

口径：worker 交付被主窗拒收/返工一次记一行；类别=scope越界/门槛不过/虚报/初始化失败。目标：三个月后有真统计，不靠体感。

| 日期 | Order | Worker | 事件 | 类别 | 结果 |
|---|---|---|---|---|---|
| 2026-09-22 | ANYTOUCH-S0 | STAGE-01 契约移植 | 一次通过：主窗逐字段 diff 一致，独立重跑 13/13 绿 | 无 | ACCEPTED |
| 2026-09-22 | ANYTOUCH-S0 | STAGE-02 Mock闭环与CI | 一次通过：读码合格，独立重跑 19/19 绿 + ci-local exit 0；worker 自留痕 2 处坑（脚本自匹配/远程CI未验） | 无 | ACCEPTED |

累计：交付 2 次，拒收 0 次，Retry 0 次。
