# T3-K80 第二真机摸底：红米 K80 / HyperOS / Android 16（2026-09-22）

## 0. 设备档案
- 序列号 `9a2a0a97`，product `miro`，model `24122RKC7C`，**Android 16 / SDK 36**，arm64，1440x3200@600dpi，HyperOS 国行，初始 zh-CN。
- 老板第二台真机（T3-R4 口径下的补充样本）。安装/绑定/首触当日晚于 K40 批次完成。

## 1. 雷 14（HyperOS 静默回滚 adb 无障碍写入）
- 现象：`settings put secure enabled_accessibility_services` 写入后数秒被回滚为 null，无报错——**HyperOS 默认禁止 adb 改无障碍绑定**。
- 解法（老板手动）：开发者选项 → **USB调试（安全设置）** 开启 + 将 com.anytouch.app 加入调试应用白名单 → 写入即持久。
- 实证：白名单后绑定存活（清单条目留住 + dumpsys 2 命中）；后续 force-stop→重绑亦持久。
- 口径：这是**测试通道/装机引导**问题，不是产品缺陷；S4 的新机引导文案需覆盖小米系这两开关（已挂待办）。

## 2. 雷 12 复现：HyperOS 搜索框同样双通道拒 → 小米系通病
- 目标：设置首页 "Search settings"（android:id/input，focused=true）。
- 自家执行器 type_text：回执 `perform_failed`（setText false 且 pasteText false，与 K40/MIUI 一致）；uiautomator 复核零落字。
- 对照：自家 Compose 输入框同 ROM 上 `ok=1 total=1`——通道代码在 K80 无恙，拒的是 HyperOS 搜索框。
- **结论：雷 12 = 小米系（MIUI+HyperOS）通病**，非 K40 孤例。按 T3-R1 裁决不开第三通道（IME 劫持面），用户侧口径=录製时手动输入。

## 3. 雷 13 复现：force-stop 清绑定条目 → 小米系通病
- `am force-stop com.anytouch.app` 后 `enabled_accessibility_services` 直接变 **null**（AOSP 语义只禁用不清条目）。
- **结论：雷 13 = 小米系通病**。测试通道口径不变：禁裸 force-stop 自家包，解绑/重绑走 settings put。

## 4. 反例：MIUI 拒系统滚动**不是**小米系通病
- K80 首页可滚动容器 = `com.android.settings:id/scroll_headers`（nestedheaderlayout 在场但不带 scrollable）。
- scroll forward + click 链：`ok=2 total=2 stopped=false`——**HyperOS 接受 ACTION_SCROLL_FORWARD**，K40/MIUI 的 perform_failed 是 MIUI 特有。
- `docs/usage-notes.md` 的"MIUI 首页先手动滑一下"口径保留，限定 MIUI。

## 5. 语言切换真机实证 + SERVICE_INTERRUPTED 活体首演
- 自家执行器两步：LOCALE_SETTINGS 页点 "English"（ok=1/1）→ 系统对话框点 "确定"（ok=1/1）→ `system_locales=en-US`。
- 副作用（意外之喜）：语言切换引发无障碍服务重启，恰好打断一次在跑任务——logcat 实录 `run cancelled by service lifecycle ... SERVICE_INTERRUPTED`（22:00:30），**第 7 颗雷的修复首次在真机活体演示**（此前仅注入式验证）。

## 6. 停止球 K80 实证 + 坐标口径
- 跑中截图红色素聚类（右缘带 83% 覆盖率，bbox 138x147）→ 球心 **(1346, 1680)**（1440x3200 全分辨率，非缩放图目测）。
- 15s 定位期一点：≤2s 出 `user_stop`（"用户在定位期间按下停止"）。
- 面板挂起期同点再证：`user_stop`（"用户在二次确认等待期按下停止"）。
- 口径：`BALL_TAP` 是设备参数（K40/模拟器=1002 1272；K80=1346 1680），随设备档案走，不进产品代码。

## 7. 高危链 K80 自主两路径闭环（Reset-password 路径）
- 目标节点：小米账号页 "Reset password"（text 命中 PASSWORD 词表；同 K40 目标族）。
- 路径 1 超时默认拒绝：`SAFETY_GATE_BLOCKED / stop="PASSWORD:password"`（22:01:19 与 22:11:42 双份）。
- 路径 2 面板挂起期点球：`USER_STOP`（22:01:38 与 22:12:18 双份）。
- 路径 3 真人确认放行：K40 已实证（老板手点）；门禁代码设备无关，K80 不重复占用人手。
- 截图：`img/k80-confirm-panel-english.png`（面板全文 + 红球同框）、`img/k80-stop-ball-midrun.png`。
- 回执随档：`highrisk-confirm-receipts.txt` K80 段。

## 8. 整轮 smoke：8 绿 3 红，三红同根
- 第 3 轮（前置修复后）明细与归因见 `device-smoke-output.txt` K80 段：C2/C5/C7 红全部指向雷 12（脚本链第二步必须落搜索框输入）；高危链本体另经 §7 路径实证，不随脚本红误判。
- 测试通道修复（本批已 commit）：C1/C5/C7/C8 预置页由隐式 `ACTION_SETTINGS` 改显式 `com.android.settings/.Settings`——设备实证：HyperOS 上隐式 intent 偶发被 `com.milink.service`（ConnectivityActivity）劫持，导致首页节点零命中假红。

## 9. 结论（对 T3-R4 口径）
- 真机样本 = K40（MIUI/Android 14 级）+ K80（HyperOS/Android 16）双档；AVD 矩阵 API 31/34/35 补原生面。
- 小米系通病清单：雷 12（搜索框双通道拒）、雷 13（force-stop 清绑定）、雷 14（adb 无障碍写入需白名单）；MIUI 特有：拒系统滚动。产品能力面（定位/点击/滚动/自目标输入/停止球/高危门禁/中断回执/自愈）两台真机全实证。
