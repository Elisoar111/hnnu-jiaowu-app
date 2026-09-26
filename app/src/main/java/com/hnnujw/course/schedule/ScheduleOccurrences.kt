package com.hnnujw.course.schedule

import java.util.Calendar
import java.util.TimeZone

/**
 * 课程时间窗展开：把「第 N 周 星期 X 第 A-B 节」变成真实的绝对时间区间。
 *
 * 课前提醒（[CourseReminderPlanner]）与上课自动模式（[AutoModePlan]）都要做这套算术，
 * 所以抽在这里只有一份实现 —— 两边对"这节课什么时候开始、什么时候结束"的判断必须一致，
 * 否则会出现「提醒响了但勿扰没切」这种对不上的现象。
 */

/** 一节课在真实时间轴上的区间，左闭右开。 */
data class CourseWindow(val courseId: String, val startsAt: Long, val endsAt: Long)

/**
 * 第一周周一。
 *
 * 日期缺失、格式不对、或那一天不是周一时返回 null：周次算不出来就不排任何闹钟，
 * 不能猜一个「大概是这天」。
 */
internal fun weekOneMonday(timeBase: ScheduleTimeBase?, zone: TimeZone = TimeZone.getDefault()): Calendar? {
    val parts = timeBase?.firstWeekDate?.split('-')?.map { it.toIntOrNull() } ?: return null
    if (parts.size != 3 || parts.any { it == null }) return null
    return runCatching {
        Calendar.getInstance(zone).apply {
            clear()
            isLenient = false
            set(parts[0]!!, parts[1]!! - 1, parts[2]!!)
            // isLenient=false 时非法日期（如 13 月）会在读 timeInMillis 时抛异常。
            timeInMillis
            require(get(Calendar.DAY_OF_WEEK) == Calendar.MONDAY)
        }
    }.getOrNull()
}

/** `"HH:mm"` → (时, 分)。格式不对或超出范围返回 null。 */
internal fun parseClock(value: String?): Pair<Int, Int>? {
    val parts = value?.split(':')?.map { it.trim().toIntOrNull() } ?: return null
    if (parts.size != 2) return null
    val hour = parts[0] ?: return null
    val minute = parts[1] ?: return null
    return if (hour in 0..23 && minute in 0..59) hour to minute else null
}

/** 第 [week] 周星期 [day] 的 [hour]:[minute]。 */
internal fun occurrenceAt(
    monday: Calendar,
    week: Int,
    day: Int,
    hour: Int,
    minute: Int,
    zone: TimeZone
): Calendar = (monday.clone() as Calendar).apply {
    add(Calendar.DAY_OF_YEAR, (week - 1) * 7 + day - 1)
    set(Calendar.HOUR_OF_DAY, hour)
    set(Calendar.MINUTE, minute)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}

/**
 * 展开 `[from, to)` 区间内所有课次，按开始时间升序。
 *
 * 起止时间取「起始节的开始时间」与「结束节的结束时间」。任一项缺失或非法就**跳过该课程** ——
 * 宁可不切勿扰，也不要按一个编出来的下课时间去恢复手机。
 */
internal fun courseWindows(
    courses: List<ScheduleCourseRecord>,
    timeBase: ScheduleTimeBase?,
    from: Long,
    to: Long,
    zone: TimeZone = TimeZone.getDefault()
): List<CourseWindow> {
    val base = timeBase ?: return emptyList()
    val monday = weekOneMonday(base, zone) ?: return emptyList()
    val starts = base.periodStarts
    val ends = base.periodEnds
    return courses.flatMap { course ->
        val start = parseClock(starts[course.startPeriod])
        val end = parseClock(ends[course.endPeriod])
        val weeks = ScheduleWeeks.parse(course.weeks)
        if (course.day !in 1..7 || course.startPeriod < 1 || course.endPeriod < course.startPeriod ||
            start == null || end == null || !weeks.valid
        ) {
            emptyList()
        } else {
            weeks.weeks.mapNotNull { week ->
                val startsAt = occurrenceAt(monday, week, course.day, start.first, start.second, zone).timeInMillis
                val endsAt = occurrenceAt(monday, week, course.day, end.first, end.second, zone).timeInMillis
                // 结束不晚于开始 = 节次时间填反了，当作无效，不产生窗口。
                if (endsAt <= startsAt || endsAt <= from || startsAt >= to) null
                else CourseWindow(course.id, startsAt, endsAt)
            }
        }
    }.sortedBy { it.startsAt }
}
