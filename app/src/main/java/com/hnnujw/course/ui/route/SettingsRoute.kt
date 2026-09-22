package com.hnnujw.course.ui.route

import com.hnnujw.course.ui.system.GlassToaster
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.hnnujw.course.ui.system.SystemDialog
import com.hnnujw.course.ui.system.SystemConfirmDialog
import com.hnnujw.course.ui.system.SystemPrimaryButton
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import com.hnnujw.course.LoginActivity
import com.hnnujw.course.MainActivity
import com.hnnujw.course.MessageCenterActivity
import com.hnnujw.course.login.PasswordLoginCallback
import com.hnnujw.course.login.PasswordLoginGatewayFactory
import com.hnnujw.course.manager.AppearanceSettingsManager
import com.hnnujw.course.manager.StartupPagePreferences
import com.hnnujw.course.manager.UserManager
import com.hnnujw.course.network.CourseApiClient
import com.hnnujw.course.ui.screen.SettingsScreen
import com.hnnujw.course.activation.ActivationManager
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import java.io.ByteArrayOutputStream
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
import com.hnnujw.course.ui.system.SystemStatusBadge
import com.hnnujw.course.ui.system.SystemTone
import com.hnnujw.course.ui.theme.NeuPrimary
import com.hnnujw.course.ui.theme.SemanticSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 官方反馈 QQ 频道号（见用户手册「反馈渠道」）。 */
private const val QQ_CHANNEL_ID = "pd32446534"

/** 「我的」页日志标签（院系/专业表头、账号切换等）。 */
private const val TAG = "SettingsRoute"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsRoute(
    onAccountChanged: () -> Unit = {}
) {
    val context = LocalContext.current
    val isDemoMode = remember { UserManager.getInstance().isDemoMode }
    
    var studentName by remember { mutableStateOf("") }
    var deviceId by remember { mutableStateOf("") }
    var schoolName by remember { mutableStateOf("") }
    /**
     * 本人院系 / 专业（「我的」页头像下面那行，替换原先只显示校名的做法）。
     *
     * 教务侧拿不到 —— `CourseParser` 只解析姓名与学号；唯一来源是二课的
     * `/student/achievement/detail`（`user.collegeName` / `user.majorName`）。
     * 因此**只在已绑定二课（本地有 token）时才发这个请求**；没有 token 就保持空串，
     * 界面回退成显示校名，不会因为一个装饰性信息去打扰用户。
     */
    var collegeName by remember { mutableStateOf("") }
    var majorName by remember { mutableStateOf("") }
    
    // UI States
    var showLogoutDialog by remember { mutableStateOf(false) }
    var showClearCacheDialog by remember { mutableStateOf(false) }
    var showAccountManagerDialog by remember { mutableStateOf(false) }
    var pendingPasswordDelete by remember { mutableStateOf<UserManager.AccountRecord?>(null) }
    var pendingAccountDelete by remember { mutableStateOf<UserManager.AccountRecord?>(null) }
    var showWallpaperDialog by remember { mutableStateOf(false) }
    var showThemeDialog by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var showStartupPageDialog by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    val startupPagePreferences = remember(context) { StartupPagePreferences.from(context) }
    var startupPage by remember(startupPagePreferences) { mutableStateOf(startupPagePreferences.read()) }
    val currentWallpaperName = com.hnnujw.course.manager.AppearanceSettingsManager.currentWallpaperName
    
    // 身份标识：配额展示已按要求整体移除，这里只保留"超级用户"徽标所需的判断
    var isSuper by remember { mutableStateOf(false) }
    // 账号管理走全量列表（跨学校）：这里是"管理"，不该被当前学校过滤掉
    var allAccounts by remember { mutableStateOf<List<UserManager.AccountRecord>>(emptyList()) }
    var accountsWithPassword by remember { mutableStateOf<Set<String>>(emptySet()) }
    var currentAccountKey by remember { mutableStateOf("") }
    var canRefreshCookie by remember { mutableStateOf(false) }
    val session by UserManager.getInstance().sessionState.state.collectAsState()
    var cookieUpdateFeedback by remember {
        mutableStateOf<Pair<com.hnnujw.course.manager.SessionToken, com.hnnujw.course.ui.system.SymbolResult>?>(null)
    }
    val cookieUpdateResult = cookieUpdateFeedback?.takeIf { it.first == session.token }?.second
        ?: com.hnnujw.course.ui.system.SymbolResult.None
    val recovery by com.hnnujw.course.utils.SessionRenewer.state.collectAsState()
    val isRefreshingCookie = recovery.token == session.token &&
        recovery.phase == com.hnnujw.course.utils.RecoveryPhase.Restoring
    val relogin = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { }

    // ── 自定义头像：相册 → 取景框裁剪页 → 落盘 ─────────────────────
    //
    // 与「自定义背景」走**同一条**路径（同一个 ImageCropActivity），
    // 区别只有两处：取景框是方形、输出写进头像文件。见 ImageCropActivity 的注释。
    var avatarRefreshKey by remember { mutableIntStateOf(0) }
    val hasAvatar = remember(avatarRefreshKey) { com.hnnujw.course.manager.UserAvatarStore.hasAvatar(context) }
    /** 头像裁剪的输出路径。裁剪页只负责写字节，落盘位置由这里定。 */
    var avatarCropOutput by remember { mutableStateOf<java.io.File?>(null) }
    val cropLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val output = avatarCropOutput
        avatarCropOutput = null
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            avatarRefreshKey++
            GlassToaster.show("头像已更新")
        } else {
            // 取消：把可能已经写了一半的输出清掉，别留个半成品等着被当头像读
            output?.takeIf { it.exists() }?.delete()
        }
    }

    val pickAvatarLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        // 直接进裁剪页：备料（读尺寸、降采样、EXIF 摆正）都在那边做，
        // 这边不再落一张中间图 —— 少一份状态就少一处能对不上的地方。
        val output = com.hnnujw.course.manager.UserAvatarStore.avatarFile(context)
        avatarCropOutput = output
        cropLauncher.launch(
            com.hnnujw.course.ImageCropActivity.newIntent(
                context = context,
                source = uri,
                outputPath = output.absolutePath,
                aspectRatio = 1f,
                title = "调整头像"
            )
        )
    }

    /** 打开系统相册选一张图当头像。 */
    fun launchAvatarPicker() {
        pickAvatarLauncher.launch(
            androidx.activity.result.PickVisualMediaRequest(
                androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly
            )
        )
    }

    /**
     * 「更换头像」的唯一入口。
     *
     * **不再自己申请读图权限**：照片选择器（`PickVisualMedia`）从 Android 13 起
     * 由系统进程代理读图，App 一行权限都不需要；在 13 以下它会退化成
     * `ACTION_OPEN_DOCUMENT`，同样不需要 `READ_EXTERNAL_STORAGE`。
     * 旧实现在入口处先申请一遍权限，反而把"取消失败"这条路引进来：
     * 用户拒绝后整个流程就断了，表现成"头像读不出来"。
     */
    fun requestAvatarPicker() {
        launchAvatarPicker()
    }
    
    // 版本号用于页脚展示与「检查更新」比较。应用内自动更新已移除，
    // 检查更新按需从 Gitee 拉取最新 Release。
    val currentVersion = remember { com.hnnujw.course.BuildConfig.VERSION_NAME }

    // ── 「我的」页新增功能的状态 ────────────────────────────────────────
    val scope = rememberCoroutineScope()
    var showContactDialog by remember { mutableStateOf(false) }
    var showSitesDialog by remember { mutableStateOf(false) }
    var showAboutDialog by remember { mutableStateOf(false) }
    // 更新状态放在 UpdateCenter 里：启动自动检查与这里的手动检查共用，
    // 否则用户在设置页跳过过的版本，下次冷启动还会被自动弹一次。
    val updateChecking = com.hnnujw.course.network.UpdateCenter.checking
    val updateInfo = com.hnnujw.course.network.UpdateCenter.pending

    /** 用系统默认浏览器打开链接（推荐网站、Star、下载等，不走应用内 WebView）。 */
    fun openInBrowser(url: String) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.onFailure { GlassToaster.show("未找到可以打开网页的应用") }
    }

    /** 跳登录页登录/切换账号：账号管理的空槽位与"重新登录"共用这条路。 */
    fun launchRelogin() {
        relogin.launch(Intent(context, LoginActivity::class.java).apply {
            putExtra("force_relogin", true)
            putExtra(LoginActivity.EXTRA_RETURN_TO_CALLER, true)
        })
    }

    fun shareAppToClassmates() {
        val text = "校园助理 · 开源免费的高校教务客户端（课表 / 选课 / 成绩 / 第二课堂）\n" +
            "GitHub：https://github.com/Elisoar111/hnnu-jiaowu-app\n" +
            "Gitee：https://gitee.com/Elisoar/hnnu-jiaowu-app"
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        runCatching {
            context.startActivity(Intent.createChooser(send, "分享给校友"))
        }.onFailure { GlassToaster.show("没有可用的分享应用") }
    }

    fun checkForUpdate() {
        if (updateChecking) return
        scope.launch {
            when (val result = com.hnnujw.course.network.UpdateCenter.checkNow(context)) {
                is com.hnnujw.course.network.AppUpdateChecker.Result.UpToDate ->
                    GlassToaster.show("已是最新版本 · v${result.versionName}")
                is com.hnnujw.course.network.AppUpdateChecker.Result.Failure ->
                    GlassToaster.show("检查更新失败：${result.message}")
                // UpdateCenter 已经把 pending 设好，弹窗那一侧会自动出现
                is com.hnnujw.course.network.AppUpdateChecker.Result.Update -> Unit
            }
        }
    }

    // 第二课堂「成绩单」已迁到底栏「二课」页最右侧的「成绩单」Tab（在「消息」右边），
    // 与本页无关；未读消息红点：读的是全局 Compose state，消息中心里读掉一条这里就当帧消失
    val messageUnread = com.hnnujw.course.academic.MessageCenterManager.unread
    // 必须用 **storage key**：MessageCenterActivity 传给消息中心的就是它，
    // 缓存文件名按这个 key 生成。这里若用 currentAccountKey（未经 toStorageKey
    // 归一化），设置页读的是另一个文件，红点永远是 0。
    val messageAccountKey = remember(currentAccountKey, session.token) {
        if (isDemoMode) "" else UserManager.getInstance().currentAccountStorageKey
    }
    LaunchedEffect(messageAccountKey, isDemoMode) {
        if (isDemoMode || messageAccountKey.isBlank()) return@LaunchedEffect
        // 先亮出缓存里的红点，再按节流打一次网络（30 分钟内不重复请求）
        com.hnnujw.course.academic.MessageCenterManager.refreshUnreadFromCache(context, messageAccountKey)
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            com.hnnujw.course.academic.MessageCenterNotifier.check(
                context = context,
                school = UserManager.getInstance().currentSchool,
                accountKey = messageAccountKey
            )
        }
    }
    // 开发者公告：进入「我的」时确保内置公告已载入（进程内只解析一次），用来算红点；
    // 点入口进独立的公告中心页（与消息中心同构）。
    // 以前这里弹一个列表弹窗、点开再叠一个详情弹窗，正文长了很难读；现在统一成整页。
    val announcementUnread = com.hnnujw.course.announcement.AnnouncementCenter.unreadCount
    LaunchedEffect(isDemoMode) {
        if (isDemoMode) return@LaunchedEffect
        // 读 assets 那一步在 load 内部切到 IO，状态更新落在主线程；这里不用再包一层
        com.hnnujw.course.announcement.AnnouncementCenter.load(context)
    }

    // 通知权限状态：Android 13+ 需要在系统里授权才会弹成绩发布/站内消息/考前提醒。
    // 用户在系统设置里改完回到前台时，生命周期回到 RESUMED，这里跟着更新。
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    var notificationsAllowed by remember { mutableStateOf(MainActivity.notificationsAllowed(context)) }
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                notificationsAllowed = MainActivity.notificationsAllowed(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }


    fun refreshAccountUiState() {
        val userManager = UserManager.getInstance()
        val name = userManager.studentName
        val school = userManager.currentSchool

        if (isDemoMode) {
            studentName = name ?: "演示用户"
            deviceId = "LOCAL-DEMO"
            schoolName = school?.name ?: "正方演示大学（演示数据）"
            isSuper = true
            allAccounts = emptyList()
            accountsWithPassword = emptySet()
            currentAccountKey = userManager.currentAccountKey
            canRefreshCookie = false
            return
        }

        studentName = name ?: "同学"
        deviceId = ActivationManager.getSavedDeviceId(context)
        schoolName = school?.name ?: "未选择"

        isSuper = ActivationManager.getMaxStudents(context) <= 0
        allAccounts = userManager.savedAccounts
        accountsWithPassword = allAccounts
            .filter { userManager.hasSavedPassword(it.key) }
            .map { it.key }
            .toSet()
        currentAccountKey = userManager.currentAccountKey
        canRefreshCookie = userManager.loginMode == "password"
    }

    LaunchedEffect(session.token) {
        refreshAccountUiState()
    }

    // 院系 / 专业：只在已绑定二课时拉一次（切账号会随 session.token 变化重拉）。
    // 失败静默 —— 这是装饰性信息，拿不到就回退显示校名，不弹任何提示。
    LaunchedEffect(session.token, isDemoMode) {
        // 先清空：切账号后若新账号没绑二课，不能把上一个账号的院系留在表头上
        collegeName = ""
        majorName = ""
        if (isDemoMode) return@LaunchedEffect
        val userManager = UserManager.getInstance()
        val secondClassClient = com.hnnujw.course.secondclass.SecondClassroomStore
            .clientFor(userManager.currentSchool) ?: return@LaunchedEffect
        val token = com.hnnujw.course.secondclass.SecondClassroomStore
            .token(context, userManager.currentAccountKey)
        if (token.isBlank()) return@LaunchedEffect
        val profile = runCatching {
            withContext(Dispatchers.IO) { secondClassClient.profile(token) }
        }.getOrNull() ?: return@LaunchedEffect
        collegeName = profile.collegeName
        majorName = profile.majorName
        Log.d(TAG, "已取到本人院系/专业（用于「我的」页表头）")
    }
    
    fun performLogout() {
        UserManager.getInstance().clearLoginState()
        val intent = Intent(context, LoginActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        context.startActivity(intent)
        // If context is not activity, clean task might need validation but usually safe
    }

    fun switchAccount(accountKey: String, onSwitched: () -> Unit) {
        if (accountKey == currentAccountKey) return
        if (UserManager.getInstance().switchToAccount(accountKey)) {
            refreshAccountUiState()
            onSwitched()
            onAccountChanged()
            GlassToaster.show("已切换账号")
        } else {
            GlassToaster.show("账号切换失败，请重新登录")
        }
    }

    /** 只删密码：账号还在，但不再自动续期，下次失效需要手动登录。 */
    fun deleteAccountPassword(record: UserManager.AccountRecord) {
        UserManager.getInstance().deletePassword(record.key)
        refreshAccountUiState()
        GlassToaster.show("已删除该账号保存的密码")
    }

    /** 彻底删号：账号记录 + 已存密码 + 运行期 Cookie + 本地课程缓存。 */
    fun deleteAccountEntirely(record: UserManager.AccountRecord) {
        val userManager = UserManager.getInstance()
        val isCurrent = record.key == userManager.currentAccountKey
        val storageKey = userManager.deleteAccount(record.key)
        if (storageKey.isNotEmpty()) {
            com.hnnujw.course.manager.CourseCacheManager.clearAccountCache(context, storageKey)
            // 删号不能只清课程缓存：成绩/考试缓存、消息中心缓存都是按账号落盘的，
            // 留着就是"删了号还能翻出旧数据"的残留。
            com.hnnujw.course.manager.GradesCacheManager.clearAccount(context, storageKey)
            com.hnnujw.course.academic.MessageCenterManager.clearCache(context, storageKey)
        }
        // 第二课堂的 token 与已保存密码是按 accountKey 存的，一起删掉才算"彻底删号"
        com.hnnujw.course.secondclass.SecondClassroomStore.clearAccount(context, record.key)
        refreshAccountUiState()
        if (isCurrent) {
            // 当前账号被删掉，会话已经没有依据了，直接回登录页
            GlassToaster.show("账号已删除，请重新登录")
            performLogout()
        } else {
            GlassToaster.show("账号已删除")
        }
    }
    
    fun refreshCookieManually() {
        if (isDemoMode || isRefreshingCookie) return
        cookieUpdateFeedback = null
        val user = UserManager.getInstance()
        val expected = user.sessionState.token
        com.hnnujw.course.utils.SessionRenewer.request(expected, manual = true) { result ->
            when (result) {
                is com.hnnujw.course.utils.SessionRecoveryResult.Recovered -> {
                    if (user.sessionState.isCurrent(result.token)) {
                        cookieUpdateFeedback = result.token to com.hnnujw.course.ui.system.SymbolResult.Success
                        refreshAccountUiState()
                        GlassToaster.show("登录状态已更新")
                    }
                }
                is com.hnnujw.course.utils.SessionRecoveryResult.NeedsLogin -> {
                    if (user.sessionState.isCurrent(expected)) {
                        cookieUpdateFeedback = expected to com.hnnujw.course.ui.system.SymbolResult.Failure
                        GlassToaster.show(
                        when (result.reason) {
                            com.hnnujw.course.utils.RecoveryFailure.Network -> "暂时无法连接，请稍后重试"
                            com.hnnujw.course.utils.RecoveryFailure.Storage -> "保存失败，请重试"
                            else -> "需要重新登录以更新登录状态"
                        }
                        )
                    }
                }
                com.hnnujw.course.utils.SessionRecoveryResult.Superseded -> Unit
            }
        }
    }
    
    // ── QQ 频道 ─────────────────────────────────────────────────────────
    // QQ 频道没有可公开分享的网页直达链接，稳妥做法：频道号进剪贴板，
    // 再顺手拉起本机 QQ（没装就静默失败），用户切过去粘贴搜索即可加入。
    fun joinQqChannel() {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
        clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("qq_channel", QQ_CHANNEL_ID))
        GlassToaster.show("频道号 $QQ_CHANNEL_ID 已复制，打开 QQ 频道搜索加入")
        runCatching {
            context.packageManager.getLaunchIntentForPackage("com.tencent.mobileqq")?.let {
                context.startActivity(it)
            }
        }
    }

    SettingsScreen(
        studentName = studentName,
        studentId = deviceId,
        schoolName = schoolName,
        collegeName = collegeName,
        majorName = majorName,
        currentVersion = currentVersion,
        onSchoolSelect = {
            GlassToaster.show("本应用仅支持淮南师范学院")
        },
        onAccountManage = {
            if (isDemoMode) GlassToaster.show("本地演示模式不读取真实账号") else showAccountManagerDialog = true
        },
        onAddAccount = { launchRelogin() },
        savedAccountCount = allAccounts.size,
        onClearCache = { showClearCacheDialog = true },
        onLogout = { showLogoutDialog = true },
        onRefreshCookieClick = { refreshCookieManually() },
        onLogExport = { com.hnnujw.course.utils.LogUtils.exportLogs(context) },
        onWallpaperSelect = { showWallpaperDialog = true },
        wallpaperName = currentWallpaperName,
        themeName = AppearanceSettingsManager.themeMode.label,
        onThemeSelect = { showThemeDialog = true },
        // 启动页选择器：二课 Tab 已恢复，无需兜底
        startupPageName = startupPage.label,
        onStartupPageSelect = { showStartupPageDialog = true },
        glassEffectEnabled = AppearanceSettingsManager.glassEffectEnabled,
        onGlassEffectChange = { AppearanceSettingsManager.updateGlassEffect(it) },
        messageUnread = messageUnread,
        announcementUnread = announcementUnread,
        onAnnouncements = { context.startActivity(Intent(context, com.hnnujw.course.AnnouncementActivity::class.java)) },
        isSuper = isSuper,
        canRefreshCookie = canRefreshCookie,
        isRefreshingCookie = isRefreshingCookie,
        academicSystemName = com.hnnujw.course.academic.AcademicCapabilities.name(UserManager.getInstance().currentSchool?.academicSystem),
        notificationsAllowed = notificationsAllowed,
        onNotificationSettings = { MainActivity.openNotificationSettings(context) },
        onMessageCenter = { context.startActivity(Intent(context, MessageCenterActivity::class.java)) },
        onXuegongSystem = {
            runCatching { context.startActivity(Intent(context, com.hnnujw.course.XuegongActivity::class.java)) }
                .onFailure { GlassToaster.show("无法打开学工系统页面") }
        },
        onKaojiRegistration = {
            runCatching { context.startActivity(Intent(context, com.hnnujw.course.KaojiActivity::class.java)) }
                .onFailure { GlassToaster.show("无法打开考级报名页面") }
        },
        onCheckUpdate = { checkForUpdate() },
        onOpenManual = {
            runCatching { context.startActivity(Intent(context, com.hnnujw.course.ManualActivity::class.java)) }
                .onFailure { GlassToaster.show("无法打开用户手册") }
        },
        onRecommendedSites = { showSitesDialog = true },
        onShareApp = { shareAppToClassmates() },
        onStarProject = { openInBrowser("https://github.com/Elisoar111/hnnu-jiaowu-app") },
        onStarUpstream = { openInBrowser("https://github.com/znjhahaha/zhengfang-apk") },
        onContactDeveloper = { showContactDialog = true },
        onJoinQqChannel = { joinQqChannel() },
        onAbout = { showAboutDialog = true },
        hasAvatar = hasAvatar,
        avatarRefreshKey = avatarRefreshKey,
        // 先要权限、再选图：见 [requestAvatarPicker] 的说明。
        onAvatarClick = { requestAvatarPicker() }
    )
    if (showThemeDialog) {
        com.hnnujw.course.ui.screen.AppThemeSettingsDialog { showThemeDialog = false }
    }
    if (showStartupPageDialog) {
        com.hnnujw.course.ui.screen.StartupPageSettingsDialog(
            page = startupPage,
            onPageChange = {
                startupPagePreferences.write(it)
                startupPage = it
            },
            onDismiss = { showStartupPageDialog = false }
        )
    }
    if (showWallpaperDialog) {
        com.hnnujw.course.ui.screen.WallpaperSettingsDialog(
            onDismiss = { showWallpaperDialog = false }
        )
    }

    // 头像不再自己申请相册权限：照片选择器由系统进程代理读图，App 不需要权限。
    // 原先那块「需要相册读取权限」的引导弹窗（含跳系统设置那一支）随之整体移除。

    // Dialogs
    if (showLogoutDialog) {
        SimpleConfirmDialog(
            title = "退出登录",
            text = "确定要退出登录吗？",
            onConfirm = { 
                performLogout() 
                showLogoutDialog = false
            },
            onDismiss = { showLogoutDialog = false }
        )
    }
    
    if (showClearCacheDialog) {
        SimpleConfirmDialog(
            title = "清除缓存",
            text = "确定要清除所有本地缓存数据吗？",
            onConfirm = {
                // 之前这里只弹了一句 Toast、什么都没清。现在真正落地清理：
                // 课程列表缓存 + 成绩/考试缓存 + 消息中心缓存 + 课表缓存。
                // 注意不动账号、密码、激活码、外观与提醒设置等"配置"类数据。
                val cacheAccountKey = UserManager.getInstance().currentAccountStorageKey
                com.hnnujw.course.manager.CourseCacheManager.clearCache(context)
                if (cacheAccountKey.isNotBlank()) {
                    com.hnnujw.course.manager.GradesCacheManager.clearAccount(context, cacheAccountKey)
                    com.hnnujw.course.academic.MessageCenterManager.clearCache(context, cacheAccountKey)
                }
                context.getSharedPreferences("schedule_cache", android.content.Context.MODE_PRIVATE)
                    .edit().clear().apply()
                com.hnnujw.course.ui.system.PageDataClearSignal.bump()
                GlassToaster.show("缓存已清除")
                showClearCacheDialog = false
            },
            onDismiss = { showClearCacheDialog = false }
        )
    }
    
    if (showAccountManagerDialog) {
        AccountManagerDialog(
            accounts = allAccounts,
            currentAccountKey = currentAccountKey,
            accountsWithPassword = accountsWithPassword,
            onSwitchAccount = { accountKey ->
                switchAccount(accountKey) { showAccountManagerDialog = false }
            },
            onDeletePassword = { pendingPasswordDelete = it },
            onDeleteAccount = { pendingAccountDelete = it },
            onAddAccount = {
                showAccountManagerDialog = false
                launchRelogin()
            },
            onDismiss = { showAccountManagerDialog = false }
        )
    }

    // ── 检查更新：发现新版本时的液态玻璃弹窗（应用内下载 + 唤起安装） ──
    updateInfo?.let { info ->
        com.hnnujw.course.ui.update.AppUpdateDialog(
            info = info,
            currentVersion = currentVersion,
            onDismiss = { com.hnnujw.course.network.UpdateCenter.dismiss(context) }
        )
    }

    // ── 推荐网站：点条目用系统默认浏览器打开 ────────────────────────
    if (showSitesDialog) {
        SystemDialog(
            onDismissRequest = { showSitesDialog = false },
            title = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "推荐网站",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = "点击后使用手机默认浏览器打开",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            },
            confirmButton = {
                SystemPrimaryButton(
                    text = "关闭",
                    onClick = { showSitesDialog = false },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 380.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                com.hnnujw.course.ui.screen.recommendedSites.forEach { site ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                showSitesDialog = false
                                openInBrowser(site.second)
                            },
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Column(Modifier.padding(horizontal = 14.dp, vertical = 11.dp)) {
                            Text(
                                text = site.first,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = site.second.removePrefix("https://"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }

    // ── 联系开发者：GitHub Issue / 邮件 ─────────────────────────────
    if (showContactDialog) {
        SystemDialog(
            onDismissRequest = { showContactDialog = false },
            title = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "联系开发者",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = "反馈问题、提出建议，或直接发邮件",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            },
            confirmButton = {
                SystemPrimaryButton(
                    text = "关闭",
                    onClick = { showContactDialog = false },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            showContactDialog = false
                            openInBrowser("https://github.com/Elisoar111/hnnu-jiaowu-app/issues")
                        },
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 11.dp)) {
                        Text(
                            text = "GitHub Issue（推荐）",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "提交问题与建议，进度公开可追踪",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            showContactDialog = false
                            val mail = Intent(Intent.ACTION_SENDTO).apply {
                                data = Uri.parse("mailto:elisoar@qq.com")
                                putExtra(Intent.EXTRA_SUBJECT, "校园助理反馈")
                            }
                            runCatching { context.startActivity(mail) }
                                .onFailure { GlassToaster.show("未找到可以发送邮件的应用") }
                        },
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 11.dp)) {
                        Text(
                            text = "发邮件给作者",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "elisoar@qq.com · 建议附上「导出日志」里的日志文件",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }

    // ── 关于项目 ────────────────────────────────────────────────────
    if (showAboutDialog) {
        SystemDialog(
            onDismissRequest = { showAboutDialog = false },
            title = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "关于项目",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = "校园助理 · v$currentVersion",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            },
            confirmButton = {
                SystemPrimaryButton(
                    text = "完成",
                    onClick = { showAboutDialog = false },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "开源、免费、无广告的高校教务客户端。" +
                        "课表、选课、成绩、第二课堂、教务消息一站式完成，" +
                        "全套液态玻璃界面，折射、色散与跟手形变实时渲染。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    lineHeight = 20.sp
                )
                AboutSection(title = "隐私与安全") {
                    Text(
                        text = "密码经系统密钥库加密，仅保存在本机；" +
                            "不采集、不上传任何使用数据，运行日志已脱敏。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        lineHeight = 20.sp
                    )
                }
                AboutSection(title = "项目地址") {
                    Text(
                        text = "GitHub：github.com/Elisoar111/hnnu-jiaowu-app\n" +
                            "Gitee：gitee.com/Elisoar/hnnu-jiaowu-app\n" +
                            "基于 GPL-3.0 协议开源，欢迎 Star 与参与贡献。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        lineHeight = 20.sp
                    )
                }
                AboutSection(title = "致谢") {
                    Text(
                        text = "本应用基于原作者 znjhahaha 的开源项目 zhengfang-apk " +
                            "修改与扩展而来，感谢原作者的慷慨开源。\n" +
                            "原项目：github.com/znjhahaha/zhengfang-apk",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        lineHeight = 20.sp
                    )
                }
                AboutSection(title = "反馈与声明") {
                    Text(
                        text = "问题与建议：GitHub Issue 或邮件 elisoar@qq.com\n" +
                            "本应用与学校官方无关，选课规则与数据以学校教务为准。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        lineHeight = 20.sp
                    )
                }
            }
        }
    }

    pendingPasswordDelete?.let { record ->
        SimpleConfirmDialog(
            title = "删除已保存的密码",
            text = "删除后「${record.displayName}」将无法在登录状态失效时自动续期，" +
                "需要你手动重新登录。账号本身与本地数据不会被删除。",
            confirmText = "删除密码",
            onConfirm = {
                deleteAccountPassword(record)
                pendingPasswordDelete = null
            },
            onDismiss = { pendingPasswordDelete = null }
        )
    }

    pendingAccountDelete?.let { record ->
        SimpleConfirmDialog(
            title = "删除账号",
            text = "将删除「${record.displayName}」的账号记录、已保存的密码、登录状态与本地课程缓存，" +
                "此操作不可恢复。设备绑定名额不会因此释放。",
            confirmText = "删除账号",
            onConfirm = {
                deleteAccountEntirely(record)
                pendingAccountDelete = null
                showAccountManagerDialog = false
            },
            onDismiss = { pendingAccountDelete = null }
        )
    }


    
}

/** 跳到本应用的系统设置页，让用户手动开权限（被"永久拒绝"后唯一可行的路）。 */
private fun openAppSettings(context: android.content.Context) {
    val intent = Intent(
        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        android.net.Uri.fromParts("package", context.packageName, null)
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
        .onFailure { GlassToaster.show("无法打开系统设置，请手动在系统设置里允许读取照片") }
}

@Composable
private fun AccountManagerDialog(
    accounts: List<UserManager.AccountRecord>,
    currentAccountKey: String,
    accountsWithPassword: Set<String>,
    onSwitchAccount: (String) -> Unit,
    onDeletePassword: (UserManager.AccountRecord) -> Unit,
    onDeleteAccount: (UserManager.AccountRecord) -> Unit,
    onAddAccount: () -> Unit,
    onDismiss: () -> Unit
) {
    SystemDialog(
        onDismissRequest = onDismiss,
        title = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "账号管理",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = "点账号卡切换 · 空槽位登录新账号（最多 3 个）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        },
        confirmButton = {
            SystemPrimaryButton(
                text = "完成",
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth()
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 440.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            accounts.forEach { account ->
                val isCurrent = account.key == currentAccountKey
                val hasPassword = accountsWithPassword.contains(account.key)
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !isCurrent) { onSwitchAccount(account.key) },
                    color = if (isCurrent) NeuPrimary.copy(alpha = 0.12f)
                        else MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Text(
                                    text = account.displayName,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "${account.accountIdText} · " +
                                        if (account.loginMode == "password") "密码登录" else "Cookie 登录",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = account.schoolName.ifBlank { "未记录学校" },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            SystemStatusBadge(
                                text = if (hasPassword) "已存密码" else "未存密码",
                                tone = if (hasPassword) SystemTone.Success else SystemTone.Neutral
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (isCurrent) {
                                SystemStatusBadge(text = "当前账号", tone = SystemTone.Info)
                            } else {
                                AccountActionButton(
                                    text = "切换到此账号",
                                    onClick = { onSwitchAccount(account.key) }
                                )
                            }
                            Spacer(modifier = Modifier.weight(1f))
                            if (hasPassword) {
                                AccountActionButton(
                                    text = "删除密码",
                                    onClick = { onDeletePassword(account) }
                                )
                            }
                            AccountActionButton(
                                text = "删除账号",
                                tint = com.hnnujw.course.ui.theme.SemanticDanger,
                                onClick = { onDeleteAccount(account) }
                            )
                        }
                    }
                }
            }

            // 空槽位：点一下跳登录页，登录后即绑定新账号（上限 3）。
            repeat((3 - accounts.size).coerceAtLeast(0)) { _ ->
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onAddAccount),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.45f),
                    shape = RoundedCornerShape(16.dp),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        NeuPrimary.copy(alpha = 0.35f)
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Surface(
                            shape = androidx.compose.foundation.shape.CircleShape,
                            color = NeuPrimary.copy(alpha = 0.12f)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Add,
                                contentDescription = null,
                                tint = NeuPrimary,
                                modifier = Modifier.padding(6.dp).size(20.dp)
                            )
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = "登录其它账号",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "点这里跳转登录页，登录后自动绑定到本机",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            Text(
                text = "密码经系统密钥库加密后仅保存在本机，用于登录状态失效时自动续期；" +
                    "退出登录不会删除它。删除账号不会释放设备绑定名额。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 18.sp
            )
        }
    }
}

/** 「关于项目」的分节：小号加粗标题 + 正文。 */
@Composable
private fun AboutSection(
    title: String,
    content: @Composable () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = com.hnnujw.course.ui.theme.NeuPrimary
        )
        content()
    }
}

/** 账号卡里的小动作按钮：轻量胶囊，避免三个实心按钮在一行里互相抢注意力。 */
@Composable
private fun AccountActionButton(
    text: String,
    tint: Color = NeuPrimary,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier.clickable(onClick = onClick),
        color = tint.copy(alpha = 0.12f),
        shape = RoundedCornerShape(999.dp)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = tint
        )
    }
}

@Composable
fun SimpleConfirmDialog(
    title: String, 
    text: String, 
    onConfirm: () -> Unit, 
    onDismiss: () -> Unit,
    confirmText: String = "确定",
    showCancel: Boolean = true
) {
    SystemConfirmDialog(
        title = title,
        text = text,
        onConfirm = onConfirm,
        onDismiss = onDismiss,
        confirmText = confirmText,
        showCancel = showCancel
    )
}


