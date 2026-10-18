package com.hnnujw.course.review

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * 复习提醒时间计算的回归。
 *
 * 这里守两件事，错任何一件用户都会觉得"这提醒有病"：
 *  1. **不早于设定时刻**：排到过去意味着闹钟立刻响（甚至被系统当作过期丢弃）；
 *  2. **按钟点排，不按间隔**：固定间隔会在用户作息偏移时一天天漂，
 *     固定钟点才能保证"每天晚上八点"这个承诺。
 */
class ReviewReminderTimeTest {

    /** 构造一个本地时区的确定时刻，避免用 System.currentTimeMillis() 造成不可复现。 */
    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int = 0): Long =
        Calendar.getInstance().apply {
            clear()
            set(year, month - 1, day, hour, minute, second)
        }.timeInMillis

    private fun hourOf(millis: Long): Int = Calendar.getInstance().apply { timeInMillis = millis }
        .get(Calendar.HOUR_OF_DAY)

    private fun minuteOf(millis: Long): Int = Calendar.getInstance().apply { timeInMillis = millis }
        .get(Calendar.MINUTE)

    private fun minuteOfDay(millis: Long): Int = hourOf(millis) * 60 + minuteOf(millis)

    // —— 基本行为

    @Test
    fun `白天时排到当天晚上`() {
        val now = at(2026, 10, 18, 9, 30)
        val next = ReviewReminderTime.nextTrigger(now, hourOfDay = 20, minute = 0)
        assertEquals(2026, Calendar.getInstance().apply { timeInMillis = next }.get(Calendar.YEAR))
        assertEquals(18, Calendar.getInstance().apply { timeInMillis = next }.get(Calendar.DAY_OF_MONTH))
        assertEquals(20 * 60, minuteOfDay(next))
    }

    @Test
    fun `已经过了设定时刻就排到明天`() {
        val now = at(2026, 10, 18, 21, 0)
        val next = ReviewReminderTime.nextTrigger(now, hourOfDay = 20, minute = 0)
        assertEquals("必须排到明天", 19, Calendar.getInstance().apply { timeInMillis = next }.get(Calendar.DAY_OF_MONTH))
        assertEquals(20 * 60, minuteOfDay(next))
    }

    /** 🔴 核心不变量：返回值**严格大于**现在。 */
    @Test
    fun `返回值永远严格大于当前时刻`() {
        val now = at(2026, 10, 18, 20, 0) // 正好落在目标分钟上
        val next = ReviewReminderTime.nextTrigger(now, hourOfDay = 20, minute = 0)
        assertTrue("next=$next 必须 > now=$now", next > now)
    }

    @Test
    fun `正好在目标秒时排到明天而不是立刻触发`() {
        val now = at(2026, 10, 18, 20, 0, 0)
        val next = ReviewReminderTime.nextTrigger(now, hourOfDay = 20, minute = 0)
        assertEquals(19, Calendar.getInstance().apply { timeInMillis = next }.get(Calendar.DAY_OF_MONTH))
    }

    @Test
    fun `越过月份的边界正确进位`() {
        val now = at(2026, 10, 31, 23, 0)
        val next = ReviewReminderTime.nextTrigger(now, hourOfDay = 20, minute = 0)
        val cal = Calendar.getInstance().apply { timeInMillis = next }
        assertEquals(11, cal.get(Calendar.MONTH) + 1)
        assertEquals(1, cal.get(Calendar.DAY_OF_MONTH))
    }

    @Test
    fun `跨年边界正确进位`() {
        val now = at(2026, 12, 31, 22, 0)
        val next = ReviewReminderTime.nextTrigger(now, hourOfDay = 20, minute = 0)
        val cal = Calendar.getInstance().apply { timeInMillis = next }
        assertEquals(2027, cal.get(Calendar.YEAR))
        assertEquals(1, cal.get(Calendar.MONTH) + 1)
        assertEquals(1, cal.get(Calendar.DAY_OF_MONTH))
    }

    // —— 非法输入不能崩

    @Test
    fun `越界的小时被夹回合法范围`() {
        val now = at(2026, 10, 18, 9, 0)
        for (h in listOf(-5, 24, 99)) {
            val next = ReviewReminderTime.nextTrigger(now, hourOfDay = h, minute = 0)
            assertTrue("h=$h 必须 > now", next > now)
            assertTrue(hourOf(next) in 0..23)
        }
    }

    @Test
    fun `越界的分钟被夹回合法范围`() {
        val now = at(2026, 10, 18, 9, 0)
        for (m in listOf(-1, 60, 999)) {
            val next = ReviewReminderTime.nextTrigger(now, hourOfDay = 20, minute = m)
            assertTrue("m=$m 必须 > now", next > now)
            assertTrue(minuteOf(next) in 0..59)
        }
    }

    // —— shouldNotify

    @Test
    fun `开关关着时永远不提醒`() {
        assertFalse(ReviewReminderTime.shouldNotify(enabled = false, dueCount = 10))
        assertFalse(ReviewReminderTime.shouldNotify(enabled = false, dueCount = 0))
    }

    @Test
    fun `没有到期题时不提醒`() {
        assertFalse(ReviewReminderTime.shouldNotify(enabled = true, dueCount = 0))
        assertFalse(ReviewReminderTime.shouldNotify(enabled = true, dueCount = -1))
    }

    @Test
    fun `开着且有到期题才提醒`() {
        assertTrue(ReviewReminderTime.shouldNotify(enabled = true, dueCount = 1))
        assertTrue(ReviewReminderTime.shouldNotify(enabled = true, dueCount = 30))
    }

    // —— 文案

    @Test
    fun `文案里带实际题数`() {
        assertTrue(ReviewReminderTime.body(7).contains("7"))
        assertTrue(ReviewReminderTime.body(1).contains("1"))
    }

    @Test
    fun `标题非空`() {
        assertTrue(ReviewReminderTime.title().isNotBlank())
    }

    @Test
    fun `默认是晚上八点`() {
        assertEquals(20, ReviewReminderTime.DEFAULT_HOUR)
        assertEquals(0, ReviewReminderTime.DEFAULT_MINUTE)
    }
}
