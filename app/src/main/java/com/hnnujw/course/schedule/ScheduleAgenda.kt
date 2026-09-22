package com.hnnujw.course.schedule

import java.util.Calendar
import java.util.TimeZone

/**
 * 「日视图」的一天课程时间线（自旧仓库移植）。
 *
 * 与周网格「按周次静态铺开」不同，日视图关心的是**时间轴上的此刻**：
 * 今天有哪些堂、正在上哪一堂、下一节是什么、还剩几堂。这些都需要把
 * 「周次 + 星期 + 节次时间」换算成真实时间戳后再推——所以入口是
 * [ScheduleTimeBase]（第一周日期 + 各节起止时刻），不是周网格用的分钟数。
 *
 * 纯函数、无 Android 依赖，JVM 单测直接覆盖（见 ScheduleAgendaTest）。
 */
data class ScheduleOccurrence(val course: ScheduleCourseRecord, val startsAt: Long, val endsAt: Long)

data class ScheduleAgenda(
    /** 此刻所在的周次；开学日期推不出来时为 null。 */
    val week: Int?,
    /** 今天的全部课程（按开始时间排序）。 */
    val today: List<ScheduleOccurrence>,
    /** 正在上（now ∈ [startsAt, endsAt)）的课程。 */
    val current: List<ScheduleOccurrence>,
    /** 下一节（今天的；明天以后的课不算）。 */
    val next: ScheduleOccurrence?,
    /** 下一次时间线变化的时刻（某堂结束 / 下节开始 / 跨天），驱动时钟刷新粒度。 */
    val nextChangeAt: Long,
    /** 开学日期缺失或无效，无法换算真实日期。 */
    val needsCalendar: Boolean,
    /** 今天尚未开始的全部课程（按时间升序）。 */
    val upcoming: List<ScheduleOccurrence> = listOfNotNull(next)
) {
    /** 今天还剩几堂没上（含正在上的）。 */
    fun remaining(now: Long) = today.count { it.endsAt > now }

    companion object {
        fun calculate(
            courses: List<ScheduleCourseRecord>,
            base: ScheduleTimeBase?,
            now: Long,
            zone: TimeZone = TimeZone.getDefault()
        ): ScheduleAgenda {
            val day = Calendar.getInstance(zone).apply {
                timeInMillis = now
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }
            val tomorrow = (day.clone() as Calendar).apply { add(Calendar.DATE, 1) }.timeInMillis
            if (base == null || ScheduleDates.firstMonday(base.firstWeekDate, zone) == null) {
                return ScheduleAgenda(null, emptyList(), emptyList(), null, tomorrow, true)
            }

            fun time(value: String?): Pair<Int, Int>? {
                val parts = value?.split(':')?.map { it.toIntOrNull() } ?: return null
                return if (parts.size == 2 && parts[0] in 0..23 && parts[1] in 0..59) parts[0]!! to parts[1]!! else null
            }

            // 把每门课在其所有有效周次里的「真实出现」展开成时间戳，再筛掉已经过去的。
            // distinctBy(id)：同一门课可能因冲突筛选出现两份记录，展开前先去重。
            val occurrences = courses.distinctBy { it.id }.flatMap { course ->
                val weeks = ScheduleWeeks.parse(course.weeks)
                val start = time(base.periodStarts[course.startPeriod])
                val end = time(base.periodEnds[course.endPeriod])
                if (!weeks.valid || course.day !in 1..7 || start == null || end == null || course.endPeriod < course.startPeriod) {
                    emptyList()
                } else {
                    weeks.weeks.mapNotNull { week ->
                        val date = ScheduleDates.date(base.firstWeekDate, week, course.day, zone)
                            ?: return@mapNotNull null
                        val startsAt = (date.clone() as Calendar).apply {
                            set(Calendar.HOUR_OF_DAY, start.first); set(Calendar.MINUTE, start.second)
                        }.timeInMillis
                        val endsAt = date.apply {
                            set(Calendar.HOUR_OF_DAY, end.first); set(Calendar.MINUTE, end.second)
                        }.timeInMillis
                        if (endsAt <= startsAt || endsAt < day.timeInMillis) null else ScheduleOccurrence(course, startsAt, endsAt)
                    }
                }
            }.sortedWith(compareBy<ScheduleOccurrence> { it.startsAt }.thenBy { it.course.id })

            val today = occurrences.filter { it.startsAt in day.timeInMillis until tomorrow }
            val current = today.filter { now in it.startsAt until it.endsAt }
            val upcoming = occurrences.filter { it.startsAt > now }
            val next = upcoming.firstOrNull()
            val boundary = (current.map { it.endsAt } + listOfNotNull(next?.startsAt) + tomorrow)
                .filter { it > now }.min()
            return ScheduleAgenda(
                ScheduleDates.weekAt(base.firstWeekDate, now, zone),
                today, current, next, boundary, false, upcoming
            )
        }
    }
}
