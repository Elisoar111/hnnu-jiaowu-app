package com.hnnujw.course.schedule

import com.hnnujw.course.document.XlsxParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleExcelIOTest {

    @Test
    fun `export roundtrip parses back to the same courses`() {
        val courses = listOf(
            ScheduleExcelCourse("高等数学", "张三", "教一 101", 1, 1, 2, "1-16周"),
            ScheduleExcelCourse("大学英语", "李四", "文B 202", 3, 3, 4, "1,3,5,7周"),
            ScheduleExcelCourse("体育", "", "田径场", 7, 5, 6, "1-13单"),
        )
        val bytes = ScheduleExcelIO.exportWorkbook(courses)
        val document = XlsxParser.parse(bytes)
        val imported = ScheduleExcelIO.parseImported(document.sheets.first().rows)
        assertEquals(courses, imported)
    }

    @Test
    fun `import maps columns by header name regardless of order`() {
        val grid = listOf(
            listOf("教师", "课程名称", "结束节次", "星期", "周次", "开始节次", "上课地点"),
            listOf("王五", "数据结构", "4", "星期三", "2-8双", "3", "机房"),
        )
        val result = ScheduleExcelIO.parseImported(grid)
        assertEquals(1, result.size)
        val course = result.single()
        assertEquals("数据结构", course.name)
        assertEquals("王五", course.teacher)
        assertEquals("机房", course.location)
        assertEquals(3, course.day)
        assertEquals(3, course.startPeriod)
        assertEquals(4, course.endPeriod)
        assertEquals("2-8双", course.weeks)
    }

    @Test
    fun `import without header falls back to positional columns`() {
        val grid = listOf(listOf("化学", "赵六", "实验楼", "周四", "1", "2", "5-8周"))
        val result = ScheduleExcelIO.parseImported(grid)
        assertEquals(1, result.size)
        val course = result.single()
        assertEquals("化学", course.name)
        assertEquals(4, course.day)
        assertEquals(1, course.startPeriod)
        assertEquals(2, course.endPeriod)
        assertEquals("5-8周", course.weeks)
    }

    @Test
    fun `import skips blank and nameless rows`() {
        val grid = listOf(
            ScheduleExcelIO.EXPORT_HEADER,
            listOf("", "", "", "", "", "", ""),
            listOf("", "只有教师没有课程名", "楼", "周一", "1", "2", "1-4周"),
            listOf("毛概", "", "", "周二", "3", "4", "10-15周"),
            listOf("  ", "  ", "  ", "  ", "  ", "  ", "  "),
        )
        val result = ScheduleExcelIO.parseImported(grid)
        assertEquals(listOf("毛概"), result.map { it.name })
    }

    @Test
    fun `day parsing handles common formats`() {
        assertEquals(1, ScheduleExcelIO.parseDay("周一"))
        assertEquals(5, ScheduleExcelIO.parseDay("星期五"))
        assertEquals(6, ScheduleExcelIO.parseDay("周6"))
        assertEquals(7, ScheduleExcelIO.parseDay("周日"))
        assertEquals(7, ScheduleExcelIO.parseDay("星期天"))
        assertEquals(2, ScheduleExcelIO.parseDay("2"))
        assertEquals(7, ScheduleExcelIO.parseDay("Sunday"))
        assertEquals(1, ScheduleExcelIO.parseDay(""))
        assertEquals(1, ScheduleExcelIO.parseDay("看不懂"))
    }

    @Test
    fun `period parsing takes the first integer`() {
        assertEquals(1, ScheduleExcelIO.parsePeriod("1-2")!!)
        assertEquals(3, ScheduleExcelIO.parsePeriod("第3节")!!)
        assertEquals(10, ScheduleExcelIO.parsePeriod("10")!!)
        assertEquals(null, ScheduleExcelIO.parsePeriod("无"))
    }

    @Test
    fun `empty grid imports to empty list`() {
        assertTrue(ScheduleExcelIO.parseImported(emptyList()).isEmpty())
        assertTrue(ScheduleExcelIO.parseImported(listOf(listOf("", ""))).isEmpty())
    }

    @Test
    fun `single period end collapses to start`() {
        val grid = listOf(
            ScheduleExcelIO.EXPORT_HEADER,
            listOf("书法", "", "", "周三", "8", "", "2-9周"),
        )
        val course = ScheduleExcelIO.parseImported(grid).single()
        assertEquals(8, course.startPeriod)
        assertEquals(8, course.endPeriod)
    }
}
