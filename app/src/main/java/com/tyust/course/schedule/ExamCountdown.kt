package com.tyust.course.schedule

import java.util.Calendar
import java.util.TimeZone

/**
 * 考试时间的解析与倒计时换算。
 *
 * 教务返回的考试时间是自由文本：jwglxt 的 `kssj` 常见
 * "2026-01-15 09:00~11:00"，HTML 表格常见 "2026年1月15日 14:30-16:30"。
 * 这里用宽松正则提取「日期 + 首个时刻」，日期提取失败返回 null（UI 保持原样、不参与排序/提醒）；
 * 只认出日期没有时刻时按 08:00 兜底（考试几乎不会早于这个点开始）。
 */
object ExamCountdown {

    private val datePattern = Regex("(\\d{4})[-/.年]\\s*(\\d{1,2})[-/.月]\\s*(\\d{1,2})")
    private val timePattern = Regex("(\\d{1,2}):(\\d{2})")

    /** 解析考试开始时刻；解析不出返回 null。 */
    fun parseStart(raw: String?, zone: TimeZone = TimeZone.getDefault()): Long? {
        if (raw.isNullOrBlank()) return null
        val date = datePattern.find(raw) ?: return null
        val (year, month, day) = date.destructured
        val (hour, minute) = timePattern.find(raw)?.destructured
            ?.let { (h, m) ->
                val hourPart = h.toIntOrNull() ?: return@let null
                val minutePart = m.toIntOrNull() ?: return@let null
                if (hourPart in 0..23 && minutePart in 0..59) hourPart to minutePart else null
            }
            ?: (8 to 0)
        return runCatching {
            Calendar.getInstance(zone).apply {
                clear(); isLenient = false
                set(year.toInt(), month.toInt() - 1, day.toInt(), hour, minute)
                timeInMillis
            }.timeInMillis
        }.getOrNull()
    }

    /** 相差的自然日数（按当天零点算）；to 在 from 之前返回负数。 */
    fun calendarDayDiff(fromMillis: Long, toMillis: Long, zone: TimeZone = TimeZone.getDefault()): Int {
        fun floor(millis: Long) = Calendar.getInstance(zone).apply {
            timeInMillis = millis
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        return ((floor(toMillis) - floor(fromMillis)) / 86_400_000L).toInt()
    }

    /**
     * 倒计时徽标文案。考试已过开始时刻返回 null（行内不再显示倒计时，
     * 排序上这类考试自然沉底）。
     */
    fun countdownLabel(startMillis: Long, now: Long = System.currentTimeMillis(), zone: TimeZone = TimeZone.getDefault()): String? {
        if (now >= startMillis) return null
        val days = calendarDayDiff(now, startMillis, zone)
        return when {
            days <= 0 -> "今天开考"
            days == 1 -> "明天开考"
            else -> "还有 ${days}天"
        }
    }
}
