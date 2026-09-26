package com.hnnujw.course.ui.screen

import com.hnnujw.course.ui.theme.moduleEntrance

import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Refresh
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
import androidx.compose.runtime.key
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.hnnujw.course.ui.system.AnimatedIconSpec
import com.hnnujw.course.ui.system.AnimatedLineIcon
import com.hnnujw.course.ui.system.GlassLoadingState
import com.hnnujw.course.ui.system.LiquidButton
import com.hnnujw.course.ui.system.LiquidSegmentedControl
import com.hnnujw.course.ui.system.PagePadding
import com.hnnujw.course.ui.system.SystemActionMenu
import com.hnnujw.course.ui.system.SystemMenuAction
import com.hnnujw.course.ui.system.SystemCard
import com.hnnujw.course.ui.system.SystemDialog
import com.hnnujw.course.ui.system.SystemEmptyState
import com.hnnujw.course.ui.system.SystemPrimaryButton
import com.hnnujw.course.ui.system.SystemSecondaryButton
import com.hnnujw.course.ui.system.SystemStatusBadge
import com.hnnujw.course.ui.system.SystemTone
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
import com.hnnujw.course.ui.system.GlassMaterialRole
import com.hnnujw.course.ui.system.HeaderGlassSlab
import com.hnnujw.course.ui.system.StatusBarFrost
import com.hnnujw.course.ui.system.lerpDp
import com.hnnujw.course.ui.system.rememberScreenMetrics
import com.hnnujw.course.ui.system.lerpSp
import com.hnnujw.course.ui.system.GlassMaterials
import com.hnnujw.course.ui.system.GlassRecipe
import com.hnnujw.course.ui.system.LocalAppBackdrop
import com.hnnujw.course.ui.system.LocalControlBackdrop
import com.hnnujw.course.ui.system.isBackdropSupported
import com.hnnujw.course.ui.system.glass.LiquidActionGroup
import com.hnnujw.course.ui.system.glass.glassRim
import com.hnnujw.course.ui.system.glass.resolvePhysicalLens
import com.hnnujw.course.ui.system.rememberGlassAccessibilityMode
import com.hnnujw.course.ui.system.reportNoticeAnchor

import com.hnnujw.course.ui.theme.NeuDivider
import com.hnnujw.course.ui.theme.NeuPrimary
import com.hnnujw.course.ui.theme.NeuSurface

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
// 星期行必须同时装下「星期」+「日期」+「今天圆点」三行内容（见 WeekHeaderCompact
// 里日期格的行高注释）：星期与日期字号一致，展开 34+34+圆点 ≈ 74px = 37dp，
// 折叠 30+30+圆点 ≈ 64px = 32dp；各留一点余量，免得字号/字体度量稍有出入
// 又把日期挤到"一行都排不下"。
private val HeaderWeekRowExpanded = 40.dp
private val HeaderWeekRowCollapsed = 34.dp
private val HeaderBottomPadExpanded = 8.dp
private val HeaderBottomPadCollapsed = 4.dp

/**
 * 星期条的标签（单个汉字），顺序即星期 1..7。
 *
 * 「显示周末」关闭时靠 `take(5)` 截到周五 —— 截取而不是另建一张 5 元素表，
 * 是为了让"第 N 个格 = 星期 N"这个不变量只有一处定义，
 * 下标换算（日期、今天圆点、无障碍播报）全部照旧。
 *
 * 汉字本身取自 [com.hnnujw.course.schedule.scheduleWeekdayChar]：
 * 星期条只画一个字，补课弹窗画"周六"、正文画"星期六"，三处共用同一张映射表。
 */
private val WeekdayShortLabels =
    (1..7).map { com.hnnujw.course.schedule.scheduleWeekdayChar(it).toString() }

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
    val id: String = if (isCustom) "custom:$customId" else com.hnnujw.course.schedule.ScheduleIdentity.network(
        sourceId, name, teacher, day, startPeriod, endPeriod, weeks, location
    ),
    val hasConflict: Boolean = false,
    val isCurrent: Boolean = false,
    /** 日视图用：今天「下一节」的课（周网格不用这个标记）。 */
    val isNext: Boolean = false
) {
    fun record() = com.hnnujw.course.schedule.ScheduleCourseRecord(id, name, teacher, location, day, startPeriod, endPeriod, weeks, isCustom)
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
    /** true = 日视图（按天分页的列表）；false = 周网格。持久化在 Route。 */
    dayView: Boolean = false,
    onDayViewChange: (Boolean) -> Unit = {},
    /** 右上角更多菜单：同步课表。 */
    onSyncClick: () -> Unit = {},
    /** 右上角更多菜单：添加自定义课程。 */
    onAddClick: () -> Unit = {},
    /** 右上角更多菜单：打开 App 内组件工作台（桌面卡片也从这里加到手机桌面）。 */
    onWidgetBoardClick: () -> Unit = {},
    /** true = 紧凑显示密度（节次行高 ×0.78，参考项目同款）。持久化在 Route。 */
    compact: Boolean = false,
    /**
     * true = 周视图显示周六/周日（默认）；false = 只画周一至周五。
     * 只影响**周视图**；日视图恒 7 天（见 [scheduleVisibleDays]）。持久化在 Route。
     */
    showWeekend: Boolean = true,
    /**
     * 「回到今天」请求计数器：Route 每发一次跳转请求就 +1（桌面组件点按等）。
     * 0 = 无请求。用 nonce 而不是布尔，连续两次点按也能各跳一次。
     */
    todayRequest: Int = 0,
    errorMessage: String = "",
    onRetry: () -> Unit = {},
    firstWeekDate: String? = null,
    weekRequestKey: String? = null
) {
    // 课表字号：只给课表网格用（成绩 / 二课不读这个值）。
    // key 带上 revision —— 设置页改完字号后 revision++，这里立刻拿到新值重组。
    val scheduleSettings = com.hnnujw.course.manager.ScheduleSettingsManager.getInstance()
    val scheduleFontScale = remember(scheduleSettings.revision) { scheduleSettings.scheduleFontScale }
    CompositionLocalProvider(LocalScheduleFontScale provides scheduleFontScale) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
    val coroutineScope = rememberCoroutineScope()
    val maxWeeks = com.hnnujw.course.schedule.ScheduleMaxWeeks
    val reducedMotion = com.hnnujw.course.ui.system.rememberGlassAccessibilityMode().reduceMotion
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
    val actualWeek = com.hnnujw.course.schedule.ScheduleDates.weekAt(effectiveFirstWeekDate, minuteClock)
    val conflictIds = remember(courses) {
        val records = courses.map { it.record() }
        records.filter { com.hnnujw.course.schedule.scheduleConflicts(it, records).isNotEmpty() }.map { it.id }.toSet()
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
    // 日视图状态：今天星期几（1..7）+ 正在浏览的星期。跨进程恢复交给
    // rememberSaveable；冷启动由 Route 用真实日期重置 currentWeek，这里跟随之。
    val todayDay = (Calendar.getInstance().apply { timeInMillis = minuteClock }.get(Calendar.DAY_OF_WEEK) + 5) % 7 + 1
    var selectedDayState by rememberSaveable { mutableIntStateOf(todayDay) }
    // 日视图的时间线（今天有哪些堂 / 正在上 / 下一节）。与周网格共用 minuteClock，
    // 每分钟重算一次，"正在上课"标记不需要额外的秒级时钟。
    val agendaBase = remember(effectiveFirstWeekDate, periodTimes) {
        com.hnnujw.course.schedule.ScheduleTimeBase(
            effectiveFirstWeekDate.orEmpty(),
            periodTimes.associate { it.period to it.startTime },
            periodTimes.associate { it.period to it.endTime })
    }
    val agenda = remember(courses, agendaBase, minuteClock) {
        com.hnnujw.course.schedule.ScheduleAgenda.calculate(courses.map { it.record() }, agendaBase, minuteClock)
    }
    // 「下一节」只在还是今天的课时标出来（明天的下一节不该出现在今天的时间线里）。
    val nextTodayId = agenda.next?.takeIf { next ->
        agenda.today.any { it.startsAt == next.startsAt && it.course.id == next.course.id }
    }?.course?.id
    val pagerState = key(dayView) {
        val firstPageUnit = 1
        val lastPageUnit = if (dayView) maxWeeks * 7 else maxWeeks
        // 日视图的页单元 = (周-1)*7 + 星期；周视图的页单元 = 周次。
        // 切换视图时页数变化，pager 状态必须重建并落到当前浏览的单元上。
        val requestedUnit = if (dayView) (currentWeek - 1) * 7 + selectedDayState else currentWeek
        rememberPagerState(
            initialPage = (requestedUnit - firstPageUnit).coerceIn(0, lastPageUnit - firstPageUnit),
            pageCount = { lastPageUnit }
        )
    }

    val latestWeekChange by rememberUpdatedState(onWeekChange)
    val latestRequestedWeek by rememberUpdatedState(currentWeek)
    val latestWeekRequestKey by rememberUpdatedState(weekRequestKey)
    val latestSelectedDay by rememberUpdatedState(selectedDayState)
    // 日视图下外部（Route）请求的仍是"周次"，换算成该周的 [selectedDay] 那一天。
    val weekSync = remember(pagerState, dayView) {
        val lastPageUnit = if (dayView) maxWeeks * 7 else maxWeeks
        val requestedUnit = if (dayView) (currentWeek - 1) * 7 + latestSelectedDay else currentWeek
        com.hnnujw.course.schedule.ScheduleWeekPagerSync(requestedUnit, weekRequestKey, 1, lastPageUnit)
    }

    var arrowJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    // 记"单元"（周视图 = 周次，日视图 = 周*7+天）而不是页码：视图切换时页码含义会变。
    var requestedUnit by remember(dayView) { mutableIntStateOf(pagerState.currentPage + 1) }
    val userDragging by pagerState.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(userDragging) {
        if (userDragging) { arrowJob?.cancel(); requestedUnit = pagerState.currentPage + 1 }
    }
    fun moveUnit(delta: Int) {
        val lastPageUnit = if (dayView) maxWeeks * 7 else maxWeeks
        requestedUnit = ((if (arrowJob?.isActive == true) requestedUnit else pagerState.currentPage + 1) + delta)
            .coerceIn(1, lastPageUnit)
        arrowJob?.cancel()
        arrowJob = coroutineScope.launch {
            if (reducedMotion) pagerState.scrollToPage(requestedUnit - 1)
            else pagerState.animateScrollToPage(requestedUnit - 1, animationSpec = com.hnnujw.course.ui.theme.MotionProfile.pagerSpring())
        }
    }
    // 桌面组件等外部来源的「回到今天」：必须同时复位星期，否则日视图下会
    // 落到"这一周你上次浏览的那天"而不是今天（深链只带周次信息的坑）。
    LaunchedEffect(todayRequest) {
        if (todayRequest == 0) return@LaunchedEffect
        val target = actualWeek ?: return@LaunchedEffect
        val targetUnit = if (dayView) (target - 1) * 7 + todayDay else target
        selectedDayState = todayDay
        moveUnit(targetUnit - (pagerState.currentPage + 1))
    }

    LaunchedEffect(pagerState, dayView) {
        snapshotFlow {
            Triple(latestRequestedWeek to latestWeekRequestKey, pagerState.isScrollInProgress, pagerState.settledPage)
        }.collect { (request, scrolling, page) ->
            val requestedUnit2 = if (dayView) (request.first - 1) * 7 + latestSelectedDay else request.first
            val targetPage = weekSync.requestPage(requestedUnit2, request.second)
            if (targetPage != null) {
                arrowJob?.cancel()
                pagerState.scrollToPage(targetPage)
                weekSync.settledUnit(targetPage)
            } else if (!scrolling) {
                weekSync.settledUnit(page)?.let { unit ->
                    if (dayView) {
                        selectedDayState = (unit - 1) % 7 + 1
                        latestWeekChange((unit - 1) / 7 + 1)
                    } else {
                        latestWeekChange(unit)
                    }
                }
            }
        }
    }

    // 所有 pager 页共用一个滚动位置：顶栏折叠进度要跟着它推导，
    // 而且左右切周时纵向位置不该跳回顶部。
    val gridScrollState = rememberScrollState()
    // 日视图的滚动位置单独一份：切换视图时各自记住滚到哪，不互相串。
    val dayScrollState = rememberScrollState()
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
                // 日视图下顶栏展示的是 pager 当前落定的周/天（滑动逐日变化）。
                val shownUnit = pagerState.currentPage + 1
                val shownWeek = if (dayView) (shownUnit - 1) / 7 + 1 else shownUnit
                val shownDay = if (dayView) (shownUnit - 1) % 7 + 1 else todayDay
                WeekHeaderCompact(
                    currentWeek = shownWeek,
                    weekOffset = if (reducedMotion) 0f else pagerState.currentPageOffsetFraction,
                    firstWeekDate = effectiveFirstWeekDate,
                    actualWeek = actualWeek,
                    onSettingsClick = onSettingsClick,
                    onExportClick = onExportClick,
                    onMakeUpFromWeekday = onMakeUpFromWeekday,
                    dayView = dayView,
                    onDayViewChange = onDayViewChange,
                    showWeekend = showWeekend,
                    selectedDay = shownDay,
                    onDayClick = { day ->
                        // 点星期条跳到那一天。
                        // 日视图：页单元就是"周*7+天"，直接按天算 delta 翻页。
                        // 周视图：页单元是"周次"，按天算 delta 会跳飞 —— 改为切到日视图
                        // 并把它定位到这一天（切视图时 pager 会用 selectedDayState 重建）。
                        if (dayView) {
                            moveUnit((shownWeek - 1) * 7 + day - (pagerState.currentPage + 1))
                        } else {
                            selectedDayState = day
                            onDayViewChange(true)
                        }
                    },
                    onTodayClick = {
                        if (actualWeek != null) {
                            val target = if (dayView) (actualWeek - 1) * 7 + todayDay else actualWeek
                            moveUnit(target - (pagerState.currentPage + 1))
                        }
                    },
                    showTodayButton = dayView && (actualWeek == null ||
                        shownWeek != actualWeek || (dayView && shownDay != todayDay)),
                    onSyncClick = onSyncClick,
                    onAddClick = onAddClick,
                    onWidgetBoardClick = onWidgetBoardClick,
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
                    com.hnnujw.course.ui.system.SystemEmptyState(title = "课表同步失败", message = errorMessage) {
                        com.hnnujw.course.ui.system.SystemSecondaryButton(text = "重新同步", onClick = onRetry)
                    }
                }
            }

            else -> {
                HorizontalPager(
                    state = pagerState,
                    flingBehavior = androidx.compose.foundation.pager.PagerDefaults.flingBehavior(
                        state = pagerState, snapAnimationSpec = com.hnnujw.course.ui.theme.MotionProfile.pagerSpring()),
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
                    // 页单元：周视图 = 周次；日视图 = (周-1)*7 + 星期。
                    val unit = page + 1
                    val weekNumber = if (dayView) (unit - 1) / 7 + 1 else unit
                    val dayNumber = if (dayView) (unit - 1) % 7 + 1 else todayDay
                    // 「本节上哪门」：冲突时段的用户选择。一门课只要覆盖到任何一个
                    // 选了别人的节次，就整体让位隐藏（key 只到节，A 占 1-2、B 占 2-3
                    // 只在第 2 节撞车时，A 的第 1 节不受影响）。
                    val conflictChoices = com.hnnujw.course.schedule.ScheduleConflictStore.choices
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
                    val isTodayPage = actualWeek != null && weekNumber == actualWeek && dayNumber == todayDay
                    val displayedCourses = remember(courses, conflictIds, liveIds, weekNumber, actualWeek, conflictHiddenIds, isTodayPage, nextTodayId) {
                        courses.filter { it.id !in conflictHiddenIds }.map {
                            it.copy(
                                hasConflict = it.id in conflictIds,
                                isCurrent = if (dayView) {
                                    isTodayPage && it.id in liveIds
                                } else {
                                    weekNumber == actualWeek && it.id in liveIds
                                },
                                isNext = dayView && isTodayPage && it.id == nextTodayId
                            )
                        }
                    }
                    Box(Modifier.fillMaxSize().graphicsLayer {
                        val offset = pagerState.currentPage + pagerState.currentPageOffsetFraction - page
                        scaleX = if (reducedMotion) 1f else com.hnnujw.course.ui.theme.SchedulePagerMotion.scale(offset)
                        scaleY = scaleX
                        alpha = if (reducedMotion) 1f else com.hnnujw.course.ui.theme.SchedulePagerMotion.alpha(offset)
                    }) {
                    if (dayView) {
                        ScheduleDayList(
                            courses = displayedCourses,
                            week = weekNumber,
                            day = dayNumber,
                            firstWeekDate = effectiveFirstWeekDate,
                            times = periodTimes,
                            scrollState = dayScrollState,
                            topInset = statusBarHeight + headerMetrics.expanded,
                            onCourseClick = onCourseClick,
                            agenda = agenda,
                            now = minuteClock,
                            isToday = isTodayPage,
                            onCalendar = onSettingsClick
                        )
                    } else {
                        ScheduleGrid(
                            courses = displayedCourses,
                            currentWeek = weekNumber,
                            periodTimes = periodTimes,
                            periodCount = periodCount,
                            onCourseClick = onCourseClick,
                            scrollState = gridScrollState,
                            // 紧凑显示密度：节次行高 ×0.78，一屏能看到更多节次。
                            compact = compact,
                            // 周视图的可见天数：隐藏周末时只画 5 列。
                            showWeekend = showWeekend,
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
    }
    } // CompositionLocalProvider(LocalScheduleFontScale)
}

@Composable
fun WeekHeaderCompact(
    currentWeek: Int,
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
    actualWeek: Int? = null,
    /** true = 日视图：标题副行带星期、星期条可点跳日、时间列给「今天」按钮。 */
    dayView: Boolean = false,
    onDayViewChange: (Boolean) -> Unit = {},
    /**
     * true = 星期条画到周日；false = 只到周五。
     * 日视图下即使为 false 也画满 7 格（见 [scheduleVisibleDays]）。
     */
    showWeekend: Boolean = true,
    /** 日视图当前浏览的星期（1..7），用于星期条高亮与副行标注。 */
    selectedDay: Int = 1,
    /** 日视图点星期条跳到本周那一天。 */
    onDayClick: (Int) -> Unit = {},
    /** 日视图「回到今天」（当前周 + 今天星期）。 */
    onTodayClick: () -> Unit = {},
    /** 是否显示「今天」快捷按钮（仅在日视图且当前不在今天时为真）。 */
    showTodayButton: Boolean = false,
    /** 右上角更多菜单：同步课表 / 添加课程 / 组件工作台。 */
    onSyncClick: () -> Unit = {},
    onAddClick: () -> Unit = {},
    onWidgetBoardClick: () -> Unit = {}
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
    // 日期条画几天：日视图恒 7（周末的课只能在这里翻到），周视图跟随「显示周末」。
    // 日期格里的下标换算全部照用 1..7 的星期序号，所以只是"少画两格"，
    // 不涉及任何偏移重算。
    val weekLabels = WeekdayShortLabels.take(
        com.hnnujw.course.schedule.scheduleVisibleDays(dayView, showWeekend)
    )
    // 「是否本周」只有一个判据：第一周日期已知，且当前页就是它所在的那一周。
    val isCurrentWeek = actualWeek != null && currentWeek == actualWeek
    // 标题旁的注解：只有翻到别的周才标注（非本周）；本周不再重复写「周X」。
    val weekSuffix = if (actualWeek != null && !isCurrentWeek) "(非本周)" else null
    val todayLabel = "${calendar.get(Calendar.YEAR)}/${calendar.get(Calendar.MONTH) + 1}/${calendar.get(Calendar.DAY_OF_MONTH)}"
    val monthLabel = com.hnnujw.course.schedule.ScheduleDates.date(firstWeekDate, currentWeek, 1)
        ?.let { "${it.get(Calendar.MONTH) + 1}月" }
    // 学期第一周周一日期没配置时，这一行的日期、月份会一起失效
    // （firstMonday 取不到起点，date() 全返回 null）。
    //
    // **现在这种情况基本不会发生了**：`ScheduleRoute` 的 displayedTimeBase 在用户
    // 没设过日期时用 `defaultFirstWeekDate()` 兜底（秋季 9/1、春季 3/1 所在周周一），
    // 所以 firstWeekDate 总有值。这个判据保留着作为最后一道保险——
    // 万一 termId 与日历都推不出来，至少还能给用户一个去设置的入口。
    val weekDatesAvailable = com.hnnujw.course.schedule.ScheduleDates.firstMonday(firstWeekDate) != null
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
            .then(com.hnnujw.course.ui.system.wallpaperHeaderScrim())
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
                        // 日视图再拼上「· 周X」：这一行是当前浏览日期的完整坐标。
                        // getOrNull 而不是下标：日视图下 weekLabels 恒为 7（见 scheduleVisibleDays），
                        // 但"隐藏周末"开关让这个长度变成了变量，下标越界会直接崩在标题行上。
                        val daySuffix = if (dayView) {
                            weekLabels.getOrNull(selectedDay - 1)?.let { "  周$it" }
                        } else {
                            null
                        }
                        Text(
                            text = listOfNotNull(todayLabel, daySuffix, weekSuffix).joinToString("  "),
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

                    Row(
                        modifier = Modifier.testTag("schedule-header-actions"),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 翻周靠横向滑动 pager（箭头已按需求移除，动作行更宽松）。
                        // 日/周视图切换（参考项目的 ScheduleViewToggle）：
                        // 88dp 定宽壳里放 36dp 高的分段控件，折叠时随行高一起收。
                        Box(
                            modifier = Modifier
                                .width(88.dp)
                                .height(48.dp)
                                .testTag("schedule-view-toggle"),
                            contentAlignment = Alignment.Center
                        ) {
                            LiquidSegmentedControl(
                                options = listOf("日视图", "周视图"),
                                selectedIndex = if (dayView) 0 else 1,
                                onSelect = { onDayViewChange(it == 0) },
                                height = lerpDp(36.dp, 32.dp, collapse)
                            ) { index, selection, color ->
                                Text(
                                    text = if (index == 0) "日" else "周",
                                    fontSize = 14.sp,
                                    color = color,
                                    fontWeight = if (selection >= 0.5f) FontWeight.Bold else FontWeight.Medium
                                )
                            }
                        }
                        // 右上角更多菜单（参考项目的做法）：同步 / 导出 / 添加课程 /
                        // 组件工作台 / 课表设置 收进一个入口，动作行不再被一堆图标挤满。
                        SystemActionMenu(
                            description = "更多课表操作",
                            actions = listOf(
                                SystemMenuAction("同步课表", Icons.Outlined.Refresh, onSyncClick),
                                SystemMenuAction("导出课表", Icons.Filled.Share, onExportClick),
                                SystemMenuAction("添加课程", Icons.Outlined.Add, onAddClick),
                                SystemMenuAction("组件工作台", Icons.Outlined.Dashboard, onWidgetBoardClick),
                                SystemMenuAction("课表设置", Icons.Filled.Settings, onSettingsClick)
                            ),
                            modifier = Modifier.testTag("schedule-more"),
                            trigger = { toggle ->
                                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                                    LiquidButton(
                                        onClick = toggle,
                                        modifier = Modifier.size(40.dp),
                                        minHeight = 40.dp,
                                        horizontalPadding = 0.dp
                                    ) {
                                        AnimatedLineIcon(
                                            AnimatedIconSpec.More,
                                            Modifier.size(21.dp),
                                            description = "更多课表操作",
                                            tint = MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                }
                            }
                        )
                    }
                }

                Spacer(Modifier.height(lerpDp(headerMetrics.titleGap, 0.dp, collapse)))

                // 星期条的行高：Row 与条内的 LiquidSegmentedControl 必须用**同一个值**，
                // 否则控件会按自己的默认高（52dp）撑开，整行比网格宽一截。
                val weekRowHeight = lerpDp(
                    headerMetrics.weekRowExpanded,
                    maxOf(HeaderWeekRowCollapsed, 26.dp * LocalDensity.current.fontScale),
                    collapse
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(weekRowHeight)
                        .padding(horizontal = scheduleGridPadding()),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 时间列这一格放月份：七个日列只写「几号」，月份在这里出现一次。
                    // 日视图偏离今天时，这一格让位给「今天」快捷键（参考项目同款位置）。
                    Box(
                        modifier = Modifier.width(scheduleTimeColumnWidth() + ScheduleTimeColumnShadowWidth),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        if (showTodayButton) {
                            LiquidButton(
                                onClick = onTodayClick,
                                modifier = Modifier
                                    .size(40.dp)
                                    .testTag("schedule-today"),
                                minHeight = 40.dp,
                                horizontalPadding = 0.dp
                            ) {
                                Text(
                                    text = "今天",
                                    fontSize = 11.sp,
                                    // 固定高度容器里的文字必须显式写行高（同标题行的坑）。
                                    lineHeight = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1
                                )
                            }
                        } else if (monthLabel != null) {
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
                    // 星期条 = 液态分段控件：滑块（圆圈）只在**有"当日"可言**时出现。
                    // 日视图的 pager 是逐日翻页的，shownDay 随 currentPage 变化，
                    // 滑块就跟着滑 —— 滑到哪天，日期行上就有一格被圈出来；
                    // 周视图里它只标今天；翻到别的周时今天不在这周，整条就不给滑块。
                    //
                    // 三处几何必须和下方网格对齐（见 scheduleGridPadding 的注释）：
                    // edgePadding = 0.dp（默认的 3/5dp 会让整条相对网格偏移）、
                    // showTrack = false（只留滑块，壁纸从格间透上来）、
                    // restingRefraction = 0f（这一格是「星期 + 日期 + 圆点」三行密排文字，
                    // 静止态折射会把字拉糊）。
                    // 滑块落点由纯函数决定（三条规则的优先级见
                    // [scheduleDateStripSelection] 的文档，那里有单测钉住）。
                    val indicatorIndex = scheduleDateStripSelection(
                        dayView = dayView,
                        selectedDay = selectedDay,
                        isCurrentWeek = isCurrentWeek,
                        currentDayOfWeek = currentDayOfWeek,
                        dayCount = weekLabels.size
                    )
                    LiquidSegmentedControl(
                        options = weekLabels,
                        // 非本周时 indicatorIndex 为 null（没有"当日"）：滑块与选中文字
                        // 一起收掉，见 showIndicator 的说明。这里给 0 只是占位 ——
                        // showIndicator = false 时控件不会把它当成选中项。
                        selectedIndex = indicatorIndex ?: 0,
                        showIndicator = indicatorIndex != null,
                        onSelect = { index ->
                            // 周视图的「六 / 日」仍是补课入口（见 [MakeUpCourseDialog]）：
                            // 用户点的是"这一周的周六"这一格，所以补课要带周次。
                            // 日视图下七个格子一律是"跳到那天"，否则滑不到周末。
                            if (!dayView && index >= 5 && onMakeUpFromWeekday != {}) {
                                onMakeUpFromWeekday(index + 1)
                            } else {
                                onDayClick(index + 1)
                            }
                        },
                        modifier = Modifier.weight(1f),
                        height = weekRowHeight,
                        edgePadding = 0.dp,
                        // verticalInset = 0.dp 是必须的：控件会给标签内容加 verticalPadding 内缩，
                        // 而这一行只有 40dp。留 2dp 的话内容区只剩 36dp，装不下
                        // 「星期 + 日期 + 圆点」37dp，日期会被裁掉一截。
                        // 内缩为 0 时内容区 = 整行高，与改造前那张纯 Column 的布局完全等价；
                        // 同时滑块变成近乎正方（≈46dp 宽 × 40dp 高），正是参考项目里那个"圆圈"。
                        verticalInset = 0.dp,
                        restingRefraction = 0f,
                        showTrack = false
                    ) { index, selection, color ->
                        val isRealToday = isCurrentWeek && index + 1 == currentDayOfWeek
                        // 用 weekdayDate（永不返回 null）：拿不到就按"本周周一 + 周次偏移"兜底，
                        // 星期条永远显示日期，不再退回占位小圆点。
                        val date = com.hnnujw.course.schedule.ScheduleDates.weekdayDate(
                            firstWeekDate, currentWeek, index + 1
                        )
                        // 选中态（滑块所在格）与今天都用主色：前者回答"你在看哪天"，
                        // 后者是绝对坐标。两者可能重合，也可能分开（翻到别的周时）。
                        val emphasized = isRealToday || selection >= 0.5f
                        val dateAlpha = (1f - kotlin.math.abs(weekOffset) * 2f).coerceIn(0.35f, 1f)
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .testTag("schedule-weekday-${index + 1}")
                                .semantics { if (isRealToday) stateDescription = "今天" },
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            // 【两个 Text 都必须显式写 lineHeight】。
                            //
                            // 不写时它们继承 MaterialTheme.typography.bodyLarge 的 lineHeight = 24.sp
                            // （320dpi 上 = 48px），与 13sp / 11.5sp 的字号完全无关。星期行的固定高度
                            // 只有 34dp（68px），星期那行先吃掉 48px，剩下 20px 给日期 —— 比一行的行高
                            // 还矮，Compose 的排版结果是"一行都排不下"，于是**节点照常测量、文字一个
                            // 像素都不画**。表现就是：UI 层级里能看到 "14" "15"… 这些节点，屏幕上却
                            // 什么都没有。紧凑行高后：星期 17sp(34px) + 日期 17sp(34px) + 圆点 ≈ 68px，
                            // 正好装进行高。
                            Text(
                                text = weekLabels[index],
                                fontSize = lerpSp(13f, 11.5f, collapse),
                                lineHeight = lerpSp(17f, 15f, collapse),
                                fontWeight = if (emphasized) FontWeight.SemiBold else FontWeight.Medium,
                                color = color,
                                maxLines = 1
                            )
                            Text(
                                text = date.get(Calendar.DAY_OF_MONTH).toString(),
                                fontSize = lerpSp(13f, 11.5f, collapse),
                                lineHeight = lerpSp(17f, 15f, collapse),
                                fontWeight = FontWeight.SemiBold,
                                color = if (emphasized) MaterialTheme.colorScheme.primary else NeuPrimary,
                                maxLines = 1,
                                modifier = Modifier.graphicsLayer { alpha = dateAlpha }
                            )
                            // 今天的圆点。**每一格都占同样大小的位置**（非今天画透明），
                            // 否则有/无圆点的两格文字基线会差一个点高。
                            //
                            // 刻意不额外加 padding：两行文字行高 17dp + 17dp + 圆点 3dp = 37dp，
                            // 与改造前那张纯 Column 的内容高度一模一样，行高余量不变。
                            Box(
                                modifier = Modifier
                                    .size(3.dp)
                                    .background(
                                        color = if (isRealToday) MaterialTheme.colorScheme.primary else Color.Transparent,
                                        shape = RoundedCornerShape(2.dp)
                                    )
                            )
                        }
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

/**
 * 补课弹窗：把某一周的某一天的课**整体**搬到某一周的某一天。
 *
 * ## 语义
 *
 * 「在**第几周的周几**补课」= 源的「周 + 天」和目标的「周 + 天」**四个都由用户选**：
 *
 * ```
 * 弹窗里选「第 3 周 · 星期三」的课，补到「第 8 周 · 周六」
 *   → 第 3 周星期三的 3 门课渲染到「第 8 周 · 周六」的网格里
 * ```
 *
 * 源那一天、那一周的**每一门课**都会按各自的原时段落到目标那一格。
 *
 * 调用方给的两个值（[initialTargetDay] / [initialTargetWeek]）都只是**初值** ——
 * 用户点周六那一格进来就是"第 N 周周六"，但他完全可以把课补到别的周。
 * 初值必须快照：弹窗开着时底下的 pager 还能被手势翻动，
 * 届时"第 4 周"会悄悄变成"第 9 周"（见 `ScheduleRoute.makeUpWeek`）。
 *
 * 因此：
 * - **不需要挑具体课程**（用户明确要求）——整天的课一起补，符合"这一天被调过来了"的现实；
 * - 周次与星期都用**折叠选择器**（收起时只显示当前选择，点开才铺出可选项），
 *   而不是旧版那种一屏铺满的加减按钮 + 方块阵；
 * - 目标周 / 目标天**都由用户在弹窗里决定**，并通过 [onConfirm] 回传给调用方。
 *   调用方不能拿自己传进去的初值当答案（那是"默认决定了"的旧行为）。
 *
 * 视觉材质交给 [SystemDialog] —— 主站登录页那套液态玻璃模态（blur → lens → vibrancy）。
 */
@Composable
fun MakeUpCourseDialog(
    /**
     * 目标星期（1..7）的**初始值**：从周六 / 周日那一格点进来就是那一格，
     * 从课表设置进来是周六。用户可以在弹窗里改成任意一天（见 [FoldPanel.TargetDay]）。
     */
    initialTargetDay: Int,
    /**
     * 目标周次的**初始值**：用户点击那一刻正在浏览的周次。
     * 用户可以在弹窗里改成任意一周（见 [FoldPanel.TargetWeek]）。
     */
    initialTargetWeek: Int,
    courses: List<ScheduleCourseUi>,
    firstWeekDate: String? = null,
    onDismiss: () -> Unit,
    /**
     * 确认补课：([来源星期 1..7], [来源周次], [目标星期 1..7], [目标周次])。
     * 四个值全部由弹窗内的选择决定，调用方照单执行即可。
     */
    onConfirm: (sourceDay: Int, sourceWeek: Int, targetDay: Int, targetWeek: Int) -> Unit
) {
    val maxWeeks = com.hnnujw.course.schedule.ScheduleMaxWeeks
    // 调用方给的两个初值：夹到合法区间，后面"改回第 N 周 / 周X"的回退按钮也用它们。
    val initialWeek = initialTargetWeek.coerceIn(1, maxWeeks)
    val initialDay = initialTargetDay.coerceIn(1, 7)
    // 目标星期：初值来自调用方（点哪一格就是哪一格），但用户可以改。
    // 改目标天是本次新增的能力 —— 原来它写死在调用方，用户只能改"源"。
    var targetDay by remember { mutableIntStateOf(initialDay) }
    // 目标周次：同样只是初值。以前它被写死成"点进来的那一周"，
    // 于是想把课补到第 8 周就只能先翻到第 8 周再点进来 —— 现在四个值都能改。
    var targetWeek by remember { mutableIntStateOf(initialWeek) }
    // 源默认取同一天同一周（周六点进来默认补本周周六的课），这是最常见的一种调休
    var weekday by remember { mutableIntStateOf(initialDay) }
    var week by remember { mutableIntStateOf(initialWeek) }
    // 四个面板同一时刻只展开一个：都摊开时弹窗会顶到屏幕上下缘
    var expanded by remember { mutableStateOf(FoldPanel.None) }

    val targetDayName = com.hnnujw.course.schedule.scheduleWeekdayShort(targetDay)
    val sourceLabel = com.hnnujw.course.schedule.scheduleWeekdayLong(weekday)
    // 目标格子在课表上的完整坐标，用于标题与结果预览（"补到第 8 周周六"）
    val targetDate = com.hnnujw.course.schedule.ScheduleDates.date(firstWeekDate, targetWeek, targetDay)
        ?.let { "${it.get(Calendar.MONTH) + 1}/${it.get(Calendar.DAY_OF_MONTH)}" }
    val targetLabel = "第 $targetWeek 周$targetDayName"
    // 预览用：该周该天到底有几门课会搬过来。0 的时候确认按钮直接禁用，
    // 免得用户点完才发现"这天没课"。
    val affected = remember(courses, weekday, week) {
        courses.count { !it.isCustom && it.day == weekday && isInWeek(it.weeks, week) }
    }
    // 目标与源指到同一格 = 把课搬到自己身上：课表上会多出一张完全重叠的卡片，
    // 看得见却点不中下面那张。以前目标天写死在周六 / 周日、而周末通常没课，
    // 撞不上；现在目标的周和天都任选，这一格必须堵掉。
    val isSameSlot = weekday == targetDay && week == targetWeek

    // 折叠面板开着时才渲染滚轮。滚轮的初始项由它自己的 remember 决定，
    // 收起再展开会重新从"当前选中值"起步 —— 这正是想要的语义。
    if (expanded == FoldPanel.Week) {
        com.hnnujw.course.ui.system.GlassOptionWheelDialog(
            title = "要补哪一周",
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
        com.hnnujw.course.ui.system.GlassOptionWheelDialog(
            title = "要补哪一天",
            options = (1..7).map { com.hnnujw.course.schedule.scheduleWeekdayLong(it) },
            selectedIndex = (weekday - 1).coerceIn(0, 6),
            onConfirm = { index ->
                weekday = index + 1
                expanded = FoldPanel.None
            },
            onDismiss = { expanded = FoldPanel.None }
        )
    }
    if (expanded == FoldPanel.TargetWeek) {
        // 全学期都给：调休补课常常补在好几周之后（比如假期前那个周末的课
        // 顺延到假期后某一周），只给"本周"等于没给。
        com.hnnujw.course.ui.system.GlassOptionWheelDialog(
            title = "补到第几周",
            options = (1..maxWeeks).map { "第 $it 周" },
            selectedIndex = (targetWeek - 1).coerceIn(0, maxWeeks - 1),
            onConfirm = { index ->
                targetWeek = index + 1
                expanded = FoldPanel.None
            },
            onDismiss = { expanded = FoldPanel.None }
        )
    }
    if (expanded == FoldPanel.TargetDay) {
        // 七天全给：补课在现实里确实会落到工作日（比如周一调休、课时顺延），
        // 把可选项收成"周六 / 周日"只是把调用方的默认值再写死一遍。
        com.hnnujw.course.ui.system.GlassOptionWheelDialog(
            title = "补到哪一天",
            options = (1..7).map { com.hnnujw.course.schedule.scheduleWeekdayShort(it) },
            selectedIndex = (targetDay - 1).coerceIn(0, 6),
            onConfirm = { index ->
                targetDay = index + 1
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
                    text = if (targetDate != null) "先定补到哪一格（$targetDate）· 再选要补哪一周哪一天的课"
                    else "先定补到第几周哪一天，再选要补哪一周哪一天的课，整天的课一起搬过来",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        },
        confirmButton = {
            SystemPrimaryButton(
                text = when {
                    isSameSlot -> "目标和来源是同一格"
                    affected > 0 -> "补到$targetLabel（$affected 门）"
                    else -> "该周这天没有课"
                },
                onClick = { onConfirm(weekday, week, targetDay, targetWeek) },
                enabled = affected > 0 && !isSameSlot,
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
            // 四项都点开滚轮（见上面四个 GlassOptionWheelDialog）。
            // 原来这里是点击式网格：选"第几周"要在一堆方格里找，选"星期几"要在
            // 七个格子里瞄。换成滑轮后与课表设置里的日期/时间选择是同一套交互。
            //
            // 顺序刻意是"**先目标、后源**"，即用户心里的那句人话：
            // 「我要在**第几周周几**补课」→「补的是**那周那天**的课」。
            // 先定"补在哪一格"（这一格通常是用户点进来的那一格，往往不用改），
            // 再定"补什么"。反过来（先源后目标）时用户会以为第一行是"要补的课在哪"，
            // 结果第一行其实在决定课落到哪。
            //
            // ⚠️ 下面**预览行仍是"源 → 目标"**（`A 的课 → 落到 B`）。
            // 选择和预览的语序不同是刻意的：选择按"提问顺序"，预览按"因果顺序"。
            FoldSetting(
                label = "补到第几周",
                value = "第 $targetWeek 周",
                expanded = expanded == FoldPanel.TargetWeek,
                onToggle = {
                    expanded = if (expanded == FoldPanel.TargetWeek) FoldPanel.None else FoldPanel.TargetWeek
                },
                // 只有"用户动过目标周"才给回退按钮：没动过时它就是初值，
                // 挂一个"改回第 N 周"的按钮反而像是推荐。
                summary = if (expanded != FoldPanel.TargetWeek && targetWeek != initialWeek) {
                    "改回第 $initialWeek 周" to { targetWeek = initialWeek }
                } else null
            )
            FoldSetting(
                label = "补到哪一天",
                value = targetDayName,
                expanded = expanded == FoldPanel.TargetDay,
                onToggle = {
                    expanded = if (expanded == FoldPanel.TargetDay) FoldPanel.None else FoldPanel.TargetDay
                },
                // 只有"用户动过目标天"才给回退按钮：没动过时它就是初值，
                // 挂一个"补回周六"的按钮反而像是推荐。
                summary = if (expanded != FoldPanel.TargetDay && targetDay != initialDay) {
                    "改回${com.hnnujw.course.schedule.scheduleWeekdayShort(initialDay)}" to {
                        targetDay = initialDay
                    }
                } else null
            )
            FoldSetting(
                label = "要补哪一周",
                value = "第 $week 周",
                expanded = expanded == FoldPanel.Week,
                onToggle = {
                    expanded = if (expanded == FoldPanel.Week) FoldPanel.None else FoldPanel.Week
                },
                // 源的回退按钮写"补本周"：最常见的调休就是"把本周某天的课补到本周另一天"
                summary = if (expanded != FoldPanel.Week && week != initialWeek) {
                    "补本周" to { week = initialWeek }
                } else null
            )
            FoldSetting(
                label = "要补哪一天",
                value = sourceLabel,
                expanded = expanded == FoldPanel.Weekday,
                onToggle = {
                    expanded = if (expanded == FoldPanel.Weekday) FoldPanel.None else FoldPanel.Weekday
                }
            )

            // 结果预览：说清"会发生什么"，而不是让用户自己推
            val canConfirm = affected > 0 && !isSameSlot
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
                    imageVector = if (canConfirm) Icons.Filled.CheckCircle
                    else Icons.Filled.Warning,
                    contentDescription = null,
                    tint = if (canConfirm) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = when {
                        isSameSlot -> "第 $week 周 · $sourceLabel 就是要补到的那一格，换一天或换一周"
                        affected > 0 ->
                            "第 $week 周 · $sourceLabel 的 $affected 门课 → 第 $targetWeek 周$targetDayName"
                        else -> "第 $week 周 · $sourceLabel 没有课，换一天或换一周试试"
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
private enum class FoldPanel { None, Week, Weekday, TargetWeek, TargetDay }

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
    /** true = 紧凑显示密度：节次最小行高 ×0.78（参考项目同款）。 */
    compact: Boolean = false,
    /**
     * 顶栏高度。**施加在 verticalScroll 内部**——容器保持全出血，于是滚动位置 0
     * 时内容起始于顶栏之下，滚起来则从顶栏底下穿过，顶栏芯片才有东西可折射。
     * 加在容器上（`Modifier.padding(paddingValues)`）就成了"内容被推到顶栏下方"，
     * 芯片背后永远是空的。
     */
    topInset: Dp = 0.dp,
    /**
     * true = 画周六/周日两列；false = 只画周一至周五。
     *
     * 隐藏周末时被挡住的课程**不再参与布局**（不是画出来再裁掉）：
     * 列宽按可见天数均分，5 列比 7 列宽 40%，课程卡片也随之变宽，
     * 这正是用户打开这个开关想要的收益。
     */
    showWeekend: Boolean = true
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
    // ScheduleGrid 只服务周视图（日视图走 ScheduleDayList），所以这里恒按 dayView = false 取。
    val visibleDays = com.hnnujw.course.schedule.scheduleVisibleDays(
        dayView = false, showWeekend = showWeekend
    )
    // 只对"看得见的那几天"布局：隐藏周末时，周六的课既不占列、也不参与行高实测 ——
    // 否则一节周六的课会把整周的节次行高顶高，而用户看到的是一片空白。
    val visibleCourses = remember(weeklyCourses, visibleDays) {
        weeklyCourses.filter { it.day in 1..visibleDays }
    }
    val timeColumnWidth = scheduleTimeColumnWidth()
    val gridPadding = scheduleGridPadding()
    val dayColumnWidthPx = with(LocalDensity.current) {
        ((constraints.maxWidth - gridPadding.roundToPx() * 2 - timeColumnWidth.roundToPx() -
            ScheduleTimeColumnShadowWidth.roundToPx()) / visibleDays.toFloat()).roundToInt()
    }
    // 紧凑密度只压「最小行高」：实测文字仍可能把行撑高，字不会被裁。
    // 0.78 与参考项目一致 —— 一屏能多看约四分之一的节次。
    val periodHeight = rememberCoursePeriodHeight(
        visibleCourses, dayColumnWidthPx,
        schedulePeriodHeight() * if (compact) 0.78f else 1f
    )
    val totalHeight = periodHeight * periodCount
    val darkGrid = com.hnnujw.course.ui.system.rememberGlassDarkTheme()
    val gridTint = MaterialTheme.colorScheme.surface.copy(alpha =
        if (darkGrid || com.hnnujw.course.manager.AppearanceSettingsManager.mode != com.hnnujw.course.manager.WallpaperMode.Preset) 0.86f else 0.18f)
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
                bottom = com.hnnujw.course.ui.system.LocalAppOverlayBottomInset.current + 24.dp
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
                        periodHeight = periodHeight,
                        dayCount = visibleDays
                    )
                    TimetableLayout(
                        courses = visibleCourses,
                        periodCount = periodCount,
                        periodHeight = periodHeight,
                        dayCount = visibleDays,
                        onCourseClick = onCourseClick
                    )

                    // 空态按**看得见的课程**判断：只藏了周末课时，网格确实是空的，
                    // 用户需要的是"为什么空"的线索 —— 由网格下方那条周末提示给出。
                    if (visibleCourses.isEmpty()) {
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

        // 隐藏周末时，把"看不见的课"明说出来。不说的话用户只会觉得"我的课少了两门"，
        // 而不会想到是自己开的开关。计数为 0 时整条不出现 —— 空提示比没有提示更烦人。
        val hiddenWeekendCourses = com.hnnujw.course.schedule.hiddenWeekendCourseCount(
            weeklyCourses.map { it.day },
            showWeekend
        )
        if (hiddenWeekendCourses > 0) {
            Text(
                text = "另有 $hiddenWeekendCourses 门周末课程，可在日视图查看",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
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
    !com.hnnujw.course.schedule.ScheduleWeeks.parse(weeks).valid

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
    periodHeight: Dp,
    /** 画几列日列。5 = 隐藏周末，列宽随之变宽（由 Row 的 weight 均分）。 */
    dayCount: Int = 7
) {
    Box(modifier = Modifier.fillMaxSize()) {
        // 去斑马纹：透明列 + 极淡分隔线，壁纸从网格间透出
        Row(modifier = Modifier.fillMaxSize()) {
            repeat(dayCount) { dayIndex ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                ) {
                    // 最后一列不画右边界：那是网格卡片的边框，再画一条会变成双线。
                    if (dayIndex < dayCount - 1) {
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
    /** 日列数。列宽 = 可用宽 / dayCount，课程卡片按星期落列。 */
    dayCount: Int = 7,
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
        val columnWidth = width / dayCount.toFloat()
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
                val dayIndex = (course.day - 1).coerceIn(0, dayCount - 1)
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
    val darkCard = com.hnnujw.course.ui.system.rememberGlassDarkTheme()
    val focusRequester = remember { FocusRequester() }
    val focusRegistry = LocalScheduleFocus.current
    var cardBounds by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    val unknownWeeks = remember(course.weeks) { !com.hnnujw.course.schedule.ScheduleWeeks.parse(course.weeks).valid }
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
        targetValue = if (isPressed && !com.hnnujw.course.ui.system.rememberGlassAccessibilityMode().reduceMotion) 0.97f else 1f,
        animationSpec = com.hnnujw.course.ui.theme.MotionProfile.iconSpring(),
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
    return com.hnnujw.course.schedule.ScheduleWeeks.parse(weeks).visibleIn(week)
}
