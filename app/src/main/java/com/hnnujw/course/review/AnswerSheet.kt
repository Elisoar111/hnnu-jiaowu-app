package com.hnnujw.course.review

/**
 * 练习页「答题卡」的**纯逻辑**：每一题的作答状态与整体汇总。
 *
 * 为什么抽出来：答题卡上每个格子的颜色就是"这题怎么样了"，一旦状态判定写错，
 * 用户会照着错的颜色去跳题（比如把未答看成已答、跳过一整片没做的题），
 * 而这类错误**不崩不报错**，只有肉眼盯着几十个格子才可能发现。抽成纯函数后
 * 可以用单测把四种状态的边界逐条钉住。
 *
 * 四种状态是刻意分开的：本应用是"先选再确认"的两步作答，所以"选了但没确认"
 * （[Answered]）与"确认过"（[Correct] / [Wrong]）是两回事 —— 前者还没判分，
 * 既不算对也不算错。把两者合成一种，用户就分不清"我提交了吗"。
 */

/** 答题卡上单个格子的状态。 */
enum class AnswerSheetStatus {
    /** 还没选任何选项。 */
    Unanswered,

    /** 选了选项但**还没确认**（未判分）。 */
    Answered,

    /** 已判分且答对。 */
    Correct,

    /** 已判分且答错。 */
    Wrong
}

/** 答题卡上的一个格子。[index] 是从 0 起的下标（跳题用），[number] 是显示用的题号（从 1 起）。 */
data class AnswerSheetCell(
    val index: Int,
    val number: Int,
    val status: AnswerSheetStatus,
    val isCurrent: Boolean
)

/**
 * 判定单题状态。
 *
 * 判据顺序不能换：**未作答优先于一切** —— 背题模式下用户从不作答，此时
 * `judged` 恒为 false、`correct` 恒为 false，若先判 judged/correct 会把整张卡
 * 涂成"答错"。
 */
fun answerSheetStatus(answered: Boolean, judged: Boolean, correct: Boolean): AnswerSheetStatus =
    when {
        !answered -> AnswerSheetStatus.Unanswered
        !judged -> AnswerSheetStatus.Answered
        correct -> AnswerSheetStatus.Correct
        else -> AnswerSheetStatus.Wrong
    }

/** 答题卡的汇总数字。 */
data class AnswerSheetSummary(
    val total: Int,
    val answered: Int,
    val correct: Int,
    val wrong: Int
) {
    /** 已确认的题数（答对 + 答错）。 */
    val judged: Int get() = correct + wrong
}

/** 由格子列表汇总。 */
fun summarizeAnswerSheet(cells: List<AnswerSheetCell>): AnswerSheetSummary = AnswerSheetSummary(
    total = cells.size,
    // 「已答」包含"选了没确认"——用户想知道的是"我碰过多少题了"。
    answered = cells.count { it.status != AnswerSheetStatus.Unanswered },
    correct = cells.count { it.status == AnswerSheetStatus.Correct },
    wrong = cells.count { it.status == AnswerSheetStatus.Wrong }
)

/**
 * 从一道题在本次会话里的作答情况造出格子。
 *
 * [answered] / [judged] / [correct] 由调用方从 [PracticeSession] 取 ——
 * 本函数不持有会话，只做映射，因此可以脱离 Android 单测。
 */
fun answerSheetCell(
    index: Int,
    answered: Boolean,
    judged: Boolean,
    correct: Boolean,
    isCurrent: Boolean
): AnswerSheetCell = AnswerSheetCell(
    index = index,
    number = index + 1,
    status = answerSheetStatus(answered, judged, correct),
    isCurrent = isCurrent
)

/** 答题卡按章节分组后的一段。[chapter] 为空串表示"未分章"。 */
data class AnswerSheetSection(val chapter: String, val cells: List<AnswerSheetCell>)

/**
 * 把格子按章节分组，**保持原有顺序与下标**。
 *
 * 为什么要分组：内置题库动辄 300+ 题，一屏五六个格子要翻几十屏才能找到「第 7 章那道题」。
 * 分组之后至少知道自己在第几章。
 *
 * [chapters] 与 [cells] 必须等长且一一对应（调用方从 `session.questions` 直接映射）。
 * 分段顺序按**首次出现**排 —— 练习顺序（顺序/乱序/错题优先）才是用户心里那本账，
 * 这里再按章名重排一次反而会让答题卡和翻题顺序对不上。
 *
 * 章节名保持原样（含空白）：空白章在界面上显示为「未分章」，但键值不改写，
 * 否则同一章会被拆成两段。
 */
fun answerSheetSections(chapters: List<String>, cells: List<AnswerSheetCell>): List<AnswerSheetSection> {
    if (cells.isEmpty()) return emptyList()
    val order = LinkedHashMap<String, MutableList<AnswerSheetCell>>()
    cells.forEachIndexed { i, cell ->
        val key = chapters.getOrNull(i).orEmpty()
        order.getOrPut(key) { mutableListOf() }.add(cell)
    }
    return order.map { (chapter, list) -> AnswerSheetSection(chapter, list) }
}
