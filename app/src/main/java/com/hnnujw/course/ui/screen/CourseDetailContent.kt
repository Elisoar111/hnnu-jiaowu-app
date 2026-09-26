package com.hnnujw.course.ui.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hnnujw.course.schedule.ScheduleConflict
import com.hnnujw.course.ui.system.*
import com.hnnujw.course.ui.theme.moduleEntrance

@Immutable
data class CourseDetailUiState(
    val course: ScheduleCourseUi,
    val conflicts: List<ScheduleConflict> = emptyList(),
    val reminderEnabled: Boolean = false,
    val reminderAvailable: Boolean = true,
    val reminderDescription: String = "未开启",
    val needsPermission: Boolean = false,
    val needsTime: Boolean = false,
    val invalidWeeks: Boolean = false,
    /** 是否为周末补课/调休课程（周六=6、周日=7 的排课）。 */
    val isMakeUp: Boolean = false,
    /** 该补课对应的平时上课星期（1-5）；找不到同名平日课程时为 null。 */
    val makeUpWeekday: Int? = null,
    val sourceCenterX: Float? = null,
    /** 时间冲突时的候选课程：自己 + 全部撞车的课（有冲突才 > 1）。 */
    val conflictCandidates: List<ScheduleCourseUi> = emptyList(),
    /**
     * 提前量文案（「上课前 15 分钟」/「上课时提醒」）。由调用方从提醒偏好里取 ——
     * 这里原先硬编码 15 分钟，把提前量做成可设置之后那句话就开始骗人了。
     */
    val reminderLeadText: String = "上课前 15 分钟"
)

/** Content-sized sheet with a bounded scroll body and a persistent, single primary action. */
@Composable
fun CourseDetailContent(
    ui: CourseDetailUiState, sheet: ScheduleBottomSheetState, onClose: () -> Unit,
    onReminderChanged: (Boolean) -> Unit, onPermission: () -> Unit,
    onConfigureTime: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit,
    onChooseConflictCourse: (String) -> Unit = {},
    onClearConflictChoice: () -> Unit = {}
) {
    val colors = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val reduced = rememberGlassAccessibilityMode().reduceMotion
    val progress = LocalDialogProgress.current
    val moduleProgress = LocalDialogModuleProgress.current ?: progress
    val entrance: () -> Float = { if (reduced) 1f else moduleProgress?.value?.coerceIn(0f, 1f) ?: 1f }
    val latestClose by rememberUpdatedState(onClose)
    val connection = remember(sheet) { sheet.nestedScroll { latestClose() } }
    SideEffect { sheet.density = density.density; sheet.reducedMotion = reduced }
    val course = ui.course
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val wideDetails = maxWidth >= 360.dp && density.fontScale <= 1.2f && course.location.length <= 18
        val compactHeight = maxHeight < 420.dp
        val titleDrift = ui.sourceCenterX?.let { (it - with(density) { maxWidth.toPx() } / 2f)
            .coerceIn(-with(density) { 16.dp.toPx() }, with(density) { 16.dp.toPx() }) } ?: 0f
        Surface(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).heightIn(max = maxHeight * 0.85f)
                .testTag("course-detail-surface").onSizeChanged { sheet.height = it.height.toFloat() }
                .nestedScroll(connection).semantics { contentDescription = "课程详情" },
            shape = RoundedCornerShape(28.dp), color = colors.surface,
            border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.45f)), shadowElevation = 10.dp
        ) {
            Column(Modifier.background(Brush.verticalGradient(
                listOf(course.color.copy(alpha = 0.06f), colors.surface), endY = with(density) { 144.dp.toPx() }
            )).padding(horizontal = 18.dp)) {
                Box(Modifier.fillMaxWidth().height(if (compactHeight) 16.dp else 26.dp)
                    .draggable(rememberDraggableState { sheet.dragBy(it) }, Orientation.Vertical,
                        onDragStopped = { sheet.finishDrag(it, onClose) })
                    .semantics { contentDescription = "下拉关闭课程详情" }, contentAlignment = Alignment.Center) {
                    Box(Modifier.size(32.dp, 4.dp).background(colors.outlineVariant, RoundedCornerShape(2.dp)))
                }
                Row(Modifier.fillMaxWidth().padding(bottom = 12.dp).moduleEntrance(0, entrance), verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f).padding(top = 5.dp).graphicsLayer {
                        translationX = (1f - entrance()) * titleDrift
                    }) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            Box(Modifier.size(22.dp, 4.dp).background(course.color, RoundedCornerShape(2.dp)))
                            if (!compactHeight) Text(if (course.isCustom) "自定义课程" else "课程详情", style = MaterialTheme.typography.labelMedium,
                                color = colors.onSurfaceVariant)
                        }
                        Text(course.name, Modifier.padding(top = 7.dp).semantics { heading() }, fontSize = 22.sp, lineHeight = 28.sp,
                            fontWeight = FontWeight.Bold, color = colors.onSurface, maxLines = if (compactHeight) 1 else 3,
                            overflow = TextOverflow.Ellipsis)
                    }
                    if (course.isCustom) SystemActionMenu(
                        description = "更多课程操作",
                        actions = listOf(SystemMenuAction("删除课程", Icons.Outlined.Delete, onDelete, destructive = true)),
                        modifier = Modifier.testTag("course-detail-more")
                    )
                    IconButton(onClose, Modifier.size(48.dp)) {
                        AnimatedLineIcon(AnimatedIconSpec.Close, description = "关闭课程详情", tint = colors.onSurfaceVariant)
                    }
                }
                // fill=false lets short courses wrap content and long courses use the remaining viewport.
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).testTag("course-detail-scroll"),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (ui.isMakeUp) {
                        Surface(
                            Modifier.fillMaxWidth(),
                            color = colors.secondaryContainer.copy(alpha = 0.55f),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Row(
                                Modifier.padding(12.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                AnimatedLineIcon(AnimatedIconSpec.Calendar, Modifier.size(18.dp), tint = colors.primary)
                                Text(
                                    text = if (ui.makeUpWeekday != null) {
                                        "补课/调休：本课时为周末补课，对应平时「${
                                            com.hnnujw.course.schedule.scheduleWeekdayLong(ui.makeUpWeekday)
                                        }」的《${course.name}》课程"
                                    } else {
                                        "补课/调休：本课时为周末补课课程"
                                    },
                                    color = colors.onSurface,
                                    style = MaterialTheme.typography.bodySmall,
                                    lineHeight = 18.sp
                                )
                            }
                        }
                    }
                    Column(Modifier.moduleEntrance(1, entrance), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val weekday = com.hnnujw.course.schedule.scheduleWeekdayShort(course.day)
                        val time = "$weekday · 第 ${course.startPeriod}–${course.endPeriod} 节"
                        if (wideDetails) Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            DetailInfoTile("上课时间", time, AnimatedIconSpec.Clock, Modifier.weight(1f).fillMaxHeight())
                            DetailInfoTile("上课地点", course.location.ifBlank { "未指定地点" }, AnimatedIconSpec.Location,
                                Modifier.weight(1f).fillMaxHeight())
                        } else {
                            DetailInfoTile("上课时间", time, AnimatedIconSpec.Clock)
                            DetailInfoTile("上课地点", course.location.ifBlank { "未指定地点" }, AnimatedIconSpec.Location)
                        }
                        Column(Modifier.padding(vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            DetailInfoLine("教师", course.teacher.ifBlank { "未指定教师" }, AnimatedIconSpec.Person)
                            DetailInfoLine("周次", course.weeks.ifBlank { "待补全" }, AnimatedIconSpec.Calendar)
                        }
                        if (ui.invalidWeeks) Text("周次待核对，暂不能安排提醒。", color = colors.error, style = MaterialTheme.typography.bodySmall)
                        ui.conflicts.forEach { conflict ->
                            Surface(color = colors.errorContainer, shape = RoundedCornerShape(14.dp)) {
                                Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    AnimatedLineIcon(AnimatedIconSpec.Warning, Modifier.size(18.dp), tint = colors.onErrorContainer)
                                    Text("与「${conflict.otherName}」在第 ${conflict.weeks.joinToString("、")} 周、第 " +
                                        "${conflict.startPeriod}–${conflict.endPeriod} 节重叠", color = colors.onErrorContainer,
                                        style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                        // 时间冲突选课：列出撞车的全部课程，选出"本节要上"的那门。
                        // 选中的课正常显示在课表上，其余的让位隐藏；随时可清除选择恢复全部。
                        if (ui.conflictCandidates.size > 1) {
                            val overlapPeriods = ui.conflicts.flatMap { c -> c.startPeriod..c.endPeriod }.toSet()
                            val chosenId = com.hnnujw.course.schedule.ScheduleConflictStore.choices.entries
                                .firstOrNull { (key, _) ->
                                    key.startsWith("${course.day}:") &&
                                        key.removePrefix("${course.day}:").toIntOrNull() in overlapPeriods
                                }?.value
                            Surface(Modifier.fillMaxWidth(), color = colors.surfaceContainerLow,
                                shape = RoundedCornerShape(18.dp)) {
                                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                                        AnimatedLineIcon(AnimatedIconSpec.Warning, Modifier.size(18.dp), tint = colors.primary)
                                        Text("时间冲突 · 本节上哪门？", style = MaterialTheme.typography.titleSmall)
                                    }
                                    Text("点选要上的课程，另一门会从课表格子里让位；随时可清除选择。",
                                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, lineHeight = 17.sp)
                                    ui.conflictCandidates.forEach { candidate ->
                                        val picked = chosenId == candidate.id
                                        Row(
                                            Modifier.fillMaxWidth()
                                                .clip(RoundedCornerShape(12.dp))
                                                .background(
                                                    if (picked) colors.primary.copy(alpha = 0.10f)
                                                    else colors.surfaceVariant.copy(alpha = 0.35f))
                                                .clickable { onChooseConflictCourse(candidate.id) }
                                                .padding(horizontal = 12.dp, vertical = 9.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Box(Modifier.size(18.dp, 4.dp).background(candidate.color, RoundedCornerShape(2.dp)))
                                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                                Text(
                                                    candidate.name + if (candidate.id == course.id) "（本节课）" else "",
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = colors.onSurface,
                                                    maxLines = 1, overflow = TextOverflow.Ellipsis
                                                )
                                                val meta = buildString {
                                                    append("第 ${candidate.startPeriod}–${candidate.endPeriod} 节")
                                                    if (candidate.teacher.isNotBlank()) append(" · ${candidate.teacher}")
                                                    if (candidate.location.isNotBlank()) append(" · ${candidate.location}")
                                                }
                                                Text(meta, style = MaterialTheme.typography.bodySmall,
                                                    color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            }
                                            if (picked) {
                                                Text("已选择", style = MaterialTheme.typography.labelMedium,
                                                    fontWeight = FontWeight.Bold, color = colors.primary)
                                            } else {
                                                Text("上这门", style = MaterialTheme.typography.labelMedium,
                                                    fontWeight = FontWeight.SemiBold, color = colors.primary,
                                                    modifier = Modifier
                                                        .clip(RoundedCornerShape(999.dp))
                                                        .background(colors.primary.copy(alpha = 0.12f))
                                                        .padding(horizontal = 10.dp, vertical = 4.dp))
                                            }
                                        }
                                    }
                                    if (chosenId != null) {
                                        Text(
                                            "清除选择 · 显示全部冲突课程",
                                            style = MaterialTheme.typography.labelLarge,
                                            color = colors.error,
                                            fontWeight = FontWeight.SemiBold,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(12.dp))
                                                .clickable(onClick = onClearConflictChoice)
                                                .padding(vertical = 8.dp),
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                }
                            }
                        }
                    }
                    Surface(Modifier.fillMaxWidth().moduleEntrance(2, entrance), color = colors.surfaceContainerLow,
                        shape = RoundedCornerShape(18.dp)) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                AnimatedLineIcon(AnimatedIconSpec.Bell, Modifier.size(20.dp),
                                    tint = if (ui.reminderEnabled) colors.primary else colors.onSurfaceVariant,
                                    state = if (ui.reminderEnabled) IconVisualState.Selected else IconVisualState.Idle)
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text("课程提醒", style = MaterialTheme.typography.titleSmall)
                                    Text(if (ui.reminderEnabled) ui.reminderLeadText else "未开启 · ${ui.reminderLeadText}",
                                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                                }
                                LiquidSwitch(ui.reminderEnabled, onReminderChanged, enabled = ui.reminderAvailable)
                            }
                            if (ui.reminderDescription.isNotBlank() && ui.reminderDescription !in listOf("未开启", "开启后，在上课前通知你")) {
                                Text(ui.reminderDescription, color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                            }
                            if (ui.needsPermission) SystemSecondaryButton("设置提醒权限", onPermission, Modifier.fillMaxWidth())
                            if (ui.needsTime) SystemSecondaryButton("设置学期时间", onConfigureTime, Modifier.fillMaxWidth())
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                }
                if (course.isCustom) {
                    Box(Modifier.fillMaxWidth().padding(top = if (compactHeight) 8.dp else 12.dp,
                        bottom = if (compactHeight) 10.dp else 14.dp).moduleEntrance(3, entrance)) {
                        SystemPrimaryButton("编辑课程", onEdit, Modifier.fillMaxWidth().testTag("course-detail-edit"),
                            leadingIcon = { AnimatedLineIcon(AnimatedIconSpec.Edit, Modifier.size(18.dp)) })
                    }
                } else Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun DetailInfoTile(label: String, value: String, icon: AnimatedIconSpec, modifier: Modifier = Modifier) {
    Surface(modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                AnimatedLineIcon(icon, Modifier.size(17.dp), tint = MaterialTheme.colorScheme.primary)
                Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(value, style = MaterialTheme.typography.titleSmall, lineHeight = 21.sp)
        }
    }
}

@Composable
private fun DetailInfoLine(label: String, value: String, icon: AnimatedIconSpec) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 3.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.Top) {
        AnimatedLineIcon(icon, Modifier.padding(top = 1.dp).size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}
