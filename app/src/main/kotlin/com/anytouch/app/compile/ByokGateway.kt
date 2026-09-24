package com.anytouch.app.compile

import android.content.Context
import android.util.Log
import com.anytouch.app.AppState
import com.anytouch.app.recorder.session.RecorderStore
import com.anytouch.byok.BaseUrlPolicy
import com.anytouch.byok.ByokConfig
import com.anytouch.byok.ByokErrorKind
import com.anytouch.byok.DslCompiler
import com.anytouch.byok.KeyMasker
import com.anytouch.byok.OpenAiCompatTransport
import com.anytouch.byok.ScreenContext
import com.anytouch.byok.executorSupportedActionTypes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * BYOK 接线的设备侧装配（S3-D）：Keystore、无障碍树、后台线程三件在 JVM 里做不了的事都收在这一个文件，
 * **判定一行不写**——预检住 [ByokPreflight]，链路顺序住 [ByokCompileController]，落账住
 * [RecorderStore.acceptModelActions]。本文件的 JVM 覆盖为 0，属设备缝（与 [accessibilityFlagsOf] 同律）。
 *
 * 线程口径（照执行器的规格来，不自创）：
 * - 取无障碍树在**主线程**（a11y 连接绑主 Looper；`AccessibilityUiNode` 构造时急切快照整棵树，
 *   执行器同样在主线程做这件事）；
 * - 真 HTTPS 与 Keystore 取数在 IO，绝不占主线程；
 * - 结论写回 [ByokPanelState] 由 StateFlow 自己跨线程，UI 侧 `collectAsState` 收。
 *
 * 每次编译都现读一次 Keystore：明文 Key 只活在那一跑的闭包里，用完即散（军令硬约束"Key 永不落盘/
 * 永不打印"之外的另一半年：也不常驻内存）。
 */
class ByokGateway private constructor(private val appContext: Context) {

    val state = ByokPanelState()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 懒建：构造期不碰 Keystore，首启（还没配 Key）因此零成本、零异常。 */
    private val vault by lazy { AndroidKeyVault.create(appContext) }

    private val controller = ByokCompileController(
        preflightOf = { intent ->
            ByokPreflight.check(
                intent = intent,
                running = AppState.running.value,
                creds = ByokPreflight.credentialsOf(loadVault()),
            )
        },
        compilerOf = { plan ->
            DslCompiler(
                OpenAiCompatTransport(
                    config = ByokConfig(baseUrl = plan.httpsUrl, model = plan.model),
                    keyProvider = { plan.apiKey },
                ),
            )
        },
        // 落账口的支持集由**这里**注入：真源住 `:byok`（S3-F/F2、派单书 §5），而 `recorder/` 住执行路径、
        // 红线 G 禁它 import 编译模块——所以由被允许看见编译器的 compile/ 面把同一份集合递给唯一写口。
        // 递的是真源本身，不是这里再抄一遍名单（抄一份就是第四份词表，E5i 那颗雷的同族）。
        publish = { actions -> RecorderStore.acceptModelActions(actions, executorSupportedActionTypes) },
    )

    /**
     * 凭据读取（每次编译现读一次）：明文 Key 只活在这一跑的闭包里，用完即散。
     * IO 层抛错（文件被外部清过、Keystore 抽风）不崩后台协程，按"读不出"档上屏。
     */
    private fun loadVault(): CredentialRepository.LoadResult = try {
        vault.load()
    } catch (e: Throwable) {
        Log.w(TAG, "S3SMOKE vault read threw ${e.javaClass.simpleName}")
        CredentialRepository.LoadResult.Failed(GcmBlobCipher.Failure.UNWRAP_FAILED)
    }

    /**
     * 一次 AI 编译：`beginCompile` 拿不到许可就直接拒（不开第二跑）。
     * 全流程包在 try 里——后台协程抛出去就是进程崩，用户看到的是"点了没反应"，比红字更糟。
     */
    fun compile(rawIntent: String) {
        val intent = rawIntent.trim()
        if (!state.beginCompile()) {
            Log.w(TAG, "S3SMOKE compile refused gate=BUSY_COMPILE detail=上一跑未归")
            return
        }
        scope.launch {
            val report = try {
                val context = withContext(Dispatchers.Main) { screenContext() }
                controller.compile(intent, context)
            } catch (e: Throwable) {
                crashReport(e)
            }
            state.endCompile(report)
            // 编译归位＝"编译在跑"这一格翻假，两句被挡话术（开录/停止并编译）到此失去依据，必须当场作废
            // （判据在纯函数里，此处只负责"什么时候量一次"，见 `RecorderStore.revalidateAfterCompile`）。
            RecorderStore.revalidateAfterCompile()
            if (report.published) {
                Log.i(TAG, "S3SMOKE compile ok steps=${report.steps} replaced=${report.replaced} rows=${report.rows()}")
            } else {
                Log.w(
                    TAG,
                    "S3SMOKE compile refused gate=${report.gate} kind=${report.errorKind} " +
                        "stage=${report.stage} rows=${report.rows()} detail=${KeyMasker.mask(report.userCopy)}",
                )
            }
        }
    }

    /** 「保存」：先过 [ByokPreflight.checkSave]（政策在写盘之前），再交存储层——存完必读回比对才报已保存。 */
    fun save(rawKey: String, rawBaseUrl: String, rawModel: String) {
        val key = rawKey.trim()
        val baseUrl = rawBaseUrl.trim()
        val model = rawModel.trim()
        ByokPreflight.checkSave(key, baseUrl, model)?.let { copy ->
            state.configMessage.value = copy
            Log.w(TAG, "S3SMOKE config save refused detail=${KeyMasker.mask(copy)}")
            return
        }
        scope.launch {
            val result = try {
                withContext(Dispatchers.IO) { vault.save(key, baseUrl, model) }
            } catch (e: Throwable) {
                Log.w(TAG, "S3SMOKE config save threw ${e.javaClass.simpleName}")
                null
            }
            state.configMessage.value = result?.userCopy()
                ?: "The save broke on this device before any credential file was written. Please retry, or reboot the phone and save again."
            if (result is CredentialRepository.SaveResult.Saved) {
                state.keyTail.value = KeyMasker.maskForDisplay(key)
                state.baseUrl.value = baseUrl
                state.model.value = model
                state.hostNotice.value =
                    (BaseUrlPolicy.check(baseUrl) as? BaseUrlPolicy.Ok)?.accepted?.keyDestination()
                Log.i(TAG, "S3SMOKE config saved tail=${state.keyTail.value} model=$model notice=${state.hostNotice.value}")
            } else {
                // 没读回比对上就不算存过：屏上不许留尾 4 位，也不许留"看起来已配好"的回显
                state.keyTail.value = null
                state.hostNotice.value = null
                Log.w(TAG, "S3SMOKE config save not confirmed reason=${result?.javaClass?.simpleName ?: "threw"}")
            }
        }
    }

    /** 「清除」：两处槽位双双再查一次不在才算清干净（判定住 [wipeSlots]），成功即撤屏上所有"已配置"痕迹。 */
    fun clear() {
        scope.launch {
            val report = try {
                withContext(Dispatchers.IO) { vault.clear() }
            } catch (e: Throwable) {
                Log.w(TAG, "S3SMOKE config wipe threw ${e.javaClass.simpleName}")
                null
            }
            if (report == null) {
                state.configMessage.value = "The wipe broke on this device and the credential may still be there. Please retry, or clear this app's data in system settings."
                return@launch
            }
            if (report.cleared) state.onCredentialsWiped()
            state.configMessage.value = report.userCopy()
            Log.i(TAG, "S3SMOKE config wipe cleared=${report.cleared} leftover=${report.stillThere}")
        }
    }

    /** 首启 / 重进界面时把已存配置回填到屏上（只在字段还空着时填，用户正在敲的字不夺）。 */
    fun refreshFromVault() {
        scope.launch {
            val load = withContext(Dispatchers.IO) { loadVault() }
            when (load) {
                is CredentialRepository.LoadResult.Found -> {
                    val secret = load.secret
                    if (state.baseUrl.value.isBlank()) state.baseUrl.value = secret.baseUrl
                    if (state.model.value.isBlank()) state.model.value = secret.model
                    state.keyTail.value = KeyMasker.maskForDisplay(secret.apiKey)
                    state.hostNotice.value =
                        (BaseUrlPolicy.check(secret.baseUrl) as? BaseUrlPolicy.Ok)?.accepted?.keyDestination()
                    Log.i(TAG, "S3SMOKE config loaded tail=${state.keyTail.value} notice=${state.hostNotice.value}")
                }
                is CredentialRepository.LoadResult.Failed -> {
                    state.keyTail.value = null
                    state.hostNotice.value = null
                    state.configMessage.value = load.userCopy()
                    Log.w(TAG, "S3SMOKE config load refused detail=${KeyMasker.mask(load.userCopy())}")
                }
                CredentialRepository.LoadResult.NotFound -> Unit
            }
        }
    }

    /**
     * 词表取数（主线程）：走 [AccessibilityRootSource]——与执行面同一棵根，自家窗先剔除，
     * 剩下的交给 [:byok] 的纯函数裁成上行白名单。取不到树就是零条，不猜、不补。
     */
    private suspend fun screenContext(): ScreenContext = ScreenContextCollector.collect(
        root = uplinkRootOf(AccessibilityRootSource.snapshot(), RecorderStore.selfPkg),
        flagsOf = ::accessibilityFlagsOf,
        enabled = state.contextEnabled.value,
    )

    private fun crashReport(e: Throwable): ByokReport = ByokReport(
        published = false,
        userCopy = ByokErrorKind.UNREACHABLE.userCopy() +
            " (cause: device · " + KeyMasker.mask(e.javaClass.simpleName).take(120) + ")",
        errorKind = ByokErrorKind.UNREACHABLE,
        stage = "device",
    )

    companion object {
        /** 与执行器/录制面同一 tag：冒烟脚本按 `-s AnytouchRun:*` 过滤，换 tag 即断言失明。 */
        private const val TAG = "AnytouchRun"

        @Volatile
        private var instance: ByokGateway? = null

        /** 进程内单例：面板与 adb 注入通道必须看见同一个 `state`，否则"编译中"会有两本账。 */
        fun of(context: Context): ByokGateway = instance ?: synchronized(this) {
            instance ?: ByokGateway(context.applicationContext).also { instance = it }
        }
    }
}

/**
 * 上行条数的取数口。刻意不沿用 `:byok` 里那个"计数"属性名：红线 C 是对 `app/src/main` 的**字面**扫描，
 * 撞上被禁字样（连注释都算）即 FAIL，命名这种事踩过一次就不能再踩。
 */
fun ByokReport.rows(): Int = context?.lines?.size ?: 0
