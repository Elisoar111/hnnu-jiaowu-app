package com.hnnujw.course.ui.system

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.util.Log
import com.hnnujw.course.BuildConfig

/**
 * Backdrop 的运行期开关。
 *
 * **当前策略（实验期）：只要系统版本够（>= Android 12 / S）就恒启用** —— 既不按设备品牌
 * 预筛，也**不因历史崩溃熔断降级**。上一进程是否异常退出（crash / native crash / ANR）
 * 只用来写一条 `Log.w` 取证，不产生任何行为改变；`KEY_DISABLED_VERSION` 是历史字段，
 * 现在每次启动都会主动清掉。
 *
 * 换句话说：外部看到的是"永远开启"。[isBackdropEnabled] 返回 false 的唯一原因是系统版本
 * 低于 S。要恢复"崩溃后降级"时，[didPreviousProcessFail] 已经能识别三种失败原因、
 * [disableDynamicOpticsForSession] 也能按会话关掉动态折射 —— 缺的只是把两者接成
 * "写 disabledVersion → 下次启动读它"这条线。
 */
object GlassRuntimeGuard {
    private const val TAG = "GlassRuntimeGuard"
    private const val PREFS_NAME = "glass_runtime_guard"
    private const val KEY_SESSION_USED_GLASS = "session_used_glass"
    private const val KEY_DISABLED_VERSION = "disabled_version"

    @Volatile
    private var initialized = false

    @Volatile
    private var enabled = true

    @Volatile
    private var dynamicOpticsEnabled = true

    @Volatile
    private var sessionMarked = false

    private var appContext: Context? = null

    fun initialize(context: Context) {
        val applicationContext = context.applicationContext
        appContext = applicationContext
        sessionMarked = false
        dynamicOpticsEnabled = true

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            enabled = false
            initialized = true
            return
        }

        val preferences = applicationContext.getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE
        )
        // 实验期开关：不再因历史崩溃熔断降级。
        // 仅记录上一进程是否失败用于日志取证，不写 disabledVersion。
        val previousSessionUsedGlass = preferences.getBoolean(
            KEY_SESSION_USED_GLASS,
            false
        )
        val previousProcessFailed = previousSessionUsedGlass &&
            didPreviousProcessFail(applicationContext)
        if (previousProcessFailed) {
            Log.w(TAG, "Previous process exit looked like a failure; keeping Backdrop enabled (experimental)")
        }
        preferences.edit()
            .remove(KEY_SESSION_USED_GLASS)
            .remove(KEY_DISABLED_VERSION)
            .apply()

        // 实验期恒启用（仅受 SDK 下限约束），后续可恢复为熔断策略。
        enabled = true
        initialized = true
    }

    fun isBackdropEnabled(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        if (initialized && !enabled) return false

        markGlassSession()
        return true
    }

    fun isDynamicOpticsEnabled(): Boolean =
        enabled && dynamicOpticsEnabled

    fun disableDynamicOpticsForSession(error: Throwable? = null) {
        if (!dynamicOpticsEnabled) return
        dynamicOpticsEnabled = false
        if (error == null) {
            Log.w(TAG, "Dynamic glass optics disabled for this session")
        } else {
            Log.w(TAG, "Dynamic glass optics disabled for this session", error)
        }
    }

    private fun markGlassSession() {
        if (sessionMarked) return

        synchronized(this) {
            if (sessionMarked) return
            val context = appContext ?: return
            sessionMarked = true
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_SESSION_USED_GLASS, true)
                .apply()
        }
    }

    private fun didPreviousProcessFail(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false

        return runCatching {
            val activityManager = context.getSystemService(ActivityManager::class.java)
            val previousExit = activityManager
                .getHistoricalProcessExitReasons(null, 0, 1)
                .firstOrNull()
                ?: return@runCatching false

            previousExit.reason in failedExitReasons
        }.getOrElse { error ->
            Log.w(TAG, "Unable to inspect the previous process exit", error)
            false
        }
    }

    private val failedExitReasons = setOf(
        ApplicationExitInfo.REASON_CRASH,
        ApplicationExitInfo.REASON_CRASH_NATIVE,
        ApplicationExitInfo.REASON_ANR
    )
}
