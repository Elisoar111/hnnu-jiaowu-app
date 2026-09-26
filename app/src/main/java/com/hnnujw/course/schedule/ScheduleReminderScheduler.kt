package com.hnnujw.course.schedule

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.hnnujw.course.MainActivity
import com.hnnujw.course.R
import com.hnnujw.course.manager.UserManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

class ScheduleReminderScheduler private constructor(private val context: Context) : Application.ActivityLifecycleCallbacks {
    private val preferences = context.getSharedPreferences("course_reminders", Context.MODE_PRIVATE)
    private val calendars = ScheduleCalendarStore(preferences)
    private val settingsStore = ReminderSettingsStore(preferences)
    private val alarmManager = context.getSystemService(AlarmManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var started = false
    var revision by mutableIntStateOf(0)
        private set

    companion object {
        const val ACTION = "com.hnnujw.course.action.COURSE_REMINDER"
        const val CHANNEL = "course_reminders"
        const val EXTRA_REMINDER_ID = "course_reminder_id"
        const val EXTRA_REVISION = "course_reminder_revision"
        const val EXTRA_TRIGGER = "course_reminder_trigger"

        /** 上课自动模式闹钟的 action。与课前提醒分开，两者的失败互不影响。 */
        const val ACTION_AUTO_MODE = "com.hnnujw.course.action.COURSE_AUTO_MODE"
        const val ROLE_START = "start"
        const val ROLE_END = "end"

        /** 判断「此刻是否在上课」时向前回看的时长：一节课不可能比这更长。 */
        private const val AUTO_MODE_LOOKBACK = 24L * 60 * 60 * 1000L

        /** 预排自动模式闹钟的前瞻窗口。 */
        private const val AUTO_MODE_HORIZON = 7L * 24 * 60 * 60 * 1000L

        @Volatile private var instance: ScheduleReminderScheduler? = null
        @JvmStatic fun get(context: Context): ScheduleReminderScheduler = instance ?: synchronized(this) {
            instance ?: ScheduleReminderScheduler(context.applicationContext).also { instance = it }
        }
    }

    // ---- 设备级提醒偏好（总开关 / 提前量 / 上课自动模式） ----

    fun settings(): ReminderSettings = settingsStore.read()

    fun setMasterEnabled(enabled: Boolean) {
        if (!settingsStore.write(settings().copy(masterEnabled = enabled))) return
        revision++
        reconcile()
    }

    /**
     * 改提前量。按课程的 [CourseReminder.leadMinutes] 是逐条存的，但界面上只有这一个全局值，
     * 所以改完要把已存在的记录一起改过去 —— 否则"设置里显示 30 分钟、实际还按 15 分钟响"。
     */
    fun setLeadMinutes(minutes: Int) {
        if (!settingsStore.write(settings().copy(leadMinutes = minutes))) return
        revision++
        reconcile()
    }

    fun setAutoMode(kind: AutoModeKind) {
        if (!settingsStore.write(settings().copy(autoMode = kind))) return
        revision++
        reconcile()
    }

    /** 勿扰权限（`ACCESS_NOTIFICATION_POLICY`）。没有它 `setInterruptionFilter` 是静默无效的。 */
    fun hasDndAccess(): Boolean =
        context.getSystemService(NotificationManager::class.java)?.isNotificationPolicyAccessGranted == true

    /** 提前量归一：把历史遗留的、与全局值不一致的记录拉齐。 */
    private fun alignLeadMinutes(records: List<CourseReminder>, lead: Int): List<CourseReminder> =
        if (records.all { it.leadMinutes == lead }) records
        else records.map { if (it.leadMinutes == lead) it else it.copy(leadMinutes = lead, revision = it.revision + 1) }

    fun start(application: Application) {
        if (started) return
        started = true
        // Application is created before an alarm Receiver. Restore the account before any
        // reconciliation; the default, uninitialized session would cancel every alarm.
        UserManager.getInstance().init(application)
        application.registerActivityLifecycleCallbacks(this)
        scope.launch {
            UserManager.getInstance().sessionState.state.map { it.token.accountStorageKey to it.expired }
                .distinctUntilChanged().collect { reconcile() }
        }
    }

    private fun activeAccount(): String = UserManager.getInstance().let {
        if (it.isLoggedIn && !it.isDemoMode) it.currentAccountStorageKey else ""
    }

    private fun records() = ReminderJson.decode(preferences.getString("records", null))
    private fun save(records: List<CourseReminder>) {
        preferences.edit().putString("records", ReminderJson.encode(records)).apply()
        revision++
    }

    fun find(key: CourseReminderKey): CourseReminder? = records().firstOrNull { it.key == key }
    fun encodeUndo(records: List<CourseReminder>) = ReminderJson.encode(records)
    fun restoreUndo(value: String) {
        val restore = ReminderJson.decode(value)
        val ids = restore.map { it.key.storageId }.toSet()
        save(records().filter { it.key.storageId !in ids } + restore.map { it.copy(revision = it.revision + 1) })
        reconcile()
    }

    @Synchronized
    fun setEnabled(key: CourseReminderKey, course: ScheduleCourseRecord, enabled: Boolean) {
        val current = records()
        val old = current.firstOrNull { it.key == key }
        val updated = CourseReminder(key, course, enabled, settings().leadMinutes, (old?.revision ?: 0) + 1)
        save(current.filter { it.key != key } + updated)
        reconcile()
    }

    @Synchronized
    fun updateSnapshot(account: String, term: String, courses: List<ScheduleCourseRecord>) {
        if (account.isBlank() || term.isBlank()) return
        // 上课自动模式要的是「全部课程」，而 records() 只装用户点过提醒开关的那几门，
        // 所以课表快照另存一份。调用方传进来的 term 恒为当前学期（课表只展示本学期）。
        saveAutoModeCourses(account, term, courses)
        val byId = courses.associateBy { it.id }
        val old = records()
        val updated = old.mapNotNull { reminder ->
            if (reminder.key.account != account || reminder.key.term != term) reminder else {
                byId[reminder.key.courseId]?.let { course ->
                    if (reminder.course == course) reminder else reminder.copy(course = course, revision = reminder.revision + 1)
                }
            }
        }
        if (updated != old) save(updated)
        reconcile()
    }

    @Synchronized
    fun updateCustomCourse(account: String, course: ScheduleCourseRecord) {
        val old = records()
        val updated = old.map { if (it.key.account == account && it.key.courseId == course.id && it.course != course)
            it.copy(course = course, revision = it.revision + 1) else it }
        if (old != updated) save(updated)
        reconcile()
    }

    @Synchronized
    fun removeCourse(account: String, courseId: String): List<CourseReminder> {
        val old = records()
        val removed = old.filter { it.key.account == account && it.key.courseId == courseId }
        save(old - removed.toSet())
        reconcile()
        return removed
    }

    @Synchronized
    fun clearAccount(account: String) {
        save(records().filter { it.key.account != account })
        val editor = preferences.edit()
        preferences.all.keys.filter { it.startsWith("calendar:$account|") }.forEach { editor.remove(it) }
        editor.remove("legacy-calendar-migrated:$account")
        // 课表快照是按账号存的，清账号时必须一起删 —— 否则换账号后自动模式还会按
        // 上一个账号的课表切勿扰。设备级偏好（总开关/提前量/自动模式开关）不动：
        // 它们管的是系统权限，与账号无关。
        editor.remove(autoModeCoursesKey(account))
        editor.apply()
        reconcile()
    }

    fun timeBase(account: String, term: String): ScheduleTimeBase? = calendars.read(account, term)

    fun updateTimeBase(account: String, term: String, value: ScheduleTimeBase) {
        if (!calendars.write(account, term, value)) return
        revision++
        reconcile()
    }

    fun migrateLegacyTimeBase(account: String, currentTerm: String, value: ScheduleTimeBase) {
        if (calendars.migrateLegacy(account, currentTerm, value)) { revision++; reconcile() }
    }

    fun permissions(): ReminderPermissions {
        val notifications = NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            (Build.VERSION.SDK_INT < 26 || context.getSystemService(NotificationManager::class.java).getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE)
        return ReminderPermissions(notifications, Build.VERSION.SDK_INT < 31 || alarmManager.canScheduleExactAlarms())
    }

    fun status(key: CourseReminderKey): ReminderStatus {
        // 总开关关掉时，逐门课程的开关不再有任何效果 —— 直接报 Off。
        // 否则课程详情页会显示「下次提醒：X月X日」，而那个闹钟根本不存在。
        if (!settings().masterEnabled) return ReminderStatus(ReminderAvailability.Off)
        return find(key)?.let {
            CourseReminderPlanner.plan(it, timeBase(key.account, key.term), System.currentTimeMillis(), permissions(), activeAccount())
        } ?: ReminderStatus(ReminderAvailability.Off)
    }

    private fun alarmIntent(key: CourseReminderKey) = Intent(context, CourseReminderReceiver::class.java).apply {
        action = ACTION
        data = Uri.Builder().scheme("course-reminder").authority("alarm").appendPath(key.storageId).build()
    }

    private fun scheduledPlans(): Map<String, PlannedReminder> = runCatching {
        val json = JSONArray(preferences.getString("scheduled", "[]"))
        (0 until json.length()).mapNotNull { index -> runCatching {
            val item = json.getJSONObject(index)
            val plan = PlannedReminder(ReminderJson.reminder(item.getJSONObject("reminder")), item.getLong("trigger"), item.getLong("start"))
            plan.reminder.key.storageId to plan
        }.getOrNull() }.toMap()
    }.getOrDefault(emptyMap())

    private fun savePlans(plans: Map<String, PlannedReminder>) {
        preferences.edit().putString("scheduled", JSONArray().apply {
            plans.values.forEach { plan -> put(JSONObject().apply {
                put("reminder", ReminderJson.reminder(plan.reminder)); put("trigger", plan.triggerAt); put("start", plan.startsAt)
            }) }
        }.toString()).apply()
    }

    private val alarms = object : ReminderAlarmPort {
        override fun scheduled() = scheduledPlans()
        override fun exists(key: CourseReminderKey): Boolean = PendingIntent.getBroadcast(context, 0, alarmIntent(key),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE) != null
        override fun schedule(plan: PlannedReminder) {
            val key = plan.reminder.key
            val intent = alarmIntent(key).putExtra(EXTRA_REMINDER_ID, key.storageId)
                .putExtra(EXTRA_REVISION, plan.reminder.revision).putExtra(EXTRA_TRIGGER, plan.triggerAt)
            val pending = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, plan.triggerAt, pending)
            savePlans(scheduledPlans() + (key.storageId to plan))
        }
        override fun cancel(key: CourseReminderKey) {
            PendingIntent.getBroadcast(context, 0, alarmIntent(key), PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)?.let {
                alarmManager.cancel(it); it.cancel()
            }
            savePlans(scheduledPlans() - key.storageId)
            NotificationManagerCompat.from(context).cancel(key.storageId, 1)
        }
    }

    // ---- 上课自动模式（勿扰 / 静音） ----

    private fun autoModeCoursesKey(account: String) = "auto-courses:$account"

    private fun saveAutoModeCourses(account: String, term: String, courses: List<ScheduleCourseRecord>) {
        preferences.edit().putString(
            autoModeCoursesKey(account),
            JSONObject().apply {
                put("term", term)
                put("courses", JSONArray().apply { courses.forEach { put(ReminderJson.course(it)) } })
            }.toString()
        ).apply()
    }

    private fun autoModeSnapshot(account: String): Pair<String, List<ScheduleCourseRecord>>? = runCatching {
        val raw = preferences.getString(autoModeCoursesKey(account), null) ?: return null
        val root = JSONObject(raw)
        val array = root.optJSONArray("courses") ?: JSONArray()
        val courses = (0 until array.length())
            .mapNotNull { runCatching { ReminderJson.course(array.getJSONObject(it)) }.getOrNull() }
        root.optString("term") to courses
    }.getOrNull()

    private fun autoModeIntent(role: String) = Intent(context, AutoModeReceiver::class.java).apply {
        action = ACTION_AUTO_MODE
        data = Uri.Builder().scheme("course-auto-mode").authority("alarm").appendPath(role).build()
    }

    private fun autoModePending(role: String, create: Boolean): PendingIntent? = PendingIntent.getBroadcast(
        context, 0, autoModeIntent(role),
        (if (create) PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_NO_CREATE) or PendingIntent.FLAG_IMMUTABLE
    )

    private fun autoModeAlarmKey(role: String) = if (role == ROLE_START) "auto.start-at" else "auto.end-at"

    /**
     * 排一个自动模式闹钟。
     *
     * 已经排在同一时刻就跳过 —— [reconcile] 每次 Activity resume 都会跑，
     * 每次 cancel + setExact 只会白白唤醒系统。
     */
    private fun scheduleAutoModeAlarm(role: String, at: Long) {
        val key = autoModeAlarmKey(role)
        if (preferences.getLong(key, Long.MIN_VALUE) == at && autoModePending(role, create = false) != null) return
        val pending = autoModePending(role, create = true) ?: return
        runCatching { alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending) }
        preferences.edit().putLong(key, at).apply()
    }

    private fun clearAutoModeAlarm(role: String) {
        autoModePending(role, create = false)?.let { alarmManager.cancel(it); it.cancel() }
        preferences.edit().remove(autoModeAlarmKey(role)).apply()
    }

    /** 真正动系统设置的地方。调用前必须已确认勿扰权限在手。 */
    private fun applyAutoMode(kind: AutoModeKind, runtime: AutoModeRuntime): AutoModeRuntime {
        val target = kind.interruptionFilter ?: return runtime
        val manager = context.getSystemService(NotificationManager::class.java) ?: return runtime
        // 只在「我们还没接管」时记下系统档位；否则会把我们自己刚设的那一档当成原样存下来，
        // 之后还原就等于什么都没做。
        val previous = if (runtime.applied == null) manager.currentInterruptionFilter else runtime.previousInterruptionFilter
        manager.setInterruptionFilter(target)
        return AutoModeRuntime(kind, previous)
    }

    /**
     * 还原成我们动之前的那一档，返回是否已还原。
     *
     * 只有**真的还原成功**才清空运行态：权限被撤销时 `setInterruptionFilter` 会抛
     * SecurityException，此时若照样清空，还原目标就永久丢了，手机再也回不到原样。
     * 留着运行态，下一次 reconcile 还会再试一次。
     */
    private fun restoreAutoMode(runtime: AutoModeRuntime): Boolean {
        val done = runCatching {
            val target = runtime.previousInterruptionFilter
            // 哨兵 = 当初就没读到系统档位，没有可还原的目标，视为完成。
            if (target == AutoModeRuntime.UNKNOWN) true
            else {
                val manager = context.getSystemService(NotificationManager::class.java)
                if (manager == null) false else {
                    manager.setInterruptionFilter(target)
                    true
                }
            }
        }.getOrDefault(false)
        if (done) settingsStore.writeRuntime(AutoModeRuntime())
        return done
    }

    /**
     * 让系统状态与「此刻该不该静音」对齐，并排好下一次上/下课闹钟。
     *
     * 每次 [reconcile] 都会跑，所以「切了勿扰却没还原」最多持续到下一次进 App 或开机。
     */
    @Synchronized
    private fun reconcileAutoMode(settings: ReminderSettings) {
        val now = System.currentTimeMillis()
        val snapshot = autoModeSnapshot(activeAccount())
        val windows = if (snapshot == null) emptyList() else courseWindows(
            snapshot.second, timeBase(activeAccount(), snapshot.first),
            now - AUTO_MODE_LOOKBACK, now + AUTO_MODE_HORIZON
        )
        val inClass = windows.any { now >= it.startsAt && now < it.endsAt }
        val runtime = settingsStore.readRuntime()
        // 总开关关闭 = 应用不做任何自动行为，上课自动模式一并停掉（设置页的提示也是这么写的）。
        // 否则"关掉课程提醒"之后手机还会在课上自己切勿扰，那是很意外的事。
        val kind = if (settings.masterEnabled) settings.autoMode else AutoModeKind.Off
        // 没有勿扰权限时绝不施加 —— 施加了就还原不了，手机只会一直停在勿扰。
        val granted = hasDndAccess()
        when (val action = decideAutoMode(kind, runtime.applied, inClass)) {
            AutoModeAction.None -> Unit
            is AutoModeAction.Apply -> if (granted) settingsStore.writeRuntime(applyAutoMode(action.kind, runtime))
            // 还原不看权限：即使权限刚被撤销也要试，成功了才算数（见 restoreAutoMode）。
            AutoModeAction.Restore -> restoreAutoMode(runtime)
        }
        if (kind == AutoModeKind.Off || !granted) {
            clearAutoModeAlarm(ROLE_START)
            clearAutoModeAlarm(ROLE_END)
        } else {
            windows.firstOrNull { it.startsAt > now }?.let { scheduleAutoModeAlarm(ROLE_START, it.startsAt) }
                ?: clearAutoModeAlarm(ROLE_START)
            windows.firstOrNull { it.endsAt > now }?.let { scheduleAutoModeAlarm(ROLE_END, it.endsAt) }
                ?: clearAutoModeAlarm(ROLE_END)
        }
    }

    @Synchronized
    fun reconcile(resetAlarms: Boolean = false) {
        if (resetAlarms) scheduledPlans().values.forEach { alarms.cancel(it.reminder.key) }
        val settings = settings()
        val stored = records()
        // 提前量是全局设置、却逐条存在课程记录里。历史记录可能还是旧值，这里拉齐，
        // 否则会出现「设置里写 30 分钟、实际按 15 分钟响」。
        val aligned = alignLeadMinutes(stored, settings.leadMinutes)
        if (aligned != stored) save(aligned)
        val permission = permissions()
        try {
            // 总开关关掉 = 一条提醒都不该排。传空列表让 reconcileCourseReminders 把已排的
            // 全撤掉，比在 plan 里再加一个分支更不容易漏。
            val active = if (settings.masterEnabled) aligned else emptyList()
            reconcileCourseReminders(active, activeAccount(), { timeBase(it.account, it.term) }, permission, System.currentTimeMillis(), alarms)
        } catch (_: SecurityException) {
            scheduledPlans().values.forEach { alarms.cancel(it.reminder.key) }
        }
        reconcileAutoMode(settings)
        revision++
    }

    @Synchronized
    fun receive(id: String, expectedRevision: Long, expectedTrigger: Long) {
        val plan = scheduledPlans()[id] ?: return
        val record = records().firstOrNull { it.key.storageId == id } ?: return
        if (record.revision != expectedRevision || plan.triggerAt != expectedTrigger) return
        if (!isCurrentReminderOccurrence(plan, record, timeBase(record.key.account, record.key.term), permissions(), activeAccount())) {
            reconcile(); return
        }
        val now = System.currentTimeMillis()
        if (now < plan.triggerAt) return
        // An old broadcast must never replace or advance a newer pending occurrence.
        savePlans(scheduledPlans() - id)
        if (now >= plan.triggerAt && now < plan.startsAt) {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel(CHANNEL, "课程提醒", NotificationManager.IMPORTANCE_DEFAULT))
            val open = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                data = Uri.Builder().scheme("course-reminder").authority("open").appendPath(id).build()
                putExtra(EXTRA_REMINDER_ID, id)
            }
            val content = PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val notification = NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_course_reminder)
                .setContentTitle("${record.course.name} · 即将上课")
                .setContentText(listOf(record.course.location, "第 ${record.course.startPeriod}-${record.course.endPeriod} 节").filter { it.isNotBlank() }.joinToString(" · "))
                .setContentIntent(content).setAutoCancel(true).setOnlyAlertOnce(true).build()
            try { NotificationManagerCompat.from(context).notify(id, 1, notification) } catch (_: SecurityException) { }
        }
        reconcile()
    }

    fun findById(id: String): CourseReminder? = records().firstOrNull { it.key.storageId == id && it.key.account == activeAccount() }
    override fun onActivityResumed(activity: Activity) { reconcile() }
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
