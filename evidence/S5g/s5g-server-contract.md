# S5-g 服务器契约测试（主机侧，零设备）

**日期**：2026-09-26（04:29–04:37 两轮）
**被测对象**：`server/activation/app.py`（Python 3 标准库，无第三方依赖）
**脚本**：`scripts/s5g-contract-test.sh`
**原文日志**：`evidence/S5g/raw/s5g-contract-20260926-043702.raw.txt`（尾部原文 `CONTRACT pass=19 fail=0` / `SCRIPT-RC=0`）

## 1. 这一格验的是什么、不验什么

验的是**接口逻辑本身**：军令 §1 那四条分支（在册即绑 / 未满放行 / 满员拒 / 已绑幂等）＋ 裁1 的解绑口 ＋ 裁3 的 staging 租约口 ＋ 并发不超绑。

**不验的两件，不在这里充数**：
- **证书指纹固定**（判据 8）属 APP 客户端层行为，本脚本一律 `curl -k`，跳过证书校验是脚本的设计边界，不是漏洞被掩盖。
- **真机 TLS 握手**：本轮证书 SAN 只有 `IP:127.0.0.1`，与将部署到 45.32.63.177 的那枚（SAN 含公网 IP）不是同一枚；指纹要到部署轮再算再记。

在册码一律**现生成现丢弃**：脚本每次跑都走 `scripts/activation-code.py --random 5` 发新码，work 目录随 trap 删除，日志与仓内**零整码**（本文末 §4 有扫描原文）。

## 2. 判据结果（19 格，raw 逐行）

| 段 | 判据 | 结果 |
|---|---|---|
| [4/7] 三态 | 不在册但格式合法 → `invalid` | PASS |
| | 结构脏的码 → 同一档 `invalid`（不泄露"在册但格式错"） | PASS |
| | 在册码首绑 → `ok seats=1` | PASS |
| | 同码同机重放 → `ok` 且 seats 仍 =1（幂等，不重复占额度） | PASS |
| [5/7] 绑满即拒 | 第二台 → `ok seats=2` | PASS |
| | 第三台 → `seats_full`（军令"最多绑 2 台"成立） | PASS |
| | 满员后已绑过的机器仍可激活（不被自己锁死） | PASS |
| | 解绑一台 → seats 回 1 | PASS |
| | 解绑后第三台可进（裁1 兜底口真有效） | PASS |
| [6/7] 并发超绑锁 | 8 路并发抢 1 码，库里最终绑定数**恰为 2** | PASS |
| | 8 路恰好 2 路拿到 `ok`（不多放） | PASS |
| | 落败 6 路**逐条明回 `seats_full`**（不是超时/断连/空应答被误当成拒绝） | PASS |
| [7/7] 管理口 | 错 token → 403 `forbidden` | PASS |
| | 缺 token 的解绑 → `forbidden`（不能被人白嫖解绑） | PASS |
| | 租到在册 staging 码，且 `kind=staging` | PASS |
| | staging 占满 → `no_staging_seat`，**绝不回退去租 buyer 段** | PASS |
| | buyer 段全程零使用（未进租约池、未上服务端日志） | PASS |
| | 全表复查：任何一枚码绑定数都 ≤2 | PASS |

并发那格的分布原文：`并发应答分布：ok=2 seats_full=6 其他=0`。
这一格是本轮唯一有实质增量的：前 16 格全绿的那轮是**单线程**打的，服务端 `BEGIN IMMEDIATE` + `_db_lock`
那条防超绑守卫**根本没被走到**——只靠注释和"看起来对"的话写着，等于没测。`ThreadingHTTPServer` 起真并发 8 路才逼出来。

## 3. 本批测试通道自己造的四条雷（都是我的，不是产品的）

1. **`echo` 里的反引号被当命令替换**：段落标题写了 ``防 `BEGIN IMMEDIATE` 那条``，bash 真去执行了 `BEGIN`
   → `line 110: BEGIN: command not found`，标题打成空词。日志写作里的反引号 ≠ 无害排版。
2. **裸 `wait` 把整脚本挂死**（本批最贵一条）：`wait` 不带参数等的是**所有**后台作业，而本脚本此刻已挂着
   常驻服务进程 + `exec > >(sed|tee)` 那条进程替换，两者都不退出 → `wait` 永不返回。实测 180 秒超时无一字节
   产出（`sed` 非 tty 时块缓冲，所以 raw 连标题都没刷出来，"零输出"不等于"没跑起来"）。
   对策：`RACE_PIDS+=(...)` + `wait "${RACE_PIDS[@]}"` 只等这 8 个 curl。
3. **伪哈希不是哈希**：并发格早先用 `race0001` 当 device_hash，服务端 `HASH_RE` 只认 8–64 位十六进制，
   `r`/`t` 不合法 → 八路全被当脏输入拒掉，"最终 2 台"会变成"0 台"或者压根测不到锁。改成 `fa5e000X`。
4. **占满 staging 那段会把自己写的最后一条复查拆掉**：原写法对每枚非 STG1 的 staging 硬插 2 条绑定，
   而并发格已经给 STG_RACE 绑了 2 台 → 那一格被顶到 4 条 → 报错的是我"全表 ≤2"的复查，不是服务的罪。
   改成**补到 2**（先查现有条数再决定插几条）。

顺带一条环境雷（沿用 S5-f 记录）：这台 Windows 只有 `python`、Linux 侧只有 `python3`，脚本用
`PY="$(command -v python3 || command -v python)"` 两边同一条命令；openssl 的 `-subj /CN=...` 必须
`MSYS_NO_PATHCONV=1`，否则 Git Bash 把 `/CN=` 改写成 `C:/Program Files/Git/CN=`。

## 4. 泄露面自查（原文）

```
$ grep -oE "ANY-[A-Z0-9]{4}-[A-Z0-9]{4}-[A-Z0-9]{4}" <raw> | sort -u     # 整码命中 0
$ grep -oE "tail=[A-Z0-9]{4}" <raw> | sort -u                             # 只有尾四位
$ grep -cE "aaaaaaaa11112222|fa5e000" <raw>                               # device_hash 全文 0
```
本轮日志里出现过的只有两枚尾四位：`tail=66VY`、`tail=DLAC`。

## 5. 端口残留守卫（新增，防假靶）

上一轮挂死被强杀后，孤儿服务进程仍在 `18443` 上 LISTENING（`netstat` 与 `ps -a` 双向对上，同族手段核活后 kill）。
危害不是"占端口"而是**它对不上本轮新生成的码**：新服务绑不上端口、请求却被孤儿答掉，测试会对着假靶跑。
脚本现在起服务前先探一次，端口上已有应答就当场 `die`，不放跑。

## 6. 尚未证明的（部署轮要接着证的）

- 公网 8443 可达性（Vultr 账号侧防火墙有无拦截，本机 iptables 三链全 ACCEPT，实测才算）。
- 生产那枚自签证书的 SPKI SHA-256 指纹（进 APP 常量，非秘密）。
- 100 枚 buyer 码入库后的 seed 过程——**整码只出现在服务器库与仓外私有件，绝不进 git/日志/脚本字面量**。
- buyer 段与 staging 段的隔离在真实白名单规模下仍成立（本轮只有 1 枚 buyer）。
