package com.tyust.course.ui.route

import com.tyust.course.ui.system.GlassToaster
import android.content.Intent
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
import com.tyust.course.ui.system.SystemDialog
import com.tyust.course.ui.system.SystemConfirmDialog
import com.tyust.course.ui.system.SystemSecondaryButton
import com.tyust.course.ui.system.SystemPrimaryButton
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import com.tyust.course.LoginActivity
import com.tyust.course.MessageCenterActivity
import com.tyust.course.login.PasswordLoginCallback
import com.tyust.course.login.PasswordLoginGatewayFactory
import com.tyust.course.manager.AppearanceSettingsManager
import com.tyust.course.manager.StartupPagePreferences
import com.tyust.course.manager.UserManager
import com.tyust.course.network.CourseApiClient
import com.tyust.course.ui.screen.SettingsScreen
import com.tyust.course.activation.ActivationManager
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
import com.tyust.course.ui.system.SystemStatusBadge
import com.tyust.course.ui.system.SystemTone
import com.tyust.course.ui.theme.NeuPrimary
import com.tyust.course.ui.theme.SemanticSuccess
import kotlinx.coroutines.launch

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
    val currentWallpaperName = com.tyust.course.manager.AppearanceSettingsManager.currentWallpaperName
    
    // 身份标识：配额展示已按要求整体移除，这里只保留"超级用户"徽标所需的判断
    var isSuper by remember { mutableStateOf(false) }
    // 账号管理走全量列表（跨学校）：这里是"管理"，不该被当前学校过滤掉
    var allAccounts by remember { mutableStateOf<List<UserManager.AccountRecord>>(emptyList()) }
    var accountsWithPassword by remember { mutableStateOf<Set<String>>(emptySet()) }
    var currentAccountKey by remember { mutableStateOf("") }
    var canRefreshCookie by remember { mutableStateOf(false) }
    val session by UserManager.getInstance().sessionState.state.collectAsState()
    var cookieUpdateFeedback by remember {
        mutableStateOf<Pair<com.tyust.course.manager.SessionToken, com.tyust.course.ui.system.SymbolResult>?>(null)
    }
    val cookieUpdateResult = cookieUpdateFeedback?.takeIf { it.first == session.token }?.second
        ?: com.tyust.course.ui.system.SymbolResult.None
    val recovery by com.tyust.course.utils.SessionRenewer.state.collectAsState()
    val isRefreshingCookie = recovery.token == session.token &&
        recovery.phase == com.tyust.course.utils.RecoveryPhase.Restoring
    val relogin = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { }

    // ── 自定义头像：相册 → 取景框裁剪页 → 落盘 ─────────────────────
    //
    // 与「自定义背景」走**同一条**路径（同一个 ImageCropActivity），
    // 区别只有两处：取景框是方形、输出写进头像文件。见 ImageCropActivity 的注释。
    var avatarRefreshKey by remember { mutableIntStateOf(0) }
    val hasAvatar = remember(avatarRefreshKey) { com.tyust.course.manager.UserAvatarStore.hasAvatar(context) }
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
        val output = com.tyust.course.manager.UserAvatarStore.avatarFile(context)
        avatarCropOutput = output
        cropLauncher.launch(
            com.tyust.course.ImageCropActivity.newIntent(
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
    
    // 版本号只用于页脚展示。应用内更新功能（含更新弹窗）已整体移除，
    // 这里直接读构建产物里的 VERSION_NAME，不再依赖 UpdateManager。
    val currentVersion = remember { com.tyust.course.BuildConfig.VERSION_NAME }

    // 第二课堂登录
    var showSecondClassLogin by remember { mutableStateOf(false) }
    var secondClassRefresh by remember { mutableIntStateOf(0) }
    // 未读消息红点：读的是全局 Compose state，消息中心里读掉一条这里就当帧消失
    val messageUnread = com.tyust.course.academic.MessageCenterManager.unread
    // 必须用 **storage key**：MessageCenterActivity 传给消息中心的就是它，
    // 缓存文件名按这个 key 生成。这里若用 currentAccountKey（未经 toStorageKey
    // 归一化），设置页读的是另一个文件，红点永远是 0。
    val messageAccountKey = remember(currentAccountKey, session.token) {
        if (isDemoMode) "" else UserManager.getInstance().currentAccountStorageKey
    }
    LaunchedEffect(messageAccountKey, isDemoMode) {
        if (isDemoMode || messageAccountKey.isBlank()) return@LaunchedEffect
        // 先亮出缓存里的红点，再按节流打一次网络（30 分钟内不重复请求）
        com.tyust.course.academic.MessageCenterManager.refreshUnreadFromCache(context, messageAccountKey)
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            com.tyust.course.academic.MessageCenterNotifier.check(
                context = context,
                school = UserManager.getInstance().currentSchool,
                accountKey = messageAccountKey
            )
        }
    }
    val secondClassSubtitle = remember(session.token, secondClassRefresh, currentAccountKey) {
        if (!com.tyust.course.secondclass.SecondClassroomStore.isAvailable(UserManager.getInstance().currentSchool)) {
            "当前学校未接入第二课堂"
        } else {
            val key = UserManager.getInstance().currentAccountKey
            val store = com.tyust.course.secondclass.SecondClassroomStore
            when {
                store.token(context, key).isNotBlank() -> "已登录 · ${store.academicStudentId()}"
                store.hasPassword(context, key) -> "已保存密码 · 登录状态失效时自动续期"
                else -> "用教务学号登录成绩单系统"
            }
        }
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
            com.tyust.course.manager.CourseCacheManager.clearAccountCache(context, storageKey)
        }
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
        com.tyust.course.utils.SessionRenewer.request(expected, manual = true) { result ->
            when (result) {
                is com.tyust.course.utils.SessionRecoveryResult.Recovered -> {
                    if (user.sessionState.isCurrent(result.token)) {
                        cookieUpdateFeedback = result.token to com.tyust.course.ui.system.SymbolResult.Success
                        refreshAccountUiState()
                        GlassToaster.show("登录状态已更新")
                    }
                }
                is com.tyust.course.utils.SessionRecoveryResult.NeedsLogin -> {
                    if (user.sessionState.isCurrent(expected)) {
                        cookieUpdateFeedback = expected to com.tyust.course.ui.system.SymbolResult.Failure
                        GlassToaster.show(
                        when (result.reason) {
                            com.tyust.course.utils.RecoveryFailure.Network -> "暂时无法连接，请稍后重试"
                            com.tyust.course.utils.RecoveryFailure.Storage -> "保存失败，请重试"
                            else -> "需要重新登录以更新登录状态"
                        }
                        )
                    }
                }
                com.tyust.course.utils.SessionRecoveryResult.Superseded -> Unit
            }
        }
    }
    
    SettingsScreen(
        studentName = studentName,
        studentId = deviceId,
        schoolName = schoolName,
        currentVersion = currentVersion,
        onSchoolSelect = {
            GlassToaster.show("本应用仅支持淮南师范学院")
        },
        onCookieConfig = {
            relogin.launch(Intent(context, LoginActivity::class.java).apply {
                putExtra("force_relogin", true)
                putExtra(LoginActivity.EXTRA_RETURN_TO_CALLER, true)
            })
        },
        onAccountManage = {
            if (isDemoMode) GlassToaster.show("本地演示模式不读取真实账号") else showAccountManagerDialog = true
        },
        savedAccountCount = allAccounts.size,
        onClearCache = { showClearCacheDialog = true },
        onLogout = { showLogoutDialog = true },
        onRefreshCookieClick = { refreshCookieManually() },
        onLogExport = { com.tyust.course.utils.LogUtils.exportLogs(context) },
        onWallpaperSelect = { showWallpaperDialog = true },
        wallpaperName = currentWallpaperName,
        themeName = AppearanceSettingsManager.themeMode.label,
        onThemeSelect = { showThemeDialog = true },
        startupPageName = startupPage.label,
        onStartupPageSelect = { showStartupPageDialog = true },
        glassEffectEnabled = AppearanceSettingsManager.glassEffectEnabled,
        onGlassEffectChange = { AppearanceSettingsManager.updateGlassEffect(it) },
        showClassRank = AppearanceSettingsManager.showClassRank,
        onShowClassRankChange = { AppearanceSettingsManager.updateShowClassRank(it) },
        messageUnread = messageUnread,
        isSuper = isSuper,
        canRefreshCookie = canRefreshCookie,
        isRefreshingCookie = isRefreshingCookie,
        academicSystemName = com.tyust.course.academic.AcademicCapabilities.name(UserManager.getInstance().currentSchool?.academicSystem),
        secondClassSubtitle = secondClassSubtitle,
        onSecondClassLogin = { showSecondClassLogin = true },
        onMessageCenter = { context.startActivity(Intent(context, MessageCenterActivity::class.java)) },
        hasAvatar = hasAvatar,
        avatarRefreshKey = avatarRefreshKey,
        // 先要权限、再选图：见 [requestAvatarPicker] 的说明。
        onAvatarClick = { requestAvatarPicker() }
    )
    com.tyust.course.ui.screen.SecondClassLoginHost(
        visible = showSecondClassLogin,
        onDismiss = { showSecondClassLogin = false },
        onLoggedIn = {
            showSecondClassLogin = false
            secondClassRefresh++
        }
    )
    if (showThemeDialog) {
        com.tyust.course.ui.screen.AppThemeSettingsDialog { showThemeDialog = false }
    }
    if (showStartupPageDialog) {
        com.tyust.course.ui.screen.StartupPageSettingsDialog(
            page = startupPage,
            onPageChange = {
                startupPagePreferences.write(it)
                startupPage = it
            },
            onDismiss = { showStartupPageDialog = false }
        )
    }
    if (showWallpaperDialog) {
        com.tyust.course.ui.screen.WallpaperSettingsDialog(
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
            onDismiss = { showAccountManagerDialog = false }
        )
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
                    text = "切换账号、管理已保存的密码",
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
                .heightIn(max = 420.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (accounts.isEmpty()) {
                Text(
                    text = "本机还没有保存任何账号。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                accounts.forEach { account ->
                    val isCurrent = account.key == currentAccountKey
                    val hasPassword = accountsWithPassword.contains(account.key)
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
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
                                        text = "切换",
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
                                    tint = com.tyust.course.ui.theme.SemanticDanger,
                                    onClick = { onDeleteAccount(account) }
                                )
                            }
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


