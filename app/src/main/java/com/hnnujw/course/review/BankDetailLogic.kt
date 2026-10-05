package com.hnnujw.course.review

/**
 * 题库详情页的**纯逻辑**：章节多选、题数汇总、正确率换算。
 *
 * 为什么全部抽成纯函数（不写进 Composable）：这几件事都是"算错了不会崩、只会显示假数字"
 * 的类型 —— 例如选中 3 个章节却报出全库题数，用户在练习页发现题数对不上时，
 * 根本不会想到是详情页的汇总写错了。抽出来就能被单测直接钉死。
 *
 * 2026-10 的两处结构性改动：
 *  - **章节改成多选**。原先用「下标 → 章节名」的映射（`ChapterOption` +
 *    `resolveSelectedChapter`），因为 UI 上「全部章节」是一个普通选项、要占一个下标。
 *    多选后选择状态的载体变成 `Set<String>`（章节名即键），下标映射整个消失 ——
 *    它当初存在的唯一理由（"`if (index == 0) null else list[index - 1]` 容易差一位"）
 *    也随之消失，故 `ChapterOption` 与 `resolveSelectedChapter` 一并删除。
 *  - **`modeIndex` / `modeAt` 删除**：那是给 `LiquidSegmentedControl` 用的下标互转，
 *    详情页改用四档 `ModeCell` 后已无调用点（只剩单测在用，属于死代码）。
 */

/** 「全部章节」在界面上的文案（选中集合为空时的等价说法）。 */
const val ALL_CHAPTERS_LABEL = "全部章节"

/**
 * 切换一个章节的选中状态。
 *
 * 返回新集合而不是原地改：Compose 靠**引用变化**触发重组，原地改 `MutableSet`
 * 会让界面停在旧状态（选项不高亮），这与练习页那个"选项不高亮"的坑同源。
 * 空集 = 「全部章节」，所以全部取消勾选就是回到不过滤。
 */
fun toggleChapterSelection(selected: Set<String>, chapter: String): Set<String> =
    if (chapter in selected) selected - chapter else selected + chapter

/** 当前选择下的题数。空集（全部章节）时是全库题数。 */
fun selectedChapterQuestionCount(
    chapters: List<Pair<String, Int>>,
    selected: Set<String>
): Int =
    if (selected.isEmpty()) {
        chapters.sumOf { it.second }
    } else {
        chapters.filter { it.first in selected }.sumOf { it.second }
    }

/** 当前选择的标题：空集 → 「全部章节」；单章 → 章节名；多章 → 「已选 N 章」。 */
fun chapterSelectionLabel(selected: Set<String>): String = when {
    selected.isEmpty() -> ALL_CHAPTERS_LABEL
    selected.size == 1 -> selected.first()
    else -> "已选 ${selected.size} 章"
}

/**
 * 归一化成传给 [ReviewDatabase.pickQuestions] 的章节列表；空集 = 不过滤。
 *
 * 顺带滤掉空白项：题库里 `chapter` 字段有默认值「未分类」，但导入的数据仍可能
 * 出现空串；空串进 SQL 的 `IN (?)` 只会白跑一趟。
 */
fun resolveSelectedChapters(selected: Set<String>): List<String> =
    selected.filter { it.isNotBlank() }

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
