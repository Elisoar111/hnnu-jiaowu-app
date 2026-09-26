package com.hnnujw.course.ykt

import android.content.Context
import android.content.SharedPreferences
import com.hnnujw.course.manager.UserManager

/**
 * 电费低余额提醒的设置：开关 + 阈值。
 *
 * ## 为什么阈值是**全局**的而不是按账号
 *
 * 与课表展示三开关同理：这是"这台手机上我想被怎么提醒"的偏好，
 * 不是某个账号的属性。同一部手机换账号时用户不会希望阈值被重置回 20。
 *
 * ## 为什么默认开启
 *
 * 这个提醒的价值全在"用户还没意识到快没电了"——默认关闭等于没有。
 * 但**必须允许关闭**，所以设置项与阈值都可改。
 */
object YktAlertSettings {

    private const val PREFS = "ykt_alert_prefs"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_THRESHOLD = "threshold_yuan"

    /** 默认阈值 20 元（用户指定）。 */
    const val DEFAULT_THRESHOLD_YUAN: Double = 20.0

    /** 阈值可调范围：太小会天天响，太大没意义。 */
    const val MIN_THRESHOLD_YUAN: Double = 1.0
    const val MAX_THRESHOLD_YUAN: Double = 500.0

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    /** 读阈值；越界或损坏的值一律回落到默认值，不让脏数据把提醒变成"永不触发"。 */
    fun thresholdYuan(context: Context): Double {
        val raw = prefs(context).getString(KEY_THRESHOLD, null)
        val parsed = raw?.toDoubleOrNull() ?: DEFAULT_THRESHOLD_YUAN
        return clampThreshold(parsed)
    }

    fun setThresholdYuan(context: Context, value: Double) {
        prefs(context).edit()
            .putString(KEY_THRESHOLD, clampThreshold(value).toString())
            .apply()
    }

    /** 归一化阈值：非有限值回默认，越界夹到边界。 */
    fun clampThreshold(value: Double): Double = when {
        value.isNaN() || value.isInfinite() -> DEFAULT_THRESHOLD_YUAN
        value < MIN_THRESHOLD_YUAN -> MIN_THRESHOLD_YUAN
        value > MAX_THRESHOLD_YUAN -> MAX_THRESHOLD_YUAN
        else -> value
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * 提醒去重用的键：账号 + 阈值。
     *
     * 用 `UserManager.toStorageKey` 同款规则归一化账号——本工程并存两种账号键口径，
     * 不归一化会出现「按 A 键写入、按 B 键读取」，表现为提醒反复发或永不发。
     */
    internal fun stateKey(accountKey: String): String =
        accountKey.ifBlank { "default" }.replace(Regex("[^A-Za-z0-9_.-]"), "_")
}
