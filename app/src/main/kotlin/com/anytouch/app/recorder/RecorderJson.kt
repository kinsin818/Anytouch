package com.anytouch.app.recorder

import com.anytouch.contracts.Action
import com.anytouch.contracts.ContractJson
import kotlinx.serialization.builtins.ListSerializer

private val ACTION_LIST = ListSerializer(Action.serializer())

/**
 * 产物序列化（军令 L2-6"输出即契约"）：List<Action> ⇄ JSON 字符串，
 * 走 contracts 冻结的 ContractJson 配置；解码失败透传异常（脏输入报失败，不假绿）。
 */
fun encodeActions(actions: List<Action>): String = ContractJson.instance.encodeToString(ACTION_LIST, actions)

fun decodeActions(json: String): List<Action> = ContractJson.instance.decodeFromString(ACTION_LIST, json)
