package com.hnnujw.course.ui.screen

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.material.icons.outlined.BatterySaver
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.DoNotDisturb
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.hnnujw.course.MainActivity
import com.hnnujw.course.schedule.AutoModeKind
import com.hnnujw.course.schedule.LEAD_MINUTE_OPTIONS
import com.hnnujw.course.schedule.ScheduleReminderScheduler
import com.hnnujw.course.schedule.leadMinutesLabel
import com.hnnujw.course.schedule.leadMinutesOptionLabel
import com.hnnujw.course.schedule.sanitizeLeadMinutes
import com.hnnujw.course.ui.system.GlassOptionWheelDialog
import com.hnnujw.course.ui.system.GlassPageScaffold
import com.hnnujw.course.ui.system.GlassToaster
import com.hnnujw.course.ui.system.InsetGroupedRow
import com.hnnujw.course.ui.system.InsetGroupedSection
import com.hnnujw.course.ui.system.LiquidSwitch
import com.hnnujw.course.ui.system.PagePadding
import com.hnnujw.course.ui.system.SectionSpacing
import com.hnnujw.course.ui.system.SystemConfirmDialog
import com.hnnujw.course.ui.theme.NeuPrimary

/**
 * 课程提醒设置页。
 *
 * 这一页把原本散落的三件事收到一起：**总开关 / 提前量**这两个真正影响"提醒长什么样"的偏好，
 * 以及**四项权限自检**。权限自检是这一页存在的主要理由 —— 提醒不响时用户没有任何线索，
 * 而原因九成是"通知没授权 / 精确闹钟没授权 / 应用被电池优化杀掉了"这三件里的一件。
 *
 * 与「课表设置」页的关系：本页是它的下级页，由 `ScheduleRoute` 在同一个子页窗口里切换渲染，
 * 所以返回箭头回到课表设置而不是关掉整层。
 *
 * @param scheduler 提醒调度器。本页只发出意图，落盘与重新排闹钟都由它负责 ——
 *        页面自己写偏好会漏掉 `reconcile()`，于是"设置改了、闹钟还是老样子"。
 */
@Composable
fun ReminderSettingsScreen(
    scheduler: ScheduleReminderScheduler,
    onBack: () -> Unit
) {
    val context = LocalContext.current

    var settings by remember { mutableStateOf(scheduler.settings()) }
    var showLeadPicker by remember { mutableStateOf(false) }
    var showAutoModePicker by remember { mutableStateOf(false) }
    var showPermissionGuide by remember { mutableStateOf(false) }
    /** 批量开关进行中：防重复点击。 */
    var busy by remember { mutableStateOf(false) }

    // 权限状态不是可观察的：从系统设置页回来、或刚弹完运行时授权框，都要重新读一遍。
    // 少这一下的话，用户明明授了权，页面还写着"未授权"。
    var permissionTick by remember { mutableIntStateOf(0) }
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) permissionTick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { permissionTick++ }

    val permissions = remember(permissionTick, settings) { scheduler.permissions() }
    val dndGranted = remember(permissionTick) { scheduler.hasDndAccess() }
    val batteryExempt = remember(permissionTick) { isIgnoringBatteryOptimizations(context) }

    // 同步外部值：调度器在别处（课程详情页、账号切换）也可能改了这些值。
    LaunchedEffect(permissionTick) { settings = scheduler.settings() }

    // 缺权限时的跳转目标。与课程详情页的 onPermission 同一套判断顺序：
    // 先补通知（运行时权限，能就地弹），再补精确闹钟（只能跳系统页），最后退回应用通知设置。
    val openPermissionTarget: () -> Unit = {
        if (!permissions.notifications && Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else if (!permissions.exactAlarms && Build.VERSION.SDK_INT >= 31) {
            openSystemSettings(
                context,
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))
            )
        } else {
            MainActivity.openNotificationSettings(context)
        }
    }

    val scrollState = rememberScrollState()
    val headerCollapse by remember {
        derivedStateOf { (scrollState.value / 96f).coerceIn(0f, 1f) }
    }

    // ── 本学期课程：批量开关（第 15 轮） ────────────────────────────────
    //
    // 数据源是课表快照（Scheduler 里 updateSnapshot 存的那份）。用户还没打开过课表时
    // 这份快照是 null，此时两个按钮要禁用并说明原因，而不是给个点了没反应的按钮。
    val account = remember(scheduler, permissionTick) {
        com.hnnujw.course.manager.UserManager.getInstance().let {
            if (it.isLoggedIn && !it.isDemoMode) it.currentAccountStorageKey else ""
        }
    }
    var semesterRevision by remember { mutableIntStateOf(0) }
    // 快照 + 概况一起重算：revision 变化（本页操作后）、账号变化或权限/总开关变化时都刷新。
    // 用一个局部 data class 装住三元组，避免在渲染处反复解 Pair（可读性 + 少踩类型错）。
    data class SemesterState(
        val term: String,
        val courses: List<com.hnnujw.course.schedule.ScheduleCourseRecord>,
        val summary: com.hnnujw.course.schedule.SemesterReminderPlan.SemesterReminderSummary
    )
    val semesterData: SemesterState? = remember(account, permissionTick, semesterRevision, settings.masterEnabled) {
        val snapshot = if (account.isBlank()) null else scheduler.semesterCourses(account)
        if (snapshot == null) {
            null
        } else {
            SemesterState(
                term = snapshot.first,
                courses = snapshot.second,
                summary = scheduler.semesterSummary(account, snapshot.first, snapshot.second)
            )
        }
    }
    val summary = semesterData?.summary
    val toggleAvailability = summary?.let {
        com.hnnujw.course.schedule.SemesterReminderPlan.toggleAvailability(it, settings.masterEnabled)
    }

    val reminderFooter = when {
        !settings.masterEnabled ->
            "总开关关闭时所有课程提醒都不会响，逐门课程里的开关也一并失效。"
        settings.autoMode != AutoModeKind.Off && !dndGranted ->
            "上课自动模式选了「${settings.autoMode.label}」，但还没有「勿扰模式权限」，不会生效 —— 请在下方的权限自检里授予。"
        else -> null
    }

    GlassPageScaffold(
        title = "课程提醒设置",
        subtitle = "总开关、提前量与权限自检",
        onBack = onBack,
        collapseFraction = headerCollapse
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(padding)
                .padding(start = PagePadding, end = PagePadding, top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(SectionSpacing)
        ) {
            InsetGroupedSection(header = "提醒", footer = reminderFooter) {
                InsetGroupedRow(
                    icon = Icons.Outlined.NotificationsActive,
                    iconTint = Color(0xFF0A84FF),
                    title = "课程提醒",
                    subtitle = "总开关；关闭后不再排任何上课提醒",
                    trailing = {
                        LiquidSwitch(
                            checked = settings.masterEnabled,
                            onCheckedChange = { target ->
                                if (target && !permissions.available) {
                                    // 没有权限就排不出闹钟，开了也只是一句空承诺 ——
                                    // 先把用户引到权限上，而不是让他以为已经生效。
                                    showPermissionGuide = true
                                } else {
                                    settings = settings.copy(masterEnabled = target)
                                    scheduler.setMasterEnabled(target)
                                }
                            }
                        )
                    }
                )
                InsetGroupedRow(
                    icon = Icons.Outlined.AccessTime,
                    iconTint = Color(0xFFFF9F0A),
                    title = "提前提醒时间",
                    subtitle = "在每节课开始前多久提醒你",
                    trailing = {
                        Text(
                            text = leadMinutesLabel(settings.leadMinutes),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = NeuPrimary
                        )
                    },
                    onClick = { showLeadPicker = true }
                )
                InsetGroupedRow(
                    icon = Icons.Outlined.Bedtime,
                    iconTint = Color(0xFF5E5CE6),
                    title = "上课自动模式",
                    subtitle = settings.autoMode.description,
                    trailing = {
                        Text(
                            text = settings.autoMode.label,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = NeuPrimary
                        )
                    },
                    showDivider = false,
                    onClick = { showAutoModePicker = true }
                )
            }

            InsetGroupedSection(
                header = "本学期课程",
                footer = when {
                    account.isBlank() -> "登录后可为整学期课程一键开关提醒。"
                    summary == null -> "还没有本学期课表快照，请先在课表页加载本学期课程，再回来一键设置。"
                    else -> "一键仅作用于本学期的 ${summary.totalCount} 门课；单门可在课程详情里单独调整。"
                }
            ) {
                val state = semesterData
                val summary = state?.summary
                if (state != null && summary != null) {
                    InsetGroupedRow(
                        icon = Icons.Outlined.NotificationsActive,
                        iconTint = Color(0xFF0A84FF),
                        title = "已开启 ${summary.enabledCount} / ${summary.totalCount} 门",
                        subtitle = com.hnnujw.course.schedule.SemesterReminderPlan
                            .availabilityBreakdown(summary)
                            .ifBlank { "全部课程均已就绪" },
                        showDivider = true
                    )
                    // 「待授权」时给一个一键跳权限的入口 —— 复用本页已有的权限自检目标，
                    // 不新造一个权限面板。
                    val needsPermission = summary.byAvailability[
                        com.hnnujw.course.schedule.ReminderAvailability.NeedsPermission
                    ] ?: 0
                    SemesterToggleButtons(
                        availability = toggleAvailability,
                        needsPermissionCount = needsPermission,
                        busy = busy,
                        onEnable = {
                            busy = true
                            scheduler.setAllEnabled(account, state.term, state.courses, true)
                            semesterRevision++
                            GlassToaster.show("已开启本学期全部课程提醒")
                            busy = false
                        },
                        onDisable = {
                            busy = true
                            scheduler.setAllEnabled(account, state.term, state.courses, false)
                            semesterRevision++
                            GlassToaster.show("已关闭本学期全部课程提醒")
                            busy = false
                        },
                        onGrantPermission = openPermissionTarget
                    )
                } else {
                    InsetGroupedRow(
                        icon = Icons.Outlined.NotificationsActive,
                        iconTint = Color(0xFF8E8E93),
                        title = "暂无本学期课表",
                        subtitle = if (account.isBlank()) "登录后可一键设置" else "先在课表页加载本学期课程",
                        showDivider = false
                    )
                }
            }

            InsetGroupedSection(
                header = "权限自检",
                footer = "这些权限系统不允许应用自己打开，需要你手动授予。点任意一行可直接跳到对应设置页；" +
                    "其中任何一项没开，提醒都可能不准时甚至完全不响。"
            ) {
                InsetGroupedRow(
                    icon = Icons.Outlined.Notifications,
                    iconTint = Color(0xFFFF3B30),
                    title = "通知权限",
                    subtitle = "没有它，提醒一条都发不出来",
                    trailing = { StatusText(on = permissions.notifications, onText = "已开启", offText = "已关闭") },
                    onClick = openPermissionTarget
                )
                if (Build.VERSION.SDK_INT >= 31) {
                    InsetGroupedRow(
                        icon = Icons.Outlined.Alarm,
                        iconTint = Color(0xFFFF9F0A),
                        title = "精确闹钟权限",
                        subtitle = "Android 12 起必须授予，否则闹钟排不出来",
                        trailing = { StatusText(on = permissions.exactAlarms, onText = "已开启", offText = "已关闭") },
                        onClick = {
                            openSystemSettings(
                                context,
                                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))
                            )
                        }
                    )
                }
                InsetGroupedRow(
                    icon = Icons.Outlined.DoNotDisturb,
                    iconTint = Color(0xFF5E5CE6),
                    title = "勿扰模式权限",
                    subtitle = "上课自动模式需要它；没有它不会去切勿扰",
                    trailing = { StatusText(on = dndGranted, onText = "已授权", offText = "未授权") },
                    onClick = {
                        openSystemSettings(context, Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
                    }
                )
                InsetGroupedRow(
                    icon = Icons.Outlined.Autorenew,
                    iconTint = Color(0xFF34C759),
                    title = "后台运行和自启",
                    subtitle = "部分 ROM 需要在应用详情页里允许自启动与后台运行",
                    onClick = {
                        openSystemSettings(
                            context,
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                        )
                    }
                )
                InsetGroupedRow(
                    icon = Icons.Outlined.BatterySaver,
                    iconTint = Color(0xFF30D158),
                    title = "忽略电池优化",
                    subtitle = "被省电策略限制时闹钟会被推迟或吞掉",
                    trailing = { StatusText(on = batteryExempt, onText = "已加入", offText = "未加入") },
                    showDivider = false,
                    onClick = {
                        openSystemSettings(
                            context,
                            Intent(
                                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Uri.parse("package:${context.packageName}")
                            )
                        )
                    }
                )
            }
        }
    }

    if (showLeadPicker) {
        val options = LEAD_MINUTE_OPTIONS
        GlassOptionWheelDialog(
            title = "提前提醒时间",
            options = options.map { leadMinutesOptionLabel(it) },
            selectedIndex = options.indexOf(sanitizeLeadMinutes(settings.leadMinutes)).coerceAtLeast(0),
            onConfirm = { index ->
                val minutes = options[index.coerceIn(0, options.lastIndex)]
                settings = settings.copy(leadMinutes = minutes)
                scheduler.setLeadMinutes(minutes)
                showLeadPicker = false
            },
            onDismiss = { showLeadPicker = false }
        )
    }

    if (showAutoModePicker) {
        val kinds = AutoModeKind.entries
        GlassOptionWheelDialog(
            title = "上课自动模式",
            options = kinds.map { it.label },
            selectedIndex = kinds.indexOf(settings.autoMode).coerceAtLeast(0),
            onConfirm = { index ->
                val kind = kinds[index.coerceIn(0, kinds.lastIndex)]
                settings = settings.copy(autoMode = kind)
                scheduler.setAutoMode(kind)
                showAutoModePicker = false
                when {
                    kind == AutoModeKind.Off -> Unit
                    !settings.masterEnabled ->
                        GlassToaster.show("请先打开「课程提醒」总开关，自动模式才会生效")
                    !dndGranted ->
                        GlassToaster.show("还缺「勿扰模式权限」，请在下方的权限自检里授予")
                }
            },
            onDismiss = { showAutoModePicker = false }
        )
    }

    if (showPermissionGuide) {
        val missing = when {
            !permissions.notifications && !permissions.exactAlarms -> "通知权限和精确闹钟权限"
            !permissions.notifications -> "通知权限"
            else -> "精确闹钟权限"
        }
        SystemConfirmDialog(
            title = "还差一步权限",
            text = "提醒要靠系统通知和精确闹钟送达，现在还没拿到$missing。这样排不出闹钟，" +
                "打开总开关也不会有提醒。点「去开启」按提示授予即可。",
            confirmText = "去开启",
            onConfirm = {
                showPermissionGuide = false
                openPermissionTarget()
            },
            onDismiss = { showPermissionGuide = false }
        )
    }
}

/**
 * 「本学期课程」区块的两个批量按钮。
 *
 * 按钮可用性完全由纯函数 [com.hnnujw.course.schedule.SemesterReminderPlan.ToggleAvailability]
 * 决定，这里只按结果渲染 —— 判断逻辑不许长在 Composable 里（项目红线）。
 *
 * 「待授权」时额外给一个跳权限的入口，复用本页已有的 `openPermissionTarget`。
 */
@Composable
private fun SemesterToggleButtons(
    availability: com.hnnujw.course.schedule.SemesterReminderPlan.ToggleAvailability?,
    needsPermissionCount: Int,
    busy: Boolean,
    onEnable: () -> Unit,
    onDisable: () -> Unit,
    onGrantPermission: () -> Unit
) {
    androidx.compose.foundation.layout.Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // 缺权限时先把用户引到权限上：开了也排不出闹钟，不如明说。
        if (needsPermissionCount > 0) {
            com.hnnujw.course.ui.system.SystemSecondaryButton(
                text = "有 $needsPermissionCount 门课待授权，去开启",
                onClick = onGrantPermission,
                modifier = Modifier.fillMaxWidth()
            )
        }
        when (availability) {
            is com.hnnujw.course.schedule.SemesterReminderPlan.ToggleAvailability.Ready -> {
                androidx.compose.foundation.layout.Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    com.hnnujw.course.ui.system.SystemPrimaryButton(
                        text = "全部开启",
                        onClick = onEnable,
                        enabled = availability.canEnable && !busy,
                        modifier = Modifier.weight(1f)
                    )
                    com.hnnujw.course.ui.system.SystemSecondaryButton(
                        text = "全部关闭",
                        onClick = onDisable,
                        enabled = availability.canDisable && !busy,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            is com.hnnujw.course.schedule.SemesterReminderPlan.ToggleAvailability.MasterOff -> {
                // 总开关关着：给出原因，而不是两个灰按钮让人猜。
                Text(
                    text = "提醒总开关已关闭，批量开关不会生效。请先打开上方的「课程提醒」。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                com.hnnujw.course.ui.system.SystemSecondaryButton(
                    text = "全部关闭",
                    onClick = onDisable,
                    enabled = false,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            is com.hnnujw.course.schedule.SemesterReminderPlan.ToggleAvailability.NoSnapshot,
            null -> {
                // 无快照：禁用并已在区块 footer 说明原因
                com.hnnujw.course.ui.system.SystemPrimaryButton(
                    text = "全部开启",
                    onClick = onEnable,
                    enabled = false,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

/** 权限状态文本。开/关用同一套字重与颜色，避免"未授权"看着像错误。 */
@Composable
private fun StatusText(on: Boolean, onText: String, offText: String) {    Text(
        text = if (on) onText else offText,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Medium,
        color = if (on) NeuPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/**
 * 跳系统设置页。
 *
 * 个别 ROM 把这些页面裁掉了，`startActivity` 会抛 ActivityNotFoundException；
 * 退到应用详情页至少能让用户自己找到入口，而不是点了没反应。
 */
private fun openSystemSettings(context: Context, intent: Intent) {
    if (runCatching { context.startActivity(intent) }.isSuccess) return
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
        )
    }
}

/** 是否已在电池优化白名单里。取不到 PowerManager 时按"未加入"显示，宁可多提示一次。 */
private fun isIgnoringBatteryOptimizations(context: Context): Boolean =
    context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true
