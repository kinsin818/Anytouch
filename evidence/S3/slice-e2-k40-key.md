# S3-E2 · 切片 E 真机半边收口（面板形态 / 真 HTTPS / 真 Keystore / 词表真采）

> 落盘人：施工总窗口（主窗）　日期：2026-09-24　依据：`orders/ANYTOUCH-S3-byok-ORDER.md` §5（切片 E 四点）、
> 老板 09-23 令「继续往下做切片 E：上真机验证面板、真 HTTPS 调用、Keystore 存储这四个点」、
> 老板裁决 S3-R5（Key 由主窗代填：「这些 KEY 都是免费的…你直接从后台输入不就完事了嘛」「你来操作把」）。
> 原文证据：`evidence/S3/raw/byok-smoke-k40-e1pre.log` [K4] 节（真机逐字原文，含终端转义序列）、
> `evidence/S3/raw/byok-smoke-emulator-e1.log` [E1m] 节（模拟器 keyed 补录，用来钉假红与暴露真缺陷）。

## §1 一句话结论

**K40 真机一轮 36 条断言、0 失败**（跳过 4 条 = E11a~E11d 清除双槽，那四条要老板的手指），
切片 E 的四个设备点全部在**真机**上取证：面板屏上形态（E1a~E1o）、真 HTTPS 编译（E5a `steps=1 rows=31`
且产物真执行 `ok=1 total=1`）、真 Keystore 跨进程读回（E9a pid 22996→26696、E9b/E9c 同一尾 4 位）、
词表真采几条（E5c rows=31 上屏 + E10 关开关 rows=0）。

## §2 四点各自判据与命中原文（全部出自 [K4]，本机 +0800）

| 点 | 判据（军令原文的意思） | 真机命中原文（时间戳=设备 UTC） |
|---|---|---|
| ① 面板屏上形态 | 手机上手填三项 + 保存/清除 + 凭据状态行**都在屏上看得见的地方**，且屏上只允许 `***尾4` | E1a~E1i 逐项命中；E1j/E1k「折叠线以下滚得到：开始录制 / 停止并编译」；E1l `[本机已保存 Key：***JgYp]`；E1m 地址回显在屏（裁 §3-4）；**E1o 整页无 24+ 连续字母数字段**（真凭据在场时的整页扫描，不是空屏扫描） |
| ② 真 HTTPS 往返 | 一次真请求换回一条真产物，产物走既有落账口并进建议流 | `08:28:19.394 … S3SMOKE compile ok steps=1 replaced=0 rows=31`；E5e `type 集合=[click,]`；E5h `S1SMOKE ok=1 total=1 stopped=false stop=-`（AI 产物**没被 V-3 误拒**且真跑通那一步） |
| ③ 真 Keystore 存储 | 写成功≠存上了：进程被真收走之后必须**现读**回同一把 | E9a `pid 22996→26696`（`install -r` 同构建，保留 /data 与别名；不用 force-stop=雷 13）；E9b `config loaded tail=***JgYp notice=你的 Key 只发往这一个地址：integrate.api.nvidia.com`；E9c 屏上尾 4 位 kill 前后逐字一致 |
| ④ 词表真采几条 | 开关开着=真采到几条并上屏；关着=**零条上行**（裁 2 的设备半边） | E5c `rows=31`（同一条账上屏=E5d `ai_ctx_notice` 命中）；E10 `compile ok steps=1 replaced=0 rows=0`（off 档真零条，目标窗仍是 Settings） |

另附**执行中拒编译**（裁决 S3-R4-1 的反向档）：E4a `compile refused gate=RUNNING … rows=0`、
E4b「执行中的那次编译一个字都没发出去」= `compile ok` 计数 0，E4c 那一跑正常收尾留痕；
编译在跑的双拒与过期边：E8a/E8b 两档拒因、E8c 会话没被起、E8d/E8e 两格拒因同屏并存、
E8f `record stop rejection expired gate_was=COMPILING`。

## §3 构建与凭据口径（谁在验、验的是哪一枚）

- 被验 APK：`D:/Anytouch-e1w/app/build/outputs/apk/debug/app-debug.apk`（mtime 09-23 23:42）——
  **与 JVM 332 例门禁、九线红线、redline-probe 同源的那枚**，不是另出的包。
- 装法：`adb -s 7ae4bfee install -r`；无障碍绑定条目装前装后都核过（雷 13 之后不碰 force-stop）。
- 凭据进机：`scripts/byok-credential-inject.sh --key-file <老板给的 key 文件>`（S3-R5 代填通道）——
  脚本内无 Key 字面量、全程不打印 Key、逐字符下发后逐字段读回比对（`input text` 整串下发有换序竞态，见 §4-③），
  屏上/日志里只出现 `***JgYp`。Key 不落 git、不落证据、不进 `logcat`。
  提交前泄漏筛子的**口径**（本批钉死，别再拿宽筛子当绿）：从老板给的 key 文件里按"无空白、`[A-Za-z0-9_-.]`、长度 ≥32、
  尾 4 位与屏上 `JgYp` 对齐"筛出**那一条**（len=70），再拿它的**全文**与**前 8 字**两个 needle 扫全仓 309 个文件（排除 `.git/build/.gradle`）
  → 命中 0。宽筛子（≥20 字符通配）会在 `STATUS.md`/`METRICS.md`/`t2-key-adaptation.md` 里报出十几条 prose 假命中，
  那些不是凭据形态；**筛子命中必须逐条人工归因**（同 §E1-pre 那条"筛子不是判据"），否则宽筛子会把"其实没漏"报成"漏了"、把窄筛子的空判误当"已扫过"。
- 靶设备自报：`靶设备：M2012K11AC（模拟器=0…）`——本节所有结论是**真机**结论；
  [E1m] 那一节的结论只是模拟器结论，两边不互相冒领。

## §4 自纠清单（本批把"脚本写歪"和"产品缺陷"分开钉的四条 + 两条新的）

① **缺 INTERNET 权限使此前的"飞行模式零网络"绿部分是空判**。`AndroidManifest.xml` 从来没申请过 INTERNET
（S1 那句"BYOK 网络仅在 S3 引入"落到 S3 时没人执行），E5a 因此报 `Permission denied`。
补齐之后重拿的证据在 `evidence/S3/raw/flight-rerun-emu-internet.log`：装同源构建 + `INTERNET: granted=true`
+ `Active default network: none` 三条同时成立下 `device-smoke 13/13 ALL PASS`——这才是一次**非空判**的零网络自证。
只加 INTERNET 一条，其余权限原样；执行期零网络仍然由代码不发起请求来锁，不由缺权限来锁（缺权限的"安全"是假的）。

② **三条假红，全部是脚本前提写歪，不是产品读错**（同 U15c 族：判据没坏，前置没立）：
- E5e 取错 marker（先 grep `S2SMOKE-TASK`=录制路径，AI 路径不发这行）→ 改取 `S3SMOKE-TASK`（`RecorderStore.kt:214` 自己的取件口）；
- E9 用 `am kill` 想收走进程——常驻服务型 App（无障碍+悬浮窗+前台服务）**收不走**，K40 两轮 pid 都是同一个
  → 换 `adb install -r` 同构建（系统杀进程、留 /data 与 Keystore 别名，又不动无障碍条目）；
- E5c / E8d-e 押前提：E5c 拿"开关默认开"当前置（而 E10 上一跑把 `StateFlow` 留在了 off，默认开是**产品**的默认不是**脚本**的权限）
  → 注入行显式 `--es ctx_enabled on`；E8d/e 拿"整页扫遍读不到=屏上没有"当证据，而 Compose 折叠线以下不进无障碍树
  → 改成"单屏 dump 正向命中优先、未命中才回退整页、回退仍不成则 SKIP"，且不押时长。

③ **`input text` 整串下发会换序**（MIUI 实测相邻字符顺序错乱）→ 注入通道改为逐字符下发 + 逐字段读回比对；
读不回就重来一轮，不写"保存成功"。

④ **新设备事实（本批第一次记牢）**：自家 Compose 面板**折叠线以下的节点根本没组合**，因此不进无障碍树——
切片 D 之后 `task_input` 被 BYOK 面板推到线以下，`device-smoke` 的 C3 于是报 `L1 NO_MATCH 'task_input' 零命中`。
测试通道凡按 resource-id 取框，必须先滚到、再 dump 自证"框确实在树里"才动手（`scripts/device-smoke.sh` 的
`wait_own_task_input`），否则"没滚到"会被读成"屏上没有"。

⑤ **本轮新增的第 5 条（E9c 的左半边）**：`tail_before` 吃的是 E5 那次 `harvest_page 8` 留下的 PAGE_TMP 余货，
而切片 D 之后整页变高，8 屏扫不到凭据行 → 左半边为空，E9c 把"脚本没读到"判成"尾 4 位不吻合"。
模拟器 [E1m] 就是这条红。修法：kill 前**现读**（回主窗 + 整页 12 屏扫遍 + 最多两轮），
两边都走同一条取数通道；仍读不到就记 SKIP，不把账算到产品头上。[K4] 的 E9c 已按新口径真绿。

⑥ **本窗自己违反了"证据只追加"**（09-24，落盘前自查发现并已修复）：给
`evidence/S3/raw/byok-smoke-emulator-e1.log` 补 `[E1m]` 段时，我用**文本模式**读旧文件再写回——
旧文件里第 262 行是 GBK 字节，按 UTF-8 读时被换成 28 个 `U+FFFD` 才写回去，等于**把已归档的证据行改坏了**。
发现方式不是自觉，是提交前 `git diff --numstat` 显示 `72 insertions / 1 deletion`（"只追加"的改动不该有 deletion）。
修复：以 HEAD 的**原始字节**为前缀、只把我新增的段拼在其后，二进制重写；修后
`git diff --numstat` = `71 insertions / 0 deletions`、全文件 `U+FFFD` 计数 0、前 25600 字节与 HEAD 逐字节相同。
纪律回写：**往证据文件追加只能走二进制/追加模式**（`open(...,'ab')` 或先 `rb` 取尾再 `wb` 整体拼接），
文本模式读旧内容 = 拿别人的编码再编一次；提交前的 `--numstat` 里那条 `deletions` 就是这类事故的探针，本批之前没人看它。
同批另外两份 raw（`byok-smoke-k40-e1pre.log`、`flight-rerun-emu-internet.log`）已用同一筛子量过：`U+FFFD` 计数 0，未受害。
（本条自身也是"假绿更贵"的反面教材：如果我只看"文件还在、新段还在"就报收工，这 28 处坏字节会永久留在已验收的证据里。）

## §5 顺着假红挖出来的**真缺陷**（待老板一句话，本窗未擅自改任一侧）

现象：模型对同一句意图可能交出 `{"type":"key","value":{"key":"back"}}`。
- `:byok` 的词表**明确授权** key：`DslCompiler.kt:50`（提示词只允许 click/type_text/scroll/**key**）、
  `:54`（`key` 用 `back|home|enter`）、`:132`（值域校验）、`:150` `ALLOWED_TYPES = {CLICK, TYPE_TEXT, SCROLL, KEY}`；
  契约也留着扩展位 `Constants.kt:27 ActionType.KEY`。
- 执行器只走 `WAIT` + `CLICK/SCROLL/TYPE_TEXT`（`NodeTaskRunner.kt:98-109`），其余一律
  `unsupported_type:<type>` + `StopCode.EXECUTOR_ERROR` 停队（`:111-121`）。
  （行号自纠：本文件首版写的是 `:99-108`/`:113-121`，09-24 落笔后按磁盘逐行复核更正——引用错行号与写错事实同级。）
真机/模拟器各自的原文：模拟器 [E1m] `S1SMOKE ok=0 total=1 stopped=true stop="unsupported_type:key"`（编得出、跑不动）；
K40 [K4] 这一跑模型交的是 click，故 E5h `ok=1 total=1`。

所以：**编译词表与执行支持集从未对拍过**，这一条是间歇性的（模型给什么类型决定它露不露），
不是安全缺陷（失败是显式的、带回执的，不会静默点错东西），但是**产物质量缺陷**：
屏上"AI 给了 1 步"，点执行第一步就以执行器错误收尾。
本窗把它钉成了判据（新增 E5i：产物每一步都要在执行面支持集内，否则当场红），
改哪一侧留给一句话：
- (a) **收紧词表**：`:byok` 的提示词与 `ALLOWED_TYPES` 去掉 `key`，直到执行面支持它——改动小、方向是 fail-closed；
      代价是模型遇到"该按返回键"的意图只能改走点击，能力变窄。
- (b) **执行面补 KEY**：`performGlobalAction(BACK/HOME)` + `enter` 走 IME action——能力对得上，
      但这动的是回放热路径（31-B 同一段代码），按军令 §3 那句"待老板一句话"，且必须重跑模拟器全量 + 真机复验。
本窗倾向：先 (a) 立住"编出来的每一步都跑得动"，(b) 与 31-B 一起做。不擅自选，等一句话。

## §6 诚实边界（不洗）

1. **E11 清除双槽仍未跑**：判据要人手指点屏上「清除本机凭据」（注入通道故意不开这个口，也不模拟手指点自家面板），
   老板已点头 `E_WIPE=1`，但需要人在机器旁边那一下。本轮按 SKIP 记，不算 PASS 也不删断言。
2. **两条 JVM 覆盖=0 的原样在**：真 HTTPS 传输（`HttpURLConnection` 那段设备缝）、真 Keystore 别名读写
   （`AndroidKeyVault.create()`）——只有设备证据，没有单测冒充。
   `GcmBlobCipher` 要说准：它的**blob 格式与拒绝路径** JVM 已锁（`GcmBlobCipherTest`），
   但本批修的那条真缺陷——"密文 blob 自带 IV 会被 keystore2 拒（`CALLER_NONCE is not present`）"——
   **只在真机暴露**，JVM 那条测试换的是软件 `SecretKeySpec`，看不见密钥库的 nonce 策略。
   JVM 账：切片 E 结束时 332（app 254/byok 59/contracts 19），产品代码两处改动没动判据。
3. **模拟器 ≠ 真机**：[E1m] 那一节只证明"脚本口径与假红归因"，四点里真机证据全在 [K4]。
4. **E5i 是间歇红**：模型输出非确定性，同一句意图两轮交出不同类型。这不是脚本坏，是缺陷本身就有偶发面目——
   偶发不是不存在的豁免，故留在脚本里。
5. **E1n 的"空闲档"读数带旧痕**：常驻进程 `am kill` 收不走 → 脚本已自报"按'可能带旧痕'读，已如实标注"，
   本轮该档没有据此判任何产品结论。
6. 切片 E 的编译产物只做"落账 + 建议流 + 按框执行"，**没有任何一步执行期网络**：
   E5h 那一跑与 device-smoke 13/13 都是在 `Active default network: none` 或纯本机链路上取的。

## §7 复跑命令（谁都能独立重跑）

```bash
# 0) 出与门禁同源的那枚包（工作树 D:/Anytouch-e1w 已 commit 状态时）
cd /d/Anytouch-e1w && ./gradlew :app:assembleDebug
# 1) 代填凭据（S3-R5）：屏上只出现尾 4 位
cd /d/Anytouch && ANDROID_SERIAL=7ae4bfee bash scripts/byok-credential-inject.sh --key-file <key 文件路径>
# 2) 真机最终轮（E9 需要同一枚 APK 才谈得上"跨进程"）
ANDROID_SERIAL=7ae4bfee E9_APK="D:/Anytouch-e1w/app/build/outputs/apk/debug/app-debug.apk" \
  bash scripts/byok-smoke.sh
# 3) 只有老板在场且同意真删凭据时才加：E_WIPE=1（会真清双槽，之后需重填一次）
```
