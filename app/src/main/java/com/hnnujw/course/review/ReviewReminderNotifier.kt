package com.hnnujw.course.review

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.hnnujw.course.R
import com.hnnujw.course.ReviewActivity

/**
 * 复习提醒的通知发射器。
 *
 * ## 与其它四条推送共用的一条不变量
 *
 * **先确认送达、再当作已完成**。`NotificationManagerCompat.notify` 在通知权限被
 * 关闭 / 渠道被禁用时会静默返回（不抛异常），所以这里把结果用 `areNotificationsEnabled`
 * 判一次再发，并**返回是否真的发出去了** —— 调用方据此决定要不要把它算作"已提醒"。
 *
 * 本项目另外四条推送（成绩/消息/电费/考试）都栽过同一个坑：先记账后发送，
 * 通知被关时事件被永久吞掉，用户重开通知也补不回来。
 * 复习提醒不需要"补发历史"（每次都是重新算到期数），所以只需要结果正确、日志准确。
 */
object ReviewReminderNotifier {

    private const val CHANNEL = "review_due"
    private const val NOTIFICATION_ID = 0x6C32

    /**
     * 有到期题就发一条通知。
     *
     * @return 是否真的发出了。
     *
     * ⚠️ 必须在后台线程调用：内部会查 SQLite（`dueWrongCount`）。
     */
    fun notifyIfDue(context: Context): Boolean {
        val app = context.applicationContext
        // 开关在 scheduler 那边已经判过，但这里再判一次：调用点可能不止广播一个，
        // 而"关掉开关却还收到提醒"是最伤信任的一类 bug。
        if (!ReviewReminderScheduler.isEnabled(app)) return false
        val due = runCatching { ReviewDatabase.get(app).dueWrongCount() }.getOrDefault(0)
        if (!ReviewReminderTime.shouldNotify(enabled = true, dueCount = due)) return false
        return post(app, due)
    }

    /** 发通知，返回是否真的送出。 */
    private fun post(context: Context, dueCount: Int): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        if (Build.VERSION.SDK_INT >= 26 && manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "错题复习提醒", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "每天到点时提醒你复习到期的错题"
                }
            )
        }
        // 通知被关的时候 notify 会静默成功返回，所以必须先问一句。
        val compat = NotificationManagerCompat.from(context)
        if (!compat.areNotificationsEnabled()) return false
        // 显式检查运行时权限（Android 13+ 的 POST_NOTIFICATIONS）。
        //
        // `areNotificationsEnabled()` 已经覆盖了大部分情况，但它**不是**权限检查：
        // 权限被拒时它可能仍返回 true（取决于 ROM），而实际 notify 会被系统丢掉或抛
        // SecurityException。两件事都要问 —— lint 的 MissingPermission 也正是这个意思。
        if (Build.VERSION.SDK_INT >= 33) {
            val granted = androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!granted) return false
        }
        if (Build.VERSION.SDK_INT >= 26) {
            if (manager.getNotificationChannel(CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE) return false
        }

        val open = Intent(context, ReviewActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val content = PendingIntent.getActivity(
            context, 0, open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_course_reminder)
            .setContentTitle(ReviewReminderTime.title())
            .setContentText(ReviewReminderTime.body(dueCount))
            .setStyle(NotificationCompat.BigTextStyle().bigText(ReviewReminderTime.body(dueCount)))
            .setContentIntent(content)
            .setAutoCancel(true)
            // 同一天多次触发只更新这一条，不要叠成一堆。
            .setOnlyAlertOnce(true)
            .build()
        return runCatching {
            compat.notify(NOTIFICATION_ID, notification)
            true
        }.getOrElse {
            // SecurityException：权限在"问"与"发"之间被撤销，极小概率但要兜住。
            false
        }
    }
}
