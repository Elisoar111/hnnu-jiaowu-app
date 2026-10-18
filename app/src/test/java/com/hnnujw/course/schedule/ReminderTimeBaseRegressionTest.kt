package com.hnnujw.course.schedule

import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.*
import org.junit.Test

/**
 * 「课程提醒开了却从不响」的回归测试。
 *
 * 现场：用户在课程详情里打开某门课的提醒开关，详情页要么显示
 * 「待补全本学期第一周周一日期或节次时间」，要么显示「下次提醒：X月X日」但到点没有任何通知。
 *
 * 根因是提醒用的「时间基准」缺了节次时间：
 *
 *  1. `ScheduleRoute` 第一次回写基准时只给 `firstWeekDate`，`periodStarts/periodEnds` 是空的；
 *  2. 补救路径 `ScheduleCalendarStore.migrateLegacy` 带着「已有第一周日期就整条跳过」的守卫，
 *     于是只要第 1 步写过日期，节次时间就再也没机会补进来；
 *  3. `CourseReminderPlanner` 拿不到 `periodStarts[startPeriod]` 就返回 `NeedsTime`，
 *     **一条闹钟都不会排** —— 这就是“设置了却不响”的直接原因。
 *
 * 修复后：读取侧用设置里的节次时间补齐，迁移侧改成合并，写入侧一次性写全。
 */
class ReminderTimeBaseRegressionTest {

    private val account = "account-a"
    private val term = "2026-2027-1"
    private val zone = TimeZone.getTimeZone("Asia/Shanghai")
    private val allowed = ReminderPermissions(notifications = true, exactAlarms = true)
    private val course = ScheduleCourseRecord("custom:one", "高等数学", "", "A101", 1, 1, 2, "1-16周")
    private val reminder = CourseReminder(CourseReminderKey(account, term, course.id), course, enabled = true)

    /** 2026-09-07 是周一；第 1 节 08:00。 */
    private val completeBase = ScheduleTimeBase(
        firstWeekDate = "2026-09-07",
        periodStarts = mapOf(1 to "08:00", 2 to "08:55"),
        periodEnds = mapOf(1 to "08:45", 2 to "09:40")
    )

    private fun at(day: Int, hour: Int, minute: Int) = Calendar.getInstance(zone).apply {
        clear(); set(2026, Calendar.SEPTEMBER, day, hour, minute)
    }.timeInMillis

    // ---------- 缺陷 1：缺节次时间 → 一条闹钟都排不出来 ----------

    @Test
    fun aDateOnlyTimeBaseCannotScheduleAnyReminder() {
        val dateOnly = ScheduleTimeBase(firstWeekDate = "2026-09-07")
        val status = CourseReminderPlanner.plan(reminder, dateOnly, at(7, 7, 0), allowed, account, zone)
        assertEquals(
            "缺节次时间时必然规划不出来 —— 这正是「设置了却不响」的直接原因",
            ReminderAvailability.NeedsTime, status.availability
        )
    }

    @Test
    fun withACompleteTimeBaseTheReminderIsActuallyScheduled() {
        val status = CourseReminderPlanner.plan(reminder, completeBase, at(7, 7, 0), allowed, account, zone)
        assertEquals(ReminderAvailability.Scheduled, status.availability)
        assertEquals(at(7, 7, 45), status.next!!.triggerAt)   // 08:00 提前 15 分钟
        assertEquals(at(7, 8, 0), status.next.startsAt)
    }

    // ---------- 缺陷 2：读取侧必须用设置里的节次时间补齐 ----------

    @Test
    fun aHalfWrittenCalendarIsRepairedByTheSettingsPeriodTimes() {
        val dateOnly = ScheduleTimeBase(firstWeekDate = "2026-09-07")
        val repaired = dateOnly.fillMissingPeriodTimes(
            mapOf(1 to "08:00", 2 to "08:55"),
            mapOf(1 to "08:45", 2 to "09:40")
        )
        assertEquals(completeBase, repaired)
        assertEquals(
            ReminderAvailability.Scheduled,
            CourseReminderPlanner.plan(reminder, repaired, at(7, 7, 0), allowed, account, zone).availability
        )
    }

    @Test
    fun settingsPeriodTimesNeverOverwriteValuesAlreadyStoredInTheCalendar() {
        // 用户在设置里改过第 1 节为 08:30；设置侧后来回落成 08:00 也不该盖掉它。
        val stored = ScheduleTimeBase("2026-09-07", mapOf(1 to "08:30"), mapOf(1 to "09:15"))
        val merged = stored.fillMissingPeriodTimes(
            mapOf(1 to "08:00", 2 to "08:55"),
            mapOf(1 to "08:45", 2 to "09:40")
        )
        assertEquals("08:30", merged.periodStarts[1])
        assertEquals("09:15", merged.periodEnds[1])
        assertEquals("08:55", merged.periodStarts[2])   // 第 2 节仍从设置补
        assertEquals("09:40", merged.periodEnds[2])
    }

    @Test
    fun aCompleteCalendarIsLeftUntouched() {
        val merged = completeBase.fillMissingPeriodTimes(
            mapOf(1 to "07:00", 3 to "10:00"),
            mapOf(1 to "07:45", 3 to "10:45")
        )
        assertEquals("08:00", merged.periodStarts[1])   // 已有值优先
        assertEquals("10:00", merged.periodStarts[3])   // 缺失值补齐
        assertEquals("2026-09-07", merged.firstWeekDate)
    }

    // ---------- 缺陷 3：迁移要能补节次时间，而不是「有日期就跳过」 ----------

    @Test
    fun migrationFillsPeriodTimesEvenWhenTheStartDateIsAlreadyStored() {
        val prefs = MemoryPreferences()
        val store = ScheduleCalendarStore(prefs)

        // 现状：ScheduleRoute 先写了只有日期的记录。
        assertTrue(store.write(account, term, ScheduleTimeBase(firstWeekDate = "2026-09-07")))
        assertTrue("前提：节次时间确实是空的", requireNotNull(store.read(account, term)).periodStarts.isEmpty())

        // 补救调用带着完整的节次时间进来。
        store.migrateLegacy(account, term, completeBase)

        val after = requireNotNull(store.read(account, term))
        assertEquals("2026-09-07", after.firstWeekDate)
        assertEquals("迁移必须把节次时间补进来", mapOf(1 to "08:00", 2 to "08:55"), after.periodStarts)
        assertEquals(mapOf(1 to "08:45", 2 to "09:40"), after.periodEnds)
    }

    @Test
    fun migrationNeverOverwritesAUserAdjustedPeriodTime() {
        val prefs = MemoryPreferences()
        val store = ScheduleCalendarStore(prefs)
        store.write(account, term, ScheduleTimeBase("2026-09-07", mapOf(1 to "08:30"), mapOf(1 to "09:15")))

        store.migrateLegacy(account, term, completeBase)

        val after = requireNotNull(store.read(account, term))
        assertEquals("08:30", after.periodStarts[1])
        assertEquals("09:15", after.periodEnds[1])
        assertEquals("09:40", after.periodEnds[2])
    }

    @Test
    fun migrationStillRefusesWhenNeitherSideHasAUsableStartDate() {
        val prefs = MemoryPreferences()
        val store = ScheduleCalendarStore(prefs)
        assertFalse(store.migrateLegacy(account, term, ScheduleTimeBase()))
        assertNull(store.read(account, term))
    }

    // ---------- 端到端：修复后 reconcile 真的会排出一条闹钟 ----------

    @Test
    fun reconciliationSchedulesAnAlarmOnceTheTimeBaseIsRepaired() {
        val prefs = MemoryPreferences()
        val store = ScheduleCalendarStore(prefs)
        store.write(account, term, ScheduleTimeBase(firstWeekDate = "2026-09-07"))
        store.migrateLegacy(account, term, completeBase)

        val alarms = RecordingAlarms()
        val status = reconcileCourseReminders(
            reminders = listOf(reminder),
            activeAccount = account,
            timeBase = { store.read(it.account, it.term) },
            permissions = allowed,
            now = at(7, 7, 0),
            alarms = alarms,
            zone = zone
        )

        assertEquals(ReminderAvailability.Scheduled, status.getValue(reminder.key.storageId).availability)
        assertEquals(1, alarms.plans.size)
        assertEquals(at(7, 7, 45), alarms.plans.values.single().triggerAt)
    }

    @Test
    fun withoutThePeriodTimesNoAlarmIsScheduledAtAll() {
        val prefs = MemoryPreferences()
        val store = ScheduleCalendarStore(prefs)
        store.write(account, term, ScheduleTimeBase(firstWeekDate = "2026-09-07"))

        val alarms = RecordingAlarms()
        val status = reconcileCourseReminders(
            reminders = listOf(reminder),
            activeAccount = account,
            // 故意不补设置里的节次时间，模拟修复前的读取路径
            timeBase = { store.read(it.account, it.term) },
            permissions = allowed,
            now = at(7, 7, 0),
            alarms = alarms,
            zone = zone
        )

        assertEquals(ReminderAvailability.NeedsTime, status.getValue(reminder.key.storageId).availability)
        assertTrue("这就是用户侧的「开了提醒但不响」", alarms.plans.isEmpty())
    }

    private class RecordingAlarms : ReminderAlarmPort {
        val plans = mutableMapOf<String, PlannedReminder>()
        override fun scheduled() = plans.toMap()
        override fun exists(key: CourseReminderKey) = key.storageId in plans
        override fun schedule(plan: PlannedReminder) { plans[plan.reminder.key.storageId] = plan }
        override fun cancel(key: CourseReminderKey) { plans.remove(key.storageId) }
    }
}
