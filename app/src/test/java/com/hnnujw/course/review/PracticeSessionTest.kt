package com.hnnujw.course.review

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 练习会话的单元测试。
 *
 * 这里守的是三条最容易在改动中被破坏的规则：
 *  1. 多选必须全对才算对（[judgeAnswer]）；
 *  2. 判过分之后不能偷偷改答案（否则正确率会被中途篡改）；
 *  3. 正确率只按已判分的题算，没做完不算错题。
 */
class PracticeSessionTest {

    private fun q(id: String, type: String = "single", answer: String = "A") =
        ReviewQuestion(
            id = id,
            type = type,
            stem = "题干$id",
            options = listOf("甲", "乙", "丙", "丁"),
            answer = answer,
            chapter = "章",
            bankId = "b1"
        )

    // —— 判分口径

    @Test
    fun `单选全对才算对`() {
        assertTrue(judgeAnswer(q("1"), "A"))
        assertFalse(judgeAnswer(q("1"), "B"))
    }

    @Test
    fun `多选必须完全一致`() {
        val multi = q("2", ReviewQuestion.TYPE_MULTI, "ABD")
        assertTrue(judgeAnswer(multi, "ABD"))
        assertTrue(judgeAnswer(multi, "DBA")) // 顺序不影响
        assertFalse(judgeAnswer(multi, "AB"))   // 少选
        assertFalse(judgeAnswer(multi, "ABCD")) // 多选
        assertFalse(judgeAnswer(multi, "ABC"))  // 错选
    }

    @Test
    fun `多选乱序作答仍算对`() {
        // 回归：曾经直接比较归一化后的字符串，用户按 D→B→A 顺序点选
        // 就会把"选全了"误判成错。两边都必须排序后再比。
        val multi = q("2", ReviewQuestion.TYPE_MULTI, "ABD")
        val s = PracticeSession(listOf(multi))
        val question = s.questions[0]
        s.select(question, "D")
        s.select(question, "A")
        s.select(question, "B")
        assertEquals("ABD", s.answerOf(question))
        assertTrue(s.judge(question))
    }

    @Test
    fun `空作答一律算错`() {
        assertFalse(judgeAnswer(q("1"), ""))
        assertFalse(judgeAnswer(q("1", ReviewQuestion.TYPE_MULTI, "AB"), ""))
    }

    @Test
    fun `作答小写与含杂字符也能判对`() {
        assertTrue(judgeAnswer(q("1"), "a"))
        assertTrue(judgeAnswer(q("1", ReviewQuestion.TYPE_MULTI, "AB"), "a、b"))
    }

    // —— 选择

    @Test
    fun `单选重复点击同一项是覆盖不是追加`() {
        val s = PracticeSession(listOf(q("1")))
        val question = s.questions[0]
        s.select(question, "B")
        s.select(question, "B")
        assertEquals("B", s.answerOf(question))
    }

    @Test
    fun `多选点击切换该字母并保持有序`() {
        val s = PracticeSession(listOf(q("2", ReviewQuestion.TYPE_MULTI, "ABD")))
        val question = s.questions[0]
        s.select(question, "D")
        s.select(question, "B")
        assertEquals("BD", s.answerOf(question))
        s.select(question, "B")
        assertEquals("D", s.answerOf(question))
    }

    // —— 判分与锁定

    @Test
    fun `未作答不能判分`() {
        val s = PracticeSession(listOf(q("1")))
        val question = s.questions[0]
        assertFalse(s.judge(question))
        assertFalse(s.isJudged(question))
    }

    @Test
    fun `判分后禁止改答案`() {
        val s = PracticeSession(listOf(q("1")))
        val question = s.questions[0]
        s.select(question, "B")
        assertFalse(s.judge(question))
        assertFalse(s.select(question, "A"))
        assertEquals("B", s.answerOf(question))
    }

    @Test
    fun `撤回判定后可以重新作答`() {
        val s = PracticeSession(listOf(q("1")))
        val question = s.questions[0]
        s.select(question, "B")
        s.judge(question)
        s.unjudge(question)
        assertFalse(s.isJudged(question))
        assertTrue(s.select(question, "A"))
        assertTrue(s.judge(question))
    }

    @Test
    fun `重复判分结果稳定`() {
        val s = PracticeSession(listOf(q("1")))
        val question = s.questions[0]
        s.select(question, "A")
        assertTrue(s.judge(question))
        assertTrue(s.judge(question))
        assertEquals(1, s.judgedCount)
    }

    // —— 进度与结果

    @Test
    fun `正确率只按已判分的题计算`() {
        val qs = listOf(q("1"), q("2", answer = "B"), q("3"))
        val s = PracticeSession(qs)
        s.select(qs[0], "A")
        s.judge(qs[0])          // 对
        s.select(qs[1], "A")
        s.judge(qs[1])          // 错
        // 第三题没做
        assertEquals(2, s.judgedCount)
        assertEquals(0.5f, s.accuracy, 0.0001f)
        val r = s.result()
        assertEquals(3, r.total)
        assertEquals(2, r.answered)
        assertEquals(1, r.correct)
        assertEquals(1, r.wrong)
        assertEquals(1, r.unjudged)
    }

    @Test
    fun `全部判完才算结束`() {
        val qs = listOf(q("1"), q("2"))
        val s = PracticeSession(qs)
        assertFalse(s.isFinished)
        qs.forEach { s.select(it, "A"); s.judge(it) }
        assertTrue(s.isFinished)
    }

    @Test
    fun `空题库不视为已完成`() {
        val s = PracticeSession(emptyList())
        assertTrue(s.isEmpty)
        assertFalse(s.isFinished)
        assertEquals(0f, s.accuracy, 0.0001f)
    }

    @Test
    fun `不同题库的同 id 题目互不干扰`() {
        val a = ReviewQuestion("1", ReviewQuestion.TYPE_SINGLE, "s", listOf("甲", "乙"), "A", "c", bankId = "bankA")
        val b = ReviewQuestion("1", ReviewQuestion.TYPE_SINGLE, "s", listOf("甲", "乙"), "B", "c", bankId = "bankB")
        val s = PracticeSession(listOf(a, b))
        s.select(a, "A")
        s.judge(a)
        s.select(b, "B")
        s.judge(b)
        assertEquals(2, s.judgedCount)
        assertEquals(1.0f, s.accuracy, 0.0001f)
    }

    @Test
    fun `清空回到初始状态`() {
        val qs = listOf(q("1"))
        val s = PracticeSession(qs)
        s.select(qs[0], "A")
        s.judge(qs[0])
        s.clear()
        assertEquals(0, s.answeredCount)
        assertEquals(0, s.judgedCount)
        assertEquals("", s.answerOf(qs[0]))
    }
}
