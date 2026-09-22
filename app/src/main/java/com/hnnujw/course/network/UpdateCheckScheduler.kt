package com.hnnujw.course.network

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * 版本巡检的闹钟：让 App **没打开**时也能发现新版本。
 *
 * 为什么用 AlarmManager 而不是 WorkManager：本工程没有引入 `androidx.work`，
 * 而考试提醒那条链路（[com.hnnujw.course.schedule.ExamReminderScheduler]）已经在用
 * 「AlarmManager + 广播接收器」这套模式 —— 沿用同一套，不加依赖，行为也一致。
 *
 * 刻意**不用 `setRepeating`**：它从 API 19 起就不精确，且进了 Doze 会被整体推迟到
 * 下一个维护窗口（可能几天后）。这里用「排一次 → 收到后自己排下一次」的自我续期，
 * 配合 `setAndAllowWhileIdle` 才能真的穿过 Doze。
 */
object UpdateCheckScheduler {

    /** 一天一次。发版频率不高，更勤只是白打网络。 */
    private const val INTERVAL_MS = 24 * 60 * 60 * 1000L
    private const val REQUEST_CODE = 0x5551

    const val ACTION = "com.hnnujw.course.action.CHECK_UPDATE"

    /**
     * 排下一次巡检。**幂等**：同一个 PendingIntent（相同 requestCode + action）
     * 会被 `set` 直接覆盖，所以每次冷启动都调用不会有多个闹钟堆起来。
     */
    fun schedule(context: Context) {
        val app = context.applicationContext
        val manager = app.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val triggerAt = System.currentTimeMillis() + INTERVAL_MS
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                manager.setAndAllowWhileIdle(AlarmManager.RTC, triggerAt, pendingIntent(app))
            } else {
                manager.set(AlarmManager.RTC, triggerAt, pendingIntent(app))
            }
        } catch (_: SecurityException) {
            // 个别 ROM 会拦 AlarmManager：拦了就退化成"只在冷启动时检查更新"，
            // 主流程（启动时的那次检查）不受影响，不必让用户看到异常。
        }
    }

    fun cancel(context: Context) {
        val app = context.applicationContext
        val manager = app.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        manager.cancel(pendingIntent(app))
    }

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, UpdateCheckReceiver::class.java).setAction(ACTION)
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
