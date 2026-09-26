# ANYTOUCH-S8c · K40 真机清 byok-smoke 7 行欠格 —— 施工回报（本批按硬闸停下上报，未起跑）

工单：`D:\Qoder\ops\orders\ANYTOUCH-S8c-K40清byok七行欠格-ORDER-20260926.md`
施工窗：可见 worker 窗（施工对象 `D:\Anytouch`）　日期：2026-09-26 17:50~18:0x
一句话：**K40 在线、包已对账到发布件、无障碍已绑，但机上存着老板那把真 Key（屏上 `Key saved on this device: ***JgYp`）⇒ 以带钥档起跑 `byok-smoke.sh` 的第一格消耗点 E5a 就是一次真 HTTPS 编译，与本批"一律不走真请求"正面冲突 ⇒ 当场停跑。已绿 0 格、仍欠 7 格，真模型请求消耗 0、S4 额度仍 6/8 未动。**

## §1 三态表（本批）

| 态 | 内容 |
|---|---|
| 已绿 | **0 格**（byok-smoke.sh 未起跑 ⇒ 没有任何一格有 PASS 资格） |
| 欠 + 原因 | 全部 7 行（#1–#7 逐行见 `debt-ledger.md` §A）。根因：脚本无"零消耗档"开关，带钥态下 E1 之后直达 `run_key_group`，E5a 即外呼 |
| 需老板的手 | ①「清除本机凭据」那一下（E11/#2 #3 #7 的前置，脚本自身明写不模拟手指点自家面板）；②额度授权（见 §4 算术：一轮带钥到底要 **3 次**，剩 **2**，超册） |

末行计数原文：**没有** —— 脚本未起跑，无 `byok-smoke: 断言 N 条…` 输出可入册。SKIP 未被记成 PASS（本轮连 SKIP 都没产生）。

## §2 跑前对账（工单 §2 逐条）

| 条 | 结果 |
|---|---|
| 1 `7ae4bfee` 在列 + 显式指定 | ✅ `adb devices -l` 见 `7ae4bfee device product:alioth model:M2012K11AC`；`export ANDROID_SERIAL=7ae4bfee`；emulator-5554 全程零指令 |
| 2 机上包＝1.0.6/7 且与发布件同值 | ⚠→✅ **首轮不合格**：机上 `0.1.0-s1 / versionCode 1`（09-24 15:43 装，base.apk md5 `37d2e199…`、9,273,307B）。按工单"不符→先装已发布件"：本次**匿名回对** release 直链（HTTP 200、9,389,205B、md5 `d61b9867fcdf164d73ed236ce61f76e1`）→ `install -r -g` Success → 装后 `versionCode=7/versionName=1.0.6`、机上 base.apk md5 与发布件**逐字同值**、`INTERNET: granted=true`。原文全在 `precheck-apk.txt` |
| 3 真机判定 | ✅ `ro.product.model=M2012K11AC`、abi `arm64-v8a`，不命中脚本 IS_EMU 名单 ⇒ 本批一切设备结论是**真机**结论，未冒领模拟器、反向亦然 |
| 4 唯一日志目录 | ✅ `raw/20260926-175045-7ae4bfee/`（本轮新增件；历史 raw 零删改，`evidence/S3/raw/byok-smoke-k40-e1pre.log` 等原样在册） |
| 5 运行中禁编辑脚本 | ✅ 未编辑任何脚本；`git pull --rebase` → Already up to date（HEAD `7cd3e57`），那 7 行在 HEAD 逐字复核有效，`ByokPanel.kt:144` 两串至今未变 |
| 独占 | ✅ 仅 1 枚 adb.exe（server 本体）、无 tcpdump、无 java/Gradle；`/tmp/*.lock` 只有 09-16/09-20/09-22 旧件，未动 |
| 无障碍前置 | ✅ 追加式绑定（原值 `com.mmnn.oce/.xk_Service` 留档未摘），`dumpsys accessibility` 见 `Anytouch Executor` capabilities=33 |

## §3 五关自检

1. **逐格三态表** ✅（§1 + `debt-ledger.md` §A 逐行对号）；末行计数**如实记"无"**；SKIP 未折成 PASS，屏上带钥痕迹未折成任何一格绿。
2. **同帧 ui-dump + 截图互证** ✅（仅对"机上当前是带钥档"这一条对账事实取证）：`raw/20260926-175045-7ae4bfee/ui-dump-credential-line.xml`（`byok_key_tail` 节点 + `…saved on this device: ***JgYp`）与同帧 `precheck-screen-keyed.png`（202,967B）互证；因未起跑，**没有**逐格断言的 dump 可交，此项按"无格可验"如实空。
3. **真模型请求消耗 0 / S4 额度仍 6/8** ✅：`adb -s 7ae4bfee logcat -d -s AnytouchRun:*` 全量 3 行入册，`S3SMOKE compile` 计数 **0**（只有两条 `config loaded tail=***JgYp` 与 buffer 头）。额度原文（`orders/METRICS.md:184`）：「额度账定格 **6/8、剩 2 不花**」。
4. **emulator-5554 零指令 / 无并行 Gradle / 无遗留进程** ✅：本窗对 5554 一条命令都没发（只在 `adb devices -l` 里被动出现在列）；tasklist 无 java.exe、无 tcpdump.exe；本窗无后台 logcat 流残留（未起跑即无 `start_log_stream`）。
5. **整枚 Key / 整枚激活码零入册 + 历史 raw 零删除** ✅：本窗全程只读屏上尾 4 位；探针 dump 做 Key 形态扫描 `grep -ao '[A-Za-z0-9]\{24,\}'` → **0 命中**（密码框 text 恒空，屏上只有 `***JgYp`）；`key.txt` 内容未被读取、未被打印；`evidence/S3/raw/byok-smoke-k40-e1pre.log` 等历史 raw 未被触碰（本轮只在 `evidence/S8c-k40-byok-debt/` 新目录内写件）。

## §4 本轮挖出的两条新事实（请主窗入账/裁决）

1. **带钥组一轮到底 = 3 次真请求**（E5a、E8 的 `ai_compile true`、E10 的 off 档各一次；E9 与 E11 自身零消耗）。剩余额度只有 2 ⇒ **即便老板只批 1 次也不够清 #4~#7**。这条与主窗 S4 批自己的在册算术同向（`orders/METRICS.md:183`：「批账 6/8、剩 2<全链 3 按 §2-4 再停手照报」）。⇒ "下一次真机批清完 7 行欠格"这句目标，在"额度 6/8 不动"的硬边界内**结构上不成立**，需要改口径。
2. **E9c 那两行其实零消耗**：E9 只做 `install -r` 同构建 + 现读屏上尾 4 位，外呼数为 0；它之所以本批够不到，纯粹是脚本把它排在 E5/E8 之后。⇒ 若主窗裁一条"判据只加不减"的**流程重排/零消耗档**（`debt-ledger.md` §C 路径 C），零额度可清 #1 #4 #5 #6（4 行），剩 #2 #3 #7 归"无凭据态"那半条腿，需老板点一次清除（点完本窗可用 `byok-credential-inject.sh` 零消耗把 Key 填回，尾 4 仍 `JgYp`）。
   本窗**没有**动脚本：改判据/重排属主窗裁决项（军令 §1-1 禁"改脚本判据凑绿"）。

## §5 设备遗留态（如实报，等主窗裁是否复原）

- K40 已装**发布件 v1.0.6**（原为 0.1.0-s1 中文旧包）——这是工单 §2-2 要求的动作，**建议不复原**（0.1.0-s1 是 S1 期旧包，无保留价值）。
- 无障碍服务被**追加**了 `com.anytouch.app/.service.AnytouchAccessibilityService`，第三方"暴力熊"原条目原样保留。原值已留档（`precheck-apk.txt` §2）；若 K40 要交还老板日用，本窗可按留档值一条命令摘回，等主窗令。
- 老板那把 Key 仍在机上（`install -r` 保留 /data 与 Keystore），本窗未做任何清除动作。

## §6 交付物清单

```
evidence/S8c-k40-byok-debt/
  S8c-report.md                        本件（五关自检 + 三态表）
  precheck-apk.txt                     跑前对账原文（含装前不合格、匿名回对、装后同值、a11y、git status）
  debt-ledger.md                       7 行逐格对号 + 三条路径算术
  raw/20260926-175045-7ae4bfee/
    adb-devices.txt                    adb devices -l 原文
    ui-dump-screen1.xml / ui-dump-credential-line.xml   同帧 dump（凭据状态行命中件）
    precheck-screen-keyed.png          同帧截图（202,967B）
    logcat-anytouchrun-dump.txt        零消耗证据（S3SMOKE compile 计数 0）
```
未交付件如实标注：**byok-smoke 运行日志不存在**（未起跑），不造第二份"看起来跑过"的件。
