package com.hnnujw.course.ui.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 星期条滑块落点的回归测试。
 *
 * 盯住的需求：
 * 1. **课表日页滑动时，日期行上的圆圈要跟着走到对应的那一天。**
 * 2. **周视图里圆圈只标"今天"；翻到别的周就不该有圆圈。**
 *
 * 这条链路是 `pager.currentPage → shownDay → selectedDay → 滑块下标`，
 * 前三段在 Compose 里没法单测，最后一段（本文件测的这个纯函数）就是它唯一的接缝 ——
 * 所以这里要把三条规则的**优先级**钉死，尤其是"日视图必须压过周视图那两条"。
 */
class ScheduleDateStripSelectionTest {

    private fun select(
        dayView: Boolean = false,
        selectedDay: Int = 1,
        isCurrentWeek: Boolean = true,
        currentDayOfWeek: Int = 1,
        dayCount: Int = 7,
    ) = scheduleDateStripSelection(dayView, selectedDay, isCurrentWeek, currentDayOfWeek, dayCount)

    // ── 日视图：滑块 = 正在浏览的那天（需求 1 的核心）──────────────────────

    @Test
    fun dayViewFollowsTheBrowsedDay() {
        // 星期 1..7 → 格子 0..6，逐个都对得上，滑块才能"跟手"
        for (day in 1..7) {
            assertEquals(day - 1, select(dayView = true, selectedDay = day))
        }
    }

    @Test
    fun dayViewClampsOutOfRangeDays() {
        assertEquals(0, select(dayView = true, selectedDay = 0))
        assertEquals(0, select(dayView = true, selectedDay = -3))
        assertEquals(6, select(dayView = true, selectedDay = 8))
    }

    /**
     * 优先级回归：日视图落在**别的周**时，不能被"非本周 → 无滑块"那条规则抢走。
     *
     * 这是最容易写错的一处 —— 若把 `isCurrentWeek` 分支写在 `dayView` 前面，
     * 用户在日视图翻到下周之后，滑块会整条消失，需求 1 即失效。
     */
    @Test
    fun dayViewWinsOverTheWeekViewRules() {
        assertEquals(
            6,
            select(dayView = true, selectedDay = 7, isCurrentWeek = false, currentDayOfWeek = 2),
        )
        assertEquals(
            3,
            select(dayView = true, selectedDay = 4, isCurrentWeek = true, currentDayOfWeek = 2),
        )
    }

    /** 同上，但直接断言"日视图不返回 null"——这是需求 1 与需求 2 的分界线。 */
    @Test
    fun dayViewNeverReportsNoSelection() {
        for (day in 1..7) {
            for (current in 1..7) {
                for (isCurrentWeek in listOf(true, false)) {
                    assertEquals(
                        "日视图第 $day 天（本周=$isCurrentWeek）不该没有滑块",
                        day - 1,
                        select(dayView = true, selectedDay = day, isCurrentWeek = isCurrentWeek, currentDayOfWeek = current),
                    )
                }
            }
        }
    }

    // ── 周视图：本周标今天、非本周没有滑块（需求 2）────────────────────────

    @Test
    fun weekViewOnTheCurrentWeekMarksToday() {
        for (day in 1..7) {
            assertEquals(day - 1, select(dayView = false, isCurrentWeek = true, currentDayOfWeek = day))
        }
    }

    /**
     * 需求 2 的核心断言：翻到别的周时**不该有滑块**。
     *
     * 曾经这里是"锚定第 1 格"。那是错的：周视图一次浏览一整周，滑块只在表达
     * "今天是这一天"；翻到别的周时今天并不在这一周里，锚在第 1 格会让用户
     * 以为"正在看周一"。
     */
    @Test
    fun weekViewOnAnotherWeekHasNoIndicator() {
        for (current in 1..7) {
            assertNull(select(dayView = false, isCurrentWeek = false, currentDayOfWeek = current))
        }
    }

    @Test
    fun weekViewIgnoresTheBrowsedDay() {
        // 周视图下 selectedDay 不参与决策：它是日视图的浏览状态，
        // 周视图里恒等于 todayDay（见调用点），若误用会让滑块随切周乱跳。
        assertEquals(
            select(dayView = false, selectedDay = 1, isCurrentWeek = true, currentDayOfWeek = 5),
            select(dayView = false, selectedDay = 7, isCurrentWeek = true, currentDayOfWeek = 5),
        )
    }

    // ── 边界 ───────────────────────────────────────────────────────────

    @Test
    fun emptyStripReportsNoSelectionInsteadOfCrashing() {
        assertNull(select(dayView = true, selectedDay = 3, dayCount = 0))
        assertNull(select(dayView = false, isCurrentWeek = true, currentDayOfWeek = 3, dayCount = 0))
    }

    @Test
    fun fiveDayStripStillClampsWithinItsOwnRange() {
        // 只显示工作日时，周六/周日的落点必须被夹回最后一格，不能越界。
        assertEquals(4, select(dayView = true, selectedDay = 7, dayCount = 5))
        assertEquals(4, select(dayView = false, isCurrentWeek = true, currentDayOfWeek = 6, dayCount = 5))
    }
}
