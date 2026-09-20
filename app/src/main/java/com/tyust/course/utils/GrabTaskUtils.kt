package com.tyust.course.utils

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 抢课任务共享工具：日志追加与定时时间解析。
 *
 * 为什么集中到这里：`GrabService` / `GrabAlarmReceiver`（后台）和 `GrabProRoute`（前台）
 * 原先各自维护一份"追加日志"和"解析 yyyy/MM/dd HH:mm"的实现，行为不一致——
 * 后台无限增长导致 SharedPreferences 膨胀，前台 `GrabProRoute` 用默认 Locale 且宽容解析，
 * 而 `AcademicRoutes` 用 Locale.US 严格解析。统一到这里，保证三条路径行为一致。
 */
object GrabTaskUtils {

    /** 日志保留的最大行数，与 UI 层（GrabProRoute.appendLog）的 100 条截断策略一致。 */
    private const val MAX_LOG_LINES = 100

    /**
     * 向现有日志文本追加一行，并把总行数截断到 [MAX_LOG_LINES]。
     * 返回的字符串可直接写回 SharedPreferences。
     */
    fun appendGrabLog(currentLog: String, entry: String): String {
        val combined = currentLog + entry.trimEnd('\n') + "\n"
        val lines = combined.split('\n').filter { it.isNotBlank() }
        val trimmed = if (lines.size > MAX_LOG_LINES) lines.takeLast(MAX_LOG_LINES) else lines
        return trimmed.joinToString("\n", postfix = "\n")
    }

    /** 定时任务时间格式：与 UI 选择器输出一致，固定 Locale.US 且严格（不宽容进位）。 */
    private const val SCHEDULED_PATTERN = "yyyy/MM/dd HH:mm"

    /**
     * 形状校验。`SimpleDateFormat` 对分隔符等字面量的匹配在不同 JDK 上并不一致
     * （`2026-09-20 08:00` 可能被"宽容"地当成合法输入），所以先卡死形状再交给严格解析。
     */
    private val SCHEDULED_SHAPE = Regex("""\d{4}/\d{2}/\d{2} \d{2}:\d{2}""")

    /**
     * 解析用户选择的定时时间字符串，非法或空白返回 null。
     * 供 `AcademicRoutes`、`GrabProRoute`、开机重排三处共用，避免两处解析策略不一致。
     */
    fun parseScheduledDateTime(value: String): Long? {
        if (value.isBlank() || !SCHEDULED_SHAPE.matches(value.trim())) return null
        return runCatching {
            SimpleDateFormat(SCHEDULED_PATTERN, Locale.US)
                .apply { isLenient = false }
                .parse(value.trim())?.time
        }.getOrNull()
    }

    /** 把毫秒时间格式化为定时任务字符串（写入 prefs / 展示用）。 */
    fun formatScheduledDateTime(millis: Long): String =
        SimpleDateFormat(SCHEDULED_PATTERN, Locale.US).format(Date(millis))

    /** 队列模式定时闹钟的 request code 基数，与历史实现保持一致。 */
    private const val GRAB_ALARM_REQUEST_CODE_BASE = 9999

    /**
     * 队列模式（GrabProRoute）闹钟的 PendingIntent request code。
     *
     * 开机重排时必须用同一个公式重建，否则重排出来的 PendingIntent 与用户取消任务时
     * 查找的身份不一致，会出现"取消不掉的闹钟"。
     */
    fun grabAlarmRequestCode(accountStorageKey: String): Int =
        GRAB_ALARM_REQUEST_CODE_BASE + (accountStorageKey.hashCode() and 0x0FFFFFFF)

    /** 协议模式（AcademicGrabScheduler）闹钟的 data URI，同样用于重排时保持身份一致。 */
    fun grabAlarmDataUri(accountStorageKey: String): String =
        "academic-grab://schedule/$accountStorageKey"
}
