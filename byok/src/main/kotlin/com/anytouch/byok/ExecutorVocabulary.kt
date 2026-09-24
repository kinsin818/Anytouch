package com.anytouch.byok

import com.anytouch.contracts.ActionType

/**
 * **全仓唯一的「执行器跑得动的 Action.type」真源**（裁决 S31-B3"收紧词表"，落账面 S3-F/F2）。
 *
 * 为什么必须有这一格（磁盘事实，不是臆想）：这批之前全仓有**三份互不相同**的手抄词表——
 * - 执行器 `when (action.type)`（`app/.../executor/NodeTaskRunner.kt:98-121`）：`wait/click/scroll/type_text`，
 *   其余一律 `unsupported_type` + `StopCode.EXECUTOR_ERROR`（**行为真集**）；
 * - 编译器授权集（`DslCompiler.ALLOWED_TYPES`，旧字面）：`click/type_text/scroll/`**`key`** —— 多一个 `key`；
 * - 冒烟脚本 E5i（`scripts/byok-smoke.sh` 的 `grep -vw -e click -e type_text -e scroll -e wait`）。
 * 三份里"授权的那一份"比"跑得动的那一份"宽，于是模拟器 `[E1-*]` 轮抓到 `key/back`：账落得下、屏上看得到，
 * 一点「执行任务」第一步就以 EXECUTOR_ERROR 收官（间歇红，见 `evidence/S3/slice-e2-k40-key.md` §5）。
 * 雷 18 同族：两处实现只有坏的那条会被看见，三处同理。
 *
 * 三条硬约束（派单书 §5 与 §4-3 的字面）：
 * 1) **住 `:byok`**（依赖方向 `:app → :byok → :core:contracts`，真源若在 `:app` 则 `:byok` 引不到＝循环依赖）；
 * 2) **android-free**：本文件零 `android.*`、零 `Context`、零 Compose，JVM 直接锁；
 * 3) **元素类型是 `String` 不是 `ActionType`**（S31-B5：`ActionType` 是 `object` + `const val String`，
 *    自陈"非严格枚举，留扩展位"；做成枚举要另一次放行 `core/`）。集合成员一律引 `ActionType.X` **常量**，
 *    不写字面量——比较侧同一条纪律。
 *
 * 词表变化方向照实登记（F2-4"副作用照报"）：相对旧授权集，`key` **出**（老板裁的收紧落点）、
 * `wait` **入**（它本来就在执行器的真集里，从真源派生必然带上；模型编一步 wait 是跑得动的动作，
 * 不是新的能力面）。执行器一侧**没有**补 `KEY`/`BACK` 派发（§3 明确不做）。
 */
val executorSupportedActionTypes: Set<String> = setOf(
    ActionType.WAIT,
    ActionType.CLICK,
    ActionType.SCROLL,
    ActionType.TYPE_TEXT,
)

/** 单一判定口：某一条 type 执行器派得动吗。编译侧、落账侧、话术侧都调它，不许各自 `in` 一份抄来的集合。 */
fun supportsExecutorDispatch(type: String): Boolean = type in executorSupportedActionTypes

/** 稳定序列化（声明序）。JVM 用例用它对拍提示词那半句与 `scripts/byok-smoke.sh` 的字面，顺序无关的比较也走这条。 */
fun executorSupportedTypesSerialized(separator: String = ","): String =
    executorSupportedActionTypes.joinToString(separator)

/**
 * 提示词里"type 只允许 …"那一整半句**由真源拼出**——提示词与校验器说两套话的成因就是那句手抄，
 * 现在它在物理上抄不出来（派单书 §1-F2-3）。
 */
fun executorSupportedTypesPromptClause(): String =
    "type 只允许 " + executorSupportedActionTypes.joinToString(" | ") { "\"$it\"" }
