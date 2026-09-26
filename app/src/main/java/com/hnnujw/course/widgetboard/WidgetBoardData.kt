package com.hnnujw.course.widgetboard

import com.hnnujw.course.academic.AcademicGradeReport
import com.hnnujw.course.academic.AcademicMessage
import com.hnnujw.course.academic.MessageCenterManager
import com.hnnujw.course.schedule.ExamCountdown
import com.hnnujw.course.schedule.ScheduleSnapshot
import com.hnnujw.course.schedule.ScheduleWidgetState
import com.hnnujw.course.secondclass.SecondClassExtraScore
import com.hnnujw.course.secondclass.SecondClassModule
import com.hnnujw.course.ui.screen.ExamItemUi
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 工作台各卡片的数据模型。
 *
 * 这一层刻意**只有纯数据**、不含任何 Compose / RemoteViews 类型：卡片渲染器
 * 拿到的是"已经算好的东西"，换皮肤、加尺寸、做预览都只是换一种画法。
 *
 * 与系统桌面组件共用同一套口径 —— 课表部分直接复用
 * [ScheduleWidgetState.from] 算出的状态（它就是当初把"算什么"从 RemoteViews 里
 * 拆出来的那一层），所以 App 内卡片和桌面组件的说法永远一致：
 * 同一时刻，两处都会说"正在上课"、都报同一个"还剩 N 堂"。
 */

/** 一行课程。时间已经格式化好，卡片不再碰时间戳。 */
data class CourseRowUi(
    val name: String,
    val location: String,
    val time: String,
    val status: String,
    val dateLabel: String,
    val ongoing: Boolean,
    /**
     * 这一行对应哪门课（`ScheduleCourseRecord.id` / `ScheduleCourseUi.id` 同一套口径）。
     *
     * 桌面上的这一行可以单独点开对应课程的详情，靠的就是它。空串表示"这行不对应任何
     * 单独一门课"（老缓存里读不出来的行），此时点击退回卡片根节点的行为。
     */
    val courseId: String = "",
)

data class ScheduleCardData(
    val heading: String,
    val date: String,
    val summary: String,
    val primary: CourseRowUi?,
    val next: CourseRowUi?,
    /** 没有主课程时的说明（未登录 / 请设置开学日期 / 今天没有课程…）。 */
    val message: String?,
    val actionLabel: String,
    val timeline: List<CourseRowUi>,
)

data class ExamCardData(
    val courseName: String,
    val examName: String,
    /** 距开考的自然日数；已开考为负。 */
    val days: Int,
    val time: String,
    val location: String,
    val seat: String,
)

data class GradesCardData(
    val gpa: String,
    val credits: String,
    /** 已出成绩的门数（分数非空的）。 */
    val count: Int,
    val highest: Double?,
    val lowest: Double?,
    val average: Double?,
    val fetchedAt: Long,
)

data class ModuleRowUi(val name: String, val mine: Double, val required: Double, val average: Double)

data class SecondClassCardData(
    val bound: Boolean,
    val modules: List<ModuleRowUi>,
    /**
     * 积分单位，取自站点下发的 `hourUnit`（"学时"/"学分"/"分数"，各校不同）。
     *
     * 为空表示拿不到单位（老缓存，或站点没配）—— 此时渲染层**不显示单位**，
     * 而不是硬凑一个"学时"：同一张卡片上的合计值说的是"总积分"，
     * 给明细写死"学时"会让两行自相矛盾。
     */
    val hourUnit: String = "",
    /** 综测附加分（[com.hnnujw.course.secondclass.SecondClassExtraScore] 口径）；算不出为 null。 */
    val extraScore: Double?,
    val updatedAt: Long,
) {
    /**
     * 明细行末尾的单位后缀（含前导空格，可直接拼在数值后面）。
     *
     * 桌面卡片（`CardWidgetRenderer`）与 App 内卡片（`WidgetBoardCards`）共用它，
     * 保证同一份数据在两处显示成同一句话。拿不到单位时是空串。
     */
    val unitSuffix: String get() = hourUnit.trim().let { if (it.isEmpty()) "" else " $it" }
}

data class MessagesCardData(
    val loaded: Boolean,
    val unread: Int,
    val total: Int,
    val latestTitle: String,
    val latestTime: String,
)

/**
 * 工作台一屏所需的全部数据。一次组装、按卡片分发 —— 卡片之间因此永远看到
 * **同一时刻**的口径，不会出现课表卡说"正在上课"、时间轴卡却把它标成"已结束"。
 */
data class WidgetBoardData(
    val now: Long,
    val schedule: ScheduleCardData,
    val exam: ExamCardData?,
    val grades: GradesCardData?,
    val secondClass: SecondClassCardData?,
    val messages: MessagesCardData,
)

// ── 构建 ───────────────────────────────────────────────────────────────────

private fun clock(millis: Long): String =
    SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(millis))

/**
 * 课表快照 → 卡片数据。
 *
 * @param timelineLimit 时间轴卡片最多列几节；由卡片按自己的尺寸传进来，
 *        这里不做"按高度动态裁剪"（那是系统组件的做法，App 内卡片高度是固定的）。
 */
internal fun scheduleCardData(
    snapshot: ScheduleSnapshot?,
    now: Long,
    timelineLimit: Int = 8,
): ScheduleCardData {
    val state = ScheduleWidgetState.from(snapshot, now)
    fun row(item: com.hnnujw.course.schedule.ScheduleWidgetCourse?, ongoing: Boolean) = item?.let {
        CourseRowUi(
            name = it.name,
            location = it.location,
            time = it.time,
            status = it.status,
            dateLabel = it.dateLabel,
            ongoing = ongoing,
            courseId = it.course.id,
        )
    }
    val ongoing = state.agenda?.current?.isNotEmpty() == true
    val timeline = state.agenda?.today.orEmpty().take(timelineLimit).map { occ ->
        val active = now in occ.startsAt until occ.endsAt
        CourseRowUi(
            name = occ.course.name,
            location = occ.course.location.ifBlank { "教室待定" },
            time = clock(occ.startsAt),
            status = when {
                active -> "进行中"
                occ.endsAt <= now -> "已结束"
                else -> "待上课"
            },
            dateLabel = "",
            ongoing = active,
            courseId = occ.course.id,
        )
    }
    return ScheduleCardData(
        heading = state.heading,
        date = state.date,
        summary = state.summary,
        primary = row(state.primary, ongoing),
        next = row(state.secondary, false),
        message = state.message,
        actionLabel = state.actionLabel,
        timeline = timeline,
    )
}

/**
 * 考试列表 → 最近一场考试的卡片数据。
 *
 * 只认能解析出日期的条目（[ExamCountdown.parseStart]）；一场都解析不出时返回
 * null，卡片显示"暂无考试安排"，**绝不猜日期**。已全部考完时取最后一场，
 * 卡片会显示成"已结束"，而不是凭空说"还有 -3 天"。
 */
internal fun examCardData(exams: List<ExamItemUi>, now: Long): ExamCardData? {
    val parsed = exams.mapNotNull { item ->
        ExamCountdown.parseStart(item.examTime)?.let { it to item }
    }
    if (parsed.isEmpty()) return null
    val picked = parsed.filter { it.first >= now }.minByOrNull { it.first }
        ?: parsed.maxByOrNull { it.first }!!
    return ExamCardData(
        courseName = picked.second.courseName,
        examName = picked.second.examName,
        days = ExamCountdown.calendarDayDiff(now, picked.first),
        time = picked.second.examTime,
        location = picked.second.location,
        seat = picked.second.seatNumber,
    )
}

/** 成绩报告 → 卡片数据。分数非数字（优秀 / 通过…）的条目不计入统计口径。 */
internal fun gradesCardData(report: AcademicGradeReport?, fetchedAt: Long): GradesCardData? {
    if (report == null) return null
    val numeric = report.grades.mapNotNull { it.score.trim().toDoubleOrNull() }
    return GradesCardData(
        gpa = report.gradePointAverage.ifBlank { "--" },
        credits = report.totalCredits.ifBlank { "--" },
        count = report.grades.count { it.score.isNotBlank() },
        highest = numeric.maxOrNull(),
        lowest = numeric.minOrNull(),
        average = if (numeric.isEmpty()) null else numeric.average(),
        fetchedAt = fetchedAt,
    )
}

/** 消息缓存 → 卡片数据。列表为空也返回非 null（"没有消息"本身是一个合法状态）。 */
internal fun messagesCardData(messages: List<AcademicMessage>?): MessagesCardData {
    val list = messages.orEmpty()
    val latest = list.firstOrNull()
    return MessagesCardData(
        loaded = messages != null,
        unread = MessageCenterManager.unreadCount(list),
        total = list.size,
        latestTitle = latest?.title.orEmpty(),
        latestTime = latest?.sendTime.orEmpty(),
    )
}

/**
 * 二课模块 → 卡片数据。
 *
 * 综测附加分直接调 [SecondClassExtraScore.compute]，**不在这里重算一遍权重** ——
 * 那套权重跟学校综测表绑定，散成两份迟早会不一致。
 *
 * @param bound 二课是否已绑定（持有 token）。未绑定时卡片显示"去绑定"，
 *        这种状态下即便缓存里还有数据也不展示：换了账号之后旧缓存会误导人。
 */
internal fun secondClassCardData(
    bound: Boolean,
    modules: List<SecondClassModule>,
    hourUnit: String,
    updatedAt: Long,
): SecondClassCardData = SecondClassCardData(
    bound = bound,
    modules = modules.map {
        ModuleRowUi(name = it.name, mine = it.mine, required = it.required, average = it.average)
    },
    hourUnit = hourUnit,
    extraScore = if (bound) SecondClassExtraScore.compute(modules) else null,
    updatedAt = updatedAt,
)
