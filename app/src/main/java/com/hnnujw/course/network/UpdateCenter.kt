package com.hnnujw.course.network

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.hnnujw.course.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
     *
     * 🔴 **`pending` 必须在主线程写**（2026-10-19 修）：它是 Compose 的
     * `mutableStateOf`，在 IO 线程上赋值不会可靠地让界面重组 —— 表现就是
     * 「启动时自动检查从不弹窗，只有手动点『检查更新』才弹」。手动那条走的是
     * 主线程（点击回调里），所以一直是好的，掩盖了这个问题。
     * 现在无论调用方在哪个线程，状态写都收敛到主线程（[publish]）。
     */
    suspend fun checkQuietly(context: Context) {
        val app = context.applicationContext
        val store = prefs(app)
        val now = System.currentTimeMillis()
        if (!shouldAutoCheck(now, store.getLong(KEY_LAST_CHECK, 0L), MIN_INTERVAL_MS)) return

        // 网络请求本身已在 AppUpdateChecker 内部切到 IO，这里不必再包一层；
        // 再包一层反而会把下面的状态写带离主线程。
        val result = AppUpdateChecker.check(BuildConfig.VERSION_NAME)
        // 只有**真的问到了结果**才记账。失败也记账的话，一次地铁里的断网就会让这台
        // 设备在接下来 6 小时里彻底不查更新 —— 而用户很可能一直在正常用网。
        if (result !is AppUpdateChecker.Result.Failure) {
            store.edit().putLong(KEY_LAST_CHECK, now).apply()
        }

        val info = (result as? AppUpdateChecker.Result.Update)?.info ?: return
        if (!shouldPrompt(info.forceUpdate, info.versionName, store.getString(KEY_SKIPPED, null))) return
        publish(info)
    }

    /** 用户主动点「检查更新」。结果原样返回给调用方做 toast。 */
    suspend fun checkNow(context: Context): AppUpdateChecker.Result {
        publishChecking(true)
        return try {
            val result = AppUpdateChecker.check(BuildConfig.VERSION_NAME)
            val store = prefs(context)
            store.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
            if (result is AppUpdateChecker.Result.Update) {
                // 手动检查 = 用户明确想看，之前跳过过的标记作废
                store.edit().remove(KEY_SKIPPED).apply()
                publish(result.info)
            }
            result
        } finally {
            publishChecking(false)
        }
    }

    /**
     * 把「待展示的更新」推到界面。
     *
     * 统一走主线程：Compose 状态的写入方与读方（重组）必须在同一个可观测快照里，
     * 后台线程直接赋值可能只改值不触发重组。
     */
    private suspend fun publish(info: AppUpdateInfo) = withContext(Dispatchers.Main.immediate) {
        pending = info
    }

    private suspend fun publishChecking(value: Boolean) = withContext(Dispatchers.Main.immediate) {
        checking = value
    }

    /**
     * 自动检查的节流判据（纯函数，便于单测）。
     *
     * 两种记录不可信的情况都直接判定"该查了"：
     *  - `lastCheckMillis <= 0`：从未查过（全新安装）；
     *  - `lastCheckMillis > nowMillis`：**时钟被往回调过**（用户手改时间、NTP 回拨）。
     *    此时差值是负数，若按 `now - lastCheck >= interval` 判断，会一直不满足 ——
     *    这台设备可能几十天都不再检查更新，且没有任何报错。
     */
    fun shouldAutoCheck(nowMillis: Long, lastCheckMillis: Long, intervalMillis: Long = MIN_INTERVAL_MS): Boolean {
        if (lastCheckMillis <= 0L) return true
        if (lastCheckMillis > nowMillis) return true
        return nowMillis - lastCheckMillis >= intervalMillis
    }

    /**
     * 是否应该弹窗（纯函数，便于单测）。
     *
     * 强制更新**无视**「以后再说」——那个标记是给可选更新用的；
     * 拿它去压住一个"不升级就没法用"的版本，只会把用户永远留在旧版上。
     */
    fun shouldPrompt(forceUpdate: Boolean, remoteVersion: String, skippedVersion: String?): Boolean =
        forceUpdate || remoteVersion != skippedVersion

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
