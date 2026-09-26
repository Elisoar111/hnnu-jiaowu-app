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
        val currentTerm = cache.currentTerm(account, school)
        val term = termId?.takeIf { it.isNotBlank() }?.let(AcademicStudyParser::term) ?: currentTerm
        val json = cache.read(account, school, term)
        val network = json?.let(ScheduleJson::parse).orEmpty().map { it.course }
        val records = mergeCustom(network, settings.getCustomCourses(account))
        return ScheduleSnapshot(
            account, school, term.id, records,
            timeBase(account, term.id),
            cachePrefs.getLong("schedule_${account}_${school}_${term.id}_time", 0), json != null
        )
    }

    /**
     * 取某学期的时间基准。**纯读，不写盘。**
     *
     * 桌面卡片每次刷新都会走到这里，读接口一旦带副作用，"刷新"就变成了"写 prefs"，
     * 调用方也无法从签名判断代价。历史日历迁移已挪到 [migrateLegacyCalendar]。
     */
    fun timeBase(account: String, term: String): ScheduleTimeBase {
        val periods = settings.getPeriodTimes()
        val store = ScheduleCalendarStore(context.getSharedPreferences("course_reminders", Context.MODE_PRIVATE))
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

    /**
     * 把旧格式的课表日历迁进 `course_reminders`。**会写 prefs**，所以只允许在显式的
     * "写"时机调用 —— 目前是 `CourseApplication` 主进程 onCreate 的一次调用。
     *
     * 它原先挂在 [timeBase] 里，两个问题同时存在：① 读路径每次刷新都要过一遍迁移判定
     * （数据不巧时真的会反复 `write`）；② [timeBase] 只由 [snapshot] 触达，所以
     * **从没加过桌面组件、也没打开过组件工作台的用户，迁移根本不会发生**。
     * 挪到启动路径后，既不再有副作用，覆盖范围反而变大了。
     */
    fun migrateLegacyCalendar(account: String, school: String): Boolean {
        if (account.isBlank() || school.isBlank()) return false
        val cache = ScheduleCacheStore(context.getSharedPreferences("schedule_cache", Context.MODE_PRIVATE))
        val term = cache.currentTerm(account, school)
        val periods = settings.getPeriodTimes()
        val store = ScheduleCalendarStore(context.getSharedPreferences("course_reminders", Context.MODE_PRIVATE))
        return store.migrateLegacy(account, term.id, ScheduleTimeBase(
            ScheduleTimeBase.dateFromMillis(settings.semesterStartDate),
            periods.associate { it.period to it.startTime },
            periods.associate { it.period to it.endTime }
        ))
    }

    companion object {
        fun mergeCustom(network: List<ScheduleCourseRecord>, custom: List<ScheduleSettingsManager.CustomCourse>): List<ScheduleCourseRecord> =
            network.filterNot { it.custom } + custom.map {
                ScheduleCourseRecord("custom:${it.id}", it.name, it.teacher,
                    it.location, it.day, it.startPeriod, it.endPeriod, it.weeks, true)
            }
    }
}
