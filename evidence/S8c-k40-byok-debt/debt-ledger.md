# S8c 欠格台账 · byok-smoke 那 7 行（逐格对号）

靶机：K40 `7ae4bfee`（M2012K11AC，真机）　受测件：已发布 v1.0.6（versionCode 7，机上 base.apk md5 `d61b9867fcdf164d73ed236ce61f76e1`＝发布字节同值）
欠账来源：commit `5b2c3c1`（S5-c 英文化，v1.0.1）改了 `scripts/byok-smoke.sh` 恰好 7 行凭据状态行字面量，此后**从未设备重跑**。
本轮结论一句话：**7 行全部仍欠（已验 0 / 仍欠 7）**——不合格的不是环境，是本批"零真请求"的分寸与机上"带钥档"互斥，详见 §C。

## A. 逐行对号

| # | 行（HEAD 脚本原文，逐字取自 `git show 5b2c3c1 -- scripts/byok-smoke.sh`） | 所属格 | 本轮 | 原因 / 要什么才能绿 |
|---|---|---|---|---|
| 1 | `tail_read=$(page_line 'ey saved on this device[^"<]*')` | E1l（取数，门控 E2/E3 与 E5–E11 走向） | **欠** | 本轮 byok-smoke.sh 未执行 ⇒ 该行未被执行过。只读探屏读到过 `…saved on this device: ***JgYp`，但那不是脚本判据，**不折成 PASS**。任一档位（带钥/无钥）跑一次 E1 即可清 |
| 2 | `if printf '%s' "$tail_read" \| grep -q 'No key saved on this device'; then` | E1l（无钥分支判据） | **欠** | 需**机上无凭据**才有正向命中。当前机上存着老板那把（尾 4=`JgYp`）⇒ 清除是 E11 那把"老板手指的活"（脚本明写不模拟手指点自家面板） |
| 3 | `    KEY_PRESENT=0; pass "E1l 凭据状态行说「No key saved on this device」 :: [$tail_read]"` | E1l（无钥分支 PASS 文案） | **欠** | 同上：与 #2 同一条腿，需无凭据态 |
| 4 | `elif printf '%s' "$tail_read" \| grep -q 'Key saved on this device: \*\*\*'; then` | E1l（带钥分支判据） | **欠** | 这一档**屏上条件今天就满足**（`Key saved on this device: ***JgYp`），但脚本一旦以带钥态起跑，E1 之后即进 `run_key_group`，第一格 E5a 就是真 HTTPS 编译 ⇒ 与本批"一律不走真请求"正面冲突 ⇒ 未起跑。要清它：要么零消耗档能停在 E1（需主窗裁脚本流程，见 §C 路径 C），要么老板批真请求 |
| 5 | `        tail_before=$(printf '%s' "$(page_line 'Key saved on this device: [^\"<]*')" \| sed 's/Key saved on this device: //')` | E9c 左半边（kill 前现读） | **欠** | 在 `run_key_group` 内、E5a/E8 之后（那两格各一次真编译）。E9 本身**零消耗**（`install -r` 同构建 + 现读），但脚本没有"跳过 E5/E8 直达 E9"的档位 |
| 6 | `    tail_after=$(printf '%s' "$(page_line 'Key saved on this device: [^\"<]*')" \| sed 's/Key saved on this device: //')` | E9c 右半边（重启后现读） | **欠** | 同 #5 |
| 7 | `        assert_ui "E11b 屏上回到「No key saved on this device」" 'No key saved on this device'` | E11b（清除后回读） | **欠** | 需 `E_WIPE=1` 且带钥起跑（即先过 E5a/E8/E10 的三次真编译），**并且**"清除"那一下按账面归老板手指（byok-smoke.sh:711-713 原文：脚本不下发 Key、也不模拟手指点自家面板） |

## B. 本轮零消耗路径为什么一步没跑

`byok-smoke.sh` 无"只跑零消耗格"的开关（全部环境变量只有 `E_WIPE`、`E9_APK`）。流程是：
E1（含 #1–#4 门控）→ E2/E3（带钥态自动 SKIP）→ E4 → **`KEY_PRESENT=1` ⇒ `run_key_group` ⇒ E5a 真 HTTPS 编译**。
⇒ 起跑即花额度。工单 §1-1 写死"本批一律不走真请求、谁也不许替老板花"，故当场停跑，未起跑、未改判据、未 mock 端点。

## C. 清完这 7 行需要的东西（算术先摆平，交主窗裁）

带钥组一轮到底的真请求数 = **3 次**（E5a 一次、E8 的 `ai_compile true` 一次、E10 的 off 档一次；E9 与 E11 自身零消耗）。
S4 额度账定格 **6/8、剩 2**（`orders/METRICS.md:183` 同一算术在册：「批账 6/8、剩 2<全链 3 按 §2-4 再停手照报」）。

| 路径 | 动作 | 消耗 | 清掉 | 仍欠 | 前置 |
|---|---|---|---|---|---|
| A | 老板手指点一次「清除本机凭据」→ 本窗以无钥态跑零消耗腿（E1l #1–#3、E1m/E1n/E1o、E2a~f、E3a/b、E4a~c）→ 跑完用 `scripts/byok-credential-inject.sh` 把 Key 填回（S3-R5 通道，零消耗，尾 4 仍 `JgYp`） | 0 次 | #1 #2 #3（3 行） | #4 #5 #6 #7（4 行） | 老板点清除那一下；key 文件仍在盘（`key.txt`，仓库外，本窗未读内容） |
| B | 带钥 + `E_WIPE=1` 一轮跑完 | **3 次（超册：剩 2）** | #1 #2(反)/#3(不走)/#4 #5 #6 #7 | 见左：需 3 次额度 + 老板点清除 | 老板改额度上限或补额度；本窗不自作主张 |
| C | 主窗裁一条"判据只加不减"的流程改造：给 `byok-smoke.sh` 加零消耗档（E1 后可停在门控处 / 把 E9 的 `install -r`+现读从 E5 之后摘出来单独可跑），改后 JVM+设备同轮重跑 | 0 次 | #1 #4 #5 #6（4 行，含 E9c 两行） | #2 #3 #7（需无凭据态 ⇒ 仍归老板手指） | **属判据/流程改动，须主窗下裁再动**；本窗不擅自改（军令 §1-1 禁"改脚本判据凑绿"） |
| A+C | 两把合起来 | 0 次 | #1 #2 #3 #4 #5 #6（6 行） | #7（E11b：清除后回读，需 `E_WIPE` 分支真跑到） | 老板点清除 + 主窗裁 C |

⇒ **最重的一句**：在"额度 6/8 定格不动"这条硬边界内，7 行**不可能全清**——#7（E11b）按现行脚本必须先穿过 3 次真编译才够得着。要全清只有两条：批 3 次真请求（超册），或裁 C 那样的零消耗重排。

## D. 在册旧账不动

`evidence/S5/english-build-device-revalidation.md:20-23` 原文一字未删，只在其后**追加** `§S8c 复验`；`orders/METRICS.md:227,247`、`orders/RULINGS-20260922.md:171,176,181,221,296`、`orders/ANYTOUCH-S5e-final-three-features-EXECUTION.md:70` 各"欠格在册"行只做就地标注，旧陈述一律不抹。账面口径：**仍欠 7 格，禁止写成"旧账已清"**。
