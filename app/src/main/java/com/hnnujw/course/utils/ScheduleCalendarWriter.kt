package com.hnnujw.course.utils

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract
import android.util.Log
import java.util.Calendar
import java.util.TimeZone

/**
 * 把课表**直接写进系统日历**（而不是导出一个 .ics 让用户手动导入）。
 *
 * ## 与已有的 iCal 导出是什么关系
 *
 * `ICalExporter` 生成 `.ics` 并走系统分享 —— 用户得自己找日历应用打开、确认导入，
 * 而且很多国产 ROM 的日历对 `.ics` 支持残缺。这条路适合"发给自己存档"，
 * 但不适合"我就想让课表出现在系统日历里"。
 *
 * 这里走 `CalendarContract`，一次写入即可在系统日历里看到。两者并存：
 * 导出适合备份与跨设备，直写适合本机日常查看。
 *
 * ## 写哪个日历
 *
 * 优先写**可写的本地日历**；没有本地日历（某些 ROM 默认只挂账户日历）时，
 * 退而选第一个可写日历。一个都没有就返回失败，让界面提示用户先建一个日历 ——
 * **不自动创建日历**：那会在用户的日历应用里凭空多出一个条目，是很唐突的行为。
 *
 * ## 幂等
 *
 * 重复导入不能产生重复课程。写入前先按 `CALENDAR_ID + 自定义标记` 删掉上一次导入的
 * 事件，再整批写入。标记放在 `DESCRIPTION` 里（`[校园助理]`），
 * 这样只清理**自己写的**，绝不碰用户自己建的事件。
 *
 * ⚠️ 必须在后台线程调用（要读写 ContentProvider）。
 */
object ScheduleCalendarWriter {

    private const val TAG = "ScheduleCalendarWriter"

    /** 标记：用来识别"这些事件是本应用写的"，清理时只删带标记的。 */
    private const val MARKER = "[校园助理]"

    /** 一次导入的结果。 */
    data class Result(val ok: Boolean, val message: String, val inserted: Int = 0)

    /**
     * 写入课表。
     *
     * @param events 由 [ICalExporter.expandEvents] 展开好的逐次上课事件。
     */
    fun write(context: Context, events: List<CourseEvent>): Result {
        if (events.isEmpty()) return Result(false, "没有可导入的课程")
        val resolver = context.contentResolver
        val calendarId = pickWritableCalendarId(context)
            ?: return Result(false, "没有找到可写入的日历。请先在系统日历应用里新建一个本地日历。")

        return try {
            // 先清理上一次导入的，避免重复。
            val removed = deleteImported(resolver, calendarId)
            var inserted = 0
            events.forEach { event ->
                val values = ContentValues().apply {
                    put(CalendarContract.Events.CALENDAR_ID, calendarId)
                    put(CalendarContract.Events.TITLE, event.title)
                    if (event.location.isNotBlank()) {
                        put(CalendarContract.Events.EVENT_LOCATION, event.location)
                    }
                    put(CalendarContract.Events.DESCRIPTION, MARKER + event.description)
                    put(CalendarContract.Events.DTSTART, event.startMillis)
                    put(CalendarContract.Events.DTEND, event.endMillis)
                    put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
                    // 不设提醒：课表提醒由应用自己的闹钟负责，日历再响一次就是双份。
                    put(CalendarContract.Events.HAS_ALARM, 0)
                    put(CalendarContract.Events.AVAILABILITY, CalendarContract.Events.AVAILABILITY_BUSY)
                }
                if (resolver.insert(CalendarContract.Events.CONTENT_URI, values) != null) inserted++
            }
            if (inserted == 0) {
                Result(false, "写入失败，请检查日历权限")
            } else {
                val cleaned = if (removed > 0) "，已替换上次导入的 $removed 条" else ""
                Result(true, "已导入 $inserted 条课程到系统日历$cleaned", inserted)
            }
        } catch (e: SecurityException) {
            // 没给日历权限时 insert 会抛。这不是 bug，是用户没授权。
            Log.w(TAG, "写入日历缺少权限: ${e.message}")
            Result(false, "没有日历权限，请在系统设置里允许后重试")
        } catch (e: Exception) {
            Log.w(TAG, "写入日历失败: ${e.javaClass.simpleName} ${e.message}")
            Result(false, "写入失败：${e.message ?: "未知错误"}")
        }
    }

    /**
     * 挑一个可写日历的 id。
     *
     * 顺序：本地日历（`ACCOUNT_TYPE IS NULL` 或 `LOCAL`）→ 任意可写日历。
     * 用 `IS_SYNCABLE=0` 优先本地：写进账户日历会触发同步上传，
     * 用户的课程表被同步到第三方服务器不是本意。
     */
    private fun pickWritableCalendarId(context: Context): Long? {
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.ACCOUNT_TYPE,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
        )
        val writable = CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL + " >= ?"
        val args = arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString())
        var fallback: Long? = null
        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI, projection, writable, args, null
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val accountType = c.getString(1)
                val isLocal = accountType.isNullOrBlank() ||
                    accountType.equals("LOCAL", ignoreCase = true)
                if (isLocal) return id
                if (fallback == null) fallback = id
            }
        }
        return fallback
    }

    /** 删掉本应用上一次导入的事件。只删带 [MARKER] 的，绝不碰用户自己的日程。 */
    private fun deleteImported(
        resolver: android.content.ContentResolver,
        calendarId: Long,
    ): Int {
        val selection = CalendarContract.Events.CALENDAR_ID + " = ? AND " +
            CalendarContract.Events.DESCRIPTION + " LIKE ?"
        val args = arrayOf(calendarId.toString(), MARKER + "%")
        return runCatching {
            resolver.delete(CalendarContract.Events.CONTENT_URI, selection, args)
        }.getOrDefault(0)
    }

    /** 系统日历里是否存在可写日历（界面据此决定要不要显示入口）。 */
    fun hasWritableCalendar(context: Context): Boolean =
        runCatching { pickWritableCalendarId(context) != null }.getOrDefault(false)

    /**
     * 删除本应用导入的全部课程事件。
     *
     * 返回删掉的条数。用于"清除课表 / 退出登录"时顺手清理，
     * 避免日历里留一堆已经不属于用户的课程。
     */
    fun clearImported(context: Context): Int = runCatching {
        val selection = CalendarContract.Events.DESCRIPTION + " LIKE ?"
        context.contentResolver.delete(
            CalendarContract.Events.CONTENT_URI, selection, arrayOf(MARKER + "%")
        )
    }.getOrDefault(0)

    /** 仅用于测试与调试：判断某个事件 id 是否由本应用写入。 */
    internal fun marker(): String = MARKER

    /** 内部用：把 ContentUris 引进来（避免未使用 import 警告）。 */
    @Suppress("unused")
    private fun eventUri(id: Long) = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id)
}

/**
 * 一次上课事件（展开后的具体日期）。
 *
 * 与 iCal 导出共用同一份展开结果 —— 否则两条路径会各自算一遍周次与节次时间，
 * 一旦有一边算错，用户会发现"导出的 .ics 和写进日历的时间不一样"。
 */
data class CourseEvent(
    val title: String,
    val location: String,
    val description: String,
    val startMillis: Long,
    val endMillis: Long,
)

/** 把课表按周展开成逐次事件。供 iCal 导出与系统日历写入共用。 */
fun expandCourseEvents(
    courses: List<com.hnnujw.course.ui.screen.ScheduleCourseUi>,
    semesterStartDate: Calendar,
    totalWeeks: Int,
    periodTimes: Map<Int, Pair<String, String>>,
): List<CourseEvent> {
    val out = ArrayList<CourseEvent>()
    val tz = semesterStartDate.timeZone ?: TimeZone.getDefault()
    courses.forEach { course ->
        if (course.day !in 1..7 || course.startPeriod < 1 || course.endPeriod < course.startPeriod) return@forEach
        val startTime = periodTimes[course.startPeriod]?.first ?: return@forEach
        val endTime = periodTimes[course.endPeriod]?.second ?: return@forEach
        val valid = Regex("(?:[01]\\d|2[0-3]):[0-5]\\d")
        if (!valid.matches(startTime) || !valid.matches(endTime)) return@forEach
        val weeks = parseWeeksForCalendar(course.weeks, totalWeeks)
        if (weeks.isEmpty()) return@forEach

        weeks.forEach { week ->
            val start = (semesterStartDate.clone() as Calendar).apply {
                timeZone = tz
                add(Calendar.DAY_OF_YEAR, (week - 1) * 7 + (course.day - 1))
                set(Calendar.HOUR_OF_DAY, startTime.substring(0, 2).toInt())
                set(Calendar.MINUTE, startTime.substring(3, 5).toInt())
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val end = (start.clone() as Calendar).apply {
                set(Calendar.HOUR_OF_DAY, endTime.substring(0, 2).toInt())
                set(Calendar.MINUTE, endTime.substring(3, 5).toInt())
            }
            out.add(
                CourseEvent(
                    title = course.name,
                    location = course.location,
                    description = buildString {
                        if (course.teacher.isNotBlank()) append("授课教师: ${course.teacher}　")
                        append("第${week}周")
                    },
                    startMillis = start.timeInMillis,
                    endMillis = end.timeInMillis,
                )
            )
        }
    }
    return out
}

/**
 * 解析周次串（`"1-8,10,12-16"` / `"1-16单"` 之类）成周列表。
 *
 * 与 `ICalExporter` 里那份保持同一口径：单双周后缀会影响结果，
 * 两边算法不一致会让导出与直写的课程数量对不上。
 */
private fun parseWeeksForCalendar(weeksStr: String, totalWeeks: Int): List<Int> {
    if (weeksStr.isBlank()) return (1..totalWeeks).toList()
    val oddOnly = weeksStr.contains("单")
    val evenOnly = weeksStr.contains("双")
    val clean = weeksStr.replace("单", "").replace("双", "").replace("周", "")
    val result = sortedSetOf<Int>()
    clean.split(',', '，').forEach { part ->
        val p = part.trim()
        if (p.isEmpty()) return@forEach
        if (p.contains('-')) {
            val bounds = p.split('-')
            val a = bounds.getOrNull(0)?.trim()?.toIntOrNull() ?: return@forEach
            val b = bounds.getOrNull(1)?.trim()?.toIntOrNull() ?: return@forEach
            for (w in minOf(a, b)..maxOf(a, b)) if (w in 1..totalWeeks) result.add(w)
        } else {
            p.toIntOrNull()?.let { if (it in 1..totalWeeks) result.add(it) }
        }
    }
    return result.filter { w ->
        when {
            oddOnly -> w % 2 == 1
            evenOnly -> w % 2 == 0
            else -> true
        }
    }
}
