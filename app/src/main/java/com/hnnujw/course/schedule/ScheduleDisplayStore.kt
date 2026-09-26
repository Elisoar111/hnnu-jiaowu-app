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
    private const val KEY_SHOW_WEEKEND = "show_weekend"

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

    /**
     * true = 周视图显示周六/周日（默认）；false = 只显示周一至周五。
     *
     * 默认 true 是刻意的：隐藏周末会让周末的课在周视图里看不见，
     * 这是个"少看东西"的偏好，不能替用户默认打开。
     * 日视图不受此开关影响（见 [scheduleVisibleDays]）——否则周末的课将无处可看。
     */
    fun showWeekend(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_SHOW_WEEKEND, true)

    fun setShowWeekend(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_SHOW_WEEKEND, value).apply()
    }
}
