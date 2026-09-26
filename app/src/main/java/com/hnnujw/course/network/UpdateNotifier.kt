package com.hnnujw.course.network

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.hnnujw.course.MainActivity
import com.hnnujw.course.R

/**
 * 「发现新版本」的系统通知。
 *
 * 与 [UpdateCenter] 的应用内弹窗是**两条互补的路**：
 * - 弹窗只在用户主动打开 App 时才有机会出现；
 * - 后台巡检（[UpdateCheckReceiver]）跑在 App 根本没启动的时候，只能靠通知触达。
 *
 * 同一个版本**只通知一次**（prefs 里的 `notified_version`）：巡检是每天一次的，
 * 不去重的话用户会连着一周每天收到同一条"发现新版本"。
 */
object UpdateNotifier {

    private const val CHANNEL = "app_update"
    private const val NOTIFICATION_ID = 0x5552

    /** 与 [UpdateCenter] 共用 `app_update` 这份 prefs，避免再多一个存储点。 */
    private const val PREFS = "app_update"
    private const val KEY_NOTIFIED = "notified_version"

    /** 这个版本还没通知过就发一条；返回是否真的发了。 */
    fun notifyIfNew(context: Context, info: AppUpdateInfo): Boolean {
        val app = context.applicationContext
        val store = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (store.getString(KEY_NOTIFIED, null) == info.versionName) return false
        if (!post(app, info)) return false
        store.edit().putString(KEY_NOTIFIED, info.versionName).apply()
        return true
    }

    private fun post(context: Context, info: AppUpdateInfo): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        if (Build.VERSION.SDK_INT >= 26 && manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "版本更新", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "发现新版本时提醒。本应用不上架应用商店，新版本只有这里能知道"
                }
            )
        }
        // 唯一判定入口：系统开关 + Android 13 的运行时权限都在它里面（MainActivity）
        if (!MainActivity.notificationsAllowed(context)) return false

        // 点通知直接去拿安装包：`downloadUrl` 已经是"第一个 .apk 资产"，
        // 实在没有 apk 资产时回退成 Release 网页，行为一致。
        val open = Intent(Intent.ACTION_VIEW, Uri.parse(info.downloadUrl)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val content = PendingIntent.getActivity(
            context,
            0x5553,
            open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val summary = info.notes.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
        // 强制更新走另一套文案：后台通知是用户唯一能看到它的地方（他还没打开 App），
        // 标题里必须自带"这条要当真"的信号，不能和普通版本更新长得一样。
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_course_reminder)
            .setContentTitle(
                if (info.forceUpdate) "建议尽快更新到 v${info.versionName}"
                else "发现新版本 v${info.versionName}"
            )
            .setContentText(
                if (info.forceUpdate) "本次为强制更新，请更新后继续使用"
                else summary.ifBlank { "点击查看更新说明并下载安装包" }
            )
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(
                        if (info.forceUpdate) "本次为强制更新，请更新后继续使用。\n\n" +
                            info.notes.ifBlank { "点击下载最新版本" }
                        else info.notes.ifBlank { "点击下载最新版本" }
                    )
            )
            .setContentIntent(content)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        return try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
            true
        } catch (_: SecurityException) {
            false
        }
    }
}
