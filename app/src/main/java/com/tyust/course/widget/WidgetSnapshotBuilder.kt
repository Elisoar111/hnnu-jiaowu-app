package com.tyust.course.widget

import android.content.Context
import com.tyust.course.academic.AcademicStudyReader
import com.tyust.course.manager.ScheduleSettingsManager
import com.tyust.course.manager.UserManager
import com.tyust.course.schedule.ScheduleCacheStore
import com.tyust.course.schedule.ScheduleDates
import com.tyust.course.schedule.ScheduleJson
import com.tyust.course.schedule.ScheduleReminderScheduler
import com.tyust.course.schedule.ScheduleTimeBase
import com.tyust.course.schedule.ScheduleWeeks
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * 桌面小组件的数据层：**只读本地缓存，不发任何网络请求**。
 *
 * 数据来源与课表页完全同源：
 * - 课表：`schedule_cache` SharedPreferences（课表页写入的网络快照）；
 * - 自定义/补课课程：[ScheduleSettingsManager]；
 * - 第一周日期：提醒日历 → 设置里的开学日期 → 学期推算（与课表页的
 *   `displayedTimeBase` 同一条兜底链，只是不做"回写"——小组件是纯读者）；
 * - 节次时间：提醒日历的 periodStarts/Ends → 设置里的节次时间表 → 内置默认。
 *
 * 缓存为空（新装、未登录、演示模式）时返回 null，小组件显示引导文案。
 */
object WidgetSnapshotBuilder {

    data class WidgetCourse(
        val name: String,
        val location: String,
        val startPeriod: Int,
        val endPeriod: Int,
        val startTime: String,
        val endTime: String,
        /** 该次上课的开始时刻；仅"下一节课"分支保证非空。 */
        val startsAtMillis: Long = 0L,
        /** 展示用的相对描述：今天/明天/周X。 */
        val dayLabel: String = ""
    )

    data class Snapshot(
        val week: Int?,
        val todayDay: Int,
        val dateLabel: String,
        val todayCourses: List<WidgetCourse>,
        val next: WidgetCourse?,
        /** 不可用时的引导文案（未登录/无缓存），可用时为空。 */
        val notice: String = ""
    )

    fun build(context: Context, now: Long = System.currentTimeMillis()): Snapshot? {
        val user = UserManager.getInstance()
        if (!user.isLoggedIn || user.isDemoMode) {
            return Snapshot(null, 0, "", emptyList(), null, notice = "登录后显示课表")
        }
        val school = user.currentSchool ?: return Snapshot(null, 0, "", emptyList(), null, notice = "登录后显示课表")
        val account = user.currentAccountStorageKey
        if (account.isBlank()) return null

        val settings = ScheduleSettingsManager.getInstance().apply { init(context) }
        val store = ScheduleCacheStore(context.getSharedPreferences("schedule_cache", Context.MODE_PRIVATE))
        val term = store.currentTerm(account, school.id)
        val json = store.read(account, school.id, term) ?: return Snapshot(
            null, 0, "", emptyList(), null, notice = "打开 App 同步一次课表"
        )
        val records = ScheduleJson.parse(json)?.map { it.course } ?: return null
        val custom = settings.getCustomCourses(account).map {
            com.tyust.course.schedule.ScheduleCourseRecord(it.id, it.name, it.teacher, it.location,
                it.day, it.startPeriod, it.endPeriod, it.weeks, custom = true)
        }
        val all = records + custom

        val reminderBase = ScheduleReminderScheduler.get(context).timeBase(account, term.id)
        val firstWeekDate = resolveFirstWeekDate(settings, reminderBase, term.id)
        val week = ScheduleDates.weekAt(firstWeekDate, now)

        val zone = TimeZone.getDefault()
        val calendar = Calendar.getInstance(zone).apply { timeInMillis = now }
        val todayDay = ((calendar.get(Calendar.DAY_OF_WEEK) + 5) % 7) + 1
        val dateLabel = "%d月%d日 %s".format(
            Locale.ROOT,
            calendar.get(Calendar.MONTH) + 1,
            calendar.get(Calendar.DAY_OF_MONTH),
            listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")[todayDay - 1]
        )

        val periodStarts = periodTimes(settings, reminderBase).first
        val periodEnds = periodTimes(settings, reminderBase).second

        val visibleToday = all.filter { it.day == todayDay && ScheduleWeeks.parse(it.weeks).visibleIn(week ?: return@filter false) }
            .ifEmpty { emptyList() }
        val todayCourses = visibleToday.sortedBy { it.startPeriod }.map {
            WidgetCourse(it.name, it.location, it.startPeriod, it.endPeriod,
                periodStarts[it.startPeriod].orEmpty(), periodEnds[it.endPeriod].orEmpty())
        }

        val next = findNext(all, firstWeekDate, periodStarts, week, now, zone)
        return Snapshot(week, todayDay, dateLabel, todayCourses, next)
    }

    /**
     * 下一节课：从"现在"往后找最近的上课时刻。
     * 今天已开始的课程跳过（开课后 15 分钟内仍算"进行中"可选，这里从简：过了开始时刻就不算下一节）。
     */
    private fun findNext(
        all: List<com.tyust.course.schedule.ScheduleCourseRecord>,
        firstWeekDate: String,
        periodStarts: Map<Int, String>,
        currentWeek: Int?,
        now: Long,
        zone: TimeZone
    ): WidgetCourse? {
        val monday = ScheduleDates.firstMonday(firstWeekDate, zone) ?: return null
        var best: Pair<com.tyust.course.schedule.ScheduleCourseRecord, Long>? = null
        val startWeek = currentWeek?.coerceIn(1, com.tyust.course.schedule.ScheduleMaxWeeks) ?: 1
        for (course in all) {
            val weeks = ScheduleWeeks.parse(course.weeks)
            if (!weeks.valid) continue
            for (week in weeks.weeks) {
                if (week < startWeek) continue
                val dayOffset = (week - 1) * 7 + course.day - 1
                val parts = periodStarts[course.startPeriod]?.split(':')?.map { it.toIntOrNull() } ?: continue
                if (parts.size != 2 || parts[0] == null || parts[1] == null) continue
                val occurrence = (monday.clone() as Calendar).apply {
                    add(Calendar.DAY_OF_YEAR, dayOffset)
                    set(Calendar.HOUR_OF_DAY, parts[0]!!)
                    set(Calendar.MINUTE, parts[1]!!)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                val start = occurrence.timeInMillis
                if (start <= now) continue
                val current = best
                if (current == null || start < current.second) best = course to start
                break // 同一门课只需要最近的一次
            }
        }
        val (course, start) = best ?: return null
        val diffDays = java.util.concurrent.TimeUnit.MILLISECONDS.toDays(
            dayFloor(start, zone) - dayFloor(now, zone)
        ).toInt()
        val dayLabel = when {
            diffDays <= 0 -> "今天"
            diffDays == 1 -> "明天"
            else -> "周" + "一二三四五六日"[course.day - 1]
        }
        return WidgetCourse(
            name = course.name,
            location = course.location,
            startPeriod = course.startPeriod,
            endPeriod = course.endPeriod,
            startTime = periodStarts[course.startPeriod].orEmpty(),
            endTime = "",
            startsAtMillis = start,
            dayLabel = dayLabel
        )
    }

    private fun dayFloor(millis: Long, zone: TimeZone): Long = Calendar.getInstance(zone).apply {
        timeInMillis = millis
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /** 与课表页同一条兜底链（只读，不回写）：提醒日历 → 设置开学日期 → 学期推算。 */
    private fun resolveFirstWeekDate(
        settings: ScheduleSettingsManager,
        base: ScheduleTimeBase?,
        termId: String
    ): String {
        val usable: (String?) -> String? = { it?.takeIf(String::isNotBlank)?.let { v -> ScheduleDates.normalizeFirstWeekDate(v) } }
        val storedMillis = settings.semesterStartDate
        val storedDate = if (storedMillis > 0L) usable(ScheduleTimeBase.dateFromMillis(storedMillis)) else null
        return usable(base?.firstWeekDate)
            ?: storedDate
            ?: ScheduleDates.rememberedDefaultFirstWeekDate(termId)
            ?: ScheduleDates.resolveFirstWeekDate(termId)
    }

    private fun periodTimes(
        settings: ScheduleSettingsManager,
        base: ScheduleTimeBase?
    ): Pair<Map<Int, String>, Map<Int, String>> {
        val defaults = settings.getPeriodTimes().associateBy({ it.period }, { it })
        val starts = base?.periodStarts?.takeIf { it.isNotEmpty() }
            ?: defaults.mapValues { it.value.startTime }
        val ends = base?.periodEnds?.takeIf { it.isNotEmpty() }
            ?: defaults.mapValues { it.value.endTime }
        return starts to ends
    }

    /** 给单元测试/其它调用方暴露的日历兜底入口。 */
    fun calendarTermId(): String = AcademicStudyReader.calendarTerm().id
}
