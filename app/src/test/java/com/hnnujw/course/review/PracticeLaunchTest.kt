package com.hnnujw.course.review

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 开练请求（[PracticeLaunch]）的单元测试。
 *
 * 它把一条 6+ 位置参数的函数签名收成具名数据类，顺带承载了两段纯判断：
 *  - [PracticeLaunch.isTimedExam]：什么才算"限时考试"（有时长 且 非背题）；
 *  - [PracticeLaunch.title]：标题的优先级。
 * 这两段以前散在 ReviewActivity 的 when 里，没法单测 —— 搬进来后就钉在这里。
 */
class PracticeLaunchTest {

    // ── 默认值 ──────────────────────────────────────────────────────

    @Test
    fun `默认值就是不限时的普通顺序练习`() {
        val launch = PracticeLaunch(bankId = "b1")
        assertFalse(launch.onlyWrong)
        assertFalse(launch.onlyFavorite)
        assertFalse(launch.memorize)
        assertEquals(0, launch.durationSeconds)
        assertFalse(launch.isTimedExam)
    }

    // ── isTimedExam ─────────────────────────────────────────────────

    @Test
    fun `有时长且非背题才算限时考试`() {
        assertTrue(PracticeLaunch(bankId = "b1", durationSeconds = 1200).isTimedExam)
        assertTrue(PracticeLaunch(bankId = "b1", durationSeconds = 1).isTimedExam)
    }

    @Test
    fun `零或负时长不算限时`() {
        assertFalse(PracticeLaunch(bankId = "b1", durationSeconds = 0).isTimedExam)
        assertFalse(PracticeLaunch(bankId = "b1", durationSeconds = -5).isTimedExam)
    }

    @Test
    fun `背题即使有时长也不算限时考试`() {
        // 背题是"直接看答案"，计时没有意义；界面对背题不应起倒计时。
        assertFalse(
            PracticeLaunch(bankId = "b1", durationSeconds = 1200, memorize = true).isTimedExam
        )
    }

    // ── title 优先级 ─────────────────────────────────────────────────

    @Test
    fun `背题优先级最高`() {
        val launch = PracticeLaunch(
            bankId = "b1",
            chapters = listOf("第一章"),
            onlyWrong = true,
            onlyFavorite = true,
            memorize = true
        )
        assertEquals("背题", launch.title())
    }

    @Test
    fun `错题本优先级高于收藏与章节`() {
        val launch = PracticeLaunch(
            bankId = "b1",
            chapters = listOf("第一章"),
            onlyWrong = true,
            onlyFavorite = true
        )
        assertEquals("错题本", launch.title())
    }

    @Test
    fun `收藏优先级高于章节`() {
        val launch = PracticeLaunch(
            bankId = "b1",
            chapters = listOf("第一章"),
            onlyFavorite = true
        )
        assertEquals("收藏", launch.title())
    }

    @Test
    fun `有章节时用章节名`() {
        assertEquals("第一章", PracticeLaunch(bankId = "b1", chapters = listOf("第一章")).title())
    }

    @Test
    fun `空章节退回到模式标签`() {
        // 空集合（没选章节）与"只选了一个空白名"都不该把标题弄成空串。
        assertEquals(ReviewMode.SEQUENTIAL.label, PracticeLaunch(bankId = "b1").title())
        assertEquals(
            ReviewMode.SEQUENTIAL.label,
            PracticeLaunch(bankId = "b1", chapters = listOf("")).title()
        )
        assertEquals(
            ReviewMode.SEQUENTIAL.label,
            PracticeLaunch(bankId = "b1", chapters = listOf("   ", "")).title()
        )
    }

    @Test
    fun `多章标题报数量而不是拼接名字`() {
        // 拼接章节名会把标题撑成两行（内置题库的章节名本身就有十几个字）。
        assertEquals(
            "已选 2 章",
            PracticeLaunch(bankId = "b1", chapters = listOf("第一章", "第二章")).title()
        )
        // 空白项不计入数量。
        assertEquals(
            "第一章",
            PracticeLaunch(bankId = "b1", chapters = listOf("第一章", "  ")).title()
        )
    }

    @Test
    fun `无章节无特殊开关用模式标签`() {
        assertEquals(
            ReviewMode.RANDOM.label,
            PracticeLaunch(bankId = "b1", mode = ReviewMode.RANDOM).title()
        )
        assertEquals(
            ReviewMode.WRONG_FIRST.label,
            PracticeLaunch(bankId = "b1", mode = ReviewMode.WRONG_FIRST).title()
        )
    }

    @Test
    fun `今日待复习有独立标题且低于错题本`() {
        // dueOnly 是"错题本的一个子集"，与 onlyWrong 同时给时应显示错题本（更宽的那档）。
        assertEquals("今日待复习", PracticeLaunch(bankId = "b1", dueOnly = true).title())
        assertEquals(
            "错题本",
            PracticeLaunch(bankId = "b1", onlyWrong = true, dueOnly = true).title()
        )
    }

    @Test
    fun `跨题库开练标题仍按开关走`() {
        assertEquals("错题本", PracticeLaunch(bankId = null, onlyWrong = true).title())
        assertEquals("收藏", PracticeLaunch(bankId = null, onlyFavorite = true).title())
    }
}
