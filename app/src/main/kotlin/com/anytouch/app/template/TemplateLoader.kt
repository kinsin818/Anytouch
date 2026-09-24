package com.anytouch.app.template

import android.content.Context
import com.anytouch.app.recorder.decodeActions
import com.anytouch.contracts.Action

/**
 * 模板装载结果：成功给解码后的账本（List&lt;Action&gt;），失败给拒因话术（L2-③ 必须上屏）。
 * 这里**不做**任何门禁判断——门禁在 `RecorderStore.acceptModelActions` 唯一落账口，
 * 装载器只负责"读得到、解得开"；解不开=脏资产，同样大声拒（脏输入不假绿，与编译链同律）。
 */
sealed class TemplateLoad {
    data class Ok(val template: PresetTemplate, val actions: List<Action>) : TemplateLoad()
    data class Failed(val id: String, val reason: String) : TemplateLoad()
}

object TemplateLoader {

    fun load(context: Context, id: String): TemplateLoad {
        val template = PresetTemplateLibrary.byId(id)
            ?: return TemplateLoad.Failed(id, "没有这个预制模板（注册表里查无 id），一步都没落账。")
        return try {
            val text = context.assets.open(template.assetPath).bufferedReader().use { it.readText() }
            TemplateLoad.Ok(template, decodeActions(text))
        } catch (e: Exception) {
            TemplateLoad.Failed(id, "模板「${template.label}」读取或解析失败（${e.javaClass.simpleName}），一步都没落账。")
        }
    }
}
