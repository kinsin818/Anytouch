# v1.0.2 设备复验（avd34）+ 面板截屏竞态血账

裁决来源：`orders/RULINGS-20260922.md` § S5-R10 裁 1（面板文案说人话，排 v1.0.2）。
本件只记 v1.0.2 的设备面证据与一处测试通道修法；产品语义变更见 `evidence/S5/release-notes-v1.0.2.md`。

## 0. 被验构建（三处对齐）

| 项 | 值 |
|---|---|
| APK | `app/build/outputs/apk/debug/app-debug.apk` |
| md5 | `775ebb89bef9f6e8632c4b07d0ab2eb6` |
| 字节 | 9,290,901 |
| 装机版本 | `versionCode=3` / `versionName=1.0.2`（`dumpsys package` 原文见下 §4） |
| 靶机 | `emulator-5554`（avd34，API 34，1080x2400）；K40 `7ae4bfee` 本批零触碰 |

产品源文件最后改动时间均早于打包时刻 09:51:35（`OverlayUi.kt` 09:41:46、`HighRiskPanelCopy.kt` 09:41:23、
`HighRiskPanelCopyTest.kt` 09:50:26）；打包之后只改过测试通道脚本与 `.gitignore`——所以
JVM 435/0/0/0（app 347 + byok 69 + contracts 19，按分模块 XML 计数）与红线 A–I 全清对同一棵源树成立。

## 1. run1（含轮 4 红）：`RESULT fail=1 passed=11 rounds=5`，`RAW_EXIT=1`

控制台原文：`evidence/S5/raw/s5a-run1-console-round4-red.log`
轮 4 raw：`evidence/S5/raw/s5a-emulator-5554-photos-round4-20260925-095918.log`
轮 4 截屏：`evidence/S5/raw/s5a-panel-round4-100047.png`（**这张就是本案物证，别当废片**）

轮 1/2/3/5 全绿（每轮 ok=9/9 + 篓内外双真空，按屏定位命中 `(729,1391)`）。轮 4 连红四条：

1. `FAIL 轮 4 :: 面板按钮按屏定位失败` → 退死坐标 `(702,1294)` 试投；
2. `FAIL 轮 4 :: 首次确认点击未落`（面板窗 8s 后仍在焦）；
3. `FAIL 轮 4 :: 回执不达 … ok=8 total=9 stopped=true stop="DELETE:delete"` +
   `S1SMOKE-DETAIL code=SAFETY_GATE_BLOCKED msg=high-risk match never got the second confirmation: DELETE:delete`；
4. `FAIL 轮 4 :: 回收站仍有残留（三次对拍后 trashed=2 any=0）`。

**根因（量化，不是猜）**：判据是"焦点见到自家窗即 `exec-out screencap`"。轮 4 那一帧里面板底色
（`0xF2212121` 半透明深灰）像素数 = **0**，正常轮同窗口计数 = **104548**；也就是说截屏抓到的是
"压暗层已挂上、面板内容还没合成"的帧。判据没量到两枚钮 → 按纪律当场记红并退死坐标（v1.0.1 校准值
`(702,1294)`，v1.0.2 面板正文变长后钮实际在 y 1344..1438，死坐标落在正文区）→ 决策没被吃进 →
产品按 fail-closed 15s 默认拒（第 3 条红）。

**责任归属**：红记在测试通道，不记在产品。产品这一轮的表现恰恰是安全模型要的：没拿到二次确认就
不执行删除，且归因（`DELETE:delete` / `SAFETY_GATE_BLOCKED`）留在日志与回执里没上屏。
判据侧的账：`dumpsys window` 的焦点行只证明"窗在"，不证明"内容画完"。

## 2. 修法（三处，全在测试通道，产品代码零改动）

1. `scripts/s5-templates-smoke.sh` 面板块：改成"睡 1s → 拍 → 量 → 量不到重拍"，最多 3 拍；
   三拍全空才记红退兜底坐标。截屏命名加 `-tryN-`，每拍各留一张（证据只增不删）。
2. 兜底坐标改按 v1.0.2 实测 `(729,1391)`，注释明写"只作试投、不作判据"，并记下 v1.0.1→v1.0.2
   钮位移（685,1282 → 729,1391）就是"文案一改面板宽高就变"的实锤——与
   `HighRiskPanelCopy` KDoc 里那句警告对上。
3. `scripts/find-panel-confirm.py`：失败输出加 `dark-panel-px=<n>` 计数（n≈0 即"这帧根本没拍到面板"，
   与"拍到了但判据不认"分开），诊断行改纯 ASCII（原先中文/间隔号经 stderr 重定向进 RAW 变乱码）；
   顺手修了 RAW 里 `焦点=` 恒空的 typo（`MSYS_NO_PATHCONV 1` 少了等号）。

离线回归（不碰设备）：把 12 张历史面板截屏全喂给改后的检测器——v1.0.1 的 8 张仍逐字 `685 1282`，
v1.0.2 的 4 张（轮 1/2/3/5）逐字 `729 1391`，轮 4 那张仍如实失败并给出 `dark-panel-px=0`。

## 3. run2（修后干净跑）：`RESULT fail=0 passed=13 rounds=5`，`RAW_EXIT=0`

控制台原文：`evidence/S5/raw/s5a-run2-console-clean-5rounds.log`

| 轮 | 回执 | 磁盘终判 | 按屏定位 | 命中拍次 | 补点 |
|---|---|---|---|---|---|
| 1 | ok=9/9 stopped=false | 篓内外双真空 | (729,1391) | 第 1 拍 | 无 |
| 2 | ok=9/9 stopped=false | 篓内外双真空 | (729,1391) | 第 1 拍 | 无 |
| 3 | ok=9/9 stopped=false | 篓内外双真空 | (729,1391) | 第 1 拍 | 无 |
| 4 | ok=9/9 stopped=false | 篓内外双真空 | (729,1391) | 第 1 拍 | 无 |
| 5 | ok=9/9 stopped=false | 篓内外双真空 | (729,1391) | 第 1 拍 | 无 |

装载面 T0a/T0b/T0c 同轮全绿。用时 554s。零轮走兜底坐标、零轮补点。
raw 逐轮：`s5a-emulator-5554-photos-round{1..5}-20260925-10{07,09,11,13}*.log`，
截屏 5 张 `s5a-panel-round{1..5}-try1-*.png`。

面板正文（轮 2 截屏人读复核，`.smoke-tmp/panel-v102-crop.png` 为同一张的裁切）：

```
This step needs your OK

It may delete items here (photos, messages and the like). Deleted content is often gone for good.

Only confirm if you asked for this. If you don't answer, nothing runs.

[Cancel]   [Confirm and run]
```

屏上无规则 id、无命中字段名、无分类枚举——`HighRiskPanelCopyTest` 四条 JVM 锁把这条钉成硬约束。

## 4. ui-smoke 同构建复跑：41/0，SKIP 0

控制台原文：`evidence/S5/raw/ui-smoke-v102-console.log`（末两行：`汇总：通过 41 … 跳过 0`、
`ui-smoke: ALL PASS（含 0 条 SKIP）`）。装机版本原文取自该轮 `dumpsys package`：`versionCode=3`、
`versionName=1.0.2`。

## 5. 边界（照旧不洗）

- AVD ≠ 真机；本轮全部为 `emulator-5554`。K40 本批零触碰，S4 额度账 6/8 定格不动。
- 测试通道的 `input tap` 是用户手指的替身，不是手指本身；"面板窗撤焦"只证明决策被吃进，不证明真人点得中。
- 执行期零网络：全程零模型请求（模板硬编码、执行面零模型），凭据未参与本轮。
- 判据只加不减：本轮新增的是"重拍 + dark 计数"，未删任何原有断言；轮 4 的红与物证全部留档，
  run2 的绿不覆盖 run1 的账。
