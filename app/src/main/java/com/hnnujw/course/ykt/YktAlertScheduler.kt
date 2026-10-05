package com.hnnujw.course.ykt

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/**
 * 电费巡检的闹钟：让 App **没打开**时也能发现余额不足并发通知。
 *
 * ## 🔴 为什么是「链式单次 + 持久化触发时刻」，而不是 `setInexactRepeating`
 *
 * 旧实现是 `setInexactRepeating(RTC_WAKEUP, now + 24h, 24h)`，并在**每次进程冷启动**
 * （CourseApplication.onCreate）都调用一次 schedule。`FLAG_UPDATE_CURRENT` 覆盖旧的
 * PendingIntent 时会把触发时刻一并重置 —— 于是只要用户每天打开 App（进程每天至少
 * 重建一次），巡检就被每天往后推 24 小时，**后台巡检永远不触发**，提醒只剩下
 * 「用户打开一卡通页面拉到余额时判一次」这一条路径（2026-10 用户报：
 * 「每当我进入一卡通时，才给我发送缴费提醒」）。
 *
 * 现在的语义是「**恢复**而不是重排」：
 *  - 下一次触发时刻持久化在 prefs（[KEY_NEXT_TRIGGER]）；
 *  - 冷启动 schedule()：按**存储值**排（闹钟即使被系统清了也回到原时刻）；
 *  - 存储值已过期（进程死了超过一个周期，该巡检没巡成）→ 排 [CATCH_UP_DELAY_MS]
 *    后**立即补跑一轮** —— 错过的巡检不丢；
 *  - 巡检触发后由 [YktAlertReceiver] 调 [scheduleNext] 自我续期。
 *
 * 每条设计取舍：
 * - 用 AlarmManager 而非 WorkManager：本工程没有 `androidx.work` 依赖。
 * - 用 `setAndAllowWhileIdle` 而非 exact：晚几十分钟提醒电费毫无影响，而 exact
 *   在 Android 12+ 需要用户额外授权"闹钟与提醒"权限，不值得。
 * - **六小时一轮**（[INTERVAL_MS]）：电费是慢变量，但"达到阈值就提醒"（2026-10
 *   用户要求）值得一天查 4 次。防轰炸交给 [YktAlertPolicy] 的同余额 24h 节流。
 *
 * ⚠️ **后台能否真正跑起来，还取决于系统的电池优化策略**（见 [YktBackgroundPermission]）：
 * 被列入"电池优化白名单之外"的 App 在部分 ROM 上闹钟不可靠，
 * 表现为"提醒时有时无"。界面上提供入口引导用户把本 App 加入白名单。
 */
object YktAlertScheduler {

    /** 六小时一轮：一天最多 4 次巡检，配合同余额 24h 节流不会刷屏。 */
    private const val INTERVAL_MS = 6 * 60 * 60 * 1000L

    /** 冷启动发现"该巡检了但闹钟没触发"（进程死亡超期 / 闹钟被清）时的补跑延迟。 */
    private const val CATCH_UP_DELAY_MS = 30_000L

    private const val REQUEST_CODE = 0x5962
    private const val PREFS = "ykt_alert_scheduler"
    private const val KEY_NEXT_TRIGGER = "next_trigger_at"

    const val ACTION = "com.hnnujw.course.action.YKT_ALERT_CHECK"

    /**
     * 确保下一轮巡检已排上（冷启动 / 系统事件时调用）。
     *
     * 触发时刻取**存储值**：闹钟还在则恢复同一时刻（无副作用），被清则重建，
     * 已过期则尽快补跑一轮。**绝不**从"现在 + 周期"算起 —— 那正是旧实现的 bug。
     */
    fun schedule(context: Context) {
        val stored = storedNextTrigger(context)
        scheduleAt(context, stored)
    }

    /** 巡检触发完成后的自我续期：下一轮 = 现在 + 周期。 */
    fun scheduleNext(context: Context) {
        scheduleAt(context, System.currentTimeMillis() + INTERVAL_MS)
    }

    fun cancel(context: Context) {
        val app = context.applicationContext
        val manager = app.getSystemService(AlarmManager::class.java) ?: return
        val pending = pendingIntent(app)
        runCatching {
            manager.cancel(pending)
            pending.cancel()
        }
        prefs(app).edit().remove(KEY_NEXT_TRIGGER).apply()
    }

    private fun scheduleAt(context: Context, triggerAt: Long) {
        val app = context.applicationContext
        val manager = app.getSystemService(AlarmManager::class.java) ?: return
        val now = System.currentTimeMillis()
        // 存储时刻仍在未来 → 原样恢复；已过期（错过）→ 尽快补跑一轮。
        val trigger = if (triggerAt > now) triggerAt else now + CATCH_UP_DELAY_MS
        try {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pendingIntent(app))
            prefs(app).edit().putLong(KEY_NEXT_TRIGGER, trigger).apply()
        } catch (_: SecurityException) {
            // 个别 ROM 拦 AlarmManager：退化成"只在打开 App / 一卡通页面时检查"，
            // 不影响主流程（界面拉到余额时同样会判提醒）。
        }
    }

    private fun storedNextTrigger(context: Context): Long =
        prefs(context).getLong(KEY_NEXT_TRIGGER, 0L)

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, YktAlertReceiver::class.java).setAction(ACTION)
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
