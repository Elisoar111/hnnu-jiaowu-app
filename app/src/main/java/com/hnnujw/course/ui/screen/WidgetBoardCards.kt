package com.hnnujw.course.ui.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.AddToHomeScreen
import androidx.compose.material.icons.outlined.AspectRatio
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hnnujw.course.ui.system.LocalWallpaperAppearanceColors
import com.hnnujw.course.ui.system.glassBorderColor
import com.hnnujw.course.ui.system.glassSurfaceColor
import com.hnnujw.course.ui.theme.NeuPrimary
import com.hnnujw.course.widgetboard.CourseRowUi
import com.hnnujw.course.widgetboard.ExamCardData
import com.hnnujw.course.widgetboard.GradesCardData
import com.hnnujw.course.widgetboard.InAppWidgetRegistry
import com.hnnujw.course.widgetboard.MessagesCardData
import com.hnnujw.course.widgetboard.ModuleRowUi
import com.hnnujw.course.widgetboard.ScheduleCardData
import com.hnnujw.course.widgetboard.SecondClassCardData
import com.hnnujw.course.widgetboard.WidgetBoardData
import com.hnnujw.course.widgetboard.WidgetInstance
import com.hnnujw.course.widgetboard.WidgetSize
import com.hnnujw.course.widgetboard.number
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * App 内组件的渲染层：**全部是普通 Compose**，与系统桌面卡片那套 RemoteViews
 * （[com.hnnujw.course.widgetboard.CardWidgetRenderer]）是两套画法。
 *
 * 两者的关系有两处：数据口径共用 [com.hnnujw.course.widgetboard.scheduleCardData]，
 * 所以同一时刻 App 内卡片与桌面卡片说的是一样的话；卡片 id 也共用
 * （[com.hnnujw.course.widgetboard.CardWidget] 按 cardId 找组件），
 * 所以工作台里能直接把这张卡片「添加到桌面」。
 *
 * 卡片高度由 [WidgetSize.heightDp] 固定，**所有文字都显式给 lineHeight** ——
 * 系统字体放大到 1.5 倍时，靠默认行高会把固定高度的卡片顶破（截断成半行字）。
 */

// ── 外壳 ───────────────────────────────────────────────────────────────────

/**
 * 卡片外壳：玻璃底 + 圆角 + 顶部一行「图标 + 卡片名」。
 *
 * 卡片名固定由外壳画，是为了让所有卡片有一致的识别方式（尤其小尺寸时，
 * 光看一个数字根本不知道那是什么）。
 */
@Composable
internal fun WidgetCardShell(
    instance: WidgetInstance,
    size: WidgetSize,
    editing: Boolean,
    onTap: (() -> Unit)?,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val spec = remember(instance.sourceId) { InAppWidgetRegistry.find(instance.sourceId) }
    val appearance = LocalWallpaperAppearanceColors.current
    val onSurface = appearance.onSurface
    val onVariant = appearance.onSurfaceVariant

    // 编辑态下交给外层的拖动处理器接管手势：这里再挂点击/长按会互相抢。
    val gesture = if (editing) {
        Modifier
    } else {
        Modifier.pointerInput(instance.id, onTap, onLongPress) {
            detectTapGestures(
                onTap = { onTap?.invoke() },
                onLongPress = { onLongPress() },
            )
        }
    }

    androidx.compose.material3.Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(size.heightDp.dp)
            .then(gesture),
        // 18dp：与桌面组件的 card_widget_light/dark 同一档圆角 —— 同一张卡片在
        // 工作台里和钉到桌面上之后，轮廓应该看得出是同一个东西。
        shape = RoundedCornerShape(18.dp),
        color = glassSurfaceColor(),
        border = BorderStroke(0.5.dp, if (editing) NeuPrimary.copy(alpha = 0.85f) else glassBorderColor()),
        tonalElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (spec != null) {
                    Icon(
                        imageVector = spec.icon,
                        contentDescription = null,
                        tint = onVariant,
                        modifier = Modifier.size(13.dp),
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        text = spec.title,
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = onVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
                if (editing) {
                    // 编辑态：卡片的编辑动作从 CompositionLocal 取，见
                    // LocalWidgetCardEditActions 的说明。
                    val actions = LocalWidgetCardEditActions.current
                    if (actions != null) {
                        EditGlyph(Icons.Outlined.AspectRatio, "调整尺寸", actions.onResize)
                        Spacer(Modifier.width(5.dp))
                        EditGlyph(Icons.AutoMirrored.Outlined.AddToHomeScreen, "添加到桌面", actions.onPinToHome)
                        Spacer(Modifier.width(5.dp))
                        EditGlyph(Icons.Outlined.DeleteOutline, "从工作台移除", actions.onDelete)
                    }
                } else {
                    trailing?.invoke()
                }
            }
            content()
        }
    }
}

// ── 分派 ───────────────────────────────────────────────────────────────────

/** 按 `sourceId` 分派到具体卡片。注册表里没登记的 id 不会走到这里（存储层已过滤）。 */
@Composable
internal fun WidgetBoardCard(
    instance: WidgetInstance,
    data: WidgetBoardData,
    editing: Boolean,
    onOpen: (sourceId: String, action: String) -> Unit,
    onLongPress: () -> Unit,
    onCourseOpen: (courseId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val size = instance.size
    when (instance.sourceId) {
        "schedule.next" -> ScheduleNextCard(instance, size, data.schedule, editing, onOpen, onLongPress, onCourseOpen, modifier)
        "schedule.today" -> ScheduleTodayCard(instance, size, data.schedule, editing, onOpen, onLongPress, onCourseOpen, modifier)
        "schedule.timeline" -> ScheduleTimelineCard(instance, size, data.schedule, editing, onOpen, onLongPress, onCourseOpen, modifier)
        "exam.countdown" -> ExamCountdownCard(instance, size, data.exam, editing, onOpen, onLongPress, modifier)
        "grades.overview" -> GradesOverviewCard(instance, size, data.grades, editing, onOpen, onLongPress, modifier)
        "secondclass.overview" -> SecondClassOverviewCard(instance, size, data.secondClass, editing, onOpen, onLongPress, modifier)
        "message.unread" -> MessagesCard(instance, size, data.messages, editing, onOpen, onLongPress, modifier)
    }
}

// ── 课表类 ─────────────────────────────────────────────────────────────────

/** 课程行的两种画法：紧凑（名字 + 时间·教室一行）与舒展（名字 + 独立元信息行）。 */
@Composable
private fun CourseLine(
    row: CourseRowUi,
    dense: Boolean,
    showStatus: Boolean,
    accent: Boolean,
    /** 非空时这一行可点：跳到课表并打开这门课（`courseId` 为空时不挂，点击退回卡片根节点）。 */
    onCourseClick: (() -> Unit)? = null,
) {
    val appearance = LocalWallpaperAppearanceColors.current
    val nameColor = if (accent) NeuPrimary else appearance.onSurface
    Column(
        Modifier
            .fillMaxWidth()
            .then(if (onCourseClick != null) Modifier.clickable(onClick = onCourseClick) else Modifier)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = row.name.ifBlank { "未命名课程" },
                fontSize = if (dense) 13.sp else 15.sp,
                lineHeight = if (dense) 17.sp else 20.sp,
                fontWeight = FontWeight.Medium,
                color = nameColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (showStatus && row.status.isNotBlank()) {
                Spacer(Modifier.width(6.dp))
                Text(
                    text = row.status,
                    fontSize = 10.sp,
                    lineHeight = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (row.ongoing) NeuPrimary else appearance.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
        Text(
            text = listOf(row.dateLabel, row.time, row.location)
                .filter { it.isNotBlank() }
                .joinToString(" · "),
            fontSize = 11.sp,
            lineHeight = 14.sp,
            color = appearance.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 主课程为空时的空态：说明文案 + 一个动作提示。 */
@Composable
private fun EmptyHint(message: String, actionLabel: String) {
    val appearance = LocalWallpaperAppearanceColors.current
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = message,
            fontSize = 13.sp,
            lineHeight = 17.sp,
            fontWeight = FontWeight.Medium,
            color = appearance.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (actionLabel.isNotBlank()) {
            Text(
                text = "$actionLabel ›",
                fontSize = 11.sp,
                lineHeight = 15.sp,
                color = NeuPrimary,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun ScheduleNextCard(
    instance: WidgetInstance,
    size: WidgetSize,
    data: ScheduleCardData,
    editing: Boolean,
    onOpen: (String, String) -> Unit,
    onLongPress: () -> Unit,
    onCourseOpen: (String) -> Unit,
    modifier: Modifier,
) {
    val primary = data.primary
    WidgetCardShell(
        instance = instance,
        size = size,
        editing = editing,
        onTap = { onOpen(instance.sourceId, data.actionLabel) },
        onLongPress = onLongPress,
        modifier = modifier,
        trailing = {
            if (primary != null) {
                Text(
                    text = data.summary,
                    fontSize = 10.sp,
                    lineHeight = 13.sp,
                    color = LocalWallpaperAppearanceColors.current.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        },
    ) {
        if (primary == null) {
            EmptyHint(data.message ?: "今天没有课程", data.actionLabel)
        } else {
            val rowClick = if (editing || primary.courseId.isBlank()) null
            else ({ onCourseOpen(primary.courseId) })
            Spacer(Modifier.weight(0.2f))
            CourseLine(primary, dense = false, showStatus = true, accent = primary.ongoing, onCourseClick = rowClick)
            Spacer(Modifier.weight(1f))
            Text(
                // 曾经写成「第 「heading」」—— heading 是"今日课表 / 今天无课 / 今日已结束"
                // 这类整句状态，不是周次，于是页脚会印出「第 今日课表 · 9月23日 周三」。
                text = "${data.heading} · ${data.date}",
                fontSize = 10.sp,
                lineHeight = 13.sp,
                color = LocalWallpaperAppearanceColors.current.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ScheduleTodayCard(
    instance: WidgetInstance,
    size: WidgetSize,
    data: ScheduleCardData,
    editing: Boolean,
    onOpen: (String, String) -> Unit,
    onLongPress: () -> Unit,
    onCourseOpen: (String) -> Unit,
    modifier: Modifier,
) {
    val appearance = LocalWallpaperAppearanceColors.current
    WidgetCardShell(
        instance = instance,
        size = size,
        editing = editing,
        onTap = { onOpen(instance.sourceId, data.actionLabel) },
        onLongPress = onLongPress,
        modifier = modifier,
        trailing = {
            Text(
                text = data.summary.ifBlank { data.date },
                fontSize = 10.sp,
                lineHeight = 13.sp,
                color = appearance.onSurfaceVariant,
                maxLines = 1,
            )
        },
    ) {
        if (data.primary == null) {
            EmptyHint(data.message ?: "今天没有课程", data.actionLabel)
            return@WidgetCardShell
        }
        val primaryClick = if (editing || data.primary.courseId.isBlank()) null
        else ({ onCourseOpen(data.primary.courseId) })
        CourseLine(data.primary, dense = !size.roomy, showStatus = true, accent = data.primary.ongoing, onCourseClick = primaryClick)
        if (data.next != null && data.next.name != data.primary.name) {
            HorizontalHairline(alpha = 0.35f)
            val nextClick = if (editing || data.next.courseId.isBlank()) null
            else ({ onCourseOpen(data.next.courseId) })
            CourseLine(data.next, dense = true, showStatus = size.roomy, accent = false, onCourseClick = nextClick)
        }
        if (size.roomy) {
            Spacer(Modifier.weight(1f))
            Text(
                text = data.date,
                fontSize = 10.sp,
                lineHeight = 13.sp,
                color = appearance.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun ScheduleTimelineCard(
    instance: WidgetInstance,
    size: WidgetSize,
    data: ScheduleCardData,
    editing: Boolean,
    onOpen: (String, String) -> Unit,
    onLongPress: () -> Unit,
    onCourseOpen: (String) -> Unit,
    modifier: Modifier,
) {
    val appearance = LocalWallpaperAppearanceColors.current
    WidgetCardShell(
        instance = instance,
        size = size,
        editing = editing,
        onTap = { onOpen(instance.sourceId, data.actionLabel) },
        onLongPress = onLongPress,
        modifier = modifier,
        trailing = {
            Text(
                text = data.summary.ifBlank { data.date },
                fontSize = 10.sp,
                lineHeight = 13.sp,
                color = appearance.onSurfaceVariant,
                maxLines = 1,
            )
        },
    ) {
        if (data.timeline.isEmpty()) {
            EmptyHint(data.message ?: "今天没有课程", data.actionLabel)
            return@WidgetCardShell
        }
        // 行数按可用高度算。高卡（中/大）多画一行教室，行也就更高 —— 与桌面组件
        // "地方够才显示教室"是同一条取舍（参考实现里是 showLocation）。
        val showLocation = size.roomy
        val rowHeightDp = if (showLocation) 56 else 42
        val capacity = ((size.heightDp - 44) / rowHeightDp).coerceIn(1, 6)
        data.timeline.take(capacity).forEach { row ->
            // 左侧轨道 + 圆点，与桌面组件的 card_widget_timeline_row 同一套语义：
            // 竖轨给出"一天里的先后"，圆点标出"现在上到哪一节"。
            // 行高由内容决定，所以轨道用 IntrinsicSize.Min + fillMaxHeight 撑满整行 ——
            // 写死高度的话，系统字体放大后文字会溢出行容器。
            // 逐行点击：点这一行 = 跳到课表并打开这门课（与桌面时间轴卡同一语义）。
            // courseId 为空（老缓存读不出的行）不挂，点击退回卡片根节点。
            val rowClick = if (editing || row.courseId.isBlank()) null
            else ({ onCourseOpen(row.courseId) })
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min)
                    .then(if (rowClick != null) Modifier.clickable(onClick = rowClick) else Modifier),
            ) {
                Box(
                    modifier = Modifier.width(12.dp).fillMaxHeight(),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier
                            .width(1.dp)
                            .fillMaxHeight()
                            .background(appearance.border)
                    )
                    Box(
                        Modifier
                            .size(5.dp)
                            .clip(CircleShape)
                            .background(if (row.ongoing) NeuPrimary else appearance.onSurfaceVariant.copy(alpha = 0.5f))
                    )
                }
                Spacer(Modifier.width(8.dp))
                Column(
                    modifier = Modifier.weight(1f).padding(vertical = 5.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = row.time,
                            fontSize = 11.sp,
                            lineHeight = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (row.ongoing) NeuPrimary else appearance.onSurfaceVariant,
                            maxLines = 1,
                        )
                        Spacer(Modifier.weight(1f))
                        if (row.status.isNotBlank()) {
                            Text(
                                text = row.status,
                                fontSize = 10.sp,
                                lineHeight = 13.sp,
                                color = if (row.ongoing) NeuPrimary else appearance.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }
                    Text(
                        text = row.name.ifBlank { "未命名课程" },
                        fontSize = 12.sp,
                        lineHeight = 15.sp,
                        fontWeight = if (row.ongoing) FontWeight.Medium else FontWeight.Normal,
                        color = if (row.ongoing) NeuPrimary else appearance.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (showLocation && row.location.isNotBlank()) {
                        Text(
                            text = row.location,
                            fontSize = 10.sp,
                            lineHeight = 13.sp,
                            color = appearance.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

// ── 考试倒计时 ─────────────────────────────────────────────────────────────

@Composable
private fun ExamCountdownCard(
    instance: WidgetInstance,
    size: WidgetSize,
    data: ExamCardData?,
    editing: Boolean,
    onOpen: (String, String) -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier,
) {
    val appearance = LocalWallpaperAppearanceColors.current
    WidgetCardShell(
        instance = instance,
        size = size,
        editing = editing,
        onTap = { onOpen(instance.sourceId, "查看考试") },
        onLongPress = onLongPress,
        modifier = modifier,
    ) {
        if (data == null) {
            EmptyHint("暂无考试安排", "查看考试")
            return@WidgetCardShell
        }
        val number: String
        val unit: String
        when {
            data.days > 1 -> { number = data.days.toString(); unit = "天后" }
            data.days == 1 -> { number = "明天"; unit = "" }
            data.days == 0 -> { number = "今天"; unit = "开考" }
            else -> { number = "已结束"; unit = "" }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            Column {
                Text(
                    text = number,
                    fontSize = if (size.columns >= 4) 30.sp else 26.sp,
                    lineHeight = 34.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (data.days >= 0) NeuPrimary else appearance.onSurfaceVariant,
                    maxLines = 1,
                )
                if (unit.isNotBlank()) {
                    Text(
                        text = unit,
                        fontSize = 10.sp,
                        lineHeight = 13.sp,
                        color = appearance.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = data.courseName.ifBlank { data.examName.ifBlank { "考试" } },
                    fontSize = 14.sp,
                    lineHeight = 18.sp,
                    fontWeight = FontWeight.Medium,
                    color = appearance.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = data.time,
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                    color = appearance.onSurfaceVariant,
                    maxLines = if (size.roomy && size.columns >= 4) 2 else 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val place = listOf(data.location, data.seat.takeIf { it.isNotBlank() }?.let { "座位 $it" }.orEmpty())
                    .filter { it.isNotBlank() }
                    .joinToString(" · ")
                if (place.isNotBlank()) {
                    Text(
                        text = place,
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                        color = appearance.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

// ── 成绩 ───────────────────────────────────────────────────────────────────

@Composable
private fun GradesOverviewCard(
    instance: WidgetInstance,
    size: WidgetSize,
    data: GradesCardData?,
    editing: Boolean,
    onOpen: (String, String) -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier,
) {
    val appearance = LocalWallpaperAppearanceColors.current
    WidgetCardShell(
        instance = instance,
        size = size,
        editing = editing,
        onTap = { onOpen(instance.sourceId, "查看成绩") },
        onLongPress = onLongPress,
        modifier = modifier,
        trailing = {
            if (data != null) {
                Text(
                    text = "${data.count} 门",
                    fontSize = 10.sp,
                    lineHeight = 13.sp,
                    color = appearance.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        },
    ) {
        if (data == null) {
            EmptyHint("还没有成绩缓存", "查看成绩")
            return@WidgetCardShell
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = data.gpa,
                fontSize = if (size.columns >= 4) 32.sp else 28.sp,
                lineHeight = 36.sp,
                fontWeight = FontWeight.Medium,
                color = NeuPrimary,
                maxLines = 1,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "平均绩点 · ${data.credits} 学分",
                fontSize = 11.sp,
                lineHeight = 15.sp,
                color = appearance.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.padding(bottom = 5.dp),
            )
        }
        if (size.roomy || size.columns >= 4) {
            val stats = listOfNotNull(
                data.highest?.let { "最高 ${trimNumber(it)}" },
                data.average?.let { "平均 ${trimNumber(it)}" },
                data.lowest?.let { "最低 ${trimNumber(it)}" },
            )
            if (stats.isNotEmpty()) {
                HorizontalHairline(alpha = 0.3f)
                Text(
                    text = stats.joinToString("   "),
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    color = appearance.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.weight(1f))
        Text(
            text = cacheAgeLabel(data.fetchedAt),
            fontSize = 10.sp,
            lineHeight = 13.sp,
            color = appearance.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

// ── 第二课堂 ───────────────────────────────────────────────────────────────

@Composable
private fun SecondClassOverviewCard(
    instance: WidgetInstance,
    size: WidgetSize,
    data: SecondClassCardData?,
    editing: Boolean,
    onOpen: (String, String) -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier,
) {
    val appearance = LocalWallpaperAppearanceColors.current
    WidgetCardShell(
        instance = instance,
        size = size,
        editing = editing,
        onTap = { onOpen(instance.sourceId, "第二课堂") },
        onLongPress = onLongPress,
        modifier = modifier,
        trailing = {
            if (data != null && data.extraScore != null) {
                Text(
                    text = "附加分 ${trimNumber(data.extraScore)}",
                    fontSize = 10.sp,
                    lineHeight = 13.sp,
                    color = appearance.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        },
    ) {
        if (data == null || !data.bound) {
            EmptyHint("二课未绑定", "去绑定")
            return@WidgetCardShell
        }
        if (data.modules.isEmpty()) {
            EmptyHint("还没有积分数据", "第二课堂")
            return@WidgetCardShell
        }
        if (size.columns < 4 && !size.roomy) {
            // 小卡：只报模块总数与最高一项，塞不下进度条。
            val top = data.modules.maxByOrNull { it.mine }
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = trimNumber(data.modules.sumOf { it.mine }),
                    fontSize = 26.sp,
                    lineHeight = 30.sp,
                    fontWeight = FontWeight.Medium,
                    color = NeuPrimary,
                    maxLines = 1,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    // 合计值的单位同样随站点下发；拿不到时退回泛称"分"（与 SecondClassroomScreen
                    // 的"总积分"兜底同源），不写死具体单位
                    text = "${data.hourUnit.trim().ifBlank { "分" }} · ${data.modules.size} 个模块",
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    color = appearance.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            if (top != null) {
                Text(
                    text = "${top.name} 最高 ${trimNumber(top.mine)}",
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    color = appearance.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            return@WidgetCardShell
        }
        val capacity = if (size.roomy) 4 else 2
        data.modules.take(capacity).forEach { ModuleBar(it, data.unitSuffix) }
        if (size.roomy) {
            Spacer(Modifier.weight(1f))
            Text(
                text = "全校参考值取自站点数据 · ${cacheAgeLabel(data.updatedAt)}",
                fontSize = 10.sp,
                lineHeight = 13.sp,
                color = appearance.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ModuleBar(row: ModuleRowUi, unitSuffix: String) {
    val appearance = LocalWallpaperAppearanceColors.current
    val fraction = if (row.required > 0) (row.mine / row.required).coerceIn(0.0, 1.0).toFloat()
    else if (row.average > 0) (row.mine / row.average).coerceIn(0.0, 1.0).toFloat()
    else 0f
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = row.name,
                fontSize = 11.sp,
                lineHeight = 14.sp,
                color = appearance.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                // 单位随站点下发（hourUnit：学时 / 学分 / 分数），与桌面卡片共用同一个后缀
                text = (if (row.required > 0) "${trimNumber(row.mine)}/${trimNumber(row.required)}"
                else trimNumber(row.mine)) + unitSuffix,
                fontSize = 11.sp,
                lineHeight = 14.sp,
                fontWeight = FontWeight.Medium,
                color = NeuPrimary,
                maxLines = 1,
            )
        }
        Spacer(Modifier.height(3.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(3.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(appearance.onSurfaceVariant.copy(alpha = 0.22f))
        ) {
            if (fraction > 0f) {
                Box(
                    Modifier
                        .fillMaxWidth(fraction)
                        .fillMaxHeight()
                        .background(NeuPrimary, RoundedCornerShape(2.dp))
                )
            }
        }
    }
}

// ── 消息 ───────────────────────────────────────────────────────────────────

@Composable
private fun MessagesCard(
    instance: WidgetInstance,
    size: WidgetSize,
    data: MessagesCardData,
    editing: Boolean,
    onOpen: (String, String) -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier,
) {
    val appearance = LocalWallpaperAppearanceColors.current
    WidgetCardShell(
        instance = instance,
        size = size,
        editing = editing,
        onTap = { onOpen(instance.sourceId, "消息中心") },
        onLongPress = onLongPress,
        modifier = modifier,
        trailing = {
            if (data.loaded && data.total > 0) {
                Text(
                    text = "共 ${data.total} 条",
                    fontSize = 10.sp,
                    lineHeight = 13.sp,
                    color = appearance.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        },
    ) {
        if (!data.loaded) {
            EmptyHint("尚未同步消息", "消息中心")
            return@WidgetCardShell
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = data.unread.toString(),
                fontSize = 28.sp,
                lineHeight = 34.sp,
                fontWeight = FontWeight.Medium,
                color = if (data.unread > 0) NeuPrimary else appearance.onSurfaceVariant,
                maxLines = 1,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = if (data.unread > 0) "条未读" else "全部已读",
                fontSize = 11.sp,
                lineHeight = 15.sp,
                color = appearance.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.padding(bottom = 5.dp),
            )
        }
        if (data.latestTitle.isNotBlank()) {
            HorizontalHairline(alpha = 0.3f)
            Text(
                text = data.latestTitle,
                fontSize = 12.sp,
                lineHeight = 15.sp,
                color = appearance.onSurface,
                maxLines = if (size.roomy) 2 else 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (data.latestTime.isNotBlank() && (size.roomy || size.columns >= 4)) {
                Text(
                    text = data.latestTime,
                    fontSize = 10.sp,
                    lineHeight = 13.sp,
                    color = appearance.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// ── 小组件 ─────────────────────────────────────────────────────────────────

@Composable
private fun EditGlyph(icon: ImageVector, description: String, onClick: () -> Unit) {
    val appearance = LocalWallpaperAppearanceColors.current
    Box(
        modifier = Modifier
            .size(22.dp)
            .clip(CircleShape)
            .background(appearance.onSurfaceVariant.copy(alpha = 0.16f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = appearance.onSurface, modifier = Modifier.size(14.dp))
    }
}

@Composable
private fun HorizontalHairline(alpha: Float) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(0.5.dp)
            .background(LocalWallpaperAppearanceColors.current.onSurfaceVariant.copy(alpha = alpha))
    )
}

/**
 * 与桌面卡片**同一套**数字口径，见 [com.hnnujw.course.widgetboard.number]。
 *
 * 这里原本自己算一遍、保留两位小数，于是同一个绩点在 App 内是「3.46」、钉到桌面上
 * 变成「3.5」—— 两处读的是同一份缓存，却给出两个不一样的数字。这类不一致比"
 * 用哪种精度"重要得多：用户会以为其中一个坏了。所以只留一个实现。
 */
private fun trimNumber(value: Double): String =
    if (value.isNaN() || value.isInfinite()) "--" else number(value)

/** 「3 天前更新」这类新鲜度提示；未缓存时不显示（调用方自己会走空态）。 */
private fun cacheAgeLabel(at: Long): String {
    if (at <= 0L) return "尚未同步"
    val days = (System.currentTimeMillis() - at) / 86_400_000L
    return when {
        days <= 0 -> "今天同步"
        days == 1L -> "昨天同步"
        else -> SimpleDateFormat("M/d", Locale.CHINA).format(Date(at)) + " 同步"
    }
}
