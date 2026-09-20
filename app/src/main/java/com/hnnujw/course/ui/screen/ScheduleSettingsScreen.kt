package com.hnnujw.course.ui.screen

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.shapes.Capsule
import com.hnnujw.course.manager.AppearanceSettingsManager
import com.hnnujw.course.manager.ScheduleSettingsManager
import com.hnnujw.course.manager.ScheduleSettingsManager.PeriodTime
import com.hnnujw.course.ui.system.DialogHost
import com.hnnujw.course.ui.system.GlassWindowHost
import com.hnnujw.course.ui.system.GlassCircleButton
import com.hnnujw.course.ui.system.GlassDatePickerDialog
import com.hnnujw.course.ui.system.GlassOptionWheelDialog
import com.hnnujw.course.ui.system.GlassTimeRangePickerDialog
import com.hnnujw.course.ui.system.InsetGroupedRow
import com.hnnujw.course.ui.system.InsetGroupedSection
import com.hnnujw.course.ui.system.LocalAppBackdrop
import com.hnnujw.course.ui.system.LocalAppOverlayBottomInset
import com.hnnujw.course.ui.system.LocalControlBackdrop
import com.hnnujw.course.ui.system.LocalDialogHost
import com.hnnujw.course.ui.system.LocalFloatingNotice
import com.hnnujw.course.ui.system.LocalModalBackdrop
import com.hnnujw.course.ui.system.LocalNoticeAnchor
import com.hnnujw.course.ui.system.NoticeAnchorState
import com.hnnujw.course.ui.system.PagePadding
import com.hnnujw.course.ui.system.SectionSpacing
import com.hnnujw.course.ui.system.SystemTopBar
import com.hnnujw.course.ui.system.SystemConfirmDialog
import com.hnnujw.course.ui.system.drawWallpaperPattern
import com.hnnujw.course.ui.system.isBackdropSupported
import com.hnnujw.course.ui.system.rememberDialogHostState
import com.hnnujw.course.ui.system.rememberGlassAccessibilityMode
import com.hnnujw.course.ui.theme.NeuPrimary
import com.hnnujw.course.ui.theme.SemanticDanger
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val PeriodCountMin = 8
private const val PeriodCountMax = 16

/**
 * 课表设置页。
 *
 * 这一页运行在【独立的 Dialog 窗口】里（见 ScheduleRoute），跨窗口拿不到主窗口的
 * 捕获层，所以它必须自己铺一层壁纸并导出 backdrop——否则 SystemTopBar /
 * InsetGroupedSection 之类的组件全都退化成灰卡片，整页看起来像另一个 App。
 * 同理它也自带一个 DialogHost，让嵌套的日期/时间滚轮走同窗口 portal，
 * 能压暗、有弹簧入场、并且采样得到本窗口的壁纸。
 *
 * @param onShowDatePicker 遗留的外部日期选择回调（旧 Fragment 入口在用）。
 *        为 null 时本页用自己的玻璃滚轮日期选择器。
 */
@Composable
fun ScheduleSettingsScreen(
    manager: ScheduleSettingsManager,
    onClose: () -> Unit,
    onShowDatePicker: (() -> Unit)? = null,
    semesterStartOverride: Long? = null,
    onSemesterStartChange: ((Long) -> Unit)? = null,
    onPeriodTimesChange: ((List<PeriodTime>) -> Unit)? = null,
    periodTimesOverride: List<PeriodTime>? = null,
    customCourses: List<ScheduleSettingsManager.CustomCourse> = emptyList(),
    onAddCustomCourse: (() -> Unit)? = null,
    onEditCustomCourse: (String) -> Unit = {},
    /**
     * 删除一门自定义课程。为空时列表只读（不显示删除按钮）。
     *
     * 删除必须由调用方落到 manager + 提醒调度器上（见 `ScheduleRoute`），
     * 本页只负责发出意图 —— 设置页自己改 store 会漏掉提醒记录，
     * 于是"课表上没了、通知还在响"。
     */
    onDeleteCustomCourse: ((String) -> Unit)? = null,
    onSyncSchedule: (() -> Unit)? = null,
    /**
     * 课表当前**实际生效**的第一周日期（`yyyy-MM-dd`）。
     * 用来在「第一周开始日期」这行直接告诉用户日期是设过的还是推算出来的，
     * 免得为了定位"日期对不上"还得连电脑抓日志。
     */
    effectiveFirstWeekDate: String? = null
) {
    var periodCount by remember { mutableStateOf(manager.periodCount) }
    var storedPeriodTimes by remember { mutableStateOf(periodTimesOverride ?: manager.getPeriodTimes()) }
    var semesterStartDate by remember { mutableStateOf(semesterStartOverride ?: manager.semesterStartDate) }
    var showDatePicker by remember { mutableStateOf(false) }
    var showPeriodCountPicker by remember { mutableStateOf(false) }
    var showFontScalePicker by remember { mutableStateOf(false) }
    /** 课表字号倍率（本地态，改完同步落 manager）。 */
    var fontScale by remember { mutableStateOf(manager.scheduleFontScale) }
    var editingPeriod by remember { mutableStateOf<PeriodTime?>(null) }
    /** 待确认删除的自定义课程 id；null = 没有待删项。 */
    var pendingDeleteId by remember { mutableStateOf<String?>(null) }

    val dateFormat = remember { SimpleDateFormat("yyyy年M月d日", Locale.CHINA) }
    val dateText = if (semesterStartDate > 0) dateFormat.format(Date(semesterStartDate)) else "未设置"

    // 外部（旧 Fragment 的 MaterialDatePicker）改了日期要能同步回来
    LaunchedEffect(manager.semesterStartDate, semesterStartOverride) {
        semesterStartDate = semesterStartOverride ?: manager.semesterStartDate
    }
    LaunchedEffect(periodTimesOverride) { periodTimesOverride?.let { storedPeriodTimes = it } }

    // getPeriodTimes() 返回存档或 12 条默认值，与 periodCount 无关——所以选了
    // 16 节之后列表仍然只有 12 行，而课表已经画 16 行。这里按 periodCount 裁剪/补齐。
    // 超出默认表的节次补空串显示"未设置"，不凭空编时间。
    val periodTimes = remember(periodCount, storedPeriodTimes) {
        val byPeriod = storedPeriodTimes.associateBy { it.period }
        val defaults = manager.getDefaultPeriodTimes().associateBy { it.period }
        (1..periodCount).map { period ->
            byPeriod[period] ?: defaults[period] ?: PeriodTime(period, "", "")
        }
    }

    val scrollState = rememberScrollState()
    val headerCollapse by remember {
        derivedStateOf { (scrollState.value / 96f).coerceIn(0f, 1f) }
    }

    // 入场错峰只在刚进入时播一次；之后任何重组都直接渲染终态
    val accessibility = rememberGlassAccessibilityMode()
    var entranceSettled by remember { mutableStateOf(accessibility.reduceMotion) }
    LaunchedEffect(Unit) {
        delay(700)
        entranceSettled = true
    }

    // 顶栏会 reportNoticeAnchor()，不隔离的话本窗口顶栏的底边会被写进主窗口的
    // 锚点状态，关掉设置页后主窗口的悬浮通知会停在一个错误的落点上。
    val noticeAnchorState = remember { NoticeAnchorState() }

    GlassWindowHost(modifier = Modifier.fillMaxSize()) {
        CompositionLocalProvider(
            LocalFloatingNotice provides null,
            LocalNoticeAnchor provides noticeAnchorState,
            // 主窗口给的是 96dp（底栏高度），Dialog 沿用父 composition 会把它带进来，
            // 这个窗口没有底栏，不清零就会在页面底部空出一块。
            LocalAppOverlayBottomInset provides 0.dp
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                SystemTopBar(
                    title = "课表设置",
                    subtitle = "学期起始与节次时间",
                    collapseFraction = headerCollapse,
                    navigationIcon = {
                        GlassCircleButton(
                            onClick = onClose,
                            icon = Icons.Default.Close,
                            contentDescription = "关闭",
                            size = 34.dp
                        )
                    },
                    actions = {
                        Text(
                            text = "完成",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = NeuPrimary,
                            modifier = Modifier
                                .clip(Capsule())
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    role = Role.Button,
                                    onClick = onClose
                                )
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                )

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState)
                        .padding(
                            start = PagePadding,
                            end = PagePadding,
                            top = 8.dp,
                            bottom = 32.dp
                        ),
                    verticalArrangement = Arrangement.spacedBy(SectionSpacing)
                ) {
                    if (onAddCustomCourse != null) {
                        InsetGroupedSection(
                            header = "自定义课程",
                            footer = if (customCourses.isEmpty()) {
                                "还没有自定义课程。补课生成的课程也会出现在这里，可以随时删除。"
                            } else {
                                "点课程可修改；点右侧删除按钮移除该课程（补课生成的课程同样可删）。"
                            }
                        ) {
                            InsetGroupedRow(title = "添加课程", subtitle = "课程只保存在当前账号", onClick = onAddCustomCourse)
                            customCourses.forEachIndexed { index, course ->
                                InsetGroupedRow(
                                    title = course.name.ifBlank { "未命名课程" },
                                    subtitle = "周${course.day} · ${course.startPeriod}-${course.endPeriod} 节 · ${course.weeks}",
                                    showDivider = false,
                                    onClick = { onEditCustomCourse(course.id) },
                                    trailing = {
                                        if (onDeleteCustomCourse != null) {
                                            // 用 IconButton 而不是让整行可删：整行点击已经给了"编辑"，
                                            // 删除是不可逆操作，必须是一个独立的、要瞄准的目标。
                                            IconButton(
                                                onClick = { pendingDeleteId = course.id },
                                                modifier = Modifier.size(34.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Outlined.DeleteOutline,
                                                    contentDescription = "删除${course.name.ifBlank { "该课程" }}",
                                                    tint = SemanticDanger,
                                                    modifier = Modifier.size(19.dp)
                                                )
                                            }
                                        }
                                    }
                                )
                                // 每行下面都画分隔线（`showDivider = false` 关掉的是行内那条），
                                // 让"课程条目"与"添加课程"在视觉上分成两组。
                                if (index != customCourses.lastIndex) {
                                    HorizontalDivider(
                                        modifier = Modifier.padding(start = 16.dp),
                                        thickness = 0.5.dp,
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                                    )
                                }
                            }
                        }
                    }
                    StaggerIn(index = 0, settled = entranceSettled) {
                        InsetGroupedSection(
                            header = "基础设置",
                            footer = "未设置第一周日期时，秋季学期默认 9 月 1 日、春季学期默认 3 月 1 日所在周为第一周；如需精确周次，点上方手动指定。"
                        ) {
                            // 把「当前实际生效的日期」直接写在副标题里：
                            // 用户不需要连电脑抓日志也能看出日期是【自己设的】还是【按学期推算的】——
                            // 后者和自己真实的开学周经常差几周，光看星期下的数字分不出来。
                            val effectiveDate = effectiveFirstWeekDate.orEmpty()
                            InsetGroupedRow(
                                icon = Icons.Filled.DateRange,
                                iconTint = Color(0xFF0A84FF),
                                title = "第一周开始日期",
                                subtitle = when {
                                    effectiveDate.isBlank() ->
                                        "按所选日期所在周的周一计算周次；当前推算不出日期"
                                    semesterStartDate > 0L ->
                                        "按所选日期所在周的周一计算周次；当前生效 $effectiveDate（已按你的设置）"
                                    else ->
                                        "按所选日期所在周的周一计算周次；当前生效 $effectiveDate（按学期推算，非你设置）"
                                },
                                trailing = {
                                    Text(
                                        text = dateText,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                        color = NeuPrimary
                                    )
                                },
                                onClick = {
                                    if (onShowDatePicker != null) {
                                        onShowDatePicker()
                                    } else {
                                        showDatePicker = true
                                    }
                                }
                            )
                            InsetGroupedRow(
                                icon = Icons.Outlined.AccessTime,
                                iconTint = Color(0xFFFF9F0A),
                                title = "每天节数",
                                subtitle = "课表纵向的节次总数",
                                trailing = {
                                    Text(
                                        text = "$periodCount 节",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                        color = NeuPrimary
                                    )
                                },
                                // 原先是 Material DropdownMenu；换成与节次时间同一种
                                // 滚轮弹窗，两处交互一致
                                onClick = { showPeriodCountPicker = true }
                            )
                            // 课表字号：**只改课表**。设置的是"渲染密度里的 fontScale 倍率"，
                            // 所以网格几何（列宽 / 行高 / 留白）一点不变，只有字变大变小；
                            // 其它页面不读这个值，不受影响。
                            InsetGroupedRow(
                                icon = Icons.Filled.FormatSize,
                                iconTint = Color(0xFF34C759),
                                title = "课表字号",
                                subtitle = "只影响课表内的课程卡片与节次时间",
                                trailing = {
                                    Text(
                                        text = scheduleFontScaleLabel(fontScale),
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                        color = NeuPrimary
                                    )
                                },
                                showDivider = false,
                                onClick = { showFontScalePicker = true }
                            )
                        }
                    }

                    if (onSyncSchedule != null) {
                        InsetGroupedSection(header = "课表数据") {
                            InsetGroupedRow(title = "同步课表", subtitle = "从教务系统更新正在查看的学期",
                                showDivider = false, onClick = onSyncSchedule)
                        }
                    }
                    StaggerIn(index = 1, settled = entranceSettled) {
                        InsetGroupedSection(
                            header = "节次时间",
                            footer = "点任意一节可用滚轮修改起止时间"
                        ) {
                            periodTimes.forEachIndexed { index, periodTime ->
                                val hasTime = periodTime.startTime.isNotBlank() &&
                                    periodTime.endTime.isNotBlank()
                                // 不给图标：十几行同一枚时钟图标只是噪声，
                                // 「第 N 节」本身已经是最强的识别信息
                                InsetGroupedRow(
                                    title = "第 ${periodTime.period} 节",
                                    trailing = {
                                        GlassFilterChip(
                                            label = if (hasTime) {
                                                "${periodTime.startTime} - ${periodTime.endTime}"
                                            } else {
                                                "未设置"
                                            },
                                            selected = hasTime,
                                            compact = true
                                        )
                                    },
                                    showDivider = index != periodTimes.lastIndex,
                                    onClick = { editingPeriod = periodTime }
                                )
                            }
                        }
                    }
                }
            }

            // 三个 picker 必须写在这个 CompositionLocalProvider 【里面】。
            // 放在外面时 LocalDialogHost.current 取到的是【主窗口】那个 host
            // （MainActivity 下发的），SystemDialog 会把弹窗注册进主窗口渲染，
            // 而主窗口在这个 Dialog 窗口的后面——弹窗于是被设置页整页挡住、看不见。
            if (showDatePicker) {
                GlassDatePickerDialog(
                    title = "选择第一周日期",
                    initialMillis = if (semesterStartDate > 0) {
                        semesterStartDate
                    } else {
                        System.currentTimeMillis()
                    },
                    onConfirm = { millis ->
                        val monday = com.hnnujw.course.schedule.ScheduleDates.mondayOfWeek(millis).timeInMillis
                        if (onSemesterStartChange != null) onSemesterStartChange(monday) else manager.semesterStartDate = monday
                        semesterStartDate = monday
                        showDatePicker = false
                    },
                    onDismiss = { showDatePicker = false }
                )
            }

            if (showPeriodCountPicker) {
                GlassOptionWheelDialog(
                    title = "每天节数",
                    options = (PeriodCountMin..PeriodCountMax).map { "$it 节" },
                    selectedIndex = (periodCount - PeriodCountMin)
                        .coerceIn(0, PeriodCountMax - PeriodCountMin),
                    onConfirm = { index ->
                        val count = PeriodCountMin + index
                        periodCount = count
                        manager.periodCount = count
                        showPeriodCountPicker = false
                    },
                    onDismiss = { showPeriodCountPicker = false }
                )
            }

            if (showFontScalePicker) {
                GlassOptionWheelDialog(
                    title = "课表字号",
                    options = ScheduleFontScaleOptions.map { it.second },
                    selectedIndex = scheduleFontScaleIndex(fontScale),
                    onConfirm = { index ->
                        val value = ScheduleFontScaleOptions[index.coerceIn(0, ScheduleFontScaleOptions.lastIndex)].first
                        fontScale = value
                        // 落 manager：revision++ → ScheduleScreen 的 remember key 变化 →
                        // CompositionLocal 下发新倍率，课表立即重排（不用重启）。
                        manager.scheduleFontScale = value
                        showFontScalePicker = false
                    },
                    onDismiss = { showFontScalePicker = false }
                )
            }

            editingPeriod?.let { target ->
                GlassTimeRangePickerDialog(
                    title = "第 ${target.period} 节",
                    initialStart = target.startTime.ifBlank { "08:00" },
                    initialEnd = target.endTime.ifBlank { "08:45" },
                    onConfirm = { start, end ->
                        // 落盘的是当前可见的完整列表（已按 periodCount 裁剪/补齐），
                        // 否则改第 13 节时会把补齐出来的行又丢掉。
                        val updated = periodTimes.map {
                            if (it.period == target.period) {
                                PeriodTime(it.period, start, end)
                            } else {
                                it
                            }
                        }
                        if (onPeriodTimesChange != null) onPeriodTimesChange(updated) else manager.savePeriodTimes(updated)
                        storedPeriodTimes = updated
                        editingPeriod = null
                    },
                    onDismiss = { editingPeriod = null }
                )
            }

            // 删除确认。放在同一个 CompositionLocalProvider 里，走本窗口的 DialogHost，
            // 否则弹窗会注册到主窗口、被设置页整页挡住（同上）。
            pendingDeleteId?.let { id ->
                val target = customCourses.firstOrNull { it.id == id }
                SystemConfirmDialog(
                    title = "删除课程",
                    text = if (target != null) {
                        "将删除「${target.name.ifBlank { "未命名课程" }}」（周${target.day} · " +
                            "${target.startPeriod}-${target.endPeriod} 节），课程与对应提醒会一起移除，此操作不可恢复。"
                    } else {
                        "将删除该课程及其提醒，此操作不可恢复。"
                    },
                    confirmText = "删除",
                    onConfirm = {
                        onDeleteCustomCourse?.invoke(id)
                        pendingDeleteId = null
                    },
                    onDismiss = { pendingDeleteId = null }
                )
            }
        }

    }
}

/**
 * 入场错峰：延迟 index*28ms 后淡入并上移到位。
 *
 * @param settled true 时直接渲染终态。入场结束后任何重组都不该再播一遍，
 *        这个开关就是为此存在的。
 */
@Composable
private fun StaggerIn(
    index: Int,
    settled: Boolean,
    content: @Composable () -> Unit
) {
    if (settled) {
        content()
        return
    }
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(index.coerceAtMost(8) * 28L)
        shown = true
    }
    val progress by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.9f, stiffness = 300f),
        label = "staggerIn"
    )
    Box(
        modifier = Modifier.graphicsLayer {
            alpha = progress
            translationY = (1f - progress) * 12.dp.toPx()
        }
    ) {
        content()
    }
}

/** 顶栏「完成」用不着一整个按钮组件。 */
