package com.hnnujw.course.ui.screen

/**
 * 星期条滑块（"圆圈"）该落在哪一格 —— 0-based 下标；返回 `null` = **这一条不该有滑块**。
 *
 * 这是"滑到哪天，日期行就圈出哪一格"的**全部决策逻辑**，刻意抽成无 Compose 依赖的
 * 纯函数，好让 JVM 单测把它钉住（同 `GradeRowIdentity.kt` 的做法）。
 *
 * 三条规则，优先级从高到低（**顺序不可交换**）：
 * 1. **日视图**：滑块 = 正在浏览的那一天。日视图的 pager 是逐日翻页的，
 *    调用方传进来的 `selectedDay` 由 `pager.currentPage` 派生，于是滑动过程中
 *    滑块会跟着走 —— 这正是需求里那个"圆圈有对应的显示"。
 * 2. **周视图且本周**：滑块 = 今天。
 * 3. **周视图且非本周**：`null`，不画滑块。
 *
 * 第 3 条是刻意的：周视图一次浏览一整周，本来就没有"正在看哪一天"这回事，
 * 滑块只用于表达"今天是这一天"。翻到别的周时今天并不在这一周里，
 * 若把滑块锚定在第 1 格，用户会以为"正在看周一" —— 那是错的。
 *
 * 第 1 条必须排在第 2、3 条之前：日视图翻到非本周时若被下面两条抢走，
 * 滑块会停在第 1 格或整条消失，"日视图跟随滑动"就失效了。
 *
 * @param dayView 是否日视图
 * @param selectedDay 正在浏览的星期（1..7）
 * @param isCurrentWeek 当前页是否就是本周
 * @param currentDayOfWeek 真实的今天星期几（1..7）
 * @param dayCount 星期条格子数（隐藏周末时为 5）
 * @return 0-based 的格子下标；无滑块或 `dayCount <= 0` 时返回 null
 */
internal fun scheduleDateStripSelection(
    dayView: Boolean,
    selectedDay: Int,
    isCurrentWeek: Boolean,
    currentDayOfWeek: Int,
    dayCount: Int,
): Int? {
    if (dayCount <= 0) return null
    val last = dayCount - 1
    return when {
        dayView -> (selectedDay - 1).coerceIn(0, last)
        isCurrentWeek -> (currentDayOfWeek - 1).coerceIn(0, last)
        else -> null
    }
}
