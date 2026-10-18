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
    private const val KEY_KWH_THRESHOLD = "threshold_kwh"

    /** 默认阈值 20 元（用户指定）。 */
    const val DEFAULT_THRESHOLD_YUAN: Double = 20.0

    /** 阈值可调范围：太小会天天响，太大没意义。 */
    const val MIN_THRESHOLD_YUAN: Double = 1.0
    const val MAX_THRESHOLD_YUAN: Double = 500.0

    /**
     * 度数型费项的默认阈值：20 度。
     *
     * 与金额阈值同数不同量纲。学校宿舍一天大约 3~6 度，20 度约等于"还能撑三四天"，
     * 与 20 元在金额型费项上的提前量相当。
     */
    const val DEFAULT_THRESHOLD_KWH: Double = 20.0

    /** 度数阈值范围：1~200 度。上限比金额宽松（一个学期不清零的宿舍可能存上百元/度）。 */
    const val MIN_THRESHOLD_KWH: Double = 1.0
    const val MAX_THRESHOLD_KWH: Double = 200.0

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

    // —— 度数型费项（剩余电量/剩余水费）的独立阈值 ——
    //
    // 为什么需要独立的一份：金额与度数的量纲、量级、心理预期都不同。
    // "低于 20 元提醒"对金额型合理，但度数型如果也套 20，含义就变成"低于 20 度"——
    // 而一个宿舍一天用 3~6 度，20 度还有三四天，两者恰好接近，纯属巧合。
    // 学校若把某费项从金额改成度数，共用同一个阈值就会突然变得过松或过紧。
    // 更关键的是**单位不能混**：把 312.2 度当成 312.2 元会直接绕过 20 元阈值
    // （这正是 YktBalance 注释里记着的那次误判风险）。

    /** 读度数阈值；损坏值回落默认。 */
    fun thresholdKwh(context: Context): Double {
        val raw = prefs(context).getString(KEY_KWH_THRESHOLD, null)
        val parsed = raw?.toDoubleOrNull() ?: DEFAULT_THRESHOLD_KWH
        return clampKwhThreshold(parsed)
    }

    fun setThresholdKwh(context: Context, value: Double) {
        prefs(context).edit()
            .putString(KEY_KWH_THRESHOLD, clampKwhThreshold(value).toString())
            .apply()
    }

    /** 归一化度数阈值。 */
    fun clampKwhThreshold(value: Double): Double = when {
        value.isNaN() || value.isInfinite() -> DEFAULT_THRESHOLD_KWH
        value < MIN_THRESHOLD_KWH -> MIN_THRESHOLD_KWH
        value > MAX_THRESHOLD_KWH -> MAX_THRESHOLD_KWH
        else -> value
    }

    /**
     * 按费项单位取对应阈值。
     *
     * 这是**唯一**应该被调用方使用的取阈值入口 —— 让"用哪个阈值"由单位决定，
     * 而不是让每个调用点自己判断，避免某处漏判就把度数当金额。
     *
     * `UNKNOWN` 单位返回 null：单位都认不出来时**不提醒**，
     * 与 `YktAlertPolicy` 里"余额解析失败就不报"是同一条原则（宁可漏报不可误报）。
     */
    fun thresholdFor(context: Context, unit: ElectricityUnit): Double? = when (unit) {
        ElectricityUnit.YUAN -> thresholdYuan(context)
        ElectricityUnit.KWH -> thresholdKwh(context)
        ElectricityUnit.UNKNOWN -> null
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
