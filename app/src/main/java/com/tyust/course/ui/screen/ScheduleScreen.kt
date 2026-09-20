package com.tyust.course.ui.screen

import com.tyust.course.ui.theme.moduleEntrance

import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tyust.course.ui.system.GlassLoadingState
import com.tyust.course.ui.system.PagePadding
import com.tyust.course.ui.system.SystemCard
import com.tyust.course.ui.system.SystemDialog
import com.tyust.course.ui.system.SystemEmptyState
import com.tyust.course.ui.system.SystemPrimaryButton
import com.tyust.course.ui.system.SystemSecondaryButton
import com.tyust.course.ui.system.SystemStatusBadge
import com.tyust.course.ui.system.SystemTone
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.TextUnit
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.tyust.course.ui.system.GlassMaterialRole
import com.tyust.course.ui.system.HeaderGlassSlab
import com.tyust.course.ui.system.StatusBarFrost
import com.tyust.course.ui.system.lerpDp
import com.tyust.course.ui.system.rememberScreenMetrics
import com.tyust.course.ui.system.lerpSp
import com.tyust.course.ui.system.GlassMaterials
import com.tyust.course.ui.system.GlassRecipe
import com.tyust.course.ui.system.LocalAppBackdrop
import com.tyust.course.ui.system.LocalControlBackdrop
import com.tyust.course.ui.system.isBackdropSupported
import com.tyust.course.ui.system.glass.LiquidActionGroup
import com.tyust.course.ui.system.glass.glassRim
import com.tyust.course.ui.system.glass.resolvePhysicalLens
import com.tyust.course.ui.system.rememberGlassAccessibilityMode
import com.tyust.course.ui.system.reportNoticeAnchor

import com.tyust.course.ui.theme.MotionSpring
import com.tyust.course.ui.theme.NeuDivider
import com.tyust.course.ui.theme.NeuPrimary
import com.tyust.course.ui.theme.NeuSurface

import java.util.Calendar
import kotlin.math.roundToInt
import kotlin.math.ceil
import kotlinx.coroutines.launch

private val ScheduleTimeColumnWidth = 36.dp
private val ScheduleTimeColumnShadowWidth = 8.dp
private val SchedulePeriodHeight = 84.dp

/** 窄屏收窄的时间列与网格左右留白，把省下的宽度全给七个日列。 */
private val ScheduleTimeColumnWidthTight = 28.dp
private val SchedulePeriodHeightTight = 68.dp
private val ScheduleGridPaddingTight = 10.dp

// ── 顶栏折叠几何 ────────────────────────────────────────────────
// 展开态与折叠态的高度【差】必须等于折叠行程（travel）：
// 手指走 60dp -> 内容上移 60dp -> 顶栏下缘也上移 60dp，两者间距恒定。
// 行程和高度差一旦脱钩，网格顶端就会与收缩中的顶栏彼此追赶——所以 travel
// 定义成差值而不是另一个常量（与成绩页的 GradesHeaderMetrics 同一套写法）。
private val HeaderTopPadExpanded = 10.dp
private val HeaderTopPadCollapsed = 6.dp
/** 标题 + 日期行 + 芯片同一行：34(标题) + 6 + 日期行 ≈ 56，留一点余量。 */
private val HeaderActionRowExpanded = 58.dp
private val HeaderActionRowCollapsed = 48.dp
private val HeaderTitleGap = 6.dp
// 星期行必须同时装下「星期」+「日期」两行文字（见 CompactWeekdayLabel 的行高注释）：
// 两行字号一致，展开 34+6+34 = 74px = 37dp，折叠 30+4+30 = 64px = 32dp；各留一点余量，
// 免得字号/字体度量稍有出入又把日期挤到"一行都排不下"。
private val HeaderWeekRowExpanded = 40.dp
private val HeaderWeekRowCollapsed = 34.dp
private val HeaderBottomPadExpanded = 8.dp
private val HeaderBottomPadCollapsed = 4.dp

/**
 * 顶栏两态高度。**短屏只压展开态的空白**——折叠态与玻璃条几何一律不动，
 * 那几个值同时是胶囊圆角与折射行程的依据。
 */
private class ScheduleHeaderMetrics(
    val expanded: Dp,
    val collapsed: Dp,
    val topPadExpanded: Dp,
    val titleGap: Dp,
    val weekRowExpanded: Dp,
    val actionRowExpanded: Dp,
    val actionRowCollapsed: Dp,
    val stackedActions: Boolean
) {
    /** 折叠行程。定义成差值，于是不可能与两态高度脱钩。 */
    val travel: Dp get() = expanded - collapsed
}

@Composable
private fun rememberScheduleHeaderMetrics(availableWidth: Dp): ScheduleHeaderMetrics {
    val screen = rememberScreenMetrics()
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val titleStyle = MaterialTheme.typography.bodyLarge
    return remember(screen, availableWidth, density, measurer, titleStyle) {
        fun textSize(text: String, style: TextStyle) = measurer.measure(text, style, maxLines = 1)
        fun textWidth(text: String, style: TextStyle) = with(density) { textSize(text, style).size.width.toDp() }
        fun textHeight(text: String, style: TextStyle) = with(density) { textSize(text, style).size.height.toDp() }
        val topPad = screen.tall(HeaderTopPadExpanded, 6.dp)
        val titleGap = screen.tall(HeaderTitleGap, 4.dp)
        // 下限 38dp * fontScale：短屏分支原本会退到 30dp，正好矮于"星期+日期"两行所需，
        // 日期又会被挤没。字号随系统字体缩放，所以下限也要跟着缩放。
        // 这里算的是**预算**（按每行 24sp 倒推），实际行高在 Text 上显式写死后更矮，
        // 差额就是余量——千万别把这里的预算当成"正好够用"再去加行。
        val weekRow = maxOf(screen.tall(HeaderWeekRowExpanded, 38.dp), 38.dp * density.fontScale)
        val bottomPad = screen.tall(HeaderBottomPadExpanded, 4.dp)
        // Four 48dp targets keep their original row. Fit the text to the actual parent,
        // rather than stacking every device below an arbitrary screen-width breakpoint.
        val titleAvailable = (availableWidth - PagePadding * 2 - 204.dp - 8.dp).coerceAtLeast(0.dp)
        // 26sp 太宽：「第 25 周」连同右侧按钮会把标题挤到截断。缩到 21sp 后
        // 「第 N 周」能完整显示，且与下方日期行（13sp）仍有明显层级差。
        val expandedStyle = titleStyle.copy(fontSize = 21.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.sp)
        val collapsedStyle = expandedStyle.copy(fontSize = 15.sp)
        val dateStyle = titleStyle.copy(fontSize = 13.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.sp)
        // 只拿「第 N 周」判能否与按钮同行：（非本周）是随页变化的注解，
        // 把它算进最小宽度会把按钮挤到下一行，标题行反而更空。
        val titleMinimum = maxOf(
            textWidth("第 25 周", expandedStyle),
            textWidth("第 25 周", collapsedStyle)
        )
        val stacked = titleAvailable < titleMinimum
        val titleExpanded = textHeight("第 25 周", expandedStyle) + titleGap + textHeight("2026/9/19", dateStyle)
        val titleCollapsed = textHeight("第 25 周", collapsedStyle) + textHeight("2026/9/19", dateStyle)
        val actionExpanded = if (stacked) titleExpanded + 6.dp + 48.dp else maxOf(HeaderActionRowExpanded, titleExpanded)
        val actionCollapsed = if (stacked) titleCollapsed + 6.dp + 48.dp else maxOf(HeaderActionRowCollapsed, titleCollapsed)
        val collapsedWeekRow = maxOf(HeaderWeekRowCollapsed, 32.dp * density.fontScale)
        ScheduleHeaderMetrics(
            // 展开态：上留白 + 标题行 + 标题间距 + 周次行 + 下留白
            expanded = topPad + actionExpanded + titleGap + weekRow + bottomPad,
            collapsed = HeaderTopPadCollapsed + actionCollapsed + collapsedWeekRow + HeaderBottomPadCollapsed,
            topPadExpanded = topPad,
            titleGap = titleGap,
            weekRowExpanded = weekRow,
            actionRowExpanded = actionExpanded,
            actionRowCollapsed = actionCollapsed,
            stackedActions = stacked
        )
    }
}

/** 悬浮玻璃条相对屏幕边缘的内缩。前景内容不在条里，所以这个值不影响任何对齐。 */
private val HeaderSlabInset = 12.dp
/** 与状态栏磨砂之间留一道透明缝：网格清晰穿过，条的上缘才看得出折射。 */
private val HeaderSlabTopGap = 6.dp
private val HeaderSlabBottomGap = 4.dp
/**
 * 固定 29dp = 折叠态条高(58dp)的一半，于是折叠态正好是一枚胶囊。
 * 不用 percent=50：那样半可见的中途会是一枚很大的软药片，观感突兀。
 */
private val HeaderSlabCorner = 29.dp

/**
 * 网格的横向几何。**星期条与网格必须调用同一对函数**——星期标签靠
 * 「相同的左右留白 + 相同宽度的时间列占位」才和日期列对齐（见 `WeekHeaderCompact`
 * 里那条注释），两边取不同的值就会整体错位。
 */
@Composable
private fun scheduleGridPadding(): Dp =
    rememberScreenMetrics().wide(PagePadding, ScheduleGridPaddingTight)

@Composable
private fun scheduleTimeColumnWidth(): Dp =
    rememberScreenMetrics().wide(ScheduleTimeColumnWidth, ScheduleTimeColumnWidthTight)

/** 单节课的行高。短屏收到 68dp，一屏能多看一节多。 */
@Composable
private fun schedulePeriodHeight(): Dp =
    rememberScreenMetrics().tall(SchedulePeriodHeight, SchedulePeriodHeightTight)

data class ScheduleCourseUi(
    val name: String,
    val teacher: String,
    val location: String,
    val day: Int,
    val startPeriod: Int,
    val endPeriod: Int,
    val weeks: String,
    val color: Color,
    val isCustom: Boolean = false,
    val customId: String = "",
    val sourceId: String = "",
    val id: String = if (isCustom) "custom:$customId" else com.tyust.course.schedule.ScheduleIdentity.network(
        sourceId, name, teacher, day, startPeriod, endPeriod, weeks, location
    ),
    val hasConflict: Boolean = false,
    val isCurrent: Boolean = false
) {
    fun record() = com.tyust.course.schedule.ScheduleCourseRecord(id, name, teacher, location, day, startPeriod, endPeriod, weeks, isCustom)
}

data class PeriodTimeUi(val period: Int, val startTime: String, val endTime: String)

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ScheduleScreen(
    currentWeek: Int,
    courses: List<ScheduleCourseUi>,
    isLoading: Boolean,
    periodTimes: List<PeriodTimeUi> = emptyList(),
    periodCount: Int = 12,
    onWeekChange: (Int) -> Unit,
    onCourseClick: (ScheduleCourseUi) -> Unit,
    onSettingsClick: () -> Unit = {},
    onExportClick: () -> Unit = {},
    /** 点击周末（周六=6/周日=7）星期条触发补课入口；为空时周末条不可点。 */
    onMakeUpFromWeekday: (Int) -> Unit = {},
    errorMessage: String = "",
    onRetry: () -> Unit = {},
    firstWeekDate: String? = null,
    weekRequestKey: String? = null
) {
    // 课表字号：只给课表网格用（成绩 / 选课 / 二课不读这个值）。
    // key 带上 revision —— 设置页改完字号后 revision++，这里立刻拿到新值重组。
    val scheduleSettings = com.tyust.course.manager.ScheduleSettingsManager.getInstance()
    val scheduleFontScale = remember(scheduleSettings.revision) { scheduleSettings.scheduleFontScale }
    CompositionLocalProvider(LocalScheduleFontScale provides scheduleFontScale) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
    val coroutineScope = rememberCoroutineScope()
    val maxWeeks = com.tyust.course.schedule.ScheduleMaxWeeks
    val reducedMotion = com.tyust.course.ui.system.rememberGlassAccessibilityMode().reduceMotion
    val minuteClock by androidx.compose.runtime.produceState(System.currentTimeMillis()) {
        while (true) {
            kotlinx.coroutines.delay(60_000L - System.currentTimeMillis() % 60_000L)
            value = System.currentTimeMillis()
        }
    }
    // 「记忆第二遍」：把见过的最后一个有效第一周日期粘住。
    //
    // 上游（ScheduleRoute + ScheduleDates）已经有两层兜底，正常情况下传进来的
    // firstWeekDate 恒非空。但课表页是**跨重组、跨 pager 重建**的长命界面：
    // 恢复快照、切账号、学期接口重试的那一两帧里，上游可能还没把日期算出来。
    // 早先这一帧就是整行空白的小圆点 —— 用户看到的是"日期闪一下又没了"。
    //
    // 这里用 remember 记下**最后一次的非空值**，只要见过一次就再也不回退到空白。
    // 用 remember（而非 rememberSaveable）：进程重启后上游会重新兜底，无需跨进程保留。
    var stickyFirstWeekDate by remember { mutableStateOf(firstWeekDate?.takeIf { it.isNotBlank() }) }
    LaunchedEffect(firstWeekDate) {
        firstWeekDate?.takeIf { it.isNotBlank() }?.let { stickyFirstWeekDate = it }
    }
    val effectiveFirstWeekDate = firstWeekDate?.takeIf { it.isNotBlank() } ?: stickyFirstWeekDate
    val actualWeek = com.tyust.course.schedule.ScheduleDates.weekAt(effectiveFirstWeekDate, minuteClock)
    val conflictIds = remember(courses) {
        val records = courses.map { it.record() }
        records.filter { com.tyust.course.schedule.scheduleConflicts(it, records).isNotEmpty() }.map { it.id }.toSet()
    }
    val liveIds = remember(courses, periodTimes, minuteClock) {
        val clock = Calendar.getInstance().apply { timeInMillis = minuteClock }
        val today = (clock.get(Calendar.DAY_OF_WEEK) + 5) % 7 + 1
        val nowMinutes = clock.get(Calendar.HOUR_OF_DAY) * 60 + clock.get(Calendar.MINUTE)
        fun minutes(text: String?): Int? {
            val parts = text?.split(':')?.map { it.toIntOrNull() } ?: return null
            return if (parts.size == 2 && parts[0] != null && parts[1] != null) parts[0]!! * 60 + parts[1]!! else null
        }
        courses.filter { course ->
            val start = minutes(periodTimes.firstOrNull { it.period == course.startPeriod }?.startTime)
            val end = minutes(periodTimes.firstOrNull { it.period == course.endPeriod }?.endTime)
            course.day == today && start != null && end != null && nowMinutes in start until end
        }.map { it.id }.toSet()
    }
    val pagerState = rememberPagerState(
        initialPage = (currentWeek - 1).coerceIn(0, maxWeeks - 1),
        pageCount = { maxWeeks }
    )

    val latestWeekChange by rememberUpdatedState(onWeekChange)
    val latestRequestedWeek by rememberUpdatedState(currentWeek)
    val latestWeekRequestKey by rememberUpdatedState(weekRequestKey)
    val weekSync = remember(pagerState) {
        com.tyust.course.schedule.ScheduleWeekPagerSync(currentWeek, weekRequestKey)
    }

    var arrowJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var requestedPage by remember { mutableIntStateOf(pagerState.currentPage) }
    val userDragging by pagerState.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(userDragging) {
        if (userDragging) { arrowJob?.cancel(); requestedPage = pagerState.currentPage }
    }
    fun moveWeek(delta: Int) {
        requestedPage = ((if (arrowJob?.isActive == true) requestedPage else pagerState.currentPage) + delta).coerceIn(0, maxWeeks - 1)
        arrowJob?.cancel()
        arrowJob = coroutineScope.launch {
            if (reducedMotion) pagerState.scrollToPage(requestedPage)
            else pagerState.animateScrollToPage(requestedPage, animationSpec = com.tyust.course.ui.theme.MotionProfile.pagerSpring())
        }
    }

    LaunchedEffect(pagerState) {
        snapshotFlow {
            Triple(latestRequestedWeek to latestWeekRequestKey, pagerState.isScrollInProgress, pagerState.settledPage)
        }.collect { (request, scrolling, page) ->
            val targetPage = weekSync.requestPage(request.first, request.second)
            if (targetPage != null) {
                arrowJob?.cancel()
                pagerState.scrollToPage(targetPage)
                weekSync.settledWeek(targetPage)
            } else if (!scrolling) {
                weekSync.settledWeek(page)?.let(latestWeekChange)
            }
        }
    }

    // 所有 pager 页共用一个滚动位置：顶栏折叠进度要跟着它推导，
    // 而且左右切周时纵向位置不该跳回顶部。
    val gridScrollState = rememberScrollState()
    val statusBarHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val headerMetrics = rememberScheduleHeaderMetrics(maxWidth)
    // 折叠行程 = 顶栏高度差，于是收缩与滚动 1:1 对消，全程跟手。
    val travelPx = with(LocalDensity.current) { headerMetrics.travel.toPx() }
    val headerCollapse by remember(travelPx) {
        derivedStateOf { (gridScrollState.value / travelPx).coerceIn(0f, 1f) }
    }
    // 课表内容的捕获层。顶栏玻璃采样「壁纸 + 这一层」，于是网格从顶栏底下
    // 穿过时会被折射；顶栏本身不在这一层内，不构成自采样。
    val wallpaperBackdrop = LocalAppBackdrop.current
    val contentBackdrop = if (wallpaperBackdrop != null && isBackdropSupported()) {
        rememberLayerBackdrop()
    } else {
        null
    }
    val headerSampleBackdrop = if (wallpaperBackdrop != null && contentBackdrop != null) {
        rememberCombinedBackdrop(wallpaperBackdrop, contentBackdrop)
    } else {
        null
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            // topBar slot 只测量单个子项，两个兄弟节点会叠放并让通知条压到状态栏，
            // 因此顶栏与内联通知必须在同一个 Column 里纵向排布。
            Column(modifier = Modifier.moduleEntrance(0).reportNoticeAnchor()) {
                WeekHeaderCompact(
                    currentWeek = pagerState.currentPage + 1,
                    weekOffset = if (reducedMotion) 0f else pagerState.currentPageOffsetFraction,
                    firstWeekDate = effectiveFirstWeekDate,
                    actualWeek = actualWeek,
                    onPrevClick = {
                        moveWeek(-1)
                    },
                    onNextClick = {
                        moveWeek(1)
                    },
                    onSettingsClick = onSettingsClick,
                    onExportClick = onExportClick,
                    onMakeUpFromWeekday = onMakeUpFromWeekday,
                    collapseFraction = headerCollapse,
                    sampleBackdrop = headerSampleBackdrop
                )
            }
        }
    ) { paddingValues ->
        when {
            isLoading && courses.isEmpty() -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .moduleEntrance(1)
                        .padding(paddingValues),
                    contentAlignment = Alignment.Center
                ) {
                    GlassLoadingState(text = "正在同步课表…")
                }
            }

            errorMessage.isNotBlank() -> {
                Box(Modifier.fillMaxSize().moduleEntrance(1).padding(paddingValues).padding(24.dp), contentAlignment = Alignment.Center) {
                    com.tyust.course.ui.system.SystemEmptyState(title = "课表同步失败", message = errorMessage) {
                        com.tyust.course.ui.system.SystemSecondaryButton(text = "重新同步", onClick = onRetry)
                    }
                }
            }

            else -> {
                HorizontalPager(
                    state = pagerState,
                    flingBehavior = androidx.compose.foundation.pager.PagerDefaults.flingBehavior(
                        state = pagerState, snapAnimationSpec = com.tyust.course.ui.theme.MotionProfile.pagerSpring()),
                    modifier = Modifier
                        .testTag("schedule-pager")
                        .fillMaxSize()
                        .moduleEntrance(1)
                        // 内容捕获层挂在 pager 这个稳定节点上（不要挂进每一页）：
                        // 顶栏玻璃与芯片采样它，才能折射滚动中的网格与课程卡片。
                        .then(
                            if (contentBackdrop != null) {
                                Modifier.layerBackdrop(contentBackdrop)
                            } else {
                                Modifier
                            }
                        ),
                    beyondViewportPageCount = 0,
                    pageSpacing = 0.dp
                ) { page ->
                    val weekNumber = page + 1
                    // 「本节上哪门」：冲突时段的用户选择。一门课只要覆盖到任何一个
                    // 选了别人的节次，就整体让位隐藏（key 只到节，A 占 1-2、B 占 2-3
                    // 只在第 2 节撞车时，A 的第 1 节不受影响）。
                    val conflictChoices = com.tyust.course.schedule.ScheduleConflictStore.choices
                    val conflictHiddenIds = remember(courses, conflictChoices) {
                        if (conflictChoices.isEmpty()) {
                            emptySet()
                        } else {
                            courses.filter { course ->
                                (course.startPeriod..course.endPeriod).any { p ->
                                    conflictChoices["${course.day}:$p"]?.let { it != course.id } == true
                                }
                            }.map { it.id }.toSet()
                        }
                    }
                    val displayedCourses = remember(courses, conflictIds, liveIds, weekNumber, actualWeek, conflictHiddenIds) {
                        courses.filter { it.id !in conflictHiddenIds }.map { it.copy(hasConflict = it.id in conflictIds,
                            isCurrent = weekNumber == actualWeek && it.id in liveIds) }
                    }
                    Box(Modifier.fillMaxSize().graphicsLayer {
                        val offset = pagerState.currentPage + pagerState.currentPageOffsetFraction - page
                        scaleX = if (reducedMotion) 1f else com.tyust.course.ui.theme.SchedulePagerMotion.scale(offset)
                        scaleY = scaleX
                        alpha = if (reducedMotion) 1f else com.tyust.course.ui.theme.SchedulePagerMotion.alpha(offset)
                    }) {
                    ScheduleGrid(
                        courses = displayedCourses,
                        currentWeek = weekNumber,
                        periodTimes = periodTimes,
                        periodCount = periodCount,
                        onCourseClick = onCourseClick,
                        scrollState = gridScrollState,
                        // 【常量】而不是 paddingValues.calculateTopPadding()：后者随顶栏
                        // 一起收缩，而它施加在 verticalScroll 内部，于是顶栏每缩 1dp
                        // 内容就被额外上提 1dp——手指走 60dp、内容走 120dp。
                        topInset = statusBarHeight + headerMetrics.expanded
                    )
                    }
                }
            }
        }
    }
    }
    } // CompositionLocalProvider(LocalScheduleFontScale)
}

@Composable
fun WeekHeaderCompact(
    currentWeek: Int,
    onPrevClick: () -> Unit,
    onNextClick: () -> Unit,
    onSettingsClick: () -> Unit = {},
    onExportClick: () -> Unit = {},
    /** 点击周末（周六=6/周日=7）星期条触发补课入口；为空时周末条不可点。 */
    onMakeUpFromWeekday: (Int) -> Unit = {},
    /** 0=未滚动（大标题直接浮在课表上）、1=已上划（收拢成一条悬浮玻璃）。 */
    collapseFraction: Float = 0f,
    /** 「壁纸 + 课表内容」的合成采样源。为空则退回无玻璃顶栏。 */
    sampleBackdrop: Backdrop? = null,
    weekOffset: Float = 0f,
    firstWeekDate: String? = null,
    actualWeek: Int? = null
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
    val calendar = Calendar.getInstance()
    val headerFocus = remember { FocusRequester() }
    val focusRegistry = LocalScheduleFocus.current
    DisposableEffect(focusRegistry, headerFocus) {
        focusRegistry?.register("header", headerFocus)
        onDispose { focusRegistry?.remove("header", headerFocus) }
    }
    val currentDayOfWeek = (calendar.get(Calendar.DAY_OF_WEEK) + 5) % 7 + 1
    val weekLabels = listOf("一", "二", "三", "四", "五", "六", "日")
    // 「是否本周」只有一个判据：第一周日期已知，且当前页就是它所在的那一周。
    val isCurrentWeek = actualWeek != null && currentWeek == actualWeek
    // 标题旁的注解：只有翻到别的周才标注（非本周）；本周不再重复写「周X」。
    val weekSuffix = if (actualWeek != null && !isCurrentWeek) "(非本周)" else null
    val todayLabel = "${calendar.get(Calendar.YEAR)}/${calendar.get(Calendar.MONTH) + 1}/${calendar.get(Calendar.DAY_OF_MONTH)}"
    val monthLabel = com.tyust.course.schedule.ScheduleDates.date(firstWeekDate, currentWeek, 1)
        ?.let { "${it.get(Calendar.MONTH) + 1}月" }
    // 学期第一周周一日期没配置时，这一行的日期、月份会一起失效
    // （firstMonday 取不到起点，date() 全返回 null）。
    //
    // **现在这种情况基本不会发生了**：`ScheduleRoute` 的 displayedTimeBase 在用户
    // 没设过日期时用 `defaultFirstWeekDate()` 兜底（秋季 9/1、春季 3/1 所在周周一），
    // 所以 firstWeekDate 总有值。这个判据保留着作为最后一道保险——
    // 万一 termId 与日历都推不出来，至少还能给用户一个去设置的入口。
    val weekDatesAvailable = com.tyust.course.schedule.ScheduleDates.firstMonday(firstWeekDate) != null
    val statusBarHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    // CourseSelectorTheme 已根据系统模式与壁纸明暗选择配色，无需读取 GPU 像素。
    val titleColor = MaterialTheme.colorScheme.onSurface

    val collapse = collapseFraction.coerceIn(0f, 1f)

    // 顶栏玻璃把自己的渲染结果导出到这一层，供板上的芯片二次采样，
    // 于是芯片折射「壁纸 + 滚动网格 + 顶栏玻璃」三者的合成——玻璃叠玻璃。
    val headerBackdrop = rememberLayerBackdrop()
    val chipBackdrop = if (sampleBackdrop != null) {
        rememberCombinedBackdrop(sampleBackdrop, headerBackdrop)
    } else {
        null
    }
    // 与 ScheduleScreen 里那份是同一个纯函数结果，不会算出两套几何
    val headerMetrics = rememberScheduleHeaderMetrics(maxWidth)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            // 定高：这是"收起"的来源，也让悬浮条的几何是确定的
            .height(
                statusBarHeight +
                    lerpDp(headerMetrics.expanded, headerMetrics.collapsed, collapse)
            )
            .then(com.tyust.course.ui.system.wallpaperHeaderScrim())
    ) {
        // 玻璃层：【必须是前景内容的兄弟节点，不能是它的父节点】。
        // layerBackdrop 捕获所在节点的整棵子树——挂在包含按钮的父节点上，
        // 按钮就会采样一个含有自己的图层，RenderThread 死循环直接 native 崩溃。
        // 同款写法见 SystemUi.kt 的 SystemDialog。
        //
        // 节点常驻、不用 if(collapse>0) 摘掉：一摘掉这一层就没有内容可导出，
        // 采样它的芯片会拿到空图层。强度全部由 collapse 调制。
        if (sampleBackdrop != null) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .layerBackdrop(headerBackdrop)
            ) {
                StatusBarFrost(
                    height = statusBarHeight + 1.dp,
                    collapse = collapse,
                    backdrop = sampleBackdrop
                )
                HeaderGlassSlab(
                    // 比 collapse 晚起步：顶栏还高的时候它是一张大卡片，
                    // 提前显形会让人先看到"卡"再看到"条"。
                    strength = ((collapse - 0.35f) / 0.65f).coerceIn(0f, 1f),
                    backdrop = sampleBackdrop,
                    cornerRadius = HeaderSlabCorner,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            top = statusBarHeight + HeaderSlabTopGap,
                            start = HeaderSlabInset,
                            end = HeaderSlabInset,
                            bottom = HeaderSlabBottomGap
                        )
                        .fillMaxHeight()
                )
            }
        }

        CompositionLocalProvider(LocalControlBackdrop provides chipBackdrop) {
            // 前景横向几何刻意保持不变（padding = PagePadding）：星期条要和网格的
            // 日期列对齐，塞进内缩 12dp 的玻璃条里就得反向补偿，多一处会飘的耦合。
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(
                        top = lerpDp(headerMetrics.topPadExpanded, HeaderTopPadCollapsed, collapse)
                    )
            ) {
                ScheduleHeaderActionLayout(
                    stacked = headerMetrics.stackedActions,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(
                            lerpDp(headerMetrics.actionRowExpanded, headerMetrics.actionRowCollapsed, collapse)
                        )
                        .padding(horizontal = PagePadding)
                ) {
                    // 标题、日期与芯片同属一个纵列，芯片在右侧垂直居中。
                    Column(
                        modifier = Modifier.testTag("schedule-header-title"),
                        verticalArrangement = Arrangement.spacedBy(
                            lerpDp(headerMetrics.titleGap, 0.dp, collapse)
                        )
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "第 $currentWeek 周",
                                modifier = Modifier.focusRequester(headerFocus).focusable().graphicsLayer {
                                    alpha = (1f - kotlin.math.abs(weekOffset) * 2f).coerceIn(0f, 1f)
                                    translationX = -weekOffset * 12.dp.toPx()
                                },
                                fontSize = lerpSp(21f, 15f, collapse),
                                // 【必须显式写 lineHeight】。不写就继承 bodyLarge 的 24.sp
                                // （320dpi 上 48px），与 21sp/15sp 的字号毫无关系；而这一行的
                                // 高度是由 rememberScheduleHeaderMetrics 按"每行 24sp"倒推的，
                                // 两行加起来正好等于行高 —— 零余量。日期那行只要再多占 1px，
                                // Compose 就排不下一整行，于是节点在、像素全无（同星期条日期那个坑）。
                                lineHeight = lerpSp(26f, 19f, collapse),
                                fontWeight = FontWeight.Bold,
                                color = titleColor,
                                letterSpacing = 0.sp,
                                maxLines = 1
                            )
                        }

                        // 标题下的今天日期 + (非本周) 标注：展开 13sp、收拢 11sp，跟着标题一起收缩。
                        // (非本周) 刻意放在这一行而不是标题行 —— 标题行右侧紧邻动作按钮，
                        // 可用宽度被挤压时后缀会被裁成"(非本"（真机实测复现）；
                        // 日期行本身很短，拼在这里永远能完整显示。
                        Text(
                            text = if (weekSuffix != null) "$todayLabel  $weekSuffix" else todayLabel,
                            fontSize = lerpSp(13f, 11f, collapse),
                            // 同上：显式钉行高，别去继承 24.sp。13sp 的文字配 24sp 行高会让这一行
                            // 上下各空出 11px，既挤占标题行的余量，视觉上也把日期推得离标题太远。
                            lineHeight = lerpSp(17f, 15f, collapse),
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            letterSpacing = 0.sp,
                            maxLines = 1
                        )
                    }

                    com.tyust.course.ui.system.TopBarActionRail(Modifier.testTag("schedule-header-actions"), spacing = lerpDp(4.dp, 3.dp, collapse)) {
                        val buttonSize = lerpDp(34.dp, 30.dp, collapse)
                        val iconSize = lerpDp(16.dp, 15.dp, collapse)
                        action(
                            index = 0,
                            icon = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                            contentDescription = "上一周",
                            onClick = onPrevClick,
                            enabled = currentWeek > 1,
                            buttonSize = buttonSize,
                            iconSize = iconSize
                        )
                        action(
                            index = 1,
                            icon = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = "下一周",
                            onClick = onNextClick,
                            enabled = currentWeek < 25,
                            buttonSize = buttonSize,
                            iconSize = iconSize
                        )
                        action(
                            index = 2,
                            icon = Icons.Default.Share,
                            contentDescription = "导出",
                            onClick = onExportClick,
                            buttonSize = buttonSize,
                            iconSize = iconSize
                        )
                        action(
                            index = 3,
                            icon = Icons.Default.Settings,
                            contentDescription = "设置",
                            onClick = onSettingsClick,
                            buttonSize = buttonSize,
                            iconSize = iconSize
                        )
                    }
                }

                Spacer(Modifier.height(lerpDp(headerMetrics.titleGap, 0.dp, collapse)))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(
                            lerpDp(
                                headerMetrics.weekRowExpanded,
                                maxOf(HeaderWeekRowCollapsed, 26.dp * LocalDensity.current.fontScale),
                                collapse
                            )
                        )
                        .padding(horizontal = scheduleGridPadding()),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 时间列这一格放月份：七个日列只写「几号」，月份在这里出现一次。
                    Box(
                        modifier = Modifier.width(scheduleTimeColumnWidth() + ScheduleTimeColumnShadowWidth),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        if (monthLabel != null) {
                            Text(
                                text = monthLabel,
                                fontSize = lerpSp(10f, 9f, collapse),
                                // 星期条是固定高度：行高同样要显式写，理由同上面的标题行。
                                lineHeight = lerpSp(13f, 12f, collapse),
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                letterSpacing = 0.sp,
                                maxLines = 1,
                                modifier = Modifier.graphicsLayer {
                                    alpha = (1f - kotlin.math.abs(weekOffset) * 2f).coerceIn(0f, 1f)
                                }
                            )
                        } else if (!weekDatesAvailable) {
                            // 时间列显示「请设置 →」，比纯「设置」多一个动作提示。
                            // 字号也放大（原 10/9sp），折叠后仍可点。
                            Text(
                                text = "请设置 →",
                                fontSize = lerpSp(12f, 10f, collapse),
                                // 同上：固定高度的星期条里，行高一律显式写。
                                lineHeight = lerpSp(15f, 13f, collapse),
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary,
                                letterSpacing = 0.sp,
                                maxLines = 1,
                                modifier = Modifier
                                    .clickable { onSettingsClick() }
                                    .graphicsLayer {
                                        alpha = (1f - kotlin.math.abs(weekOffset) * 2f).coerceIn(0f, 1f)
                                    }
                            )
                        }
                    }
                    weekLabels.forEachIndexed { index, day ->
                        // 与参考图一致：本周高亮今天；翻到别的周时高亮该周第一天，
                        // 于是「当前在看哪一周」始终有一个锚点。
                        val isRealToday = isCurrentWeek && index + 1 == currentDayOfWeek
                        val highlighted = isRealToday || (actualWeek != null && !isCurrentWeek && index == 0)
                        // 用 weekdayDate（永不返回 null）：拿不到就按"本周周一 + 周次偏移"兜底，
                        // 星期条永远显示日期，不再退回占位小圆点。
                        val date = com.tyust.course.schedule.ScheduleDates.weekdayDate(
                            firstWeekDate, currentWeek, index + 1
                        )
                        // 点击「六/日」= 补课入口。补课弹窗要带**周次**：用户点的是
                        // 「第四周的周六」这一格，而不是笼统的"周六"，见 [MakeUpCourseDialog]。
                        val clickable = index >= 5 && onMakeUpFromWeekday != {}
                        CompactWeekdayLabel(
                            modifier = Modifier.weight(1f).testTag("schedule-weekday-${index + 1}")
                                .let { m -> if (clickable) m.clickable { onMakeUpFromWeekday(index + 1) } else m },
                            day = day,
                            isToday = highlighted,
                            collapse = collapse,
                            date = date.get(Calendar.DAY_OF_MONTH).toString(),
                            // date 恒非空（见 ScheduleDates.weekdayDate）。下面那个小圆点
                            // 分支理论上走不到了，保留它只是防止将来有人改坏上游。
                            semanticLabel = "今天".takeIf { isRealToday },
                            weekOffset = weekOffset
                        )
                    }
                }
            }
        }
    }
    }
}

@Composable
private fun ScheduleHeaderActionLayout(stacked: Boolean, modifier: Modifier, content: @Composable () -> Unit) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val actions = measurables[1].measure(loose.copy(maxHeight = 48.dp.roundToPx()))
        val gap = if (stacked) 6.dp.roundToPx() else 8.dp.roundToPx()
        val title = measurables[0].measure(loose.copy(
            maxWidth = if (stacked) constraints.maxWidth else (constraints.maxWidth - actions.width - gap).coerceAtLeast(0),
            maxHeight = if (stacked) (constraints.maxHeight - actions.height - gap).coerceAtLeast(0) else constraints.maxHeight
        ))
        layout(constraints.maxWidth, constraints.maxHeight) {
            if (stacked) {
                title.placeRelative(0, 0)
                actions.placeRelative(constraints.maxWidth - actions.width, constraints.maxHeight - actions.height)
            } else {
                title.placeRelative(0, (constraints.maxHeight - title.height) / 2)
                actions.placeRelative(constraints.maxWidth - actions.width, (constraints.maxHeight - actions.height) / 2)
            }
        }
    }
}

@Composable
private fun CompactWeekdayLabel(
    modifier: Modifier = Modifier,
    day: String,
    isToday: Boolean,
    collapse: Float = 0f,
    date: String? = null,
    /** 无障碍播报，只有真正的「今天」才有；浏览其它周时的高亮只是锚点。 */
    semanticLabel: String? = null,
    weekOffset: Float = 0f
) {
    val textColor by animateColorAsState(
        targetValue = if (isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        label = "weekdayColor"
    )
    val dotScale by animateFloatAsState(
        targetValue = if (isToday) 1f else 0.65f,
        animationSpec = MotionSpring.gentle(),
        label = "weekdayDotScale"
    )
    // 折叠后只留今天那一点——"只留必要信息"落到最小的一处。
    // 非今天也必须用看得见的颜色：学期起始日没配置时，这个点是该行唯一的占位，
    // 沿用分隔线色（alpha 极低）会让整行看起来是空的。
    val dotColor = if (isToday) {
        NeuPrimary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f * (1f - collapse))
    }

    // 【两个 Text 都必须显式写 lineHeight】。
    //
    // 不写时它们继承 MaterialTheme.typography.bodyLarge 的 lineHeight = 24.sp（320dpi 上 = 48px），
    // 与 13sp / 11sp 的字号完全无关。星期行的固定高度只有 34dp（68px），
    // 星期那行先吃掉 48px + 6px 间距，剩下 14px 给日期 —— 比一行的行高还矮，
    // Compose 的排版结果是"一行都排不下"，于是**节点照常测量、文字一个像素都不画**。
    // 表现就是：UI 层级里能看到 "14" "15"… 这些节点，屏幕上却什么都没有。
    //
    // 紧凑行高后：星期 17sp(34px) + 间距 3dp(6px) + 日期 14sp(28px) = 68px，正好装进行高，
    // 折叠态 15sp(30px) + 2dp(4px) + 13sp(26px) = 60px（HeaderWeekRowCollapsed = 32dp = 64px）。
    Column(
        modifier = modifier
            .semantics(mergeDescendants = true) {
                semanticLabel?.let { stateDescription = it }
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(lerpDp(3.dp, 2.dp, collapse))
    ) {
        Text(
            text = day,
            fontSize = lerpSp(13f, 11.5f, collapse),
            lineHeight = lerpSp(17f, 15f, collapse),
            fontWeight = if (isToday) FontWeight.SemiBold else FontWeight.Medium,
            color = textColor,
            maxLines = 1
        )
        if (date != null) {
            val dateColor = if (isToday) textColor else NeuPrimary
            // alpha 下限 0.35f：原来 coerceIn(0f, 1f) 在切周过程中（|weekOffset| >= 0.5）
            // 会把日期整行抹成全透明，节点还在、像素没了 —— 用户看到的就是"日期不显示"。
            val dateAlpha = (1f - kotlin.math.abs(weekOffset) * 2f).coerceIn(0.35f, 1f)
            // 字号与上方的星期文字完全一致：日期是同一条星期条里的信息，
            // 比星期小一号会显得像次要标注，用户扫一眼容易漏掉。
            Text(
                text = date,
                fontSize = lerpSp(13f, 11.5f, collapse),
                lineHeight = lerpSp(17f, 15f, collapse),
                fontWeight = FontWeight.SemiBold,
                color = dateColor,
                maxLines = 1,
                modifier = Modifier.graphicsLayer { alpha = dateAlpha }
            )
        }
        else Box(
            modifier = Modifier
                .scale(dotScale)
                .size(lerpDp(5.dp, 4.dp, collapse))
                .background(color = dotColor, shape = CircleShape)
        )
    }
}

/**
 * 补课入口：点击课表顶部的「六 / 日」星期条弹出。
 *
 * ## 语义（这一版按用户的实际心智重写）
 *
 * 用户在**某一周的周六/周日那一格**上点击，意味着"我要给这一周的这一天上点课"。
 * 所以弹窗里选的是「**补第几周、星期几的课**」——选中的那一天、那一周的**每一门课**
 * 都会按各自的原时段落到用户点的那一格：
 *
 * ```
 * 在第 4 周的周六格上点击 → 弹窗里选「第 3 周 · 星期三」
 *                        → 第 3 周星期三的 3 门课渲染到「第 4 周 · 周六」的网格里
 * ```
 *
 * 因此：
 * - **不需要挑具体课程**（用户明确要求）——整天的课一起补，符合"这一天被调过来了"的现实；
 * - 周次与星期都用**折叠选择器**（收起时只显示当前选择，点开才铺出可选项），
 *   而不是旧版那种一屏铺满的加减按钮 + 方块阵；
 * - 目标格子由调用方（`makeUpDay` + 当前周次）决定，这里只负责"源是哪一周哪一天"。
 *
 * 视觉材质交给 [SystemDialog] —— 主站登录页那套液态玻璃模态（blur → lens → vibrancy）。
 */
@Composable
fun MakeUpCourseDialog(
    /** 用户点击的目标星期：6 = 周六，7 = 周日。 */
    weekendDay: Int,
    /** 用户点击时正在浏览的周次，即课程要**落进去**的那一周。 */
    targetWeek: Int,
    courses: List<ScheduleCourseUi>,
    firstWeekDate: String? = null,
    onDismiss: () -> Unit,
    /** 确认补课：([源星期 1..7], [源周次])。由调用方搬到 [targetWeek] 的 [weekendDay]。 */
    onConfirm: (weekday: Int, week: Int) -> Unit
) {
    val maxWeeks = com.tyust.course.schedule.ScheduleMaxWeeks
    val weekdayLabels = "一二三四五六日"
    // 源默认取同一天（周六点进来默认补周六的课），这是最常见的一种调休
    var weekday by remember { mutableIntStateOf(weekendDay.coerceIn(1, 7)) }
    var week by remember { mutableIntStateOf(targetWeek.coerceIn(1, maxWeeks)) }
    // 两个面板同一时刻只展开一个：都摊开时弹窗会顶到屏幕上下缘
    var expanded by remember { mutableStateOf(FoldPanel.None) }

    val targetName = if (weekendDay == 7) "周日" else "周六"
    val sourceLabel = "星期${weekdayLabels.getOrElse(weekday - 1) { '?' }}"
    // 目标格子在课表上的完整坐标，用于标题与结果预览（"补到第 4 周周六"）
    val targetDate = com.tyust.course.schedule.ScheduleDates.date(firstWeekDate, targetWeek, weekendDay)
        ?.let { "${it.get(Calendar.MONTH) + 1}/${it.get(Calendar.DAY_OF_MONTH)}" }
    val targetLabel = "第 $targetWeek 周$targetName"
    // 预览用：该周该天到底有几门课会搬过来。0 的时候确认按钮直接禁用，
    // 免得用户点完才发现"这天没课"。
    val affected = remember(courses, weekday, week) {
        courses.count { !it.isCustom && it.day == weekday && isInWeek(it.weeks, week) }
    }

    // 折叠面板开着时才渲染滚轮。滚轮的初始项由它自己的 remember 决定，
    // 收起再展开会重新从"当前选中值"起步 —— 这正是想要的语义。
    if (expanded == FoldPanel.Week) {
        com.tyust.course.ui.system.GlassOptionWheelDialog(
            title = "补第几周",
            options = (1..maxWeeks).map { "第 $it 周" },
            selectedIndex = (week - 1).coerceIn(0, maxWeeks - 1),
            onConfirm = { index ->
                week = index + 1
                expanded = FoldPanel.None
            },
            onDismiss = { expanded = FoldPanel.None }
        )
    }
    if (expanded == FoldPanel.Weekday) {
        com.tyust.course.ui.system.GlassOptionWheelDialog(
            title = "补星期几",
            options = weekdayLabels.map { "星期$it" },
            selectedIndex = (weekday - 1).coerceIn(0, 6),
            onConfirm = { index ->
                weekday = index + 1
                expanded = FoldPanel.None
            },
            onDismiss = { expanded = FoldPanel.None }
        )
    }

    SystemDialog(
        onDismissRequest = onDismiss,
        title = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "补课到$targetLabel",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = if (targetDate != null) "落在 $targetDate 这一格 · 选要补哪一周的哪一天"
                    else "选要补哪一周的哪一天，整天的课一起搬过来",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        },
        confirmButton = {
            SystemPrimaryButton(
                text = if (affected > 0) "补到$targetLabel（$affected 门）" else "该周这天没有课",
                onClick = { onConfirm(weekday, week) },
                enabled = affected > 0,
                modifier = Modifier.fillMaxWidth()
            )
        },
        dismissButton = {
            SystemSecondaryButton(
                text = "取消",
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth()
            )
        }
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 两项都点开滚轮（见上面两个 GlassOptionWheelDialog）。
            // 原来这里是点击式网格：选"第几周"要在一堆方格里找，选"星期几"要在
            // 七个格子里瞄。换成滑轮后与课表设置里的日期/时间选择是同一套交互。
            FoldSetting(
                label = "补第几周",
                value = "第 $week 周",
                expanded = expanded == FoldPanel.Week,
                onToggle = {
                    expanded = if (expanded == FoldPanel.Week) FoldPanel.None else FoldPanel.Week
                },
                summary = if (expanded != FoldPanel.Week &&
                    week != targetWeek.coerceIn(1, maxWeeks)
                ) "补本周" to { week = targetWeek.coerceIn(1, maxWeeks) } else null
            )
            FoldSetting(
                label = "补星期几",
                value = sourceLabel,
                expanded = expanded == FoldPanel.Weekday,
                onToggle = {
                    expanded = if (expanded == FoldPanel.Weekday) FoldPanel.None else FoldPanel.Weekday
                }
            )

            // 结果预览：说清"会发生什么"，而不是让用户自己推
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = if (affected > 0) Icons.Filled.CheckCircle
                    else Icons.Filled.Warning,
                    contentDescription = null,
                    tint = if (affected > 0) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = if (affected > 0) {
                        "第 $week 周 · $sourceLabel 的 $affected 门课 → 第 $targetWeek 周$targetName"
                    } else {
                        "第 $week 周 · $sourceLabel 没有课，换一天或换一周试试"
                    },
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    lineHeight = 17.sp
                )
            }
        }
    }
}

/** 折叠面板的互斥状态。 */
private enum class FoldPanel { None, Week, Weekday }

/**
 * 选择器外壳：一行"标签 + 当前值 + 箭头"，点一下打开滚轮弹窗。
 *
 * ## 为什么不再有 [content] 槽位
 *
 * 原先它内联一个 `AnimatedVisibility` 展开区，把 [OptionGrid] 塞在弹窗里展开。
 * 弹窗内展开会把整个弹窗顶高（叠两张网格时几乎顶满屏幕），而且那是**点击式**
 * 交互 —— 用户明确要求选项一律用滑轮。现在展开状态 [expanded] 只用来决定
 * "要不要开滚轮弹窗"，壳子本身不再承载任何内容。
 *
 * @param expanded 对应的滚轮弹窗是否正在显示（决定箭头朝向）。
 */
@Composable
private fun FoldSetting(
    label: String,
    value: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    summary: Pair<String, () -> Unit>? = null
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                .clickable(onClick = onToggle)
                .padding(start = 14.dp, end = 10.dp, top = 11.dp, bottom = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = value,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            if (summary != null) {
                Text(
                    text = summary.first,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = NeuPrimary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = summary.second)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
            Icon(
                imageVector = Icons.Filled.KeyboardArrowDown,
                contentDescription = if (expanded) "收起" else "展开",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
fun ScheduleGrid(
    courses: List<ScheduleCourseUi>,
    currentWeek: Int,
    periodTimes: List<PeriodTimeUi> = emptyList(),
    periodCount: Int = 12,
    onCourseClick: (ScheduleCourseUi) -> Unit,
    /**
     * scrollState 由调用方持有：顶栏的玻璃浓度要跟着它推导，而且所有 pager 页
     * 共用一个，左右切周时纵向位置不会跳回顶部。
     */
    scrollState: ScrollState = rememberScrollState(),
    /**
     * 顶栏高度。**施加在 verticalScroll 内部**——容器保持全出血，于是滚动位置 0
     * 时内容起始于顶栏之下，滚起来则从顶栏底下穿过，顶栏芯片才有东西可折射。
     * 加在容器上（`Modifier.padding(paddingValues)`）就成了"内容被推到顶栏下方"，
     * 芯片背后永远是空的。
     */
    topInset: Dp = 0.dp
) {
    // 【课表字号】把渲染密度的 fontScale 乘上课表专属倍率后再下发给网格：
    // dp 几何（列宽、行高、留白）一个像素都不动，只有 sp 换算出的字号变大变小。
    // 必须包在 BoxWithConstraints 外面 —— rememberCoursePeriodHeight 就在里面，
    // 它要拿同一份缩放后的密度去实测课程卡片文字，行高才会跟着文字长高。
    val baseDensity = LocalDensity.current
    val scheduleFontScale = LocalScheduleFontScale.current
    val scaledDensity = remember(baseDensity, scheduleFontScale) {
        Density(baseDensity.density, baseDensity.fontScale * scheduleFontScale)
    }
    CompositionLocalProvider(LocalDensity provides scaledDensity) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
    val weeklyCourses = remember(courses, currentWeek) {
        courses.filter { isInWeek(it.weeks, currentWeek) }
    }
    val timeColumnWidth = scheduleTimeColumnWidth()
    val gridPadding = scheduleGridPadding()
    val dayColumnWidthPx = with(LocalDensity.current) {
        ((constraints.maxWidth - gridPadding.roundToPx() * 2 - timeColumnWidth.roundToPx() -
            ScheduleTimeColumnShadowWidth.roundToPx()) / 7f).roundToInt()
    }
    val periodHeight = rememberCoursePeriodHeight(courses, dayColumnWidthPx, schedulePeriodHeight())
    val totalHeight = periodHeight * periodCount
    val darkGrid = com.tyust.course.ui.system.rememberGlassDarkTheme()
    val gridTint = MaterialTheme.colorScheme.surface.copy(alpha =
        if (darkGrid || com.tyust.course.manager.AppearanceSettingsManager.mode != com.tyust.course.manager.WallpaperMode.Preset) 0.86f else 0.18f)
    val timeColumnTint = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (darkGrid) 0.4f else 0.28f)

    Column(
        modifier = Modifier
            .testTag("schedule-grid-$currentWeek")
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(
                start = gridPadding,
                end = gridPadding,
                top = topInset + 8.dp,
                bottom = com.tyust.course.ui.system.LocalAppOverlayBottomInset.current + 24.dp
            ),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 去卡片化：轻玻璃衬底压到很低，让壁纸渐变与网格线透上来。
        // 这个 alpha 是"顶栏芯片能不能看出折射"的直接开关——衬底一厚，
        // 白芯片压在白板上，折射再准也是白压白。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(gridTint)
                .border(
                    0.5.dp,
                    Color.White.copy(alpha = 0.34f),
                    RoundedCornerShape(24.dp)
                )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(totalHeight)
            ) {
                Column(
                    modifier = Modifier
                        .width(timeColumnWidth)
                        .fillMaxHeight()
                        .background(timeColumnTint),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    for (i in 1..periodCount) {
                        val periodTime = periodTimes.find { it.period == i }
                        Box(
                            modifier = Modifier
                                .height(periodHeight)
                                .fillMaxWidth(),
                            contentAlignment = Alignment.TopCenter
                        ) {
                            Column(
                                modifier = Modifier.padding(top = 6.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(1.dp)
                            ) {
                                Text(
                                    text = i.toString(),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontWeight = FontWeight.SemiBold
                                )
                                if (periodTime != null) {
                                    Text(
                                        text = periodTime.startTime,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 9.sp,
                                        maxLines = 1
                                    )
                                    Text(
                                        text = periodTime.endTime,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 9.sp,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }
                }

                // 时间列右侧阴影，柔和过渡
                Box(
                    modifier = Modifier
                        .width(ScheduleTimeColumnShadowWidth)
                        .fillMaxHeight()
                        .background(
                            Brush.horizontalGradient(
                                colors = listOf(
                                    NeuDivider.copy(alpha = 0.10f),
                                    Color.Transparent
                                )
                            )
                        )
                )

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                ) {
                    TimetableBackground(
                        periodCount = periodCount,
                        periodHeight = periodHeight
                    )
                    TimetableLayout(
                        courses = weeklyCourses,
                        periodCount = periodCount,
                        periodHeight = periodHeight,
                        onCourseClick = onCourseClick
                    )

                    if (weeklyCourses.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            SystemEmptyState(
                                title = "本周暂无课程",
                                message = "可以切换周次、学期，或在设置中管理自定义课程。",
                                modifier = Modifier.padding(24.dp)
                            )
                        }
                    }
                }
            }
        }

        if (weeklyCourses.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SystemStatusBadge(
                    text = "本周 ${weeklyCourses.size} 门课程",
                    tone = SystemTone.Info
                )
                if (weeklyCourses.any { it.isCustom }) {
                    SystemStatusBadge(
                        text = "含自定义课程",
                        tone = SystemTone.Warning
                    )
                }
            }
        }
    }
    }
    } // CompositionLocalProvider(LocalDensity = 课表字号)
}

private fun courseNameStyle(base: TextStyle, duration: Int): TextStyle = base.copy(
    fontWeight = FontWeight.Bold,
    fontSize = when (duration) { 1 -> 10.5.sp; 2 -> 11.sp; else -> 11.5.sp },
    lineHeight = when (duration) { 1 -> 12.sp; 2 -> 12.5.sp; else -> 13.sp },
    letterSpacing = (-0.2).sp
)

private fun courseLocationStyle(base: TextStyle, duration: Int): TextStyle = base.copy(
    fontWeight = FontWeight.Normal,
    fontSize = if (duration <= 2) 9.sp else 9.5.sp,
    lineHeight = if (duration <= 2) 10.5.sp else 11.sp
)

private fun ScheduleCourseUi.hasCardStatus() = hasConflict || isCurrent || isCustom ||
    !com.tyust.course.schedule.ScheduleWeeks.parse(weeks).valid

/** The same measured row height drives the time rail, grid lines and course spans. */
@Composable
private fun rememberCoursePeriodHeight(courses: List<ScheduleCourseUi>, columnWidthPx: Int, minimum: Dp): Dp {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer(cacheSize = 128)
    val style = MaterialTheme.typography.labelSmall
    return remember(courses, columnWidthPx, minimum, density, measurer, style) {
        with(density) {
            val contentWidth = (columnWidthPx - 2 * 1.dp.roundToPx() - 5.dp.roundToPx() - 2.dp.roundToPx()).coerceAtLeast(1)
            var rowHeight = minimum.toPx()
            for (course in courses) {
                val duration = (course.endPeriod - course.startPeriod + 1).coerceAtLeast(1)
                val name = measurer.measure(course.name, courseNameStyle(style, duration),
                    maxLines = 4, overflow = TextOverflow.Ellipsis, constraints = Constraints(maxWidth = contentWidth))
                val hasStatus = course.hasCardStatus()
                val locationHeight = if (course.location.isNotBlank()) measurer.measure(
                    course.location, courseLocationStyle(style, duration), constraints = Constraints(maxWidth = contentWidth)
                ).size.height else 0
                val informationHeight = locationHeight + if (hasStatus) 11.dp.roundToPx() else 0
                // Includes card insets, content padding, inter-line gap and rounding slack.
                val required = name.size.height + informationHeight + 9.dp.toPx()
                rowHeight = maxOf(rowHeight, ceil(required / duration))
            }
            ceil(rowHeight).toDp()
        }
    }
}

@Composable
private fun TimetableBackground(
    periodCount: Int,
    periodHeight: Dp
) {
    Box(modifier = Modifier.fillMaxSize()) {
        // 去斑马纹：透明列 + 极淡分隔线，壁纸从网格间透出
        Row(modifier = Modifier.fillMaxSize()) {
            repeat(7) { dayIndex ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                ) {
                    if (dayIndex < 6) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .fillMaxHeight()
                                .width(1.dp)
                                .background(NeuDivider.copy(alpha = 0.18f))
                        )
                    }
                }
            }
        }

        Column(modifier = Modifier.fillMaxSize()) {
            repeat(periodCount) { rowIndex ->
                Box(
                    modifier = Modifier
                        .height(periodHeight)
                        .fillMaxWidth()
                ) {
                    if (rowIndex < periodCount - 1) {
                        HorizontalDivider(
                            modifier = Modifier.align(Alignment.BottomCenter),
                            color = NeuDivider.copy(alpha = 0.15f),
                            thickness = 0.5.dp
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun TimetableLayout(
    courses: List<ScheduleCourseUi>,
    periodCount: Int = 12,
    periodHeight: Dp = SchedulePeriodHeight,
    modifier: Modifier = Modifier,
    onCourseClick: (ScheduleCourseUi) -> Unit
) {
    Layout(
        modifier = modifier.fillMaxSize(),
        content = {
            courses.forEach { course ->
                androidx.compose.runtime.key(course.id) { CourseCard(course = course, onClick = { onCourseClick(course) }) }
            }
        }
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val columnWidth = width / 7f
        val cardInset = 1.dp.roundToPx()
        val pxPerPeriod = periodHeight.toPx()

        val placeables = measurables.mapIndexed { index, measurable ->
            val course = courses[index]
            val duration = course.endPeriod - course.startPeriod + 1
            val height = (duration * pxPerPeriod).roundToInt()
            val cardWidth = (columnWidth.roundToInt() - cardInset * 2).coerceAtLeast(1)
            val cardHeight = (height - cardInset * 2).coerceAtLeast(1)

            measurable.measure(
                constraints.copy(
                    minWidth = cardWidth,
                    maxWidth = cardWidth,
                    minHeight = cardHeight,
                    maxHeight = cardHeight
                )
            )
        }

        layout(width, (periodCount * pxPerPeriod).roundToInt()) {
            placeables.forEachIndexed { index, placeable ->
                val course = courses[index]
                val dayIndex = (course.day - 1).coerceIn(0, 6)
                val startPeriodIndex = (course.startPeriod - 1).coerceIn(0, periodCount - 1)

                val x = (dayIndex * columnWidth).roundToInt() + cardInset
                val y = (startPeriodIndex * pxPerPeriod).roundToInt() + cardInset

                placeable.place(x, y)
            }
        }
    }
}

@Composable
fun CourseCard(course: ScheduleCourseUi, onClick: () -> Unit) {
    val darkCard = com.tyust.course.ui.system.rememberGlassDarkTheme()
    val focusRequester = remember { FocusRequester() }
    val focusRegistry = LocalScheduleFocus.current
    var cardBounds by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    val unknownWeeks = remember(course.weeks) { !com.tyust.course.schedule.ScheduleWeeks.parse(course.weeks).valid }
    DisposableEffect(course.id, focusRegistry) {
        focusRegistry?.register(course.id, focusRequester)
        onDispose { focusRegistry?.remove(course.id, focusRequester) }
    }
    val duration = (course.endPeriod - course.startPeriod + 1).coerceAtLeast(1)
    // 彩色半透玻璃 tile：加深填充保证壁纸上可读，边缘细亮线似透镜
    val containerColor = androidx.compose.ui.graphics.lerp(MaterialTheme.colorScheme.surface, course.color, if (course.isCustom) 0.30f else 0.22f)
    val borderColor = course.color.copy(alpha = 0.50f)
    val accentColor = course.color.copy(alpha = 0.85f)

    val displayLocation = course.location

    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed && !com.tyust.course.ui.system.rememberGlassAccessibilityMode().reduceMotion) 0.97f else 1f,
        animationSpec = com.tyust.course.ui.theme.MotionProfile.iconSpring(),
        label = "courseCardScale"
    )

    Surface(
        modifier = Modifier
            .testTag("schedule-course-${course.id}")
            .focusRequester(focusRequester)
            .onGloballyPositioned {
                cardBounds = it.boundsInWindow()
                focusRegistry?.place(course.id, it.boundsInWindow())
            }
            .semantics {
                stateDescription = listOfNotNull(if (course.hasConflict) "时间冲突" else null,
                    if (course.isCurrent) "正在上课" else null,
                    // 补课生成的课程也是"自定义"，但对用户来说是"补课"，播报也该这么说
                    if (course.isCustom) {
                        if (course.day == 6 || course.day == 7) "补课" else "自定义课程"
                    } else null,
                    if (unknownWeeks) "周次待核对" else null).joinToString("，")
            }
            .scale(scale)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = {
                    // A course can also exist in the pager's adjacent week. The clicked copy
                    // owns the opening origin and the focus returned after dismissal.
                    focusRegistry?.register(course.id, focusRequester)
                    cardBounds?.let { focusRegistry?.place(course.id, it) }
                    onClick()
                }
            ),
        shape = RoundedCornerShape(12.dp),
        color = containerColor,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(0.6.dp, borderColor)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // 顶部玻璃高光渐变：模拟光源照射的反射
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(with(LocalDensity.current) { (duration * 20).dp })
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.White.copy(alpha = if (darkCard) 0.08f else 0.35f),
                                Color.White.copy(alpha = if (darkCard) 0.02f else 0.05f),
                                Color.Transparent
                            )
                        )
                    )
            )
            // 左侧彩色指示条：课程颜色标识
            Box(
                modifier = Modifier
                    .width((3f + (1f - scale) * 50f).dp)
                    .fillMaxHeight()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                accentColor,
                                accentColor.copy(alpha = 0.3f)
                            )
                        )
                    )
            )
            // 内容区域
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = 5.dp, end = 2.dp, top = 2.dp, bottom = 2.dp),
                verticalArrangement = Arrangement.spacedBy(1.dp)
            ) {
                if (course.day == 6 || course.day == 7) Text(
                    text = "补课",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 1.dp)
                )
                Text(
                    text = course.name,
                    style = courseNameStyle(MaterialTheme.typography.labelSmall, duration),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )

                if (displayLocation.isNotBlank()) {
                    Text(
                        text = displayLocation,
                        modifier = Modifier.fillMaxWidth().testTag("schedule-location-${course.id}"),
                        style = courseLocationStyle(MaterialTheme.typography.labelSmall, duration),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        softWrap = true
                    )
                }
                // A status symbol must not reserve a column beside every line of a long address.
                //
                // **补课课程不画图标**。补课生成的自定义课程落在周六/周日（`day in 6..7`），
                // 它们和普通课一样是"要上的课"，再挂一支铅笔只会让人以为可以点进去编辑，
                // 而且"补课"两个字已经写在卡片顶部了。
                // 手动添加的自定义课程仍然保留铅笔 —— 那才是需要提示"这条可以改"的情况。
                val isMakeUpCourse = course.isCustom && (course.day == 6 || course.day == 7)
                val showStatusIcon = course.hasConflict || course.isCurrent ||
                    (course.isCustom && !isMakeUpCourse) || unknownWeeks
                if (showStatusIcon) Icon(
                        imageVector = when { course.hasConflict || unknownWeeks -> Icons.Default.Warning
                            course.isCurrent -> Icons.Default.PlayArrow
                            else -> Icons.Default.Edit },
                        contentDescription = null,
                        modifier = Modifier.size(10.dp),
                        tint = if (course.hasConflict || unknownWeeks) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                    )
            }
        }
    }
}

internal fun isInWeek(weeks: String?, week: Int): Boolean {
    return com.tyust.course.schedule.ScheduleWeeks.parse(weeks).visibleIn(week)
}
