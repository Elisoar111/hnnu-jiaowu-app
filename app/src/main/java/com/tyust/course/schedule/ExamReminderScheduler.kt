package com.tyust.course.schedule

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.tyust.course.MainActivity
import com.tyust.course.R
import com.tyust.course.manager.UserManager
import com.tyust.course.ui.screen.ExamItemUi
import org.json.JSONArray
import org.json.JSONObject

/**
 * 考前提醒：**开考前 24 小时发一条系统通知**。
 *
 * 逻辑与 [ScheduleReminderScheduler] 的课前提醒同构——同一套
 * 「PendingIntent 覆盖排程 + 计划快照存 SharedPreferences + 开机重排」的思路，
 * 但实现独立成对象：考试提醒是"一门考试一条"的一次性闹钟，没有周次循环、
 * 没有修订号对账，简单得多。
 *
 * ## 数据源
 *
 * 考试列表来自 [com.tyust.course.manager.GradesCacheManager] 的本地缓存
 * （成绩页/考试 Tab 加载后写入），**reconcile 只读缓存不发网络**。
 * 因此开机、应用更新后可以直接重排，不依赖会话有效。
 *
 * ## 时间
 *
 * 教务的考试时间是自由文本（见 [ExamCountdown.parseStart]）——解析不出时刻的
 * 考试不排提醒（宁缺勿错）；已开考/已结束的也不排。
 * 提醒用 `setExactAndAllowWhileIdle`，个别 ROM 拒绝精确闹钟时退化为
 * `set()`，晚一点也比不提醒强。
 */
object ExamReminderScheduler {

    const val ACTION = "com.tyust.course.action.EXAM_REMINDER"
    const val CHANNEL = "exam_reminders"

    private const val PREFS = "exam_reminders"
    private const val KEY_PLANS = "plans"
    private const val LEAD_MS = 24 * 60 * 60 * 1000L
    private const val NOTIFICATION_TAG = "exam_reminder"

    private fun plans(context: Context): Map<String, Plan> = runCatching {
        val array = JSONArray(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_PLANS, "[]"))
        (0 until array.length()).mapNotNull { index -> runCatching {
            val item = array.getJSONObject(index)
            val plan = Plan(
                id = item.getString("id"),
                triggerAt = item.getLong("trigger"),
                courseName = item.optString("course"),
                examTime = item.optString("time"),
                location = item.optString("location"),
                examName = item.optString("examName")
            )
            plan.id to plan
        }.getOrNull() }.toMap()
    }.getOrDefault(emptyMap())

    private fun savePlans(context: Context, plans: Map<String, Plan>) {
        val array = JSONArray()
        plans.values.forEach { plan ->
            array.put(JSONObject()
                .put("id", plan.id)
                .put("trigger", plan.triggerAt)
                .put("course", plan.courseName)
                .put("time", plan.examTime)
                .put("location", plan.location)
                .put("examName", plan.examName))
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_PLANS, array.toString()).apply()
    }

    private data class Plan(
        val id: String,
        val triggerAt: Long,
        val courseName: String,
        val examTime: String,
        val location: String,
        val examName: String
    )

    private fun examId(exam: ExamItemUi): String = ScheduleIdentity.digest(
        listOf(exam.courseName, exam.examTime, exam.examName, exam.location).joinToString("\u001f"))

    private fun alarmPending(context: Context, id: String, flags: Int): PendingIntent {
        val intent = Intent(context, ExamReminderReceiver::class.java).apply {
            action = ACTION
            data = Uri.Builder().scheme("exam-reminder").authority("alarm").appendPath(id).build()
            putExtra(EXTRA_ID, id)
        }
        return PendingIntent.getBroadcast(context, id.hashCode(), intent, flags)
    }

    const val EXTRA_ID = "exam_reminder_id"

    /**
     * 对齐"应排的考试提醒"与"已排的闹钟"。幂等：重复调用无副作用。
     * 考试 Tab 每次加载完、开机广播时都会调用。
     */
    @Synchronized
    fun reconcile(context: Context, exams: List<ExamItemUi>) {
        val now = System.currentTimeMillis()
        val existing = plans(context)
        val desired = exams.mapNotNull { exam ->
            val start = ExamCountdown.parseStart(exam.examTime) ?: return@mapNotNull null
            val trigger = start - LEAD_MS
            if (trigger <= now) return@mapNotNull null
            val id = examId(exam)
            id to Plan(id, trigger, exam.courseName, exam.examTime, exam.location, exam.examName)
        }.toMap()

        // 取消不再出现（或内容变化导致 id 变化）的旧闹钟
        existing.filterKeys { it !in desired }.values.forEach { plan ->
            runCatching {
                alarmPending(context, plan.id,
                    PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)?.let {
                    context.getSystemService(AlarmManager::class.java)?.cancel(it); it.cancel()
                }
            }
        }
        // 排/更新目标闹钟
        desired.forEach { (id, plan) ->
            if (existing[id] == plan) return@forEach
            val alarm = context.getSystemService(AlarmManager::class.java) ?: return@forEach
            val pending = alarmPending(context, id, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            runCatching {
                if (Build.VERSION.SDK_INT < 31 || alarm.canScheduleExactAlarms()) {
                    alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, plan.triggerAt, pending)
                } else {
                    alarm.set(AlarmManager.RTC_WAKEUP, plan.triggerAt, pending)
                }
            }.onFailure {
                runCatching { alarm.set(AlarmManager.RTC_WAKEUP, plan.triggerAt, pending) }
            }
        }
        if (existing.keys != desired.keys || existing != desired) savePlans(context, desired)
    }

    /** 闹钟触发：核对计划仍然有效后发通知，然后摘掉这个一次性计划。 */
    @Synchronized
    fun receive(context: Context, id: String) {
        val plan = plans(context)[id] ?: return
        if (System.currentTimeMillis() < plan.triggerAt - 60_000L) return // 闹钟提前到达（改时间等），忽略
        savePlans(context, plans(context) - id)

        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && manager?.getNotificationChannel(CHANNEL) == null) {
            manager?.createNotificationChannel(
                NotificationChannel(CHANNEL, "考试提醒", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "开考前一天提醒考试时间与地点"
                }
            )
        }
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return

        val open = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_OPEN_TAB, com.tyust.course.manager.StartupPage.Grades.route)
        }
        val content = PendingIntent.getActivity(context, 0x4558, open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val whenText = plan.examTime.ifBlank { "明天" }
        val body = buildString {
            append("《${plan.courseName}》将于 ").append(whenText).append(" 开考")
            if (plan.location.isNotBlank()) append("，地点：").append(plan.location)
            append("。记得带好证件与文具")
        }
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_course_reminder)
            .setContentTitle("考试提醒 · ${plan.courseName}")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(content)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_TAG, id.hashCode(), notification)
        } catch (_: SecurityException) {
        }
    }

    /** 退出登录时清掉当前账号可见的提醒（考试数据按账号隔离）。 */
    @Synchronized
    fun clearAll(context: Context) {
        plans(context).values.forEach { plan ->
            runCatching {
                alarmPending(context, plan.id,
                    PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)?.let {
                    context.getSystemService(AlarmManager::class.java)?.cancel(it); it.cancel()
                }
            }
        }
        savePlans(context, emptyMap())
    }
}
