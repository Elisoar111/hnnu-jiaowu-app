package com.hnnujw.course.schedule

import android.content.SharedPreferences

/** 默认提前量（分钟）。与旧版硬编码的 15 一致，升级后行为不变。 */
internal const val DEFAULT_LEAD_MINUTES = 15

/** 提前量可选值。0 = 上课时提醒。 */
internal val LEAD_MINUTE_OPTIONS = listOf(0, 5, 10, 15, 20, 30, 45, 60)

/**
 * 提前量只认 [LEAD_MINUTE_OPTIONS] 里的值。
 *
 * 它直接参与闹钟时刻计算（`startsAt - lead * 60_000`）：一旦被写成负数或天文数字，
 * 要么立刻误响、要么永远不响。存进来的脏数据一律回落默认值。
 */
internal fun sanitizeLeadMinutes(value: Int): Int =
    if (LEAD_MINUTE_OPTIONS.contains(value)) value else DEFAULT_LEAD_MINUTES

/** 提前量的可读文案（行尾值）。0 说成「上课时」，比「提前 0 分钟」通顺。 */
internal fun leadMinutesLabel(value: Int): String {
    val minutes = sanitizeLeadMinutes(value)
    return if (minutes == 0) "上课时" else "$minutes 分钟"
}

/** 提前量在滚轮里的选项文案。选项本身要自带「提前」二字，否则一排数字看不出在选什么。 */
internal fun leadMinutesOptionLabel(value: Int): String {
    val minutes = sanitizeLeadMinutes(value)
    return if (minutes == 0) "上课时" else "提前 $minutes 分钟"
}

/**
 * 设备级提醒偏好。
 *
 * 与按课程的 [CourseReminder] 是两层：这里是「总闸 + 默认提前量」，按课程记录仍是
 * 「这门课要不要提醒」的唯一事实来源。**不按账号**存放 —— 它管的是通知渠道、精确闹钟、
 * 勿扰权限这些设备能力，换个账号并不会换一套系统权限。
 */
data class ReminderSettings(
    val masterEnabled: Boolean = true,
    val leadMinutes: Int = DEFAULT_LEAD_MINUTES,
    val autoMode: AutoModeKind = AutoModeKind.Off
)

/**
 * 提醒偏好落盘。与 [ScheduleCalendarStore] 同一套路：只依赖 SharedPreferences，
 * 单测可以直接喂 MemoryPreferences。
 *
 * 用的是提醒调度器同一个 `course_reminders` 偏好文件（由调用方传入），
 * 所以 [ScheduleReminderScheduler.clearAccount] 清账号数据时不会顺手把设备级偏好删掉。
 */
internal class ReminderSettingsStore(private val preferences: SharedPreferences) {

    fun read(): ReminderSettings = ReminderSettings(
        masterEnabled = preferences.getBoolean(KEY_MASTER, true),
        leadMinutes = sanitizeLeadMinutes(preferences.getInt(KEY_LEAD, DEFAULT_LEAD_MINUTES)),
        autoMode = AutoModeKind.fromStorage(preferences.getString(KEY_AUTO_MODE, null))
    )

    /** 写入并返回是否真的变了 —— 没变就不必重新调度闹钟。 */
    fun write(value: ReminderSettings): Boolean {
        val normalized = value.copy(leadMinutes = sanitizeLeadMinutes(value.leadMinutes))
        if (read() == normalized) return false
        preferences.edit()
            .putBoolean(KEY_MASTER, normalized.masterEnabled)
            .putInt(KEY_LEAD, normalized.leadMinutes)
            .putString(KEY_AUTO_MODE, normalized.autoMode.storageValue)
            .apply()
        return true
    }

    fun readRuntime(): AutoModeRuntime = AutoModeRuntime(
        applied = AutoModeKind.fromStorage(preferences.getString(KEY_APPLIED_KIND, null))
            .takeIf { it != AutoModeKind.Off },
        previousInterruptionFilter = preferences.getInt(KEY_PREV_FILTER, AutoModeRuntime.UNKNOWN)
    )

    fun writeRuntime(value: AutoModeRuntime) {
        preferences.edit()
            .putString(KEY_APPLIED_KIND, (value.applied ?: AutoModeKind.Off).storageValue)
            .putInt(KEY_PREV_FILTER, value.previousInterruptionFilter)
            .apply()
    }

    private companion object {
        const val KEY_MASTER = "settings.master"
        const val KEY_LEAD = "settings.lead"
        const val KEY_AUTO_MODE = "settings.auto-mode"
        const val KEY_APPLIED_KIND = "auto.applied-kind"
        const val KEY_PREV_FILTER = "auto.prev-filter"
    }
}
