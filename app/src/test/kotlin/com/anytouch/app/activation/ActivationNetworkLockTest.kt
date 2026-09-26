package com.anytouch.app.activation

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * S5-g 判据 9 的结构锁（附页 §5 第 9 条：把"激活联网代码只住 `:byok`"与
 * "`platform/` 与执行路径 import 不到 byok"从脚本约定升级为 JVM 用例）。
 *
 * 与 `scripts/ci-local.sh` 红线 C/F/G 的关系是**同源机器化**，不是复制粘贴第二套口径：
 * 脚本那三条扫的是整个 app 模块的关键字，这里额外钉本批新长出来的**缝**——
 * 一座桥（`compile/ActivationChannel.kt`）、一条写盘口（`Allowed` 才能落盘）、
 * 一个顺序（本地判据在服务器结论之前）。这三样任何一样被人挪走，
 * "服务器说不算数 / 设备自己给自己解锁"就成了可能，而脚本那三条一条都不会红。
 *
 * 本文件只读源码文本，不碰设备、不联网。
 */
class ActivationNetworkLockTest {

    private val appMain = File("src/main/kotlin/com/anytouch/app")

    private fun appSources(): List<File> =
        appMain.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    private fun rel(file: File): String = file.relativeTo(appMain).path.replace('\\', '/')

    /**
     * 1. app 模块内一个字节的网络代码都没有：URL 字面量与 net/ssl 类型名统统不许出现。
     *
     * 字面量在这里必须拆开写：红线 F 是对**整个仓库**（除 byok/tools）的 grep，
     * 一份直书这些词的测试文件自己就是它的第一号命中——那既不是本批要钉的东西，
     * 也会让"改测试去迁就红线"的诱惑成真。拆开的是写法，不是判据强度。
     */
    @Test
    fun `激活联网实现一个字都不在 app 模块里`() {
        val patterns = listOf(
            "java" + ".net",
            "javax" + ".net",
            "Http" + "URL",
            "URL" + "Connection",
            "ok" + "http",
            "Http" + "Client",
            "Sock" + "et(",
            "https:" + "//",
            "http:" + "//",
        )
        val offenders = appSources().flatMap { file ->
            val hits = file.readText().let { text -> patterns.filter(text::contains) }
            hits.map { rel(file) to it }
        }
        assertTrue(
            offenders.isEmpty(),
            "app 模块里出现了网络字样（红线 C/F 的 JVM 同源锁）：" +
                offenders.joinToString { "${it.first} → ${it.second}" },
        )
    }

    /** 2. 桥只有一座：`:byok` 的激活口在 app 侧唯一的调用点。 */
    @Test
    fun `byok 激活口只经一座桥 执行路径与安全面看不见它`() {
        val callers = appSources()
            .filter { it.readText().contains("ActivationTransport") }
            .map(::rel)
        assertEquals(listOf("compile/ActivationChannel.kt"), callers, "第二座桥=第二份真值，且它可能在任何位置")

        // 红线 G 名单逐目录点验（脚本按目录 grep，这里按同一份目录名单再钉一次）
        val bannedDirs = listOf("executor", "locator", "service", "safety", "platform", "recorder")
        val leaks = bannedDirs.flatMap { dir ->
            File(appMain, dir).walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .filter { file ->
                    val text = file.readText()
                    text.contains("com.anytouch.byok") || text.contains("ActivationTransport") ||
                        text.contains("ActivationRemote")
                }
                .map(::rel)
        }
        assertTrue(leaks.isEmpty(), "执行路径/安全面/平台层看见了激活联网口：" + leaks.joinToString())
    }

    /** 3. 服务器结论只有一个来源：`Allowed` 只能由桥构造，本地代码伪造不出一枚。 */
    @Test
    fun `服务器放行态没有第二个构造点`() {
        val constructors = appSources()
            .filter { it.readText().contains("ActivationRemote.Allowed(") }
            .map(::rel)
        assertEquals(listOf("compile/ActivationChannel.kt"), constructors, "谁能在本地 new 出 Allowed，谁就能离线给自己解锁")
    }

    /** 4. 顺序与写盘口：本地判据在前、只有 Allowed 落盘（判据 3 的机器锁）。 */
    @Test
    fun `落盘只跟在服务器放行之后 且本地判据排在前面`() {
        val store = File(appMain, "activation/ActivationStore.kt").readText()
        val body = store.substringAfter("fun submit(")
        assertTrue(
            body.indexOf("ActivationCode.classify") < body.indexOf("when (remote)"),
            "本地格式判据排到了服务器结论之后：脏码可以带着 Allowed 抢跑",
        )
        val writeSite = body.lineSequence().filter { it.contains("disk.write") }.toList()
        assertEquals(1, writeSite.size, "落盘口不止一处")
        val allowedBranch = body.substringAfter("is ActivationRemote.Allowed ->")
            .substringBefore("ActivationRemote.Invalid")
        assertTrue(
            allowedBranch.contains("disk.write"),
            "唯一的落盘口不在 Allowed 分支里：说明有别的路径能解锁",
        )
        // 写后复核不许被顺手删掉（v1.0.5 那条读回比对）
        assertTrue(allowedBranch.contains("disk.read()"), "Allowed 分支丢了写回复核：写失败会被当成解锁成功")
    }

    /** 5. fail-closed 的映射不许合并档：四档服务器结论各落各的拒因，UNREACHABLE 不得塌成 INVALID。 */
    @Test
    fun `四档服务器结论各走各的话术 不可达不冒充码无效`() {
        val gate = File(appMain, "activation/ActivationGate.kt").readText()
        listOf(
            ActivationVerdict.SERVER_INVALID,
            ActivationVerdict.SERVER_SEATS_FULL,
            ActivationVerdict.SERVER_UNREACHABLE,
            ActivationVerdict.DEVICE_ID_MISSING,
        ).forEach { verdict ->
            assertTrue(gate.contains(verdict.name), "$verdict 没有自己的话术分支")
        }
        val copy = ActivationVerdict.values().map(ActivationCopy::refusal)
        assertEquals(copy.size, copy.toSet().size, "两档共用一句话：买家分不清该找谁")
        val unreachable = ActivationCopy.refusal(ActivationVerdict.SERVER_UNREACHABLE)
        val invalid = ActivationCopy.refusal(ActivationVerdict.SERVER_INVALID)
        assertTrue(
            !unreachable.contains("invalid") && !unreachable.contains("Activation code invalid"),
            "不可达被说成了码无效：fail-closed 会被误读成退单理由",
        )
        assertTrue(invalid.contains("Nothing was unlocked"), "拒因必须声明什么都没发生")
        // 异步那一格也得有话术（没有它就是八秒的"点了没反应"）
        assertTrue(ActivationCopy.CHECKING.isNotBlank())
    }

    /** 6. 完整码不上屏、不进日志：屏上只准尾四位（判据 7 的 JVM 半边）。 */
    @Test
    fun `日志与文案里不许出现整码或设备标识`() {
        val offenders = appSources().flatMap { file ->
            val hits = file.readLines().mapIndexedNotNull { n, line ->
                val isLog = line.contains("Log.") || line.contains("println(")
                val leaksWhole = isLog && (line.contains("rawCode") || line.contains("androidId"))
                if (leaksWhole) "${rel(file)}:${n + 1} ${line.trim()}" else null
            }
            hits
        }
        assertTrue(offenders.isEmpty(), "日志语句里送了整码或原始设备标识：" + offenders.joinToString(" | "))
    }
}
