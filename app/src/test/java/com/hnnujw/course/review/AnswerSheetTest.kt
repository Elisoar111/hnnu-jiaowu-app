package com.hnnujw.course.review

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 答题卡纯逻辑的回归测试。
 *
 * 为什么要专门测"颜色"这种看起来纯展示的东西：答题卡上格子的颜色就是"这题怎么样了"，
 * 判错之后用户会照着错的颜色去跳题（比如把"选了没提交"看成"已答对"，
 * 于是跳过一整片根本没提交的题）。这类错误不崩不报错，只能靠单测把边界钉住。
 *
 * 四态的顺序也是被测的一部分：本应用是"先选再确认"的两步作答，所以
 * 「选了没提交」与「确认过」必须分开。
 */
class AnswerSheetTest {

    @Test
    fun `未作答`() {
        assertEquals(
            AnswerSheetStatus.Unanswered,
            answerSheetStatus(answered = false, judged = false, correct = false)
        )
    }

    @Test
    fun `选了但没确认是中间态`() {
        // 关键：判为"已答"而不是"答对/答错" —— 还没提交，既不算对也不算错。
        assertEquals(
            AnswerSheetStatus.Answered,
            answerSheetStatus(answered = true, judged = false, correct = false)
        )
    }

    @Test
    fun `已判分答对`() {
        assertEquals(
            AnswerSheetStatus.Correct,
            answerSheetStatus(answered = true, judged = true, correct = true)
        )
    }

    @Test
    fun `已判分答错`() {
        assertEquals(
            AnswerSheetStatus.Wrong,
            answerSheetStatus(answered = true, judged = true, correct = false)
        )
    }

    @Test
    fun `未作答优先于判分结果`() {
        // 🔴 背题模式的真实取值就是这三个 false —— 用户从不作答，也从不判分。
        // 若判据顺序写成"先看 judged/correct"，整张卡会被涂成"答错"。
        assertEquals(
            AnswerSheetStatus.Unanswered,
            answerSheetStatus(answered = false, judged = true, correct = false)
        )
        assertEquals(
            AnswerSheetStatus.Unanswered,
            answerSheetStatus(answered = false, judged = true, correct = true)
        )
    }

    @Test
    fun `格子题号从 1 起 下标从 0 起`() {
        val cell = answerSheetCell(index = 0, answered = false, judged = false, correct = false, isCurrent = true)
        assertEquals(0, cell.index)
        assertEquals(1, cell.number)
        assertEquals(true, cell.isCurrent)

        val tenth = answerSheetCell(index = 9, answered = false, judged = false, correct = false, isCurrent = false)
        assertEquals(9, tenth.index)
        assertEquals(10, tenth.number)
    }

    // ── 汇总 ─────────────────────────────────────────────────────────

    private fun cell(index: Int, status: AnswerSheetStatus) =
        AnswerSheetCell(index = index, number = index + 1, status = status, isCurrent = false)

    @Test
    fun `汇总分别数出已答 对 错`() {
        val cells = listOf(
            cell(0, AnswerSheetStatus.Correct),
            cell(1, AnswerSheetStatus.Wrong),
            cell(2, AnswerSheetStatus.Answered),
            cell(3, AnswerSheetStatus.Unanswered),
            cell(4, AnswerSheetStatus.Unanswered)
        )
        val summary = summarizeAnswerSheet(cells)
        assertEquals(5, summary.total)
        // 「已答」包含"选了没提交"：用户想知道的是"我碰过多少题了"。
        assertEquals(3, summary.answered)
        assertEquals(1, summary.correct)
        assertEquals(1, summary.wrong)
        // 「已确认」只数判过分的。
        assertEquals(2, summary.judged)
    }

    @Test
    fun `空答题卡汇总全为零`() {
        val summary = summarizeAnswerSheet(emptyList())
        assertEquals(0, summary.total)
        assertEquals(0, summary.answered)
        assertEquals(0, summary.judged)
    }

    @Test
    fun `未作答的题不计入对错`() {
        val summary = summarizeAnswerSheet(listOf(cell(0, AnswerSheetStatus.Unanswered)))
        assertEquals(0, summary.answered)
        assertEquals(0, summary.correct)
        assertEquals(0, summary.wrong)
    }
}
