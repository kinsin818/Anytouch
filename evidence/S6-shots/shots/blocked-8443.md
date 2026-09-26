# SHOT-2 / SHOT-3 BLOCKED-8443 — AVD 侧直连探测原文与结论

工单：`ANYTOUCH-S6-AVD三屏商品图-ORDER-20260926.md` §1-1
结论一句话：**从本测试机上的 AVD 直连 `https://45.32.63.177:8443` 不可达（产品自身 fail-closed `SERVER_UNREACHABLE`，服务器机内 journal 同窗零收到），按工单硬前置只交 SHOT-1。**
本件不含任何整激活码，指代只写尾四位。

## 探测原文（全文见 `raw/20260926-150402/probe-8443.txt`，以下为逐段照录）

### §0 残留 DNAT 检查（保证探测打在真地址上）
```
$ adb -s emulator-5554 shell iptables -t nat -L NAT -n | grep 45.32
(无命中，grep 退出码=1 ⇒ 无中继无DNAT，干净)
```

### §1–§5 TCP 层读数作废证明
- AVD 内 `toybox nc` 打 `45.32.63.177:8443` → rc=0；
- **反面对照**：打同 IP **明知无监听的 9999** → 同样 rc=0；
- 主机侧 `/dev/tcp` 对 8443 与 9999 均报 open。
⇒ 本机出网被透明拦截接管，**TCP connect 层一切读数不作数**（与 S5-g 部署账"对无监听的 9999 也连接建立"同一形态，该账面在册）。

### §6–§8 TLS 层（内容级）
- 主机侧 `openssl s_client -connect 45.32.63.177:8443` 取对端证书：**零字节**（SPKI 实测为空输入的 SHA-256 `e3b0c442…`，即没有拿到任何证书）；TLS 内 `GET /api/health` 零字节。
- 探测器自证：同一管线打 Cloudflare `104.16.132.229:443` 正常取回 SPKI 前 16 位 `96d43a697cb7b6aa` ⇒ **工具是好的，8443 那侧真没内容回来**。

### §9 机内对照（证明服务本身活着）
```
$ ssh root@45.32.63.177 'curl -sk https://127.0.0.1:8443/api/health'
{"ok": true, "service": "anytouch-activate"}
LISTEN 0 5 0.0.0.0:8443 users:(("python3",pid=140520,fd=3))
```

### §10 定性实验（主窗可逐字复现）
服务器上 `tcpdump -i enp1s0 -n 'tcp port 8443'` 抓 45 秒窗口，同窗从本 Windows 机发 3 发 HTTPS 探测：
```
0 packets captured
0 packets received by filter
0 packets dropped by kernel
```
⇒ 本机的探测 SYN **根本没到服务器网卡**（机内回环不走 enp1s0，此计数不含 §9 自打）。

### §12 产品直连激活探测（决定性，设备侧原话）
无中继无 DNAT，AVD 内注入 staging 码（尾四位 `C0YN`）走产品唯一校验路径：
```
09-26 07:21:49.910  W AnytouchRun: S5FSMOKE activation refused gate=SERVER_UNREACHABLE via=adb_inject
detail=Activation needs a network connection. The server could not be reached,
so this code was not checked at all. Nothing was unlocked.
```
- 同一探测窗口内服务器机内 `journalctl -u anytouch-activate --since '3 min ago'` → **No entries**（尾四位 grep 命中 0）⇒ 请求未到达服务器，与产品 fail-closed 读数互证。
- 屏上拒因逐字：`ui-dump-activation-rejection.xml`，画面：`raw/20260926-150402/scan/activation-rejection-frame.png`。
- **码未被消费**：未校验=不占格，服务器端该码绑定数不变（staging 段本就空）。

## 归因边界（不洗）
本批能定性的只有"从这台 Windows 测试机（含其上的 AVD）打不到 8443"。**不能区分**：
1. Vultr 入站规则仍未对 8443 生效；与
2. 本机出网对 `45.32.63.177` 整段丢弃（同机历史读数：对**有真实公网流量的 :80** 同样全超时，S5-g 账面在册，故本机一切"超时"不作数）。
判定"对真实买家可达"仍需工单 §1 之外的干净外部路径（老板手机蜂窝直打，或另一台干净机器 curl），本窗不冒判。

## SHOT-2/3 现场状态照登
- 两页控件真实在场（免费态）：`Repetitions (1-100)`／`Interval seconds (1-60)` 见 `raw/20260926-150402/ui-dump-shot1c-bottom.xml`（bounds 在视口内、`enabled="false"`、上屏"Upgrade to Pro to repeat rounds."）；My tasks 段同帧（"Upgrade to Pro to save and reuse your own tasks."）。
- **解锁态两图未截**：激活这一步的服务器握手在本机不可达。既有 S5-g 正规中继（`ssh -L 18443` + AVD DNAT，对面=真服务器、产品代码一字未动）可在约 30 分钟内补拍两图；**是否按中继口径补拍，交主窗裁决，worker 不擅动**。
- My tasks 列表页还需盘上至少 1 条已保存任务（构造路径：本地"录制→停止并编译"或注入 SAMPLE_TASK 后 Save，均零模型请求、不碰 S4 额度账）——此步同样等补拍裁决一并做。

## 补记（同日晚些，只追加不覆盖）：S5-g 受制裁中继通道可完成激活，SHOT-2/3 具备拍摄条件——待主窗裁

09-26 15:43 起，本窗按 S5-g 既定测试通道（`scripts/s5g-relay.sh`：靶机 DNAT 10.0.2.2:18443 → ssh -L 隧道 → 真服务器 8443；
对面证书 SPKI 实测 `3d7146a142f8d08f` 与 APP 固定值逐字一致）跑 `scripts/device-smoke.sh` 前置，激活成功：

```
relay :: up · 靶机 45.32.63.177:8443 → 10.0.2.2:18443 → 机上 127.0.0.1:8443 · 对面证书实测 3d7146a142f8d08f（本轮期望 3d7146a142f8d08f）
activation-preflight :: 已激活（租到 staging 段，尾四位 9EXD，… S5FSMOKE activation ok tail=9EXD via=adb_inject seats=1/2）
```

事实边界（两条都不许混着说）：
1. **§1–§12 的直连结论不变**：从本机（含 AVD）直连 8443 不可达，本窗没有推翻它；
2. 经中继完成的这一次激活**走的是产品唯一校验路径**（真服务器、真 SPKI、真 staging 码、同一个 `ActivationStore.submit`），
   不是 v1.0.5 本地判据、不是假界面——但它**也不是工单 §1-1 口径下"AVD 直连通"**。
3. 因此 SHOT-2/3 用中继激活态拍摄是否算"绕硬前置凑图"，**本窗不自裁**，列为请示项。
   本窗按工单字面口径执行：正式交付仍为"只交 SHOT-1"；中继态图若主窗点头可用，作为**待裁附件**另列。

中继残留现场：机上 DNAT 与 ssh 隧道仍在跑（S5-g 生命周期口径：一轮设备面跑完由人显式收）。
收法：`ANDROID_SERIAL=emulator-5554 bash scripts/s5g-relay.sh --down`（本窗收尾时会执行并留痕）。
