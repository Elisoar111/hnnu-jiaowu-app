package com.hnnujw.course.ui.screen

import androidx.compose.runtime.compositionLocalOf

/**
 * 课表字号倍率（1.0 = 原样），**只作用于课表网格内的文字**。
 *
 * 为什么不逐个 Text 改 fontSize：课表里的字号散落在时间列、课程卡片、补课标记等
 * 十几处，逐个改既容易漏，也很容易和"固定高度容器 + 只写 fontSize 不写 lineHeight
 * → 一个字都不画"那个老坑撞上。这里改成**整体缩放渲染密度**：
 *
 * 在 `ScheduleGrid` 里把 [androidx.compose.ui.unit.Density] 的 `fontScale` 乘上这个倍率。
 * 于是：
 * - `dp` 完全不受影响 → 网格几何、时间列宽度、行高等布局一个像素都不动；
 * - `sp` 换算出的像素等比放大 → 课表里所有文字一起变大变小；
 * - `rememberCoursePeriodHeight` 是用同一个（已缩放的）密度去实测文字的，
 *   所以行高会跟着文字自动长高，不会把字挤到"一行都排不下"。
 *
 * 其它页面（成绩、二课、设置）不读这个值，因此不受影响。
 */
val LocalScheduleFontScale = compositionLocalOf { 1f }

/**
 * 设置页「课表字号」的档位（倍率 -> 显示名）。
 *
 * 上下限与 [com.hnnujw.course.manager.ScheduleSettingsManager] 里的一致：
 * 再大就会出现单元格装不下整行的排版风险，再小则失去可读性。
 */
val ScheduleFontScaleOptions: List<Pair<Float, String>> = listOf(
    0.85f to "小",
    1.00f to "标准",
    1.15f to "大",
    1.30f to "特大"
)

/** 倍率 → 档位名。不在档位上时取最接近的一档，避免旧数据/手工改值显示空白。 */
fun scheduleFontScaleLabel(scale: Float): String {
    val nearest = ScheduleFontScaleOptions.minByOrNull { kotlin.math.abs(it.first - scale) }
    return nearest?.second ?: "标准"
}

/** 倍率 → 档位下标（给滚轮用）。 */
fun scheduleFontScaleIndex(scale: Float): Int {
    var bestIndex = 0
    var bestDelta = Float.MAX_VALUE
    ScheduleFontScaleOptions.forEachIndexed { index, option ->
        val delta = kotlin.math.abs(option.first - scale)
        if (delta < bestDelta) {
            bestDelta = delta
            bestIndex = index
        }
    }
    return bestIndex
}
