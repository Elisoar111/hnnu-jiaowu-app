package com.hnnujw.course.schedule

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 星期标签换算的边界。
 *
 * 重点盯两件事：
 * 1. **7 = 周日**（不是 0，也不是越界）—— 这是全项目"星期数字 1..7"的口径；
 * 2. 越界返回 `'?'` 而不是抛异常或猜一个看起来合理的字 ——
 *    界面上一旦出现 `?` 就说明上游给错了值，不该被悄悄掩盖成"周六"。
 */
class ScheduleWeekdayLabelTest {

    @Test
    fun mondayIsTheFirstCharacter() {
        assertEquals('一', scheduleWeekdayChar(1))
        assertEquals("周一", scheduleWeekdayShort(1))
        assertEquals("星期一", scheduleWeekdayLong(1))
    }

    @Test
    fun saturdayIsSixthAndSundayIsSeventh() {
        assertEquals("周六", scheduleWeekdayShort(6))
        assertEquals("周日", scheduleWeekdayShort(7))
        assertEquals("星期六", scheduleWeekdayLong(6))
        assertEquals("星期日", scheduleWeekdayLong(7))
    }

    @Test
    fun everyDayInRangeMapsToItsOwnCharacter() {
        val seen = (1..7).map { scheduleWeekdayChar(it) }
        assertEquals("一二三四五六日".toList(), seen)
        // 七个字互不相同 —— 否则"第 N 格 = 星期 N"就不再成立。
        assertEquals(7, seen.toSet().size)
    }

    @Test
    fun outOfRangeFallsBackToQuestionMarkInsteadOfThrowing() {
        assertEquals('?', scheduleWeekdayChar(0))
        assertEquals('?', scheduleWeekdayChar(8))
        assertEquals('?', scheduleWeekdayChar(-1))
        assertEquals("周?", scheduleWeekdayShort(0))
        assertEquals("星期?", scheduleWeekdayLong(99))
    }

    @Test
    fun shortAndLongShareTheSameCharacter() {
        (1..7).forEach { day ->
            assertEquals(scheduleWeekdayChar(day).toString(), scheduleWeekdayShort(day).removePrefix("周"))
            assertEquals(scheduleWeekdayChar(day).toString(), scheduleWeekdayLong(day).removePrefix("星期"))
        }
    }
}
