package com.anytouch.tools.compiler

import com.anytouch.byok.CompileResult
import com.anytouch.byok.DslCompiler

/**
 * T2 探针入口（host 侧手动工具，不进 CI 网络路径）：
 *   ANYTOUCH_NVIDIA_KEY=... ./gradlew :tools:compiler:run --args="--model z-ai/glm-5.3 --intent 点击 Connected devices 然后进入蓝牙"
 * Key 也可经 --key-file 指向仓库外文件；stdout 只输出通过校验的 Action JSON（可直接注入设备执行通道），
 * 校验拒绝走 stderr + 退出码 2。任何路径都不打印 Key。
 */
fun main(args: Array<String>) {
    val parsed = args.parseArgs()
    val key = parsed["key-file"]?.let { path ->
        java.io.File(path).readLines(Charsets.UTF_8).firstOrNull { it.isNotBlank() }?.trim()
    } ?: System.getenv("ANYTOUCH_NVIDIA_KEY")
    if (key.isNullOrBlank()) {
        System.err.println("缺 Key：设 ANYTOUCH_NVIDIA_KEY 或 --key-file <仓库外路径>")
        kotlin.system.exitProcess(1)
    }
    val model = parsed["model"] ?: "deepseek-ai/deepseek-v4.1-flash"
    // --intent-file 优先：中文走命令行会被 Windows 代码页反复转码（Gradle 分词+JVM 参数解码两段），
    // 探针曾因此把意图传成乱码、模型只能拒编空数组；UTF-8 文件直读把变量一次消掉。
    val intent = parsed["intent-file"]?.let { path ->
        java.io.File(path).readText(Charsets.UTF_8).trim().also {
            if (it.isEmpty()) {
                System.err.println("--intent-file 内容为空")
                kotlin.system.exitProcess(1)
            }
        }
    } ?: parsed["intent"]?.takeIf { it.isNotBlank() } ?: run {
        System.err.println("缺 --intent-file（推荐，UTF-8 直读）或 --intent")
        kotlin.system.exitProcess(1)
    }
    System.err.println("PROBE model=$model intent=${intent.take(80)}")
    val compiler = DslCompiler(NvidiaNimTransport(apiKey = key, model = model))
    when (val result = compiler.compile(intent)) {
        is CompileResult.Ok -> {
            println(result.actionsJson)
        }
        is CompileResult.Reject -> {
            System.err.println("REJECT stage=${result.stage} detail=${result.detail}")
            kotlin.system.exitProcess(2)
        }
    }
}

private fun Array<String>.parseArgs(): Map<String, String> {
    val out = HashMap<String, String>()
    var i = 0
    while (i < size) {
        val a = this[i]
        if (a.startsWith("--")) {
            val name = a.removePrefix("--")
            if (i + 1 < size && !this[i + 1].startsWith("--")) {
                out[name] = this[i + 1]
                i += 2
            } else {
                out[name] = ""
                i += 1
            }
        } else i += 1
    }
    return out
}
