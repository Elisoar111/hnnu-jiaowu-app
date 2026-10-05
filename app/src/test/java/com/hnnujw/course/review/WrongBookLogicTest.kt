package com.hnnujw.course.review

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 错题本列表逻辑（筛选 / 排序 / 汇总 / 到期文案）的单元测试。
 *
 * 这些判据原先只存在于"点着看"的界面里，抽出成纯函数后必须逐条钉死 ——
 * 尤其是两条容易写错的：
 *  1. 汇总里的「今日到期」**不能**把已掌握的题算进去（否则与 `dueWrongQuestions`
 *     的 SQL 条件 `mastered = 0` 不一致，入口角标与列表条数会对不上）；
 *  2. `due_at = 0`（老数据 / 从未排期）必须算作**立即到期**，否则老用户的错题
 *     会被永久埋在队列里。
 */
class WrongBookLogicTest {

    private fun q(id: String) = ReviewQuestion(
        id = id,
        type = ReviewQuestion.TYPE_SINGLE,
        stem = "题干 $id",
        options = listOf("甲", "乙"),
        answer = "A",
        chapter = "第一章",
        bankId = "bank-1"
    )

    private fun entry(
        id: String,
        wrongCount: Int = 1,
        mastered: Boolean = false,
        streak: Int = 0,
        dueAt: Long = 0L,
        lastWrongAt: Long = 0L
    ) = ReviewDatabase.WrongBookEntry(
        question = q(id),
        wrongCount = wrongCount,
        mastered = mastered,
        streak = streak,
        dueAt = dueAt,
        lastWrongAt = lastWrongAt
    )

    // ── 筛选 ─────────────────────────────────────────────────────────

    @Test
    fun `待复习口径排除已掌握`() {
        val list = listOf(
            entry("a", wrongCount = 1),
            entry("b", wrongCount = 3),
            entry("c", mastered = true, streak = 3)
        )
        val ids = filterWrongEntries(list, WrongStatusFilter.Active).map { it.question.id }
        assertEquals(listOf("a", "b"), ids)
    }

    @Test
    fun `未掌握档只留错两次以上的`() {
        val list = listOf(
            entry("a", wrongCount = 1),
            entry("b", wrongCount = 2),
            entry("c", wrongCount = 9)
        )
        val ids = filterWrongEntries(list, WrongStatusFilter.NotMastered).map { it.question.id }
        assertEquals(listOf("b", "c"), ids)
    }

    @Test
    fun `全部档一个不漏`() {
        val list = listOf(entry("a"), entry("b", mastered = true, streak = 3))
        assertEquals(2, filterWrongEntries(list, WrongStatusFilter.All).size)
    }

    @Test
    fun `筛选不改动入参顺序`() {
        val list = listOf(entry("a"), entry("b"), entry("c"))
        assertEquals(listOf("a", "b", "c"), filterWrongEntries(list, WrongStatusFilter.All).map { it.question.id })
    }

    // ── 排序 ─────────────────────────────────────────────────────────

    @Test
    fun `错误最多按次数降序`() {
        val list = listOf(entry("a", wrongCount = 1), entry("b", wrongCount = 7), entry("c", wrongCount = 3))
        val ids = sortWrongEntries(list, WrongSort.WrongCount).map { it.question.id }
        assertEquals(listOf("b", "c", "a"), ids)
    }

    @Test
    fun `最近答错按时间降序`() {
        val list = listOf(
            entry("a", lastWrongAt = 100L),
            entry("b", lastWrongAt = 900L),
            entry("c", lastWrongAt = 500L)
        )
        val ids = sortWrongEntries(list, WrongSort.LastWrong).map { it.question.id }
        assertEquals(listOf("b", "c", "a"), ids)
    }

    @Test
    fun `同键时用错误次数兜底保证顺序稳定`() {
        // 时间相同 → 错得多的排前面。没有这条兜底时顺序由输入顺序决定，
        // 用户会看到同一批数据每次进页面顺序都不一样。
        val list = listOf(
            entry("a", wrongCount = 1, lastWrongAt = 100L),
            entry("b", wrongCount = 5, lastWrongAt = 100L)
        )
        val ids = sortWrongEntries(list, WrongSort.LastWrong).map { it.question.id }
        assertEquals(listOf("b", "a"), ids)
    }

    @Test
    fun `按掌握状态排序是复习中-未掌握-已掌握`() {
        val list = listOf(
            entry("m", mastered = true, streak = 3),
            entry("n", wrongCount = 4),
            entry("r", wrongCount = 1)
        )
        val ids = sortWrongEntries(list, WrongSort.Status).map { it.question.id }
        assertEquals(listOf("r", "n", "m"), ids)
    }

    @Test
    fun `排序不改动原列表`() {
        val list = listOf(entry("a", wrongCount = 1), entry("b", wrongCount = 9))
        sortWrongEntries(list, WrongSort.WrongCount)
        assertEquals(listOf("a", "b"), list.map { it.question.id })
    }

    // ── 汇总 ─────────────────────────────────────────────────────────

    @Test
    fun `汇总区分在册待复习已掌握`() {
        val list = listOf(
            entry("a", wrongCount = 1),
            entry("b", wrongCount = 5),
            entry("c", mastered = true, streak = 3)
        )
        val s = summarizeWrongBook(list, now = 1_000L)
        assertEquals(3, s.total)
        assertEquals(2, s.active)
        assertEquals(1, s.mastered)
    }

    @Test
    fun `汇总的今日到期不含已掌握的题`() {
        // 已掌握 + 已到期：不算到期。与 dueWrongQuestions 的 `mastered = 0` 对齐。
        val list = listOf(
            entry("a", wrongCount = 1, dueAt = 0L),
            entry("b", mastered = true, streak = 3, dueAt = 0L)
        )
        val s = summarizeWrongBook(list, now = 1_000L)
        assertEquals(1, s.due)
        assertEquals(1, s.mastered)
    }

    @Test
    fun `汇总把从未排期当作立即到期`() {
        val list = listOf(entry("a", dueAt = 0L), entry("b", dueAt = 10_000L))
        val s = summarizeWrongBook(list, now = 5_000L)
        assertEquals(1, s.due)
    }

    @Test
    fun `汇总恰好到期也算到期`() {
        val list = listOf(entry("a", dueAt = 5_000L))
        assertEquals(1, summarizeWrongBook(list, now = 5_000L).due)
        assertEquals(0, summarizeWrongBook(list, now = 4_999L).due)
    }

    @Test
    fun `空列表汇总全为零`() {
        val s = summarizeWrongBook(emptyList(), now = 1_000L)
        assertEquals(0, s.total)
        assertEquals(0, s.active)
        assertEquals(0, s.due)
        assertEquals(0, s.mastered)
    }

    // ── 到期文案 ─────────────────────────────────────────────────────

    @Test
    fun `已到期与从未排期文案一致`() {
        // 判据上 due_at = 0 就等价于"立即到期"，文案分成两句只会让人以为有区别。
        assertEquals("现在可复习", dueLabel(0L, now = 1_000L))
        assertEquals("现在可复习", dueLabel(999L, now = 1_000L))
    }

    @Test
    fun `未到期显示剩余天数且至少一天`() {
        val day = 24L * 60L * 60L * 1000L
        assertEquals("3 天后复习", dueLabel(3 * day, now = 0L))
        // 还差 1 小时：不足一天也必须显示"1 天"，不能显示"0 天"。
        assertEquals("1 天后复习", dueLabel(60L * 60L * 1000L, now = 0L))
    }

    @Test
    fun `到期文案对已掌握的题不做特殊处理`() {
        // 文案只看 due_at，不看掌握状态 —— 掌握状态由 StatusTag 单独渲染。
        // 这条钉住"别把两个维度混进一个函数"。
        val s = dueLabel(0L, now = 1_000L)
        assertTrue(s.isNotBlank())
    }
}
