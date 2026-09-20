package com.tyust.course.receiver

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import com.tyust.course.manager.UserManager
import com.tyust.course.service.GrabService
import com.tyust.course.utils.GrabTaskUtils
import com.tyust.course.utils.GrabTaskUtils.appendGrabLog
import com.tyust.course.utils.GrabTaskUtils.parseScheduledDateTime

/**
 * 定时抢课广播接收器
 * 使用 AlarmManager 触发
 * 🔧 修复说明：GrabService 已经修改为在匹配教学班时优先使用 SmartSelector.queue 中保存的 classId
 * 而不是只按关键词匹配老师/时间，这确保了精确模式能正确选择用户指定的教学班
 */
class GrabAlarmReceiver : BroadcastReceiver() {
    
    companion object {
        private const val TAG = "GrabAlarmReceiver"
        const val ACTION_SCHEDULED_GRAB = "com.tyust.course.action.SCHEDULED_GRAB"
        const val EXTRA_COURSE_KEYWORDS = "course_keywords"
        const val EXTRA_ACCOUNT_KEY = "account_key"
        const val EXTRA_ACCOUNT_STORAGE_KEY = "account_storage_key"
        const val EXTRA_INTERVAL = "interval"
        const val EXTRA_MAX_RETRY = "max_retry"
        const val EXTRA_PARALLEL_MODE = "parallel_mode"

        /** 这些系统广播到达时闹钟已被清空，需要按持久化的任务状态重排。 */
        private val RESCHEDULE_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
        )
    }
    
    override fun onReceive(context: Context, intent: Intent) {
        // 开机 / 应用更新 / 改时间后系统会清掉所有闹钟：先重排，再按原逻辑只响应触发广播。
        if (intent.action != ACTION_SCHEDULED_GRAB) {
            if (intent.action in RESCHEDULE_ACTIONS) rescheduleAll(context)
            return
        }
        Log.d(TAG, "⏰ 定时抢课触发!")

        val userManager = UserManager.getInstance()
        userManager.init(context.applicationContext)
        val requestedStorageKey = intent.getStringExtra(EXTRA_ACCOUNT_STORAGE_KEY).orEmpty()
        if (requestedStorageKey.isNotBlank()) {
            context.getSharedPreferences("grab_pro_prefs", Context.MODE_PRIVATE).edit()
                .putBoolean(scopedKey("has_scheduled_task", requestedStorageKey), false)
                .remove(scopedKey("scheduled_trigger", requestedStorageKey)).apply()
        }
        val scheduledAccountKey = intent.getStringExtra(EXTRA_ACCOUNT_KEY).orEmpty()
        if (scheduledAccountKey.isNotBlank() && scheduledAccountKey != userManager.currentAccountKey) {
            val switched = userManager.switchToAccount(scheduledAccountKey)
            if (!switched) {
                val fallbackStorageKey = intent.getStringExtra(EXTRA_ACCOUNT_STORAGE_KEY).orEmpty()
                appendLog(context, fallbackStorageKey, "定时任务失败：找不到创建任务的账号，请重新登录")
                Log.e(TAG, "找不到定时任务账号: $scheduledAccountKey")
                return
            }
        }

        val accountStorageKey = intent.getStringExtra(EXTRA_ACCOUNT_STORAGE_KEY)
            ?: userManager.currentAccountStorageKey
        val courseKeywords = intent.getStringExtra(EXTRA_COURSE_KEYWORDS) ?: ""
        Log.d(TAG, "关键词: $courseKeywords")
        
        val school = userManager.currentSchool
        if (school == null) {
            Log.e(TAG, "❌ 未登录，无法执行定时任务")
            appendLog(context, accountStorageKey, "未登录，无法执行定时任务")
            return
        }
        
        appendLog(context, accountStorageKey, "定时任务触发，关键词: $courseKeywords")
        
        val prefs = context.getSharedPreferences("grab_pro_prefs", Context.MODE_PRIVATE)
        val interval = if (intent.hasExtra(EXTRA_INTERVAL)) {
            intent.getIntExtra(EXTRA_INTERVAL, 1500)
        } else {
            prefs.getString(scopedKey("interval", accountStorageKey), prefs.getString("interval", "1500"))?.toIntOrNull() ?: 1500
        }
        val maxRetry = if (intent.hasExtra(EXTRA_MAX_RETRY)) {
            intent.getIntExtra(EXTRA_MAX_RETRY, 100)
        } else {
            prefs.getString(scopedKey("max_retry", accountStorageKey), prefs.getString("max_retry", "100"))?.toIntOrNull() ?: 100
        }
        val isParallelMode = if (intent.hasExtra(EXTRA_PARALLEL_MODE)) {
            intent.getBooleanExtra(EXTRA_PARALLEL_MODE, false)
        } else {
            prefs.getBoolean(scopedKey("parallel_mode", accountStorageKey), prefs.getBoolean("parallel_mode", false))
        }
        
        // 🔧 使用 GrabService 的队列模式
        // 服务启动后会绑定定时任务创建账号，并读取该账号自己的队列槽
        val serviceIntent = Intent(context, GrabService::class.java).apply {
            action = GrabService.ACTION_START_QUEUE
            putExtra(GrabService.EXTRA_ACCOUNT_KEY, scheduledAccountKey)
            putExtra(GrabService.EXTRA_ACCOUNT_STORAGE_KEY, accountStorageKey)
            putExtra(GrabService.EXTRA_COURSE_KEYWORDS, courseKeywords)
            putExtra(GrabService.EXTRA_INTERVAL, interval)
            putExtra(GrabService.EXTRA_MAX_RETRY, maxRetry)
            putExtra(GrabService.EXTRA_PARALLEL_MODE, isParallelMode)
        }
        
        Log.d(TAG, "🚀 启动关键词抢课服务...")
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
        
        // 清除当前账号的定时任务标志
        prefs.edit()
            .putBoolean(scopedKey("has_scheduled_task", accountStorageKey), false)
            .remove("has_scheduled_task")
            .apply()
    }

    private fun scopedKey(key: String, accountStorageKey: String): String {
        return if (accountStorageKey.isBlank()) key else "${key}_${accountStorageKey}"
    }

    /**
     * 重启 / 更新 / 改时间后，扫描所有标记为"已排程"的账号，把未来时间的任务重新注册闹钟。
     * 已过期或解析不出的时间一律清理标志位，避免"看起来有任务、实际没有闹钟"的假状态。
     *
     * 两条调度路径的持久化键不同，这里合并处理：
     * - GrabProRoute（队列模式）：`scheduled_datetime_<key>`（字符串 yyyy/MM/dd HH:mm）
     * - AcademicGrabScheduler（协议模式）：`scheduled_trigger_<key>`（毫秒长整型）
     */
    private fun rescheduleAll(context: Context) {
        val prefs = context.getSharedPreferences("grab_pro_prefs", Context.MODE_PRIVATE)
        val alarms = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        if (Build.VERSION.SDK_INT >= 31 && !alarms.canScheduleExactAlarms()) {
            Log.w(TAG, "重排跳过：缺少精确闹钟权限")
            return
        }
        val now = System.currentTimeMillis()
        val storageKeys = prefs.all.keys
            .filter { it.startsWith("has_scheduled_task_") }
            .map { it.removePrefix("has_scheduled_task_") }
            .filter { it.isNotBlank() }
            .distinct()
        for (storageKey in storageKeys) {
            if (!prefs.getBoolean(scopedKey("has_scheduled_task", storageKey), false)) continue
            val storedTrigger = prefs.getLong(scopedKey("scheduled_trigger", storageKey), 0L)
            // 协议模式记录毫秒时间戳，队列模式只留格式化字符串，两者都要能重排。
            val protocolMode = storedTrigger > 0L
            val triggerAt = if (protocolMode) storedTrigger
                else parseScheduledDateTime(prefs.getString(scopedKey("scheduled_datetime", storageKey), "") ?: "")
            if (triggerAt == null || triggerAt <= now) {
                // 计划时间已过（含关机期间错过）：清掉标志，不假装还有任务。
                prefs.edit()
                    .putBoolean(scopedKey("has_scheduled_task", storageKey), false)
                    .remove(scopedKey("scheduled_trigger", storageKey))
                    .apply()
                Log.d(TAG, "重排清理已过期任务 account=$storageKey")
                continue
            }
            val accountKey = prefs.getString(scopedKey("scheduled_account", storageKey), "") ?: ""
            val intent = Intent(context, GrabAlarmReceiver::class.java).apply {
                action = ACTION_SCHEDULED_GRAB
                putExtra(EXTRA_ACCOUNT_KEY, accountKey)
                putExtra(EXTRA_ACCOUNT_STORAGE_KEY, storageKey)
            }
            // 身份必须与创建方一致：协议模式带 data URI + requestCode 0，队列模式用哈希 request code。
            val pending = if (protocolMode) {
                intent.data = Uri.parse(GrabTaskUtils.grabAlarmDataUri(storageKey))
                PendingIntent.getBroadcast(context, 0, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            } else {
                PendingIntent.getBroadcast(context, GrabTaskUtils.grabAlarmRequestCode(storageKey), intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            }
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
            Log.d(TAG, "重排定时任务 account=$storageKey")
        }
    }

    private fun appendLog(context: Context, accountStorageKey: String, message: String) {
        val prefs = context.getSharedPreferences("grab_pro_prefs", Context.MODE_PRIVATE)
        val logKey = scopedKey("log_text", accountStorageKey)
        val currentLog = prefs.getString(logKey, prefs.getString("log_text", "")) ?: ""
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        prefs.edit().putString(logKey, appendGrabLog(currentLog, "[$timestamp] $message")).apply()
    }
}
