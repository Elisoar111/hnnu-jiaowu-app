package com.hnnujw.course.schedule

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 课表「可展示状态」的**唯一口径**。
 *
 * 这里刻意只有纯数据、不碰 Compose 也不碰 RemoteViews：App 内的组件工作台
 * （[com.hnnujw.course.widgetboard.scheduleCardData]）与桌面上的课表类卡片
 * （[com.hnnujw.course.widgetboard.CardWidgetRenderer]）都从这一份状态出发，
 * 所以两处对"现在是什么状态"的说法永远一致 —— 同一时刻，两处都会说
 * 「正在上课」、都报同一个「还剩 N 堂」。
 *
 * 这段代码原本长在 `ScheduleWidgetPresentation.kt` 里（和旧的三个课表桌面组件的
 * RemoteViews 渲染挤在一个文件）。v1.2.6 把那三个组件换成了工作台里的卡片组件，
 * 渲染部分整体删除，状态部分留在这里 —— 它才是真正被复用的那一半。
 *
 * 预测口径：**绝不猜未知的教学周**。周次解析不出的课程不进入时间线，
 * 宁可显示"周次待核对"。
 */

internal data class ScheduleWidgetCourse(
    val occurrence: ScheduleOccurrence,
    val status: String,
    val name: String,
    val location: String,
    val time: String,
    val dateLabel: String
) {
    val course get() = occurrence.course
}

internal data class ScheduleWidgetState(
    val snapshot: ScheduleSnapshot?, val agenda: ScheduleAgenda?, val now: Long,
    val heading: String, val date: String,
    val primary: ScheduleWidgetCourse?, val secondary: ScheduleWidgetCourse?,
    val message: String?, val actionLabel: String, val summary: String
) {
    val title get() = primary?.name ?: message.orEmpty()

    companion object {
        fun from(snapshot: ScheduleSnapshot?, now: Long, zone: TimeZone = TimeZone.getDefault()): ScheduleWidgetState {
            fun format(pattern: String, time: Long) =
                SimpleDateFormat(pattern, Locale.CHINA).apply { timeZone = zone }.format(Date(time))
            val date = format("M月d日 E", now)
            fun empty(message: String, label: String, agenda: ScheduleAgenda? = null) =
                ScheduleWidgetState(snapshot, agenda, now, "今日课表", date, null, null, message, label, "")
            if (snapshot == null) return empty("登录后查看课表", "去登录")
            val agenda = ScheduleAgenda.calculate(snapshot.courses, snapshot.timeBase, now, zone)
            if (!snapshot.hasCache && snapshot.courses.isEmpty()) {
                return empty("还没有本地课表", "同步课表", agenda)
            }
            if (agenda.needsCalendar) return empty("请设置开学日期", "去设置", agenda)
            val current = agenda.current.firstOrNull()
            val primary = current ?: agenda.upcoming.firstOrNull()
            if (primary == null) {
                if (snapshot.courses.any { !ScheduleWeeks.parse(it.weeks).valid }) {
                    return empty("课程周次待核对", "查看课表", agenda)
                }
                val hasMissingTime = snapshot.courses.any {
                    snapshot.timeBase.periodStarts[it.startPeriod].isNullOrBlank() ||
                        snapshot.timeBase.periodEnds[it.endPeriod].isNullOrBlank()
                }
                if (hasMissingTime) return empty("请补全节次时间", "去设置", agenda)
                return empty(if (agenda.today.isEmpty()) "今天没有课程" else "今日课程已结束", "查看课表", agenda)
            }
            val tomorrow = Calendar.getInstance(zone).apply { timeInMillis = now; add(Calendar.DATE, 1) }.timeInMillis
            fun row(item: ScheduleOccurrence, status: String): ScheduleWidgetCourse {
                val dateLabel = when (format("yyyy-MM-dd", item.startsAt)) {
                    format("yyyy-MM-dd", now) -> ""
                    format("yyyy-MM-dd", tomorrow) -> "明天"
                    else -> format("M/d", item.startsAt)
                }
                return ScheduleWidgetCourse(item, status, item.course.name, item.course.location.ifBlank { "教室待定" },
                    "${format("HH:mm", item.startsAt)}–${format("HH:mm", item.endsAt)}", dateLabel)
            }
            val secondary = if (current != null) agenda.upcoming.firstOrNull() else agenda.upcoming.getOrNull(1)
            val heading = when {
                agenda.today.isEmpty() -> "今天无课"
                agenda.remaining(now) == 0 -> "今日已结束"
                else -> "今日课表"
            }
            return ScheduleWidgetState(
                snapshot, agenda, now, heading, date,
                row(primary, if (current != null) "正在上课" else "下一节"),
                secondary?.let { row(it, if (current != null) "下一节" else "随后") },
                null, "查看课表",
                "今日 ${agenda.today.size} 堂 · 还剩 ${agenda.remaining(now)} 堂")
        }
    }
}
