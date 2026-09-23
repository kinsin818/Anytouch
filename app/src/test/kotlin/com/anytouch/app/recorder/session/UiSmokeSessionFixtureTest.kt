package com.anytouch.app.recorder.session

import com.anytouch.app.recorder.RecorderCompiler
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ui-smoke 预置会话档的出处锁。
 * 这份 JSON 不是手写的：由冻结的 [RecorderSession.serialize] 真产出来（脚手架测试打印后逐字搬入）。
 * 之所以两处各存一份字面量（本测试 + `scripts/ui-smoke.sh`），是因为设备侧脚本不能依赖 JVM 类路径；
 * 漂移风险用 [预置档与脚本内字面量逐字一致] 这条锁住——脚本里的档一旦与冻结 codec 口径脱节，
 * 先在这里红，而不是到设备上表现成一串无法归因的假红。
 */
class UiSmokeSessionFixtureTest {

    @Test
    fun `预置档可被冻结读档口受理且编译出三步`() {
        val restore = RecorderSession.deserialize(FIXTURE)
        val loaded = restore as? SessionRestore.Loaded
            ?: error("读档失败：${(restore as SessionRestore.Failed).detail}")
        assertEquals(SessionState.STOPPED, loaded.session.state, "注入档必须是只读停动态")
        assertEquals("com.android.settings", loaded.session.targetPkg)
        assertEquals(4, loaded.session.eventList().size, "1 条开窗 + 3 条点击，一条不多一条不少")
        val actions = RecorderCompiler().compile(loaded.session.eventList(), loaded.session.targetPkg).actions
        assertEquals(3, actions.size, "三步链编译不得丢步（丢了就不是 ui-smoke 期望的那份账）")
        assertEquals(
            listOf("Connected devices", "Connection preferences", "Bluetooth"),
            actions.map { it.value?.toString().firstTextClue() },
        )
        // 再序列化必须逐字回原档：读写两侧同口径，脚本里那份字面量才是"冻结 codec 真产物"而非手抄近似
        assertEquals(FIXTURE, loaded.session.serialize(), "读档后再序列化与入档不一致——codec 往返有损")
    }

    @Test
    fun `预置档与脚本内字面量逐字一致`() {
        val script = repoFile("scripts/ui-smoke.sh")
        assertTrue(
            script.contains(FIXTURE),
            "脚本里的预置会话档与本测试持有的冻结产物不一致——改一处必须同时改另一处",
        )
    }

    private fun String?.firstTextClue(): String? =
        this?.let { Regex("\"text\":\"([^\"]*)\"").find(it)?.groupValues?.get(1) }

    /** 从测试工作目录向上找仓库根（Gradle 默认 cwd=模块目录，跨环境不赌死路径）。 */
    private fun repoFile(relative: String): String {
        var dir: java.io.File? = java.io.File(System.getProperty("user.dir"))
        while (dir != null) {
            val candidate = java.io.File(dir, relative)
            if (candidate.isFile) return candidate.readText()
            dir = dir.parentFile
        }
        error("找不到 $relative（测试工作目录 ${System.getProperty("user.dir")} 不在仓库内？）")
    }

    companion object {
        const val FIXTURE =
            "{\"format\":\"anytouch.recorder.session\",\"version\":1,\"targetPkg\":\"com.android.settings\"," +
                "\"state\":\"STOPPED\",\"overflowCount\":0,\"events\":[" +
                "{\"type\":\"window\",\"pkg\":\"com.android.settings\",\"windowTitle\":\"session-open\",\"timestampMs\":1700000001000}," +
                "{\"type\":\"action\",\"kind\":\"CLICK\",\"text\":null,\"confirmed\":true,\"timestampMs\":1700000002000," +
                "\"snapshot\":{\"resourceId\":null,\"text\":\"Connected devices\",\"contentDesc\":null," +
                "\"className\":\"android.widget.TextView\",\"pkg\":\"com.android.settings\",\"indexPath\":[0,1,2]}}," +
                "{\"type\":\"action\",\"kind\":\"CLICK\",\"text\":null,\"confirmed\":true,\"timestampMs\":1700000003000," +
                "\"snapshot\":{\"resourceId\":null,\"text\":\"Connection preferences\",\"contentDesc\":null," +
                "\"className\":\"android.widget.TextView\",\"pkg\":\"com.android.settings\",\"indexPath\":[0,1,2,0]}}," +
                "{\"type\":\"action\",\"kind\":\"CLICK\",\"text\":null,\"confirmed\":true,\"timestampMs\":1700000004000," +
                "\"snapshot\":{\"resourceId\":null,\"text\":\"Bluetooth\",\"contentDesc\":null," +
                "\"className\":\"android.widget.TextView\",\"pkg\":\"com.android.settings\",\"indexPath\":[0,1,2,1]}}]," +
                "\"rejectedEvents\":[]}"
    }
}
