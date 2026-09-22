package com.hnnujw.course.schedule

/**
 * 分页器与外部周次请求的双向同步。
 *
 * 页单元（unit）由调用方定义：周视图里 unit = 周次（1..25），日视图里
 * unit = (周-1)*7 + 星期（1..175）。本类只负责两件事：
 * ① 外部（日历/学期切换/补课跳转）带着 key 请求某个 unit 时，给出应跳的页码；
 * ② 用户手势落定的页码换算回 unit 上报——但绝不把上报再当成一次新跳转。
 *
 * 默认 firstUnit=1、lastUnit=[ScheduleMaxWeeks]，即旧的纯周次行为；
 * 日视图传 (1, 175) 即可复用同一套语义（单元测试两种模式都覆盖）。
 *
 * Calendar changes request a page; settled user swipes only report the page for restoration.
 */
internal class ScheduleWeekPagerSync(
    initialUnit: Int,
    initialRequestKey: String?,
    private val firstUnit: Int = 1,
    private val lastUnit: Int = ScheduleMaxWeeks
) {
    init {
        require(firstUnit <= lastUnit) { "firstUnit($firstUnit) > lastUnit($lastUnit)" }
    }

    private var requestKey = initialRequestKey
    private var requestedUnit = initialUnit.coerceIn(firstUnit, lastUnit)
    private var pendingUnit: Int? = requestedUnit
    private var lastSettledUnit: Int? = null
    private var lastReportedUnit: Int? = null

    fun requestPage(unit: Int, key: String?): Int? {
        val requested = unit.coerceIn(firstUnit, lastUnit)
        // Callers without a calendar key can still request a unit directly.
        val directRequest = key == null && requested != requestedUnit && requested != lastReportedUnit
        if (key != requestKey || directRequest) pendingUnit = requested
        requestKey = key
        requestedUnit = requested
        return pendingUnit?.minus(firstUnit)
    }

    fun settledUnit(page: Int): Int? {
        val unit = (page + firstUnit).coerceIn(firstUnit, lastUnit)
        if (pendingUnit != null) {
            if (unit == pendingUnit) {
                pendingUnit = null
                lastSettledUnit = unit
            }
            return null
        }
        if (unit == lastSettledUnit) return null
        lastSettledUnit = unit
        lastReportedUnit = unit
        return unit
    }
}
