package com.hnnujw.course.review

import java.util.Collections
import java.util.Random

/**
 * 选项乱序：把**显示序**与**数据序**彻底分开。
 *
 * 背景：题库里 `options` 是有序列表，答案 `answer` 是**按原始下标**写的字母串
 * （"A" / "ABD"）。一旦打乱显示顺序，界面上的 "A" 就不再等于原始 "A" ——
 * 直接照搬会把「选 A」记成原始 A、把正确选项标错。
 *
 * 本文件的做法（参考 Shiroha Quiz 的 `PracticeDisplayOption` 思路）：
 *  - 显示层只认 [DisplayOption.letter]（按显示位置重新编号 A/B/C…）；
 *  - 判分、落库、错题本、答题卡**一律继续用 [DisplayOption.originalLetter]**；
 *  - 因此 [PracticeSession] 与数据库**一行都不用改**，乱序纯粹是渲染层的事。
 *
 * 乱序关闭时（默认）[displayOptions] 返回恒等映射，行为与从前逐字一致。
 *
 * 隐私：本文件只做下标搬运，不读不写任何文本内容。
 */
data class DisplayOption(
    /** 显示位置上的字母（A、B、C…）。用户点它、界面标它。 */
    val letter: String,
    /** 该选项在题库里的原始下标（0-based）。 */
    val originalIndex: Int,
    /** 选项文本。 */
    val text: String,
    /** 该选项在题库里的原始字母。判分与落库都用它。 */
    val originalLetter: String
)

/**
 * 计算一道题的显示序。
 *
 * @param shuffle 是否打乱（来自「选项乱序」设置）。
 * @param seed 本轮会话的种子。**必须是每轮新的值**（如开练时的 `currentTimeMillis`），
 *   否则同一道题每次进来都是同一个顺序，等于没打乱。
 *
 * 单选题、多选题、判断题都走这里；判断题只有两个选项，打乱与否都一样，
 * 但走同一条路径可以少一个分支（少一个分支就少一处漏改）。
 */
fun displayOptions(question: ReviewQuestion, shuffle: Boolean, seed: Long): List<DisplayOption> {
    val indices = question.options.indices.toList()
    val order = if (shuffle && indices.size > 1) {
        // 用「会话种子 ⊕ 题目 id 的哈希」作随机源：同一题在同一轮会话里每次重组
        // 都得到同一个顺序（否则每次重组选项都在跳），换一轮则整体重排。
        indices.toMutableList().also {
            Collections.shuffle(it, Random(seedFor(question, seed)))
        }
    } else {
        indices
    }
    return order.mapIndexed { displayIndex, originalIndex ->
        DisplayOption(
            letter = letterAt(displayIndex),
            originalIndex = originalIndex,
            text = question.options[originalIndex],
            originalLetter = letterAt(originalIndex)
        )
    }
}

/**
 * 一道题在本轮会话里的稳定随机种子。
 *
 * 用 `会话种子 xor (题目键 * 黄金比例常数)` 而不是直接 `Random(会话种子)`：
 * 后者会让**所有题目**共用同一个随机序列，题与题之间的洗牌结果高度相关
 * （同一轮里第 1 题与第 2 题的排列规律相同），看起来像"没乱"。
 */
fun seedFor(question: ReviewQuestion, seed: Long): Long =
    seed xor ((question.bankId + "#" + question.id).hashCode().toLong() * 2654435761L)

/**
 * 把「原始字母的答案」翻译成「显示字母的答案」，用于给用户看
 * （判定条里的"正确答案 AB · 你选 BA"、背题模式的答案条）。
 *
 * 判分本身**不要**用它 —— 判分必须在原始字母空间里做。
 */
fun toDisplayAnswer(answer: String, options: List<DisplayOption>): String =
    normalizeAnswer(answer)
        .mapNotNull { ch -> options.firstOrNull { it.originalLetter == ch.toString() }?.letter }
        .sorted()
        .joinToString("")

/** 第 index 个位置上的选项字母：0→A、1→B…；超过 Z 时退化成数字（选项最多 8 个，走不到）。 */
private fun letterAt(index: Int): String =
    if (index in 0..25) ('A'.code + index).toChar().toString() else (index + 1).toString()
