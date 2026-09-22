# Anytouch 项目状态（总窗口读取点）

> 协议：任何上位总窗口读本文件即获得全局进度，无需读对话。每次里程碑由施工窗（Qoder）更新。
> 本文件 = 施工窗 → 总窗口的唯一汇报口。细证据不进此文件，只放指针。

## 一句话进度

S0 奠基、S1 核心执行器完成；S2 框架层推进中：STAGE-21（含裁决锁定补案）与 STAGE-22 RecorderSession 均已验收（**7 worker 交付 / 1 口头虚报拒收**，全 recorder 39/39 绿；STAGE-22 一次通过 16/16，三条契约严读法主窗采纳补记军令）。设备联调 **type_text 全链路结案**：曾定性"SET_TEXT 虚报雷"，根因查明为主窗自家 Compose 状态漏 `remember`（复核另修两处缺陷：句柄活读替代线索重定位、grep 误证撤回）；修复后模拟器双通道实证 ok——经典 EditText 跨 App ok=2/2、自家 Compose ok=1/1，落字复核 fail-closed 与 PASTE 兜底作为执行器契约保留（`evidence/S2/stage21-device-action-coverage.md` 补记+双截图）。实测层仍等 T2/T3 配给。今日已产出 demo 录像打脸"一天出不了 demo"论；type_text 修复后又录 S2 输入链 demo（click 搜索框→写入 wifi→点中 Wi-Fi 结果，ok=3/3，`evidence/S2/demo/`）。高危二次确认链设备实证补两个真 bug（overlay 必须挂服务 context；决策即撤面板），三路径闭环：超时拒/取消拒/确认放行 ok=3/3；顺带把设备复现的"陈旧注入重绑偷跑"修成 60s TTL 即弃（全套 111 例绿、smoke 5/5，`evidence/S2/stage-highrisk-confirm-device.md`）。停止球响应性缺陷也已修：KillSwitch 原只在步首被查，定位轮询/二次确认/落字复核三类长循环对点球无感（实测末步 kill 丢归因）——三处循环逐轮 poll kill + 末步步界补发回执，设备实证点球→`stop="user_stop"` 回执 ≤0.5s（修复前需再等 11s 且误归因 NODE_NOT_FOUND），全套 115 例绿、smoke 第 3 轮 5/5（`evidence/S2/kill-ball-device.md`）。确认面板挂起期再挖一雷：focusable 面板默认 touch-modal 吞掉面板外触点，球在面板期不可点（超时拒兜住、无假通过）——补 `FLAG_NOT_TOUCH_MODAL` 后设备实证挂起期点球 `stop="user_stop"`（归因"用户在二次确认等待期按下停止"），面板/球撤净、高危步零派发。残留风险专项也结案：dump-under-panel 的"回执僵持"实为服务重启取消 runTask 后 `AppState.running` 永挂、后续任务全被 busy 静默吞（设备实证永久失能），已修 try/finally 无条件收尾+取消留痕，同场景复测执行器自愈（`evidence/S2/kill-ball-device.md` 追加段）。**T2 首批已结案（Key 配给到位）**：老板 15 把 NVIDIA NIM Key 15/15 验真，但附带映射表 7 个模型名在本账号目录全部不存在（勘误待回禀，实际可用 z-ai/glm-5.3、deepseek-ai/deepseek-v4.1-flash 等，无 qwen/minimax）；新增 host 侧 `:tools:compiler` 创建期编译链（`DslCompiler` 校验跑在原始 JSON 文本上防 ignoreUnknownKeys 静默吞掉模型偷塞坐标，12 例绿，Key 只进 env、错误信息 `nvapi-***` 脱敏，模块位于红线 A/C 扫描路径外已留豁免口径），端到端首次打通：中文意图→AI 编译→12 项校验→设备注入→`S1SMOKE ok=3 total=3 stopped=false` + Bluetooth 二级页截图。第 11 颗雷是工具链自己的：同一 prompt python 直调 3/3 绿、gradle `--intent <中文>` 稳定拒编空数组，根因为中文经 Git Bash→gradle --args→JVM 两段转码成乱码（模型拒编是对的，输入通道是坏的），改 `--intent-file` UTF-8 直读消变量（`evidence/S2/t2-key-adaptation.md`）。同层黑洞再补一刀：取消路径此前只留日志痕、`lastRunReport` 不写——用户在界面只见上一条陈旧回执，在跑任务"无声消失"；现中断即写 `SERVICE_INTERRUPTED` 回执（results 显式标注不可信），设备实证解绑瞬间回执入日志与 UI（截图）、重绑后照常执行，smoke 增 C8/C8b 双锁、两轮 9/9（`evidence/S2/service-interrupted-receipt.md`）。第 9 刀补注入总线其余退出边：过期丢弃边设备实证写 `REQUEST_EXPIRED` 回执（作废+留痕+零执行三合一，UI 可见截图），busy 边实测揭示旧口径失真——执行中再注入实为 conflation 并队串行而非即弃，busy 分支降级为"防 running 泄漏"的保险丝（如实修正注释与留档，全套 119 绿、smoke 9/9 三轮）。第 10 刀对齐 fail-closed 三形态：停止球 `addView` 失败曾被静默吞掉——唯一急停手段缺席照常开跑；现挂不上球即写 `SAFETY_BALL_UNAVAILABLE` 回执拒跑（与落字复核、二次确认同构），拒跑早退置于 try/finally 内（首版 edit 曾插错位置、恰好复刻第 7 颗雷形态，编译前自查自愈并留痕），smoke 9/9 四轮证明球正常路径零回归（`evidence/S2/safety-ball-failclosed.md`）。

## 阶段面板

| Stage | 状态 | 门禁 | 指针 |
|---|---|---|---|
| S0 奠基+契约+CI | ✅ 完成 | — | `docs/ANYTOUCH-S0-final-report.md`，commits de3d6b4→2c7ddf1 |
| S1 核心执行器 | ✅ 完成（模拟器口径） | — | `docs/ANYTOUCH-S1-final-report.md`，commits 5049b05→0c5ebbc；demo 录像 `evidence/S1/demo/`；真机 ≥95% 归 T3 |
| S2 录制 | 🟡 框架层：STAGE-21/22 双验收（会话状态机 16/16 绿，清单先行 3e67664，worker 会话 18a07ee7）；设备回归固化为 `scripts/device-smoke.sh`（9/9 PASS：高危超时默认拒绝负例 + C6 停止球即时响应 + C7 面板挂起期点球即停 + C8/C8b 执行中解绑中断回执与自愈）；输入链 demo + 安全链双幕 demo（确认放行/挂起期急停）`evidence/S2/demo/`；停止球全局停设备实证 `evidence/S2/kill-ball-device.md`（115 例绿）。**T2 编译链首批已结案**：NVIDIA NIM 单通道端到端打通（中文意图→AI 编译→12 项校验→设备执行 ok=3/3，`evidence/S2/t2-key-adaptation.md`）。实测层 🔒 | 实测层需 T2 余量（Gemini/GPT/Haiku 三档 Key）+ T3 | 军令 `orders/ANYTOUCH-S2-ORDER.md`；验收清单+设备覆盖面+冒烟输出 `evidence/S2/`；门票裁决 `RULINGS-20260922.md` P0-1 |
| S3 兜底 | 🔒 门票门禁 | T3（需真机，含 MediaProjection 判官项） | 同上 P0-3 |
| S4 上线 | 🔒 门票门禁 | T-US + 模板合规预检 | 同上 P0-4 |
| W 轨 Windows 先行验证 | 🔒 老板拍板人力 | — | RULINGS P1-2 |

## 老板待办（阻塞项）

1. T2 配给：NVIDIA NIM 通道**已到位并结案首批**（15/15 Key 验真，端到端编译链打通）；仍缺 BYOK 另三档（Gemini/GPT-4o-mini/Haiku）。**需老板回禀**：Key 文件附带的"角色→模型"映射表 7 个模型名在本账号目录全部不存在（无 qwen/minimax 任何条目），实际可用为 z-ai/glm-5.3、z-ai/glm-5.3-flash、deepseek-ai/deepseek-v4.1-flash、openai/gpt-oss-20b、mistral-large-2-instruct、deepseek-coder-6.7b、llama 系列——表来自另一账号/区域还是已过期？角色分配表需按实际目录重写
2. T3 配给：真机预算（三星×2/Pixel×2/摩托×1/小米国际×1，二手 ¥3–5k）；**小米真机老板口头承诺即将配给**——到位后首件事：真机适配专项（overlay/停止球/前台服务/doze/ROM 白名单逐厂商核，模拟器上"不可达"的守护分支在真机重新过一遍）
3. P0-2 裁决确认：免费/付费边界按冻结共识执行中，如需翻案须老板明示
4. worker 会话可归档（UI 操作）：S0 STAGE-01/02、S1 STAGE-11/12、S2 STAGE-21（含结案后虚报会话）；STAGE-22 新会话在建

## 纪律数据

`orders/METRICS.md`：交付 7 / 拒收 1（口头虚报，零写盘）/ Retry 0；S1 收口挖出 3 颗真机雷（doze 冻结/viewId 全 null/StateFlow 重放），S2 联调又挖出第 4 颗（SET_TEXT 虚报成功——回执自此必须落字复核）、第 5 颗（KillSwitch 只在步首查询，停止球在定位/确认/复核三类长等待里形同虚设——安全类信号必须逐轮 poll 所有等待环）、第 6 颗（focusable 确认面板默认 touch-modal 吞掉面板外触点，挂起期停止球点不动——浮层间触点归属同为平台强耦合盲区）、第 7 颗（runTask 取消路径跳过收尾，服务被系统重启=执行器永久失能——协程收尾必须 finally 化，"不伪造回执"要配"必留痕"）、第 8 颗（取消路径不写执行报告=中断无痕，与虚报成功同罪——"不伪造回执"的对称义务是"不隐瞒中断"）、第 9 颗（停止球 addView 失败被静默吞，急停缺席照常开跑——安全模型的前提必须每次执行前自证）、第 11 颗（工具链自家雷：同一 prompt python 绿、gradle 中文命令行参数乱码致模型稳定拒编——探针的输入通道本身是待验对象，跨进程边界必须双路对拍）。JVM 单测盲区持续兑现为军令门禁。
治理文件：军令协议 `orders/ANYTOUCH-S0-ORDER.md` 为范本；裁决案卷 `orders/RULINGS-20260922.md`；主窗硬约束 `orders/PROTO-lead-gate.md`（军令对照验收/拆分可证伪，老板 2026-09-22 拍板）。
