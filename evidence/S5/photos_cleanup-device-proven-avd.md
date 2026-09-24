# S5-a 相册清理模板全链实证（photos_cleanup → DEVICE_PROVEN_ON_AVD）

本文件是 `PresetTemplatesTest`「DEVICE_PROVEN_ON_AVD 标记必须有磁盘实证背书」锁的背书件
（文件名前缀 `photos_cleanup-device-proven*` 即锁所查）。老板 S5-R7 裁：相册清理=**业务步全链设备实证档**
（Gmail/Discord 降为装载面+结构判据，永不代人登录，另案）。

## 0. 一句话结论

相册清理模板（9 步）在**两台** AVD 原生 GMS 靶机上，走「装载口落账 → 执行器节点驱动 → 高危二次确认 →
磁盘真空终判」整链，各 **5 轮全绿（ok=9/9 未中止 + 种子文件与回收站双归零）**。
产品资产/执行器词表**零坐标**，全程零网络、零模型额度（不触碰 S4 批账）。

## 1. 靶机（老板 S5-R6 改判：AVD 原生 GMS 镜像，非国行 K40；K40 本批零轮）

| 靶机 | serial | API | 分辨率 | 无障碍绑定 | APK md5 |
|---|---|---|---|---|---|
| avd34 | emulator-5554 | 34 | 1080x2400 | 已绑（沿用） | eee8180d40282cb784ef413d83e062c3 |
| avd35 | emulator-5556 | 35 | 1080x2340 | BIND_PROVISION=1 自动绑（仅模拟器） | eee8180d40282cb784ef413d83e062c3 |

APK 与盘上 `app/build/outputs/apk/debug/app-debug.apk` 同 md5；其内 assets 的 `photos_cleanup.json` 第 9 步
已用 `resource_id=…:id/confirmation_button`（非易变的 "Delete permanently" 文本，后者带计数后缀会 trim-全等失配）。

## 2. 判据链（每轮都重走，不只验一次）

- **T0a/T0b**：gmail_cleanup(4 步)/discord_checkin(5 步) 装载面绿——`S5SMOKE template load ok` +
  `model ledger written origin=template`，**未执行任何业务步**（S5-R7 只验装载+落账）。
- **T0c**：脏 id `bogus_x` 被装载器当场拒并留痕 `template load refused gate=LOADER`，一步不落（fail-closed）。
- **轮 R 全链**（photos_cleanup）：
  1. `seed_photos R`：设备侧 base64 生成**两枚不同像素**（红/蓝 64x64）、**不同未来日期**
     （2030-01-01 / 2030-02-02）、**逐轮换代号文件名**（`S5SEEDR<轮>A/B`）的种子，MediaStore 建行恰好 2 条。
  2. `prime_trash R`（测试通道手指，产品不背）：查看器 Delete → Move to trash →（登出态 Got it 确认）×2，
     终判**本代**可见种子归零 + 篓内恰好 2（防历史残骸冒充）。
  3. `load_tpl photos_cleanup 9` → 抓装载产出的任务 JSON → `am start … task_json` 注入。
  4. 竞态双相位等待：先等焦点回 Photos（执行真开跑）、再等自家无活动后缀窗回焦（高危面板现身），
     命中即测试通道定点 tap「确认执行」（S2 killdemo 同法，模拟用户手指，非产品定位）。
  5. **回执判据**：`S1SMOKE ok=9 total=9 stopped=false`。
  6. **磁盘终判**：`.trashed-*` 种子残留=0 且可见种子残留=0（篓内外双真空，非账面绿）。

## 3. 绿灯轮次（raw 逐字保留）

avd34（run12，13/13）：round1..5 →
`raw/s5a-emulator-5554-photos-round1-20260925-011837.log`、`…012016`、`…012155`、`…012334`、`…012513`。
每台每轮 raw 内均含逐字 `S5SMOKE template load ok id=photos_cleanup … steps=9` +
`model ledger written origin=template steps=9` + `S1SMOKE ok=9 total=9 stopped=false` + `# 磁盘终判 .trashed 残留=0 种子残留=0`。

avd35（run14，13/13）：round1..5 →
`raw/s5a-emulator-5556-photos-round1-20260925-013319.log`、`…013501`、`…013643`、`…013825`、`…014006`。
判据同上逐字命中。

## 4. Photos 雷账（本环境实证，写死在测试脚本注释里，下批勿再踩）

- **种子必须真实尺寸**：1x1 PNG 被 Photos 网格直接滤掉（MediaStore 行在、日期对、is_trashed=0，就是不上屏）。
- **两张必须不同像素 + 不同日期**：同内容两张（哪怕日期不同）网格里被**内容级去重折叠成一格**，
  第二张永远 NOHIT，点第一张移篓会把折叠格的第二张一起改名带走（run10/11 实锤）。
- **逐轮换文件名**：MediaProvider 对"曾被清过篓的同名文件"不再重新建行（文件在盘、扫描照发、库里没行）。
- **移篓 = 改名 `.trashed-*` + 删索引行**：行整条消失，**不是** `is_trashed=1`，故 MediaStore 计数不可作前置判据；
  进篓真判据走盘上改名事实（run9 实锤）。
- **登出态首碰移篓弹 "removed from all folders" + Got it**：不点 Got it 则移篓被拦（run8 实锤）。
- **双弹层壁每次冷启都要 dismiss**：`Sign in to back up` 底簿（点外区 scrim 撤）+ 升级推广页
  `UpdateAppTreatmentPromoPageActivity`（"Not now" 关，avd35 每次冷启弹一次，盖网格）。

## 5. 诚实边界（不洗）

- 定坐标点「确认执行」的是**测试通道**（模拟用户手指），非产品资产；产品资产/执行器词表**零坐标**。
- AVD 原生 GMS 镜像 ≠ 海外真机：厂商 ROM 权限弹层、Google Photos 线上版本差异本批未覆盖，
  上架话术与 README 已按此口径写（见 `docs/onboarding-gumroad-en.md`、`README.md`）。
- 本轮为**模板装载→执行**整链，不含 AI 编译（无网络、无额度）；模板资产是人工按判据前置的静态 9 步。
- Gmail/Discord 两模板**未做业务步实证**（本工具不做登录），仍为 STRUCTURAL_ONLY，不随本件翻档。
- K40 真机本批按 S5-R6 **零轮**，不受本件背书。
