package com.hnnujw.course.review

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 错题本三态模型的单元测试。
 *
 * 三态是**纯展示层**对已有两列（`mastered` / `wrong_count`）的再切分，不写库、不迁移。
 * 这里把边界钉死：mastered 优先于错误次数、错误次数 1 与 2 的分界、筛选各档、
 * 名字还原认不出回默认、排序键不绑死枚举顺序。
 */
class WrongStatusTest {

    // ── wrongStatus 三态 ─────────────────────────────────────────────

    @Test
    fun `未掌握且错误一次为复习中`() {
        assertEquals(WrongStatus.Reviewing, wrongStatus(mastered = false, wrongCount = 1))
    }

    @Test
    fun `错误两次及以上为未掌握`() {
        assertEquals(WrongStatus.NotMastered, wrongStatus(mastered = false, wrongCount = 2))
        assertEquals(WrongStatus.NotMastered, wrongStatus(mastered = false, wrongCount = 99))
    }

    @Test
    fun `已掌握优先于错误次数再多`() {
        // 错了很多次但已连对到 MASTER_STREAK，就该显示已掌握 —— 不能自相矛盾。
        assertEquals(WrongStatus.Mastered, wrongStatus(mastered = true, wrongCount = 1))
        assertEquals(WrongStatus.Mastered, wrongStatus(mastered = true, wrongCount = 100))
    }

    @Test
    fun `错误次数零按复习中处理`() {
        // 入册的题按定义错过，但纯函数不该对"0 次"抛异常或落到未掌握。
        assertEquals(WrongStatus.Reviewing, wrongStatus(mastered = false, wrongCount = 0))
    }

    @Test
    fun `阈值常量与判定一致`() {
        assertEquals(2, NOT_MASTERED_THRESHOLD)
        assertEquals(WrongStatus.Reviewing, wrongStatus(false, NOT_MASTERED_THRESHOLD - 1))
        assertEquals(WrongStatus.NotMastered, wrongStatus(false, NOT_MASTERED_THRESHOLD))
    }

    // ── WrongStatusFilter.accepts ─────────────────────────────────────

    @Test
    fun `Active 只收未掌握的`() {
        assertTrue(WrongStatusFilter.Active.accepts(WrongStatus.Reviewing))
        assertTrue(WrongStatusFilter.Active.accepts(WrongStatus.NotMastered))
        assertFalse(WrongStatusFilter.Active.accepts(WrongStatus.Mastered))
    }

    @Test
    fun `单档筛选只收自己那一档`() {
        assertTrue(WrongStatusFilter.Reviewing.accepts(WrongStatus.Reviewing))
        assertFalse(WrongStatusFilter.Reviewing.accepts(WrongStatus.NotMastered))

        assertTrue(WrongStatusFilter.NotMastered.accepts(WrongStatus.NotMastered))
        assertFalse(WrongStatusFilter.NotMastered.accepts(WrongStatus.Reviewing))

        assertTrue(WrongStatusFilter.Mastered.accepts(WrongStatus.Mastered))
        assertFalse(WrongStatusFilter.Mastered.accepts(WrongStatus.Reviewing))
    }

    @Test
    fun `All 收全部`() {
        WrongStatus.entries.forEach { assertTrue(WrongStatusFilter.All.accepts(it)) }
    }

    // ── fromName 容错 ────────────────────────────────────────────────

    @Test
    fun `筛选名字还原命中已知项`() {
        assertEquals(WrongStatusFilter.NotMastered, WrongStatusFilter.fromName("NotMastered"))
        assertEquals(WrongStatusFilter.All, WrongStatusFilter.fromName("All"))
    }

    @Test
    fun `筛选名字认不出回默认 Active`() {
        assertEquals(WrongStatusFilter.Active, WrongStatusFilter.fromName(null))
        assertEquals(WrongStatusFilter.Active, WrongStatusFilter.fromName(""))
        assertEquals(WrongStatusFilter.Active, WrongStatusFilter.fromName("不存在"))
    }

    @Test
    fun `排序名字认不出回默认 LastWrong`() {
        assertEquals(WrongSort.LastWrong, WrongSort.fromName(null))
        assertEquals(WrongSort.LastWrong, WrongSort.fromName("garbage"))
        assertEquals(WrongSort.WrongCount, WrongSort.fromName("WrongCount"))
        assertEquals(WrongSort.Status, WrongSort.fromName("Status"))
    }

    // ── wrongStatusOrder ─────────────────────────────────────────────

    @Test
    fun `排序键让复习中最前已掌握最后`() {
        // 越该先练的越小。抽成函数是为了不把顺序绑死在枚举声明序上。
        assertTrue(wrongStatusOrder(WrongStatus.Reviewing) < wrongStatusOrder(WrongStatus.NotMastered))
        assertTrue(wrongStatusOrder(WrongStatus.NotMastered) < wrongStatusOrder(WrongStatus.Mastered))
    }

    @Test
    fun `按排序键排出的顺序符合预期`() {
        val sorted = listOf(WrongStatus.Mastered, WrongStatus.Reviewing, WrongStatus.NotMastered)
            .sortedBy { wrongStatusOrder(it) }
        assertEquals(
            listOf(WrongStatus.Reviewing, WrongStatus.NotMastered, WrongStatus.Mastered),
            sorted
        )
    }
}
