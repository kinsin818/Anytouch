# S5-a 靶机摸底：avd34（Android 14 / GMS 镜像）三模板可测性实测（2026-09-24 深夜）

> 背景：老板 S5-R6 改判 S5-a 验收靶机=AVD 原生 GMS 镜像（原文逐字见 `orders/RULINGS-20260922.md` S5-R6），
> 其中"Gmail、Google相册本来就是预装的……不用你手动登录"为老板给定前提。本篇逐款实测该前提，
> 只读探针（am start / uiautomator dump / input tap 手导路径），产品通道零占用、网络额度零消耗。
> 探针时刻：本机 09-24 深夜；设备 emulator-5554 = `sdk_gphone64_x86_64`，API 34，abilist=`x86_64,arm64-v8a`（含 ARM 转译）。
> **本批结论已被老板 S5-R7 采纳为验收分档的事实底座**（相册=全链实证；Gmail/Discord=装载面+结构判据；测试账号出局）。

## 1. 预装在架（对盘）

- `pm list packages` 226 包，含 `com.google.android.gm`、`com.google.android.apps.photos`、`com.android.vending`。
- `com.discord` **不在架**——老板已令"直接装个 APK 就行"；abilist 含 `arm64-v8a`，官方 Discord APK 在此镜像的可安装性留待装机轮实测（未测不冒称）。

## 2. Google 相册：**无登录即可测，前提成立** ✅

- 首启 `HomeActivity` 弹"Sign in to back up"备份引导层（`com.google.android.apps.photos:id/sign_in_button` 等）；
- **点层外空白即散**（实测 tap 视口上部空白 → sheet 收起），落进本地图库首页：dump 见日期格（`text="19"`）、`Search`、相册库名=设备名 `sdk_gphone64_x86_64`——**全程零账户**。
- 清理靶子可造：`adb push` 种子图 + 媒体扫描即可生成可删对象（装机轮执行并留 raw）。
- 结论：**相册清理模板可在 avd34/avd35 全链实证 100%**，与老板前提一致。

## 3. Gmail：**应用预装=true，"不用登录"=false** ⚠️

逐屏实证（dump 原文存本目录 `raw/`）：
1. 首启=`welcome.WelcomeTourActivity`"New in Gmail"→`welcome_tour_got_it`（有稳定 resource-id）；
2. 下一屏=`welcome.SetupAddressesActivity`："Add an email address" + `action_done`="TAKE ME TO GMAIL"；
3. 点 `action_done` → 弹阻断框 **"Please add at least one email address."**（OK 后仍留在 SetupAddressesActivity，**无处可跳过**）。
- 即：**该 AVD 上 Gmail 没有任何未登录可达的收件箱态**——"批量标已读"业务步在无账户镜像上物理不存在。
- 登录墙性质=Google 账户本身，与 MIUI 无关；换靶只绕开了相册一款，Gmail 这一款的"不用登录"半句经实测不成立、由 S5-R7 改判装载面档。

## 4. K40 对照账（本摸底前半段，只读）

- `7ae4bfee`（国行 MIUI）`pm list packages` = 421 包，`com.google.android.gm` / `com.google.android.apps.photos` / `com.discord` **零命中**（17 个 com.google 包全为框架/服务类）——S5-R6 改判 AVD 的缘起。

## 5. 探针工件

- 屏态 dump 要点（Git Bash 需 `MSYS_NO_PATHCONV=1`，否则 `/sdcard` 被转成本机路径——本机 shell 事实一条）：
  `gm1`=WelcomeTour（`welcome_tour_got_it`，bounds `[0,2190][1080,2337]`）；`gm2`=SetupAddresses（`action_done`="TAKE ME TO GMAIL"）；`gm3`=阻断框"Please add at least one email address."；`ph1`=相册备份引导层；`ph3`=点层外散开后=本地图库首页（`text="19"`/`Search`）。
- 复查命令：`adb -s emulator-5554 shell dumpsys window | grep mCurrentFocus`（Gmail=`SetupAddressesActivity`，Photos=`HomeActivity`）。
- 原始 XML 在本机 `/tmp`（非持久工件，关键断言已逐字誊入上文）；装机轮的持久 raw 届时入 `evidence/S5/raw/`。
