package com.hnnujw.course.review

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 「今日待复习」提醒：每天固定时刻检查一次错题本，有到期的就发一条通知。
 *
 * ## 为什么需要它
 *
 * 错题本已经有完整的间隔复习调度（[ReviewScheduling] 的 `due_at`、
 * [ReviewDatabase.dueWrongCount]），练习页能算出「今日待复习 N 题」。
 * 但**没有任何通知** —— 用户不主动点进来就永远不知道。
 * 间隔复习的价值全在"按时回来复习"，不提醒等于这套机制没生效。
 *
 * ## 实现：复用项目已有的链式单次闹钟
 *
 * 和电费/消息巡检用同一套模式，理由见 `YktAlertScheduler` 的长注释：
 * `setInexactRepeating` 在每次冷启动都会用 `FLAG_UPDATE_CURRENT` 重建 PendingIntent、
 * 把系统里的触发时刻一并重置，等于"每天打开 App 就把闹钟往后推一天"，
 * 有些用户永远等不到。所以这里也是：
 *
 *  - 链式单次闹钟，下一轮由本轮触发时自己排；
 *  - 触发时刻持久化，开机/更新后按存储值恢复（不重新算"现在 + 24h"）；
 *  - 用 `setAndAllowWhileIdle` 而非 exact：晚上八点晚十几分钟完全没影响，
 *    而 exact 在 API 31+ 需要单独申请权限，为这个场景不值得。
 *
 * ## 默认关闭
 *
 * 与"课程提醒"不同，复习提醒是**新增的打扰**。默认关，用户主动在设置里打开。
 * 开关状态存 prefs，`schedule()` 每次都会读它 —— 关闭时顺手取消已排的闹钟。
 */
object ReviewReminderScheduler {

    private const val TAG = "ReviewReminder"

    private const val PREFS = "review_reminder"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_NEXT_TRIGGER = "next_trigger_at"

    private const val REQUEST_CODE = 0x6C31
    private const val CATCH_UP_DELAY_MS = 30_000L

    private const val CHANNEL = "review_due"
    private const val NOTIFICATION_ID = 0x6C32

    const val ACTION = "com.hnnujw.course.action.REVIEW_REMINDER"

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    /** 打开/关闭提醒。关闭时连闹钟一起取消，不留一个"还会响"的悬空计划。 */
    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
        if (enabled) schedule(context) else cancel(context)
    }

    /**
     * 排下一次提醒。
     *
     * 触发时刻取**存储值**：还在未来就原样恢复（无副作用），被系统清掉则重建，
     * 已经错过就尽快补跑一轮。绝不从"现在 + 24h"算起。
     */
    fun schedule(context: Context) {
        val app = context.applicationContext
        if (!isEnabled(app)) {
            // 关着的时候也要清：否则"先开着又关掉"会留下一个已经排好的闹钟。
            cancelAlarm(app)
            return
        }
        scheduleAt(app, prefs(app).getLong(KEY_NEXT_TRIGGER, 0L))
    }

    /** 本轮触发后自我续期：按**每天固定时刻**算下一次，而不是"现在 + 24h"。 */
    fun scheduleNext(context: Context) {
        val app = context.applicationContext
        if (!isEnabled(app)) return
        scheduleAt(app, ReviewReminderTime.nextTrigger(System.currentTimeMillis()))
    }

    fun cancel(context: Context) {
        val app = context.applicationContext
        prefs(app).edit().remove(KEY_NEXT_TRIGGER).apply()
        cancelAlarm(app)
    }

    private fun cancelAlarm(app: Context) {
        val manager = app.getSystemService(AlarmManager::class.java) ?: return
        val pending = pendingIntent(app)
        runCatching {
            manager.cancel(pending)
            pending.cancel()
        }
    }

    private fun scheduleAt(context: Context, triggerAt: Long) {
        val app = context.applicationContext
        val manager = app.getSystemService(AlarmManager::class.java) ?: return
        val now = System.currentTimeMillis()
        val trigger = when {
            // 没存过（首次开启）：按固定时刻算。
            triggerAt <= 0L -> ReviewReminderTime.nextTrigger(now)
            // 还在未来：原样恢复。
            triggerAt > now -> triggerAt
            // 已错过：尽快补跑一轮。
            else -> now + CATCH_UP_DELAY_MS
        }
        try {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pendingIntent(app))
            prefs(app).edit().putLong(KEY_NEXT_TRIGGER, trigger).apply()
        } catch (e: SecurityException) {
            // 个别 ROM 拦 AlarmManager：退化成"只在打开练习页时检查"。
            // 不记 error —— 这是已知的平台差异，不是应用 bug。
            Log.w(TAG, "复习提醒闹钟排不上（ROM 限制）: ${e.message}")
        }
    }

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, ReviewReminderReceiver::class.java).setAction(ACTION)
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/**
 * 复习提醒的广播接收器。
 *
 * 它做三件事：排下一轮（链式闹钟靠这里不断）→ 算到期数 → 有就发通知。
 * 顺序很关键：**先排下一轮**再去查库发通知。反过来的话，一旦查库抛异常，
 * 这条链就断在这里，之后永远不再提醒。
 */
class ReviewReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val action = intent.action
        if (action == ReviewReminderScheduler.ACTION) {
            ReviewReminderScheduler.scheduleNext(app)
        } else {
            // 开机 / 应用更新 / 改时间：系统清掉了闹钟，按存储值恢复。
            ReviewReminderScheduler.schedule(app)
            return
        }
        // 通知必须**先确认送达**再当作已完成 —— 与项目里其它四条推送同一条不变量。
        // 这里不消费任何"已播报"状态（每次都是重新算到期数），所以只需把结果记进日志。
        runCatching { ReviewReminderNotifier.notifyIfDue(app) }
            .onFailure { Log.w("ReviewReminder", "检查到期错题失败: ${it.message}") }
    }
}
