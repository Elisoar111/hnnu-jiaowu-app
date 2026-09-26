package com.hnnujw.course.schedule

import com.hnnujw.course.ui.screen.ExamItemUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 考前提醒"对齐闹钟"决策的回归测试。
 *
 * 盯住的是 [ExamReminderScheduler.decideReconcile] 里三个写错后**用户无从察觉**的不变量：
 *  1. 计划未变但闹钟已不在系统里时，必须重排 —— 计划表存在 SharedPreferences、
 *     闹钟活在 AlarmManager，开机 / 应用更新 / 强制停止 / 改时间都会清空后者而留下前者。
 *     只看计划表就会把该排的闹钟全部跳过，考前提醒从此静默消失（本次修复的缺陷）。
 *  2. 撤销只针对**本账号** —— 碰了别的账号，切一次号就把人家的考前提醒清了。
 *  3. 计划表 = 其它账号的计划 + 本账号最新计划。
 */
class ExamReminderReconcileTest {

    /** 用同一个解析器产生"现在"与考试时间，避免测试依赖运行机器的时区。 */
    private fun at(text: String): Long =
        requireNotNull(ExamCountdown.parseStart(text)) { "测试用例时间解析失败: $text" }

    private val now = at("2026-09-23 12:00")
    private val accountA = "hnnu__2024001"
    private val accountB = "hnnu__2024002"

    private fun exam(course: String, time: String, location: String = "教学楼 A101") =
        ExamItemUi(
            courseName = course,
            examTime = time,
            location = location,
            seatNumber = "12",
            examName = "期末考试",
            teacher = "张老师",
        )

    /**
     * 拿到"这条考试在本账号下的计划"（含 `id`）。
     *
     * `examId` 是私有摘要，测试无从复算；用一次空计划表的决策把同一条计划取出来，
     * 既拿到 id，也顺带验证了"计划表为空时必须排"。
     */
    private fun planned(item: ExamItemUi, account: String): ExamReminderScheduler.Plan =
        ExamReminderScheduler
            .decideReconcile(emptyMap(), listOf(item), account, now) { false }
            .schedule
            .single()

    @Test
    fun reminderIsScheduledTwentyFourHoursBeforeTheExam() {
        val plan = planned(exam("高等数学", "2026-10-01 09:00"), accountA)
        assertEquals(at("2026-10-01 09:00") - 24L * 60 * 60 * 1000, plan.triggerAt)
        assertEquals(accountA, plan.account)
    }

    @Test
    fun unchangedPlanWithMissingAlarmMustBeRescheduled() {
        val item = exam("高等数学", "2026-10-01 09:00")
        val plan = planned(item, accountA)
        val existing = mapOf(plan.id to plan)

        // 闹钟已被开机 / 应用更新 / 改时间清掉：计划表还认它，但系统里已经没有了
        val decision = ExamReminderScheduler.decideReconcile(existing, listOf(item), accountA, now) { false }

        assertEquals(listOf(plan), decision.schedule)
        assertTrue(decision.cancelIds.isEmpty())
        assertEquals(existing, decision.merged)
    }

    @Test
    fun unchangedPlanWithLiveAlarmIsLeftAlone() {
        val item = exam("高等数学", "2026-10-01 09:00")
        val plan = planned(item, accountA)
        val existing = mapOf(plan.id to plan)

        // 幂等：闹钟还在就不能重排（否则每次打开成绩页都要重设一遍闹钟）
        val decision = ExamReminderScheduler.decideReconcile(existing, listOf(item), accountA, now) { true }

        assertTrue(decision.schedule.isEmpty())
        assertTrue(decision.cancelIds.isEmpty())
    }

    @Test
    fun cancellingOnlyTouchesTheCurrentAccount() {
        val mine = planned(exam("高等数学", "2026-10-01 09:00"), accountA)
        val theirs = planned(exam("大学物理", "2026-10-02 09:00"), accountB)
        val existing = mapOf(mine.id to mine, theirs.id to theirs)

        // 本账号已没有考试（例如缓存被清空），另一个账号的计划必须原样保留
        val decision = ExamReminderScheduler.decideReconcile(existing, emptyList(), accountA, now) { true }

        assertEquals(listOf(mine.id), decision.cancelIds)
        assertEquals(mapOf(theirs.id to theirs), decision.merged)
    }

    @Test
    fun otherAccountsPlansSurviveWhenTheCurrentAccountGetsNewExams() {
        val mine = planned(exam("高等数学", "2026-10-01 09:00"), accountA)
        val theirs = planned(exam("大学物理", "2026-10-02 09:00"), accountB)
        val existing = mapOf(mine.id to mine, theirs.id to theirs)

        val decision = ExamReminderScheduler.decideReconcile(existing, listOf(exam("高等数学", "2026-10-01 09:00")), accountA, now) { true }

        assertEquals(existing, decision.merged)
        assertTrue(decision.cancelIds.isEmpty())
    }

    @Test
    fun examsAlreadyStartedOrUnparsableAreNotScheduled() {
        val started = exam("已开考", "2026-09-20 09:00")
        val unknown = exam("时间待定", "另行通知")

        val decision = ExamReminderScheduler.decideReconcile(emptyMap(), listOf(started, unknown), accountA, now) { false }

        // 宁缺勿错：解析不出时刻、或提醒时刻已经过去的，一条都不排
        assertTrue(decision.schedule.isEmpty())
        assertTrue(decision.merged.isEmpty())
    }

    @Test
    fun changedExamTimeCancelsTheOldAlarmAndSchedulesTheNewOne() {
        val before = exam("高等数学", "2026-10-01 09:00")
        val after = exam("高等数学", "2026-10-02 09:00")
        val oldPlan = planned(before, accountA)

        val decision = ExamReminderScheduler.decideReconcile(mapOf(oldPlan.id to oldPlan), listOf(after), accountA, now) { true }

        // 改期后 id 变化（摘要含考试时间）：旧闹钟必须撤掉，新闹钟必须排上
        assertEquals(listOf(oldPlan.id), decision.cancelIds)
        assertEquals(1, decision.schedule.size)
        assertNotEquals(oldPlan.id, decision.schedule.single().id)
        assertEquals(1, decision.merged.size)
    }

    // ── 播报守卫（"删号 / 登出 / 切号后仍收到考试提醒"的判据）─────────────────
    //
    // 为什么单独盯这一组：`reconcile` 刻意**保留**其它账号的计划（见上面两条跨账号用例），
    // 所以被删账号的闹钟会永久留在系统里，开机重排还会排回来。闹钟到点时
    // `ExamReminderScheduler.receive` 若无账号守卫，就会弹出一条属于已删除账号的提醒。
    // 课前提醒那条链路靠 `isCurrentReminderOccurrence` 传 `activeAccount()` 天然挡住了，
    // 考前提醒此前没有对应兜底。

    @Test
    fun reminderIsDeliveredForItsOwnAccount() {
        val plan = planned(exam("高等数学", "2026-10-01 09:00"), accountA)

        assertTrue(ExamReminderScheduler.shouldDeliver(plan, isLoggedIn = true, isDemoMode = false, accountA))
    }

    @Test
    fun reminderOfAnotherAccountMustNotBeDelivered() {
        val plan = planned(exam("高等数学", "2026-10-01 09:00"), accountA)

        // 切号 / 删号后仍登录着另一个账号：绝不能替已失效的账号播报
        assertFalse(ExamReminderScheduler.shouldDeliver(plan, isLoggedIn = true, isDemoMode = false, accountB))
    }

    @Test
    fun reminderMustNotBeDeliveredAfterLogout() {
        val plan = planned(exam("高等数学", "2026-10-01 09:00"), accountA)

        // 登出后 activeAccount 为空：连自己账号的计划也不再播报
        assertFalse(ExamReminderScheduler.shouldDeliver(plan, isLoggedIn = false, isDemoMode = false, ""))
        assertFalse(ExamReminderScheduler.shouldDeliver(plan, isLoggedIn = false, isDemoMode = false, accountA))
    }

    @Test
    fun reminderMustNotBeDeliveredInDemoMode() {
        val plan = planned(exam("高等数学", "2026-10-01 09:00"), accountA)

        // 演示模式的考试是造出来的，不该弹真通知
        assertFalse(ExamReminderScheduler.shouldDeliver(plan, isLoggedIn = true, isDemoMode = true, accountA))
    }

    @Test
    fun legacyPlanWithoutAccountIsTreatedAsTheCurrentAccount() {
        val legacy = planned(exam("高等数学", "2026-10-01 09:00"), accountA).copy(account = "")

        // 升级前写入的计划没有 account 字段，按"属于当前账号"处理，不能因此丢掉提醒
        assertTrue(ExamReminderScheduler.shouldDeliver(legacy, isLoggedIn = true, isDemoMode = false, accountA))
    }
}
