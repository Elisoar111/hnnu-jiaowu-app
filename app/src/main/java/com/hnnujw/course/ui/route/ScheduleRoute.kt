package com.hnnujw.course.ui.route

import com.hnnujw.course.ui.system.GlassToaster
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import com.hnnujw.course.schedule.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.fragment.app.FragmentActivity
import com.hnnujw.course.demo.DemoData
import com.hnnujw.course.academic.AcademicGatewayFactory
import com.hnnujw.course.academic.AcademicStudyBridge
import com.hnnujw.course.manager.ScheduleSettingsManager
import com.hnnujw.course.manager.UserManager
import com.hnnujw.course.network.CourseApiClient
import com.hnnujw.course.ui.screen.PeriodTimeUi
import com.hnnujw.course.ui.screen.ScheduleCourseUi
import com.hnnujw.course.ui.screen.ScheduleScreen
import com.hnnujw.course.ui.screen.ScheduleSettingsScreen
import com.hnnujw.course.ui.screen.isInWeek
import com.hnnujw.course.ui.system.DisablePlatformDialogDim
import com.hnnujw.course.ui.system.SystemDialog
import com.hnnujw.course.ui.system.SystemPrimaryButton
import com.hnnujw.course.ui.system.SystemSecondaryButton
import com.hnnujw.course.ui.theme.MotionDuration
import com.hnnujw.course.ui.theme.MotionEasing
import com.hnnujw.course.ui.theme.MotionSpring
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.Calendar
import com.hnnujw.course.utils.ICalExporter
import com.hnnujw.course.schedule.ScheduleExcelCourse
import com.hnnujw.course.schedule.ScheduleExcelIO
import com.hnnujw.course.document.XlsxParser
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.hnnujw.course.AppTabNavigation

private const val ScheduleRouteSnapshotMaxAgeMs = 5 * 60 * 1000L

/** 保留课表数据状态，但页面 Composition 与玻璃图层仍在切走时立即释放。 */
private data class ScheduleRouteSnapshot(
    val savedAtMs: Long,
    val currentWeek: Int,
    val courses: List<ScheduleCourseUi>,
    val periodTimes: List<PeriodTimeUi>,
    val periodCount: Int,
    val termId: String = "",
    val appliedCalendar: String? = null
)

private object ScheduleRouteMemoryCache {
    private val snapshots = mutableMapOf<String, ScheduleRouteSnapshot>()

    fun get(accountKey: String): ScheduleRouteSnapshot? = snapshots[accountKey]?.takeIf {
        System.currentTimeMillis() - it.savedAtMs <= ScheduleRouteSnapshotMaxAgeMs
    }

    fun put(accountKey: String, snapshot: ScheduleRouteSnapshot) {
        snapshots[accountKey] = snapshot
    }
}

@Composable
fun ScheduleRoute() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isDemoMode = remember { UserManager.getInstance().isDemoMode }
    val routeAccountKey = remember { UserManager.getInstance().currentAccountStorageKey }
    val sessions = UserManager.getInstance().sessionState
    val session by sessions.state.collectAsState()
    val requests = remember { com.hnnujw.course.manager.SessionRequestGate(sessions) }
    DisposableEffect(requests) { onDispose { requests.cancelAll() } }
    // 冲突时段的「本节上哪门」选择按账号隔离存储：切账号 / 冷启动后在这里重载，
    // 课表网格与课程详情弹层直接读它的 Compose state，重载后当帧生效。
    LaunchedEffect(routeAccountKey) {
        com.hnnujw.course.schedule.ScheduleConflictStore.refresh(context)
    }
    val restoredSnapshot = remember(routeAccountKey) {
        ScheduleRouteMemoryCache.get(routeAccountKey)
    }
    var hasInitializedRoute by remember(routeAccountKey) {
        mutableStateOf(restoredSnapshot != null)
    }
    var skipFirstLoad by remember(routeAccountKey) {
        mutableStateOf(restoredSnapshot != null)
    }
    
    // State
    var currentWeek by rememberSaveable(routeAccountKey) {
        mutableIntStateOf(restoredSnapshot?.currentWeek ?: 1)
    }
    var courses by remember(routeAccountKey) {
        mutableStateOf(restoredSnapshot?.courses ?: emptyList())
    }
    var isLoading by remember { mutableStateOf(false) }
    var loadError by remember(routeAccountKey) { mutableStateOf("") }
    var studyLoadJob by remember(routeAccountKey) { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var studyGeneration by remember(routeAccountKey) { mutableIntStateOf(0) }
    var periodTimes by remember(routeAccountKey) {
        mutableStateOf(restoredSnapshot?.periodTimes ?: emptyList())
    }
    var periodCount by remember(routeAccountKey) {
        mutableIntStateOf(restoredSnapshot?.periodCount ?: 12)
    }
    
    // Dialog State
    var showSettingsDialog by rememberSaveable { mutableStateOf(false) }
    /** 同一个子页窗口里的第二层：true = 正在看「课程提醒设置」。 */
    var showReminderSettings by rememberSaveable { mutableStateOf(false) }
    var detailId by rememberSaveable(routeAccountKey) { mutableStateOf<String?>(null) }
    var detailSourceBounds by remember(routeAccountKey) { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    var editingId by rememberSaveable(routeAccountKey) { mutableStateOf<String?>(null) }
    var resolvedTermId by rememberSaveable(routeAccountKey) { mutableStateOf(restoredSnapshot?.termId.orEmpty()) }
    var appliedCalendar by rememberSaveable(routeAccountKey) { mutableStateOf(restoredSnapshot?.appliedCalendar) }
    var notificationCourseJson by rememberSaveable(routeAccountKey) { mutableStateOf<String?>(null) }
    var settingsTermOverride by rememberSaveable(routeAccountKey) { mutableStateOf<String?>(null) }
    var deletedCourseJson by rememberSaveable(routeAccountKey) { mutableStateOf<String?>(null) }
    var deletedRemindersJson by rememberSaveable(routeAccountKey) { mutableStateOf("[]") }
    var undoDeadline by rememberSaveable(routeAccountKey) { mutableLongStateOf(0L) }
    /** 0 = 未打开；6/7 = 从周六 / 周日的星期条进入补课弹窗。 */
    var makeUpDay by remember { mutableIntStateOf(0) }
    /**
     * 补课把课落进**别的周**后，让课表 pager 跳到那一格，免得用户改了却看不到。
     * 递增它 == 换一个 `weekRequestKey`，从而触发 [ScheduleWeekPagerSync] 的跳转
     * （见下面 `ScheduleScreen` 的 `snapshotFlow`）。光改 `currentWeek` 不会动 pager，
     * 因为 `ScheduleWeekPagerSync` 只在 key 变了或首屏直接请求时才跳。
     */
    var weekJumpSeq by remember { mutableIntStateOf(0) }
    /**
     * 补课的目标周次 = 用户点击那一刻正在浏览的周次。
     *
     * 快照下来而不是直接用 `currentWeek`：弹窗打开后底下的课表 pager 仍可被
     * 手势或翻页键改动，届时"第 4 周周六"会悄悄变成"第 9 周周六"，
     * 用户看到的结果和点击时的预期对不上。
     */
    var makeUpWeek by remember { mutableIntStateOf(1) }
    /** true = 顶栏导出按钮弹「日历 / Excel」格式选择。 */
    var showExportChooser by rememberSaveable { mutableStateOf(false) }
    /** true = 显示 App 内「组件工作台」（Compose 卡片，与系统桌面组件无关）。 */
    var showWidgetBoard by rememberSaveable { mutableStateOf(false) }
    // 日 / 周视图偏好：全局生效（不按账号），冷启动还原。
    var dayView by rememberSaveable { mutableStateOf(ScheduleDisplayStore.dayView(context)) }
    // 显示密度（标准 / 紧凑）：同属全局展示偏好。
    var scheduleCompact by rememberSaveable { mutableStateOf(ScheduleDisplayStore.compact(context)) }
    // 周视图是否画周末两列：同属全局展示偏好。日视图恒 7 天，不受此开关影响。
    var scheduleShowWeekend by rememberSaveable { mutableStateOf(ScheduleDisplayStore.showWeekend(context)) }
    // 「回到今天」请求计数（桌面组件点按时 +1，见 ScheduleScreen.todayRequest）。
    var todayNonce by remember { mutableIntStateOf(0) }
    
    // Managers
    val settingsManager = remember { ScheduleSettingsManager.getInstance().apply { init(context) } }
    val scheduleCache = remember(context) {
        ScheduleCacheStore(context.getSharedPreferences("schedule_cache", android.content.Context.MODE_PRIVATE))
    }
    val reminderScheduler = remember(context) { ScheduleReminderScheduler.get(context) }
    val customRevision = settingsManager.revision
    val customCourses = remember(customRevision, routeAccountKey) { settingsManager.getCustomCourses(routeAccountKey) }
    val remindersRevision = reminderScheduler.revision
    val settingsTerm = settingsTermOverride ?: resolvedTermId
    val termTimeBase = remember(settingsTerm, remindersRevision) { reminderScheduler.timeBase(routeAccountKey, settingsTerm) }
    // 「课程提醒」那一行显示的状态。读 reminderScheduler.revision 让它跟着调度器一起刷新 ——
    // 提醒设置页里改了总开关/提前量，退回来这一行要立刻变。
    val reminderSummary = remember(remindersRevision) {
        val current = reminderScheduler.settings()
        buildString {
            append(if (current.masterEnabled) "已开启" else "已关闭")
            // leadMinutesLabel 自己带「上课时 / N 分钟」两种说法，这里不要再拼「提前」，
            // 否则 0 分钟会写成「提前 上课时」。
            if (current.masterEnabled) {
                append(" · ").append(com.hnnujw.course.schedule.leadMinutesLabel(current.leadMinutes))
            }
            if (current.autoMode != com.hnnujw.course.schedule.AutoModeKind.Off) {
                append(" · 上课自动").append(current.autoMode.label)
            }
        }
    }
    // 展示用的时间基准。四级兜底，保证 firstWeekDate 恒非空 —— 星期条下面的日期不会整行消失。
    //
    //   1. 提醒日历里该学期已保存的第一周日期（用户在设置里选过，最权威）；
    //   2. **设置里存的第一周日期**（`settingsManager.semesterStartDate`）；
    //      这一层是上一版漏掉的：提醒日历的写入在 term 为空时会静默失败
    //      （`ScheduleCalendarStore.write` 对空白 term 直接 return false），
    //      那种情况下用户选的日期【只】落在 semesterStartDate 里，课表却没读它；
    //   3. 该学期在【本进程内】已经确定过的日期（ScheduleDates 的记忆，见其注释）；
    //   4. 按学期默认值推（秋季 9/1、春季 3/1 所在周周一，termId 推不出时按自然日历）。
    //
    // 第 3 层是"内存第二遍"：resolvedTermId 在冷启动首帧、快照恢复、切账号时会
    // 短暂退化成空串，第 4 层这时算出来的年份可能不对（1 月看春季课表），
    // 而且每次重组都重算、值不稳 —— 表现就是日期一会儿有一会儿没有。
    val settingsRevision = settingsManager.revision
    val displayedTimeBase = remember(resolvedTermId, remindersRevision, settingsRevision, settingsTerm) {
        val base = reminderScheduler.timeBase(routeAccountKey, resolvedTermId)
        // 老版本存过「任意星期几」的日期。ScheduleDates.date() 只认周一，
        // 直接喂进去会整行算不出日期 —— 先归一到所在周的周一再用。
        fun usable(raw: String?): String? = raw?.takeIf { it.isNotBlank() }
            ?.let { ScheduleDates.normalizeFirstWeekDate(it) }
        val storedMillis = settingsManager.semesterStartDate
        val storedDate = if (storedMillis > 0L) {
            usable(ScheduleTimeBase.dateFromMillis(storedMillis))
        } else {
            null
        }
        // 逐层取值时记下是哪一层接住的 —— 打进日志，日期对不上时一眼看出卡在哪。
        val source = when {
            usable(base?.firstWeekDate) != null -> "提醒日历"
            storedDate != null -> "semesterStartDate"
            ScheduleDates.rememberedDefaultFirstWeekDate(resolvedTermId) != null -> "进程记忆"
            else -> "学期推算"
        }
        val resolvedDate = usable(base?.firstWeekDate)
            ?: storedDate
            ?: ScheduleDates.rememberedDefaultFirstWeekDate(resolvedTermId)
            ?: ScheduleDates.resolveFirstWeekDate(resolvedTermId)
        // 记进进程记忆：后面若 termId 又空了，仍拿回同一个日期，不会闪。
        ScheduleDates.rememberFirstWeekDate(resolvedTermId, resolvedDate)
        // 诊断日志。tag 固定为 ScheduleDates，抓法：
        //   adb logcat -s ScheduleDates:*
        android.util.Log.i(
            "ScheduleDates",
            "课表日期基准: term='$resolvedTermId' firstWeekDate='$resolvedDate' 来源=$source " +
                "periodStarts=${settingsManager.semesterStartDate}"
        )
        when {
            base != null -> base.copy(firstWeekDate = resolvedDate)
            else -> ScheduleTimeBase(firstWeekDate = resolvedDate)
        }
    }
    // 把兜底算出来的日期**回写到该学期的日历**，让设置页显示的日期与课表一致，
    // 也让后台提醒规划拿到同一条基准（否则提醒会按空基准算不出触发点）。
    // 只回写"用户没设过"的情况，绝不覆盖用户的手动选择。
    LaunchedEffect(resolvedTermId, displayedTimeBase?.firstWeekDate) {
        val term = resolvedTermId
        val date = displayedTimeBase?.firstWeekDate.orEmpty()
        if (term.isBlank() || date.isBlank()) return@LaunchedEffect
        val base = reminderScheduler.timeBase(routeAccountKey, term)
        if (base == null) {
            reminderScheduler.updateTimeBase(routeAccountKey, term, ScheduleTimeBase(firstWeekDate = date))
        } else if (base.firstWeekDate.isBlank()) {
            reminderScheduler.updateTimeBase(routeAccountKey, term, base.copy(firstWeekDate = date))
        }
    }
    fun periodTimesFor(base: ScheduleTimeBase?): List<ScheduleSettingsManager.PeriodTime> = settingsManager.getPeriodTimes().map {
        it.copy(startTime = base?.periodStarts?.get(it.period) ?: it.startTime, endTime = base?.periodEnds?.get(it.period) ?: it.endTime)
    }
    val focusRegistry = remember { com.hnnujw.course.ui.screen.ScheduleFocusRegistry() }
    val reminderRequest = CourseReminderNavigation.requestedId
    LaunchedEffect(reminderRequest) {
        if (reminderRequest != null) {
            detailSourceBounds = null
            reminderScheduler.findById(reminderRequest)?.let {
                notificationCourseJson = ReminderJson.reminder(it).toString()
                detailId = it.course.id
            }
            CourseReminderNavigation.consume()
        }
    }

    // 从 Excel 导入课表：系统文件选择器 → 读字节 → 解析 → 落自定义课程。
    // 解析在 IO 线程做，落库与提示回主线程；失败给出具体原因。
    val excelImportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            val parsed = runCatching {
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: error("无法读取所选文件")
                val document = XlsxParser.parse(bytes)
                document.sheets.firstOrNull { it.rows.isNotEmpty() }?.rows
                    ?: error("表格里没有内容")
            }.mapCatching { grid -> ScheduleExcelIO.parseImported(grid) }
            launch(Dispatchers.Main) {
                val coursesToImport = parsed.getOrNull().orEmpty()
                when {
                    parsed.isFailure -> GlassToaster.show("导入失败：${parsed.exceptionOrNull()?.message ?: "文件无法识别"}")
                    coursesToImport.isEmpty() -> GlassToaster.show("表格里没有识别到课程，请检查表头")
                    else -> {
                        val existingKeys = courses.map {
                            "${ScheduleIdentity.colorKey(it.name)}|${it.day}|${it.startPeriod}|${it.endPeriod}|${ScheduleWeeks.canonical(it.weeks)}"
                        }.toMutableSet()
                        var added = 0
                        var skipped = 0
                        coursesToImport.forEach { course ->
                            val key = "${ScheduleIdentity.colorKey(course.name)}|${course.day}|${course.startPeriod}|${course.endPeriod}|${ScheduleWeeks.canonical(course.weeks)}"
                            if (key in existingKeys) {
                                skipped++
                                return@forEach
                            }
                            existingKeys.add(key)
                            val saved = ScheduleSettingsManager.CustomCourse(
                                id = java.util.UUID.randomUUID().toString(),
                                name = course.name, teacher = course.teacher, location = course.location,
                                day = course.day, startPeriod = course.startPeriod, endPeriod = course.endPeriod,
                                weeks = course.weeks.ifBlank { "1-25周" },
                            )
                            settingsManager.addCustomCourse(saved, routeAccountKey)
                            // 与手动添加一致：导入的课也要有提醒记录（默认关），详情页才能一键开启
                            reminderScheduler.updateCustomCourse(routeAccountKey, ScheduleCourseRecord(
                                "custom:${saved.id}", saved.name, saved.teacher, saved.location,
                                saved.day, saved.startPeriod, saved.endPeriod, saved.weeks, true))
                            added++
                        }
                        showSettingsDialog = false
                        GlassToaster.show(
                            if (skipped > 0) "已导入 $added 门课程（跳过 $skipped 门重复）" else "已导入 $added 门课程"
                        )
                    }
                }
            }
        }
    }

    val snapshotForCache = ScheduleRouteSnapshot(        savedAtMs = System.currentTimeMillis(),
        currentWeek = currentWeek,
        courses = courses,
        periodTimes = periodTimes,
        periodCount = periodCount,
        termId = resolvedTermId,
        appliedCalendar = appliedCalendar
    )
    val latestSnapshotForCache by rememberUpdatedState(snapshotForCache)
    val canCacheSnapshot by rememberUpdatedState(hasInitializedRoute && !isLoading && loadError.isBlank())
    DisposableEffect(routeAccountKey) {
        onDispose {
            if (canCacheSnapshot) {
                ScheduleRouteMemoryCache.put(routeAccountKey, latestSnapshotForCache)
            }
        }
    }
    
    // Colors
    // 调色板：足够多的区分度，保证一个学期内不同课程颜色不重复。
    val courseColorPalette = remember {
        listOf(
            Color(0xFF5C6BC0), Color(0xFF42A5F5), Color(0xFF66BB6A), Color(0xFFFFA726),
            Color(0xFFAB47BC), Color(0xFFEF5350), Color(0xFF26C6DA), Color(0xFF8D6E63),
            Color(0xFFEC407A), Color(0xFF9CCC65), Color(0xFFFF7043), Color(0xFF29B6F6),
            Color(0xFF7E57C2), Color(0xFF26A69A), Color(0xFFD4A017), Color(0xFFC0392B)
        )
    }

    /**
     * 根据课程名集合生成**稳定**的配色映射：
     * - 同名课程（经 [ScheduleIdentity.colorKey] 归一化）始终映射到同一颜色 —— 满足"相同课堂颜色一样"；
     * - 对当前课表所有不同课程名按确定性顺序排序后依次取调色板，只要不同课程数 ≤ 调色板大小，
     *   即可保证"每个课程颜色不同"（不再用哈希取模，避免撞色）。
     */
    fun buildCourseColorMap(names: List<String>): Map<String, Color> {
        val keys = names.map { ScheduleIdentity.colorKey(it) }.distinct().sorted()
        return keys.mapIndexed { index, key ->
            key to courseColorPalette[index % courseColorPalette.size]
        }.toMap()
    }

    /** 给整张课表重新上色：先汇总所有课程名，再统一分配，确保同课同色且互不相同。 */
    fun assignCourseColors(list: List<ScheduleCourseUi>): List<ScheduleCourseUi> {
        if (list.isEmpty()) return list
        val map = buildCourseColorMap(list.map { it.name })
        return list.map { it.copy(color = requireNotNull(map[ScheduleIdentity.colorKey(it.name)])) }
    }

    fun parseSchedule(json: String): List<ScheduleCourseUi>? = ScheduleJson.parse(json)?.map { entry ->
        val c = entry.course
        ScheduleCourseUi(c.name, c.teacher, c.location, c.day, c.startPeriod, c.endPeriod, c.weeks,
            Color.Unspecified, sourceId = entry.sourceId, id = c.id)
    }

    fun reloadCustomCourses(currentList: List<ScheduleCourseUi>): List<ScheduleCourseUi> {
        val customCourses = settingsManager.getCustomCourses(routeAccountKey)
        val customUi = customCourses.map { cc ->
            ScheduleCourseUi(
                name = cc.name, teacher = cc.teacher, location = cc.location, day = cc.day,
                startPeriod = cc.startPeriod, endPeriod = cc.endPeriod, weeks = cc.weeks,
                color = Color.Unspecified, isCustom = true, customId = cc.id
            )
        }
        val nonCustom = currentList.filter { !it.isCustom }
        // 【必须在这里统一上色】自定义课程是上面刚构造的，颜色是 [Color.Unspecified]；
        // 从教务拉回来的课程在 parseSchedule 里同样是 Unspecified。早期版本只在
        // "缓存命中 / 非协议直连" 两条分支里调 assignCourseColors，走
        // AcademicStudyBridge 那条主链路的课程就带着 Unspecified 一路渲染进课表——
        // Unspecified 是 Compose 的哨兵值，参与 lerp 后得到的就是黑块，
        // 于是"所有课程都是黑色、分辨不出是哪一门"。
        // 把上色收进这个唯一出口后，任何来源的课程都不可能再漏掉颜色。
        return assignCourseColors(nonCustom + customUi)
    }

    // Load Schedule Function
    val loadSchedule = remember(session.token) {
        fun(forceRefresh: Boolean) {
            if (isDemoMode) {
                resolvedTermId = DemoData.currentTerm.id
                courses = reloadCustomCourses(DemoData.scheduleCourses())
                isLoading = false
                return
            }
            val school = UserManager.getInstance().currentSchool
            if (school == null) return
            val ticket = requests.begin("schedule")
            studyLoadJob?.cancel()
            val generation = ++studyGeneration
            val account = UserManager.getInstance().currentAccountStorageKey
            val currentTerm = scheduleCache.currentTerm(account, school.id)
            // 课表只展示本学期：缓存的「下学期」分支已随学期切换一起移除。
            val requestedTerm = currentTerm
            val cached = scheduleCache.selected(account, school.id)
            loadError = ""
            if (!forceRefresh && cached != null) {
                courses = assignCourseColors(reloadCustomCourses(requireNotNull(parseSchedule(cached.json))))
                resolvedTermId = cached.term.id
                isLoading = false
                reminderScheduler.updateSnapshot(routeAccountKey, cached.term.id, courses.map { it.record() })
                return
            }
            // Refresh in place. Only a different semester must discard the previous rows.
            if (resolvedTermId != requestedTerm.id) courses = reloadCustomCourses(emptyList())
            val hasRetainedSchedule = cached != null || courses.isNotEmpty()
            isLoading = true

            if (AcademicGatewayFactory.supports(school)) {
                studyLoadJob = scope.launch {
                    try {
                        val loaded = withContext(Dispatchers.IO) {
                            scheduleCache.load(account, school.id, forceRefresh) {
                                AcademicStudyBridge.reader(school, account, ticket.session)
                            }
                        }
                        if (!requests.isCurrent(ticket) || studyGeneration != generation) return@launch
                        scheduleCache.save(account, school.id, loaded)
                        courses = reloadCustomCourses(requireNotNull(parseSchedule(loaded.json)))
                        resolvedTermId = loaded.term.id
                        reminderScheduler.updateSnapshot(routeAccountKey, loaded.term.id, courses.map { it.record() })
                        if (forceRefresh) GlassToaster.show("已同步课表")
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) {
                        if (requests.isCurrent(ticket) && studyGeneration == generation) {
                            val message = e.message ?: "课表同步失败，请重试"
                            if (hasRetainedSchedule) GlassToaster.show("同步失败，已保留本地课表：$message")
                            else loadError = message
                        }
                    } finally {
                        if (requests.isCurrent(ticket) && studyGeneration == generation) isLoading = false
                    }
                }
                return
            }
            
            val xnm = requestedTerm.year.toString()
            val xqm = if (requestedTerm.semester == 1) "3" else "12"
            fun isRequestAccountActive(): Boolean {
                return requests.isCurrent(ticket)
            }
            val requestTermId = requestedTerm.id
            resolvedTermId = requestTermId
            CourseApiClient.getInstance().fetchSchedule(school, "xnm=$xnm&xqm=$xqm", object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    scope.launch(Dispatchers.Main) {
                        if (!isRequestAccountActive()) return@launch
                        isLoading = false
                        if (hasRetainedSchedule) GlassToaster.show("同步失败，已保留本地课表")
                        else loadError = "加载失败：${e.message}"
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    val json = response.body?.string() ?: ""
                    if (json.contains("用户登录")) {
                         scope.launch(Dispatchers.Main) { 
                             if (!isRequestAccountActive()) return@launch
                             isLoading = false
                             if (!hasRetainedSchedule) loadError = "请先登录"
                             GlassToaster.show("请先登录") 
                         }
                        return
                    }
                    val parsed = if (response.isSuccessful) parseSchedule(json) else null
                    scope.launch(Dispatchers.Main) {
                        if (!isRequestAccountActive()) return@launch
                        isLoading = false
                        if (parsed != null) {
                            scheduleCache.save(account, school.id, CachedSchedule(currentTerm, requestedTerm, json, false))
                            courses = assignCourseColors(reloadCustomCourses(parsed))
                            reminderScheduler.updateSnapshot(routeAccountKey, requestTermId, courses.map { it.record() })
                            if (forceRefresh) GlassToaster.show("已刷新")
                        } else {
                            val message = "课表响应无效，请重试"
                            if (!hasRetainedSchedule) loadError = message
                            GlassToaster.show(message)
                        }
                    }
                }
            })
        }
    }

    // Init Effect
    LaunchedEffect(routeAccountKey) {
        if (restoredSnapshot == null) {
            periodCount = settingsManager.periodCount
            periodTimes = settingsManager.getPeriodTimes().map {
                PeriodTimeUi(it.period, it.startTime, it.endTime)
            }
        }
    }
    
    // 登录会话变化时重新加载（课表只展示本学期，不再有学期切换）
    LaunchedEffect(session.token) {
        if (skipFirstLoad) {
            skipFirstLoad = false
            return@LaunchedEffect
        }
        hasInitializedRoute = true
        loadSchedule(false)
    }

    // Refresh custom courses when dialogs close
    LaunchedEffect(customRevision, routeAccountKey) {
        courses = reloadCustomCourses(courses)
        periodCount = settingsManager.periodCount
    }
    LaunchedEffect(resolvedTermId) {
        if (resolvedTermId.isNotBlank()) {
            reminderScheduler.migrateLegacyTimeBase(routeAccountKey, resolvedTermId, ScheduleTimeBase(
                ScheduleTimeBase.dateFromMillis(settingsManager.semesterStartDate), periodTimes.associate { it.period to it.startTime },
                periodTimes.associate { it.period to it.endTime }))
        }
    }
    LaunchedEffect(displayedTimeBase) {
        periodTimes = periodTimesFor(displayedTimeBase).map { PeriodTimeUi(it.period, it.startTime, it.endTime) }
    }
    LaunchedEffect(resolvedTermId, displayedTimeBase?.firstWeekDate) {
        if (resolvedTermId.isBlank()) return@LaunchedEffect
        val calendar = "$resolvedTermId|${displayedTimeBase?.firstWeekDate.orEmpty()}"
        // Apply date changes when saved, including system-back dismissal. Retain a
        // browsed week across page restoration and unrelated reminder/time changes.
        if (appliedCalendar != calendar) {
            appliedCalendar = calendar
            currentWeek = ScheduleDates.weekAt(displayedTimeBase?.firstWeekDate, System.currentTimeMillis()) ?: 1
        }
    }
    LaunchedEffect(undoDeadline) {
        if (undoDeadline > 0) {
            kotlinx.coroutines.delay((undoDeadline - System.currentTimeMillis()).coerceAtLeast(0))
            deletedCourseJson = null
            deletedRemindersJson = "[]"
        }
    }
    com.hnnujw.course.ui.system.ReportPageContent(courses.isNotEmpty())
    Box(Modifier.fillMaxSize()) {
    CompositionLocalProvider(com.hnnujw.course.ui.screen.LocalScheduleFocus provides focusRegistry) {
    ScheduleScreen(
        currentWeek = currentWeek,
        courses = courses,
        isLoading = isLoading,
        errorMessage = loadError,
        onRetry = { loadSchedule(true) },
        periodTimes = periodTimes,
        periodCount = periodCount,
        firstWeekDate = displayedTimeBase?.firstWeekDate,
        weekRequestKey = "${appliedCalendar.orEmpty()}#$weekJumpSeq",
        onWeekChange = { currentWeek = it },
        onCourseClick = {
            notificationCourseJson = null
            // Freeze the tapped card before pager neighbours or sheet layout update their bounds.
            detailSourceBounds = focusRegistry.bounds(it.id)
            detailId = it.id
        },
        onSettingsClick = { settingsTermOverride = null; showSettingsDialog = true },
        // 入口带上"点击时正在看的周次"：补的课要落进**这一周**的这一格，
        // 而不是笼统的"周末列"（见 MakeUpCourseDialog 的语义说明）。
        onMakeUpFromWeekday = { day -> makeUpWeek = currentWeek; makeUpDay = day },
        onExportClick = {
            if (courses.isEmpty()) {
                GlassToaster.show("课表为空，无法导出")
            } else {
                showExportChooser = true
            }
        },
        dayView = dayView,
        onDayViewChange = { dayView = it; ScheduleDisplayStore.setDayView(context, it) },
        onSyncClick = { loadSchedule(true) },
        onAddClick = { editingId = java.util.UUID.randomUUID().toString() },
        onWidgetBoardClick = { showWidgetBoard = true },
        compact = scheduleCompact,
        showWeekend = scheduleShowWeekend,
        // 「回到今天」真正的消费端在 ScheduleScreen：它除了翻到本周，还要复位日视图停在哪一天。
        // 这里以前漏传了这个参数（默认值 0 会让消费端直接 return），于是 Route 里改了 currentWeek、
        // 但 pager 的 key（weekRequestKey）没变，翻页同步不动，桌面卡片 / 通知点进来仍留在上次那一周。
        todayRequest = todayNonce
    )
    }

    // 桌面课表卡片点按的「回到今天」：卡片是按当前账号渲染的，点开的意思就是"看现在"。
    // 光切到课表 Tab 不够 —— 课表页记着上次翻到第几周、日视图停在哪天，不复位就会
    // 落在三天前的那一页上。请求由 AppTabNavigation 与通知共用同一条通道（消费一次即清空）。
    val todayRequestAt = AppTabNavigation.requestedTodayAt
    LaunchedEffect(todayRequestAt) {
        if (todayRequestAt == null) return@LaunchedEffect
        ScheduleDates.weekAt(displayedTimeBase?.firstWeekDate, System.currentTimeMillis())?.let {
            currentWeek = it
        }
        todayNonce++
        AppTabNavigation.consumeToday()
    }

    // 桌面卡片点某一行（时间轴 / 今日课表的「下一节」）或「下一节课」卡片本身时，
    // 落到课表页之后还要打开**那一门课**的详情。
    //
    // 请求带的是卡片渲染那一刻的课程 id，用户可能已经换账号 / 换学期 / 重新同步过课表，
    // 所以要先确认它还在当前课表里。三种情况必须分开处理（判据见 decideCourseOpen）：
    // 命中就打开并清掉请求；课表还没加载完就**留着请求等下一轮**（否则表现为"点了没反应"）；
    // 加载完仍找不到才丢掉（否则它会在某次刷新后突然弹出一个跟当下操作无关的详情）。
    val courseOpenRequest = AppTabNavigation.requestedCourseId
    LaunchedEffect(courseOpenRequest, courses, isLoading) {
        when (decideCourseOpen(courseOpenRequest, courses.map { it.id }, isLoading)) {
            CourseOpenDecision.Open -> {
                notificationCourseJson = null
                // 不是从课表上的卡片点的，没有"源卡片"可做锚点动画。
                detailSourceBounds = null
                detailId = courseOpenRequest
                AppTabNavigation.consumeCourse()
            }

            CourseOpenDecision.Drop -> AppTabNavigation.consumeCourse()
            CourseOpenDecision.Wait, CourseOpenDecision.None -> Unit
        }
    }

    // ── 导出 ─────────────────────────────────────────────────────────────
    fun exportScheduleIcal() {
        try {
            val semesterStart = ScheduleDates.firstMonday(displayedTimeBase?.firstWeekDate)
            if (semesterStart == null) {
                GlassToaster.show("请先设置这个学期的第一周周一日期")
                settingsTermOverride = null
                showSettingsDialog = true
            } else {
                ICalExporter.exportAndShare(
                    context = context,
                    courses = courses,
                    semesterStartDate = semesterStart,
                    totalWeeks = ScheduleMaxWeeks,
                    periodTimes = periodTimes.associate { it.period to (it.startTime to it.endTime) }
                )
                GlassToaster.show("课表已导出，可导入到系统日历中查看")
            }
        } catch (e: Exception) {
            GlassToaster.show("导出失败：${e.message}")
        }
    }

    fun exportScheduleExcel() {
        try {
            val excelCourses = courses.map {
                ScheduleExcelCourse(it.name, it.teacher, it.location, it.day, it.startPeriod, it.endPeriod, it.weeks)
            }
            val file = ScheduleExcelIO.exportToCache(context, excelCourses)
            ScheduleExcelIO.shareWorkbook(context, file)
            GlassToaster.show("课表已导出为 Excel，可分享或用表格软件打开")
        } catch (e: Exception) {
            GlassToaster.show("导出失败：${e.message}")
        }
    }

    if (showExportChooser) {
        com.hnnujw.course.ui.system.SystemDialog(
            onDismissRequest = { showExportChooser = false },
            title = { Text("导出课表") },
            content = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SystemSecondaryButton(
                        text = "日历文件（.ics）",
                        onClick = { showExportChooser = false; exportScheduleIcal() },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    SystemSecondaryButton(
                        text = "Excel 表格（.xlsx）",
                        onClick = { showExportChooser = false; exportScheduleExcel() },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
        )
    }
    if (showWidgetBoard) {
        com.hnnujw.course.ui.route.WidgetBoardRoute(onDismiss = { showWidgetBoard = false })
    }
    if (makeUpDay != 0) {
        com.hnnujw.course.ui.screen.MakeUpCourseDialog(
            initialTargetDay = makeUpDay,
            initialTargetWeek = makeUpWeek,
            courses = courses,
            firstWeekDate = displayedTimeBase?.firstWeekDate,
            onDismiss = { makeUpDay = 0 },
            // 四个参数全部来自弹窗内的选择：源(星期, 周) 与 目标(星期, 周)。
            // 这里只照单执行，`makeUpDay` / `makeUpWeek` 是**初值**不是答案——
            // 拿初值当目标正是"默认决定了"的旧行为，必须改用回传来的参数。
            onConfirm = { sourceDay, sourceWeek, targetDay, targetWeek ->
                makeUpDay = 0
                val targetName = com.hnnujw.course.schedule.scheduleWeekdayShort(targetDay)
                // 「补课」= 把**源那一周那一天的课整体**搬到**目标那一周那一天**，
                // 不挑具体课程：该天该周每一门按各自原时段生成一条自定义课程。
                val sources = courses.filter {
                    !it.isCustom && it.day == sourceDay && isInWeek(it.weeks, sourceWeek)
                }
                if (sources.isEmpty()) {
                    GlassToaster.show("第 $sourceWeek 周${com.hnnujw.course.schedule.scheduleWeekdayLong(sourceDay)}没有课，没有可补的内容")
                } else {
                    sources.forEach { source ->
                        // 同一门课在同一格反复补课时**合并周次**而不是再堆一条：
                        // 否则课表上会出现多张完全重叠的卡片，看得见却点不中下面那张。
                        // `weeks` 是逗号分隔的周次列表（见 ScheduleWeeks.parse）。
                        val existing = settingsManager.getCustomCourses(routeAccountKey).firstOrNull {
                            it.day == targetDay &&
                                it.startPeriod == source.startPeriod &&
                                it.endPeriod == source.endPeriod &&
                                it.name == source.name &&
                                it.location == source.location
                        }
                        val newWeeks = if (existing == null) {
                            targetWeek.toString()
                        } else {
                            val merged = (com.hnnujw.course.schedule.ScheduleWeeks.parse(existing.weeks).weeks + targetWeek)
                                .sorted().joinToString(",")
                            merged
                        }
                        val course = ScheduleSettingsManager.CustomCourse(
                            id = existing?.id ?: java.util.UUID.randomUUID().toString(),
                            name = source.name,
                            location = source.location,
                            teacher = source.teacher,
                            day = targetDay,
                            startPeriod = source.startPeriod,
                            endPeriod = source.endPeriod,
                            weeks = newWeeks
                        )
                        settingsManager.addCustomCourse(course, routeAccountKey)
                        // 自定义课程默认**不排提醒**（用户可能只是在课表上记一笔）。
                        // 但"补课"是明确要上的课，提醒没挂上就等于白补，所以这里补一条
                        // 关闭状态的提醒记录——它只让用户在详情页里能一键打开，
                        // 不会自作主张地推送通知。
                        reminderScheduler.updateCustomCourse(routeAccountKey, ScheduleCourseRecord(
                            "custom:${course.id}", course.name, course.teacher, course.location,
                            course.day, course.startPeriod, course.endPeriod, course.weeks, true))
                    }
                    // 把课补进了别的周 → 课表 pager 跳到那一格，免得用户改了却看不到。
                    currentWeek = targetWeek
                    weekJumpSeq++
                    GlassToaster.show(
                        "已补课：第 $sourceWeek 周" +
                            com.hnnujw.course.schedule.scheduleWeekdayLong(sourceDay) +
                            " ${sources.size} 门 → 第 $targetWeek 周$targetName"
                    )
                }
            }
        )
    }
    if (deletedCourseJson != null) {
        androidx.compose.material3.Snackbar(
            modifier = Modifier.align(Alignment.BottomCenter).padding(horizontal = 16.dp)
                .padding(bottom = com.hnnujw.course.ui.system.LocalAppOverlayBottomInset.current + 12.dp),
            action = {
                androidx.compose.material3.TextButton(onClick = {
                    if (System.currentTimeMillis() < undoDeadline) {
                        val record = deletedCourseJson?.let { ReminderJson.course(JSONObject(it)) }
                        if (record != null) {
                            settingsManager.updateCustomCourse(ScheduleSettingsManager.CustomCourse(record.id.removePrefix("custom:"), record.name,
                                record.location, record.teacher, record.day, record.startPeriod, record.endPeriod, record.weeks), routeAccountKey)
                            reminderScheduler.restoreUndo(deletedRemindersJson)
                        }
                    }
                    deletedCourseJson = null
                }) { Text("撤销") }
            }
        ) { Text("已删除课程") }
    }
    }
    
    if (showSettingsDialog) {
        com.hnnujw.course.ui.system.GlassSubpage(onDismiss = {
            showSettingsDialog = false
            settingsTermOverride = null
            showReminderSettings = false
        }) { close ->
            // 课表设置与课程提醒设置共用一个子页窗口，靠 showReminderSettings 在两页间切换。
            // 这样提醒页的返回箭头是回到课表设置，而不是把整层子页关掉。
            if (showReminderSettings) {
                com.hnnujw.course.ui.screen.ReminderSettingsScreen(
                    scheduler = reminderScheduler,
                    onBack = { showReminderSettings = false }
                )
            } else {
            // 下面这一大块保持原缩进不动：为了嵌进 else 而重排 60 多行只会让 diff 没法看。
            ScheduleSettingsScreen(
                manager = settingsManager,
                periodTimesOverride = periodTimesFor(termTimeBase),
                semesterStartOverride = termTimeBase?.firstWeekDate?.takeIf { it.isNotBlank() }?.let {
                    runCatching { java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).parse(it)?.time }.getOrNull()
                } ?: 0L,
                onSemesterStartChange = { millis ->
                    if (settingsTermOverride == null) settingsManager.semesterStartDate = millis
                    val times = periodTimesFor(termTimeBase)
                    reminderScheduler.updateTimeBase(routeAccountKey, settingsTerm, ScheduleTimeBase(
                        ScheduleTimeBase.dateFromMillis(millis), times.associate { it.period to it.startTime }, times.associate { it.period to it.endTime }))
                },
                onPeriodTimesChange = { times ->
                    if (settingsTermOverride == null) settingsManager.savePeriodTimes(times)
                    reminderScheduler.updateTimeBase(routeAccountKey, settingsTerm,
                        (reminderScheduler.timeBase(routeAccountKey, settingsTerm) ?: ScheduleTimeBase()).copy(
                            periodStarts = times.associate { it.period to it.startTime }, periodEnds = times.associate { it.period to it.endTime }))
                },
                customCourses = customCourses,
                onAddCustomCourse = { editingId = java.util.UUID.randomUUID().toString() },
                onEditCustomCourse = { editingId = it },
                onDeleteCustomCourse = { id ->
                    // 与课程详情里的删除走同一条路：先摘掉提醒记录，再删设置里的课程。
                    // 只删 store 不清提醒的话，课表上课程没了、通知还在到点响。
                    reminderScheduler.removeCourse(routeAccountKey, "custom:$id")
                    settingsManager.removeCustomCourse(id, routeAccountKey)
                },
                onSyncSchedule = {
                    close()
                    loadSchedule(true)
                },
                onImportExcel = { excelImportLauncher.launch(arrayOf(
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    "application/vnd.ms-excel",
                )) },
                // 补课入口：设置页只在「隐藏周末」时显示这一行，而那个条件在**设置页内部**
                // 读它自己的本地状态。所以这里恒定传回调，不按开关传 null ——
                // 否则拨到「隐藏」的那一瞬间回调仍是 null，这一行渲染不出来。
                onMakeUpCourse = {
                    makeUpWeek = currentWeek
                    // 默认落周六；弹窗里还能改成周日、以及"源"是哪一个星期。
                    makeUpDay = 6
                    // 必须先关掉设置页：补课弹窗渲染在 Route 这一层，
                    // 而设置页是压在它上面的独立窗口，不关就看不见弹窗。
                    close()
                },
                // 提醒设置单独一页，行上显示当前状态（总开关/提前量/自动模式）。
                onOpenReminderSettings = { showReminderSettings = true },
                reminderSummary = reminderSummary,
                effectiveFirstWeekDate = displayedTimeBase?.firstWeekDate,
                // 展示偏好（显示密度 / 显示周末）：本页只发意图，落盘与状态更新都在这里做
                // —— 与 dayView 同一条写法。设置页自己改 store 会绕开 Route 的内存状态，
                // 表现就是"设置页选了、退回来课表没变"。
                displayCompact = scheduleCompact,
                onDisplayCompactChange = {
                    scheduleCompact = it
                    ScheduleDisplayStore.setCompact(context, it)
                },
                showWeekend = scheduleShowWeekend,
                onShowWeekendChange = {
                    scheduleShowWeekend = it
                    ScheduleDisplayStore.setShowWeekend(context, it)
                },
                onClose = {
                    periodCount = settingsManager.periodCount
                    periodTimes = periodTimesFor(displayedTimeBase).map { PeriodTimeUi(it.period, it.startTime, it.endTime) }
                    close()
                }
            )
            }
        }
    }
    
    val notificationCourse = notificationCourseJson?.let { runCatching { ReminderJson.reminder(JSONObject(it)) }.getOrNull() }
    val detailTerm = notificationCourse?.key?.term ?: resolvedTermId
    val selectedDetail = courses.firstOrNull { it.id == detailId && detailTerm == resolvedTermId } ?: notificationCourse?.course?.let {
        ScheduleCourseUi(it.name, it.teacher, it.location, it.day, it.startPeriod, it.endPeriod, it.weeks,
            courseColorPalette[ScheduleIdentity.colorIndex(ScheduleIdentity.colorKey(it.name), courseColorPalette.size)], it.custom, if (it.custom) it.id.removePrefix("custom:") else "", id = it.id)
    }
    selectedDetail?.let { course ->
        com.hnnujw.course.ui.screen.ScheduleCourseSheet(course, routeAccountKey, detailTerm, if (detailTerm == resolvedTermId) courses else listOf(course),
            sourceCenterX = detailSourceBounds?.center?.x,
            sourceBounds = detailSourceBounds,
            onDismiss = { detailId = null; detailSourceBounds = null; notificationCourseJson = null; focusRegistry.restore(course.id) },
            onEdit = { editingId = course.customId },
            onConfigureTime = { settingsTermOverride = detailTerm; showSettingsDialog = true },
            onDelete = {
                deletedCourseJson = ReminderJson.course(course.record()).toString()
                deletedRemindersJson = reminderScheduler.encodeUndo(reminderScheduler.removeCourse(routeAccountKey, course.id))
                undoDeadline = System.currentTimeMillis() + 5000L
                settingsManager.removeCustomCourse(course.customId, routeAccountKey)
                detailId = null
                detailSourceBounds = null
                notificationCourseJson = null
                focusRegistry.restore(course.id)
            })
    }
    editingId?.let { id ->
        val initial = customCourses.firstOrNull { it.id == id } ?: ScheduleSettingsManager.CustomCourse(
            id, "", "", "", 1, 1, 2, "1-16周")
        com.hnnujw.course.ui.system.GlassSubpage(onDismiss = { editingId = null }) { close ->
            com.hnnujw.course.ui.screen.ScheduleCourseEditor(initial, courses, periodCount,
                isNew = customCourses.none { it.id == id }, onClose = close, onSave = { saved ->
                    settingsManager.updateCustomCourse(saved, routeAccountKey)
                    reminderScheduler.updateCustomCourse(routeAccountKey, ScheduleCourseRecord("custom:${saved.id}", saved.name,
                        saved.teacher, saved.location, saved.day, saved.startPeriod, saved.endPeriod, saved.weeks, true))
                    notificationCourse?.key?.storageId?.let { reminderScheduler.findById(it) }?.let {
                        notificationCourseJson = ReminderJson.reminder(it).toString()
                    }
                    close()
                })
        }
    }
}
