package com.hnnujw.course.schedule

import org.junit.Assert.*
import org.junit.Test

/**
 * 「提醒设置 45 分钟，实际 15 分钟才响」的回归测试。
 *
 * 现场：课程详情页写着「下次提醒：11月30日 07:15」，设置也是「上课前 45 分钟」，
 * 但通知在 07:45 才弹出来 —— 只剩 15 分钟，用户当然认为提前量没生效。
 *
 * 计划侧没有错（第 1 节 08:00 − 45 分钟 = 07:15，CourseReminderPlanner 一直算得对），
 * 迟到发生在**送达**环节：setExactAndAllowWhileIdle 只保证「不早于」，系统（Doze、
 * 待机分组、国产 ROM 的后台冻结）可以把它压到之后才派发。这条链路没法在单测里模拟，
 * 所以在单测里钉住的是它带来的两个**口径**问题：
 *
 *  1. 迟到多久还算有效（以前 10 分钟就整条丢掉，一冻结就"这节课没提醒"）；
 *  2. 迟到的那条通知该怎么说（以前一律「即将上课」，08:20 送达时就是假话）。
 */
class ReminderTimingTest {

    private val minute = 60_000L
    private val start = 0L          // 把上课时刻当原点，便于读
    private val end = 45 * minute   // 一节课 45 分钟

    // ---------- 该不该发 ----------

    @Test
    fun onTimeDeliveryIsAlwaysWorthSending() {
        assertEquals(ReminderDelivery.Deliver, reminderDelivery(start, start, end))
    }

    @Test
    fun lateDeliveryInsideGraceIsStillSent() {
        // 系统冻结 30 分钟才派发：课上到一半，这条提醒仍然有用。
        assertEquals(ReminderDelivery.Deliver, reminderDelivery(30 * minute, start, end))
    }

    @Test
    fun deliveryAfterClassEndedIsStale() {
        // 课都上完了还弹通知，只会让人以为系统在瞎响。
        assertEquals(ReminderDelivery.Stale, reminderDelivery(end, start, end))
        assertEquals(ReminderDelivery.Stale, reminderDelivery(90 * minute, start, end))
    }

    @Test
    fun withoutEndTimeTheGraceWindowDecides() {
        // 拿不到下课时间（缺节次时间）时不能凭空造一个下课时刻，只按宽限判断。
        assertEquals(ReminderDelivery.Deliver, reminderDelivery(40 * minute, start, null))
        assertEquals(ReminderDelivery.Stale, reminderDelivery(LATE_GRACE_MS + minute, start, null))
    }

    @Test
    fun graceWindowCoversTheReportedHalfHourDelay() {
        // 现场那条就迟到了 30 分钟（07:15 排的、07:45 响）。旧口径 10 分钟宽限会直接丢掉，
        // 这正是「提醒没来」的另一张面孔 —— 现在必须发出来。
        assertTrue("30 分钟的延迟必须仍在宽限之内", 30 * minute <= LATE_GRACE_MS)
    }

    // ---------- 通知该怎么说 ----------

    @Test
    fun titleStaysUpcomingWhenDeliveredBeforeClass() {
        // 提前量大于系统延迟：07:15 送到、08:00 上课，仍然是「即将上课」。
        assertEquals("即将上课", reminderTitleSuffix(start - 45 * minute, start))
        assertEquals("即将上课", reminderTitleSuffix(start - 1, start))
    }

    @Test
    fun titleReportsElapsedMinutesOnceClassStarted() {
        // 迟到 30 分钟送达：不能再说「即将上课」，否则第一时间就会误导。
        assertEquals("已经开始 30 分钟", reminderTitleSuffix(start + 30 * minute, start))
    }

    @Test
    fun titleNeverSaysZeroMinutes() {
        // 刚过上课时刻几秒也至少说 1 分钟 —— 「已经开始 0 分钟」读起来像 bug。
        assertEquals("已经开始 1 分钟", reminderTitleSuffix(start + 1, start))
    }
}
