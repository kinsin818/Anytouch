# S5-g 服务端激活双校验 · 执行面（唯一真值）

**工单**：`orders/ANYTOUCH-S5g-server-activation-ORDER.md`（军令 4 条，落盘 09-26 03:34，不回改）
**裁决**：`orders/RULINGS-20260922.md` S5-R14（含本裁覆盖 S5-R13 裁 1 的范围、四裁原文、主窗自钉两条）
**发布目标**：v1.0.6（`versionCode 7`）
**靶机**：`emulator-5554`（avd34）。K40 `7ae4bfee`／K80／`emulator-5556` 零触碰。S4 额度 6/8 定格不动。

---

## 0. 本批把什么挪走了、什么一个字不动（先钉边界）

挪走：**激活的校验权威**（白名单 + 设备绑定表从"设备内的算法"挪到"服务器内的 SQLite"）。

一字不动（本批任何一步都不许顺手改）：
- **执行期零网络**：红线 C（`app/src/main/` 零网络关键字）、红线 G（执行路径 import 不到 byok）——**两条脚本本体 `git diff scripts/ci-local.sh` 必须为空**。激活发生在用户输码那一刻，不在任务回放路径上；回放仍 100% 设备内、飞行模式照跑。
- 高危二次确认与 15 秒默认拒、停止球急停、无障碍门禁、失败重试判据、禁坐标／节点引用、Key 只进 Keystore 且屏上只 `***尾4`。
- **付费墙绝不圈安全面**（S5-R13 主窗自钉）：`ActivationGateTest` 那两条机器锁继续有效，本批只允许**加严**不允许放松。
- 红线 A/B/D/E/F/H/I 一字不动。

## 1. 服务器侧（同机不加钱，但必须与买家业务解耦）

摸底事实（09-26 只读实测）：`45.32.63.177` = Ubuntu，只监听 22/80；80 由 `docker-proxy` 转 `ai-congress-online:8000`，另有 `ai-congress-redis`；**无 443、无任何证书、nginx 未启用**。

- **独立进程** `anytouch-activate`，监听 `0.0.0.0:8443`（TLS），**不共用** `ai-congress-online` 的进程、镜像、数据卷与 SQLite 文件。理由：那个容器是老板另一条在营业务，激活接口挂它身上=它一重启买家激活就失败，且两边互相拖垮。"不加钱"= 同机同 IP，不等于同进程。
  - **落地形态与本文原写法的差别（照实登记，不改口）**：本条 09-26 凌晨原写"**独立容器**"，实机部署改 **systemd 单元 + 机器自带的 Python 3.14**（`server/activation/anytouch-activate.service`）。原因：① 隔离目的（不与 ai-congress 共享进程/镜像/卷/库）systemd 比 docker **更彻底**——它压根不在 docker daemon 那条链上，误操作碰不到 `ai-congress-online`；② 免拉基础镜像、免占那台机 14G 剩余空间里的额外份额；③ 服务只依赖标准库（`sqlite3`/`ssl` 实机 `import` 已验）。**这条覆盖的是形态，不是隔离边界**：§1 后面那些"独立数据文件/私钥 0600/日志脱敏"一条不少。
- 部署前只读复查（09-26 04:3x，实机原文）：`docker ps` 基线 = `ai-congress-online`(80→8000) + `ai-congress-redis`，两者 Up 3 days；`python3 -c "import sqlite3,ssl"` → `sqlite 3.46.1 ssl ok`；`ss -lntp` 只有 22/80 在听，**8443 空**；`iptables -S` 三链全 `ACCEPT`（本机无拦截，公网可达性另测）；`df -h /` → 23G 已用 8.8G。
- **独立数据文件**：`/var/lib/anytouch-activate/activation.db`（SQLite），宿主机路径独占，不给 `ai-congress-online` 容器可读。
- **证书**：自签（CN/SAN 含 IP `45.32.63.177`），有效期按 10 年（免续期运维负担；到期前另有账面待办）。私钥只落服务器 `0600`，**不进仓、不进任何日志**。
- 表结构（最小面）：
  - `codes(code TEXT PRIMARY KEY, kind TEXT CHECK(kind IN ('buyer','staging')), created_at TEXT)`
  - `bindings(code TEXT, device_hash TEXT, bound_at TEXT, PRIMARY KEY(code, device_hash))`
  - 绑定数判据在**同一事务**内取（`SELECT`+`INSERT` 原子，防并发把 2 台绑成 3 台）。
- **白名单预置**：buyer 段 100 枚（军令字面）；staging 段独立生成、`kind='staging'`，数量自定（默认 20），**与 buyer 段永不同码**。生成走既有发卡口 `scripts/activation-code.py`，同源不另写一份算法。
- 接口（全部只此两个 + 一个管理口）：
  - `POST /api/activate` · body `{"code": "...", "device_hash": "..."}`
    → `{"ok": true, "seats_used": 1, "seats_total": 2}`
    → `{"ok": false, "reason": "invalid"}`（码不在白名单，含 staging 段错用）
    → `{"ok": false, "reason": "seats_full"}`（已绑满 2 台且本机不在列表）
    同一 `code+device_hash` 重复请求幂等返回 `ok:true`（重装/重开 App 不重复占额度）。
  - `POST /api/deactivate` · body `{"code","device_hash","admin_token"}` → 解绑一台（裁 1 那条兜底口）。`admin_token` 走环境变量注入、不落仓、日志永不出现。
  - `POST /api/admin/lease-staging` · body `{"admin_token"}` → 返回一枚在册且未占满的 `{"code","kind":"staging"}` 给自动化测试用（见 §4；**在册码一律不进仓、不进日志**）。
  - `GET /api/health` → 存活探针，无业务数据。
- **日志纪律**：服务端访问日志只记 `code` 的**尾四位**与 `device_hash` 的前 8 位；完整码/完整指纹不落盘。
- **限流**：同 IP 每分钟阈值内计数，超限 429（挡枚举；不做成长列表，轻量即可）。

## 2. APP 侧（联网代码唯一住处＝`:byok`，红线一字不改的关键）

- 新文件 `byok/src/main/kotlin/com/anytouch/byok/ActivationTransport.kt`：`HttpsURLConnection` + 自定义信任策略，**只做 SPKI SHA-256 指纹固定**（证书本身自签不可公共验证，指纹对不上即断开）。固定值以常量入仓（公仓里公开无妨：它不是秘密，公开的只是"信任哪一枚证书"）。自签无匹配域名 ⇒ 主机名校验不适用，改由指纹承担，此点在代码注释与账面同时写明，不许含糊成"关掉了校验"。
- 设备标识：`Settings.Secure.ANDROID_ID` → SHA-256 → 取前 16 位十六进制（**自钉 1：不是 MD5**，理由见 RULINGS S5-R14）。UI/日志最多回显尾 4 位，与激活码、API Key 同一条纪律。
- **接线位置硬约束**：`AndroidActivationDisk` 住在 `app/.../platform/`，而红线 G 禁 `platform/` import `com.anytouch.byok` ⇒ 联网那一步只许挂在 **MainActivity / 激活对话框层**（UI 面，S3 起本就允许看 byok）；platform 层继续只碰本地文件。此约束写成 JVM 锁（判据 9）。
- 本地既有 `ActivationCode.kt` **降级为前置过滤**（格式/长度/分隔位/字符集/校验位仍本地先判，省一次无谓请求并保住"五档脏码各说一句拒因"那六条既有判据），但**不再是放行权威**：本地过≠解锁，必须服务器 `ok:true` 才写标志。` ActivationStore.submit` 的返回档位需扩（见判据 3）。
- **自钉 2：fail-closed**。服务器不可达／超时／TLS 失败／429 ⇒ **拒绝激活**，绝不当"通过"处理；屏上明说"激活需要联网，当前连不上服务器"（**第四句文案，军令未列、本窗补，理由＝fail-closed 不解释会被买家当成码无效**）。一旦已激活，标志在本机、离线照用、**开机与每次进入都不再复核**（避免把激活期引入的联网扩散成常驻联网）。
- 超时口径：连接/读取各 ≤8 秒，整链 ≤10 秒后放弃并 fail-closed；重试按钮由用户手按，**永不自动重试到后台**。

## 3. UI 文案（全英文，军令第 3 条三句逐字，另加两句）

军令逐字三句：`Activation code invalid` / `This code has already been activated on 2 devices, maximum reached` / `Activation successful`。
本窗补两句（可覆）：`Activation needs a network connection. The server could not be reached.`（fail-closed 归因）与解绑耗尽前的 `Nothing was unlocked.`（沿用 v1.0.5 既有收尾句，不新造）。
屏上任何一处**都不许**出现完整激活码或完整设备指纹。

## 4. 测试通道（裁 3：staging 码段，生产 100 枚一次都不许碰）

**先记一条本窗施工前自查纠正（原判据若照写会全批假绿）**：白名单成为校验权威之后，**本地 mint 出来的码服务器一概不认**（格式合法≠在册）。原 §4 写"preflight 本地 mint 合法码"在 S5-f 成立、在 S5-g 必红；更坏的处理方式是"给测试加一条本地放行的旁路"，那正是判据 10 明令禁止的东西。故改为：

- **码从服务器租**：新增管理口 `POST /api/admin/lease-staging`（带 `admin_token`）→ 返回一枚**在册且未占满**的 staging 码给测试用；`activation-preflight.sh` 走这一口取码，取到后仍走 `ActivationStore.submit` **同一条校验路径**（旁路零条）。本地 mint（`scripts/activation-code.py --random`）此后**只服务两件事**：造格式负例（五档脏码六条拒因那批判据）、以及老板手工给真买家出码发货。
- **仓内零码原文（公开仓硬约束）**：本仓公开 + GPLv3 ⇒ **任何一枚在册码（buyer 或 staging）都不得进 git、不得进日志、不得进脚本字面量**。staging 码能解锁功能＝它也是免费许可证，泄进公开仓等于给全球开门。取码只经 admin 口，`admin_token` 落服务器 `.env`（0600）与本机 `D:\Qoder\secrets\anytouch-activate.md`（台账只放指针，见密钥落盘规矩）。
- preflight 必须**断言取到的是 staging**（admin 口返回 `kind` 字段），拿到 `kind='buyer'` 当场 `exit 2` 响亮失败——防配置错把手伸进真买家额度。
- 五支依赖激活的设备脚本（`ui-smoke`/`device-smoke`/`ui-english-sweep`/`s5d`/`s5e`）+ 新 `s5g` 脚本：**只在预检联网可达时跑**；不可达即 `exit 2` 响亮失败并照报，**不许**为了变绿而加"测试期跳过服务器"的旁路（那正是判据 10 禁的东西）。
- staging 绑定表可一键重置（`scripts/s5g-reset-staging.sh`，走 admin 口），重置动作与轮次一一对应入 raw。
- CI 依赖外网属**新的事实**，必须写进 STATUS 门禁列（以前激活面零外网依赖）。

## 5. 判据（1-12，逐条要有凭）

1. 服务器接口三态实测：`invalid` / `seats_full` / `ok`（含幂等重放同 `device_hash` 不占第二格）——curl 直打 staging 段，raw 入档。
2. **绑满即拒**：同一枚 staging 码，第 1、2 台成功，第 3 个不同 `device_hash` 必须 `seats_full`；解绑一台后第 3 台可进（证解绑口真有效）。
3. APP 端六档本地拒因不回退（v1.0.5 判据 1-5 全绿），且新增一档"本地格式过、服务器拒"→ 屏上落 `Activation code invalid` 且**不解锁**。
4. **fail-closed 直证**：服务器可达性两种切断方式（服务停 / 指错端口令 TLS 校验失败）下，新装未激活机必须**保持未解锁**，三入口照旧拒，屏上给联网归因句。
5. **已激活离线照用**：`install -r` 换进程 + 断网（模拟器飞行）后，激活态仍在、三功能可用，且**飞行模式下不产生任何对本接口的请求**（logcat 与 `tcpdump`/包名级流量双向取一为凭）。
6. 执行期零网络不破：既有飞行模式回放判据（Photos 全链 5 轮 13/13 那一支）在同一枚 v1.0.6 字节上复绿。
7. 设备标识纪律：屏上/日志/语义树分页扫描，完整激活码与完整设备指纹**两处分页都读不到**，只回显尾 4。
8. TLS 反向锁：客户端对**非固定指纹**的证书必须断开（用另一枚自签证书实测一次），证明指纹固定不是装饰。
9. 结构锁：JVM 新增用例钉"激活联网代码只住 `:byok`"、"`platform/` 与执行路径 import 不到 byok"（红线 G 的同源机器化），且付费墙两条既有锁不放松。
10. 旧账不回归：同一枚 v1.0.6 字节，`s5d` 15/15、`s5e` 36/36、`ui-smoke` 51/0/0（SKIP=0）、`device-smoke` 15/15、Photos 全链 5 轮 13/13、`ui-english-sweep` 全英文 CJK=0；JVM 只增不减（基线 552）。
11. 红线 A-I 全清，`git diff scripts/ci-local.sh` 为空＝**没有为过红线而改红线**。
12. 对外改口（裁 4）：README 隐私段与 README:4 那句改成如实口径；v1.0.6 Release 正文明写本版起服务端校验、并声明"v1.0.5 及以前为纯本地校验"；v1.0.5 及更早 Release 不回改。发布账先建后记（commit→tag→Release→匿名 curl 回对字节）。

## 6. 不做项（明确不做，防日后当成欠账或越权）

- 不做账号体系、不做遥测、不做启动即联网、不做使用中复核、不做远程吊销已激活设备（第一版）。
- 不做买家自助解绑界面（解绑由老板在服务端 admin 口执行；界面留待有投诉再做）。
- 不接 Gumroad 支付回调（发货/发码仍人工）。
- 不做付费墙文案的"防破解"表述（S5-R13 裁 1 禁令继续有效）。
- 不碰 `ai-congress-online` 容器与其数据。
