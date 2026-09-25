package com.anytouch.app.recorder

import com.anytouch.contracts.Action
import com.anytouch.contracts.ContractJson
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement

private val ACTION_LIST = ListSerializer(Action.serializer())

/**
 * 产物序列化（军令 L2-6"输出即契约"）：List<Action> ⇄ JSON 字符串，
 * 走 contracts 冻结的 ContractJson 配置；解码失败透传异常（脏输入报失败，不假绿）。
 */
fun encodeActions(actions: List<Action>): String = ContractJson.instance.encodeToString(ACTION_LIST, actions)

fun decodeActions(json: String): List<Action> = ContractJson.instance.decodeFromString(ACTION_LIST, json)

/**
 * 同一份动作数组的 **JsonElement 形态**（S5-e"我的任务"存储件用）。
 *
 * 为什么单开这两格而不是让存储件自己 `ListSerializer(Action.serializer())`：
 * 那两个构造点就是"步序账怎么变成 JSON"的第二份真值（雷 18 同族）——本文件的 [ACTION_LIST]
 * 与 [ContractJson] 配置是唯一口径，存储件里的 `actions` 数组必须与任务框里那串**逐字节同构**，
 * 载入后 [encodeActions] 现算出的文本才会与 V-3 准入认的那一份对得上。
 */
fun actionsToJsonElement(actions: List<Action>): JsonElement =
    ContractJson.instance.encodeToJsonElement(ACTION_LIST, actions)

fun actionsFromJsonElement(element: JsonElement): List<Action> =
    ContractJson.instance.decodeFromJsonElement(ACTION_LIST, element)

