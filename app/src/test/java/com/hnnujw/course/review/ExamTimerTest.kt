package com.hnnujw.course.review

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 限时考试计时的纯函数测试。
 *
 * 倒计时这种东西"上真机等到点"几乎没法回归（等 20 分钟看它自不自动交卷），
 * 所以判据全部抽成不读墙钟的纯函数，在这里把边界钉死：归零夹取、mm:ss / h:mm:ss
 * 的进位点、两条警告各只发一次、不限时端到端不发任何动作。
 */
class ExamTimerTest {

    // ── remainingSeconds ─────────────────────────────────────────────

    @Test
    fun `未开始时剩余等于总时长`() {
        assertEquals(600, remainingSeconds(600, 0))
    }

    @Test
    fun `用掉一部分后剩余按差值`() {
        assertEquals(540, remainingSeconds(600, 60))
        assertEquals(1, remainingSeconds(600, 599))
    }

    @Test
    fun `到点与超时都夹取到零不会出现负数`() {
        assertEquals(0, remainingSeconds(600, 600))
        assertEquals(0, remainingSeconds(600, 601))
        assertEquals(0, remainingSeconds(600, 100_000))
    }

    @Test
    fun `elapsed 为负按未开始处理`() {
        // 时钟回拨等极端情况下，elapsed 可能是负的，不能算成"还剩更多"。
        assertEquals(600, remainingSeconds(600, -5))
    }

    @Test
    fun `不限时返回极大值`() {
        assertEquals(Int.MAX_VALUE, remainingSeconds(0, 0))
        assertEquals(Int.MAX_VALUE, remainingSeconds(0, 999))
        assertEquals(Int.MAX_VALUE, remainingSeconds(-1, 100))
    }

    // ── formatRemaining ──────────────────────────────────────────────

    @Test
    fun `不足一分钟显示两位秒`() {
        assertEquals("00:59", formatRemaining(59))
        assertEquals("00:05", formatRemaining(5))
        assertEquals("00:00", formatRemaining(0))
    }

    @Test
    fun `整分钟进位到 01-00`() {
        assertEquals("01:00", formatRemaining(60))
        assertEquals("01:01", formatRemaining(61))
    }

    @Test
    fun `接近一小时仍是 mm-ss`() {
        assertEquals("59:59", formatRemaining(3599))
    }

    @Test
    fun `满一小时改用 h-mm-ss 且小时不补零`() {
        assertEquals("1:00:00", formatRemaining(3600))
        assertEquals("1:01:01", formatRemaining(3661))
        assertEquals("2:00:00", formatRemaining(7200))
    }

    @Test
    fun `不限时显示占位符`() {
        assertEquals("--:--", formatRemaining(Int.MAX_VALUE))
    }

    @Test
    fun `负数按零格式化不出现负号`() {
        assertEquals("00:00", formatRemaining(-1))
        assertEquals("00:00", formatRemaining(-999))
    }

    // ── examTimeouts ─────────────────────────────────────────────────

    @Test
    fun `归零触发交卷且只此一条`() {
        val actions = examTimeouts(0, warnedFive = false, warnedOne = false)
        assertEquals(listOf(ExamTimeoutAction.Submit), actions)
    }

    @Test
    fun `负数剩余同样触发交卷`() {
        assertEquals(listOf(ExamTimeoutAction.Submit), examTimeouts(-3, false, false))
    }

    @Test
    fun `剩 300 秒发五分钟警告`() {
        val actions = examTimeouts(300, warnedFive = false, warnedOne = false)
        assertTrue(ExamTimeoutAction.WarnFiveMinutes in actions)
        assertFalse(ExamTimeoutAction.WarnOneMinute in actions)
        assertFalse(ExamTimeoutAction.Submit in actions)
    }

    @Test
    fun `剩 301 秒不警告`() {
        assertTrue(examTimeouts(301, false, false).isEmpty())
    }

    @Test
    fun `五分钟警告已发过则不再发`() {
        val actions = examTimeouts(280, warnedFive = true, warnedOne = false)
        assertFalse(ExamTimeoutAction.WarnFiveMinutes in actions)
        // 还没到 1 分钟，也不该发 1 分钟警告
        assertTrue(actions.isEmpty())
    }

    @Test
    fun `剩 60 秒发一分钟警告`() {
        val actions = examTimeouts(60, warnedFive = true, warnedOne = false)
        assertTrue(ExamTimeoutAction.WarnOneMinute in actions)
        assertFalse(ExamTimeoutAction.Submit in actions)
    }

    @Test
    fun `一分钟警告已发过则不再发`() {
        assertTrue(examTimeouts(30, warnedFive = true, warnedOne = true).isEmpty())
    }

    @Test
    fun `一次性跨过两档会同时给出两条警告`() {
        // 后台回来直接剩 30 秒：5 分钟与 1 分钟两档都没发过，两条都该补上。
        val actions = examTimeouts(30, warnedFive = false, warnedOne = false)
        assertEquals(
            listOf(ExamTimeoutAction.WarnFiveMinutes, ExamTimeoutAction.WarnOneMinute),
            actions
        )
    }

    @Test
    fun `不限时端到端不产生任何动作`() {
        val remaining = remainingSeconds(0, 12345)
        assertTrue(examTimeouts(remaining, warnedFive = false, warnedOne = false).isEmpty())
    }

    // ── PracticeSession 集成：超时交卷 ───────────────────────────────

    private fun question(id: String, answer: String = "A") = ReviewQuestion(
        bankId = "b1",
        id = id,
        type = ReviewQuestion.TYPE_SINGLE,
        stem = "题干$id",
        options = listOf("甲", "乙", "丙", "丁"),
        answer = answer,
        chapter = "第一章",
        explanation = ""
    )

    @Test
    fun `不限时会话永不判为过期`() {
        val session = PracticeSession(
            listOf(question("1")),
            timing = ExamTiming(0),
            startedAt = 1_000L
        )
        assertFalse(session.isExpired(1_000L))
        assertFalse(session.isExpired(99_999_999L))
    }

    @Test
    fun `未设定开始时刻不会误判为立即过期`() {
        // startedAt = 0（默认）时应视作"未开始"，不能因为墙钟是个大数就直接交卷。
        val session = PracticeSession(
            listOf(question("1")),
            timing = ExamTiming(600),
            startedAt = 0L
        )
        assertFalse(session.isExpired(System.currentTimeMillis()))
    }

    @Test
    fun `限时会话在时长边界前后正确切换`() {
        val session = PracticeSession(
            listOf(question("1")),
            timing = ExamTiming(60),
            startedAt = 1_000L
        )
        // 59.9 秒：还没到
        assertFalse(session.isExpired(1_000L + 59_900L))
        // 整 60 秒：到点
        assertTrue(session.isExpired(1_000L + 60_000L))
        // 超过：仍为过期
        assertTrue(session.isExpired(1_000L + 61_000L))
    }

    @Test
    fun `超时交卷把未判分的题计为未作答`() {
        val session = PracticeSession(
            listOf(question("1"), question("2"), question("3")),
            timing = ExamTiming(60),
            startedAt = 1_000L
        )
        // 只答对并判分第 1 题，其余动都没动。
        session.select(session.questions[0], "A")
        session.judge(session.questions[0])

        val result = session.resultForTimeout()
        assertEquals(3, result.total)
        assertEquals(1, result.answered)
        assertEquals(1, result.correct)
        assertEquals(0, result.wrong)
        // 未判分的两题计为未作答 —— 不算错，正确率分母仍是已判分的 1 题。
        assertEquals(2, result.unjudged)
        assertEquals(1f, result.accuracy, 0.0001f)
    }

    @Test
    fun `超时交卷与正常交卷对已判分题结果一致`() {
        val session = PracticeSession(
            listOf(question("1"), question("2")),
            timing = ExamTiming(60),
            startedAt = 1_000L
        )
        session.select(session.questions[0], "A")
        session.judge(session.questions[0])
        session.select(session.questions[1], "B")
        val second = session.judge(session.questions[1])

        assertEquals(false, second) // 第 2 题答错
        assertEquals(session.result(), session.resultForTimeout())
        assertEquals(1, session.resultForTimeout().correct)
        assertEquals(1, session.resultForTimeout().wrong)
        assertEquals(0, session.resultForTimeout().unjudged)
    }

    @Test
    fun `ExamDurationOptions 首档为不限时且秒数一致`() {
        assertEquals("不限时", ExamDurationOptions.first().label)
        assertEquals(0, ExamDurationOptions.first().seconds)
        assertEquals(20 * 60, ExamDurationOptions[1].seconds)
        assertEquals(30 * 60, ExamDurationOptions[2].seconds)
        assertEquals(60 * 60, ExamDurationOptions[3].seconds)
    }
}
