# STAGE-31-B K40 真机复验（设备关最后一格，09-24 午后）→ 裁 **ACCEPTED（S31-B8）**

## 1. 这一格为什么必须真机

31-B 动的是**派发/复核边**（`executor/NodeTaskRunner.kt`：A1 假边重派、A2 SET_TEXT||PASTE 双通道派发边、A3 awaitLanded 落地判据下沉）。
派单书 §0-2 与裁决 S31-B1 钉死："没改坏"这句话必须由**模拟器全量 + K40（K80 在位则同批）复验**才配得上。
雷12（MIUI 持久拒 `ACTION_SET_TEXT`）**在模拟器上永远复现不了**——模拟器 SET_TEXT 恒成功，双通道的"拒"那半边在 JVM 与模拟器里都只能锁形、锁不到真拒答。

## 2. 靶与前置（全部磁盘事实）

- 设备：K40 `7ae4bfee`（alioth / Android 13 / MIUI V816），USB 调试由老板 09-24 午后开启（待办 17 消解）。
- 构建：主树 HEAD `72af452`（含 31-A/31-B/S3-F）现出 `assembleDebug`，apk md5 前缀 `37d2e199`。
  **过程一条如实记**：装机前发现盘上旧 apk（13:39，md5 `ff47a142`）mtime **早于** S3-F 落进工作树的最后一处源码改动（13:41），
  且 Gradle 报 up-to-date 未重打包——虽差异仅一处注释（字节码等价），仍强制重出包后才装机（"同一枚 APK"不靠推断）。
- 装机：`adb install -r`（禁 force-stop，雷13）；装后 `dumpsys accessibility` 自证"Anytouch 执行器"仍在 Bound services。
- 屏幕：`isKeyguardShowing=false` 才动；`screen_off_timeout` 提到 10min（防长轮息屏假红）。
- 同轮先后：byok-smoke `[K5]`（44 断言无失败，见 `evidence/S3/slice-e2-k40-key.md` §6）→ device-smoke `[K5b]`。两批不抢同一台机，串行。

## 3. 判据口径（不拿模拟器期望冒用真机）

归档口径两处：`evidence/S2/t3-k40-first-contact.md` §"整轮脚本真机轮：5绿6红，红项全部归因已知……**真机不适配模拟器脚本口径，R3 后按真机 profile 手测关键链为准**"；
裁决 S31-B4。故 31-B 的 K40 复验判据 = **① 红项 ⊆ 已知名单（新红=零）② 关键链在真机上绿（派发/复核/拒答显式说话）③ 零假绿**——不是"13/13"。

## 4. 读数与逐条对名单（raw `[K5b]` 段，`byok-smoke-k40-e1pre.log` 内，二进制追加）

`device-smoke: 有失败项 / DEVICE_SMOKE_RC=1`：**5 红 9 绿**（15 项含后补的 C5x/C7x/C8b/C9/C10）。

| 本轮 | 读数 | 对 09-22 首跑名单 | 归因 |
|---|---|---|---|
| C1 前置+混合链 | `settings_homepage_container` L1 零命中 | **在列**（MIUI 首页容器不同名） | ROM 词表差；脚本自带标注"不归产品" |
| C2 type_text 经典EditText | `performAction 失败: type_text @ root/0/0/…/1`，ok=1 total=2 | **在列**（MIUI 持久拒 SET_TEXT，R1 口径） | 同一节点路径、逐字同形态；双通道在真拒答下**诚实报 perform_failed**，无假成功 |
| C5 高危超时默认拒 | 同一路径 perform_failed（链死在 SET_TEXT 步，未达密码页） | **在列**（C5 与 C2 同链同因） | 上游拒答截断，非确认链失效（确认链真机半边由 byok/历史轮判过） |
| C7 挂起期点球即停 | 1s 即 `perform_failed`（面板未起=链早死在 SET_TEXT 步）；C7x 触摸 0 次 | 同族（上游截断） | 外部触点嫌疑被 C7x 洁净锁排除 |
| C3 / C4 | **双双绿** | 首跑的队列 conflation **未复现** | 净改善 |

绿 9：C3、C4、C5x、C6、C7x、C8、C8b、C9、C10。名单外新红：**0**。

## 5. 结论与边界

- **裁 31-B ACCEPTED（S31-B8）**：红项全落已知名单且形态逐字同首轮（=31-B 未引入新分歧），派发/复核关键链真机绿，
  持久拒每一格都显式说话（EXECUTOR_ERROR+节点路径+步级计数）——第③判据"零假绿"由 C2/C5/C7 的拒答形态**本身**兑付。
  #38 的 A1/A2/A3 随之清零。
- 诚实边界：① K80 不在位，"在位则同批"括注未触发（如实记，非漏跑）；② C5/C7 的确认链真机半边本轮被上游截断没重走到，
  其既有真机证据来自 T3 历史轮与 C7x 洁净锁，本轮不冒领也不撤销；③ 复验用同构建已锁 md5，`E9_APK` 未单传给 device-smoke（该脚本无 E9 段，不适用）。
