package com.hnnujw.course.utils

import com.hnnujw.course.ui.screen.ScheduleCourseUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * 课表 → 系统日历的事件展开回归。
 *
 * 这里锁的是"日期算对没有"：周次、星期偏移、节次时间三者任一算错，
 * 用户日历里的课就会落在错误的日子 —— 而且他可能过很久才发现。
 */
class ExpandCourseEventsTest {

    /**
     * 构造一门课。
     *
     * `color` 是 `ScheduleCourseUi` 的必填参数，但**与展开逻辑无关** ——
     * 这里给一个固定值。注意不能用 Compose 的 `Color(...)`：那是 @Immutable 的
     * 值类，在 JVM 单测里可以用，但会让本测试依赖 Compose 运行时；
     * 用 `Color.Unspecified` 更轻。
     */
    private fun course(
        id: String = "c1",
        name: String = "高等数学",
        day: Int = 1,
        startPeriod: Int = 1,
        endPeriod: Int = 2,
        weeks: String = "1-2",
        location: String = "A101",
        teacher: String = "张老师"
    ) = ScheduleCourseUi(
        id = id,
        name = name,
        day = day,
        startPeriod = startPeriod,
        endPeriod = endPeriod,
        weeks = weeks,
        location = location,
        teacher = teacher,
        color = androidx.compose.ui.graphics.Color.Unspecified
    )

    /** 2026-09-07 是周一。 */
    private fun monday(): Calendar = Calendar.getInstance().apply {
        clear()
        set(2026, Calendar.SEPTEMBER, 7, 0, 0, 0)
    }

    private val times = mapOf(
        1 to ("08:00" to "08:45"),
        2 to ("08:55" to "09:40"),
        3 to ("10:00" to "10:45"),
    )

    private fun dayOfMonth(millis: Long): Int =
        Calendar.getInstance().apply { timeInMillis = millis }.get(Calendar.DAY_OF_MONTH)

    private fun hourOf(millis: Long): Int =
        Calendar.getInstance().apply { timeInMillis = millis }.get(Calendar.HOUR_OF_DAY)

    private fun minuteOf(millis: Long): Int =
        Calendar.getInstance().apply { timeInMillis = millis }.get(Calendar.MINUTE)

    // —— 基本展开

    @Test
    fun `两周的课展开成两条事件`() {
        val events = expandCourseEvents(listOf(course(weeks = "1-2")), monday(), 20, times)
        assertEquals(2, events.size)
    }

    /** 第 1 周周一 → 9/7；第 2 周周一 → 9/14。 */
    @Test
    fun `周次正确映射到日期`() {
        val events = expandCourseEvents(listOf(course(weeks = "1-2")), monday(), 20, times)
        assertEquals(7, dayOfMonth(events[0].startMillis))
        assertEquals(14, dayOfMonth(events[1].startMillis))
    }

    /** 星期几按"周一为基准"偏移：周二 = +1 天。 */
    @Test
    fun `星期偏移以周一为基准`() {
        val events = expandCourseEvents(listOf(course(day = 3, weeks = "1")), monday(), 20, times)
        // 2026-09-07 是周一，day=3 是周三 → 9/9
        assertEquals(9, dayOfMonth(events[0].startMillis))
    }

    @Test
    fun `周日是第六天而不是前一天`() {
        val events = expandCourseEvents(listOf(course(day = 7, weeks = "1")), monday(), 20, times)
        // 周日 = 周一 + 6 天 → 9/13
        assertEquals(13, dayOfMonth(events[0].startMillis))
    }

    // —— 节次时间

    @Test
    fun `开始时间取起始节次的上课时刻`() {
        val events = expandCourseEvents(listOf(course(startPeriod = 1, endPeriod = 2, weeks = "1")), monday(), 20, times)
        assertEquals(8, hourOf(events[0].startMillis))
        assertEquals(0, minuteOf(events[0].startMillis))
    }

    @Test
    fun `结束时间取结束节次的下课时刻`() {
        val events = expandCourseEvents(listOf(course(startPeriod = 1, endPeriod = 2, weeks = "1")), monday(), 20, times)
        assertEquals(9, hourOf(events[0].endMillis))
        assertEquals(40, minuteOf(events[0].endMillis))
    }

    @Test
    fun `结束晚于开始`() {
        val events = expandCourseEvents(listOf(course(weeks = "1")), monday(), 20, times)
        assertTrue(events[0].endMillis > events[0].startMillis)
    }

    // —— 周次解析

    @Test
    fun `逗号与区间混合的周次`() {
        val events = expandCourseEvents(listOf(course(weeks = "1-2,4")), monday(), 20, times)
        assertEquals(3, events.size)
    }

    @Test
    fun `单周后缀只取奇数周`() {
        val events = expandCourseEvents(listOf(course(weeks = "1-6单")), monday(), 20, times)
        // 1,3,5 周 → 9/7、9/21、10/5
        assertEquals(3, events.size)
        assertEquals(7, dayOfMonth(events[0].startMillis))
        assertEquals(21, dayOfMonth(events[1].startMillis))
        assertEquals(5, dayOfMonth(events[2].startMillis))
        // 10/5 属于 10 月 —— 顺手钉住跨月进位
        assertEquals(
            10,
            Calendar.getInstance().apply { timeInMillis = events[2].startMillis }.get(Calendar.MONTH) + 1
        )
    }

    @Test
    fun `双周后缀只取偶数周`() {
        val events = expandCourseEvents(listOf(course(weeks = "1-6双")), monday(), 20, times)
        // 2,4,6
        assertEquals(3, events.size)
    }

    @Test
    fun `空周次视为全学期`() {
        val events = expandCourseEvents(listOf(course(weeks = "")), monday(), 4, times)
        assertEquals(4, events.size)
    }

    @Test
    fun `超出总周数的周被丢弃`() {
        val events = expandCourseEvents(listOf(course(weeks = "1-30")), monday(), 5, times)
        assertEquals(5, events.size)
    }

    // —— 脏数据必须被跳过而不是崩

    @Test
    fun `星期越界被跳过`() {
        assertTrue(expandCourseEvents(listOf(course(day = 0)), monday(), 20, times).isEmpty())
        assertTrue(expandCourseEvents(listOf(course(day = 8)), monday(), 20, times).isEmpty())
    }

    @Test
    fun `节次没有时间配置时被跳过`() {
        val empty = emptyMap<Int, Pair<String, String>>()
        assertTrue(expandCourseEvents(listOf(course()), monday(), 20, empty).isEmpty())
    }

    @Test
    fun `非法时间格式被跳过`() {
        val bad = mapOf(1 to ("8点" to "9点"), 2 to ("9点" to "10点"))
        assertTrue(expandCourseEvents(listOf(course()), monday(), 20, bad).isEmpty())
    }

    @Test
    fun `结束节次早于开始节次被跳过`() {
        assertTrue(expandCourseEvents(listOf(course(startPeriod = 3, endPeriod = 1)), monday(), 20, times).isEmpty())
    }

    @Test
    fun `空课程列表返回空`() {
        assertTrue(expandCourseEvents(emptyList(), monday(), 20, times).isEmpty())
    }

    // —— 内容字段

    @Test
    fun `事件带课程名地点与周次`() {
        val e = expandCourseEvents(listOf(course(weeks = "3")), monday(), 20, times).single()
        assertEquals("高等数学", e.title)
        assertEquals("A101", e.location)
        assertTrue("描述里应有周次: ${e.description}", e.description.contains("第3周"))
        assertTrue("描述里应有教师: ${e.description}", e.description.contains("张老师"))
    }

    @Test
    fun `没有教师时描述不出现空字段`() {
        val e = expandCourseEvents(listOf(course(teacher = "", weeks = "1")), monday(), 20, times).single()
        assertTrue(!e.description.contains("授课教师"))
    }
}
