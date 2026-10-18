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
 * ## 提醒频率（2026-09-25 用户要求后调整；2026-10-05 加同余额节流）
 *
 * **只要余额低于阈值就提醒**——但同一余额在 24 小时内不重复发
 * （见 [YktAlertPolicy.decide]）。巡检是**六小时一轮**（[YktAlertScheduler]），
 * 所以：余额持续下降时最坏一天 4 条（余额在掉本来就该催），余额不动时一天一条。
 *
 * 通知 id 仍是固定值：同一 id 的新通知会**覆盖**旧的，
 * 所以用户通知栏里始终只有一条最新余额（而不是堆 N 条）。
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
     * @param balance 当前读数；null = 未能获取
     * @param threshold 阈值（与 [unit] 同量纲）
     * @param unit 读数的量纲。默认 `YUAN` 让既有调用点不用改；
     *   度数型费项**必须**显式传 `KWH`，否则通知文案会把"度"说成"元"。
     * @return 是否真的发了通知
     */
    fun evaluateAndNotify(
        context: Context,
        accountKey: String,
        balance: Double?,
        threshold: Double,
        unit: ElectricityUnit = ElectricityUnit.YUAN,
    ): Boolean {
        val app = context.applicationContext
        val previous = YktAlertStateStore.read(app, accountKey)
        val decision = YktAlertPolicy.decide(balance, threshold, previous, System.currentTimeMillis())

        // 不提醒的分支（余额回升 → 重置、余额未知 → 原样、节流命中 → 沿用）照常落盘：
        // 「余额已回升」这一步必须被记住，否则下一次跌破会被当成"仍在同一周期"而漏报。
        if (!decision.shouldNotify) {
            YktAlertStateStore.write(app, accountKey, decision.nextState)
            return false
        }

        // 要提醒时：**先确认真的送达，再记「已提醒」**。
        //
        // 原先是「先落盘 nextState 再 post」：`post` 在通知被关闭时返回 false，
        // 但状态里 `lastNotifiedAtMillis` 已经被写成当前时刻 —— 同一余额 24h 节流
        // 于是把这次提醒直接吞掉，用户打开通知也要等到明天才可能再响。
        // 与考前提醒「先确认送达、再消费计划」是同一条不变量。
        val amount = balance ?: return false
        if (!post(app, amount, threshold, unit)) return false
        YktAlertStateStore.write(app, accountKey, decision.nextState)
        return true
    }

    private fun post(
        context: Context,
        balance: Double,
        threshold: Double,
        unit: ElectricityUnit,
    ): Boolean {
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

        val text = YktAlertPolicy.notificationText(balance, threshold, unit)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_course_reminder)
            .setContentTitle(YktAlertPolicy.notificationTitle(unit))
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
