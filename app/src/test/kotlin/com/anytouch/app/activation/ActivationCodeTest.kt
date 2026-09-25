package com.anytouch.app.activation

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * 激活码判据层的 JVM 锁（S5-f 军令 §2 + 老板裁 2/裁 3；口径见 `orders/ANYTOUCH-S5f-activation-code-APPENDIX.md` §1）。
 *
 * 为什么这一格必须锁在 JVM：设备面只能证明"某一枚码放行了"，证明不了**六档拒因各自独立成立**
 * （那要在屏前一枚一枚试脏码、还要证明它们不是同一句话）。判据住纯函数，脏码在桌面上就能逐档对拍，
 * 设备面因此只需验接线（`scripts/s5f-activation-smoke.sh` 那一格）。
 *
 * golden 表与 `scripts/activation-code.py`（发卡工具）**同源同表**：两边逐字断言同一批向量，
 * 漂出第二份口径会以红测暴露，而不是以"老板手上一枚码在 App 里不认"暴露。
 */
class ActivationCodeTest {

    /** 附录 §1 那张表，逐字照抄（自由正文 → 完整码）。 */
    private val golden = listOf(
        "A1B2C3D4E5" to "ANY-A1B2-C3D4-E5NX",
        "0000000000" to "ANY-0000-0000-00WW",
        "ZZZZZZZZZZ" to "ANY-ZZZZ-ZZZZ-ZZMM",
        "9A8B7C6D5E" to "ANY-9A8B-7C6D-5EFN",
        "PRO9FAN8B1" to "ANY-PRO9-FAN8-B1CE",
    )

    // ---- 1. 长度口径（军令"19 位"与格式串自相矛盾，老板裁 2 定死以格式串为准） ----

    @Test
    fun `完整码恰为 18 位 十九位的码当场被拒`() {
        assertEquals(18, ActivationCode.CANONICAL_LENGTH, "裁 2：长度以格式串 ANY-XXXX-XXXX-XXXX 为准=18")
        assertEquals(18, golden[0].second.length, "golden 表本身对不上长度常量：表和判据有一份是错的")
        // 军令那句"总长度 19 位"不是一枚可通行的码：多一位必须落在 BAD_LENGTH，而不是被顺手截掉
        assertEquals(ActivationVerdict.BAD_LENGTH, ActivationCode.classify("ANY-A1B2-C3D4-E5NXX"))
        assertEquals(ActivationVerdict.BAD_LENGTH, ActivationCode.classify("ANY-A1B2-C3D4-E5N"))
    }

    // ---- 2. golden 五枚：发卡侧与校验侧双向逐字对拍 ----

    @Test
    fun `golden 五枚双向对拍 issue 与 classify 同号同果`() {
        golden.forEach { (freeBody, code) ->
            assertEquals(code, ActivationCode.issue(freeBody), "$freeBody 的发卡结果与表不符")
            assertEquals(ActivationVerdict.UNLOCKED, ActivationCode.classify(code), "$code 应当放行")
            assertTrue(ActivationCode.isUnlocked(code))
        }
    }

    @Test
    fun `校验位锚点五格逐字钉死 换算法立刻红`() {
        // A→N、Z→M、0→W、9→F、1→X：这五格就是 `chr(ord(c) % 26 + 65)` 的手算凭据
        // 排版：index 16=b11（认 b1）、index 17=b12（认 b2）；这里让 b1 当变量、b2 恒为 'A'
        mapOf('A' to 'N', 'Z' to 'M', '0' to 'W', '9' to 'F', '1' to 'X').forEach { (source, expect) ->
            val code = ActivationCode.issue("${source}A".padEnd(ActivationCode.FREE_BODY_LENGTH, '1'))
            assertEquals(expect, code[16], "$source 作为 b1 的校验字母不是 $expect：模 26 那一条被改写了")
            assertEquals(ActivationVerdict.UNLOCKED, ActivationCode.classify(code))
            // 把这一位改掉必须当场认出（防手滑这一格的全部用途就在这条上）
            val alt = if (expect == 'N') 'O' else 'N'
            val broken = code.substring(0, 16) + alt + code.substring(17)
            assertEquals(ActivationVerdict.CHECKSUM, ActivationCode.classify(broken), "$source 的校验位被改却认了")
        }
    }

    @Test
    fun `b12 认 b2 不认 b1 两位各管各的`() {
        // 裁 3 的细则是"前两位逐位各取模"，不是"两位之和取模"：这一格把两种读法分开
        val code = ActivationCode.issue("AB22222222")
        assertEquals("ANY-AB22-2222-22NO", code, "b11 应认 A→N、b12 应认 B→O（求和读法会给出同一个字母两次）")
        assertEquals(ActivationVerdict.UNLOCKED, ActivationCode.classify(code))
    }

    // ---- 3. 六档拒因：每档独立成立，且排序不许反（先说的是更外层的那个错） ----

    @Test
    fun `空输入单独成档 与写了但写错分开`() {
        listOf("", "   ", "\t\n ").forEach {
            assertEquals(ActivationVerdict.EMPTY, ActivationCode.classify(it), "$it 该报『没填』而不是『填错』")
        }
    }

    @Test
    fun `前缀档排在长度档之前 AN 与 XNY 都算前缀错`() {
        assertEquals(ActivationVerdict.BAD_PREFIX, ActivationCode.classify("AN"))
        assertEquals(ActivationVerdict.BAD_PREFIX, ActivationCode.classify("XNY-A1B2-C3D4-E5NX"))
        // 整串连打（少了两枚连字符）是长度问题不是前缀问题——那句提示得让人去数位数
        assertEquals(ActivationVerdict.BAD_LENGTH, ActivationCode.classify("ANYA1B2C3D4E5NX"))
    }

    @Test
    fun `分隔符档排在字符集档之前 下划线串先报分隔符错`() {
        assertEquals(ActivationVerdict.BAD_SEPARATOR, ActivationCode.classify("ANY_A1B2_C3D4_E5NX"))
        assertEquals(ActivationVerdict.BAD_SEPARATOR, ActivationCode.classify("ANY.A1B2.C3D4.E5NX"))
        assertEquals(ActivationVerdict.BAD_CHARSET, ActivationCode.classify("ANY-A1B#-C3D4-E5NX"))
    }

    @Test
    fun `字符集档排在校验位档之前 脏字符不该被报成抄错`() {
        // 同一格里既脏又校验不对：先报字符集——那是更外层的原因（校验位只对"形状全对"的串有意义）
        assertEquals(ActivationVerdict.BAD_CHARSET, ActivationCode.classify("ANY-A1B#-C3D4-E5NY"))
        listOf('é', '·', '，', '中').forEach { dirty ->
            assertEquals(
                ActivationVerdict.BAD_CHARSET,
                ActivationCode.classify("ANY-A1B$dirty-C3D4-E5NX"),
                "$dirty 不是 A-Z0-9，必须落在字符集档",
            )
        }
        // 全角字母：长度仍 18（一个全角字符是一个 char），落到字符集档而不是长度档
        assertEquals(ActivationVerdict.BAD_CHARSET, ActivationCode.classify("ANY-Ａ1B2-C3D4-E5NX"))
    }

    @Test
    fun `六档拒因两两不同 每档各得一句自己的话术`() {
        val verdicts = listOf(
            ActivationVerdict.EMPTY,
            ActivationVerdict.BAD_PREFIX,
            ActivationVerdict.BAD_LENGTH,
            ActivationVerdict.BAD_SEPARATOR,
            ActivationVerdict.BAD_CHARSET,
            ActivationVerdict.CHECKSUM,
        )
        val copies = verdicts.map { ActivationCopy.refusal(it) }
        assertEquals(copies.size, copies.toSet().size, "两档共用一句话术：用户照提示改完还是错")
        // 判据与话术一一对应：每档都得由一枚真实脏码触发得到，而不只是枚举里存在
        val reached = listOf(
            "", "XNY-A1B2-C3D4-E5NX", "ANY-A1B2-C3D4-E5NXX",
            "ANY_A1B2_C3D4_E5NX", "ANY-A1B#-C3D4-E5NX", "ANY-A1B2-C3D4-E5NY",
        ).map { ActivationCode.classify(it) }
        assertEquals(verdicts, reached, "这一档没有脏码能触发：话术成了死代码")
    }

    @Test
    fun `校验位错单独成档 形状全对但末两位对不上`() {
        listOf("ANY-A1B2-C3D4-E5NY", "ANY-A1B2-C3D4-E5MX", "ANY-ZZZZ-ZZZZ-ZZNN").forEach {
            assertEquals(ActivationVerdict.CHECKSUM, ActivationCode.classify(it), "$it 形状对校验错")
        }
        // 自由位（b1..b10）随便改都照样放行：校验位不增加秘密性，只认那两位
        listOf("ZZZZZZZZZX", "AAAAAAAAAA", "9876543210").forEach {
            assertEquals(ActivationVerdict.UNLOCKED, ActivationCode.classify(ActivationCode.issue(it)))
        }
    }

    // ---- 4. 容错边界：只容大小写与空白，其余一律不猜 ----

    @Test
    fun `小写与粘贴空白被容 其余一个字符都不猜`() {
        val code = "ANY-A1B2-C3D4-E5NX"
        assertEquals(ActivationVerdict.UNLOCKED, ActivationCode.classify(code.lowercase()), "小写必须容（军令没说不容）")
        assertEquals(ActivationVerdict.UNLOCKED, ActivationCode.classify("  $code\n"))
        assertEquals(ActivationVerdict.UNLOCKED, ActivationCode.classify("ANY- A1B2 \t- C3D4-E5NX"), "粘贴带的内部空白要能吃掉")
        // 不容的那几种：每一种都是"猜一枚别的码"，猜错就是把另一枚码交给他
        assertNotEquals(ActivationVerdict.UNLOCKED, ActivationCode.classify(code.replace("-", "")))
        assertNotEquals(ActivationVerdict.UNLOCKED, ActivationCode.classify(code.replaceFirst("ANY", "any-")))
        assertNotEquals(ActivationVerdict.UNLOCKED, ActivationCode.classify("$code-"))
    }

    @Test
    fun `发卡口只收十位干净正文 脏输入当场抛而不是出一枚错码`() {
        assertFailsWith<IllegalArgumentException> { ActivationCode.issue("A1B2C3D4E") }
        assertFailsWith<IllegalArgumentException> { ActivationCode.issue("A1B2C3D4E5F") }
        assertFailsWith<IllegalArgumentException> { ActivationCode.issue("A1B2C3D4E#") }
        // 小写正文发卡时归一：出的码仍是规范式（发卡侧与校验侧共用同一份字符集口径）
        assertEquals("ANY-A1B2-C3D4-E5NX", ActivationCode.issue("a1b2c3d4e5"))
    }

    // ---- 5. 回显面：屏与日志只许出现末四位 ----

    @Test
    fun `回显只取末四位 全码的任何一片都不许上屏`() {
        val code = "ANY-PRO9-FAN8-B1CE"
        assertEquals("B1CE", ActivationCode.tailForDisplay(code))
        assertEquals("B1CE", ActivationCode.tailForDisplay("  ${code.lowercase()} "), "归一化与校验同一条口")
        val tail = ActivationCode.tailForDisplay(code)
        assertFalse(tail.contains("ANY"), "前缀进了回显=回显里还有可拼回的形状")
        assertEquals("…-B1CE", maskedTail(tail))
        assertEquals("-", maskedTail(""), "没激活时回显是个占位符，不是半枚码")
    }

    // ---- 6. 结构锁：判据层零 IO 零网络零界面 ----

    /** 只留代码行：文件头的中文注释本来就要写明"禁了什么"，扫注释会自己咬自己。 */
    private fun codeOnly(text: String): String = text.lines()
        .filterNot { it.trim().startsWith("*") || it.trim().startsWith("//") || it.trim().startsWith("/*") }
        .joinToString("\n")

    @Test
    fun `判据与话术两层零 IO 零网络 磁盘只准住适配器那一格`() {
        val files = listOf(
            "activation/ActivationCode.kt",
            "activation/ActivationGate.kt",
            "activation/ActivationStore.kt",
        ).map { it to File("src/main/kotlin/com/anytouch/app", it).readText() }
        // 禁词按本仓既有的分工挑：零网络那一半的唯一真源是红线 F（scripts/ci-local.sh），这里补它管不到的那些。
        // 其中网络字面**拆开写**：红线 F 扫的是全仓 *.kt（不认"这是清单不是调用"），清单原样落字就会被它自己对标的
        // 那把闸判红——与 KDoc 那次同族。拼接在运行时完成，判据一个字不松。
        val banned = listOf(
            "import android", "getSharedPreferences", "getExternalFilesDir", "externalCacheDir",
            "java" + "." + "net", "Http" + "URL" + "Connection",
            "Socket", "Log.", "Context", "Compose",
        )
        files.forEach { (path, raw) ->
            val text = codeOnly(raw)
            banned.forEach { marker ->
                assertFalse(text.contains(marker), "$path 的代码行里出现了 $marker：判据层一起床就不是纯函数了")
            }
        }
        // 激活面唯一能落盘的那一格：只准 filesDir，且必须写后读回
        val disk = codeOnly(File("src/main/kotlin/com/anytouch/app/platform/AndroidActivationDisk.kt").readText())
        assertTrue(disk.contains("filesDir"), "激活标志不落 filesDir 就是往红线 I 上撞")
        assertFalse(disk.contains("getSharedPreferences"), "裁 4 走的是既有私有件通道：红线 I 一个字都不动")
        assertTrue(disk.contains("renameTo") && disk.contains("readText() == text"), "临时件改名 + 写后读回是这条通道的本体")
    }
}
