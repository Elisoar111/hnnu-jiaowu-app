package com.hnnujw.course.review

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「提交」后的总结页全靠这一层算数字与文案。
 *
 * 三条最容易写错、而且**不崩不报错**的东西被钉在这里：
 *  1. **正确率的分母**。`PracticeResult.answered` 是"碰过的题"（含"选了但没确认"），
 *     而界面副标题用的是"已判分的题"。混用会出现"顶部 67%、总结页 40%"这种
 *     自相矛盾的数字。
 *  2. **未作答不算错**。到点自动交卷时会有大片未作答的题，把它们算进分母等于
 *     惩罚"没来得及做"，正确率会毫无意义地塌下去。
 *  3. **鼓励语不能撒谎**。「一题都没答」和「全错」是两件事，不能都给一句"别灰心"；
 *     边界（85 还是 80）写错没人看得出来，所以逐个钉住。
 */
class PracticeSummaryTest {

    private fun summary(
        total: Int = 10,
        correct: Int = 8,
        wrong: Int = 2,
        unjudged: Int = 0,
        elapsedSeconds: Int = 0,
        durationSeconds: Int = 0,
        timedOut: Boolean = false
    ) = PracticeSummary(
        total = total,
        correct = correct,
        wrong = wrong,
        unjudged = unjudged,
        elapsedSeconds = elapsedSeconds,
        durationSeconds = durationSeconds,
        timedOut = timedOut
    )

    // ── 数字口径 ────────────────────────────────────────────────────────────

    @Test
    fun `正确率的分母是已判分而不是总题数`() {
        // 10 题里只判了 4 题（3 对 1 错），另外 6 题没作答：正确率应当是 75%，不是 30%。
        val s = summary(total = 10, correct = 3, wrong = 1, unjudged = 6)
        assertEquals(4, s.judged)
        assertEquals(75, s.accuracyPercent)
    }

    @Test
    fun `一题都没判分时正确率为零且不抛`() {
        val s = summary(total = 10, correct = 0, wrong = 0, unjudged = 10)
        assertEquals(0, s.judged)
        assertEquals(0, s.accuracyPercent)
    }

    @Test
    fun `用时按墙钟差值算并夹取到非负`() {
        assertEquals(5, elapsedSeconds(startedAt = 1_000_000L, nowMillis = 1_005_000L))
        // 开始时刻未设定：不显示负数、也不当成"刚跑了 20 亿秒"。
        assertEquals(0, elapsedSeconds(startedAt = 0L, nowMillis = 1_005_000L))
        // 时钟倒退（用户手动改了系统时间）：按 0 处理。
        assertEquals(0, elapsedSeconds(startedAt = 2_000_000L, nowMillis = 1_000_000L))
    }

    @Test
    fun `用时文案与倒计时同一套格式`() {
        assertEquals("00:05", summary(elapsedSeconds = 5).elapsedLabel)
        assertEquals("05:09", summary(elapsedSeconds = 309).elapsedLabel)
        assertEquals("1:00:00", summary(elapsedSeconds = 3600).elapsedLabel)
    }

    @Test
    fun `汇总行只在确实有未作答时才提未作答`() {
        assertEquals("共 10 题 · 答对 8 · 答错 2", summary().tallyLabel)
        assertEquals("共 10 题 · 答对 8 · 答错 2 · 未作答 3", summary(unjudged = 3).tallyLabel)
    }

    @Test
    fun `考试时长按分钟向上取整且不限时为零`() {
        assertEquals(0, summary(durationSeconds = 0).durationMinutes)
        assertEquals(30, summary(durationSeconds = 1800).durationMinutes)
        // 90 秒的"考试"不该显示成 1 分钟以下，也不该显示成 0。
        assertEquals(2, summary(durationSeconds = 90).durationMinutes)
        assertFalse(summary(durationSeconds = 0).timed)
        assertTrue(summary(durationSeconds = 60).timed)
    }

    @Test
    fun `标题区分练习 考试与超时交卷`() {
        assertEquals("练习完成", summary().title)
        assertEquals("考试结束", summary(durationSeconds = 1800).title)
        assertEquals("考试时间到", summary(durationSeconds = 1800, timedOut = true).title)
    }

    // ── practiceSummaryOf 的映射 ────────────────────────────────────────────

    @Test
    fun `由会话结果映射时逐项对应且负数被夹取`() {
        val s = practiceSummaryOf(
            result = PracticeResult(total = 10, answered = 9, correct = 7, wrong = 2, unjudged = 1),
            elapsedSeconds = -5,
            durationSeconds = -60,
            timedOut = false
        )
        assertEquals(10, s.total)
        assertEquals(7, s.correct)
        assertEquals(2, s.wrong)
        assertEquals(1, s.unjudged)
        assertEquals(0, s.elapsedSeconds)
        assertEquals(0, s.durationSeconds)
        assertFalse(s.timed)
    }

    @Test
    fun `选了但没确认的题不进入正确率分母`() {
        // 关键差异：answered=9（碰过 9 题）但只判了 2 题。总结页按 2 题算。
        val s = practiceSummaryOf(
            result = PracticeResult(total = 10, answered = 9, correct = 1, wrong = 1, unjudged = 8),
            elapsedSeconds = 10
        )
        assertEquals(2, s.judged)
        assertEquals(50, s.accuracyPercent)
    }

    // ── 鼓励语分档 ──────────────────────────────────────────────────────────

    @Test
    fun `分档边界逐个钉住`() {
        val total = 100
        assertEquals(encouragementFor(95, 95, total), encouragementFor(100, 100, total))
        assertTrue(encouragementFor(95, 95, total) != encouragementFor(94, 94, total))
        assertTrue(encouragementFor(85, 85, total) != encouragementFor(84, 84, total))
        assertTrue(encouragementFor(70, 70, total) != encouragementFor(69, 69, total))
        assertTrue(encouragementFor(50, 50, total) != encouragementFor(49, 49, total))
        assertTrue(encouragementFor(1, 1, total) != encouragementFor(0, 1, total))
    }

    @Test
    fun `一题都没答与全错给的不是同一句话`() {
        val none = encouragementFor(accuracyPercent = 0, judged = 0, total = 10)
        val allWrong = encouragementFor(accuracyPercent = 0, judged = 10, total = 10)
        assertTrue(none != allWrong)
        // 一题都没答时不能按"全错"的口径说"下一轮一定比这一轮强"。
        assertTrue(none.contains("还没有作答"))
    }

    @Test
    fun `没有题目时给出中性文案`() {
        assertTrue(encouragementFor(accuracyPercent = 0, judged = 0, total = 0).contains("没有题目"))
    }

    @Test
    fun `每档鼓励语都非空且不带 Markdown 标记`() {
        // 总结页用的是普通 Text（不是 MarkdownText），文案里混进 `**` 会原样露出来。
        val cases = listOf(
            encouragementFor(0, 0, 10),
            encouragementFor(0, 10, 10),
            encouragementFor(30, 10, 10),
            encouragementFor(55, 10, 10),
            encouragementFor(75, 10, 10),
            encouragementFor(88, 10, 10),
            encouragementFor(100, 10, 10),
            encouragementFor(0, 0, 0)
        )
        cases.forEach { text ->
            assertTrue("鼓励语不该为空", text.isNotBlank())
            assertFalse("鼓励语不该带 Markdown 标记：$text", text.contains('*'))
        }
        // 七档 + 无题目，一共应当有 8 句互不相同的话。
        assertEquals(8, cases.toSet().size)
    }
}
