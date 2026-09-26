package com.hnnujw.course.ykt

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.hnnujw.course.R
import com.hnnujw.course.YktActivity

/**
 * 「宿舍电费余额不足」的系统通知。
 *
 * 与 [com.hnnujw.course.network.UpdateNotifier] / [com.hnnujw.course.academic.MessageCenterNotifier]
 * 同构。
 *
 * ## 提醒频率（2026-09-25 用户要求后调整）
 *
 * **只要低于阈值就提醒，不限制次数**（见 [YktAlertPolicy.decide]）。
 * 实际频率受后台巡检周期约束——[YktAlertScheduler] 是**一天一次**，
 * 所以最坏情况是每天一条，不会刷屏。
 *
 * 通知 id 仍是固定值：同一 id 的新通知会**覆盖**旧的，
 * 所以用户通知栏里始终只有一条最新余额（而不是堆 N 条）——
 * 这正是"每次都提醒"却又不打扰的关键。
 *
 * 渠道用 `IMPORTANCE_HIGH`：电费欠费会导致宿舍断电，属于需要立即知道的事。
 * **渠道 importance 创建后改不动**（本项目已踩过：强提醒必须新建渠道），
 * 所以要调整强度只能换渠道 ID，不要试图改这条。
 */
object YktAlertNotifier {

    private const val CHANNEL = "ykt_balance_alert"
    private const val NOTIFICATION_ID = 0x5961

    /**
     * 判定并（必要时）发出提醒。
     *
     * @param balance 当前电费余额（元）；null = 未能获取
     * @param threshold 阈值（元）
     * @return 是否真的发了通知
     */
    fun evaluateAndNotify(
        context: Context,
        accountKey: String,
        balance: Double?,
        threshold: Double,
    ): Boolean {
        val app = context.applicationContext
        val previous = YktAlertStateStore.read(app, accountKey)
        val decision = YktAlertPolicy.decide(balance, threshold, previous)

        // 状态无条件落盘：即使不发通知，"余额已回升 → 重置"这一步也必须记住，
        // 否则下一次跌破会被当成"仍在同一周期"而漏报。
        YktAlertStateStore.write(app, accountKey, decision.nextState)

        if (!decision.shouldNotify) return false
        return post(app, balance ?: return false, threshold)
    }

    private fun post(context: Context, balance: Double, threshold: Double): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        if (Build.VERSION.SDK_INT >= 26 && manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL,
                    "电费余额提醒",
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = "宿舍电费低于设定阈值时提醒，避免欠费断电"
                }
            )
        }
        if (!canNotify(context)) return false

        val open = Intent(context, YktActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val content = PendingIntent.getActivity(
            context,
            0,
            open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val text = YktAlertPolicy.notificationText(balance, threshold)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_course_reminder)
            .setContentTitle(YktAlertPolicy.notificationTitle())
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(content)
            .setAutoCancel(true)
            .build()
        return try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
            true
        } catch (_: SecurityException) {
            // 通知权限在发的一瞬间被撤销，忽略即可
            false
        }
    }

    private fun canNotify(context: Context): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT < 33) return true
        return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }
}
