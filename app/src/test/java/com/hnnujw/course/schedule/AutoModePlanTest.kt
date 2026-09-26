package com.hnnujw.course.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class AutoModePlanTest {

    private val zone = TimeZone.getTimeZone("Asia/Shanghai")

    /** 2026-09-07 是周一（与 CourseReminderPlannerTest 用的是同一天）。 */
    private val monday = "2026-09-07"

    private fun time(day: Int, hour: Int, minute: Int) = Calendar.getInstance(zone).apply {
        clear()
        set(2026, Calendar.SEPTEMBER, day, hour, minute)
    }.timeInMillis

    // ---- 决策：什么时候动系统设置 ----

    @Test fun offNeverTouchesTheSystemWhenWeHaveNotAppliedAnything() {
        assertEquals(AutoModeAction.None, decideAutoMode(AutoModeKind.Off, null, inClass = false))
        // 就算正在上课，档位是「关闭」也不该去切勿扰。
        assertEquals(AutoModeAction.None, decideAutoMode(AutoModeKind.Off, null, inClass = true))
    }

    @Test fun weMustRestoreEvenWhenTheReasonForApplyingIsGone() {
        // 这是整个功能最要紧的一条：档位被关掉之后，只要我们还改过系统设置就必须还原。
        // 少了它，用户把开关拨回「关闭」、手机会一直停在勿扰。
        assertEquals(AutoModeAction.Restore, decideAutoMode(AutoModeKind.Off, AutoModeKind.DoNotDisturb, inClass = false))
        assertEquals(AutoModeAction.Restore, decideAutoMode(AutoModeKind.Off, AutoModeKind.DoNotDisturb, inClass = true))
        // 同理：课已经下了。
        assertEquals(AutoModeAction.Restore, decideAutoMode(AutoModeKind.AlarmsOnly, AutoModeKind.AlarmsOnly, inClass = false))
    }

    @Test fun appliesOnlyWhileInClassAndIsIdempotent() {
        assertEquals(AutoModeAction.None, decideAutoMode(AutoModeKind.DoNotDisturb, null, inClass = false))
        // 第一次施加要记下系统原样。
        assertEquals(
            AutoModeAction.Apply(AutoModeKind.DoNotDisturb, capturePrevious = true),
            decideAutoMode(AutoModeKind.DoNotDisturb, null, inClass = true)
        )
        // 已经是目标档位就不要再动系统 —— reconcile 每次 Activity resume 都会跑。
        assertEquals(AutoModeAction.None, decideAutoMode(AutoModeKind.DoNotDisturb, AutoModeKind.DoNotDisturb, inClass = true))
    }

    @Test fun switchingModeMidClassKeepsTheOriginalRestoreTarget() {
        // 上课上到一半把「勿扰」改成「仅闹钟」：要重新施加，但**不能**再记一次"原样"，
        // 否则还原目标就变成我们自己刚设的档位，手机再也回不到用户原来的设置。
        assertEquals(
            AutoModeAction.Apply(AutoModeKind.AlarmsOnly, capturePrevious = false),
            decideAutoMode(AutoModeKind.AlarmsOnly, AutoModeKind.DoNotDisturb, inClass = true)
        )
        assertEquals(
            AutoModeAction.Apply(AutoModeKind.PriorityOnly, capturePrevious = false),
            decideAutoMode(AutoModeKind.PriorityOnly, AutoModeKind.AlarmsOnly, inClass = true)
        )
    }

    // ---- 时间基准 ----

    @Test fun weekOneMondayRejectsAnythingThatIsNotAMonday() {
        assertEquals(Calendar.MONDAY, weekOneMonday(ScheduleTimeBase(monday), zone)!!.get(Calendar.DAY_OF_WEEK))
        // 周二：周次算不出来就不排闹钟，不能猜。
        assertNull(weekOneMonday(ScheduleTimeBase("2026-09-08"), zone))
        assertNull(weekOneMonday(ScheduleTimeBase(""), zone))
        assertNull(weekOneMonday(ScheduleTimeBase("2026-09"), zone))
        assertNull(weekOneMonday(ScheduleTimeBase("not-a-date"), zone))
        // isLenient=false 时 13 月会抛异常，必须被吃掉返回 null 而不是崩掉调度器。
        assertNull(weekOneMonday(ScheduleTimeBase("2026-13-01"), zone))
        assertNull(weekOneMonday(null, zone))
    }

    @Test fun parseClockAcceptsOnlyRealTimes() {
        assertEquals(8 to 0, parseClock("08:00"))
        assertEquals(8 to 0, parseClock("8:0"))
        assertEquals(23 to 59, parseClock("23:59"))
        assertNull(parseClock(null))
        assertNull(parseClock(""))
        assertNull(parseClock("8"))
        assertNull(parseClock("8:0:0"))
        assertNull(parseClock("24:00"))
        assertNull(parseClock("08:60"))
        assertNull(parseClock("ab:cd"))
    }

    // ---- 课次展开 ----

    private val base = ScheduleTimeBase(
        firstWeekDate = monday,
        periodStarts = mapOf(1 to "08:00", 2 to "08:55", 3 to "10:00"),
        periodEnds = mapOf(1 to "08:45", 2 to "09:40", 3 to "10:45")
    )

    private fun course(id: String = "c1", day: Int = 1, start: Int = 1, end: Int = 2, weeks: String = "1-2周") =
        ScheduleCourseRecord(id, "课程", "", "A101", day, start, end, weeks)

    @Test fun windowsUseTheStartOfTheFirstPeriodAndTheEndOfTheLast() {
        val windows = courseWindows(listOf(course()), base, time(1, 0, 0), time(30, 0, 0), zone)
        assertEquals(2, windows.size)
        assertEquals(time(7, 8, 0), windows[0].startsAt)
        assertEquals(time(7, 9, 40), windows[0].endsAt)
        // 第二周同一星期几：往后推 7 天。
        assertEquals(time(14, 8, 0), windows[1].startsAt)
        assertEquals(time(14, 9, 40), windows[1].endsAt)
    }

    @Test fun windowsFollowTheWeekdayOffset() {
        // 周三 = 第一周周一 + 2 天。
        val windows = courseWindows(listOf(course(day = 3, weeks = "1周")), base, time(1, 0, 0), time(30, 0, 0), zone)
        assertEquals(time(9, 8, 0), windows.single().startsAt)
        assertEquals(time(9, 9, 40), windows.single().endsAt)
    }

    @Test fun rangeFilteringKeepsOnlyOverlappingWindowsAndOrdersThem() {
        val all = courseWindows(listOf(course()), base, time(1, 0, 0), time(30, 0, 0), zone)
        // 从第一周周二起看：只剩第二周那一节。
        assertEquals(listOf(time(14, 8, 0)), courseWindows(listOf(course()), base, time(8, 0, 0), time(30, 0, 0), zone).map { it.startsAt })
        // 只看第一周。
        assertEquals(listOf(time(7, 8, 0)), courseWindows(listOf(course()), base, time(1, 0, 0), time(8, 0, 0), zone).map { it.startsAt })
        // 空区间。
        assertTrue(courseWindows(listOf(course()), base, time(1, 0, 0), time(1, 0, 0), zone).isEmpty())
        // 乱序输入也要按开始时间排好 —— 调用方直接取 first() 当下一次上课。
        val two = courseWindows(listOf(course(id = "b", day = 3, weeks = "1周"), course(id = "a", day = 1, weeks = "1周")),
            base, time(1, 0, 0), time(30, 0, 0), zone)
        assertEquals(listOf("a", "b"), two.map { it.courseId })
        assertEquals(all.size, 2)
    }

    @Test fun unusableCoursesAreSkippedInsteadOfGuessed() {
        fun windows(courses: List<ScheduleCourseRecord>, timeBase: ScheduleTimeBase? = base) =
            courseWindows(courses, timeBase, time(1, 0, 0), time(30, 0, 0), zone)

        // 周次解析不出来（"待定"）→ 跳过。
        assertTrue(windows(listOf(course(weeks = "待定"))).isEmpty())
        // 起始节的开始时间缺失 → 跳过。
        assertTrue(windows(listOf(course()), base.copy(periodStarts = mapOf(2 to "08:55"))).isEmpty())
        // 结束节的结束时间缺失 → 跳过。宁可不切勿扰，也不要按一个编出来的下课时间恢复手机。
        assertTrue(windows(listOf(course()), base.copy(periodEnds = mapOf(1 to "08:45"))).isEmpty())
        // 星期几越界 / 节次区间反了 → 跳过。
        assertTrue(windows(listOf(course(day = 8))).isEmpty())
        assertTrue(windows(listOf(course(day = 0))).isEmpty())
        assertTrue(windows(listOf(course(start = 3, end = 1))).isEmpty())
        assertTrue(windows(listOf(course(start = 0))).isEmpty())
        // 时间基准整体缺失或不是周一 → 一条窗口都没有。
        assertTrue(windows(listOf(course()), null).isEmpty())
        assertTrue(windows(listOf(course()), base.copy(firstWeekDate = "2026-09-08")).isEmpty())
        // 结束时间早于开始时间 = 节次时间填反了 → 跳过，而不是产生一个负长度的窗口。
        assertTrue(windows(listOf(course()), base.copy(periodStarts = mapOf(1 to "09:00"), periodEnds = mapOf(2 to "08:00"))).isEmpty())
    }

    @Test fun inClassIsDecidedByContainmentNotByProximity() {
        val windows = courseWindows(listOf(course(weeks = "1周")), base, time(1, 0, 0), time(30, 0, 0), zone)
        fun inClass(at: Long) = windows.any { at >= it.startsAt && at < it.endsAt }
        assertTrue(inClass(time(7, 8, 0)))     // 上课瞬间算在课内（左闭）
        assertTrue(inClass(time(7, 9, 39)))
        assertTrue(!inClass(time(7, 9, 40)))   // 下课瞬间算课外（右开）
        assertTrue(!inClass(time(7, 7, 59)))
        assertTrue(!inClass(time(8, 8, 0)))
    }
}
