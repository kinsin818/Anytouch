package com.anytouch.app.compile

import com.anytouch.app.compile.ByokPreflight.Credentials
import com.anytouch.app.compile.ByokPreflight.Gate
import com.anytouch.app.compile.ByokPreflight.Verdict
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 编译前门禁的 JVM 锁（S3-D）：**顺序**与**每档一句话**是这里唯一要证的两件事。
 *
 * 为什么值得单独锁：这是全仓第一条 UI 触发的网络路径。判序错一格（先查地址再查执行态），
 * 用户在跑任务时就会看到"地址不合法"这种驴唇不对马嘴的红字；话术撞车（两档同一句），
 * 用户就分不清是没存 Key 还是手机没网——第一次配置就说假话，BYOK 产品没有第二次机会。
 */
class ByokPreflightTest {

    private val secretKey = "nvapi-AbCdEf0123456789abcdef"

    private fun ready(
        url: String = "https://integrate.api.nvidia.com/v1",
        model: String = "deepseek-ai/deepseek-v3",
        key: String = secretKey,
    ): Credentials = Credentials.Ready(StoredSecret(key, url, model))

    private fun blocked(intent: String = "进蓝牙页", running: Boolean = false, creds: Credentials = ready()): Verdict.Blocked =
        ByokPreflight.check(intent, running, creds) as Verdict.Blocked

    @Test
    fun `除 READY 外每档都有独立话术 且都不空`() {
        val gates = Gate.values().filter { it != Gate.READY }
        val copies = gates.map { ByokPreflight.copyOf(it) }
        assertEquals(gates.size, copies.size, "档位漏了一个就没人为它写话术")
        assertEquals(copies.size, copies.distinct().size, "两档共用一句话=用户分不清该改哪一处")
        assertTrue(copies.none { it.isBlank() })
    }

    @Test
    fun `意图为空先拒意图 连凭据和执行态都不看`() {
        val v = ByokPreflight.check("   ", running = true, creds = Credentials.Absent)
        assertEquals(Gate.EMPTY_INTENT, (v as Verdict.Blocked).gate)
        assertTrue(v.userCopy.contains("the intent box is empty"))
    }

    @Test
    fun `执行中不编译 即使配置完全正确`() {
        assertEquals(Gate.RUNNING, blocked(running = true).gate)
    }

    @Test
    fun `本机从没存过凭据时拒在出门前`() {
        val v = blocked(creds = Credentials.Absent)
        assertEquals(Gate.NO_CREDENTIAL, v.gate)
        assertTrue(v.userCopy.contains("last 4 digits"), "要告诉用户怎么才算存上")
    }

    @Test
    fun `凭据读不出时把存储层的归因一起上屏`() {
        val load = CredentialRepository.LoadResult.Failed(GcmBlobCipher.Failure.TAMPERED)
        val v = blocked(creds = ByokPreflight.credentialsOf(load))
        assertEquals(Gate.UNREADABLE, v.gate)
        assertTrue(
            v.userCopy.contains("device change or an edited file"),
            "只说'读不出来'等于没说：必须带上存储层已经算好的那半句归因",
        )
    }

    @Test
    fun `模型名为空时不替用户猜一个默认模型`() {
        assertEquals(Gate.NO_MODEL, blocked(creds = ready(model = "  ")).gate)
    }

    @Test
    fun `已存地址现在过不了政策时带上政策原因`() {
        val v = blocked(creds = ready(url = "http://127.0.0.1:8080/v1"))
        assertEquals(Gate.BAD_BASE_URL, v.gate)
        assertTrue(v.userCopy.contains("Only https"))
        assertTrue(v.userCopy.contains("not a single byte"), "要说清什么都没发出去")
    }

    @Test
    fun `READY 给出归一化地址 尾 4 位主机回显和原样 Key`() {
        val plan = (ByokPreflight.check("进蓝牙页", false, ready(url = "https://Integrate.Api.Nvidia.com/v1/")) as Verdict.Ready).plan
        assertEquals("https://Integrate.Api.Nvidia.com/v1", plan.httpsUrl, "尾部斜杠必须去掉（否则 endpointOf 拼出双斜杠）")
        assertEquals("integrate.api.nvidia.com", plan.hostEcho, "回显统一小写 host")
        assertEquals(secretKey, plan.apiKey)
        assertTrue(plan.notice().contains(plan.hostEcho))
    }

    @Test
    fun `plan 与结论的 toString 都不许印出 Key`() {
        val plan = (ByokPreflight.check("进蓝牙页", false, ready()) as Verdict.Ready).plan
        // data class 的默认 toString 是全仓最容易顺手引入的泄露面：这两个类都刻意不做 data class
        assertTrue(plan.toString().contains(secretKey).not(), "ByokPlan.toString 印出了 Key：$plan")
        val verdict: Verdict = Verdict.Ready(plan)
        assertTrue(verdict.toString().contains(secretKey).not(), "Verdict.Ready.toString 印出了 Key：$verdict")
    }

    @Test
    fun `credentialsOf 把存储层三态搬成三档`() {
        val secret = StoredSecret(secretKey, "https://a.example/v1", "m")
        assertTrue(ByokPreflight.credentialsOf(CredentialRepository.LoadResult.Found(secret)) is Credentials.Ready)
        val absent = ByokPreflight.credentialsOf(CredentialRepository.LoadResult.NotFound)
        assertTrue(absent is Credentials.Absent)
        val unreadable = ByokPreflight.credentialsOf(
            CredentialRepository.LoadResult.Failed(GcmBlobCipher.Failure.UNWRAP_FAILED),
        )
        assertTrue(unreadable is Credentials.Unreadable && unreadable.userCopy.isNotBlank())
    }

    @Test
    fun `READY 不能由 block 造出来`() {
        assertFailsWith<IllegalArgumentException> { ByokPreflight.block(Gate.READY) }
    }

    @Test
    fun `保存门禁 半条配置一个字都不落盘`() {
        assertTrue(ByokPreflight.checkSave("", "https://a.example/v1", "m")!!.contains("The key is empty"))
        assertTrue(ByokPreflight.checkSave(secretKey, "https://a.example/v1", " ")!!.contains("model name is empty"))
        val http = ByokPreflight.checkSave(secretKey, "http://a.example/v1", "m")!!
        assertTrue(http.contains("Only https") && http.contains("not a single byte was written"))
        assertNull(ByokPreflight.checkSave(secretKey, "https://a.example/v1", "m"))
    }
}
