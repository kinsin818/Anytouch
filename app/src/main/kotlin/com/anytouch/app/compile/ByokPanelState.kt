package com.anytouch.app.compile

import com.anytouch.app.AppState
import com.anytouch.app.compile.ByokPreflight.Gate
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * adb 注入通道对"上下文开关"的下发口径：只认 `on`/`off` 两个字面，别的值一律**不改动**当前状态。
 * 故意不做 `true/1/yes` 的宽容解析——宽容一次，脚本里就会长出三种写法，而第四种写法静默按 off 处理
 * 就是一次假红。
 */
fun byokContextFlagOf(raw: String?): Boolean? = when (raw) {
    "on" -> true
    "off" -> false
    else -> null
}

/**
 * AI 编译面板的全部可见状态（纯 Kotlin + StateFlow，零 Android，JVM 可锁）。
 *
 * 单独立一个类，是因为"上一跑还没回来就不能开第二跑"和"新一次编译开始要撤下旧红字"这两条
 * 都是能被真锁住的判据（V-2 陈旧话术同律）；放在 Compose 里就只能靠设备点验。
 *
 * [contextEnabled] 默认 **开**（S3 裁 2 原文：带屏上下文、可开关），且只在本进程内活着——
 * 落盘一个开关状态=多一条"用户以为关着但其实开着"的争议面，本片不做持久化。
 *
 * [busy] 默认直接是全局那一格（[AppState.compileBusy]）：面板按钮、开录门禁、停止并编译门禁读的
 * 必须是同一个真值（裁决 S3-R4-1 要求"状态机互斥"，两套"编译中"=两套真值，雷 18 同族）。
 * 用例可以传自己的 flow 做隔离。
 */
class ByokPanelState(val busy: MutableStateFlow<Boolean> = AppState.compileBusy) {

    /**
     * 待编译的那句话。住在状态里而不是住在 Compose 里：UI 输入框与 adb 注入通道必须写同一格，
     * 否则"屏上看到的这句话"和"实际发出去的这句话"就是两套真值（雷 18 同族）。
     */
    val intent = MutableStateFlow("")

    val contextEnabled = MutableStateFlow(true)

    /** 最近一次编译结论（成功也要说话，失败必须说话）。 */
    val report = MutableStateFlow<ByokReport?>(null)

    /** 已保存凭据的展示形态：只有尾 4 位（军令 §3-3"输入即掩码"）。 */
    val keyTail = MutableStateFlow<String?>(null)

    /** 服务地址与模型名不是秘密，屏上原样可读写；Key 从不进这里。 */
    val baseUrl = MutableStateFlow("")
    val model = MutableStateFlow("")

    /** 「保存 / 清除」这一路的话术（与编译结论分开：两件事同时红着时不许互相盖）。 */
    val configMessage = MutableStateFlow<String?>(null)

    /** 地址知情回显整句（裁 §3-4）："你的 Key 只发往这一个地址：host"。文案由政策层生成，此处只存结果。 */
    val hostNotice = MutableStateFlow<String?>(null)

    /**
     * 开跑许可：false=上一跑还没回来，本次直接拒并把话术上屏（禁并发烧钱，也禁后回来的产物
     * 静默盖掉先回来的那份）。成功领取时顺手撤下旧结论——新一次编译已经开始，还挂着上一条红字
     * 就是假红。
     *
     * 领取成功即把 [busy]（默认就是全局那格 [AppState.compileBusy]）翻成 true：**录制面从此被挡**
     * （开录 / 停止并编译各判一档 COMPILING，裁决 S3-R4-1）。被拒的那一次不许翻格——
     * 第二跑没开起来却把持有置真，等于凭空锁死录制面。
     */
    fun beginCompile(): Boolean {
        if (busy.value) {
            report.value = ByokReport(
                published = false,
                userCopy = ByokPreflight.copyOf(Gate.BUSY_COMPILE),
                gate = Gate.BUSY_COMPILE,
            )
            return false
        }
        busy.value = true
        report.value = null
        return true
    }

    fun endCompile(report: ByokReport) {
        busy.value = false
        this.report.value = report
    }

    /** 清除成功后屏上不许再留任何"看起来还配着"的痕迹：尾 4 位与知情回显一起撤，地址与模型名也归空。 */
    fun onCredentialsWiped() {
        keyTail.value = null
        hostNotice.value = null
        baseUrl.value = ""
        model.value = ""
    }
}
