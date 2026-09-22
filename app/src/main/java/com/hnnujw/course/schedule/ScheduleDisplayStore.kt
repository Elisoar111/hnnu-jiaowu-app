package com.hnnujw.course.schedule

import android.content.Context

/**
 * 课表展示偏好（日视图 / 周视图）。
 *
 * 视图偏好是「设备 + 个人习惯」层面的选择，不随账号切换而变，
 * 所以放在独立 prefs 里全局生效，不进按账号隔离的设置存储。
 */
object ScheduleDisplayStore {
    private const val PREFS_NAME = "schedule_display"
    private const val KEY_DAY_VIEW = "day_view"
    private const val KEY_COMPACT = "compact"

    /** true = 日视图（按天翻页）；false = 周视图（按周翻页，默认）。 */
    fun dayView(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_DAY_VIEW, false)

    fun setDayView(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_DAY_VIEW, value).apply()
    }

    /** true = 紧凑显示密度（节次行高 ×0.78，参考项目同款）；false = 标准。 */
    fun compact(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_COMPACT, false)

    fun setCompact(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_COMPACT, value).apply()
    }
}
