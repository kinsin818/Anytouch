# S4-b 买家话术背书表（内部件，不随发布件）

> 来源：本表原为 `docs/onboarding-gumroad-en.md` 的「Appendix B — backing table」，09-25 由老板 S5-R10 裁 2
> （原文："买家件里的中文内部背书表直接从发布件剥离，别给买家看咱们的内部测试记录"）逐字搬入本文件。
> 作用不变：卖点逐句对磁盘的工程内证，供主窗/复审核链使用；**买家件里不再出现本表，也不再指向它。**
> 路径锚点与行号厘文未改（行号即原表顺序，历史引用"第 4 行"仍指执行期零网络那一行）。

## 背书表（原 Appendix B，逐字保留）

| Sentence in this guide | Disk evidence |
|---|---|
| Keystore 加密持久化 / 掩码尾 4 / 清除双槽 / 必回读比对 | `evidence/S3/slice-e2-k40-key.md` [K4] 段 E9a~c；E11a~d（老板亲点）见同文件 §6 + `orders/ANYTOUCH-S3-byok-ORDER.md` §5.1 "E 收口" 行 |
| 地址政策拒本地元数据网段/重定向/userinfo | `evidence/S3/slice-b-key-surface.md`（BaseUrlPolicy 九档） |
| 上行=可见 text/resource-id，不含输入框内容与截图，条数上屏 | `orders/ANYTOUCH-S3-byok-ORDER.md` §0 裁 2 + `evidence/S3/slice-c-screen-context.md`；E5c/E5d/E10 rows 实采（`slice-e2-k40-key.md`） |
| 执行期零网络（行为证据） | 静态：产品执行路径零网络关键字，ci-local 红线 A/C 实跑 PASS（`evidence/S2/s2-ondevice-record-replay.md` §7/§123 行）；行为：模拟器飞行模式 device-smoke 13/13（`evidence/S3/slice-e1-emulator-pre.md`）；K40 离线段（S5~S7）绿读数在 raw r4 逐字保留，但该轮整体判双驱动互污作废、不作真机整链背书（`evidence/S4/slice-s4a-fullflow.md` §3-5）。边界照实不洗：行为证据≠抓包字节证明；AVD≠真机；真机整链绿轮未达成——老板 09-24 裁定以现有背书定案（原文入 ORDER §2-4 钉档），本行不再挂"待补轮" |
| 编译互斥四入口（跑着不许串状态） | commit 72af452，`evidence/S3/slice-f-mutex-vocab.md` |
| 词表只有 click/scroll/type_text/wait，越权整本拒并显式 | `ExecutorVocabulary` 真源 + E5i 真机命中（`slice-e2-k40-key.md` §6） |
| 高危动作二次确认/超时默认拒 | `evidence/S2/stage-highrisk-confirm-device.md`（设备实证：超时默认拒绝+面板可见） |
| 无障碍/悬浮球缺席=拒开录拒执行并指名缺哪个 | 开录边：`evidence/S2/s2-ondevice-record-replay.md` §8.2（红线 E `AccessibilityGate.kt`，设备实证 C9/C10 于 `scripts/device-smoke.sh`）；执行边：`evidence/S2/safety-ball-failclosed.md`（第 10 项：球挂不上=拒绝执行）。诚实注：§8.2 尾部"待裁"记的是派单**原文**未落盘，代码+设备证据在盘不受影响 |
| MIUI 持久拒 SET_TEXT 大声报不冒成功 | 雷 12（`evidence/S2/t3-k40-first-contact.md`）+ S31-B8 真机复验 `evidence/S31/stage31-b-k40-reverify.md` |
| minSdk 26 / 实测机型 K40 / Samsung·Moto 未实测（风险段点名） | `app/build.gradle.kts`；T3 档案；风险段口径=老板 S5-R2 裁"缺口写进上线风险段，不虚标"（`orders/RULINGS-20260922.md`，沿用 T3-R4 最新真机口径） |

## 随表一并从买家件剥离的内部状态段（原文留档，不再出现在 `docs/onboarding-gumroad-en.md`）

> Status: FINAL v1 for S4-b (military order `orders/ANYTOUCH-S4-packaging-ORDER.md` §1/§3; batch closed by boss
> ruling 2026-09-24: "把话术两处pending按现有背书转正，S4批直接结了" — see ORDER §2-4 re-nail record).
> Every capability sentence below is back-traceable to an on-disk engineering record (Appendix B).
> **Language note: written by the build agent, not proofread by a native speaker — boss review required before listing.**

注：上述"未经母语校对"一句仍是**开放项**（上架前归老板面），只是从买家看得见的位置移到了内部件里。
