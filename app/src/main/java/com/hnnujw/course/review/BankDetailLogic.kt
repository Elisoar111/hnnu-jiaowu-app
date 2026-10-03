package com.hnnujw.course.review

/**
 * 题库详情页的**纯逻辑**：章节选项构造、选中章节解析、练习模式与下标互转、正确率换算。
 *
 * 为什么全部抽成纯函数（不写进 Composable）：
 *  - 「全部章节」用 `null` 表达，而 UI 上是一个普通选项，index ↔ chapter 的映射
 *    如果在 Composable 里用 `if (index == 0) null else list[index - 1]` 手写，
 *    章节一多就极容易差一位；抽出来后可以被单测直接钉死。
 *  - `LiquidSegmentedControl` 只认下标，`pickQuestions` 只认 [ReviewMode]，
 *    两者互转也必须可测。
 */

/** 章节选择项。[value] 为 `null` 表示「全部章节」（不按章节过滤）。 */
data class ChapterOption(
    val value: String?,
    val label: String,
    val count: Int
)

/** 「全部章节」在选项列表里的固定文案。 */
const val ALL_CHAPTERS_LABEL = "全部章节"

/**
 * 把 [ReviewDatabase.chaptersWithCount] 的结果转成可渲染的选项列表。
 *
 * 首项恒为「全部章节」（[ChapterOption.value] = null），其 [ChapterOption.count]
 * 是所有章节题数之和 —— 与题库总题数一致（未分类题也算在某一章里）。
 *
 * @param chapters 章节 → 题数，已按章节名排序（由 SQL 的 `ORDER BY chapter` 保证）。
 */
fun chapterOptions(chapters: List<Pair<String, Int>>): List<ChapterOption> {
    val total = chapters.sumOf { it.second }
    return buildList {
        add(ChapterOption(value = null, label = ALL_CHAPTERS_LABEL, count = total))
        chapters.forEach { (name, count) ->
            add(ChapterOption(value = name, label = name, count = count))
        }
    }
}

/**
 * 由选中下标解析出要传给 [ReviewDatabase.pickQuestions] 的 `chapter`。
 *
 * 下标越界（选项为空、或选项被重算后下标未同步）一律返回 `null` = 全部章节，
 * 而不是抛异常 —— 详情页可能在章节重算的同一帧里被重组。
 */
fun resolveSelectedChapter(options: List<ChapterOption>, index: Int): String? =
    options.getOrNull(index)?.value

/** [ReviewMode] → [LiquidSegmentedControl] 需要的下标。 */
fun modeIndex(mode: ReviewMode): Int = ReviewMode.entries.indexOf(mode).coerceAtLeast(0)

/** 下标 → [ReviewMode]；越界回退到 [ReviewMode.SEQUENTIAL]，保证 `pickQuestions` 永远拿到合法模式。 */
fun modeAt(index: Int): ReviewMode = ReviewMode.entries.getOrNull(index) ?: ReviewMode.SEQUENTIAL

/**
 * 正确率百分比（0..100，四舍五入）。
 *
 * 未答任何题时返回 0（不是 NaN、也不抛）—— 页面首次进入 overview.answered 就是 0。
 */
fun accuracyPercent(correct: Int, answered: Int): Int {
    if (answered <= 0) return 0
    val safeCorrect = correct.coerceIn(0, answered)
    return Math.round(safeCorrect * 100f / answered)
}
