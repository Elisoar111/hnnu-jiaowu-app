package com.hnnujw.course.schedule

import org.junit.Assert.assertEquals
import org.junit.Test

class ScheduleVisibleDaysTest {

    // ── 天数选择 ─────────────────────────────────────────────────────────

    @Test fun weekViewShowsSevenDaysWhenWeekendIsOn() {
        assertEquals(7, scheduleVisibleDays(dayView = false, showWeekend = true))
    }

    @Test fun weekViewDropsToFiveDaysWhenWeekendIsHidden() {
        assertEquals(5, scheduleVisibleDays(dayView = false, showWeekend = false))
    }

    /** 关键回归：日视图必须恒 7，否则隐藏周末后周末的课无处可看。 */
    @Test fun dayViewAlwaysKeepsSevenDaysEvenWhenWeekendIsHidden() {
        assertEquals(7, scheduleVisibleDays(dayView = true, showWeekend = false))
    }

    @Test fun dayViewIsSevenDaysWhenWeekendIsOn() {
        assertEquals(7, scheduleVisibleDays(dayView = true, showWeekend = true))
    }

    @Test fun dayViewIgnoresTheWeekendSwitchEntirely() {
        // 两个开关组合下日视图结果必须相同 —— 这就是"日视图与开关解耦"的断言。
        assertEquals(
            scheduleVisibleDays(dayView = true, showWeekend = true),
            scheduleVisibleDays(dayView = true, showWeekend = false)
        )
    }

    // ── 被隐藏的周末课程计数 ─────────────────────────────────────────────

    @Test fun nothingIsHiddenWhileWeekendIsOn() {
        assertEquals(0, hiddenWeekendCourseCount(listOf(1, 6, 6, 7), showWeekend = true))
    }

    @Test fun countsCoursesOnSaturdayAndSunday() {
        assertEquals(3, hiddenWeekendCourseCount(listOf(1, 6, 6, 7), showWeekend = false))
    }

    @Test fun weekdayCoursesAreNeverCountedAsHidden() {
        assertEquals(0, hiddenWeekendCourseCount(listOf(1, 2, 3, 4, 5), showWeekend = false))
    }

    /** 返回 0 时调用方不该给提示；没有周末课的账号是最常见的情况。 */
    @Test fun emptyWeekReportsNothingHidden() {
        assertEquals(0, hiddenWeekendCourseCount(emptyList(), showWeekend = false))
    }

    /** 边界：第 5 天（周五）不算周末，第 6 天（周六）才算。 */
    @Test fun boundarySitsBetweenFridayAndSaturday() {
        assertEquals(0, hiddenWeekendCourseCount(listOf(5), showWeekend = false))
        assertEquals(1, hiddenWeekendCourseCount(listOf(6), showWeekend = false))
    }
}
