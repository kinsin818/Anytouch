# S5-g 服务器侧部署实录（Vultr 45.32.63.177）

**日期**：2026-09-26（机内时钟 UTC 09-25 20:43–20:52，与本机差 8 小时，两侧都照原文记）
**动作范围**：新建独立用户/目录/证书/库/systemd 单元；**全程未碰** `ai-congress-online`、`ai-congress-redis` 及其数据卷（部署前后 `docker ps` 原文见 §6）。

## 1. 落地形态（与执行面原写法的差别已登记）

原写"独立容器"，实落 **systemd 单元 + 机自带 Python 3.14**，隔离边界一条不少、且更彻底（不在 docker daemon 链上）。
口径与理由见 `orders/ANYTOUCH-S5g-server-activation-APPENDIX.md` §1 那条"落地形态与本文原写法的差别"。

```
/opt/anytouch-activate/app.py              ← 仓内同名文件的逐字副本
/opt/anytouch-activate/seed_whitelist.py
/opt/anytouch-activate/tls.crt / tls.key   （key: root:anytouch 0640）
/opt/anytouch-activate/.env                （root:anytouch 0640，含 ADMIN_TOKEN，值永不入仓/入日志）
/etc/systemd/system/anytouch-activate.service
/var/lib/anytouch-activate/activation.db   （anytouch:anytouch 0600，目录 0700）
```

## 2. 白名单 seed（整码零入仓）

发卡口 `scripts/activation-code.py --random` 本地生成 **buyer 100 + staging 20**，整码只落两处：
服务器库 + 仓外私有件 `D:\Qoder\secrets\anytouch-activate-codes-20260926.txt`（0700 umask）。
传输走 **stdin**（`ssh ... python3 seed_whitelist.py < codes.txt`），**不走 argv**——argv 会在那台机的进程表里被任何一次 `ps` 捞走。

原文（本地干跑与实机各一次，两次同结论）：
```
seeded=120 total={'buyer': 100, 'staging': 20} db=/var/lib/anytouch-activate/activation.db
本地干跑：codes 行数=120，唯一码=120，kind 分布=buyer:100,staging:20
buyer∩staging 重叠=0（必须 0）
```
泄露自查（全仓整码形态 ∩ 白名单，逐枚比对）：
```
白名单规模=120  仓内整码形态命中=11  其中在册码(=泄露)=0
```
仓内那 11 枚整码形态全部是 golden/反例向量（`ANY-A1B2-C3D4-E5NX`、`ANY-0000-0000-00WW` 等），无一枚在册。

## 3. 证书与指纹（判据 8 的输入）

自签，`CN=anytouch-activate`，SAN = `IP:45.32.63.177, IP:127.0.0.1`，有效期 3650 天。
```
notBefore=Sep 25 20:43:35 2026 GMT
notAfter =Sep 22 20:43:35 2036 GMT     ← 到期账面待办：2036-09-22
SPKI SHA-256(全)=3d7146a142f8d08fd507599eb426f8368224ed4e4414f0a2c0f6365bca18afa6
APP 固定用(前 16 位十六进制，主窗自钉口径)=3d7146a142f8d08f
```
机内不带 `-k` 直连 → `rc=60`（自签必不被系统信任）。**这正是裁 2 要做指纹固定的原因**：信任链上没有 CA，
就必须在客户端钉死这枚 SPKI，而不是"跳过校验"。

## 4. 服务起来后的机内实证

```
systemctl is-active → active        监听 → LISTEN 0.0.0.0:8443 (python3 pid 128701)
curl -sk https://127.0.0.1:8443/api/health → {"ok": true, "service": "anytouch-activate"}
curl -sk https://45.32.63.177:8443/api/health → 同上（经自己的公网 IP，验绑定与 SAN 覆盖）
journal 原文 → "anytouch-activate listening on 8443 (tls>=1.2)" / 127.0.0.1 "GET /api/health HTTP/1.1" 200 -
```
Memory 10.6M、Tasks 1、`ProtectSystem=strict` 等七条硬化指令**全部被 systemd 接受且未导致启动失败**（不是"配上就算"，是起了进程、健康口答了 200）。
服务端日志只见尾四位与前 8 位哈希，无整码（与 §5 契约面同一条纪律）。

**机上件 = 仓内件（逐字节对拍，防"跑的是旧包"这一族老账）**：
```
md5sum server/activation/app.py            → 879382ae4ae4a19ebc5437fb12054c6d  9780B
ssh cat /opt/anytouch-activate/app.py      → 879382ae4ae4a19ebc5437fb12054c6d  9780B
cmp -s → 逐字节相同
```

## 4b. 服务器侧运行时实证（判据 1/2 在公网实例上的复述，经机内 loopback）

**原文日志**：`evidence/S5g/raw/s5g-onbox-e2e-20260926-045828.raw.txt`（只动 staging 段，测完复原；buyer 段一枚不碰）
```
lease: ok=True kind=staging tail=5BWQ
activate first:  ok=True seats=1
activate replay: ok=True seats=1      ← 幂等：同机重放不重复占额度
activate second: ok=True seats=2
activate third:  ok=False reason=seats_full   ← 军令"最多绑 2 台"在这枚真实生产库上成立
复原核验: ok=True seats=1（解绑后额度确实放回，不是写死的账）
终态: codes={'buyer': 100, 'staging': 20}  该码剩余绑定=0  全表最大绑定数=None（bindings 全空）
journal 整码扫描=0
```
契约面 19 格是本机临时实例打的（自签测试证书、库现造现删）；本节是**同一份 `app.py`、同一套 systemd 硬化、生产白名单库**上的复述。
两格合一才够判据 1/2 的"有凭"：前者证逻辑与并发锁，后者证部署形态与真库不改变行为。

## 5. 公网可达性：**未定性**，已排除两条错读

这是本节唯一结论，也是本批现在真正的开口。

- **我这台 Windows 机的一切外网读数作废**：`api.github.com` 被解析到 `198.18.2.166`（198.18/15 是本地拦截池地址段），
  且对**无人监听的 9999 端口 curl 也报 "Established connection"**（本机出网被透明代理接管）。
  在此机器上测得 `https://45.32.63.177:8443` 超时、`http://…:80` 12 秒零字节——**这些是本机网络栈的性质，不是服务器性质**。
  winhttp 与注册表均无代理配置，`--noproxy '*'` 同样超时，故不是环境变量层面的事。
- **第三方探测 `check-host.net` 的读数同样作废**：
  - 对照组（它自己的工具是好的）：`one.one.one.one:443` → 各节点 ~1.5ms **连通**。
  - 本轮：`45.32.63.177:8443` → 57 节点全 `Connection timed out`；**对照端口 `:80` 也 57 节点全超时**。
  - 而 80 是老板在营服务的端口，**同一时刻本机日志里有真实公网流量在进**：
    `INFO: 104.23.223.28 - "GET /wp-admin/install.php?step=1" 404`、`104.23.166.82 - "GET /" 200`（近 2 小时 24 行）。
  - 定性实验：本机 `tcpdump -ni enp1s0 "(tcp port 80 or tcp port 8443) and tcp[tcpflags] & tcp-syn != 0"` 开抓，
    期间向 check-host 同时申请 8443 与 80 两轮探测 → **0 packets captured**。
    即：探测包的 SYN **一个都没到达本机**，80 也不例外。
  - 结论：那个探测服务的地址段被**上游**丢了（Vultr 侧防火墙或 DDoS 防护的信誉拦截），它的超时**不能证明 8443 对外关闭，也不能证明开放**。

**因此账面写"8443 已对外开放"是假绿，写"8443 被防火墙拦死"是未归因的假红。两者都不写。**
需要的外部一手凭只有两条来源，都在老板手里：① Vultr 控制台 Firewall 页看这台机有没有启用规则集（若有，加一条 Inbound TCP 8443），
或给我一个 Vultr API Key 由我用 API 自查自加；② 手机关掉 Wi-Fi 用蜂窝网打一次 `https://45.32.63.177:8443/api/health`（本机扫盘无 Vultr 凭据，已核 `D:\Qoder\secrets` 与 `D:\Qoder` 全域，零命中）。

## 6. 部署前后对照（证明没碰到买家业务）

```
部署前 docker ps：ai-congress-online | 0.0.0.0:80->8000 | Up 3 days
                  ai-congress-redis  | 6379/tcp         | Up 3 days
部署后 docker ps：同名同端口同状态（无 restart、无 recreate、无新卷）
```
新服务是宿主机 systemd 进程，与这两个容器**不共享网络命名空间、镜像、卷与 SQLite 文件**；
`/var/lib/anytouch-activate` 目录 0700 属 `anytouch`，容器侧无读路径。

## 7. 回滚口（万一要撤）

`systemctl disable --now anytouch-activate` + 删 `/etc/systemd/system/anytouch-activate.service`，
数据留在 `/var/lib/anytouch-activate`（不自动删，防误删买家绑定账）；整条链没有任何东西写进 ai-congress 的目录。
