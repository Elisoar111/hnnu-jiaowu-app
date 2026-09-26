package com.hnnujw.course.widgetboard

import com.hnnujw.course.manager.StartupPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 桌面卡片"填什么内容"的纯函数部分。
 *
 * 这里值得钉住的不是排版（那是 RemoteViews 的事，单元测试碰不到），而是两件容易
 * 悄悄坏掉的事：
 *  1. **卡片 id 的两套登记表必须一一对应** —— 工作台抽屉一份（[InAppWidgetRegistry]）、
 *     桌面组件一份（[CardWidget]）。少一处，卡片能摆在工作台上却"添加到桌面"没有反应，
 *     而 App 不会崩、只在运行时静默失效，靠肉眼很难发现；
 *  2. **空数据不冒充数据** —— 桌面上把"还没加载出来"画成 `0`，比留白误导得多。
 */
class CardWidgetDraftTest {

    private fun data(
        schedule: ScheduleCardData = emptySchedule(),
        exam: ExamCardData? = null,
        grades: GradesCardData? = null,
        secondClass: SecondClassCardData? = null,
        messages: MessagesCardData = MessagesCardData(false, 0, 0, "", ""),
        now: Long = 1_700_000_000_000L,
    ) = WidgetBoardData(now, schedule, exam, grades, secondClass, messages)

    private fun emptySchedule() = ScheduleCardData(
        heading = "今天无课", date = "9月23日 周三", summary = "",
        primary = null, next = null, message = null, actionLabel = "查看课表", timeline = emptyList(),
    )

    private fun course(name: String, time: String = "08:00–09:40", status: String = "下一节", dateLabel: String = "") =
        CourseRowUi(name, "博学楼 A101", time, status, dateLabel, ongoing = false)

    // ── id 一一对应 ─────────────────────────────────────────────────────────

    @Test
    fun `工作台抽屉与桌面组件的卡片 id 完全一致`() {
        assertEquals(
            InAppWidgetRegistry.all.map { it.id }.toSet(),
            CardWidget.entries.map { it.cardId }.toSet(),
        )
    }

    @Test
    fun `每一种卡片都能算出自己的标题而不是落到兜底`() {
        CardWidget.entries.forEach { widget ->
            assertTrue(
                "cardId=${widget.cardId} 在渲染层没有分支，落到了兜底标题",
                cardTitle(widget.cardId) != "校园助理",
            )
        }
    }

    @Test
    fun `桌面组件的选择器名字与渲染层标题是同一个说法`() {
        // 长按桌面看到的「今日课表」和卡片里印的标题必须一致，否则用户会以为加错了。
        assertEquals(
            listOf("下一节课", "今日课表", "今日时间轴", "考试倒计时", "成绩概览", "第二课堂积分", "未读消息"),
            CardWidget.entries.map { cardTitle(it.cardId) },
        )
    }

    @Test
    fun `未知的卡片 id 返回兜底标题而不是崩溃`() {
        assertEquals("校园助理", cardTitle("card.removed.in.future"))
        assertEquals("未知的卡片类型", cardDraft("card.removed.in.future", data()).message)
    }

    // ── 空态不冒充数据 ───────────────────────────────────────────────────────

    @Test
    fun `没有课表时走说明加动作而不是画一屏零`() {
        val draft = cardDraft(
            "schedule.next",
            data(schedule = emptySchedule().copy(message = "登录后查看课表", actionLabel = "去登录")),
        )
        assertEquals("登录后查看课表", draft.message)
        assertEquals("去登录", draft.action)
        assertNull(draft.headline)
        assertTrue(draft.rows.isEmpty())
    }

    @Test
    fun `有课时下一节课卡只突出那一节`() {
        val draft = cardDraft(
            "schedule.next",
            data(
                schedule = emptySchedule().copy(
                    primary = course("高等数学", status = "正在上课"),
                    summary = "今日 3 堂 · 还剩 2 堂",
                )
            ),
        )
        assertEquals("正在上课", draft.badge)
        assertEquals("高等数学", draft.headline)
        assertEquals("08:00–09:40 · 博学楼 A101", draft.subline)
        assertEquals("今日 3 堂 · 还剩 2 堂", draft.footer)
    }

    @Test
    fun `时间轴卡按节次排开并标出进行中的那节`() {
        val draft = cardDraft(
            "schedule.timeline",
            data(
                schedule = emptySchedule().copy(
                    timeline = listOf(
                        course("高等数学", "08:00–09:40", "已结束"),
                        course("大学英语", "10:00–11:40", "进行中"),
                    )
                )
            ),
        )
        assertEquals(2, draft.rows.size)
        assertEquals("进行中", draft.rows[1].status)
        assertEquals("共 2 堂", draft.footer)
    }

    // ── 逐行 / 整卡点开某门课 ────────────────────────────────────────────────

    /**
     * 行要带上课程 id，桌面才能给这一行挂"打开这门课"的意图。
     *
     * 这里钉的是**顺序与对应关系**：id 跟错行的话，点第三节课打开第二节，
     * 用户只会觉得"这个组件是坏的"，而不会想到是 id 串了。
     */
    @Test
    fun `时间轴每一行各自带着自己那门课的 id`() {
        val draft = cardDraft(
            "schedule.timeline",
            data(
                schedule = emptySchedule().copy(
                    timeline = listOf(
                        course("高等数学").copy(courseId = "network:math"),
                        course("大学英语").copy(courseId = "network:english"),
                    )
                )
            ),
        )
        assertEquals(listOf("network:math", "network:english"), draft.rows.map { it.courseId })
    }

    /**
     * 拿不到 id 的行**不能**给一个空的 courseId：空串会被当成"没有目标"，
     * 行为退回卡片根节点（跳课表页）—— 这正是我们要的降级，而不是跳到一个不存在的课。
     */
    @Test
    fun `拿不到 id 的行没有点击目标`() {
        val draft = cardDraft(
            "schedule.timeline",
            data(schedule = emptySchedule().copy(timeline = listOf(course("高等数学")))),
        )
        assertNull(draft.rows.single().courseId)
    }

    @Test
    fun `今日课表的下一节行带着那门课的 id`() {
        val draft = cardDraft(
            "schedule.today",
            data(
                schedule = emptySchedule().copy(
                    primary = course("高等数学").copy(courseId = "network:math"),
                    next = course("大学英语").copy(courseId = "network:english"),
                )
            ),
        )
        assertEquals("network:english", draft.rows.single().courseId)
    }

    /** 「下一节课」整张卡就是那门课，点卡片本身就该开它。 */
    @Test
    fun `下一节课卡整张卡指向那门课`() {
        val draft = cardDraft(
            "schedule.next",
            data(schedule = emptySchedule().copy(primary = course("高等数学").copy(courseId = "network:math"))),
        )
        assertEquals("network:math", draft.courseId)
    }

    /** 今日课表是总览，整卡仍跳课表页；只有行级点击落到具体课程。 */
    @Test
    fun `今日课表卡整张不指向单门课`() {
        val draft = cardDraft(
            "schedule.today",
            data(schedule = emptySchedule().copy(primary = course("高等数学").copy(courseId = "network:math"))),
        )
        assertNull(draft.courseId)
    }

    @Test
    fun `考试倒计时按天数分三种说法`() {
        val exam = ExamCardData("高等数学", "期末考试", days = 5, time = "2026-01-06 09:00", location = "A101", seat = "12")
        assertEquals("还有 5 天", cardDraft("exam.countdown", data(exam = exam)).headline)
        assertEquals("今天开考", cardDraft("exam.countdown", data(exam = exam.copy(days = 0))).headline)
        assertEquals("已结束", cardDraft("exam.countdown", data(exam = exam.copy(days = -3))).headline)
    }

    @Test
    fun `没有考试安排时说没有而不是说还有零天`() {
        val draft = cardDraft("exam.countdown", data(exam = null))
        assertEquals("暂无考试安排", draft.message)
        assertNull(draft.headline)
    }

    @Test
    fun `成绩卡区分没有缓存与真的零门`() {
        assertNull(cardDraft("grades.overview", data(grades = null)).badge)

        val empty = GradesCardData(gpa = "--", credits = "--", count = 0, highest = null, lowest = null, average = null, fetchedAt = 0)
        val draft = cardDraft("grades.overview", data(grades = empty))
        assertEquals("已出 0 门", draft.badge)
        assertEquals("绩点 --", draft.headline)
        // 没有缓存时间就不显示"更新时间"，不显示比显示"54 年前"强。
        assertNull(draft.footer)
    }

    @Test
    fun `二课未绑定时不展示旧缓存`() {
        val draft = cardDraft(
            "secondclass.overview",
            data(secondClass = SecondClassCardData(bound = false, modules = emptyList(), extraScore = null, updatedAt = 0)),
        )
        assertEquals("还没绑定第二课堂", draft.message)
        assertEquals("去「我的」绑定", draft.action)
        assertTrue(draft.rows.isEmpty())
    }

    @Test
    fun `未读消息为零时说没有未读而不是说零条`() {
        val none = MessagesCardData(loaded = true, unread = 0, total = 12, latestTitle = "关于选课的通知", latestTime = "09-20 10:00")
        assertEquals("没有未读", cardDraft("message.unread", data(messages = none)).headline)

        val some = none.copy(unread = 3)
        val draft = cardDraft("message.unread", data(messages = some))
        assertEquals("3 条未读", draft.headline)
        assertEquals("共 12 条", draft.badge)
        assertEquals("关于选课的通知", draft.subline)
    }

    // ── 数字与时间 ───────────────────────────────────────────────────────────

    @Test
    fun `整数不带小数点小数留一位`() {
        assertEquals("12", number(12.0))
        assertEquals("0", number(0.0))
        assertEquals("3.5", number(3.456))
        assertEquals("3.4", number(3.444))
    }

    @Test
    fun `缓存年龄按分钟小时天分档`() {
        val now = 1_700_000_000_000L
        assertNull(cacheAgeLabel(0, now))
        assertEquals("刚刚更新", cacheAgeLabel(now - 30_000L, now))
        assertEquals("5 分钟前更新", cacheAgeLabel(now - 5 * 60_000L, now))
        assertEquals("3 小时前更新", cacheAgeLabel(now - 3 * 60 * 60_000L, now))
        assertEquals("2 天前更新", cacheAgeLabel(now - 2 * 24 * 60 * 60_000L, now))
    }

    @Test
    fun `缓存时间比现在还新时不显示成负数`() {
        val now = 1_700_000_000_000L
        assertEquals("刚刚更新", cacheAgeLabel(now + 60_000L, now))
    }

    // ── 点击落到哪一页 ───────────────────────────────────────────────────────

    @Test
    fun `每种卡片点开都落到自己那一页`() {
        // 与工作台里点卡片跳哪儿是同一张表（WidgetBoardRoute.open），改一处要同步另一处。
        val expected = mapOf(
            "schedule.next" to StartupPage.Schedule,
            "schedule.today" to StartupPage.Schedule,
            "schedule.timeline" to StartupPage.Schedule,
            "exam.countdown" to StartupPage.Grades,
            "grades.overview" to StartupPage.Grades,
            "secondclass.overview" to StartupPage.SecondClass,
        )
        CardWidget.entries.forEach { widget ->
            // 消息卡开的是独立 Activity，不走底栏 Tab，所以没有这一页。
            val page = if (widget.cardId == "message.unread") StartupPage.Settings
            else expected.getValue(widget.cardId)
            assertEquals("cardId=${widget.cardId}", page, CardWidgetNavigation.targetPage(widget.cardId))
        }
    }
}
