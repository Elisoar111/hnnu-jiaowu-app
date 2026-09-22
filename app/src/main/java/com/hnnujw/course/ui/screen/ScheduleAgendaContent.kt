package com.hnnujw.course.ui.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hnnujw.course.schedule.ScheduleAgenda
import com.hnnujw.course.schedule.ScheduleDates
import com.hnnujw.course.schedule.ScheduleMaxWeeks
import com.hnnujw.course.schedule.ScheduleWeeks
import com.hnnujw.course.ui.system.LocalAppOverlayBottomInset
import com.hnnujw.course.ui.system.rememberGlassAccessibilityMode
import com.hnnujw.course.ui.theme.MotionProfile

/** 课表页内联提示：一句话 + 可选动作（如「设置开学日期」）。 */
@Composable
internal fun ScheduleNotice(message: String, action: String? = null, onAction: () -> Unit = {}) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 20.dp)
            .testTag("schedule-notice"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(message, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (action != null) TextButton(onAction) { Text(action) }
    }
}

/**
 * 日视图的一天列表：按开始节次排序的课程卡纵向排列。
 *
 * 数据口径与周网格一致（同一份 [courses]，已含冲突让位与配色）；此处只做
 * 「选某一天」的再过滤。顶部摘要行给出「今日 N 堂 · 还剩 M 堂 / 已结束」，
 * 剩余数来自 [agenda]（真实时间轴，含节次时间），只在看今天时有意义。
 */
@Composable
internal fun ScheduleDayList(
    courses: List<ScheduleCourseUi>,
    week: Int,
    day: Int,
    firstWeekDate: String?,
    times: List<PeriodTimeUi>,
    scrollState: ScrollState,
    topInset: Dp,
    onCourseClick: (ScheduleCourseUi) -> Unit,
    agenda: ScheduleAgenda,
    now: Long,
    isToday: Boolean,
    onCalendar: () -> Unit
) {
    val daily = remember(courses, week, day) {
        courses.filter { it.day == day && isInWeek(it.weeks, week) }
            .sortedWith(compareBy<ScheduleCourseUi> { it.startPeriod }.thenBy { it.name })
    }
    val unknown = daily.count { !ScheduleWeeks.parse(it.weeks).valid }
    Column(
        Modifier
            .fillMaxSize()
            .testTag("schedule-day-list")
            .verticalScroll(scrollState)
            .padding(start = 16.dp, end = 16.dp, top = topInset + 12.dp, bottom = LocalAppOverlayBottomInset.current + 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        when {
            ScheduleDates.firstMonday(firstWeekDate) == null ->
                ScheduleNotice("设置开学日期后显示当天课程", "设置开学日期", onCalendar)
            week !in 1..ScheduleMaxWeeks ->
                ScheduleNotice(if (week < 1) "尚未开学，当天没有课程" else "本学期已结束")
            else -> {
                val summary = if (isToday) {
                    "今日 ${daily.size} 堂" + when {
                        unknown > 0 -> " · $unknown 堂周次待核对"
                        daily.isNotEmpty() && agenda.remaining(now) == 0 -> " · 已结束"
                        else -> " · 还剩 ${agenda.remaining(now)} 堂"
                    }
                } else {
                    "当日 ${daily.size} 堂" + if (unknown > 0) " · $unknown 堂周次待核对" else ""
                }
                Text(
                    summary,
                    Modifier
                        .testTag("schedule-day-summary")
                        .padding(start = 4.dp, bottom = 2.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (daily.isEmpty()) {
                    ScheduleNotice(if (isToday) "今天没有课程" else "当天没有课程")
                }
                daily.forEach { course ->
                    androidx.compose.runtime.key(course.id) {
                        ScheduleDayCourse(
                            course = course,
                            times = times,
                            onClick = { onCourseClick(course) }
                        )
                    }
                }
            }
        }
    }
}

/**
 * 日视图的单堂课程卡。视觉语言与周网格的 [CourseCard] 同源：
 * 课程色半透填充 + 细边框 + 左侧色条；时间列在左，状态（正在上课 /
 * 下一节 / 周次待核对 / 同一时段有其他课程）在右下。
 */
@Composable
private fun ScheduleDayCourse(
    course: ScheduleCourseUi,
    times: List<PeriodTimeUi>,
    onClick: () -> Unit
) {
    val start = times.firstOrNull { it.period == course.startPeriod }?.startTime.orEmpty()
    val end = times.firstOrNull { it.period == course.endPeriod }?.endTime.orEmpty()
    val unknown = !ScheduleWeeks.parse(course.weeks).valid
    val colors = MaterialTheme.colorScheme
    val emphasis = course.isCurrent || course.isNext
    // 与 CourseCard 同一套填充强度：自定义（含补课）0.30、普通 0.22；正在上课略加深
    val fill = lerp(
        colors.surface, course.color,
        (if (course.isCustom) 0.30f else 0.22f) + if (course.isCurrent) 0.08f else 0f
    )
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val reduced = rememberGlassAccessibilityMode().reduceMotion
    val scale by animateFloatAsState(
        if (pressed && !reduced) 0.985f else 1f,
        MotionProfile.iconSpring(), label = "schedule-day-card-press"
    )
    val status = when {
        unknown -> "周次待核对"
        course.isCurrent -> "正在上课"
        course.isNext -> "下一节"
        course.hasConflict -> "同一时段有其他课程"
        else -> ""
    }
    val focus = remember { FocusRequester() }
    val registry = LocalScheduleFocus.current
    var bounds by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    DisposableEffect(course.id, registry, focus) {
        registry?.register(course.id, focus)
        onDispose { registry?.remove(course.id, focus) }
    }
    Surface(
        Modifier
            .fillMaxWidth()
            .testTag("schedule-day-course-${course.id}")
            .focusRequester(focus)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .onGloballyPositioned { bounds = it.boundsInWindow(); registry?.place(course.id, it.boundsInWindow()) }
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = {
                    registry?.register(course.id, focus)
                    bounds?.let { registry?.place(course.id, it) }
                    onClick()
                }
            )
            .semantics { stateDescription = status },
        color = fill,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(0.6.dp, course.color.copy(alpha = if (emphasis) 0.72f else 0.50f))
    ) {
        Row(
            Modifier
                .height(IntrinsicSize.Min)
                .padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(
                Modifier.width(64.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    start.ifBlank { "--:--" },
                    Modifier.fillMaxWidth(),
                    fontSize = 17.sp,
                    lineHeight = 21.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (emphasis) colors.primary else colors.onSurface,
                    maxLines = 1
                )
                Text(
                    end.ifBlank { "--:--" },
                    Modifier.fillMaxWidth(),
                    fontSize = 12.sp,
                    lineHeight = 15.sp,
                    color = colors.onSurfaceVariant,
                    maxLines = 1
                )
                Text(
                    "${course.startPeriod}–${course.endPeriod} 节",
                    Modifier.padding(top = 4.dp),
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                    color = colors.onSurfaceVariant
                )
            }
            Box(
                Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .background(course.color.copy(alpha = 0.78f), RoundedCornerShape(2.dp))
            )
            Column(
                Modifier
                    .weight(1f)
                    .align(Alignment.CenterVertically),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Text(
                    course.name,
                    Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (course.teacher.isNotBlank()) {
                    Text(
                        course.teacher,
                        Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    course.location.ifBlank { "教室待定" },
                    Modifier
                        .fillMaxWidth()
                        .testTag("schedule-day-location-${course.id}"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (status.isNotEmpty()) {
                    Text(
                        status,
                        Modifier.testTag("schedule-day-status-${course.id}"),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                        color = if (unknown || (course.hasConflict && !emphasis)) colors.error else colors.primary
                    )
                }
            }
        }
    }
}
