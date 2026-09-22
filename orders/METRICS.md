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
- 主窗自纠三批（高危确认面板，09-22 09:55 UTC 结案）：S1 收口时主窗写的 `OverlayUi(applicationContext)` 令确认面板/停止球在设备上**从未显示过**（accessibility overlay 窗口 token 必挂服务 context），高危步全部静默秒拒——JVM 12 用例全绿掩盖了服务侧接线的死路，教训：**浮层这类平台强耦合代码，单测绿≠能看见，必须设备亲验**。次 bug：按钮决策只 resume 不 removePanel，确认后面板永久盖屏。两 bug 修复后三路径设备闭环（超时拒/取消拒/确认放行 ok=3/3），证据 `evidence/S2/stage-highrisk-confirm-device.md`。风险留档：面板挂起时并发 `uiautomator dump` 可诱发重绑重放+回执僵持（生产不可达，T2/T3 复现再立专项）。同批续修：重绑重放不止测试环境——"陈旧注入在服务重绑后无人值守偷跑"是可达缺陷（设备复现），已修 60s TTL 即弃 + busy 即 consume，AppStateTest 3 例、全套 111 绿、设备实证过期丢弃 + smoke 5/5（evidence/S2/stage-highrisk-confirm-device.md 补案）。
- 主窗自纠四批（停止球响应性，09-22 10:19 UTC 结案）：设备实证发现 S1 主窗自写的 NodeTaskRunner 只在**步首**查 KillSwitch——定位轮询(15s)/二次确认(15s)/落字复核(4s)三类长循环对点球完全无感，末步定位期被 kill 时整队以 `NODE_NOT_FOUND` 收官、user_stop 归因丢失。修复：三处循环逐轮 poll kill（响应粒度=locatePollMs 250ms）+ 确认等待 waiter/killer 竞速（面板经 invokeOnCancellation 自动收起）+ 未落字失败回执前先判 kill 防抢占归因 + 末步步界补发回执；中间步 kill 仍归因下一步（既有测试语义保留）。新增 4 例 JVM 测（含耗时上界断言防"测了个寂寞"），全套 115/115 绿、ci-local 红线 A–E PASS、smoke 第 3 轮 5/5。设备实证：点球后 ≤0.5s 出 `stop="user_stop"`（修复前同场景等 11s 且归因错）。教训并入盲区清单：**安全类信号（kill/门禁）必须在每一个等待环内 poll，步首检查≠即时停止**。踩坑补记：coroutines 1.9.0 无 `Deferred.awaitOrNull`（1.10 才有），首版编译失败改 `await()`+定向 catch；测试注入 JSON 含空格须外层双引号内层单引号走设备 shell。证据 `evidence/S2/kill-ball-device.md` + 截图 + smoke 输出第 3 轮段。
- 主窗自纠四批·续（确认面板触点吞噬，09-22 10:28 UTC 结案）：Task #15 设备实证时再挖一雷——focusable 的确认面板未加 `FLAG_NOT_TOUCH_MODAL`，Android 上默认 touch-modal 吞掉**面板边界外**全部触点，挂起期停止球点不动（幸有 15s 超时默认拒绝兜底，无假通过但急停不可用）。修复一行 flag + 设备复测：挂起期点球 ≤1s 出 `stop="user_stop"`（归因"二次确认等待期"），面板/球撤净、高危步零派发。教训并案：**浮层盲区三连（context 错→面板不撤→触点吞噬），OverlayUi 每一行都要设备亲验，JVM 对窗口系统行为零覆盖**。过程另含两次测试通道无效尝试（错误 resource_id 线索、force-stop 顺手杀自家服务后忘重绑），均如实留痕 `evidence/S2/kill-ball-device.md` 追加段；期间 60s TTL 与 fail-closed 语义在异常路径上连续兑现。
- 回归固化（09-22 10:33 UTC）：device-smoke 增 C6 停止球即时响应（注入→+4s 点球→断言 `stop="user_stop"` 且 ≤5s，坐标仅测试通道模拟手指并在脚本头留痕豁免），连跑两轮 6/6 PASS 归档。浮层雷共 3 颗（context/撤面板/touch-modal）+ 等待环 kill 盲区 1 颗全部转为可重跑断言。
- 回归固化续（09-22 10:37 UTC）：再加 C7（面板挂起期点球，专防 touch-modal 雷复发），两轮 7/7 PASS（C6/C7 回执 0-3s）。设备冒烟矩阵现覆盖：正常链×3、定位失败负例、超时默认拒绝、等待环 kill、面板期 kill。
- 主窗自纠五批（running 悬挂·执行器永久失能，09-22 10:50 UTC 结案）：dump-under-panel 遗留的"回执僵持"专项复核正名——真根因是服务进程被重启时 runTask 收到协程取消，`catch (CancellationException) { throw e }` 直接跳过收尾，`AppState.running` 永挂 true，之后每一条任务都被 busy 丢弃、执行器**永久失能**（设备实证 10:44:04 新注入被静默丢弃）。"生产不可达"留档作废：系统随时可重启无障碍服务，此路生产可达。修复：runTask 收尾（hideStopBall/running=false/stopForeground/consume）全部移入 `try/finally` 无条件执行，取消路径另加 `Log.w "S1SMOKE run cancelled by service lifecycle … 回执缺席以此行为准"`（不伪造回执必须配必留痕）。设备复测同场景：10:45:13 取消留痕在日志、10:45:33 新任务照常执行并出正确回执（NODE_NOT_FOUND 归因无误），执行器自愈成立。全套 115 绿、ci-local PASS、device-smoke 7/7 连跑两轮（10:46-10:47、10:48-10:49 均归档）。教训：**协程取消不是"什么都不做"的免罪符——凡改全局状态的挂起函数，收尾必须 finally 化；把"生产不可达"写进留档前先问一句系统会不会主动重启这个服务**。证据 `evidence/S2/kill-ball-device.md` 追加段 + `evidence/S2/stage-highrisk-confirm-device.md` 风险留档更新 + `evidence/S2/img/dump-under-panel-HANG-check.png`。
