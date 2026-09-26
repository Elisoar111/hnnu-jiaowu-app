package com.hnnujw.course.schedule

/** 一周七天全画（含周六、周日）。 */
internal const val ScheduleDaysWithWeekend = 7

/** 只画周一至周五。 */
internal const val ScheduleDaysWeekdayOnly = 5

/**
 * 课表这一屏要画几天。
 *
 * 日视图**恒为 7**，不跟随「显示周末」开关。这是刻意的：
 * 周末的课只可能在日视图里被翻到，日视图也收成 5 天，用户一旦隐藏周末
 * 就再也看不到周六周日的课了 —— 那不是"隐藏"，是"丢数据"。
 * 隐藏周末时周视图画 5 列，周末课程改由日视图承担展示，
 * 并在网格下方提示"另有 N 节周末课程"。
 *
 * 另：日视图恒 7 还有一个结构性好处 —— 分页的页单元换算
 * （`(周-1)*7 + 天`）不必跟着这个开关变，翻页逻辑与开关解耦。
 *
 * @param dayView true = 日视图（按天翻页）。
 * @param showWeekend 用户在设置里的「显示周末」。
 */
internal fun scheduleVisibleDays(dayView: Boolean, showWeekend: Boolean): Int =
    if (dayView || showWeekend) ScheduleDaysWithWeekend else ScheduleDaysWeekdayOnly

/**
 * 被「隐藏周末」挡住、在周视图里看不到的课程数。
 *
 * 返回 0 表示没有课程被藏起来（要么开关是开的，要么这周本来就没有周末课），
 * 此时不该给任何提示 —— 空提示比没有提示更烦人。
 *
 * @param courseDays 本周课程的星期集合（1=周一 … 7=周日）。
 */
internal fun hiddenWeekendCourseCount(courseDays: Iterable<Int>, showWeekend: Boolean): Int =
    if (showWeekend) 0 else courseDays.count { it > ScheduleDaysWeekdayOnly }
