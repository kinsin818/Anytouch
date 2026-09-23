# S3 切片 A · `:byok` 模块与红线重划（09-23 结案）

军令：`orders/ANYTOUCH-S3-byok-ORDER.md` §1-A / §2。老板四裁之 1 落地：**网络代码单独成模块，旧红线 A/C 字面不动**。

## 1. 搬迁账（"一份编译器两处用"）

| 项 | 事实 |
|---|---|
| 移动 | `tools/compiler/src/{main,test}/.../DslCompiler{,Test}.kt` → `byok/src/{main,test}/kotlin/com/anytouch/byok/`，用 `git mv`（历史可追） |
| 改动量 | `git diff -M --stat`：DslCompiler.kt **12 行**（package 1 行 + import 段 + KDoc），DslCompilerTest.kt **2 行**（package）。**判据主体（`compile`/`validate` 12 项/`extractJsonArray`）零改动** |
| 反向依赖 | `:tools:compiler` 改为 `implementation(project(":byok"))`；`NvidiaNimTransport`/`ProbeMain` 只加 import，host 探针行为不变 |
| app 侧 | **本切片未加 `:app` → `:byok` 依赖**（无消费者不预铺；切片 D 接线时一并加） |
| 新模块 | `byok/build.gradle.kts`：纯 JVM（kotlin jvm + serialization + `api(:core:contracts)`），零 android 依赖、零新增第三方库 |
| 为什么设备侧不能复用现有传输 | `NvidiaNimTransport` 用 `java.net.http.HttpClient`（JDK 11+），**Android 平台不提供该类** → 切片 B 另写 `HttpURLConnection` 版传输，host 版原样保留 |

## 2. 验证账（真数）

- `:byok:test --rerun-tasks`：**12 例 / 0 失败**（`byok/build/test-results/test/TEST-com.anytouch.byok.DslCompilerTest.xml`，`tests="12" failures="0" errors="0"`）
- `:tools:compiler:test`：NO-SOURCE（12 例已迁走，**总数不重复计**）
- `:app:testDebugUnitTest`：180 例 / 0 失败（未受影响）
- `:core:contracts:test`：19 例 / 0 失败
- `scripts/ci-local.sh`：**PASS**，红线 **A–H 八条全清**（构建四步含 `:byok:build`）
- JVM 总数口径变更备案：自本切片起模块数由 3 变 4（app 180 / contracts 19 / **byok 12** / compiler 0），
  总数仍 **211**——**搬迁不是新增**，METRICS 记 JVM 数时必须按四模块分列，防止"看着多了一个模块"误读为"多了 12 例"。

## 3. 三条新红线的反面锁（能FAIL才算锁）

方法：在仓库里放**只含注释的探针文件**（不含可编译代码，避免污染构建），跑 `ci-local.sh` 第 [3/4] 段原文
（`sed -n '22,$p' scripts/ci-local.sh` 抽出后直接执行，**不复制判据**），逐条验证后删除探针。

| 红线 | 探针位置与内容（注释行） | 结果 |
|---|---|---|
| F 网络代码只住 byok/+tools/ | `probe/Fprobe.kt`：`// …java.net.HttpURLConnection 出现在网络白名单外` | `REDLINE-F HIT` + **rc=1** |
| G 执行路径看不见编译器 | `app/src/main/kotlin/com/anytouch/app/executor/Gprobe.kt`：`// import com.anytouch.byok.DslCompiler` | `REDLINE-G HIT` + **rc=1** |
| H Key 不进日志 | `byok/src/main/kotlin/com/anytouch/byok/Hprobe.kt`：`// println("Bearer " + apiKey)` | `REDLINE-H HIT` + **rc=1** |
| 三条合删后 | 无探针 | `ci-local PASS`（rc=0），`git status --short` 无探针残留 |

**探针踩坑一笔（如实记）**：第一轮循环把"每轮删掉另外两个探针"写成了顺序删除，结果 F 轮先把 G/H 探针删了，
G/H 两轮**根本没有文件可量**却报 clean——这是"读数器没在读自家窗"的同型错误（0 不等于通过）。
第二轮逐个重建探针才拿到真 HIT。**律：反面锁必须逐条独立放探针、独立看 rc，不许一条循环带三条。**

## 4. 本切片未做（别当已做）

1. 设备侧 HTTP 传输（切片 B）；Key 存储与脱敏（B）；屏上下文（C）；APP 接线与 UI（D）。
2. **飞行模式 device-smoke 对拍未跑**——"执行期零网络"目前只有结构锁（F/G），行为证据留切片 E 同批出。
3. `:app` 尚不能调用 `:byok`（依赖未加），故 BYOK 端到端**今天完全不可用**，切片 A 只是地基。
