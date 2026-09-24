# ANYTOUCH · S5 工单：交付面三缺补齐（上架前置门）

> 军令性质：老板 2026-09-24 傍晚 S5-R3 裁决直接开工单令——原文逐字见 `orders/RULINGS-20260922.md` S5 批表：
> **"交付面三缺排进 S5 工单：补三个预制模板、建 GitHub 仓库、写英文 README+GPLv3 许可证，做完再上架"**
> 缘起：DuMate 二审（对 WorkBuddy 初审的复核）实测盘上三缺=零实现（`git remote -v` 空、LICENSE 不存在、
> README 仅 6 行中文、三模板全线零落盘）。$6.99 付费点"模板功能"当前无货——**三缺未闭环前，账面禁出现"可上架/可交付"字样**。
> 原军令条款出处：`Anytouch-开发军令-最终版.md`（立项大会目录）R3-1/R3-3 + 交付物 2/3。

## §1 范围（三片；**开工顺序=老板 S5-R5 令：c → a → b**，原文见 RULINGS S5-R5 行）

| 片 | 内容 | 验收判据（可证伪） |
|---|---|---|
| S5-a 三预制模板 | 清单沿用 P0-4 已改判：**Gmail 清理 / 相册清理 / Discord 签到**（游戏签到不进预置库=用户自建白名单外模板）。模板=硬编码步序 JSON，**零模型调用**（R3-1 原判），走既有落账唯一写口进任务框。**验收靶机=老板 S5-R6 改判：AVD 原生 GMS 镜像 avd34（Android 14）+ avd35（Android 15），不占 K40 真机轮次**。**验收分档=老板 S5-R7**：相册清理=**全链实证 100%**（无登录可测已摸底对盘，清理靶子 adb push 种子图造）；Gmail/Discord=**装载面+结构判据**（不提供测试账号、不搞登录——用户自己的号自己登；业务步设备 100% **账面不声称**，上架文案明写"需用户先自行登录对应 App"） | 相册：双镜像设备实证 100%（判据逐条 raw 带时戳入 `evidence/S5/`）；Gmail/Discord：装载成功+门禁拒得住+JVM 结构锁全绿即达档，勿以业务绿冒称；三模板共用：磁盘单一真源文件、UI 一键装载、越权动作编译不进模板（词表仍 `ExecutorVocabulary` 单一真源）；口径边界不洗：AVD≠海外真机 |
| S5-b GitHub 仓库 | 建仓 + 全部代码上传 + Releases 挂 debug APK（R3-3/交付物 3）；**核心执行引擎 GPLv3、其余组件许可口径随本片钉**（LICENSE 文件入库） | `git remote -v` 非空且 push 成功记录在册；Releases 页 URL + 资产 md5 与本机 `37d2e199…` 链一致（若届时有新构建则登记新 md5）；**建仓/push=外发动作，每步执行前向老板要账号侧确认，不擅动** |
| S5-c 英文 README + 许可证 | README 全英文：使用说明、开源声明、隐私政策三段齐（交付物 2），与 S4-b 话术 `docs/onboarding-gumroad-en.md` 同源不互相矛盾；GPLv3 全文入 `LICENSE` | 中文残留扫描=0（正文段）；三段各指到具体章节；README 承诺逐句对盘（背书口径沿 S4-b 附录 B 纪律，"行为证据≠抓包"类边界不洗） |

## §2 硬约束（继承全部在册军令）

1. 执行期零网络、节点引用禁坐标、高危二次确认、Key 不入 git/日志、截图不出设备——红线 A/C/F/G/H/I 字面不动；**开源≠豁免红线**，LICENSE 只裁代码许可，不裁行为判据。
2. 三片各自 commit + STATUS/METRICS/本单 §4 交付账同步；判据不过照报不停摆；额度另钉（本单不含 S4 批账，真请求需求逐片预估后向老板要数）。
3. 上架动作（Gumroad 账号/定价/素材）仍在老板面，本单不做。

## §3 与旧账的钩子

- 二审门槛 3 由此单承接；门槛 1（MediaProjection）=S5-R1 已豁免关闭；门槛 2（真机口径）=S5-R2 已确认关闭——**T3-R4 为最新口径，"国产机型 ≥90% 生命线"提法作废，不得翻案**；Samsung/Moto 缺口已点名写入 `docs/onboarding-gumroad-en.md` 风险段（S5-R2 执行账）。
- 待办 1（T2 映射表勘误回禀）=S5-R4 已执行关闭，明细 `evidence/S5/nim-model-map-rewrite.md`。
- E8i/E8j 上屏像素复验（初审边界 4）不属本单，仍挂 K40 轮次账，随 S5 任一片顺带兑付或另裁。

## §4 交付账（随批填写）

| 片 | 状态 | 账 |
|---|---|---|
| S5-a | ✅ 本批结案（相册全链实证+翻档；Gmail/Discord 按 S5-R7 装载面+结构判据达档） | 摸底：K40 421 包三 App 零命中→老板改判 AVD；avd34 实测 `com.google.android.gm`/`com.google.android.apps.photos`/`com.android.vending` 在架，`com.discord` 不在架（S5-R7 后 Discord 按结构档，不再追装）。设备实证：avd34+avd35 各 5 轮全链绿（13/13×2，装载判据 T0a/b/c + 每轮六判据，raw 10 份带时戳入 `evidence/S5/raw/`，磁盘双真空终查），背书件 `evidence/S5/photos_cleanup-device-proven-avd.md`；`photos_cleanup` 翻 `DEVICE_PROVEN_ON_AVD`（反虚标锁正向分支首次走通），Gmail/Discord 维持 `STRUCTURAL_ONLY` 不冒称业务绿。JVM：模板用例 8/8（含锁路径修正 `../evidence/S5`）、基线 431 不回归。产品增量=一行档位+一处测试路径；零坐标、零网络、K40 零轮次、S4 额度 6/8 定格未动 |
| S5-b | ✅ 本批落地（09-25，老板 S5-R8 三裁放行；建仓本身=老板已在网页完成） | remote=`https://github.com/kinsin818/Anytouch.git` 公开仓、`git remote -v` 非空；main(105 commits)+tag v1.0.0 推送成功，远端与本地逐字同点 `64fc1ec`；推前全史扫描=9 字面全合成 fixture、与真 Key 池重叠 0（老板裁"视同零命中"，fixture 保留不清理）；Release 页=`github.com/kinsin818/Anytouch/releases/tag/v1.0.0`，标题逐字 "Anytouch v1.0.0 - First Release"，资产 `app-debug.apk` 9,374,132B、md5 `054993cc700fa3f7d3344bc85638d206`（HEAD 现构建新包，非旧 `37d2e199…`/`eee8180d…` 链——按军令"届时有新构建则登记新 md5"口径），匿名下载回对一致；文案三要点齐（核心功能/已知限制=K40/K80+AVD31/34/35·Samsung/Moto 未验/隐私=Keystore 本机+执行期零网络+截图不出设备），首发=debug 签名测试包（S5-R8 改判；"无缝升级"经勘误按 Android 签名事实改写），keystore 保管案留待签名版回炉；登录授权全程老板本人（GCM+gh 设备码，主窗未触 Token） |
| S5-c | ✅ 本批落地 | LICENSE=gnu.org 官方 GPLv3 全文 35149B/674 行、尾部 why-not-lgpl 段核对一致；README 全英文重写（旧 6 行中文留 git 历史），使用说明/开源声明/隐私政策三段齐 + Known limitations 逐条对盘；中文残留扫描=0、nvapi/Key 字样扫描=0；承诺逐句对盘（compileSdk 36/targetSdk 34/minSdk 26 取自 `app/build.gradle.kts`，"真机整链绿未达成"按 S4 定案口径直引 evidence 原文，UI 英文表述仅陈述"本构建未随发"不做路线图承诺）；与 `docs/onboarding-gumroad-en.md` 同源不矛盾（Samsung/Moto 未实测、中文 UI、纯节点执行三处在两文一致） |
