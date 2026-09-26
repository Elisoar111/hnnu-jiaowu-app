package com.hnnujw.course.network

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.hnnujw.course.BuildConfig

/**
 * 版本更新中心：把「什么时候检查」和「提示过哪个版本」收在一处，
 * 让启动时的自动检查与「我的 → 检查更新」共用同一份状态。
 *
 * 为什么不直接写在 SettingsRoute 里：自动检查发生在 MainActivity 启动阶段，
 * 而手动检查在「我的」页。两边各存一份状态的话，用户在设置页点过「以后再说」，
 * 下次冷启动还会被同一个版本再弹一次。
 */
object UpdateCenter {

    private const val PREFS = "app_update"
    private const val KEY_LAST_CHECK = "last_check_ms"
    private const val KEY_SKIPPED = "skipped_version"

    /**
     * 自动检查的节流窗口。手动检查不受它限制。
     * 半天一次：发版频率不高，太频繁只是白打网络。
     */
    private const val MIN_INTERVAL_MS = 6 * 60 * 60 * 1000L

    /** 非空 = 需要在界面上展示更新弹窗。 */
    var pending by mutableStateOf<AppUpdateInfo?>(null)
        private set

    var checking by mutableStateOf(false)
        private set

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * 启动时的静默检查。
     *
     * 节流 + 「以后再说」双重过滤：同一个版本被跳过之后不再自动弹，
     * 但用户在设置页手动点「检查更新」时仍能看到它（见 [checkNow]）。
     */
    suspend fun checkQuietly(context: Context) {
        val app = context.applicationContext
        val store = prefs(app)
        val now = System.currentTimeMillis()
        if (now - store.getLong(KEY_LAST_CHECK, 0L) < MIN_INTERVAL_MS) return

        val result = AppUpdateChecker.check(BuildConfig.VERSION_NAME)
        // 只有**真的问到了结果**才记账。失败也记账的话，一次地铁里的断网就会让这台
        // 设备在接下来 6 小时里彻底不查更新 —— 而用户很可能一直在正常用网。
        if (result !is AppUpdateChecker.Result.Failure) {
            store.edit().putLong(KEY_LAST_CHECK, now).apply()
        }

        val info = (result as? AppUpdateChecker.Result.Update)?.info ?: return
        // 强制更新版本**无视「以后再说」**：那个标记是给可选更新用的；
        // 拿它去压住一个"不升级就没法用"的版本，只会把用户永远留在旧版上。
        if (!info.forceUpdate && info.versionName == store.getString(KEY_SKIPPED, null)) return
        pending = info
    }

    /** 用户主动点「检查更新」。结果原样返回给调用方做 toast。 */
    suspend fun checkNow(context: Context): AppUpdateChecker.Result {
        checking = true
        return try {
            val result = AppUpdateChecker.check(BuildConfig.VERSION_NAME)
            val store = prefs(context)
            store.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
            if (result is AppUpdateChecker.Result.Update) {
                // 手动检查 = 用户明确想看，之前跳过过的标记作废
                store.edit().remove(KEY_SKIPPED).apply()
                pending = result.info
            }
            result
        } finally {
            checking = false
        }
    }

    /** 用户点「以后再说」：记住这个版本，本次不再提示。强制更新不记。 */
    fun dismiss(context: Context) {
        // 强制更新不写跳过标记：它本来就不提供"稍后"（弹窗只在确实装不上时才允许关闭），
        // 写进去反而会让下次自动检查把这个版本永久压掉。
        pending?.takeIf { !it.forceUpdate }
            ?.let { prefs(context).edit().putString(KEY_SKIPPED, it.versionName).apply() }
        pending = null
    }

    /** 弹窗自行关闭（如已跳转安装）时清状态，不写跳过标记。 */
    fun consume() {
        pending = null
    }
}
