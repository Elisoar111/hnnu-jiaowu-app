package com.hnnujw.course.review

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 间隔复习（到期）纯逻辑的单元测试。
 *
 * 到期判断是"今天该看哪些"的唯一依据，错了用户要么被淹没（全到期）、要么漏复习（全不到期），
 * 所以边界必须离线钉死：恰好到期算到期、差一毫秒不算、已过期仍算、未排期（due_at=0）
 * 恒到期、各等级间隔逐档校验、count 的汇总口径。
 */
class ReviewSchedulingTest {

    private val now = 1_700_000_000_000L
    private val day = 24L * 60L * 60L * 1000L

    // ── masteryLevelOf ──────────────────────────────────────────────

    @Test
    fun `连对三为已掌握`() {
        assertEquals(MasteryLevel.Mastered, masteryLevelOf(streak = 3, wrongCount = 0))
        assertEquals(MasteryLevel.Mastered, masteryLevelOf(streak = 5, wrongCount = 0))
    }

    @Test
    fun `连对二为较熟`() {
        assertEquals(MasteryLevel.Familiar, masteryLevelOf(streak = 2, wrongCount = 1))
    }

    @Test
    fun `连对一为学习中`() {
        assertEquals(MasteryLevel.Learning, masteryLevelOf(streak = 1, wrongCount = 1))
    }

    @Test
    fun `无连对无错题为新题`() {
        assertEquals(MasteryLevel.New, masteryLevelOf(streak = 0, wrongCount = 0))
    }

    @Test
    fun `无连对但错过为学习中`() {
        assertEquals(MasteryLevel.Learning, masteryLevelOf(streak = 0, wrongCount = 3))
    }

    @Test
    fun `连对优先于错误次数`() {
        // 错很多次但连对到 3，就是已掌握 —— 近况优先于历史。
        assertEquals(MasteryLevel.Mastered, masteryLevelOf(streak = 3, wrongCount = 100))
    }

    // ── reviewIntervalDays ──────────────────────────────────────────

    @Test
    fun `新题立即到期间隔为零`() {
        assertEquals(0, reviewIntervalDays(MasteryLevel.New))
    }

    @Test
    fun `学习中一天`() {
        assertEquals(1, reviewIntervalDays(MasteryLevel.Learning))
    }

    @Test
    fun `较熟三天`() {
        assertEquals(3, reviewIntervalDays(MasteryLevel.Familiar))
    }

    @Test
    fun `已掌握七天`() {
        assertEquals(7, reviewIntervalDays(MasteryLevel.Mastered))
    }

    @Test
    fun `间隔随掌握单调不减`() {
        val ordered = listOf(
            MasteryLevel.New, MasteryLevel.Learning, MasteryLevel.Familiar, MasteryLevel.Mastered
        ).map { reviewIntervalDays(it) }
        assertEquals(ordered.sorted(), ordered)
    }

    // ── dueAt ───────────────────────────────────────────────────────

    @Test
    fun `从未复习过的题立即到期`() {
        // lastAt <= 0 表示没排过期，due_at 给 0（isDue 会恒true）。
        assertEquals(0L, dueAt(MasteryLevel.Learning, 0L))
        assertEquals(0L, dueAt(MasteryLevel.Mastered, -1L))
    }

    @Test
    fun `到期时刻等于上次复习加间隔天`() {
        assertEquals(now + 1 * day, dueAt(MasteryLevel.Learning, now))
        assertEquals(now + 3 * day, dueAt(MasteryLevel.Familiar, now))
        assertEquals(now + 7 * day, dueAt(MasteryLevel.Mastered, now))
    }

    @Test
    fun `新题到期时刻等于当下`() {
        assertEquals(now, dueAt(MasteryLevel.New, now))
    }

    // ── isDue ───────────────────────────────────────────────────────

    @Test
    fun `未排期为立即到期`() {
        assertTrue(isDue(0L, now))
        assertTrue(isDue(-100L, now))
    }

    @Test
    fun `恰好在到期时刻算到期`() {
        // 不留一秒的缝：now == dueAt 就是到期。
        assertTrue(isDue(now, now))
    }

    @Test
    fun `已过期仍算到期`() {
        assertTrue(isDue(now - 1, now))
        assertTrue(isDue(now - 10 * day, now))
    }

    @Test
    fun `还差一毫秒不算到期`() {
        assertFalse(isDue(now + 1, now))
    }

    @Test
    fun `未来到期不算到期`() {
        assertFalse(isDue(now + 3 * day, now))
    }

    // ── dueCount ────────────────────────────────────────────────────

    @Test
    fun `到期计数只数到期的`() {
        val items = listOf(
            0L,                 // 未排期 → 到期
            now,                // 恰好到期 → 到期
            now - 1,            // 已过期 → 到期
            now + 1,            // 差一点 → 不到期
            now + 7 * day       // 未来 → 不到期
        )
        assertEquals(3, dueCount(items, now))
    }

    @Test
    fun `空列表到期数为零`() {
        assertEquals(0, dueCount(emptyList(), now))
    }

    @Test
    fun `全部未排期时全部到期`() {
        assertEquals(4, dueCount(listOf(0L, 0L, 0L, 0L), now))
    }

    @Test
    fun `全部未来时零到期`() {
        assertEquals(0, dueCount(listOf(now + day, now + 2 * day), now))
    }

    // ── 端到端：入册 → 答对 → 到期时间推进 ───────────────────────────

    @Test
    fun `答错入册后按学习中排一天后复习`() {
        // 模拟入册那一刻：streak=0, wrongCount=1 → Learning → due = now + 1 天。
        val level = masteryLevelOf(streak = 0, wrongCount = 1)
        val due = dueAt(level, now)
        assertFalse("当天不该到期", isDue(due, now))
        assertTrue("一天后到期", isDue(due, now + day))
    }

    @Test
    fun `连对到已掌握后间隔拉到七天`() {
        val level = masteryLevelOf(streak = 3, wrongCount = 1)
        assertEquals(MasteryLevel.Mastered, level)
        val due = dueAt(level, now)
        assertFalse(isDue(due, now + 3 * day))
        assertTrue(isDue(due, now + 7 * day))
    }
}
