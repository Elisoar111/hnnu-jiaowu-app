package com.hnnujw.course.ykt

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/**
 * 电费巡检的闹钟：让 App **没打开**时也能发现余额不足并发通知。
 *
 * 每条设计取舍都有先例：
 * - 用 AlarmManager 而非 WorkManager：本工程没有 `androidx.work` 依赖，
 *   消息巡检（[com.hnnujw.course.academic.MessageCenterNotifier]）已在用同一套模式。
 * - 用 `setInexactRepeating` 而非 exact：晚十几分钟提醒电费毫无影响，
 *   而 exact 在 Android 12+ 需要用户额外授权"闹钟与提醒"权限，不值得。
 * - **一天一次**（`INTERVAL_MS`）：电费是慢变量，更勤只是白打网络。
 *   注意 [YktAlertPolicy] 已改为"低于阈值就提醒、不限制次数"，
 *   所以巡检周期**就是提醒的最大频率**——这里是每天一条，不会变成骚扰。
 *   若要缩短周期，必须同时评估用户是否会被频繁打扰。
 *
 * ⚠️ **后台能否真正跑起来，取决于系统的电池优化策略**（见 [YktBackgroundPermission]）：
 * 被列入"电池优化白名单之外"的 App 在部分 ROM 上会收不到 `RTC_WAKEUP` 闹钟，
 * 表现为"提醒时有时无"。界面上提供入口引导用户把本 App 加入白名单。
 */
object YktAlertScheduler {

    private const val INTERVAL_MS = 24 * 60 * 60 * 1000L
    private const val REQUEST_CODE = 0x5962

    const val ACTION = "com.hnnujw.course.action.YKT_ALERT_CHECK"

    /**
     * 排/重排巡检闹钟。**幂等**：同一个 requestCode + action 会被覆盖，
     * 所以每次冷启动都调用不会堆出多个闹钟。
     */
    fun schedule(context: Context) {
        val app = context.applicationContext
        val manager = app.getSystemService(AlarmManager::class.java) ?: return
        val pending = pendingIntent(app)
        val first = System.currentTimeMillis() + INTERVAL_MS
        try {
            manager.setInexactRepeating(AlarmManager.RTC_WAKEUP, first, INTERVAL_MS, pending)
        } catch (_: SecurityException) {
            // 个别 ROM 拦重复闹钟：退化成"只在打开 App 时检查"，不影响主流程
            runCatching { manager.cancel(pending) }
        }
    }

    fun cancel(context: Context) {
        val app = context.applicationContext
        val manager = app.getSystemService(AlarmManager::class.java) ?: return
        val pending = pendingIntent(app)
        runCatching {
            manager.cancel(pending)
            pending.cancel()
        }
    }

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
