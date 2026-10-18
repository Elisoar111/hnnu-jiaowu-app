package com.hnnujw.course.schedule

import java.util.Calendar
import java.util.TimeZone

data class CourseReminderKey(val account: String, val term: String, val courseId: String) {
    val storageId: String get() = ScheduleIdentity.digest(listOf(account, term, courseId).joinToString("\u001f"))
}

data class CourseReminder(val key: CourseReminderKey, val course: ScheduleCourseRecord, val enabled: Boolean = false,
    val leadMinutes: Int = 15, val revision: Long = 1L)

data class ScheduleTimeBase(val firstWeekDate: String = "", val periodStarts: Map<Int, String> = emptyMap(),
    val periodEnds: Map<Int, String> = emptyMap()) {
    companion object {
        fun dateFromMillis(millis: Long, zone: TimeZone = TimeZone.getDefault()): String {
            if (millis <= 0) return ""
            val calendar = Calendar.getInstance(zone).apply { timeInMillis = millis }
            return "%04d-%02d-%02d".format(java.util.Locale.ROOT, calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH) + 1, calendar.get(Calendar.DAY_OF_MONTH))
        }
    }
}

data class ReminderPermissions(val notifications: Boolean, val exactAlarms: Boolean) {
    val available: Boolean get() = notifications && exactAlarms
}

enum class ReminderAvailability { Off, NeedsPermission, NeedsTime, InvalidWeeks, Scheduled, NoUpcoming, InactiveAccount }

data class PlannedReminder(val reminder: CourseReminder, val triggerAt: Long, val startsAt: Long)
data class ReminderStatus(val availability: ReminderAvailability, val next: PlannedReminder? = null)

object CourseReminderPlanner {
    fun plan(reminder: CourseReminder, timeBase: ScheduleTimeBase?, now: Long, permissions: ReminderPermissions,
        activeAccount: String, zone: TimeZone = TimeZone.getDefault()): ReminderStatus {
        if (!reminder.enabled) return ReminderStatus(ReminderAvailability.Off)
        if (reminder.key.account != activeAccount || activeAccount.isBlank()) return ReminderStatus(ReminderAvailability.InactiveAccount)
        if (!permissions.available) return ReminderStatus(ReminderAvailability.NeedsPermission)
        val course = reminder.course
        val weeks = ScheduleWeeks.parse(course.weeks)
        if (!weeks.valid) return ReminderStatus(ReminderAvailability.InvalidWeeks)
        // 日期 / 时钟的解析都走 ScheduleOccurrences 里的共享助手：上课自动模式要用同一套
        // 算术判断"这节课什么时候开始"，两份实现迟早会对不上。
        val time = parseClock(timeBase?.periodStarts?.get(course.startPeriod))
        if (time == null || course.day !in 1..7 || course.startPeriod < 1 ||
            course.endPeriod < course.startPeriod || reminder.leadMinutes !in 0..1440) {
            return ReminderStatus(ReminderAvailability.NeedsTime)
        }
        val monday = weekOneMonday(timeBase, zone) ?: return ReminderStatus(ReminderAvailability.NeedsTime)
        for (week in weeks.weeks.sorted()) {
            val startsAt = occurrenceAt(monday, week, course.day, time.first, time.second, zone).timeInMillis
            val trigger = startsAt - reminder.leadMinutes * 60_000L
            if (trigger > now) return ReminderStatus(ReminderAvailability.Scheduled, PlannedReminder(reminder, trigger, startsAt))
        }
        return ReminderStatus(ReminderAvailability.NoUpcoming)
    }
}

interface ReminderAlarmPort {
    fun scheduled(): Map<String, PlannedReminder>
    fun exists(key: CourseReminderKey): Boolean
    fun schedule(plan: PlannedReminder)
    fun cancel(key: CourseReminderKey)
}

/** Only a previously scheduled, unchanged occurrence may survive until its broadcast arrives. */
fun isCurrentReminderOccurrence(plan: PlannedReminder, reminder: CourseReminder, timeBase: ScheduleTimeBase?,
    permissions: ReminderPermissions, activeAccount: String, zone: TimeZone = TimeZone.getDefault()): Boolean =
    plan.reminder == reminder && CourseReminderPlanner.plan(reminder, timeBase, plan.triggerAt - 1,
        permissions, activeAccount, zone).next == plan

/**
 * 用课表设置里的节次时间补齐缺失项，**已有值优先**（用户自己调过的不能被默认值盖掉）。
 *
 * 为什么需要它：提醒日历（`calendar:<account>|<term>`）里的 `periodStarts/periodEnds`
 * 只有在用户手动改过「课表设置 → 节次时间」之后才会被写进去。只写过第一周日期的账号，
 * 存下来的是一条「有日期、无节次」的半截记录，而 [CourseReminderPlanner] 拿不到
 * `periodStarts[startPeriod]` 就解析不出上课时刻、直接返回 `NeedsTime`，
 * **一条闹钟都不会排** —— 用户看到的就是「课程提醒开了却从不响」。
 *
 * 合并口径与 `ScheduleRepository.timeBase` 一致（设置里的值打底、日历里存过的值覆盖）：
 * 桌面卡片一直是对的，提醒侧原先漏了这一步。
 */
internal fun ScheduleTimeBase.fillMissingPeriodTimes(
    defaultStarts: Map<Int, String>,
    defaultEnds: Map<Int, String>
): ScheduleTimeBase = copy(
    periodStarts = defaultStarts + periodStarts,
    periodEnds = defaultEnds + periodEnds
)

/** Repeated reconciliation has no side effects when the desired alarms already exist. */
fun reconcileCourseReminders(reminders: List<CourseReminder>, activeAccount: String,
    timeBase: (CourseReminderKey) -> ScheduleTimeBase?, permissions: ReminderPermissions, now: Long,
    alarms: ReminderAlarmPort, zone: TimeZone = TimeZone.getDefault()): Map<String, ReminderStatus> {
    val existing = alarms.scheduled()
    val status = reminders.associate { reminder ->
        val base = timeBase(reminder.key)
        val pending = existing[reminder.key.storageId]
        val due = pending?.takeIf { now >= it.triggerAt && now < it.startsAt &&
            isCurrentReminderOccurrence(it, reminder, base, permissions, activeAccount, zone) && alarms.exists(reminder.key) }
        // A resume/cold-start can run before Android delivers an already due alarm. Do not
        // replace its PendingIntent extras with the next week's occurrence in that window.
        reminder.key.storageId to (due?.let { ReminderStatus(ReminderAvailability.Scheduled, it) }
            ?: CourseReminderPlanner.plan(reminder, base, now, permissions, activeAccount, zone))
    }
    val desired = status.mapNotNull { (id, state) -> state.next?.let { id to it } }.toMap()
    existing.filterKeys { it !in desired }.values.forEach { alarms.cancel(it.reminder.key) }
    desired.forEach { (id, plan) ->
        if (existing[id] != plan || !alarms.exists(plan.reminder.key)) alarms.schedule(plan)
    }
    return status
}
