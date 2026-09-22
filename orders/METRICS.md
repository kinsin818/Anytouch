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

| 2026-09-22 | ANYTOUCH-S2-FW | STAGE-21 补案：裁决锁定用例 | 磁盘核实为真（3 用例、真断言、NodeTreeLocator 回放 assertSame）；主窗独立重跑 recorder 23/23（16+4+3）。其通知中"22（16+4+2）"计数仍不实，以 XML 为准 | 无（计数口径另记） | ACCEPTED |
| 2026-09-22 | ANYTOUCH-S2-FW | STAGE-22 录制会话状态机 | 一次通过：主窗独立重跑 session 16/16 绿（全 recorder 39/39 不回归）、ci-local PASS、红线 grep=0、范围核查干净；变异自检 4 轮证明用例有牙；三条契约严读法上报待裁（未擅动冻结件），主窗全部采纳补记入军令 | 无 | ACCEPTED |

累计：交付 7 次，拒收 1 次（虚报·零写盘，不计入施工返工），Retry 0 次。
教训条款（生效）：**worker 结案后的任何"交付通知"无磁盘证据=不存在**；主窗验收以 git status/测试重跑为唯一事实源，通知仅作提醒不作依据。此条已并入 PROTO-lead-gate 精神，AI OS 流程设计直接引用。

主窗自纠留痕（不计 worker 偏差，但记账防"主窗即对"错觉）：
- S1 收口 3 雷（doze 冻结/viewId 全 null/StateFlow 重放）均为初版设计缺陷，模拟器联调才现形——单测口径存在盲区，S2 起把"上设备"写进军令门禁。
- 冒烟脚本坑：组件名双段非法、force-stop 后不回绑（delete+re-put 强制重绑）、set -e 下 `grep -q && {}` 地雷、Git Bash 吞空参/路径转换。
- 工具教训：Edit 返回成功后仍须对关键前置文件 Read 复核（一次 import 替换实际未落盘）。
- 证据纪律自纠（本窗）：刷新 stage21-test-output.txt 时误覆盖 worker 区块——违反"证据只追加不覆盖"；已从 git HEAD 回填原文并在文件内标注。设备联调新发现 **ACTION_SET_TEXT 虚报**（performAction=true 不落字），主窗已加落字复核+回归锁定+设备复跑取证（evidence/S2/stage21-device-action-coverage.md）。
- 主窗自纠二批（type_text 全链路，09-22 09:20 UTC 结案）：①所谓"Compose 通道虚报"根因实为主窗自家 MainActivity 状态漏 `remember`（写进的值下一帧被样例重置）——排查方向此前全押在系统侧，教训：**先证自家代码，再疑平台**；②"paste 实测已落字"系 grep 单行 XML 计数的误读（命中的是屏上失败报告文本），已撤回并以截图重证；③落字复核按原线索重定位有假阴性缺陷（hint 被输入吃掉后配到别的节点），改为派发句柄 refresh() 活读。修复后双通道设备实证：经典 EditText 跨 App ok=2/2、自家 Compose ok=1/1，108 例全绿 + ci-local PASS（evidence/S2/img/ 双截图）。
- 主窗自纠三批（高危确认面板，09-22 09:55 UTC 结案）：S1 收口时主窗写的 `OverlayUi(applicationContext)` 令确认面板/停止球在设备上**从未显示过**（accessibility overlay 窗口 token 必挂服务 context），高危步全部静默秒拒——JVM 12 用例全绿掩盖了服务侧接线的死路，教训：**浮层这类平台强耦合代码，单测绿≠能看见，必须设备亲验**。次 bug：按钮决策只 resume 不 removePanel，确认后面板永久盖屏。两 bug 修复后三路径设备闭环（超时拒/取消拒/确认放行 ok=3/3），证据 `evidence/S2/stage-highrisk-confirm-device.md`。风险留档：面板挂起时并发 `uiautomator dump` 可诱发重绑重放+回执僵持（生产不可达，T2/T3 复现再立专项）。
