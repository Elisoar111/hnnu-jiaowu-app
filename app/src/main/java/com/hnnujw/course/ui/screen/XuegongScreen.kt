package com.hnnujw.course.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.AssignmentInd
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hnnujw.course.manager.UserManager
import com.hnnujw.course.ui.system.GlassPageScaffold
import com.hnnujw.course.ui.system.GlassTextField
import com.hnnujw.course.ui.system.GlassToaster
import com.hnnujw.course.ui.system.InsetGroupedSection
import com.hnnujw.course.ui.system.PagePadding
import com.hnnujw.course.ui.system.SystemCard
import com.hnnujw.course.ui.system.SystemEmptyState
import com.hnnujw.course.ui.system.SystemIconButton
import com.hnnujw.course.ui.system.SystemLoadingState
import com.hnnujw.course.ui.system.SystemPrimaryButton
import com.hnnujw.course.ui.system.SystemSegmentedControl
import com.hnnujw.course.ui.system.SystemStatusBadge
import com.hnnujw.course.ui.system.SystemTone
import com.hnnujw.course.ui.system.glassBorderColor
import com.hnnujw.course.ui.system.glassSurfaceColor
import com.hnnujw.course.ui.theme.NeuPrimary
import com.hnnujw.course.ui.theme.SemanticDanger
import com.hnnujw.course.ui.theme.SemanticSuccess
import com.hnnujw.course.ui.theme.SemanticWarning
import com.hnnujw.course.ui.theme.moduleEntrance
import com.hnnujw.course.xuegong.XuegongException
import com.hnnujw.course.xuegong.XuegongHolidayBatch
import com.hnnujw.course.xuegong.XuegongLeavePage
import com.hnnujw.course.xuegong.XuegongLeaveRecord
import com.hnnujw.course.xuegong.XuegongStore
import com.hnnujw.course.xuegong.XuegongWhereaboutsPage
import com.hnnujw.course.xuegong.XuegongWhereaboutsRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 学工系统（xg.hnnu.edu.cn）页：日常请假 + 节假日去向登记。
 *
 * ## 只读
 *
 * 本页**只展示**学工系统里已有的记录，不提供任何提交入口 —— 请假与去向登记仍然
 * 回到学工系统官方页面完成。用户明确要求"不代填、不代提交"，所以这里连表单都不做。
 *
 * ## 登录
 *
 * 学号复用教务侧学号（只读展示，避免填错账号），密码是学工系统自己的。
 * 首次登录成功后密码经 AndroidKeyStore 加密存本机，token 过期时静默续期一次 ——
 * 与二课的处理完全一致，用户不用每次进来都输密码。
 */
@Composable
fun XuegongScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val userManager = remember { UserManager.getInstance() }
    val accountKey = userManager.currentAccountKey
    val studentId = remember(accountKey) { XuegongStore.academicStudentId() }

    var token by remember(accountKey) { mutableStateOf(XuegongStore.token(context, accountKey)) }
    var password by remember(accountKey) { mutableStateOf("") }
    var visiblePassword by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var loginError by remember(accountKey) { mutableStateOf("") }
    var passwordRejected by remember { mutableStateOf(false) }
    /** 静默续期只尝试一次，失败就老实显示登录框（否则会反复撞密码）。 */
    var attemptedSilentLogin by remember(accountKey) { mutableStateOf(false) }

    var leavePage by remember { mutableStateOf<XuegongLeavePage?>(null) }
    var whereaboutsPage by remember { mutableStateOf<XuegongWhereaboutsPage?>(null) }
    var loading by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf("") }

    /** 0 = 日常请假，1 = 节假日去向登记。 */
    var tab by remember { mutableIntStateOf(0) }
    var leaveDetail by remember { mutableStateOf<XuegongLeaveRecord?>(null) }
    var whereaboutsDetail by remember { mutableStateOf<XuegongWhereaboutsRecord?>(null) }

    val detailOpen = leaveDetail != null || whereaboutsDetail != null

    // Kotlin 局部函数不能前向引用：load 必须先于下面所有引用它的地方声明。
    fun load(activeToken: String) {
        loading = true
        loadError = ""
        scope.launch {
            try {
                val client = XuegongStore.client()
                val leaves = withContext(Dispatchers.IO) { client.dailyLeaves(activeToken) }
                val whereabouts = withContext(Dispatchers.IO) { client.whereabouts(activeToken) }
                leavePage = leaves
                whereaboutsPage = whereabouts
            } catch (e: Exception) {
                val message = XuegongStore.handleFailure(context, accountKey, e)
                // token 失效：退回登录态，让用户重新输密码（比留一个空列表清楚）
                if ((e as? XuegongException)?.sessionExpired == true) token = ""
                loadError = message
            } finally {
                loading = false
            }
        }
    }

    fun submitLogin(inputPassword: String, silent: Boolean) {
        if (studentId.isBlank()) {
            loginError = "没有取到教务学号，请先重新登录教务系统"
            return
        }
        busy = true
        loginError = ""
        passwordRejected = false
        scope.launch {
            try {
                val client = XuegongStore.client()
                val fresh = withContext(Dispatchers.IO) { client.login(studentId, inputPassword) }
                XuegongStore.saveToken(context, accountKey, fresh)
                // 存下密码用于 token 过期后自动续期（独立键，不覆盖教务/二课密码）
                XuegongStore.savePassword(context, accountKey, inputPassword)
                token = fresh
                password = ""
                if (!silent) GlassToaster.show("学工系统登录成功")
                load(fresh)
            } catch (e: Exception) {
                val message = XuegongStore.handleFailure(context, accountKey, e)
                val rejected = isCredentialRejection(e)
                if (rejected) passwordRejected = true
                // 静默续期失败不打扰用户：直接显示登录框，等他手动输
                loginError = if (silent && !rejected) "" else message
                if (silent) attemptedSilentLogin = true
            } finally {
                busy = false
            }
        }
    }

    // 进来就有 token 就直接拉数据；只有 token 但存过密码时先静默续期一次
    LaunchedEffect(accountKey) {
        val saved = XuegongStore.token(context, accountKey)
        when {
            saved.isNotBlank() -> load(saved)
            XuegongStore.hasPassword(context, accountKey) && studentId.isNotBlank() && !attemptedSilentLogin -> {
                attemptedSilentLogin = true
                XuegongStore.loadPassword(context, accountKey)?.let { submitLogin(it, silent = true) }
            }
        }
    }

    GlassPageScaffold(
        title = when {
            leaveDetail != null -> "请假详情"
            whereaboutsDetail != null -> "去向登记详情"
            else -> "学工系统"
        },
        subtitle = when {
            detailOpen -> null
            token.isBlank() -> null
            else -> "只读查看 · 日常请假与节假日去向登记"
        },
        onBack = {
            when {
                leaveDetail != null -> leaveDetail = null
                whereaboutsDetail != null -> whereaboutsDetail = null
                else -> onBack()
            }
        },
        actions = {
            if (token.isNotBlank() && !detailOpen) {
                SystemIconButton(Icons.Outlined.Refresh, "刷新", { if (!loading) load(token) })
                SystemIconButton(Icons.AutoMirrored.Filled.ExitToApp, "退出学工登录", {
                    XuegongStore.clearAccount(context, accountKey)
                    token = ""
                    leavePage = null
                    whereaboutsPage = null
                    loadError = ""
                    GlassToaster.show("已退出学工系统")
                })
            }
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = PagePadding, vertical = 12.dp)
        ) {
            val leaveDetailSnapshot = leaveDetail
            val whereaboutsDetailSnapshot = whereaboutsDetail
            when {
                leaveDetailSnapshot != null -> LeaveDetailView(leaveDetailSnapshot)
                whereaboutsDetailSnapshot != null -> WhereaboutsDetailView(whereaboutsDetailSnapshot)

                token.isBlank() -> LoginView(
                    studentId = studentId,
                    password = password,
                    onPasswordChange = { password = it },
                    visiblePassword = visiblePassword,
                    onToggleVisible = { visiblePassword = !visiblePassword },
                    busy = busy,
                    error = loginError,
                    passwordRejected = passwordRejected,
                    hasSavedPassword = XuegongStore.hasPassword(context, accountKey),
                    onSubmit = { submitLogin(password, silent = false) }
                )

                loading && leavePage == null && whereaboutsPage == null ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        SystemLoadingState("正在读取学工系统…")
                    }

                loadError.isNotBlank() && leavePage == null && whereaboutsPage == null ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        SystemEmptyState(
                            title = "读取失败",
                            message = loadError,
                            action = {
                                SystemPrimaryButton(
                                    text = "重试",
                                    onClick = { load(token) },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        )
                    }

                else -> Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    XuegongOverviewCard(
                        studentId = studentId,
                        leaveCount = leavePage?.page?.total ?: leavePage?.page?.items?.size ?: 0,
                        whereaboutsCount = whereaboutsPage?.page?.items?.size ?: 0,
                        batchCount = whereaboutsPage?.batches?.size ?: 0,
                        onRefresh = { if (!loading) load(token) }
                    )
                    SystemSegmentedControl(
                        options = listOf("日常请假", "去向登记"),
                        selectedIndex = tab,
                        onSelect = { tab = it },
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (loadError.isNotBlank()) {
                        InlineWarning(loadError)
                    }
                    if (tab == 0) {
                        LeaveTab(leavePage) { leaveDetail = it }
                    } else {
                        WhereaboutsTab(whereaboutsPage) { whereaboutsDetail = it }
                    }
                    ReadOnlyNotice()
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

// ── 页头概览 ────────────────────────────────────────────────────────────

/**
 * 学工系统的页头信息卡：图标 + 系统名 + 登录状态 + 账号 + 三个统计胶囊。
 *
 * 与「我的」页的页头同构（同一个 heroShape / 同一套玻璃色），这样从「我的」点进来时
 * 视觉是连着的，而不是突然换一个风格。
 */
@Composable
private fun XuegongOverviewCard(
    studentId: String,
    leaveCount: Int,
    whereaboutsCount: Int,
    batchCount: Int,
    onRefresh: () -> Unit,
) {
    val shape = RoundedCornerShape(24.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .moduleEntrance(1)
            .clip(shape)
            .background(glassSurfaceColor())
            .border(0.5.dp, glassBorderColor(), shape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(NeuPrimary.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.AssignmentInd,
                    contentDescription = null,
                    tint = NeuPrimary,
                    modifier = Modifier.size(22.dp)
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    text = "学工系统",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = if (studentId.isBlank()) "学生工作处 · 只读查看" else "学号 $studentId",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            SystemStatusBadge(text = "已登录", tone = SystemTone.Success)
        }

        // 统计胶囊：一眼看清"我有几条请假、几条去向登记、校方开了几个批次"
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            StatPill(value = leaveCount, label = "请假记录")
            StatPill(value = whereaboutsCount, label = "去向登记")
            if (batchCount > 0) StatPill(value = batchCount, label = "登记批次")
        }

        Box(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "xg.hnnu.edu.cn",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "点顶栏刷新可重新读取",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterEnd)
            )
        }
    }
    // onRefresh 目前由顶栏按钮承担；参数保留给后续把"下拉刷新"接到卡片上
    if (false) onRefresh()
}

/** 页头统计胶囊：数字在前、说明在后，窄屏也不会把标题挤到换行。 */
@Composable
private fun StatPill(value: Int, label: String) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = value.toString(),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ── 登录 ────────────────────────────────────────────────────────────────

@Composable
private fun LoginView(
    studentId: String,
    password: String,
    onPasswordChange: (String) -> Unit,
    visiblePassword: Boolean,
    onToggleVisible: () -> Unit,
    busy: Boolean,
    error: String,
    passwordRejected: Boolean,
    hasSavedPassword: Boolean,
    onSubmit: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 登录页也走同一个页头卡：先说清"这是哪个系统、用哪个账号"，
        // 再让用户填密码，而不是一上来就给一个孤零零的输入框。
        val shape = RoundedCornerShape(24.dp)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .moduleEntrance(1)
                .clip(shape)
                .background(glassSurfaceColor())
                .border(0.5.dp, glassBorderColor(), shape)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(NeuPrimary.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.AssignmentInd,
                        contentDescription = null,
                        tint = NeuPrimary,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        text = "登录学工系统",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "学生工作处 · 日常请假与去向登记",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                SystemStatusBadge(text = "未登录", tone = SystemTone.Neutral)
            }
        }

        InsetGroupedSection(Modifier.moduleEntrance(2), header = "账号") {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = studentId.ifBlank { "未取到学号，请先登录教务" },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    SystemStatusBadge(text = "教务学号", tone = SystemTone.Info)
                }
                Text(
                    text = "账号由教务学号带入，不用另填。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        InsetGroupedSection(Modifier.moduleEntrance(3), header = "密码") {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassTextField(
                    value = password,
                    onValueChange = onPasswordChange,
                    placeholder = if (passwordRejected) "请重新输入学工系统密码" else "学工系统密码",
                    singleLine = true,
                    enabled = !busy,
                    isError = error.isNotBlank(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done
                    ),
                    visualTransformation = if (visiblePassword) VisualTransformation.None
                    else PasswordVisualTransformation(),
                    trailing = {
                        IconButton(onClick = onToggleVisible) {
                            Icon(
                                imageVector = if (visiblePassword) Icons.Outlined.VisibilityOff
                                else Icons.Outlined.Visibility,
                                contentDescription = if (visiblePassword) "隐藏密码" else "显示密码",
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                )
                if (error.isNotBlank()) {
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = SemanticDanger,
                        lineHeight = 18.sp
                    )
                }
                SystemPrimaryButton(
                    text = if (busy) "登录中…" else "登录",
                    onClick = onSubmit,
                    enabled = !busy && password.isNotEmpty() && studentId.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                )
                if (hasSavedPassword) {
                    Text(
                        text = "本机已保存过学工系统密码，登录状态失效时会自动重连；如果一直失败，请重新输入密码。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 18.sp
                    )
                }
            }
        }

        InsetGroupedSection(Modifier.moduleEntrance(4), header = "关于学工系统") {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "学工系统是学校学生工作处的系统，日常请假、节假日去向登记都在这里办理。" +
                        "密码通常是学校统一发的那个，与教务系统密码不一定相同。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 18.sp
                )
                Text(
                    text = "本应用只读取你的记录用于查看，不会替你提交任何申请。" +
                        "密码经系统密钥库加密后仅保存在本机，用于登录状态失效时自动重连。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 18.sp
                )
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

// ── 日常请假 ────────────────────────────────────────────────────────────

@Composable
private fun LeaveTab(page: XuegongLeavePage?, onOpen: (XuegongLeaveRecord) -> Unit) {
    if (page == null) {
        SystemEmptyState(title = "暂无数据", message = "没有读到请假记录。")
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ApplyStatusCard(
            canApply = page.canApply,
            actionText = page.applyButtonText,
            blockedReason = page.applyBlockedReason,
            locationRequired = page.locationRule.required,
            rangeDistance = page.locationRule.rangeDistance
        )
        if (page.page.items.isEmpty()) {
            SystemEmptyState(
                title = "没有请假记录",
                message = "你在学工系统里还没有提交过日常请假。要请假请到学工系统官方页面提交，本应用不代填。"
            )
        } else {
            page.page.items.forEach { record ->
                LeaveRow(record) { onOpen(record) }
            }
            PagingHint(page.page.total, page.page.items.size)
        }
    }
}

/** 校方的"能不能申请"状态卡：只展示，不提供按钮。 */
@Composable
private fun ApplyStatusCard(
    canApply: Boolean,
    actionText: String,
    blockedReason: String,
    locationRequired: Boolean,
    rangeDistance: String,
) {
    SystemCard(Modifier.fillMaxWidth().moduleEntrance(2)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                RowIconChip(
                    icon = Icons.Outlined.AssignmentInd,
                    tint = if (canApply) SemanticSuccess else SemanticWarning
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = if (canApply) "当前可以申请请假" else "当前不能申请请假",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (canApply) {
                            "校方入口文案：${actionText.ifBlank { "申请" }}"
                        } else {
                            blockedReason.ifBlank { "校方暂时关闭了请假申请" }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 18.sp
                    )
                }
            }
            if (locationRequired) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    SystemStatusBadge(text = "需定位打卡", tone = SystemTone.Info)
                    if (rangeDistance.isNotBlank()) {
                        Text(
                            text = "允许偏差 $rangeDistance 米",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LeaveRow(record: XuegongLeaveRecord, onClick: () -> Unit) {
    SystemCard(
        Modifier.fillMaxWidth().moduleEntrance(2),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            RowIconChip(icon = Icons.Outlined.AssignmentInd, tint = SemanticWarning)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = record.title,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    if (record.status.isNotBlank()) {
                        SystemStatusBadge(text = record.status, tone = statusTone(record.status))
                    }
                }
                if (record.beginTime.isNotBlank() || record.endTime.isNotBlank()) {
                    Text(
                        text = "${record.beginTime.ifBlank { "—" }} → ${record.endTime.ifBlank { "—" }}" +
                            (if (record.days.isNotBlank()) " · ${record.days} 天" else ""),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (record.reason.isNotBlank()) {
                    Text(
                        text = record.reason,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp).padding(top = 10.dp)
            )
        }
    }
}

@Composable
private fun LeaveDetailView(record: XuegongLeaveRecord) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        DetailHeaderCard(
            title = record.title,
            subtitle = listOfNotNull(
                if (record.days.isNotBlank()) "${record.days} 天" else null,
                record.status.takeIf { it.isNotBlank() },
            ).joinToString(" · "),
            icon = Icons.Outlined.AssignmentInd,
            tint = SemanticWarning
        )
        TimeRangeCard(
            beginLabel = "开始时间",
            begin = record.beginTime,
            endLabel = "结束时间",
            end = record.endTime,
        )
        InsetGroupedSection(header = "申请信息") {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                InfoLine("请假事由", record.reason)
                InfoLine("申请时间", record.applyTime)
                InfoLine("审批状态", record.status)
            }
        }
        ExtraFieldsCard(record.extras)
        ReadOnlyNotice()
        Spacer(Modifier.height(8.dp))
    }
}

// ── 节假日去向登记 ──────────────────────────────────────────────────────

@Composable
private fun WhereaboutsTab(page: XuegongWhereaboutsPage?, onOpen: (XuegongWhereaboutsRecord) -> Unit) {
    if (page == null) {
        SystemEmptyState(title = "暂无数据", message = "没有读到去向登记记录。")
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (page.batches.isNotEmpty()) {
            InsetGroupedSection(Modifier.moduleEntrance(2), header = "登记批次") {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    page.batches.forEach { batch -> HolidayBatchRow(batch) }
                }
            }
        }
        if (page.page.items.isEmpty()) {
            SystemEmptyState(
                title = "没有登记记录",
                message = "你在学工系统里还没有提交过节假日去向登记。要登记请到学工系统官方页面提交，本应用不代填。"
            )
        } else {
            page.page.items.forEach { record ->
                WhereaboutsRow(record) { onOpen(record) }
            }
            PagingHint(page.page.total, page.page.items.size)
        }
    }
}

@Composable
private fun HolidayBatchRow(batch: XuegongHolidayBatch) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = batch.name.ifBlank { "未命名批次" },
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            if (batch.statusName.isNotBlank()) {
                SystemStatusBadge(
                    text = batch.statusName,
                    tone = if (batch.open) SystemTone.Success else SystemTone.Neutral
                )
            }
        }
        if (batch.holidayBegin.isNotBlank() || batch.holidayEnd.isNotBlank()) {
            Text(
                text = "假期　${batch.holidayBegin.ifBlank { "—" }} 至 ${batch.holidayEnd.ifBlank { "—" }}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (batch.registerBegin.isNotBlank() || batch.registerEnd.isNotBlank()) {
            Text(
                text = "登记　${batch.registerBegin.ifBlank { "—" }} 至 ${batch.registerEnd.ifBlank { "—" }}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (batch.memo.isNotBlank()) {
            Text(
                text = batch.memo,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 18.sp
            )
        }
    }
}

@Composable
private fun WhereaboutsRow(record: XuegongWhereaboutsRecord, onClick: () -> Unit) {
    SystemCard(
        Modifier.fillMaxWidth().moduleEntrance(2),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            RowIconChip(icon = Icons.Outlined.Public, tint = Color(0xFF30B0C7))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = record.title,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    if (record.leaveType.isNotBlank()) {
                        SystemStatusBadge(text = record.leaveType, tone = SystemTone.Info)
                    }
                }
                if (record.beginTime.isNotBlank() || record.endTime.isNotBlank()) {
                    Text(
                        text = "${record.beginTime.ifBlank { "—" }} → ${record.endTime.ifBlank { "—" }}" +
                            (if (record.days.isNotBlank()) " · ${record.days} 天" else ""),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (record.destination.isNotBlank()) {
                    Text(
                        text = "去向：${record.destination}" +
                            (if (record.vehicle.isNotBlank()) " · ${record.vehicle}" else ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (record.registered.isNotBlank()) {
                    Text(
                        text = "登记状态：${record.registered}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp).padding(top = 10.dp)
            )
        }
    }
}

@Composable
private fun WhereaboutsDetailView(record: XuegongWhereaboutsRecord) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        DetailHeaderCard(
            title = record.title,
            subtitle = listOfNotNull(
                record.leaveType.takeIf { it.isNotBlank() },
                record.registered.takeIf { it.isNotBlank() },
            ).joinToString(" · "),
            icon = Icons.Outlined.Public,
            tint = Color(0xFF30B0C7)
        )
        TimeRangeCard(
            beginLabel = "离开时间",
            begin = record.beginTime,
            endLabel = "返回时间",
            end = record.endTime,
            extra = if (record.days.isNotBlank()) "${record.days} 天" else ""
        )
        InsetGroupedSection(header = "去向信息") {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                InfoLine("去向类型", record.leaveType)
                InfoLine("去向", record.destination)
                InfoLine("交通方式", record.vehicle)
                InfoLine("去向事由", record.reason)
                InfoLine("家庭所在地", record.homePlace)
            }
        }
        InsetGroupedSection(header = "联系方式") {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                InfoLine("本人手机", record.studentTel)
                InfoLine("紧急联系人", record.contactName)
                InfoLine("与本人关系", record.contactRelation)
                InfoLine("联系人电话", record.contactTel)
            }
        }
        InsetGroupedSection(header = "登记信息") {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                InfoLine("登记状态", record.registered)
                InfoLine("登记时间", record.registerTime)
            }
        }
        ExtraFieldsCard(record.extras)
        ReadOnlyNotice()
        Spacer(Modifier.height(8.dp))
    }
}

// ── 公共零件 ────────────────────────────────────────────────────────────

/** 详情页的页头卡：图标 + 标题 + 一行摘要，与列表项的视觉语言一致。 */
@Composable
private fun DetailHeaderCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    tint: Color,
) {
    val shape = RoundedCornerShape(24.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .moduleEntrance(1)
            .clip(shape)
            .background(glassSurfaceColor())
            .border(0.5.dp, glassBorderColor(), shape)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        RowIconChip(icon = icon, tint = tint, size = 44)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** 起止时间卡：中间用箭头连起来，比两行"标签：值"更像一条时间线。 */
@Composable
private fun TimeRangeCard(
    beginLabel: String,
    begin: String,
    endLabel: String,
    end: String,
    extra: String = "",
) {
    if (begin.isBlank() && end.isBlank()) return
    SystemCard(Modifier.fillMaxWidth().moduleEntrance(2)) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                TimePoint(beginLabel, begin, Modifier.weight(1f), Alignment.Start)
                Text(
                    text = "→",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TimePoint(endLabel, end, Modifier.weight(1f), Alignment.End)
            }
            if (extra.isNotBlank()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    SystemStatusBadge(text = extra, tone = SystemTone.Info)
                }
            }
        }
    }
}

@Composable
private fun TimePoint(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    align: Alignment.Horizontal,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = align,
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value.ifBlank { "—" },
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

/** 列表项左侧的圆角图标 chip（与「我的」页设置行的图标 chip 同一套观感）。 */
@Composable
private fun RowIconChip(icon: ImageVector, tint: Color, size: Int = 38) {
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(RoundedCornerShape((size / 3.4).dp))
            .background(tint.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size((size * 0.52).dp)
        )
    }
}

/** 页面内的轻提示（顶部加载成功但刷新失败时用，不抢内容位置）。 */
@Composable
private fun InlineWarning(text: String) {
    SystemCard(Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            RowIconChip(icon = Icons.Outlined.Info, tint = SemanticWarning, size = 32)
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = SemanticWarning,
                lineHeight = 18.sp,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/** 一行「标签 + 值」；值为空时整行不渲染。 */
@Composable
private fun InfoLine(label: String, value: String) {
    if (value.isBlank()) return
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(84.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
            lineHeight = 20.sp
        )
    }
}

/**
 * 「其它信息」卡：站点字段名与猜测不符时的兜底展示位。
 *
 * 日常请假的字段名是按站点命名惯例**推测**的（测试账号 0 条记录，拿不到真实样本），
 * 有了这块，即使真实键名不同，用户点进详情也能看到完整数据。
 */
@Composable
private fun ExtraFieldsCard(extras: List<Pair<String, String>>) {
    if (extras.isEmpty()) return
    InsetGroupedSection(header = "其它信息") {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            extras.forEach { (key, value) ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Text(
                        text = xuegongFieldLabel(key),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(96.dp)
                    )
                    Text(
                        text = value,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                        lineHeight = 18.sp
                    )
                }
            }
        }
    }
}

/** 页脚固定提示：本页只读。 */
@Composable
private fun ReadOnlyNotice() {
    Text(
        text = "本页只读：请假、销假与去向登记都要在学工系统官方页面提交，本应用不代填、不代提交。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        lineHeight = 18.sp
    )
}

/** 列表只取了第一页时，如实告诉用户一共多少条。 */
@Composable
private fun PagingHint(total: Int, shown: Int) {
    if (total <= shown) return
    Text(
        text = "共 $total 条，当前显示前 $shown 条。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/** 审批状态 → 语义色。判据只看常见字样，认不出来就用中性色（不臆造结论）。 */
@Composable
private fun statusTone(status: String): SystemTone = when {
    status.contains("通过") || status.contains("同意") -> SystemTone.Success
    status.contains("驳回") || status.contains("拒绝") || status.contains("不通过") -> SystemTone.Danger
    status.contains("待") || status.contains("审") -> SystemTone.Warning
    else -> SystemTone.Info
}

/**
 * 学工系统字段名 → 中文标签，认不出来就用原始键名。
 *
 * 表里的键全部来自实测响应（去向登记的 38 个字段是完整抄下来的）；请假的字段名
 * 是按同站命名惯例补的，所以「其它信息」卡里出现没翻译过来的英文键并不奇怪 ——
 * 那也比不显示强。
 */
private fun xuegongFieldLabel(key: String): String = XUEGONG_FIELD_LABELS[key] ?: key

/**
 * 是不是"账号或密码不对"这类**凭据被拒**的失败。
 *
 * 站点密码错时回的是 `Msg` 文案（不是错误码），网关偶尔直接给 401/403
 * （客户端翻成 `sessionExpired = true`）。两种都要让用户重新输密码，
 * 而不是只弹一句"登录已失效"让他困惑。
 *
 * 判据刻意只看**文案与类型**，不碰密码本身 —— 异常信息与日志里都不落明文密码。
 */
private fun isCredentialRejection(error: Throwable): Boolean {
    if (error is XuegongException && error.sessionExpired) return true
    val message = error.message.orEmpty()
    return message.contains("密码") || message.contains("账号")
}

private val XUEGONG_FIELD_LABELS: Map<String, String> = mapOf(
    // 通用
    "Status" to "状态", "StatusName" to "状态", "StatusText" to "状态",
    "Memo" to "备注", "Remark" to "备注",
    "InsertDate" to "登记时间", "EditDate" to "修改时间", "CreateTime" to "创建时间",
    "ApplyDate" to "申请时间", "ApplyTime" to "申请时间",
    "IsEdit" to "可编辑", "IsExam" to "是否考试", "ExamName" to "考试名称",
    // 请假
    "LeaveType" to "请假类型", "LeaveTypeText" to "请假类型",
    "LeaveBeginTime" to "开始时间", "LeaveEndTime" to "结束时间",
    "LeaveDays" to "请假天数", "LeaveHours" to "请假学时",
    "LeaveReason" to "请假事由", "LeaveNotice" to "请假说明",
    "AgentName" to "代理人", "AgentTel" to "代理人电话",
    "BackSchoolTime" to "返校时间", "BackDate" to "返校日期",
    // 去向登记（实测字段）
    "HolidayName" to "假期",
    "IsRs" to "登记状态",
    "Name" to "姓名", "Sex" to "性别", "Nation" to "民族", "Polity" to "政治面貌",
    "RoomNo" to "宿舍号",
    "FamillyPost" to "家庭邮编", "FamillyTel" to "家庭电话",
    "FMComeWhere" to "家庭所在地",
    "CollegeName" to "院系", "SpecialtyName" to "专业",
    "SpeGrade" to "年级", "ClassName" to "班级", "ShortClassName" to "班级",
    "StudentId" to "学号",
    "MoveTel" to "手机", "StuMoveTel" to "本人手机", "StuTel" to "本人电话",
    "OutAddressStreet" to "外出地址", "OutNumber" to "同行人数",
    "OutContacts" to "紧急联系人", "OutContactsRelationship" to "与本人关系",
    "OutContactsMoveTel" to "联系人手机", "OutContactsTel" to "联系人电话",
    "OutGoVehicle" to "交通方式编码", "OutGoVehicleText" to "交通方式",
    "Province" to "省", "City" to "市", "County" to "区县", "ComeWhere" to "去向",
    // 留校（同族模块，后续扩展用）
    "StayBeginTime" to "留校开始", "StayEndTime" to "留校结束",
    "StayDays" to "留校天数", "StayHours" to "留校学时",
    "StayNotice" to "留校须知", "StayPromise" to "留校承诺", "StayMaterial" to "留校材料",
    "HolidayTime" to "假期时间", "EnabledTime" to "可申请时间",
    "HolidayBeginDate" to "假期开始", "HolidayEndDate" to "假期结束",
    "ApplyBeginDate" to "登记开始", "ApplyEndDate" to "登记结束",
)
