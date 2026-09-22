package com.hnnujw.course

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Paint
import android.graphics.Picture
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.hnnujw.course.utils.SessionRenewer
import com.hnnujw.course.utils.RecoveryPhase
import com.hnnujw.course.ui.system.SessionNoticeViewModel
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hnnujw.course.ui.system.PageDataViewModel
import com.hnnujw.course.ui.system.LocalPageDataState
import com.hnnujw.course.ui.system.NavScrollIntent
import com.hnnujw.course.ui.system.AppSymbolSpec
import com.hnnujw.course.ui.system.GlassOverlayHost
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.hnnujw.course.activation.ActivationManager
import com.hnnujw.course.manager.AppearanceSettingsManager
import com.hnnujw.course.manager.SmartSelector
import com.hnnujw.course.manager.UserManager
import com.hnnujw.course.ui.screen.OnboardingDialog
import com.hnnujw.course.ui.system.CapsuleNavigationBar
import com.hnnujw.course.ui.system.DialogHost
import com.hnnujw.course.ui.system.GlassToastHost
import com.hnnujw.course.ui.system.GlassToaster
import com.hnnujw.course.ui.system.LocalAppBackdrop
import com.hnnujw.course.ui.system.drawWallpaperPattern
import com.hnnujw.course.ui.system.LocalAppOverlayBottomInset
import com.hnnujw.course.ui.system.LocalDialogHost
import com.hnnujw.course.ui.system.LocalModalBackdrop
import com.hnnujw.course.ui.system.LocalFloatingNotice
import com.hnnujw.course.ui.system.LocalNoticeAnchor
import com.hnnujw.course.ui.system.NoticeAnchorState
import com.hnnujw.course.ui.system.LocalControlBackdrop
import com.hnnujw.course.ui.system.FloatingNotice
import com.hnnujw.course.ui.system.FloatingNoticeHost
import com.hnnujw.course.ui.system.PagePadding
import com.hnnujw.course.ui.system.isBackdropSupported
import com.hnnujw.course.ui.system.rememberDialogHostState
import com.hnnujw.course.ui.system.rememberGlassAccessibilityMode
import com.hnnujw.course.ui.system.glass.glassLensAnchor
import com.hnnujw.course.ui.system.glass.drawBackdropSource
import androidx.compose.ui.platform.LocalDensity
import com.hnnujw.course.ui.theme.CourseSelectorTheme
import com.hnnujw.course.ui.theme.MotionEasing
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.shadow.Shadow
import androidx.compose.ui.platform.LocalContext
import com.hnnujw.course.ui.system.SystemDialog
import com.hnnujw.course.ui.system.SystemPrimaryButton
import com.hnnujw.course.ui.system.SystemSecondaryButton
import kotlinx.coroutines.delay
import com.hnnujw.course.manager.StartupPage
import com.hnnujw.course.manager.StartupPagePreferences
import com.hnnujw.course.ui.system.GlassRecipe
import com.hnnujw.course.ui.system.glass.drawBlurred

class MainActivity : FragmentActivity() {
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(com.hnnujw.course.manager.AppThemeCoordinator.wrapContext(newBase))
    }

    companion object {
        private const val PREFS_NAME = "app_prefs"
        private const val KEY_HAS_SEEN_ONBOARDING = "has_seen_onboarding"
        /** 已经自动申请过一次通知权限；用户拒绝后不再打扰，改由「我的」的通知行引导。 */
        const val KEY_NOTIFICATION_ASKED = "notification_permission_asked"

        /** 通知点击时携带：期望落地的 StartupPage route。 */
        const val EXTRA_OPEN_TAB = "com.hnnujw.course.extra.OPEN_TAB"

        /**
         * 通知点击时携带：落到「成绩」页后再选中哪个子页（0 学期 / 1 总览 / 2 考试）。
         *
         * 考前提醒必须能直接翻到「考试」：否则用户点开提醒看到的是学期成绩，
         * 还得自己再点一下 —— 而这正是提醒最该省掉的那一步。
         */
        const val EXTRA_OPEN_GRADES_TAB = "com.hnnujw.course.extra.OPEN_GRADES_TAB"

        /** 当前是否允许发系统通知（Android 13 以下默认允许）。 */
        fun notificationsAllowed(context: Context): Boolean =
            Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

        /**
         * 跳到本应用的「通知」设置页。
         *
         * Android 8+ 可直达通知频道页；更早的系统没有这个入口，退到应用详情页让用户自己找。
         * 用 [runCatching] 兜住个别 ROM 找不到 Activity 的情况，不做无谓崩溃。
         */
        fun openNotificationSettings(context: Context) {
            val intent = if (Build.VERSION.SDK_INT >= 26) {
                Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
            } else {
                Intent(
                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.parse("package:${context.packageName}")
                )
            }
            runCatching { context.startActivity(intent) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        UserManager.getInstance().init(this)
        if (savedInstanceState == null) {
            com.hnnujw.course.schedule.CourseReminderNavigation.accept(intent)
            AppTabNavigation.accept(intent)
            // 扫到 qutuo:// 码或点开二课站点链接时由系统发 VIEW intent 进来
            com.hnnujw.course.secondclass.SecondClassDeepLinkNavigation.accept(intent)
        }

        val userManager = UserManager.getInstance()
        if (BuildConfig.UI_PREVIEW) {
            userManager.startDemoSession(com.hnnujw.course.demo.DemoData.school())
        }

        SmartSelector.getInstance().init(this)
        com.hnnujw.course.network.CourseApiClient.getInstance().init(this)

        if (!userManager.isLoggedIn && !(userManager.hasSavedCookie() && userManager.sessionState.state.value.expired)) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val hasSeenOnboarding = prefs.getBoolean(KEY_HAS_SEEN_ONBOARDING, false)

        setContent {
            CourseSelectorTheme {
                var showOnboarding by remember { mutableStateOf(!hasSeenOnboarding && !userManager.isDemoMode) }

                // 【开源构建：永久免费、无任何授权门槛】
                //
                // 这里曾经是「授权检查 → 未授权就整页显示 ActivationScreen」的闸门。
                // 开源版把 ActivationManager.checkActivation 定成了硬编码 return true，
                // 于是那条分支永远进不去 —— ActivationScreen 是死代码，已随之删除。
                //
                // 保留这一遍 checkActivation 只为一个副作用：它会把设备 ID 落盘，
                // 「我的」页要显示它。将来若真要重新引入授权，闸门就该建在这里，
                // 而不是把删除掉的界面找回来再说。
                LaunchedEffect(Unit) {
                    if (!userManager.isDemoMode) {
                        ActivationManager.checkActivation(this@MainActivity)
                    }
                }

                // 主界面常驻渲染；首次启动的引导以液态玻璃弹框叠加其上，
                // 点引导条目直接跳到对应页面（见 MainScreen 内 OnboardingDialog 挂载点）。
                MainScreen(
                    fragmentActivity = this@MainActivity,
                    showOnboarding = showOnboarding,
                    onOnboardingFinish = {
                        prefs.edit().putBoolean(KEY_HAS_SEEN_ONBOARDING, true).apply()
                        showOnboarding = false
                    }
                )
            }
        }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        com.hnnujw.course.schedule.CourseReminderNavigation.accept(intent)
        AppTabNavigation.accept(intent)
        // App 已在运行时再扫一次码 / 再点一次链接走这里（singleTop 语义）
        com.hnnujw.course.secondclass.SecondClassDeepLinkNavigation.accept(intent)
        // 桌面课表组件点击（组件 Intent 带 CLEAR_TOP|SINGLE_TOP，热态走这里）
        com.hnnujw.course.schedule.ScheduleWidgetNavigation.accept(intent)
    }
}

/**
 * 桌面小组件 / 系统通知的「落到指定 Tab」入口。
 *
 * 挂在伴生对象而非 Activity 上：冷启动时 [MainScreen] 的组合先于
 * Activity 的 intent 消费，用 Compose state 跨越这条时序差。
 * 消费一次即清空，避免后台返回时又跳一次。
 */
object AppTabNavigation {
    var requestedPage by androidx.compose.runtime.mutableStateOf<StartupPage?>(null)
        private set

    /** 落到成绩页后要选中的子页（见 [MainActivity.EXTRA_OPEN_GRADES_TAB]）；null = 不改变。 */
    var requestedGradesTab by androidx.compose.runtime.mutableStateOf<Int?>(null)
        private set

    fun accept(intent: Intent?) {
        intent?.getStringExtra(MainActivity.EXTRA_OPEN_TAB)?.let { requestedPage = StartupPage.decode(it) }
        intent?.getStringExtra(MainActivity.EXTRA_OPEN_GRADES_TAB)?.toIntOrNull()
            ?.takeIf { it in 0..2 }?.let { requestedGradesTab = it }
    }

    fun consume() { requestedPage = null }

    fun consumeGradesTab() { requestedGradesTab = null }
}

sealed class BottomNavItem(
    val page: StartupPage,
    val symbol: AppSymbolSpec
) {
    val route: String get() = page.route
    val label: String get() = page.label
    val icon: ImageVector get() = symbol.outline
    object Schedule : BottomNavItem(StartupPage.Schedule, AppSymbolSpec.Schedule)
    object Grab : BottomNavItem(StartupPage.Grab, AppSymbolSpec.Grab)
    object Grades : BottomNavItem(StartupPage.Grades, AppSymbolSpec.Grades)
    object SecondClass : BottomNavItem(StartupPage.SecondClass, AppSymbolSpec.Achievement)
    object Settings : BottomNavItem(StartupPage.Settings, AppSymbolSpec.Settings)

    companion object {
        val entries: List<BottomNavItem> get() = listOf(Schedule, Grab, Grades, SecondClass, Settings)
    }
}

@Composable
fun MainScreen(
    fragmentActivity: FragmentActivity,
    showOnboarding: Boolean = false,
    onOnboardingFinish: () -> Unit = {}
) {
    val context = LocalContext.current
    val appWallpaper = com.hnnujw.course.ui.theme.rememberAppWallpaperStyle()
    val isDemoMode = remember { UserManager.getInstance().isDemoMode }
    val startupPagePreferences = remember(context) { StartupPagePreferences.from(context) }
    val items = remember { BottomNavItem.entries }
    
    var startupOverlaysReady by remember { mutableStateOf(false) }

    val sessionStore = UserManager.getInstance().sessionState
    val session by sessionStore.state.collectAsState()
    val currentAccountStorageKey = session.token.accountStorageKey
    val accessibility = rememberGlassAccessibilityMode()
    val pageDataViewModel: PageDataViewModel = viewModel()
    // 设置页清过缓存就换一个空的页面数据容器，避免磁盘清了、内存里的旧列表还在
    val pageDataRevision = com.hnnujw.course.ui.system.PageDataClearSignal.revision.intValue
    val pageData = remember(currentAccountStorageKey, pageDataRevision) {
        pageDataViewModel.forAccount(currentAccountStorageKey, reset = pageDataRevision > 0)
    }
    // Resolve before creating the motion state so the first frame is already on the chosen page.
    var selectedTab by remember(pageData) {
        pageData.state("navigation.tab") {
            val startupPage = startupPagePreferences.read()
            items.indexOfFirst { it.page == startupPage }.coerceAtLeast(0)
        }
    }
    val navigationMotion = com.hnnujw.course.ui.theme.rememberNavigationMotionState(selectedTab, currentAccountStorageKey, accessibility.reduceMotion)

    // 冷启动进入应用时弹一条「欢迎回来」（仅首次 ON_RESUME，进程存活期内只弹一次；
    // 从后台切回不再弹，避免打扰）。
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    var hasResumedOnce by remember { mutableStateOf(false) }
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                if (!hasResumedOnce) {
                    val name = UserManager.getInstance().studentName
                    if (!name.isNullOrBlank()) {
                        GlassToaster.show("欢迎回来，$name")
                    }
                }
                hasResumedOnce = true
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val reminderRequest = com.hnnujw.course.schedule.CourseReminderNavigation.requestedId
    LaunchedEffect(reminderRequest, currentAccountStorageKey) {
        if (reminderRequest != null) {
            // 课程提醒落到课表页；课程页已移除，课表是第 0 个 Tab
            if (com.hnnujw.course.schedule.ScheduleReminderScheduler.get(context).findById(reminderRequest) != null) selectedTab = 0
            else {
                com.hnnujw.course.ui.system.GlassToaster.show("这条课程提醒已失效或属于其他账号")
                com.hnnujw.course.schedule.CourseReminderNavigation.consume()
            }
        }
    }
    val dialogHostState = key(currentAccountStorageKey) { rememberDialogHostState() }
    // 成绩通知的跳页请求：落到底栏对应 Tab，消费一次即清空
    val requestedTabPage = AppTabNavigation.requestedPage
    LaunchedEffect(requestedTabPage) {
        if (requestedTabPage != null) {
            val index = items.indexOfFirst { it.page == requestedTabPage }
            if (index >= 0 && index != selectedTab) selectedTab = index
            AppTabNavigation.consume()
        }
    }
    val density = LocalDensity.current
    val pageTravelPx = with(density) { 8.dp.roundToPx() }
    val recovery by SessionRenewer.state.collectAsState()
    val isTokenExpired = session.expired && recovery.token == session.token && recovery.phase == RecoveryPhase.NeedsLogin
    val isRecovering = session.expired && !isTokenExpired
    val noticeModel: SessionNoticeViewModel = viewModel()
    val sessionNotice by noticeModel.notices.state.collectAsState()
    var foreground by remember { mutableStateOf(fragmentActivity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    val relogin = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            SessionRenewer.sessionChanged()
            val current = sessionStore.state.value
            if (!current.expired) noticeModel.notices.update(current, needsLogin = false, canPresent = false)
        }
    }
    val beginRelogin: () -> Unit = {
        if (sessionStore.isCurrent(session.token) && sessionStore.state.value.expired) {
            noticeModel.notices.dismiss(session.token)
            relogin.launch(Intent(fragmentActivity, LoginActivity::class.java).apply {
                putExtra("force_relogin", true)
                putExtra(LoginActivity.EXTRA_RETURN_TO_CALLER, true)
            })
        }
    }
    DisposableEffect(fragmentActivity) {
        val observer = LifecycleEventObserver { _, _ ->
            foreground = fragmentActivity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        }
        fragmentActivity.lifecycle.addObserver(observer)
        onDispose { fragmentActivity.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(session, isTokenExpired, foreground, dialogHostState.hasBlockingSurface) {
        noticeModel.notices.update(session, isTokenExpired, foreground && !dialogHostState.hasBlockingSurface)
    }

    // ── 通知权限 ────────────────────────────────────────────────────────────
    // Android 13+ 上 POST_NOTIFICATIONS 默认拒绝，而成绩推送与教务消息推送全靠它。
    // 此前全项目只有「设置课表提醒」的流程里才申请（AcademicRoutes / ScheduleCourseSheet），
    // 于是从不开课表提醒的用户，这两类推送是彻底静默失效的：红点亮，通知一条不发。
    // 这里登录后统一申请一次；被拒绝就不再打扰，改由「我的 → 通知提醒」引导。
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 结果由「我的」里的通知状态行反映 */ }
    var permissionHintShown by remember { mutableStateOf(false) }
    LaunchedEffect(currentAccountStorageKey, foreground, showOnboarding, isDemoMode) {
        if (isDemoMode || showOnboarding || !foreground) return@LaunchedEffect
        if (currentAccountStorageKey.isBlank()) return@LaunchedEffect
        if (MainActivity.notificationsAllowed(context)) return@LaunchedEffect
        val permissionPrefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        if (permissionPrefs.getBoolean(MainActivity.KEY_NOTIFICATION_ASKED, false)) return@LaunchedEffect
        permissionPrefs.edit().putBoolean(MainActivity.KEY_NOTIFICATION_ASKED, true).apply()
        kotlinx.coroutines.delay(1200)   // 让首帧与「欢迎回来」先出来，别抢在同一瞬间
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    // 消息中心巡检：回到前台时按节流（30 分钟）检查一次，有新消息发系统通知，
    // 并把未读数推到全局 state（「我的」页入口红点 + 底栏「我的」图标红点都读它）。
    val messageUnread = com.hnnujw.course.academic.MessageCenterManager.unread
    LaunchedEffect(currentAccountStorageKey, foreground, isDemoMode) {
        if (isDemoMode || !foreground || currentAccountStorageKey.isBlank()) return@LaunchedEffect
        com.hnnujw.course.academic.MessageCenterManager.refreshUnreadFromCache(context, currentAccountStorageKey)
        // 权限没开时，下面的巡检只会更新红点：系统通知一条都发不出去。
        // 明确说一次，免得用户以为「没消息」。
        if (!permissionHintShown && !MainActivity.notificationsAllowed(context) &&
            com.hnnujw.course.academic.MessageCenterManager.unread > 0) {
            permissionHintShown = true
            com.hnnujw.course.ui.system.GlassToaster.show("有未读消息，但通知权限未开启，无法提醒你")
        }
        val user = UserManager.getInstance()
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            com.hnnujw.course.academic.MessageCenterNotifier.check(
                context = context,
                school = user.currentSchool,
                accountKey = currentAccountStorageKey
            )
            // 成绩巡检与消息巡检同节奏（各自 12 小时节流）：前台化顺带查一次，
            // 出成绩季不用等后台闹钟也能在一小时内知道新成绩。
            runCatching {
                com.hnnujw.course.academic.GradeWatcher.check(
                    context = context,
                    school = user.currentSchool,
                    accountKey = currentAccountStorageKey
                )
            }
        }
    }

    // 版本更新：冷启动后自动查一次（UpdateCenter 内 6 小时节流），有新版就弹
    // 液态玻璃更新弹窗，用户点一下即可在应用内下载安装。
    // 未登录 / 演示模式不打扰 —— 更新与登录态无关，但启动瞬间弹窗会顶掉欢迎提示。
    LaunchedEffect(currentAccountStorageKey, isDemoMode) {
        // 后台巡检闹钟：让 App 没打开时也能发现新版本（发现后发系统通知）。
        // 与登录态无关，所以排在 isDemoMode 判断之前；同一个 PendingIntent 幂等，重复排没有副作用。
        com.hnnujw.course.network.UpdateCheckScheduler.schedule(context)
        if (isDemoMode) return@LaunchedEffect
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            com.hnnujw.course.network.UpdateCenter.checkQuietly(context)
        }
    }
    val pendingUpdate = com.hnnujw.course.network.UpdateCenter.pending

    // 开发者公告：随包内置（assets/announcement.json），有**适用于当前版本**的未读公告
    // 就弹一次液态玻璃公告。包名迁移这类重大通知都在这里触达；全部公告见「我的 → 公告」。
    // 「适用于当前版本」由 AnnouncementCenter 按 versionCode 判定：写给旧版本的、或压根
    // 没声明版本范围的历史公告不会被弹出来（见 AnnouncementManager.Announcement.appliesTo）。
    var announcementShownThisSession by remember { mutableStateOf(false) }
    LaunchedEffect(currentAccountStorageKey, foreground, isDemoMode, showOnboarding) {
        if (isDemoMode || showOnboarding || currentAccountStorageKey.isBlank()) return@LaunchedEffect
        // 读 assets 那一步在 load 内部切到 IO，状态更新落在主线程；这里不用再包一层
        com.hnnujw.course.announcement.AnnouncementCenter.load(context)
        if (!announcementShownThisSession &&
            com.hnnujw.course.announcement.AnnouncementCenter.unreadCount > 0) {
            // 稍等首帧就绪，别和「欢迎回来」提示挤在同一瞬间
            kotlinx.coroutines.delay(600)
            announcementShownThisSession = true
        }
    }
    val pendingAnnouncement = com.hnnujw.course.announcement.AnnouncementCenter.firstUnread
    // 两个弹窗排队：更新提示先出（它带着"立即更新"这个动作），用户处理完再轮到公告。
    // 都走 SystemDialog，同一时刻显示两张玻璃卡会叠在屏幕正中。
    if (pendingUpdate != null) {
        com.hnnujw.course.ui.update.AppUpdateDialog(
            info = pendingUpdate,
            currentVersion = com.hnnujw.course.BuildConfig.VERSION_NAME,
            onDismiss = { com.hnnujw.course.network.UpdateCenter.dismiss(context) }
        )
    } else if (announcementShownThisSession && pendingAnnouncement != null) {
        com.hnnujw.course.announcement.AnnouncementDialog(
            announcement = pendingAnnouncement,
            onDismiss = {
                com.hnnujw.course.announcement.AnnouncementCenter.markRead(context, pendingAnnouncement.id)
            }
        )
    }

    // 底栏滚动最小化：捕获页面内任意滚动的方向（nested scroll 冒泡，页面零改动）
    var navBarMinimized by remember { mutableStateOf(false) }
    // API31/32 折射底图的新鲜度。页面内容随滚动移动，底图必须跟着重拍，
    // 否则折射里是启动那一刻的画面。见 GlassLensFreshness 的注释。
    //
    // 其它 API 上是 null：onScroll() 会写一个 mutableIntState，写在滚动的热路径上。
    // 33+ 没有底图要重拍，那份写入没有任何消费者，白烧。
    val lensFreshness = remember {
        if (com.hnnujw.course.ui.system.glass.isGlassLensApplicable()) {
            com.hnnujw.course.ui.system.glass.GlassLensFreshness()
        } else {
            null
        }
    }
    val navScrollIntent = remember { NavScrollIntent() }
    val navBarScrollConnection = remember(density, dialogHostState) {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && dialogHostState.currentDialog == null) {
                    if (consumed.y == 0f && available.y > 0f) {
                        navBarMinimized = false
                        navScrollIntent.reset()
                    } else if (kotlin.math.abs(consumed.y) > kotlin.math.abs(consumed.x)) {
                        navScrollIntent.scroll(consumed.y / density.density)?.let { navBarMinimized = it }
                    }
                }
                // 惯性滑行（source == SideEffect）也要算：手指离开后页面还在动
                if (consumed.y != 0f) {
                    lensFreshness?.onScroll()
                }
                return Offset.Zero
            }
        }
    }
    LaunchedEffect(selectedTab) {
        navBarMinimized = false
        navScrollIntent.reset()
    }

    LaunchedEffect(Unit) {
        withFrameNanos { }
        delay(1_600)
        startupOverlaysReady = true
    }

    LaunchedEffect(session.token, session.expired) {
        SessionRenewer.sessionChanged()
        if (session.expired) SessionRenewer.request(session.token)
    }

    DisposableEffect(fragmentActivity, session.token, session.expired, foreground) {
        if (foreground && !isDemoMode && UserManager.getInstance().currentSchool != null && !session.expired) {
            com.hnnujw.course.utils.CookieWatchdog.start(fragmentActivity)
        }
        onDispose {
            com.hnnujw.course.utils.CookieWatchdog.stop()
        }
    }

    GlassOverlayHost(modifier = Modifier.fillMaxSize()) {
        val useGlass = isBackdropSupported()
        val tokenExpiredNotice = if (isTokenExpired) {
            FloatingNotice(
                message = "需要重新登录",
                actionLabel = "重新登录",
                onClick = beginRelogin
            )
        } else if (isRecovering) {
            FloatingNotice(message = "正在恢复登录状态", actionLabel = "恢复中", onClick = {})
        } else {
            null
        }

        val wallpaperBackdrop = if (useGlass) {
            rememberLayerBackdrop()
        } else {
            null
        }

        // 底栏位于该层外，只能单向采样壁纸与页面内容。
        val navBarBackdrop = if (useGlass) {
            rememberLayerBackdrop()
        } else {
            null
        }

        // 顶栏把底边写进这里，通知覆盖层据此落位，避免压住顶栏按钮
        val noticeAnchorState = remember { NoticeAnchorState() }
        // 内容要避开的底栏高度【由底栏自己算】。原先这里写死 96dp，是照手势条量的；
        // 三键导航下底栏实际 136dp 高，各页列表的末项就有一截藏在栏后面。
        val navBarContentInset = com.hnnujw.course.ui.system.NavBarMetrics.contentInset()

        // ## API 31/32 的全局折射区域
        //
        // 绝大多数控件（按钮、圆钮、开关、选择器）采样的是 `LocalControlBackdrop`，
        // 而它在这里就是 `wallpaperBackdrop` —— **一层静态壁纸**。所以整个 App
        // 只需要一张底图，一次快照，之后除了换壁纸/换主题都不必重拍。
        //
        // 为什么是全屏一张、而不是每个控件一张：折射会把边缘附近的背景**位移**进来，
        // 采样点会落到控件轮廓之外。底图只有控件那么大时，那些采样点会被 CLAMP 成
        // 边缘像素，屏幕上是一圈拉长的涂抹而不是真实背景。区域必须比控件大。
        //
        // 页面若用自己的 backdrop 覆盖了 LocalControlBackdrop（例如成绩页顶栏的
        // `combined(壁纸, 顶栏玻璃层)`），那一处就必须自己再建一个区域 ——
        // 底图要复现的是**那个控件实际采样的东西**，不是这一层。
        val appLensDensity = LocalDensity.current
        val appLensAnchor = if (wallpaperBackdrop != null) {
            com.hnnujw.course.ui.system.glass.rememberGlassLensRegion(
                tag = "app",
                // 壁纸不必写进 keys：rememberGlassLensAnchor 自己盯着
                // AppearanceSettingsManager.style（见那边的注释）。写在这里的
                // 后果是组合期读 style，整棵子树跟着订阅壁纸。
                drawSource = { coords ->
                    drawBackdropSource(wallpaperBackdrop, appLensDensity, coords)
                }
            )
        } else {
            null
        }

        // ## 第二张底图：模糊过的
        //
        // 上面那张是**锐利**的，因为按钮/圆钮/选择器在 33+ 上都是 `enableBlur = false`
        // —— 它们的 lens 采的就是没模糊过的背景。
        //
        // 模态面板（弹窗、下拉菜单）不一样，33+ 的管线是 vibrancy → blur → lens，
        // lens 采的是**已经模糊过的**像素。而模糊必须先烤进底图：屏幕上那层 blur
        // 是加在 drawBackdrop 的图层上的，折射已经在它上游画完了，那层 blur 拿不到
        // 任何输入。所以这里单独存一张 6dp 模糊版。
        //
        // 半径取 Modal 档的 6dp。弹窗与下拉菜单读的是同一个 `modal.blurDp`，
        // 所以一张就够；将来若有别的档位要 blur→lens，它得自己再建一张 ——
        // 差一档模糊，折射里的内容就和屏幕上的对不上。
        val modalLensBlurPx = with(appLensDensity) { GlassRecipe.DialogBlurDp.dp.toPx() }
        val modalLensAnchor = if (navBarBackdrop != null) {
            com.hnnujw.course.ui.system.glass.rememberGlassLensRegion(
                tag = "app-modal",
                keys = arrayOf(selectedTab, session.token, dialogHostState.currentDialog),
                freshness = lensFreshness,
                // 壁纸同上，由锚点自己盯
                drawSource = { coords ->
                    drawBlurred(modalLensBlurPx) {
                        drawBackdropSource(navBarBackdrop, appLensDensity, coords)
                    }
                }
            )
        } else {
            null
        }

        CompositionLocalProvider(
            LocalAppBackdrop provides wallpaperBackdrop,
            LocalControlBackdrop provides wallpaperBackdrop,
            LocalModalBackdrop provides navBarBackdrop,
            LocalAppOverlayBottomInset provides navBarContentInset,
            LocalDialogHost provides dialogHostState,
            LocalPageDataState provides pageData,
            LocalFloatingNotice provides tokenExpiredNotice,
            LocalNoticeAnchor provides noticeAnchorState,
            com.hnnujw.course.ui.system.glass.LocalPageGlassFreshness provides lensFreshness,
            com.hnnujw.course.ui.system.glass.LocalGlassLensAnchor provides appLensAnchor,
            com.hnnujw.course.ui.system.glass.LocalGlassLensModalAnchor provides modalLensAnchor
        ) {
            Box(
                modifier = Modifier.fillMaxSize().then(
                    if (useGlass && navBarBackdrop != null) Modifier.layerBackdrop(navBarBackdrop)
                    else Modifier
                ).then(
                    // 全局折射区域的取景框：全屏。控件的采样点会跑到自己轮廓之外，
                    // 底图必须比控件大，否则边缘会被 CLAMP 成一圈涂抹。
                    //
                    // **两张底图都要挂**。只挂一张时，另一张的 coordinates 永远是
                    // null，`ensureSource()` 直接返回 null，折射一个像素都不画；
                    // 而调用方的 `onDrawBackdrop` 已经因为"锚点非 null"把正常的背景
                    // 绘制让掉了 —— 屏幕上是**整块面板消失**，只剩文字和按钮浮在
                    // 页面上。弹窗上实拍过一次，就是这个原因。
                    Modifier
                        .glassLensAnchor(appLensAnchor)
                        .glassLensAnchor(modalLensAnchor)
                )
            ) {
            if (useGlass && wallpaperBackdrop != null) {
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .layerBackdrop(wallpaperBackdrop)
                ) {
                    // 在绘制 lambda 内部再读一次 state：图片壁纸的位图是异步解码的，
                    // 只读外面那份快照的话，位图到位时这一层不会重绘。
                    drawWallpaperPattern(appWallpaper)
                }
            } else {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    // 无玻璃分支也画完整壁纸。这里原先只填 baseColor，于是关掉液态玻璃
                    // （或在 API 31 以下）用户自己传的图片壁纸会整张消失，只剩一块纯色。
                    // drawWallpaperPattern 是纯 Canvas 绘制，不依赖 backdrop。
                    // 微纹理关掉：它唯一的作用是给折射提供可弯曲的高频内容，这条路径没有折射。
                    drawWallpaperPattern(
                        appWallpaper,
                        microTexture = false
                    )
                }
            }

            Column(modifier = Modifier.fillMaxSize()) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .nestedScroll(navBarScrollConnection)
                    ) {
                        key(currentAccountStorageKey) {
                            val savedPages = rememberSaveableStateHolder()
                            com.hnnujw.course.ui.theme.NavigationPages(navigationMotion,
                                modifier = Modifier.fillMaxSize().graphicsLayer {
                                    translationX = -6.dp.toPx() * dialogHostState.pageProgress
                                    alpha = 1f - 0.03f * dialogHostState.pageProgress
                                }
                            ) { page ->
                              savedPages.SaveableStateProvider(items[page].route) {
                                when (page) {
                                    0 -> com.hnnujw.course.ui.route.ScheduleRoute()
                                    1 -> com.hnnujw.course.ui.route.GrabProRoute()
                                    2 -> com.hnnujw.course.ui.route.GradesRoute()
                                    3 -> com.hnnujw.course.ui.route.SecondClassroomRoute()
                                    4 -> com.hnnujw.course.ui.route.SettingsRoute()
                                    else -> com.hnnujw.course.ui.route.ScheduleRoute()
                                }
                              }
                            }
                        }
                    }
                }

            } // 关闭 navBarBackdrop 捕获层

            // 底栏位于捕获层外，避免采样源包含底栏自身。
            CompositionLocalProvider(com.hnnujw.course.ui.theme.LocalNavigationMotion provides navigationMotion) {
            CapsuleNavigationBar(
                items = items,
                selectedTab = selectedTab,
                onTabSelect = { targetTab ->
                    if (targetTab != selectedTab) {
                        selectedTab = targetTab
                    }
                },
                minimized = navBarMinimized,
                onExpandRequest = { navBarMinimized = false },
                backdrop = navBarBackdrop,
                lensFreshness = lensFreshness,
                // 消息中心藏在「我的」页里，未读时至少让「我的」这个 tab 有红点，
                // 否则用户没有任何理由点进去
                badgeTabs = if (messageUnread > 0) setOf(items.indexOf(BottomNavItem.Settings)) else emptySet(),
                modifier = Modifier.align(Alignment.BottomCenter)
            )
            }

            // 全局玻璃 Toast：悬浮在底栏上方
            GlassToastHost(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = navBarContentInset + if (selectedTab == 1)
                        com.hnnujw.course.ui.system.TaskControlsReservedHeight + 12.dp else 12.dp)
            )

            DialogHost(
                state = dialogHostState,
                modifier = Modifier.fillMaxSize()
            )
            if (sessionNotice.token == session.token && sessionNotice.visible && isTokenExpired) {
                key(session.token) {
                    com.hnnujw.course.ui.system.SessionExpiryPrompt(
                        hasCachedContent = pageData.hasCachedContent,
                        onLater = { noticeModel.notices.dismiss(session.token) },
                        onLogin = {
                            if (sessionStore.isCurrent(session.token) && sessionStore.state.value.expired) beginRelogin()
                        }
                    )
                }
            }

            // 悬浮玻璃通知：叠加在正文之上，落点由顶栏上报的底边决定，不压顶栏操作
            FloatingNoticeHost(modifier = Modifier.fillMaxSize())

            // 首次启动引导：液态玻璃弹框。点条目跳到对应页面并结束引导。
            if (showOnboarding) {
                OnboardingDialog(
                    onNavigate = { targetPage ->
                        val targetIndex = items.indexOfFirst { it.page == targetPage }
                        if (targetIndex >= 0 && targetIndex != selectedTab) {
                            selectedTab = targetIndex
                        }
                        onOnboardingFinish()
                    },
                    onFinish = onOnboardingFinish
                )
            }
        }
    }
}
