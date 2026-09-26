# ANYTOUCH-S7 跨窗回报（施工总窗 → 主窗/执行总监 · 抄老板）

日期：2026-09-26（本机 +08:00；服务器侧一律 UTC）
军令：`D:\Qoder\ops\orders\ANYTOUCH-S7-跨窗-封面上传与8443定性-ORDER-20260926.md`
老板本批两裁（约束优先于军令原判据）：**T1 = 先只读侦察，不保存**；**T2 = 只做被动对照**（不起临时监听、不改任何服务）。
证据目录：`D:\Anytouch\evidence\S7-gumroad-cover-8443\`

## 一句话结论（两条）

1. **T1：没做成，且按军令"当场停下上报"。** 军令指定的第六路 `upload_file {uid, filePath}` 在 MCP 桥层就被拒，
   三次报错原文一字不差：`Invalid arguments for file_upload: name is not supported`（换两枚不同 uid、增减可选字段皆同）——
   这句报的是桥内部多塞了 `name` 字段，**不是主窗上次"我参数写错"**，该归因本批更正。
   且后台把文件输入做成 `display:none`，两次快照（315／347 元素全量）里 Cover 区**没有任何可指认的 input[type=file] uid**。
   公开页封面**仍为 `covers:[]`**，与侦察前那份 32,043 B 逐字节对拍**只差一枚 csrf-token**（第 735 字节）⇒ 本窗零保存、零对外变更。
   唯一剩下的可行路：**(a) 人的手**把三枚已按规格备好的 PNG 拖进 Cover 投放区并 Save；或 **(b)** 修 `file_upload` 桥。
   本窗**没有**走任何被禁旁路（外链图、base64 塞 textarea、直 POST 商家接口）。
2. **T2：定性 = 未定性，缺老板的手。** 三端口同窗被动抓包（win5，08:36:34–08:41:34 UTC，
   `17 packets captured / 0 dropped by kernel`）读数：**22 → 9 包（全部是本窗自己 ssh 的出口 `209.248.0.98`）、443 → 0、8443 → 0**。
   本批真正的收获不是"8443 通不通"，而是**把 S5-g 那把尺子判废了**：同一工具、同一台靶、同一时间窗，
   对**正承载它自己那条 ssh 会话**的 22 端口报 **58/59 节点超时**，ICMP 报 **59/59 全丢**，HTTP-80 报全超时
   （而同窗 tcpdump 在 :80 抓到过 7 个真实公网入站包）。⇒ 它的"超时"**不能归因到端口**，
   S5-g 账上"57 节点全超时 ⇒ 上游把这段地址丢了"那句推断**本批撤回**。
   机内防火墙已排除（`-P INPUT ACCEPT`，INPUT 链仅一条 `--dport 9000 DROP`，ufw-* 全空），
   服务在公网 IP 上自答 `{"ok": true, "service": "anytouch-activate"}` HTTP 200，
   但该单元**整段 journal 历史**（342 行）只出现过 `127.0.0.1`×163 与 `45.32.63.177`×5 两类客户端 ⇒ **至今没有任何外部 IP 打到过 8443**。
   缺的 exactly one of：① Vultr 控制台该实例 Firewall/DDoS 页只读视图（或 API Key，只进内存不落仓）；
   ② 一台关 Wi-Fi 走蜂窝的手机打一次 `https://45.32.63.177:8443/api/health`。

## 2. 交付物清单（本批落盘，逐件对号军令所列）

| 军令要求 | 本窗件 | 大小 |
|---|---|---|
| T1 结果 | `cover-upload-result.md` | 7,892 B |
| T2 抓包 | `probe-tcpdump.raw.txt`（含 win5 全 17 行原文＋win1–4 计数＋防火墙排除＋收尾无遗留进程＋末段口径精确化追加） | 6,570 B |
| T2 外部读数 | `probe-external.raw.txt`（E1–E8 八发永久报告链接＋三处矛盾＋到达计数表） | 7,125 B |
| 前后对照 | `raw/ss-before.txt` 1,097 B ／ `raw/ss-after.txt` 1,649 B（监听集与 `docker ps` 逐行一致） | — |
| 443 实验 | `port-443-experiment.md`（如实登记"未执行"＋443 空闲前置证据＋无需执行的论证） | 4,889 B |
| 本回报 | `S7-report.md` | 本件 |
| 渲染证据 | `raw/editor-cover-empty.png`（后台 Cover 区空槽截图，16,223 B） | — |
| 原始抓包 | `raw/capture-win1..win4.raw.txt`、`raw/capture-win5-3port.raw.txt` | 20,372／56,376／421,321／35,483／2,581 B |
| 账面就地销 | `D:\Anytouch\STATUS.md` 待办 18（原文一字未删，头部加定性、末段追加 S7 复验与撤回） | +19／−1 行 |

## 3. 五关自检（本窗自跑，主窗可逐条独立复跑）

| 关 | 判据 | 读数 |
|---|---|---|
| G1 | 在册整码零入仓 | 私有件 120 枚（buyer100＋staging20）逐枚对全批新件＋`STATUS.md` 比对：**命中 0**；形似字面 2 枚＝`ANY-XXXX-XXXX-XXXX`（占位符）与 `ANY-A1B2-C3D4-E5NX`（S5-f golden 向量夹具，仓内既有、不在册） |
| G2 | 未起任何监听／未改任何服务 | 脚本取 `LISTEN` 行机械比对：`ss-before` 全量监听表 7 行与 `ss-after` 7 行**逐行一致**（before 另第 8 行是其自身 `=== listening 8443 ===` grep 摘录的重复，非新增）；8443 仍是那一个 `python3 pid=140520`，443 两度皆空；`docker ps` 两度同名同端口同 `Up 4 days`；`pgrep -a tcpdump` 两次核对无遗留 |
| G3 | 对外零变更（T1 未保存） | 公开页复拉 HTTP 200／32,043 B，`&quot;covers&quot;:[]`、`&quot;thumbnail_url&quot;:null` 原样；与基线 `cmp` 唯一差异在第 735 字节＝csrf-token；未点 `Save changes`／`Unpublish`／横幅两钮 |
| G4 | 产品码／脚本／红线零改动 | `git status --porcelain` 仅 `M STATUS.md` ＋ 本批证据目录；`git diff --stat`＝`STATUS.md 19 insertions(+), 1 deletion(-)`；`git diff --check` 干净；`app/`、`byok/`、`scripts/`、`server/`、`docs/`、`README.md` **零改动** |
| G5 | 设备与额度零触碰 | 本批不跑 Gradle、不驱模拟器；K40／K80／emulator-5556／物理机 `7ae4bfee` **零指令**；S4 额度 6/8 定格未动；真模型请求零消耗；截图不出设备 |

## 4. 本窗未做、不得被读成"已做"的三件

1. **没有**证明 8443 对外可达，也**没有**证明它被拦死——账面取第三条措辞"未定性，缺老板的手"。
2. **没有**跑军令 T2 ② 的 443 临时监听实验（老板改裁被动对照）；预置步骤已写进 `port-443-experiment.md` §4 备日后。
3. **没有**把任何封面送进商家后台。三枚素材字节与 sha256 已在 `cover-upload-result.md` §1 登记，可直接交人的手使用。

## 5. 提请主窗/老板裁的三件（本窗不自作）

- **(老板面)** T1 剩下唯一路是"人的手"：谁去后台拖三枚图并 Save？（素材路径见 §1 表）
- **(老板面)** 待办 18 的两把钥匙给哪一把：Vultr 控制台只读视图／API Key，还是蜂窝手机打一次 health。
- **(主窗面)** 本窗工作树里出现一枚**非本批**的未跟踪目录 `evidence/S6-shots/`（8,602 B 报告 + `raw/` + `shots/`，
  落盘时间 09-26 15:04–16:05，属 S6 那一批的产物）。本窗**没有**把它带进 S7 的 commit，也**没有**删除它；
  归谁入册请主窗定。
