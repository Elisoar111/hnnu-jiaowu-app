package com.hnnujw.course.network

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/**
 * 版本巡检的闹钟：让 App **没打开**时也能发现新版本。
 *
 * 为什么用 AlarmManager 而不是 WorkManager：本工程没有引入 `androidx.work`，
 * 而考试提醒那条链路（[com.hnnujw.course.schedule.ExamReminderScheduler]）已经在用
 * 「AlarmManager + 广播接收器」这套模式 —— 沿用同一套，不加依赖，行为也一致。
 *
 * ## 🔴 链式单次 + 持久化触发时刻（2026-10 横向排查修复）
 *
 * 旧写法虽然是「排一次 → 收到后自己排下一次」的链式，但每次冷启动
 * （MainActivity.onCreate）的 schedule 都把触发时刻重置成 `now + 24h` ——
 * 用户每天打开 App 的场景下，后台巡检**永远不触发**（与电费/消息巡检同型）。
 *
 * 现在的语义是「**恢复**而不是重排」：下一轮触发时刻持久化（[KEY_NEXT_TRIGGER]），
 * 冷启动按存储值排；存储值已过期（进程死亡超过一个周期）→ 尽快补跑一轮。
 * 巡检触发后由 [UpdateCheckReceiver] 调 [scheduleNext] 自我续期。
 *
 * 刻意**不用 `setRepeating`**：它从 API 19 起就不精确，且进了 Doze 会被整体推迟。
 * `setAndAllowWhileIdle` 才能真的穿过 Doze。用 RTC（非 WAKEUP）：设备睡眠时
 * 推迟到唤醒即可，"发现新版本"不需要从睡眠里把机器叫起来。
 */
object UpdateCheckScheduler {

    /** 一天一次。发版频率不高，更勤只是白打网络。 */
    private const val INTERVAL_MS = 24 * 60 * 60 * 1000L

    /** 冷启动发现"该巡检了但闹钟没触发"（进程死亡超期 / 闹钟被清）时的补跑延迟。 */
    private const val CATCH_UP_DELAY_MS = 30_000L

    private const val REQUEST_CODE = 0x5551
    private const val PREFS = "update_check_scheduler"
    private const val KEY_NEXT_TRIGGER = "next_trigger_at"

    const val ACTION = "com.hnnujw.course.action.CHECK_UPDATE"

    /**
     * 确保下一轮巡检已排上（冷启动 / 系统事件时调用）。
     *
     * 触发时刻取**存储值**：仍在未来则原样恢复（冷启动不再顺延），
     * 已过期则尽快补跑一轮。
     */
    fun schedule(context: Context) {
        scheduleAt(context, storedNextTrigger(context))
    }

    /** 巡检触发完成后的自我续期：下一轮 = 现在 + 24 小时。 */
    fun scheduleNext(context: Context) {
        scheduleAt(context, System.currentTimeMillis() + INTERVAL_MS)
    }

    fun cancel(context: Context) {
        val app = context.applicationContext
        val manager = app.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pending = pendingIntent(app)
        runCatching {
            manager.cancel(pending)
            pending.cancel()
        }
        prefs(app).edit().remove(KEY_NEXT_TRIGGER).apply()
    }

    private fun scheduleAt(context: Context, triggerAt: Long) {
        val app = context.applicationContext
        val manager = app.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val now = System.currentTimeMillis()
        val trigger = if (triggerAt > now) triggerAt else now + CATCH_UP_DELAY_MS
        try {
            manager.setAndAllowWhileIdle(AlarmManager.RTC, trigger, pendingIntent(app))
            prefs(app).edit().putLong(KEY_NEXT_TRIGGER, trigger).apply()
        } catch (_: SecurityException) {
            // 个别 ROM 会拦 AlarmManager：拦了就退化成"只在冷启动时检查更新"，
            // 主流程（启动时的那次检查）不受影响，不必让用户看到异常。
        }
    }

    private fun storedNextTrigger(context: Context): Long =
        prefs(context).getLong(KEY_NEXT_TRIGGER, 0L)

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

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
