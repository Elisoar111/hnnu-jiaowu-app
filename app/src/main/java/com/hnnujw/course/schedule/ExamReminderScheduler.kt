package com.hnnujw.course.schedule

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
import com.hnnujw.course.MainActivity
import com.hnnujw.course.R
import com.hnnujw.course.manager.UserManager
import com.hnnujw.course.ui.screen.ExamItemUi
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
 * 考试列表来自 [com.hnnujw.course.manager.GradesCacheManager] 的本地缓存
 * （成绩页/考试 Tab 加载后写入），**reconcile 只读缓存不发网络**。
 * 因此开机、应用更新后可以直接重排，不依赖会话有效。
 *
 * ## 账号隔离
 *
 * 本机可以绑定多个学生账号，而闹钟是**全局**的（AlarmManager 不分账号），
 * 所以计划表里必须自己带上 `account`：
 *  - 计划 id 把账号一起算进摘要，两个账号的同一门考试不会撞 id；
 *  - [reconcile] 只取消"本账号"不再出现的旧闹钟，其它账号的提醒原样保留；
 *  - [clearAll] 只清本账号。
 * 早期版本的计划没有 `account` 字段，按"属于当前账号"处理（一次性迁移）。
 *
 * ## 时间
 *
 * 教务的考试时间是自由文本（见 [ExamCountdown.parseStart]）——解析不出时刻的
 * 考试不排提醒（宁缺勿错）；已开考/已结束的也不排。
 * 提醒用 `setExactAndAllowWhileIdle`，个别 ROM 拒绝精确闹钟时退化为
 * `set()`，晚一点也比不提醒强。
 */
object ExamReminderScheduler {

    const val ACTION = "com.hnnujw.course.action.EXAM_REMINDER"
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
                examName = item.optString("examName"),
                account = item.optString("account")
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
                .put("examName", plan.examName)
                .put("account", plan.account))
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_PLANS, array.toString()).apply()
    }

    internal data class Plan(
        val id: String,
        val triggerAt: Long,
        val courseName: String,
        val examTime: String,
        val location: String,
        val examName: String,
        val account: String = ""
    )

    private fun examId(exam: ExamItemUi, accountKey: String): String = ScheduleIdentity.digest(
        listOf(accountKey, exam.courseName, exam.examTime, exam.examName, exam.location)
            .joinToString("\u001f"))

    private fun alarmPending(context: Context, id: String, flags: Int): PendingIntent {
        val intent = Intent(context, ExamReminderReceiver::class.java).apply {
            action = ACTION
            data = Uri.Builder().scheme("exam-reminder").authority("alarm").appendPath(id).build()
            putExtra(EXTRA_ID, id)
        }
        return PendingIntent.getBroadcast(context, id.hashCode(), intent, flags)
    }

    /**
     * 这条闹钟是否**真的**还挂在系统的 AlarmManager 里。
     *
     * `FLAG_NO_CREATE` 在 PendingIntent 不存在时返回 null，正好是我们要的判据
     * （Intent 的匹配走 filterEquals，不看 extras，所以这里传 id 只为构造同一个 Intent）。
     * 与 `ScheduleReminderScheduler.alarms.exists()` 是同一套做法。
     */
    private fun alarmExists(context: Context, id: String): Boolean = runCatching {
        alarmPending(context, id, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE) != null
    }.getOrDefault(false)

    const val EXTRA_ID = "exam_reminder_id"

    /** 一次 reconcile 的决策结果，见 [decideReconcile]。 */
    internal data class ReconcileDecision(
        /** 需要撤销的闹钟（计划 id）。 */
        val cancelIds: List<String>,
        /** 需要排 / 重排的闹钟。 */
        val schedule: List<Plan>,
        /** 计划表应存成的内容。 */
        val merged: Map<String, Plan>,
    )

    /**
     * 纯决策：给定"现有计划表"与"考试列表"，算出要撤哪些、要排哪些、计划表存成什么。
     *
     * 抽成纯函数是为了能被 JVM 单测盯住 —— 这里集中了三个容易写错、且写错后**用户无从察觉**
     * 的不变量：
     *  1. 只撤**本账号**不再需要的闹钟（碰了别的账号，切一次号就把人家的考前提醒清了）；
     *  2. 计划未变但闹钟已不在系统里时**必须重排**。计划表在 SharedPreferences、闹钟在
     *     AlarmManager，开机 / 应用更新 / 强制停止 / 改时间都会清空后者而留下前者；
     *     只比计划表就会把该排的闹钟全部跳过，考前提醒静默失效；
     *  3. 计划表 = 其它账号的计划 + 本账号最新计划。
     *
     * @param alarmPresent 探测某条闹钟是否真的还挂在 AlarmManager 里（见 [alarmExists]）。
     */
    internal fun decideReconcile(
        existing: Map<String, Plan>,
        exams: List<ExamItemUi>,
        accountKey: String,
        now: Long,
        alarmPresent: (String) -> Boolean,
    ): ReconcileDecision {
        val desired = exams.mapNotNull { exam ->
            val start = ExamCountdown.parseStart(exam.examTime) ?: return@mapNotNull null
            val trigger = start - LEAD_MS
            if (trigger <= now) return@mapNotNull null
            val id = examId(exam, accountKey)
            id to Plan(id, trigger, exam.courseName, exam.examTime, exam.location, exam.examName, accountKey)
        }.toMap()

        // 不变量 1：只撤本账号里"不再出现（或内容变化导致 id 变化）"的旧闹钟
        val cancelIds = existing.filter { (id, plan) ->
            plan.ownerKey(accountKey) == accountKey && id !in desired
        }.keys.toList()

        // 不变量 2：计划未变**且**闹钟确实还在系统里，才算"已经排好了"
        val schedule = desired.filter { (id, plan) ->
            existing[id] != plan || !alarmPresent(id)
        }.values.toList()

        // 不变量 3
        val merged = existing.filterValues { it.ownerKey(accountKey) != accountKey } + desired
        return ReconcileDecision(cancelIds, schedule, merged)
    }

    /**
     * 对齐"本账号应排的考试提醒"与"已排的闹钟"。幂等：重复调用无副作用。
     * 考试 Tab 每次加载完、开机广播时都会调用。
     */
    @Synchronized
    fun reconcile(context: Context, exams: List<ExamItemUi>, accountKey: String) {
        val existing = plans(context)
        val decision = decideReconcile(existing, exams, accountKey, System.currentTimeMillis()) { id ->
            alarmExists(context, id)
        }
        decision.cancelIds.forEach { cancelAlarm(context, it) }
        decision.schedule.forEach { scheduleAlarm(context, it) }
        if (decision.merged != existing) savePlans(context, decision.merged)
    }

    /** 排一条闹钟。个别 ROM 拒绝精确闹钟时退化为非精确 —— 晚一点也比不提醒强。 */
    private fun scheduleAlarm(context: Context, plan: Plan) {
        val alarm = context.getSystemService(AlarmManager::class.java) ?: return
        val pending = alarmPending(context, plan.id, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
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

    /** 没有 account 字段的历史计划，按"属于当前账号"处理。 */
    private fun Plan.ownerKey(currentAccount: String): String = account.ifEmpty { currentAccount }

    /**
     * 该计划此刻是否应当播报。
     *
     * 抽成纯函数是为了能被 JVM 单测盯住 —— 它就是"删号 / 登出 / 切号后仍收到考试提醒"
     * 这个缺陷的判据。三条都必须成立：
     *  1. 已登录且不是演示模式（演示模式的考试是造出来的，不该弹真通知）；
     *  2. 计划归属的账号就是当前账号。
     *
     * 第 2 条为什么必要：`reconcile` 只撤销"当前账号"的计划、**刻意保留**其它账号的，
     * 所以被删账号的闹钟仍留在系统里（开机重排还会排回来）。
     */
    internal fun shouldDeliver(
        plan: Plan,
        isLoggedIn: Boolean,
        isDemoMode: Boolean,
        currentAccount: String,
    ): Boolean = isLoggedIn && !isDemoMode && plan.ownerKey(currentAccount) == currentAccount

    private fun cancelAlarm(context: Context, id: String) {
        runCatching {
            alarmPending(context, id, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)?.let {
                context.getSystemService(AlarmManager::class.java)?.cancel(it); it.cancel()
            }
        }
    }

    /** 闹钟触发：核对计划仍然有效后发通知，然后摘掉这个一次性计划。 */
    @Synchronized
    fun receive(context: Context, id: String) {
        val plan = plans(context)[id] ?: return
        if (System.currentTimeMillis() < plan.triggerAt - 60_000L) return // 闹钟提前到达（改时间等），忽略

        // ⚠️ 顺序有讲究：**必须先确认这次真的能送达，再消费计划。**
        //
        // `savePlans` 摘掉这条一次性计划，同时系统里的闹钟也已经响过、不会再来第二次 ——
        // 所以一旦先摘再发现"通知被用户关了"，这条考试提醒就**永久消失**，事后重新打开
        // 通知也回不来。放在消费之前，权限关闭时计划原样留在这里，等下一次 `reconcile`
        // （开机 / 应用更新 / 改时间 / 成绩缓存刷新）连同它一起重排，用户开了通知仍能收到。
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return

        savePlans(context, plans(context) - id)

        // 计划属于哪个账号，就必须由哪个账号来收。
        //
        // 这道守卫是必需的：`reconcile` 只撤销"当前账号"的计划、**刻意保留**其它账号的
        // （不变量 1 与 3），所以删号 / 登出 / 切号之后，这个闹钟仍留在系统里，开机重排
        // 还会把它排回来。少了守卫，用户会收到一条属于**已删除账号**的考试提醒。
        //
        // 课前提醒（`ScheduleReminderScheduler.receive`）靠 `isCurrentReminderOccurrence`
        // 传 `activeAccount()` 天然挡住了这种情况，考前提醒这条链路此前没有对应兜底。
        val user = UserManager.getInstance().apply { init(context.applicationContext) }
        if (!shouldDeliver(plan, user.isLoggedIn, user.isDemoMode, user.currentAccountStorageKey)) return

        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && manager?.getNotificationChannel(CHANNEL) == null) {
            manager?.createNotificationChannel(
                NotificationChannel(CHANNEL, "考试提醒", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "开考前一天提醒考试时间与地点"
                }
            )
        }

        val open = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_OPEN_TAB, com.hnnujw.course.manager.StartupPage.Grades.route)
            // 直接翻到「考试」子页：提醒的意义就是"点开就能看"，不该再让用户自己找
            putExtra(MainActivity.EXTRA_OPEN_GRADES_TAB, "2")
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

    /** 退出登录 / 删除账号时清掉该账号的提醒；其它账号的计划保持不动。 */
    @Synchronized
    @JvmStatic
    fun clearAll(context: Context, accountKey: String) {
        val existing = plans(context)
        val mine = existing.filterValues { it.ownerKey(accountKey) == accountKey }
        mine.values.forEach { plan -> cancelAlarm(context, plan.id) }
        savePlans(context, existing - mine.keys)
    }
}
