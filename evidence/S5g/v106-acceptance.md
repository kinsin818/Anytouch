# v1.0.6 判据 1–12 实证汇总（S5-g）

**日期**：2026-09-26（本机 10:17–12:16）
**被测字节（一支字节贯穿全部 12 格）**：`app/build/outputs/apk/debug/app-debug.apk`
9,389,205 B / md5 `d61b9867fcdf164d73ed236ce61f76e1` / versionCode 7 / versionName 1.0.6，机上 `dumpsys package` 读回 `versionCode=7 versionName=1.0.6`。
**靶机**：只有 `emulator-5554`（avd34，1080x2340）。物理机 `7ae4bfee` 在机零指令，K40/K80/emulator-5556 零触碰。
**测试通道真值**：`scripts/s5g-relay.sh`（`ssh -N -L 127.0.0.1:18443:127.0.0.1:8443 root@45.32.63.177`）＋ AVD 内 DNAT `45.32.63.177:8443 → 10.0.2.2:18443`。中继是**测试机的路由事实**，不是产品旁路：产品内端点与指纹一字未改，R6 那格正是用"把对面换成异指纹证书"来证明产品会自己断开。

## 1. 十二条逐条对号

| # | 判据 | 结果 | 凭（原文在哪） |
|---|---|---|---|
| 1 | 接口三态 `invalid`/`seats_full`/`ok`＋同 hash 幂等重放不占第二格 | PASS | `evidence/S5g/raw/s5g-contract-20260926-043702.raw.txt`（尾行 `CONTRACT pass=19 fail=0`）；设备侧 R1a/R1b/R2 |
| 2 | 第 1、2 台成、第 3 台 `seats_full`，解绑一台后第 3 台可进 | PASS | 契约面并发 8 路抢 1 码（同 raw，落败 6 路逐条明回 `seats_full`）；设备侧 R4a/R4b/R4d/R4e |
| 3 | v1.0.5 六档本地拒因不回退＋新增"本地过·服务器拒"档 | PASS | `s5f-activation-smoke` 46/0（`.smoke-tmp/r3-s5f.log`，raw `evidence/S5/raw/s5f-activation-20260926-113542.raw.txt`）；设备侧 R3a~R3d（屏上原话 "Activation code invalid"、盘上 flag 缺席、三入口照旧拒） |
| 4 | fail-closed 两种切断（服务停 / 指错端点）都必须未解锁 | PASS | R5a、R5b 两格同落 `SERVER_UNREACHABLE` 且给联网归因句；屏上/盘上/入口三处都取到"真没解锁" |
| 5 | 已激活离线照用，且飞行模式对本接口零请求 | PASS | R7a~R7e：`install -r` 换进程仍解锁、飞行下三入口放行、一次任务照跑、服务器侧 activate 计数 0（同窗自己的 health 探针被记到 1 条＝装置是活的） |
| 6 | 执行期零网络不破：Photos 全链 5 轮 13/13 同字节复绿 | PASS | `evidence/S5g/raw/v106-photos-5rounds-20260926-121339.log`（`RESULT fail=0 passed=13 rounds=5`，RC=0），逐轮 raw 5 份在 `evidence/S5/raw/s5a-emulator-5554-photos-round{1..5}-20260926-12*.log` |
| 7 | 设备标识纪律：整码/完整指纹两处分页读不到，只回显尾 4 | PASS | R8a（9 枚本轮整码在语义树与 logcat 0 命中）、R8b（logcat 无 16 位十六进制串）、R8c（raw 自身反向扫描 0 命中） |
| 8 | TLS 反向锁：非固定指纹证书必须断开 | PASS | R6a~R6z：假靶指纹 `0bc0da1bf06d4d33` ≠ 固定值 `3d7146a142f8d08f`，假靶访问日志证明通道确实打到它（health 1 条），设备落 `SERVER_UNREACHABLE`，假靶收到 GET=1 / POST=0＝握手未成 |
| 9 | 结构锁：联网代码只住 `:byok`、执行路径 import 不到、付费墙两条旧锁不放松 | PASS | `ActivationNetworkLockTest.kt`＋`ActivationTransportTest.kt`；JVM 576/0/0/0（口径件见 §2） |
| 10 | 旧账不回归（同一枚字节） | PASS | `device-smoke` ALL PASS（`.smoke-tmp/v106-devicesmoke.log`，10:57）／`ui-smoke` 51 PASS·0 FAIL·0 SKIP（`.smoke-tmp/r2-uismoke.log`）／`s5d` 15/15（`.smoke-tmp/r4-s5d.log`）／`s5e` 36/36（`.smoke-tmp/r3-s5e.log`）／Photos 5 轮 13/13（判据 6）／`ui-english-sweep` 93 串上屏 CJK=0（`.smoke-tmp/r2-english.log`） |
| 11 | 红线 A–I 全清且没为过红线改红线 | PASS | `evidence/S5g/raw/v106-ci-local-20260926-121639.log` 尾段九行全 clean＋`[4/4] ci-local PASS`，本次运行 CI-RC=0；`git diff scripts/ci-local.sh` 行数=0 |
| 12 | 对外改口（裁 4） | 已建（发布账见 §6） | README 隐私段＋README:4 已如实；`evidence/S5g/release-v106-notes.md` 首句 "from this version a purchase code is validated against our server"，旧口径逐字点名**一处** "Up to and including **v1.0.5** the same codes were checked entirely on the device"（夹加粗，纯字面 grep 会 0 命中）＋同义一句 "Everything before this release validated it on the phone"；README 侧同样**一处**；旧 Release 六枚（v1.0.0~v1.0.5）不回改；发布账先建后记 |

## 2. JVM 口径（干净重跑＋单变体分模块 XML，四列全脚本累加）

`evidence/S5g/raw/v106-jvm-module-tally-20260926-104341.txt`：app 477 ＋ byok 80 ＋ core:contracts 19 ＝ **576**，files=50，failures/errors/skipped 三列皆 0。基线 552 → 576（+24：联网结构锁与 fail-closed 用例）。
提交树复跑（`evidence/S5g/raw/v106-ci-local-20260926-121639.log`，`--rerun-tasks`）分模块 XML 汇总同样是 477/80/19＝576、files=50、零红。

## 3. 在册码纪律（本批复扫）

- 私有件 `/d/Qoder/secrets/anytouch-activate-codes-20260926.txt` 共 **120 枚**（buyer 100 ＋ staging 20）。逐枚对全仓（排除 `.git/`、`.smoke-tmp/`、`build/`、`.gradle/`）做整码比对：**命中 0 枚**。
- 形态扫描另有 16 条 `ANY-…` 命中，逐条核过全是 golden/反例夹具（`ActivationCodeTest.kt`、`s5f-activation-smoke.sh` 的假码、`ActivationCode.kt` 的格式串 `ANY-XXXX-XXXX-XXXX`）与 gitignore 内的本轮 staging 临时件（`.smoke-tmp/s5g-contract-*/codes.txt`，`.gitignore:21`）。
- `ADMIN_TOKEN` 只走环境变量与私有件，未进仓、未进日志、未出机。

## 4. 本批测试通道自造的雷（全记测试面，不记产品）

1. **`C:\Users\Administrator\.local\bin\env` 是一枚假 `env`**：PATH 优先命中它，命令被吞且返回 0 ⇒ 两轮出现"RC=0 但日志 0 字节"的假绿。改法是绝对路径 `/usr/bin/env`，并在跑批壳里加了"RC=0 且日志 < 200 字节 ⇒ 按红记（rc=97）"的正证闸门。**只看 RC 从来不是读数。**
2. **MSYS 路径交给 Windows python 会被重新解析**：`/d/Anytouch/...` 形态的产物路径读不到，须 `pwd -W` 出 Windows 形态。
3. **Photos 第三方 fixture 的坏篓态**：`Move to trash` 点下去直接回 snackbar "Failed to trash"、确认框压根缺席。手工探针复现后加了两道：`purge_seed_residue()`（盘＋MediaStore 双真空才放行播种）与 `reset_photos_fixture()`（只 `pm clear` 第三方 Photos，自家进程一枚没动）。
4. **`pm clear` 会把 Photos 的首次教育浮层重新武装**：首碰移篓弹 "Items moved to trash are removed from all folders" ＋ `Got it`，浮层压在确认框上，导致点不到确认——这是**上一条我自己新加的复位引入的**。修法是点 `Move to trash` 之前先把在场的所有 `Got it` 排掉（每一投都落 raw）。
5. **移篓改名是异步的**：`ls` 读早了会看到种子还在盘上（r4 的"可见残=1"）。改成逐枚 15s 沉降轮询并把用时报进 raw。
6. **s5d 确认点击的复核口径换法**：上一窗按"面板像素偏移"补投，结果一次点出两格额度（`面板=3`）。改回以产品自己的计数器为准——`second-confirm panel shown` 计数涨了才算落，不涨且面板仍在场才按新帧补投，最多 2 次。r4 起 L5 `面板=2`、raw 里没有一次补投记录。
7. **孤儿监听残留会当假靶**：上一轮进程仍占 18443 时，其库里没有本轮新码，测试会对着假靶跑。跑前"端口已有应答即当场停"。

## 5. 未定性与待裁（不冒充已证）

- **8443 对真实买家可达与否仍未定性**（v1.0.6 判据之外的一件事，服务器批已登记）：本机出网被透明拦截，公网多节点探测与 tcpdump 定性实验互相矛盾于"包有没有到机"，因此账面既不写"已对全球可用"也不写"被拦死"。需老板的手二选一：Vultr Firewall 加 Inbound TCP 8443（或给 API Key 由我加），或手机蜂窝直打 `/api/health`。**设备面所有绿都是在"中继对面=真服务器"下取的，不依赖公网可达性。**
- 买家件 `docs/onboarding-gumroad-en.md` 已认变更，但**通篇没有一节教买家怎么输入激活码**——属生意面文案，未擅动，待老板裁。
- README 的 Features 段没有为三项 Pro 能力加"需要激活码"的公开条目（v1.0.5 批同样没加）：隐私段与 Release 正文已如实陈述"付费墙在哪、锁什么"，是否在功能清单上公开点明付费点仍待老板裁。
- 本轮之前有一次"脚本运行期被编辑"的违规轮已作废入册；`ui-english-sweep` 早前一次 `ledger :: 走位不合格` 红在重跑后自行消失，**机制未定性**，按未解释但未复现照登。

## 6. 发布账（先建后记，本节建成时发布动作已全部完成）

| 项 | 值 |
|---|---|
| commit | `70daffd`（163 文件）推 `origin/main`，范围 `27dabdb..70daffd`（同批带上此前未推的 `c070b70`/`853dc9b` 两枚服务器件） |
| tag | annotated `v1.0.6` → `70daffd`，已推远端 |
| Release | `https://github.com/kinsin818/Anytouch/releases/tag/v1.0.6`（id 397080914，`published=2026-09-26T04:23:55Z`，draft=False／prerelease=False，现为 latest：`/releases/latest` 302 → `.../tag/v1.0.6`） |
| 正文 | 远端回读 2684 字符，与 `evidence/S5g/release-v106-notes.md` 逐字 **IDENTICAL** |
| 资产 | `app-debug.apk` 9,389,205 B（asset id 589912715），GitHub 侧 digest `sha256:d91ec5c7fa1b6527b5101cf916b9b8613ddbfa6f2d46660c7f0ed3f16e2c81fd` |
| 匿名回对 | **无凭据** curl 直链（地址从未登录页面路径取）→ HTTP 200 / 9,389,205 B / md5 `d61b9867fcdf164d73ed236ce61f76e1` / sha256 同值，与盘上被测那一枚 `cmp` 逐字节相同 |
| 旧版 | v1.0.0~v1.0.5 六版正文与资产一字未回改（大小 9,374,132／9,348,476／9,290,901／9,335,775／9,356,437／9,532,419 原样在册），仅 Latest 徽标移位 |

**自纠两条（写在自己的汇总件里，不另开文件圆）**：本件初稿把判据 12 那行写成"两处 `up to and including v1.0.5`"与"旧 Release 五枚不回改"——实测 Release 正文该句只有一处（夹 markdown 加粗，纯字面 grep 会 0 命中，另一处是同义句），且旧版是六枚不是五枚；两处均已按实改。另 RULINGS 本批落地段原写"MD5 一字未进本仓"不成立（仓内 `md5` 四处命中全在注释里解释为何不用它），已改成"MD5 未作为任何哈希原语进代码"。

