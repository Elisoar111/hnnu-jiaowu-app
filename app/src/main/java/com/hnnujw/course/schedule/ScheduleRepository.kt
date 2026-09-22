package com.hnnujw.course.schedule

import android.content.Context
import com.hnnujw.course.academic.AcademicStudyParser
import com.hnnujw.course.manager.ScheduleSettingsManager

data class ScheduleSnapshot(
    val account: String,
    val school: String,
    val term: String,
    val courses: List<ScheduleCourseRecord>,
    val timeBase: ScheduleTimeBase,
    val cachedAt: Long,
    val hasCache: Boolean
)

/**
 * 课表持久数据的统一读取口：App 页面与**桌面小组件**共用同一份本地缓存，
 * 不触网、不需要登录态（自旧仓库移植，适配本仓库的设置/日历存储）。
 *
 * 时间基准的兜底链与 `ScheduleRoute.displayedTimeBase` 同源：
 * 提醒日历 → 设置里的学期起始日 → 按学期推算，保证小组件在
 * 用户没手动设过日期时也能算出"今天有哪些课"。
 */
class ScheduleRepository(
    private val context: Context,
    private val settings: ScheduleSettingsManager = ScheduleSettingsManager.getInstance().apply { init(context) }
) {
    fun snapshot(account: String, school: String, termId: String? = null): ScheduleSnapshot {
        val cachePrefs = context.getSharedPreferences("schedule_cache", Context.MODE_PRIVATE)
        val cache = ScheduleCacheStore(cachePrefs)
        val term = termId?.takeIf { it.isNotBlank() }?.let(AcademicStudyParser::term)
            ?: cache.currentTerm(account, school)
        val json = cache.read(account, school, term)
        val network = json?.let(ScheduleJson::parse).orEmpty().map { it.course }
        val records = mergeCustom(network, settings.getCustomCourses(account))
        return ScheduleSnapshot(
            account, school, term.id, records,
            timeBase(account, term.id, cache.currentTerm(account, school).id),
            cachePrefs.getLong("schedule_${account}_${school}_${term.id}_time", 0), json != null
        )
    }

    fun timeBase(account: String, term: String, currentTerm: String): ScheduleTimeBase {
        val periods = settings.getPeriodTimes()
        val store = ScheduleCalendarStore(context.getSharedPreferences("course_reminders", Context.MODE_PRIVATE))
        if (account.isNotBlank() && term == currentTerm) {
            store.migrateLegacy(account, currentTerm, ScheduleTimeBase(
                ScheduleTimeBase.dateFromMillis(settings.semesterStartDate),
                periods.associate { it.period to it.startTime },
                periods.associate { it.period to it.endTime }))
        }
        val calendar = store.read(account, term)
        val resolved = calendar?.firstWeekDate?.takeIf { it.isNotBlank() && ScheduleDates.firstMonday(it) != null }
            ?: ScheduleTimeBase.dateFromMillis(settings.semesterStartDate).takeIf { it.isNotBlank() }
            ?: ScheduleDates.resolveFirstWeekDate(term)
        return ScheduleTimeBase(
            resolved,
            periods.associate { it.period to it.startTime } + calendar?.periodStarts.orEmpty(),
            periods.associate { it.period to it.endTime } + calendar?.periodEnds.orEmpty()
        )
    }

    companion object {
        fun mergeCustom(network: List<ScheduleCourseRecord>, custom: List<ScheduleSettingsManager.CustomCourse>): List<ScheduleCourseRecord> =
            network.filterNot { it.custom } + custom.map {
                ScheduleCourseRecord("custom:${it.id}", it.name, it.teacher,
                    it.location, it.day, it.startPeriod, it.endPeriod, it.weeks, true)
            }
    }
}
