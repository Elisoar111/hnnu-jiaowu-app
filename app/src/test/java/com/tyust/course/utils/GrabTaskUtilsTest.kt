package com.tyust.course.utils

import org.junit.Assert.*
import org.junit.Test

/**
 * 三个功能性修复的回归锁：
 * 1. 后台日志（服务 / 闹钟接收器）与 UI 使用同一套截断策略，不再无限增长；
 * 2. 定时时间解析统一为严格 + Locale.US，非法输入一律返回 null（不宽容进位）；
 * 3. 闹钟 request code 公式唯一，保证开机重排后的 PendingIntent 与取消时的身份一致。
 */
class GrabTaskUtilsTest {

    @Test fun appendedLogKeepsOnlyTheMostRecentHundredLines() {
        var log = ""
        repeat(150) { index -> log = GrabTaskUtils.appendGrabLog(log, "[10:00:00] 第 $index 条\n") }

        val lines = log.trim().split('\n')
        assertEquals(100, lines.size)
        // 保留最新的 100 条：第 149 条在末尾，第 49 条已被挤出。
        assertTrue(lines.last().endsWith("第 149 条"))
        assertTrue(lines.first().endsWith("第 50 条"))
        assertFalse(log.contains("第 49 条"))
    }

    @Test fun appendedLogNormalisesTrailingNewlinesAndKeepsEntriesSeparate() {
        val log = GrabTaskUtils.appendGrabLog("[10:00:00] 旧记录\n", "[10:00:01] 新记录")
        assertEquals(listOf("[10:00:00] 旧记录", "[10:00:01] 新记录"), log.trim().split('\n'))
        assertTrue(log.endsWith("\n"))
    }

    @Test fun scheduledTimeParsingIsStrictAndRejectsMalformedInput() {
        // 严格模式：13 月 / 45 日 / 99:99 这类越界值必须判为非法，而不是静默进位成别的日期。
        assertNull(GrabTaskUtils.parseScheduledDateTime("2026/13/01 08:00"))
        assertNull(GrabTaskUtils.parseScheduledDateTime("2026/09/45 08:00"))
        assertNull(GrabTaskUtils.parseScheduledDateTime("2026/09/20 99:99"))
        assertNull(GrabTaskUtils.parseScheduledDateTime("2026-09-20 08:00"))
        assertNull(GrabTaskUtils.parseScheduledDateTime(""))
        assertNull(GrabTaskUtils.parseScheduledDateTime("   "))
    }

    @Test fun scheduledTimeFormattingRoundTripsThroughParsing() {
        val millis = GrabTaskUtils.parseScheduledDateTime("2026/09/20 08:30")
        assertNotNull(millis)
        assertEquals("2026/09/20 08:30", GrabTaskUtils.formatScheduledDateTime(millis!!))
        // 往返一次必须得到同一个时间点，不允许因格式或时区产生偏移。
        assertEquals(millis, GrabTaskUtils.parseScheduledDateTime(GrabTaskUtils.formatScheduledDateTime(millis)))
    }

    @Test fun alarmRequestCodeIsStablePerAccountAndDistinctAcrossAccounts() {
        // 开机重排与用户取消任务必须算出同一个 request code，否则会留下取消不掉的闹钟。
        assertEquals(GrabTaskUtils.grabAlarmRequestCode("hnnu::2024001"), GrabTaskUtils.grabAlarmRequestCode("hnnu::2024001"))
        assertNotEquals(GrabTaskUtils.grabAlarmRequestCode("hnnu::2024001"), GrabTaskUtils.grabAlarmRequestCode("hnnu::2024002"))
        // 历史实现的基数是 9999，改用工具函数后不能漂移。
        assertTrue(GrabTaskUtils.grabAlarmRequestCode("hnnu::2024001") >= 9999)
    }

    @Test fun alarmDataUriStaysIdenticalToTheProtocolScheduler() {
        assertEquals("academic-grab://schedule/hnnu::2024001", GrabTaskUtils.grabAlarmDataUri("hnnu::2024001"))
    }
}
