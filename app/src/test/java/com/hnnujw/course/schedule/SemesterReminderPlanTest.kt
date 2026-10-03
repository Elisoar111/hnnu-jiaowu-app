package com.hnnujw.course.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SemesterReminderPlan] 的 JVM 单测。全部是纯数据变换，不需要 Robolectric。
 *
 * 重点钉两条「用户会真的踩到」的语义：
 * 1. 关闭时**不给没开过的课建记录**（否则点一次「全部关闭」凭空多出几十条 false 记录）；
 * 2. 幂等 —— 已在目标态的课 revision **不涨**（反复点「全部开启」不该疯狂重排闹钟）。
 */
class SemesterReminderPlanTest {

    private val account = "acc-A"
    private val term = "2024-2025-2"

    private fun course(id: String, name: String = "课$id", weeks: String = "1-16周") = ScheduleCourseRecord(
        id = id,
        name = name,
        teacher = "T",
        location = "L",
        day = 1,
        startPeriod = 1,
        endPeriod = 2,
        weeks = weeks
    )

    private fun reminder(
        id: String,
        enabled: Boolean,
        revision: Long = 1L,
        lead: Int = 15,
        acc: String = account,
        t: String = term,
        courseCourse: ScheduleCourseRecord = course(id)
    ) = CourseReminder(
        key = CourseReminderKey(acc, t, id),
        course = courseCourse,
        enabled = enabled,
        leadMinutes = lead,
        revision = revision
    )

    // ── planSemesterToggle：全开启 ──────────────────────────────────

    @Test
    fun `enable creates records for all snapshot courses`() {
        val result = SemesterReminderPlan.planSemesterToggle(
            current = emptyList(),
            account = account, term = term,
            courses = listOf(course("1"), course("2")),
            enabled = true, leadMinutes = 30
        )
        assertEquals(2, result.size)
        assertTrue(result.all { it.enabled })
        // 提前量取**传入的全局值**，不是每条继承的 15
        assertTrue(result.all { it.leadMinutes == 30 })
    }

    @Test
    fun `enable bumps revision for existing records`() {
        val existing = reminder("1", enabled = false, revision = 5L)
        val result = SemesterReminderPlan.planSemesterToggle(
            current = listOf(existing),
            account = account, term = term,
            courses = listOf(course("1")),
            enabled = true, leadMinutes = 10
        )
        assertEquals(1, result.size)
        assertEquals(6L, result.first().revision)
        assertTrue(result.first().enabled)
    }

    @Test
    fun `enable leaves already-target state untouched`() {
        // 已开启且课程未变 → 完全跳过，revision 不涨（幂等的核心）
        val existing = reminder("1", enabled = true, revision = 7L)
        val result = SemesterReminderPlan.planSemesterToggle(
            current = listOf(existing),
            account = account, term = term,
            courses = listOf(course("1")),
            enabled = true, leadMinutes = 15
        )
        assertEquals(1, result.size)
        assertEquals(7L, result.first().revision)
        assertEquals(existing, result.first())
    }

    @Test
    fun `enable updates course content and bumps revision`() {
        val oldCourse = course("1", name = "旧名")
        val newCourse = course("1", name = "新名")
        val existing = reminder("1", enabled = true, revision = 3L, courseCourse = oldCourse)
        val result = SemesterReminderPlan.planSemesterToggle(
            current = listOf(existing),
            account = account, term = term,
            courses = listOf(newCourse),
            enabled = true, leadMinutes = 15
        )
        assertEquals(1, result.size)
        assertEquals("新名", result.first().course.name)
        assertEquals(4L, result.first().revision)
    }

    // ── planSemesterToggle：全关闭 ──────────────────────────────────

    @Test
    fun `disable does not create records for never-touched courses`() {
        // 关键语义：从没建过记录的课，关闭时不新建
        val result = SemesterReminderPlan.planSemesterToggle(
            current = emptyList(),
            account = account, term = term,
            courses = listOf(course("1"), course("2")),
            enabled = false, leadMinutes = 15
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun `disable flips only existing enabled records`() {
        val existing = reminder("1", enabled = true, revision = 2L)
        val result = SemesterReminderPlan.planSemesterToggle(
            current = listOf(existing),
            account = account, term = term,
            courses = listOf(course("1"), course("2")),
            enabled = false, leadMinutes = 15
        )
        assertEquals(1, result.size)
        assertFalse(result.first().enabled)
        assertEquals(3L, result.first().revision)
    }

    @Test
    fun `disable does not touch other accounts or terms`() {
        val mine = reminder("1", enabled = true, revision = 1L)
        val otherAccount = reminder("9", enabled = true, acc = "acc-B")
        val otherTerm = reminder("8", enabled = true, t = "2023-2024-1")
        val result = SemesterReminderPlan.planSemesterToggle(
            current = listOf(mine, otherAccount, otherTerm),
            account = account, term = term,
            courses = listOf(course("1")),
            enabled = false, leadMinutes = 15
        )
        // 自己那门被关；别的账号/学期原样保留
        assertEquals(3, result.size)
        assertFalse(result.first { it.key.courseId == "1" }.enabled)
        assertTrue(result.first { it.key.courseId == "9" }.enabled)
        assertTrue(result.first { it.key.courseId == "8" }.enabled)
        assertEquals(result.first { it.key.courseId == "9" }.revision, 1L)
    }

    // ── 边界与幂等 ──────────────────────────────────────────────────

    @Test
    fun `blank account returns same list`() {
        val current = listOf(reminder("1", enabled = true))
        val result = SemesterReminderPlan.planSemesterToggle(
            current, "", term, listOf(course("1")), true, 15
        )
        assertSame(current, result)
    }

    @Test
    fun `blank term returns same list`() {
        val current = listOf(reminder("1", enabled = true))
        val result = SemesterReminderPlan.planSemesterToggle(
            current, account, "", listOf(course("1")), true, 15
        )
        assertSame(current, result)
    }

    @Test
    fun `empty courses returns same list`() {
        val current = listOf(reminder("1", enabled = true))
        val result = SemesterReminderPlan.planSemesterToggle(
            current, account, term, emptyList(), true, 15
        )
        assertSame(current, result)
    }

    @Test
    fun `duplicate course ids produce one record`() {
        val result = SemesterReminderPlan.planSemesterToggle(
            current = emptyList(),
            account = account, term = term,
            courses = listOf(course("1"), course("1"), course("1")),
            enabled = true, leadMinutes = 15
        )
        assertEquals(1, result.size)
    }

    @Test
    fun `blank course id is skipped`() {
        val result = SemesterReminderPlan.planSemesterToggle(
            current = emptyList(),
            account = account, term = term,
            courses = listOf(course(""), course("1")),
            enabled = true, leadMinutes = 15
        )
        assertEquals(1, result.size)
        assertEquals("1", result.first().key.courseId)
    }

    @Test
    fun `enable is idempotent`() {
        val courses = listOf(course("1"), course("2"))
        val first = SemesterReminderPlan.planSemesterToggle(emptyList(), account, term, courses, true, 15)
        val second = SemesterReminderPlan.planSemesterToggle(first, account, term, courses, true, 15)
        // 第二次结果与第一次完全相同（revision 不涨）
        assertEquals(first, second)
    }

    @Test
    fun `disable after enable then disable is idempotent`() {
        val courses = listOf(course("1"))
        val enabled = SemesterReminderPlan.planSemesterToggle(emptyList(), account, term, courses, true, 15)
        val disabled1 = SemesterReminderPlan.planSemesterToggle(enabled, account, term, courses, false, 15)
        val disabled2 = SemesterReminderPlan.planSemesterToggle(disabled1, account, term, courses, false, 15)
        assertEquals(disabled1, disabled2)
    }

    // ── summarize ───────────────────────────────────────────────────

    @Test
    fun `summarize counts enabled and groups by availability`() {
        val statuses = mapOf(
            "1" to ReminderAvailability.Scheduled,
            "2" to ReminderAvailability.Scheduled,
            "3" to ReminderAvailability.NeedsPermission,
            "4" to ReminderAvailability.InvalidWeeks
        )
        val summary = SemesterReminderPlan.summarize(account, term, enabledCount = 3, statuses = statuses)
        assertEquals(4, summary.totalCount)
        assertEquals(3, summary.enabledCount)
        assertEquals(2, summary.byAvailability[ReminderAvailability.Scheduled])
        assertEquals(1, summary.byAvailability[ReminderAvailability.NeedsPermission])
        assertEquals(1, summary.byAvailability[ReminderAvailability.InvalidWeeks])
        assertFalse(summary.byAvailability.containsKey(ReminderAvailability.Off))
    }

    @Test
    fun `summarize flags allEnabled and noneEnabled`() {
        val all = SemesterReminderPlan.summarize(account, term, 2, mapOf(
            "1" to ReminderAvailability.Scheduled, "2" to ReminderAvailability.Scheduled
        ))
        assertTrue(all.allEnabled)
        assertFalse(all.noneEnabled)

        val none = SemesterReminderPlan.summarize(account, term, 0, mapOf(
            "1" to ReminderAvailability.Off
        ))
        assertTrue(none.noneEnabled)
        assertFalse(none.allEnabled)
    }

    @Test
    fun `summarize empty has no courses`() {
        val summary = SemesterReminderPlan.summarize(account, term, 0, emptyMap())
        assertFalse(summary.hasCourses)
        assertFalse(summary.allEnabled)
    }

    @Test
    fun `summarize byAvailability follows enum order`() {
        // 顺序稳定：按 ReminderAvailability 枚举定义序，便于稳定渲染与断言
        val statuses = mapOf(
            "1" to ReminderAvailability.InvalidWeeks,
            "2" to ReminderAvailability.NeedsPermission,
            "3" to ReminderAvailability.Scheduled
        )
        val summary = SemesterReminderPlan.summarize(account, term, 1, statuses)
        val keys = summary.byAvailability.keys.toList()
        assertEquals(
            listOf(ReminderAvailability.NeedsPermission, ReminderAvailability.InvalidWeeks, ReminderAvailability.Scheduled),
            keys
        )
    }

    // ── toggleAvailability ──────────────────────────────────────────

    @Test
    fun `toggle availability no snapshot when no courses`() {
        val summary = SemesterReminderPlan.summarize(account, term, 0, emptyMap())
        assertTrue(SemesterReminderPlan.toggleAvailability(summary, true) is SemesterReminderPlan.ToggleAvailability.NoSnapshot)
    }

    @Test
    fun `toggle availability master off takes priority over no snapshot`() {
        // 有课但总开关关着 → MasterOff（提示开总开关），而不是 NoSnapshot
        val summary = SemesterReminderPlan.summarize(account, term, 0, mapOf(
            "1" to ReminderAvailability.Off
        ))
        assertTrue(SemesterReminderPlan.toggleAvailability(summary, false) is SemesterReminderPlan.ToggleAvailability.MasterOff)
    }

    @Test
    fun `toggle availability ready with both directions`() {
        val summary = SemesterReminderPlan.summarize(account, term, 1, mapOf(
            "1" to ReminderAvailability.Scheduled, "2" to ReminderAvailability.Off
        ))
        val r = SemesterReminderPlan.toggleAvailability(summary, true)
        assertTrue(r is SemesterReminderPlan.ToggleAvailability.Ready)
        r as SemesterReminderPlan.ToggleAvailability.Ready
        assertTrue(r.canEnable)   // 还有一门没开
        assertTrue(r.canDisable)  // 还有一门开着
    }

    @Test
    fun `toggle availability cannot enable when all enabled`() {
        val summary = SemesterReminderPlan.summarize(account, term, 2, mapOf(
            "1" to ReminderAvailability.Scheduled, "2" to ReminderAvailability.Scheduled
        ))
        val r = SemesterReminderPlan.toggleAvailability(summary, true) as SemesterReminderPlan.ToggleAvailability.Ready
        assertFalse(r.canEnable)
        assertTrue(r.canDisable)
    }

    @Test
    fun `toggle availability cannot disable when none enabled`() {
        val summary = SemesterReminderPlan.summarize(account, term, 0, mapOf(
            "1" to ReminderAvailability.Off, "2" to ReminderAvailability.Off
        ))
        val r = SemesterReminderPlan.toggleAvailability(summary, true) as SemesterReminderPlan.ToggleAvailability.Ready
        assertTrue(r.canEnable)
        assertFalse(r.canDisable)
    }

    // ── 展示文案 ────────────────────────────────────────────────────

    @Test
    fun `availability labels are distinct and non-blank`() {
        val labels = ReminderAvailability.entries.map { SemesterReminderPlan.availabilityLabel(it) }
        assertTrue(labels.all { it.isNotBlank() })
        assertEquals(labels.size, labels.toSet().size)
    }

    @Test
    fun `breakdown joins non-zero groups`() {
        val summary = SemesterReminderPlan.summarize(account, term, 1, mapOf(
            "1" to ReminderAvailability.NeedsPermission,
            "2" to ReminderAvailability.NeedsPermission,
            "3" to ReminderAvailability.InvalidWeeks
        ))
        val text = SemesterReminderPlan.availabilityBreakdown(summary)
        assertTrue(text.contains("待授权 2 门"))
        assertTrue(text.contains("周次待核对 1 门"))
    }

    @Test
    fun `breakdown is empty for empty summary`() {
        val summary = SemesterReminderPlan.summarize(account, term, 0, emptyMap())
        assertEquals("", SemesterReminderPlan.availabilityBreakdown(summary))
    }
}
