package com.tyust.course.schedule

import com.tyust.course.academic.AcademicTerm
import java.util.Calendar
import java.util.TimeZone

object ScheduleDates {
    /** UI-selected dates use Monday-based teaching weeks, in the user's local zone. */
    fun mondayOfWeek(millis: Long, zone: TimeZone = TimeZone.getDefault()): Calendar =
        Calendar.getInstance(zone).apply {
            timeInMillis = millis
            add(Calendar.DAY_OF_MONTH, -((get(Calendar.DAY_OF_WEEK) + 5) % 7))
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

    private fun calendarDate(value: String?, zone: TimeZone): Calendar? = runCatching {
        val parts = requireNotNull(value).split('-').map(String::toInt)
        require(parts.size == 3)
        Calendar.getInstance(zone).apply {
            clear(); isLenient = false
            set(parts[0], parts[1] - 1, parts[2])
            timeInMillis
        }
    }.getOrNull()

    fun firstMonday(value: String?, zone: TimeZone = TimeZone.getDefault()): Calendar? =
        calendarDate(value, zone)?.takeIf { it.get(Calendar.DAY_OF_WEEK) == Calendar.MONDAY }

    /** Older versions persisted any selected weekday; normalize the civil date without a zone shift. */
    fun normalizeFirstWeekDate(value: String?): String? {
        val zone = TimeZone.getTimeZone("UTC")
        val chosen = calendarDate(value, zone) ?: return null
        return ScheduleTimeBase.dateFromMillis(mondayOfWeek(chosen.timeInMillis, zone).timeInMillis, zone)
    }

    fun date(value: String?, week: Int, day: Int = 1, zone: TimeZone = TimeZone.getDefault()): Calendar? =
        firstMonday(value, zone)?.apply { add(Calendar.DAY_OF_YEAR, (week - 1) * 7 + day - 1) }

    /**
     * 星期条用的日期，**永不返回 null**。
     *
     * 上游已经有四级兜底，正常情况下 [date] 一定拿得到值。这里再兜一次是给
     * 【渲染层】兜底：星期条上退回占位小圆点是最容易被当成"日期功能坏了"的表现，
     * 而它一旦出现，说明前面的兜底全都没生效。与其给用户一个圆点，不如按
     * "本周周一 + 周次偏移"算一个日期 —— 偏差顶多几周，但日期行始终是可读的。
     *
     * 同时打一条日志，方便定位上游到底哪一层没接住。
     */
    fun weekdayDate(value: String?, week: Int, day: Int, zone: TimeZone = TimeZone.getDefault()): Calendar {
        date(value, week, day, zone)?.let { return it }
        val fallback = mondayOfWeek(System.currentTimeMillis(), zone)
        fallback.add(Calendar.DAY_OF_YEAR, (week - 1) * 7 + day - 1)
        android.util.Log.w(
            "ScheduleDates",
            "星期条日期兜底：firstWeekDate=$value week=$week day=$day → ${fallback.timeInMillis}"
        )
        return fallback
    }

    fun weekAt(value: String?, now: Long, zone: TimeZone = TimeZone.getDefault()): Int? {
        val start = firstMonday(value, TimeZone.getTimeZone("UTC")) ?: return null
        val local = Calendar.getInstance(zone).apply { timeInMillis = now }
        val today = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear(); set(local.get(Calendar.YEAR), local.get(Calendar.MONTH), local.get(Calendar.DAY_OF_MONTH))
        }
        val days = (today.timeInMillis - start.timeInMillis) / 86_400_000L
        return if (days < 0) null else (days / 7 + 1).toInt().takeIf { it in 1..ScheduleMaxWeeks }
    }

    /**
     * 学期第一周周一的默认值，用于用户未手动设置第一周日期时让课表直接显示日期。
     *
     * - 秋季学期（第一学期，[AcademicTerm.semester] == 1）：取该学年 9 月 1 日所在周的周一；
     * - 春季学期（第二学期及以后）：取次年 3 月 1 日所在周的周一。
     *
     * 与设置页手动选择日期的取整方式一致（[mondayOfWeek]，回退到所在周周一），
     * 因此 3 月 1 日、9 月 1 日都会落在该教学周之内。
     *
     * **termId 解析不出来时不再返回 null，而是按自然日历推断当前学年**（见 [calendarTerm]）：
     * 冷启动的第一帧、课表缓存命中但没有学期信息、演示环境等情况下 `resolvedTermId` 还是空串，
     * 早先这里返回 null 会让星期条下面**整行日期全空**——这正是"课表星期下没有日期"的表现。
     * 注意：这只是**展示**用兜底，不写入存储——用户在设置里指定了具体日期后以此为准。
     */
    fun defaultFirstWeekDate(
        termId: String,
        now: Long = System.currentTimeMillis(),
        zone: TimeZone = TimeZone.getDefault()
    ): String? = runCatching {
        val term = runCatching { AcademicTerm(termId) }.getOrNull()?.takeIf { it.year > 0 }
            ?: calendarTerm(now, zone)
        val (targetYear, targetMonth) = if (term.semester == 1) {
            term.year to Calendar.SEPTEMBER
        } else {
            (term.year + 1) to Calendar.MARCH
        }
        val target = Calendar.getInstance(zone).apply {
            clear(); isLenient = false; set(targetYear, targetMonth, 1); timeInMillis
        }
        dateFromMillisImpl(mondayOfWeek(target.timeInMillis, zone).timeInMillis, zone)
    }.getOrNull()

    /**
     * [defaultFirstWeekDate] 的**进程级记忆**——第二层兜底。
     *
     * 为什么需要它：上面那个函数虽然已经不会返回 null，但它依赖 `termId`（或自然日历）。
     * `ScheduleRoute` 的 `resolvedTermId` 在冷启动第一帧、快照恢复、切换账号时都可能退化成
     * 空串，此时按自然日历算出的年份/学期可能和真实学期不一致（比如 1 月看春季课表），
     * 更糟的是**每次重组都重算一遍**，值不稳。用户看到的就是"星期条下面的日期一会儿有一会儿没有"。
     *
     * 解决办法：把**首次算出来的**结果按 term 记下来。之后即便 termId 又空了，
     * 只要还查同一个 term 就能立刻拿回同一个日期，日期行不会闪。写入用 `synchronized`
     * 保护：`ScheduleRoute` 的重组与后台提醒线程都会读到这里。
     */
    private val rememberedFirstWeekDate = HashMap<String, String>()

    /** 记下某个 term 已经确定的第一周日期，供 [rememberedDefaultFirstWeekDate] 复用。 */
    fun rememberFirstWeekDate(termId: String, date: String) {
        if (termId.isBlank() || date.isBlank()) return
        synchronized(rememberedFirstWeekDate) { rememberedFirstWeekDate[termId] = date }
    }

    /** 取此前记下的第一周日期；没记过就返回 null（调用方继续走其它兜底）。 */
    fun rememberedDefaultFirstWeekDate(termId: String): String? =
        if (termId.isBlank()) null else synchronized(rememberedFirstWeekDate) { rememberedFirstWeekDate[termId] }

    /**
     * 解析"这一学期第一周应该是哪天"，**三层依次兜底**，永不返回 null。
     *
     * 1. 进程记忆里有就直接用（最稳，且与上一帧一致，不会闪）；
     * 2. 否则按 termId / 自然日历算（[defaultFirstWeekDate]）；
     * 3. 连日历都推不出来时，退回**本周周一**——宁可日期略有偏差，也好过整行空白。
     *
     * 算出来的结果会写回记忆，所以同一 term 只会真正计算一次。
     */
    fun resolveFirstWeekDate(
        termId: String,
        now: Long = System.currentTimeMillis(),
        zone: TimeZone = TimeZone.getDefault()
    ): String {
        rememberedDefaultFirstWeekDate(termId)?.let { return it }
        val resolved = defaultFirstWeekDate(termId, now, zone)
            ?: dateFromMillisImpl(mondayOfWeek(now, zone).timeInMillis, zone)
        rememberFirstWeekDate(termId, resolved)
        return resolved
    }

    /**
     * 按自然日历推断的当前学期，作为学期 ID 不可用时的兜底。
     *
     * 判据与教务侧的 `AcademicStudyReader.calendarTerm()` 一致：8 月及以后算秋季学期
     * （第一学期），2 月到 7 月算春季学期（第二学期），1 月归上一学年的秋季学期。
     */
    internal fun calendarTerm(now: Long, zone: TimeZone): AcademicTerm {
        val calendar = Calendar.getInstance(zone).apply { timeInMillis = now }
        val year = calendar.get(Calendar.YEAR)
        val month = calendar.get(Calendar.MONTH)
        val start = if (month >= Calendar.AUGUST) year else year - 1
        val semester = if (month >= Calendar.AUGUST || month < Calendar.FEBRUARY) 1 else 2
        return AcademicTerm("$start-${start + 1}-$semester")
    }

    /** [ScheduleTimeBase.dateFromMillis] 的包内直连，避免为默认值再走一层实例化。 */
    private fun dateFromMillisImpl(millis: Long, zone: TimeZone): String =
        ScheduleTimeBase.dateFromMillis(millis, zone)
}
