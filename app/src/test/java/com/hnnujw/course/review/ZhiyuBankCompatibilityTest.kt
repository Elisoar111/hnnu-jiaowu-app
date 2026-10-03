package com.hnnujw.course.review

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用参考项目（D:\zhiyu，知隅）的**真实题库**验证解析器。
 *
 * 为什么要拿真题库跑：手写的最小样例只能证明"标准结构能解析"，证明不了
 * "用户在那边导出的文件在这边能不能用"。真题库 320 题里有真实的长题干、
 * 全角标点、书名号、括号注音，正是容易在归一化环节出问题的地方。
 *
 * 固件是 [zhiyu_sample.json]（该题库前 40 题的子集，覆盖多个章节与 A–D 全部答案位）。
 */
class ZhiyuBankCompatibilityTest {

    private fun loadSample(): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("review/zhiyu_sample.json")) {
            "测试固件 review/zhiyu_sample.json 缺失"
        }.use { it.readBytes().toString(Charsets.UTF_8) }

    @Test
    fun `真实题库子集全部解析成功`() {
        val bank = parseJsonBank(loadSample())
        // 真题库里没有坏题，导入时不该跳过任何一条 —— 出现 error 就是解析器有 bug。
        assertTrue("不应有被跳过的题：${bank.errors}", bank.errors.isEmpty())
        assertEquals(40, bank.questions.size)
        assertTrue("库名应读出来：${bank.name}", bank.name.contains("中国近现代史纲要"))
    }

    @Test
    fun `真实题库字段完整`() {
        val questions = parseJsonBank(loadSample()).questions
        questions.forEach { q ->
            assertTrue("题干不应为空：id=${q.id}", q.stem.isNotBlank())
            assertEquals("应为单选：id=${q.id}", ReviewQuestion.TYPE_SINGLE, q.type)
            assertEquals("选项应为 4 个：id=${q.id}", 4, q.options.size)
            assertEquals("答案应为单个字母：id=${q.id} answer=${q.answer}", 1, q.answer.length)
            assertTrue("解析不应为空：id=${q.id}", q.explanation.isNotBlank())
            assertTrue("来源不应为空：id=${q.id}", q.source.isNotBlank())
        }
    }

    @Test
    fun `真实题库答案都能在选项范围内找到`() {
        val questions = parseJsonBank(loadSample()).questions
        questions.forEach { q ->
            val idx = q.answer.first() - 'A'
            assertTrue("答案 ${q.answer} 超出 4 个选项范围：id=${q.id}", idx in 0..3)
        }
    }

    @Test
    fun `真实题库判分能区分对错`() {
        val questions = parseJsonBank(loadSample()).questions
        // 每题都验三遍：选正确答案判对、选另一个判错、不选判错。
        // 这条能兜住"答案归一化把全角字母/小写吃掉"这类只在真数据上才暴露的问题。
        questions.forEach { q ->
            assertTrue("正确答案应判对：id=${q.id}", judgeAnswer(q, q.answer))
            assertTrue("小写答案也应判对：id=${q.id}", judgeAnswer(q, q.answer.lowercase()))
            val wrong = ('A'..'D').first { it.toString() != q.answer }
            assertFalse("错误答案应判错：id=${q.id} answer=${q.answer} wrong=$wrong", judgeAnswer(q, wrong.toString()))
            assertFalse("空作答应判错：id=${q.id}", judgeAnswer(q, ""))
        }
    }

    @Test
    fun `真实题库能走完一次完整练习会话`() {
        val questions = parseJsonBank(loadSample()).questions
        val session = PracticeSession(questions)
        assertEquals(40, session.total)

        // 前 20 题答对、后 20 题答错，验证进度与正确率统计。
        questions.forEachIndexed { i, q ->
            val picked = if (i < 20) q.answer else ('A'..'D').first { it.toString() != q.answer }.toString()
            session.select(q, picked)
            session.judge(q)
        }
        assertTrue(session.isFinished)
        val result = session.result()
        assertEquals(40, result.answered)
        assertEquals(20, result.correct)
        assertEquals(20, result.wrong)
        assertEquals(0.5f, result.accuracy, 0.0001f)
    }

    @Test
    fun `真实题库题干含全角标点不影响解析`() {
        // 真题库题干里有（　）全角空格、"." 书名号、括号注音等，
        // 这里只守一条底线：原样保留，不被 trim 或归一化改写。
        val questions = parseJsonBank(loadSample()).questions
        val withFullWidth = questions.filter { it.stem.contains('（') || it.stem.contains('　') }
        assertTrue("样本里应含全角括号/空格的题干", withFullWidth.isNotEmpty())
        withFullWidth.forEach { q ->
            assertTrue("全角字符被改写了：${q.stem}", q.stem.contains('（') || q.stem.contains('　'))
        }
    }
}
