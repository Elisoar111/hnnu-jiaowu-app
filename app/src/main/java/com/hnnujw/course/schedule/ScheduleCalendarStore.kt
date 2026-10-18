package com.hnnujw.course.schedule

import android.content.SharedPreferences
import org.json.JSONObject

internal class ScheduleCalendarStore(private val preferences: SharedPreferences) {
    private fun normalized(value: ScheduleTimeBase): ScheduleTimeBase = value.copy(
        firstWeekDate = ScheduleDates.normalizeFirstWeekDate(value.firstWeekDate) ?: value.firstWeekDate
    )

    fun read(account: String, term: String): ScheduleTimeBase? = preferences.getString("calendar:$account|$term", null)?.let {
        runCatching { normalized(ReminderJson.timeBase(JSONObject(it))) }.getOrNull()
    }

    fun write(account: String, term: String, value: ScheduleTimeBase): Boolean {
        val calendar = normalized(value)
        if (account.isBlank() || term.isBlank() || read(account, term) == calendar) return false
        preferences.edit().putString("calendar:$account|$term", ReminderJson.timeBase(calendar).toString()).apply()
        return true
    }

    /**
     * 把调用方给的日历并进已有记录。
     *
     * 两条不变量：
     *  - **已有的第一周日期不被覆盖**。用户可能在设置里显式选过，迁移只是兜底。
     *  - **缺失的节次时间必须补齐**。这一条原先是「已有第一周日期就整条跳过」，
     *    而 `ScheduleRoute` 会先写一条只有日期的记录，于是节次时间再也没机会写进来。
     *    后果是提醒规划器 `CourseReminderPlanner` 解析不出上课时刻、返回 `NeedsTime`，
     *    **一条闹钟都不排**——用户看到的就是「开了课程提醒却从不响」。
     *
     * 所以这里从「有日期就不管」改成「合并」：日期各自保留，节次时间取并集
     * （已有值优先，因为是用户自己调过的）。
     *
     * The caller supplies the actual current term, never the term the user is browsing.
     */
    fun migrateLegacy(account: String, currentTerm: String, value: ScheduleTimeBase): Boolean {
        val marker = "legacy-calendar-migrated:$account"
        if (account.isBlank() || currentTerm.isBlank()) return false
        val migratedTerm = preferences.getString(marker, null)
        if (migratedTerm != null && migratedTerm != currentTerm) return false
        val legacy = normalized(value)
        val existing = read(account, currentTerm)
        // A period-only record or an earlier empty migration must not discard the old start date.
        // 反过来，已经存下日期也不代表这条记录是完整的 —— 节次时间可能还空着。
        val hasDate = ScheduleDates.firstMonday(legacy.firstWeekDate) != null ||
            !existing?.firstWeekDate.isNullOrBlank()
        if (!hasDate) return false
        val merged = ScheduleTimeBase(
            firstWeekDate = existing?.firstWeekDate?.takeIf { it.isNotBlank() } ?: legacy.firstWeekDate,
            periodStarts = legacy.periodStarts + existing?.periodStarts.orEmpty(),
            periodEnds = legacy.periodEnds + existing?.periodEnds.orEmpty()
        )
        if (migratedTerm == null) preferences.edit().putString(marker, currentTerm).apply()
        return write(account, currentTerm, merged)
    }
}
