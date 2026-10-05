package com.hnnujw.course.review

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 题库详情页纯逻辑的回归测试。
 *
 * 2026-10 章节改成**多选**后，本文件覆盖的东西也跟着换了：
 * 原先测的是「下标 → 章节名」的映射（`chapterOptions` / `resolveSelectedChapter`），
 * 多选之后选择状态的载体是 `Set<String>`，那套映射整个消失（连同 `ChapterOption`、
 * `modeIndex`、`modeAt` 三个函数一起删除，理由见 BankDetailLogic 的类注释）。
 *
 * 现在钉住的是多选本身的四条不变量：
 *  - 空集 = 全部章节（与原 `chapter == null` 语义逐字一致）；
 *  - 切换是**返回新集合**而不是原地改（Compose 靠引用变化重组，原地改界面不刷新）；
 *  - 题数汇总不能把"选中 3 章"报成"全库题数"；
 *  - 传进 SQL 的章节列表要滤掉空白项。
 */
class BankDetailLogicTest {

    private val chapters = listOf("绪论" to 10, "第一章" to 20, "第二章" to 5)

    // ── 多选切换 ─────────────────────────────────────────────────────

    @Test
    fun `未选中任何章节等于全部章节`() {
        assertEquals(ALL_CHAPTERS_LABEL, chapterSelectionLabel(emptySet()))
        assertTrue(resolveSelectedChapters(emptySet()).isEmpty())
    }

    @Test
    fun `切换返回新集合而不是原地修改`() {
        val before = setOf("第一章")
        val after = toggleChapterSelection(before, "第二章")
        assertEquals(setOf("第一章"), before)
        assertEquals(setOf("第一章", "第二章"), after)
    }

    @Test
    fun `再次点击同一章节即取消选中`() {
        val once = toggleChapterSelection(emptySet(), "第一章")
        assertEquals(setOf("第一章"), once)
        assertTrue(toggleChapterSelection(once, "第一章").isEmpty())
    }

    // ── 汇总 ─────────────────────────────────────────────────────────

    @Test
    fun `全部章节时题数是各章之和`() {
        assertEquals(35, selectedChapterQuestionCount(chapters, emptySet()))
    }

    @Test
    fun `多选时只累加选中的章节`() {
        // 🔴 这条正是"选中 2 章却报出全库题数"那个假数字的护栏。
        assertEquals(30, selectedChapterQuestionCount(chapters, setOf("绪论", "第一章")))
        assertEquals(5, selectedChapterQuestionCount(chapters, setOf("第二章")))
    }

    @Test
    fun `选中集合里有不存在的章节名时忽略它`() {
        // 章节被重算（题目被删/改名）后选择集合里可能残留旧名，不能因此算错题数。
        assertEquals(20, selectedChapterQuestionCount(chapters, setOf("第一章", "已删掉的章节")))
    }

    @Test
    fun `空题库时全部章节题数为零`() {
        assertEquals(0, selectedChapterQuestionCount(emptyList(), emptySet()))
    }

    // ── 标题 ─────────────────────────────────────────────────────────

    @Test
    fun `标题随选择变化`() {
        assertEquals(ALL_CHAPTERS_LABEL, chapterSelectionLabel(emptySet()))
        assertEquals("第一章", chapterSelectionLabel(setOf("第一章")))
        assertEquals("已选 2 章", chapterSelectionLabel(setOf("第一章", "第二章")))
    }

    // ── 传给 SQL 的列表 ───────────────────────────────────────────────

    @Test
    fun `解析出的章节列表滤掉空白项`() {
        // 空白串进 `chapter IN (?)` 只会白跑一趟，且会让"已选 N 章"虚高。
        assertEquals(listOf("第一章"), resolveSelectedChapters(setOf("第一章", "  ", "")))
    }

    @Test
    fun `解析出的章节列表保留全部有效项`() {
        val picked = resolveSelectedChapters(setOf("绪论", "第一章", "第二章"))
        assertEquals(setOf("绪论", "第一章", "第二章"), picked.toSet())
    }

    // ── 正确率 ───────────────────────────────────────────────────────

    @Test
    fun `正确率正常换算并四舍五入`() {
        assertEquals(50, accuracyPercent(correct = 1, answered = 2))
        assertEquals(67, accuracyPercent(correct = 2, answered = 3))
        assertEquals(100, accuracyPercent(correct = 5, answered = 5))
    }

    @Test
    fun `未作答时正确率为零而不是 NaN`() {
        assertEquals(0, accuracyPercent(correct = 0, answered = 0))
        assertEquals(0, accuracyPercent(correct = 3, answered = -1))
    }

    @Test
    fun `答对数超出已答数时被夹到范围`() {
        // 脏数据兜底：不允许出现 >100%。
        assertEquals(100, accuracyPercent(correct = 99, answered = 10))
    }
}
