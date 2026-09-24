package com.anytouch.app.recorder

import com.anytouch.contracts.Action

/**
 * AI 编译产物落账口的**词表档**判定（S3-F/F2-3，裁决 S31-B3"收紧词表（编译侧拒并显式）"）。
 *
 * 为什么还要在落账口再判一次（编译器那关已经会拒了）：编译器那一档判的是"模型交来的 JSON 形不合法"，
 * 落账口这一档判的是"这本账执行面跑不跑得动"。两道各管一件事、各自留一条日志（派单书 §4-2：
 * "各自一条、不并档"），任何一道单独被改坏都还有另一道挡着——这不是重复判据，是同一判据的两个来路
 * （`ByokPreflight` 的 RUNNING 面与 `acceptModelActions` 的 RUNNING 面同一条先例）。
 *
 * **本文件刻意不引用编译模块（`:byok` 那条依赖线）**：`recorder/` 住执行路径，红线 G（`scripts/ci-local.sh`）
 * 禁止它看见编译模块，"执行期零网络"才成结构而不是注释。所以支持集由**调用方注入**
 * （生产唯一来路：`compile/ByokGateway` 把 `:byok` 真源传进 `acceptModelActions`），
 * 判据本体仍然住在落账口这个唯一写口上——门禁落入口不落按钮同律。
 *
 * 纯函数、零平台依赖：三条判据都要能在 JVM 里锁住（接线侧带 `Log`，锁不住）。
 */

/** 整本里第一条"执行面跑不动"的动作：`index` 是它在账上的位序（上屏要点名，不许只说"有非法步骤"）。 */
data class UnsupportedModelAction(val index: Int, val type: String)

/**
 * 逐条扫一遍，取第一条不在支持集里的动作；全支持则 null（整本放行）。
 * 只看 `type`——词表之外的形状问题归编译器那一档，本档不管"这条 click 缺不缺 text"。
 */
fun firstUnsupportedModelAction(actions: List<Action>, supportedTypes: Set<String>): UnsupportedModelAction? {
    val index = actions.indexOfFirst { it.type !in supportedTypes }
    return if (index < 0) null else UnsupportedModelAction(index, actions[index].type)
}

/**
 * 支持集的人读写法（上屏 + 日志都用这一份，两处不许各拼一次）。
 * 排序输出：集合是无序的，不排一次就成了"每次看到不一样"的话术。
 */
fun modelLedgerSupportedTypesCopy(supportedTypes: Set<String>): String = supportedTypes.sorted().joinToString("/")

/**
 * 词表档的拒因话术（L2-③"错误必显示"）：必须显式说清**支持的是哪几个**，
 * 否则用户只拿到一句"编译失败"，改什么都不知道——那一跑的钱就白烧了（F2-4 的副作用照报）。
 */
fun unsupportedModelLedgerCopy(unsupported: UnsupportedModelAction, supportedTypes: Set<String>): String =
    "第 ${unsupported.index + 1} 步是 type=${unsupported.type}，执行器跑不动它，整本一步都没落账。" +
        "现在跑得动的只有 " + modelLedgerSupportedTypesCopy(supportedTypes) + "（真源=:byok ExecutorVocabulary，裁 S31-B3 收紧词表）。" +
        "为什么整本拒而不是跳过那一步：半本账会照常上屏，屏上步骤与你说的意图不一致却没有任何一句报错，" +
        "比「这一跑没成」更难发现。请改说跑得动的意图，再点一次「AI 编译」（拒一次=重编一次，这一步不省钱）。"
