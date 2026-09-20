package com.tyust.course.academic

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
import com.tyust.course.MessageCenterActivity
import com.tyust.course.R
import com.tyust.course.manager.UserManager
import com.tyust.course.model.SchoolConfig

/**
 * 消息中心的后台巡检：**有新消息 → 系统通知**。
 *
 * ## 只播报"新"消息
 *
 * 判据不是未读条数，而是**未读消息 id 集合的差集**。这样：
 * - 同一批未读消息不会每次巡检都响一次；
 * - 用户在教务网页端读掉几条、又来了几条，只有新来的那几条会播报；
 * - 首次巡检（本机还没有基线）**只建立基线、不播报**，否则一装上 App
 *   就会被历史消息炸一遍通知。
 *
 * ## 节流
 *
 * 巡检挂在页面前台化上（见 `MainScreen`）。前台化会很频繁地发生（切页、
 * 返回桌面再回来……），所以这里默认 30 分钟才真正打一次网络；
 * 手动刷新（[force] = true）绕过节流。
 */
object MessageCenterNotifier {

    const val CHANNEL = "message_center"

    private const val PREFS = "message_center_notify"

    /**
     * 键按账号隔离。
     *
     * 曾经想过用全局键 + "退出登录时清一次"，但那要求每个退出路径都记得调用清理，
     * 少一处就会让下一个账号继承上一个账号的"已播报集合"，于是新账号的
     * 历史未读一条都不提醒。按账号分键不需要任何清理动作。
     */
    private fun notifiedKey(accountKey: String) = "notified_unread_ids::$accountKey"
    private fun lastCheckKey(accountKey: String) = "last_check_ms::$accountKey"

    /** 自动巡检的最小间隔：**12 小时一次**。 */
    private const val MIN_INTERVAL_MS = 12 * 60 * 60 * 1000L

    /** 后台闹钟的动作名。 */
    const val ACTION_CHECK = "com.tyust.course.action.MESSAGE_CHECK"

    /** 闹钟的 PendingIntent requestCode，固定值以便重复排程时覆盖而不是叠加。 */
    private const val ALARM_REQUEST_CODE = 0x4D53

    /** 通知固定 id：后一次替换前一次，不会在通知栏堆一列。 */
    private const val NOTIFICATION_ID = 0x4D53

    /**
     * 排/重排 12 小时一次的后台巡检闹钟。
     *
     * 用 `setInexactRepeating` 而不是 exact：消息提醒晚十几分钟毫无影响，
     * 而 exact 在 Android 12+ 需要用户在系统里额外授权"闹钟与提醒"权限，
     * 为一条教务通知付这个代价不值得。
     *
     * 开机、应用更新、时区变化都会重新调用它 —— 这些事件会把已排的闹钟清掉。
     * 重复调用是安全的：`FLAG_UPDATE_CURRENT` + 同一个 requestCode 会覆盖。
     */
    fun schedule(context: Context) {
        val app = context.applicationContext
        val alarmManager = app.getSystemService(android.app.AlarmManager::class.java) ?: return
        val pending = checkIntent(app)
        val first = System.currentTimeMillis() + MIN_INTERVAL_MS
        try {
            alarmManager.setInexactRepeating(
                android.app.AlarmManager.RTC_WAKEUP,
                first,
                MIN_INTERVAL_MS,
                pending
            )
        } catch (_: SecurityException) {
            // 个别 ROM 会拦重复闹钟；退化成"只在打开应用时巡检"，不影响主流程
            runCatching { alarmManager.cancel(pending) }
        }
    }

    /** 取消巡检闹钟（退出登录时用）。 */
    fun cancelSchedule(context: Context) {
        val app = context.applicationContext
        val alarmManager = app.getSystemService(android.app.AlarmManager::class.java) ?: return
        val pending = checkIntent(app)
        runCatching {
            alarmManager.cancel(pending)
            pending.cancel()
        }
    }

    private fun checkIntent(context: Context): android.app.PendingIntent {
        val intent = Intent(context, MessageCheckReceiver::class.java).setAction(ACTION_CHECK)
        return android.app.PendingIntent.getBroadcast(
            context, ALARM_REQUEST_CODE, intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * 巡检一次。
     *
     * @param force 忽略节流（用户主动刷新、刚登录、刚打开消息中心时用）。
     * @return 当前未读数。
     */
    suspend fun check(
        context: Context,
        school: SchoolConfig?,
        accountKey: String,
        force: Boolean = false,
    ): Int {
        val user = UserManager.getInstance()
        if (school == null || accountKey.isBlank() || user.isDemoMode) return 0
        if (!user.isLoggedIn) return 0

        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val seenKey = notifiedKey(accountKey)
        val stampKey = lastCheckKey(accountKey)
        val now = System.currentTimeMillis()
        val last = prefs.getLong(stampKey, 0L)
        if (!force && now - last < MIN_INTERVAL_MS) {
            MessageCenterManager.refreshUnreadFromCache(context, accountKey)
            return MessageCenterManager.unread
        }

        val result = MessageCenterManager.load(context, school, accountKey)
        // 无论成功与否都推进时间戳：失败时立刻重试只会把教务系统打爆
        prefs.edit().putLong(stampKey, now).apply()
        if (result !is MessageCenterResult.Success) return MessageCenterManager.unread

        val messages = result.messages
        val unreadIds = messages.filter { !it.read }.map { it.id }.toSet()
        val hasBaseline = prefs.contains(seenKey)
        val notified = prefs.getStringSet(seenKey, emptySet()).orEmpty()
        val fresh = messages.filter { !it.read && it.id !in notified }

        if (hasBaseline && fresh.isNotEmpty()) {
            postNotification(context, fresh)
        }
        // 只保留"当前仍未读"的 id：已读的从集合里剔除，避免无限增长
        prefs.edit().putStringSet(seenKey, unreadIds).apply()
        return MessageCenterManager.unread
    }

    /** 退出登录 / 切换账号时清掉基线。键已按账号隔离，这里只是把红点立刻归零。 */
    fun reset(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().clear().apply()
        MessageCenterManager.clearUnread()
    }

    /**
     * 用户**已经亲眼看过**这批消息时调用：只更新基线，不播报。
     *
     * 消息中心页自己有加载逻辑，加载完顺手调这个 —— 否则用户刚在页面里读完，
     * 30 分钟后的巡检还会把同一批消息再播报一次（对用户来说就是"我明明看过了"）。
     */
    fun markSeen(context: Context, accountKey: String, messages: List<AcademicMessage>) {
        if (accountKey.isBlank()) return
        val unreadIds = messages.filter { !it.read }.map { it.id }.toSet()
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(notifiedKey(accountKey), unreadIds)
            .putLong(lastCheckKey(accountKey), System.currentTimeMillis())
            .apply()
    }

    private fun postNotification(context: Context, fresh: List<AcademicMessage>) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= 26 && manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "教务消息", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "教务系统消息中心有新消息时提醒"
                }
            )
        }
        if (!canNotify(context, manager)) return

        val open = Intent(context, MessageCenterActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val content = PendingIntent.getActivity(
            context, 0, open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val headline = fresh.first().title.ifBlank { "有一条新的教务消息" }
        val text = if (fresh.size == 1) headline else "$headline（等 ${fresh.size} 条新消息）"
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_course_reminder)
            .setContentTitle(if (fresh.size == 1) "教务消息" else "教务消息 · ${fresh.size} 条新消息")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(content)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // 通知权限在发的一瞬间被撤销，忽略即可
        }
    }

    private fun canNotify(context: Context, manager: NotificationManager): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT < 33) return true
        return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }
}
