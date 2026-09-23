# S3 切片 B · Key 面（设备侧传输 / 脱敏 / 失败分档 / Keystore 持久化）

日期：2026-09-23　主窗：Qoder（施工总窗口）　构建基线：本文件随批次一起 commit（HEAD 见 §6）
军令依据：`orders/ANYTOUCH-S3-byok-ORDER.md` §1-B、§3-3/3-4/3-6、§4-5

## §1 这片在整体里的位置

切片 A 只立了地基（`:byok` 模块 + 红线 F/G）。B 补的是**"手机里第一次联网"之前必须先有的四道护栏**：
地址政策（往哪儿发）、脱敏（Key 不许出现在任何文本里）、失败分档（八种失败各说各话）、
Keystore 持久化（Key 怎么活过一次重启而不以明文落盘）。
**B 结束时 BYOK 端到端仍然完全不可用**——app 还没依赖 `:byok`，UI 还没有输入框，那都是 C/D 的活。
这一点先说死，免得"模块建了、测试绿了"被读成功能通了。

## §2 落地面

`:byok`（android-free，JVM 可锁）
| 文件 | 职责 |
|---|---|
| `ByokError.kt` | `ByokErrorKind` 八档 + 各档独立话术；`TransportFailure`（带类型的失败，`safeDetail` 只放机器事实）；`httpKindOf(status)` 纯映射 |
| `BaseUrlPolicy.kt` | `check(raw): Outcome`——https-only、拒 userinfo/query/fragment、拒本机与内网/云元数据段（127/10/192.168/172.16-31/169.254/100.64-127、localhost、::1、0.0.0.0、`[::ffff:127.0.0.1]`）；只回显主机名；`uploadNotice()` 就是判据 4 要的那句"Key 只发往…" |
| `KeyMasker.kt` | `mask(text, vararg secrets)` 认字面量 + 前缀（nvapi/sk/ghp/AIza…）+ 凭据键值对 + `Bearer` 头 + ≥16 位 base64url 长串；`maskForDisplay` 只给尾 4 位，短于 8 位整段打码 |
| `OpenAiCompatTransport.kt` | `HttpURLConnection`（Android 无 `java.net.http`）；连接/读超时、**不跟随重定向**、Key 只进 `Authorization` 头；失败细节先过 `KeyMasker` 再截断 200；`connect(url)` 留成 `protected open` 给单测注入假连接 |
| `DslCompiler.kt` | `CompileResult.Reject` 多一个 `kind: ByokErrorKind?`——UI 按档选话术，不拿 `detail` 文本做分支；`validate` 内所有拒绝统一走 `reject(detail, kind, stage)` |

`:app`（`compile/` 包，执行路径之外，红线 G 允许它看 byok）
| 文件 | 职责 |
|---|---|
| `GcmBlobCipher.kt` | `StoredSecret`（非 data class + `Ok` 同样封了 toString）、`VaultCodec`（长度前缀字段编码，截断/畸形一律拒）、`GcmBlobCipher`（AES/GCM，IV 随机，四档失败 `NO_DATA/BAD_FORMAT/TAMPERED/UNWRAP_FAILED`） |
| `CredentialRepository.kt` | 保存/读回/加载/清除的全部判定（零 Android）：`save` **必须走一遍 `load` 并逐字段比对**才敢报 `Saved` |
| `VaultWipe.kt` | `WipeSlot`/`wipeSlots`：清除的结论只认"再查一次还在不在"，单边成功不算，话术点名残留槽 |
| `AndroidKeyVault.kt` | 唯一带 Android 的文件：Keystore 别名密钥 + 私有目录密文文件 + 别名槽位适配器（`create(context): CredentialRepository`） |

`:tools:compiler`：`NvidiaNimTransport` 的 `nvapi-***` 自建正则删掉，改调 `com.anytouch.byok.KeyMasker`——
两份脱敏只留一份（判据 2 同族纪律）。

`scripts/ci-local.sh`：新增**红线 I**（禁 `getSharedPreferences`/`getExternalFilesDir`/`getExternalStorageDirectory`/
`MODE_WORLD_READABLE`/`externalCacheDir` 进 `app/src/main`）；红线 H 正则修正；加 `SKIP_GRADLE=1` 只跑红线段。
`scripts/redline-probe.sh`（新）：反面锁**可重跑**化——逐条放探针、独立看 rc 与命中行、撤探针后确认门禁回到 PASS。

## §3 判据 → 用例（新增 51 例，全部真实判据）

| 军令 | 锁在哪些用例 |
|---|---|
| §3-3 Key 生命周期 | `CredentialRepositoryTest` 10 例：落盘字节找不到明文 Key 与完整地址；写返回 false→`WRITE_FAILED` 且零残留；写"成功"但读不回→`READ_BACK_NOTHING`（**不许报已保存**）；读回是旧那份→`READ_BACK_MISMATCH`；清除后读不回；别名擦不掉→`cleared=false` 且点名；"从没配过"与"配过但坏了"不同形；换密钥解不开；重复保存以最后一次为准。+ `VaultWipeTest` 4 例 + `GcmBlobCipherTest` 8 例（随机 IV、篡改=非乱码、四档话术互不雷同、两条 toString 泄露路径堵死） |
| §3-4 端点政策 | `BaseUrlPolicyTest` 9 例：拒项逐条（http/缺协议/userinfo/query/fragment/localhost/127/10/192.168/172.16/169.254/::1/`[::ffff:127.0.0.1]`/0.0.0.0/空/畸形）、公网 IP 与 172.15、100.128 不误伤、九档话术互不雷同、拒因不回显内嵌凭据、`uploadNotice` 只含主机名 |
| §3-6 失败必显八档 | `ByokErrorTest` 7 例：八档齐全且话术互不雷同、话术不含 `nvapi`/`Bearer`/`://`、状态码分档（含 3xx→坏应答的取舍）、`TransportFailure.message == safeDetail` 且不藏请求内容、编译器各拒都带机器可读档、传输带类型失败原样透传、非受控异常兜底 `UNREACHABLE` |
| §3-1 模型输出不直接进执行器（不回归） | `DslCompilerTest` 12 例原样绿（搬迁后判据未动） |
| 脱敏 | `KeyMaskerTest` 6 例：给了字面量也要消失、没给字面量靠形态认（各家前缀）、JSON 键值对、`Bearer` 头、普通文本不误伤而 ≥16 位长串必吞、展示只给尾 4 位 |
| 传输层 | `OpenAiCompatTransportTest` 7 例：端点不双拼、请求体无 Key、Key 只出现在头里、显式 `disconnect`、不跟随跳转、五档状态码分档 + 细节脱敏截断 ≤200、五类 IO 异常分档、鉴权门户 HTML/缺字段归 `BAD_RESPONSE`、**成功正文不脱敏**（打码会破坏待编译的 Action JSON） |

## §4 过程中抓到的自家缺陷（都是反面锁挖出来的，不是猜的）

1. **红线 H 原来是假锁**：正则写作 `(Log\.[vdiwe] |println)`，要求 `Log.d` 后面跟空格——`Log.d("x", ...)` 根本匹配不上。
   放 `Log.d(... apiKey ...)` 探针时它没响，改成 `Log\.[vdiwe]\(` 并补 `ApiKey/authHeader` 才真能 FAIL。
2. **探针脚本自己的顺序 bug**：`cleanup` 只删 `probe_tmp` 和一个不存在的 `probe/` 目录，G/H/I 三条探针（落在 `app/src/main` 下）
   在"撤探针后应回 PASS"的复核时还在原地，于是三轮全报 `CLEAN-FAIL`。**这条自检本身就是这套反面锁的价值**——
   切片 A 那次是"循环里删掉别人的探针→假绿"，这次是"没删干净→假红"，两个方向都会被同一套脚本逮住。
3. **假件比生产代码更能骗人**：`FakeStore.read()` 写成 `readOverride?.invoke() ?: data`，
   于是"读回 null"被 elvis 吞掉退回真数据，`save` 判成 `Saved`——一条本该锁死"无落字不判成"的用例假绿。
   改成两个互斥开关（`nullOnRead` / `staleBytes`）后同一份生产代码立刻报 `READ_BACK_NOTHING`。生产代码没错，错在假件兜底。

## §5 数字与命令（真账，单口径：`testDebugUnitTest` + `:byok:test` + `:core:contracts:test` 的 `<testsuite tests>` 求和）

```
2026-09-23 18:21:04  ./scripts/ci-local.sh      → CI_RC=0（build + 四模块测试 + 红线 A–I 全清 PASS）
2026-09-23 18:21:38  bash scripts/redline-probe.sh → PROBE_RC=0（F/G/H/I 逐条能 FAIL，撤后回 PASS）
```
| 模块 | 片前 | 片后 | 差 |
|---|---|---|---|
| `:app` | 180 | **202** | +22 |
| `:byok` | 12 | **41** | +29 |
| `:core:contracts` | 19 | 19 | 0 |
| `:tools:compiler` | 0（NO-SOURCE） | 0 | 0 |
| **合计** | 211 | **262** | **+51** |
原始日志：`evidence/S3/raw/ci-local-and-probe-s3b.log`（含两把命令全文与时间戳）。
设备侧本批**未跑**：B 没有任何需要 adb 的判据，`AndroidKeyVault`/`HttpURLConnection` 的真行为留给 E（见 §6）。

## §6 诚实边界与偏差（照 §4 的口径先备案，不事后洗）

1. **"尾 4 位"规则实现在 `:byok` 的 `KeyMasker.maskForDisplay`，不在 app**：军令 §1-B 字面把这条写在 `AndroidKeyVault` 名下。
   挪两份实现违背判据 2，所以保持一份；代价是 app 侧要等切片 D 加了 `:app → :byok` 依赖才能调它。
   本片不预铺那条依赖（无消费者不接线），故 D 之前 UI 上还没有掩码显示。
2. **`AndroidKeyVault.create()` 与真 HTTPS 请求在本批 0 例覆盖**：Keystore 别名、`filesDir` 写入、TLS 握手都只能在设备/真端点上证。
   JVM 侧锁的是**判定**（同一个 `CredentialRepository`，只是把两个适配器换成假件）。设备证据（含"清除后重开 app 读不回"）留 E。
3. **明文 Key 在内存里是 `String`，Android 上无法 zeroize**（不可销毁、可能被 dump）。本批只堵落盘与打印两条路，不假装解决内存驻留。
4. **不跟随 3xx 是主窗取舍**：跟跳转等于让服务端把一个 https 配置点导向任意地址；代价是个别"根域跳转"的自建网关会报 `BAD_RESPONSE`。
   若老板要兼容这类端点，一句话即改（改的是 `httpKindOf` 一处）。
5. **`KeyMasker.GENERIC_TOKEN`（≥16 位长串）会误伤正常文本**：只用于失败路径的细节，成功路径的模型输出**绝不脱敏**
   （§3 有一条用例专门锁这个：打码会破坏待编译的 JSON）。
6. **零新增第三方依赖**（§4-5 原样）：HTTP 用平台自带 `HttpURLConnection`，JSON 复用既有 kotlinx.serialization。

## §7 下一步（C）

`ScreenContextBuilder` 纯函数（可编辑节点只上行"存在"这一事实、密码类整条剔除、封顶 40、开关关闭时**零条**上行）
+ app 侧从自家无障碍服务取数；开关状态与实际条数上屏。B 的 `uploadNotice` 话术在 C 之后才敢补"本次上行 N 条"。
