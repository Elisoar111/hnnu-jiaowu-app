package com.hnnujw.course.review

import android.content.Context

/**
 * AI 悬浮球的位置记忆。
 *
 * ## 为什么存**相对比例**而不是像素
 *
 * 原先球的位置只活在 `Animatable` 里，旋转屏幕或重启应用就回默认位。
 * 但直接存像素是错的：同一台手机旋转后宽高互换、换设备后分辨率不同、
 * 分屏/多窗口下容器还会变 —— 存下来的 `x=980px` 在另一套尺寸下要么越界、
 * 要么落在完全不相干的位置。
 *
 * 所以存的是**球心在容器里的相对位置**（0..1），落位时再乘回当前容器尺寸：
 *
 *  - 旋转：比例不变 → 球回到视觉上"同一个角落"；
 *  - 换设备：比例不变 → 仍然是那个相对位置；
 *  - 分屏变窄：比例不变，再经一次夹取就不会越界。
 *
 * ## 只存纵向，横向由吸附决定
 *
 * 球的横向位置本来就会被吸附到左/右边缘（见 `snapTargetX`），所以真正需要
 * 记住的只有"贴在左边还是右边"+"高度在哪"。横向存一个布尔，
 * 纵向存比例 —— 比存一对坐标更稳，也不会因为宽度变化而漂移。
 *
 * ## 越界一律回落
 *
 * 存进来的值可能是旧版本写的、或被手工改过的。所有读取都夹取，
 * 读不到就用默认值，绝不让一个坏值把球放到屏幕外（那等于球消失，用户没法再拖回来）。
 */
object FloatingBallPosition {

    private const val PREFS = "review_prefs"
    private const val KEY_RATIO_Y = "ai_ball_ratio_y"
    private const val KEY_ON_RIGHT = "ai_ball_on_right"

    /** 默认纵向位置：约 38% 高度（与原先的硬编码一致）。 */
    const val DEFAULT_RATIO_Y = 0.38f

    /**
     * 纵向比例的合法范围。
     *
     * 不取 0..1：贴到最上/最下时球会有一半压在状态栏或底栏下面，看起来像被裁掉了。
     * 0.05~0.85 覆盖了"球心能落在"的合理区间，剩下的由落位时的像素夹取兜底。
     */
    private const val MIN_RATIO = 0.05f
    private const val MAX_RATIO = 0.85f

    /** 读上次的纵向比例。坏值/未存过一律回默认。 */
    fun ratioY(context: Context): Float {
        val raw = prefs(context).getFloat(KEY_RATIO_Y, DEFAULT_RATIO_Y)
        return clampRatio(raw)
    }

    /** 读上次是否贴在右边。未存过时默认贴右（与原先一致）。 */
    fun onRight(context: Context): Boolean = prefs(context).getBoolean(KEY_ON_RIGHT, true)

    /**
     * 记住当前位置。
     *
     * @param ratioY 球心在容器里的纵向比例（0..1），内部会夹取。
     * @param onRight 球心是否在右半屏。
     */
    fun save(context: Context, ratioY: Float, onRight: Boolean) {
        prefs(context).edit()
            .putFloat(KEY_RATIO_Y, clampRatio(ratioY))
            .putBoolean(KEY_ON_RIGHT, onRight)
            .apply()
    }

    /** 归一化比例：非有限值回默认，越界夹到边界。 */
    fun clampRatio(value: Float): Float = when {
        value.isNaN() || value.isInfinite() -> DEFAULT_RATIO_Y
        value < MIN_RATIO -> MIN_RATIO
        value > MAX_RATIO -> MAX_RATIO
        else -> value
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
