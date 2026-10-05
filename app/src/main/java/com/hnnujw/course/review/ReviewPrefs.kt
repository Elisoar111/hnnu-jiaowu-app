package com.hnnujw.course.review

import android.content.Context

/**
 * 复习模块的本地偏好。
 *
 * 刻意**不用** `AiConfigStore` 那套（它管的是 Keystore 密文）；这里只有普通布尔开关，
 * 明文 SharedPreferences 足够，也不涉及任何隐私。
 *
 * 三项：
 *  - **选项乱序**：默认 **关**。乱序会改变用户对"答案是 A"的肌肉记忆，且和题库纸质
 *    材料对不上。它是一个"我想要才开"的偏好，不该替用户默认打开。
 *  - **自动下一题**：默认 **关**。打开后选完选项就跳走，**看不到这道题的解析与对错** ——
 *    那是"刷题"的用法，不是"学题"的用法。同一个道理：不能替用户默认打开。
 *  - **演示题库已播种**：见 [BundledBanks.ensureDemoBank]。它不是"用户偏好"，
 *    而是"这件事只做一次"的标记 —— 但同属模块级的轻量状态，放一起比再开一个
 *    prefs 文件省事。**刻意纳入备份**：换机恢复后不该又冒出一个演示题库。
 */
object ReviewPrefs {

    private const val PREFS = "review_prefs"
    private const val KEY_SHUFFLE_OPTIONS = "shuffle_options"
    private const val KEY_AUTO_NEXT = "auto_next"
    private const val KEY_DEMO_SEEDED = "demo_bank_seeded"

    /** 是否打乱选项显示顺序。 */
    fun isShuffleOptions(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SHUFFLE_OPTIONS, false)

    fun setShuffleOptions(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SHUFFLE_OPTIONS, enabled).apply()
    }

    /**
     * 选完选项后是否**自动跳到下一题**（2026-10 用户要求「开启自动下一题的按钮」）。
     *
     * 它只管"跳不跳"，**不管判分**：跳过的题作答照旧留着，交卷时由
     * `PracticeSession.judgePending()` 统一判对错 —— 所以开着它一路刷到底，
     * 成绩一样是准的，只是中途看不到这道题的解析与对错。
     */
    fun isAutoNext(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_NEXT, false)

    fun setAutoNext(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_NEXT, enabled).apply()
    }

    /** 演示题库是否已经播种过。true 表示"这辈子只做一次"这件事已经做完了。 */
    fun isDemoBankSeeded(context: Context): Boolean =
        prefs(context).getBoolean(KEY_DEMO_SEEDED, false)

    fun setDemoBankSeeded(context: Context, seeded: Boolean) {
        prefs(context).edit().putBoolean(KEY_DEMO_SEEDED, seeded).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
