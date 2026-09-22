package com.hnnujw.course.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.neverEqualPolicy
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hnnujw.course.manager.UserManager
import com.hnnujw.course.ui.system.GlassDatePickerDialog
import com.hnnujw.course.ui.system.GlassDateTimePickerDialog
import com.hnnujw.course.ui.system.GlassOptionWheelDialog
import com.hnnujw.course.ui.system.GlassPageScaffold
import com.hnnujw.course.ui.system.GlassTextField
import com.hnnujw.course.ui.system.GlassToaster
import com.hnnujw.course.ui.system.InsetGroupedSection
import com.hnnujw.course.ui.system.PagePadding
import com.hnnujw.course.ui.system.SystemCard
import com.hnnujw.course.ui.system.SystemConfirmDialog
import com.hnnujw.course.ui.system.SystemEmptyState
import com.hnnujw.course.ui.system.SystemIconButton
import com.hnnujw.course.ui.system.SystemLoadingState
import com.hnnujw.course.ui.system.SystemPrimaryButton
import com.hnnujw.course.ui.system.SystemSecondaryButton
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
import com.hnnujw.course.xuegong.XuegongDictOption
import com.hnnujw.course.xuegong.XuegongException
import com.hnnujw.course.xuegong.XuegongHolidayBatch
import com.hnnujw.course.xuegong.XuegongLeaveDraft
import com.hnnujw.course.xuegong.XuegongLeavePage
import com.hnnujw.course.xuegong.XuegongLeaveRecord
import com.hnnujw.course.xuegong.XuegongStore
import com.hnnujw.course.xuegong.XuegongWhereaboutsDraft
import com.hnnujw.course.xuegong.XuegongWhereaboutsPage
import com.hnnujw.course.xuegong.XuegongWhereaboutsRecord
import com.hnnujw.course.xuegong.parseSiteTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 学工系统（xg.hnnu.edu.cn）页：日常请假 + 节假日去向登记。
 *
 * ## 查看 + 提交（1.2.5）
 *
 * 记录照旧**只读展示**；提交链路照官方 H5 表单页同形实现：
 * 请假（`/DailyLeave/SaveForm`）与去向登记（`/HolidayWhereabouts/SaveForm`）。
 * 表单骨架从站点 GET 拿（字典 / 默认值 / 附件上传地址都在里面），用户只改
 * 编辑字段，提交时原样回传 —— 不猜字段名、不臆造默认值。
 *
 * **没有**的入口：销假、审批、撤销、删除。这些仍然要去官方页面。
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

    // ── 提交表单（1.2.5）─────────────────────────────────────────────────
    /** 正在拉取表单骨架（点「写请假」/ 点开放批次后的一瞬间）。 */
    var formLoading by remember { mutableStateOf(false) }
    // ⚠️ 必须用 neverEqualPolicy：草稿是有 var 字段的可变对象，编辑是「原地改 + copy 回填」，
    // 改完的新旧 FormUi 结构相等，默认的 structuralEqualityPolicy 会把这次赋值当成"没变"
    // 而不触发重组 —— 症状就是去向类型/请假类型选不动、时间选完不显示（1.2.4 实测踩坑）。
    var leaveForm by remember { mutableStateOf<XuegongLeaveFormUi?>(null, neverEqualPolicy()) }
    var whereaboutsForm by remember { mutableStateOf<XuegongWhereaboutsFormUi?>(null, neverEqualPolicy()) }

    val detailOpen = leaveDetail != null || whereaboutsDetail != null ||
        leaveForm != null || whereaboutsForm != null

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

    // ── 表单：打开 / 上传 / 提交 ─────────────────────────────────────────
    //
    // 红线：表单骨架、字典、默认值全部来自站点 GET；提交时原样回传。
    // 这里没有任何"替用户做决定"的字段，也没有任何自动提交 ——
    // 只有用户点「提交」按钮才会发请求。

    /** 打开「写请假」表单：先拉骨架，再进编辑页。 */
    fun openLeaveForm() {
        if (formLoading || token.isBlank()) return
        formLoading = true
        scope.launch {
            try {
                val client = XuegongStore.client()
                val form = withContext(Dispatchers.IO) { client.dailyLeaveForm(token) }
                leaveForm = XuegongLeaveFormUi(draft = XuegongLeaveDraft.of(form), fileList = form.optJSONObject("FileList"))
            } catch (e: Exception) {
                loadError = XuegongStore.handleFailure(context, accountKey, e)
                GlassToaster.show("请假表单加载失败")
            } finally {
                formLoading = false
            }
        }
    }

    /**
     * 点开一个去向登记批次：开放中 → 表单；已登记 → 详情（由调用方按批次状态分发）。
     *
     * 表单打开后按**上一次登记记录**自动回填记忆字段（去向类型、交通方式、地点、
     * 联系人、电话等），但**绝不自动提交** —— 用户检查、修改确认后手动点提交。
     */
    fun openWhereaboutsForm(batch: XuegongHolidayBatch) {
        if (formLoading || token.isBlank()) return
        formLoading = true
        scope.launch {
            try {
                val client = XuegongStore.client()
                val form = withContext(Dispatchers.IO) {
                    client.whereaboutsForm(token, configId = batch.id)
                }
                val draft = XuegongWhereaboutsDraft.of(form)
                val memory = whereaboutsPage?.page?.items?.firstOrNull()
                if (memory != null) draft.applyMemory(memory)
                whereaboutsForm = XuegongWhereaboutsFormUi(
                    draft = draft,
                    batch = batch,
                    prefilled = memory != null,
                )
            } catch (e: Exception) {
                loadError = XuegongStore.handleFailure(context, accountKey, e)
                GlassToaster.show("登记表单加载失败")
            } finally {
                formLoading = false
            }
        }
    }

    /** 上传请假附件（用户从相册/文件选图后走这里）。 */
    fun uploadLeaveImage(uri: android.net.Uri) {
        val state = leaveForm ?: return
        if (state.uploading || state.submitting) return
        scope.launch {
            leaveForm = state.copy(uploading = true, error = "")
            try {
                val bytes = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                }
                if (bytes == null || bytes.isEmpty()) {
                    leaveForm = state.copy(uploading = false, error = "读取图片失败，请重试")
                    return@launch
                }
                val fileName = withContext(Dispatchers.IO) {
                    queryDisplayName(context, uri) ?: "leave-${System.currentTimeMillis()}.jpg"
                }
                val client = XuegongStore.client()
                val url = withContext(Dispatchers.IO) {
                    client.uploadLeaveAttachment(token, state.draft.upFilePath, bytes, fileName)
                }
                leaveForm = leaveForm?.copy(
                    uploading = false,
                    attachments = (leaveForm?.attachments ?: state.attachments) + (url to fileName),
                )
            } catch (e: Exception) {
                val message = XuegongStore.handleFailure(context, accountKey, e)
                leaveForm = leaveForm?.copy(uploading = false, error = message)
            }
        }
    }

    fun removeLeaveAttachment(url: String) {
        val state = leaveForm ?: return
        leaveForm = state.copy(attachments = state.attachments.filterNot { it.first == url })
    }

    /** 提交请假。提交前本地校验，通过后走 SaveForm，成功刷新列表。 */
    fun submitLeave() {
        val state = leaveForm ?: return
        if (state.submitting || state.uploading) return
        val problem = state.draft.validate()
        if (problem != null) {
            leaveForm = state.copy(error = problem)
            return
        }
        scope.launch {
            leaveForm = state.copy(submitting = true, error = "")
            try {
                val client = XuegongStore.client()
                withContext(Dispatchers.IO) {
                    client.saveDailyLeave(
                        token = token,
                        applyInfo = state.draft.toApplyInfo(),
                        fileList = state.fileList,
                        imgs = state.attachments,
                    )
                }
                leaveForm = null
                GlassToaster.show("请假申请已提交")
                load(token)
            } catch (e: Exception) {
                val message = XuegongStore.handleFailure(context, accountKey, e)
                leaveForm = leaveForm?.copy(submitting = false, error = message)
            }
        }
    }

    fun submitWhereabouts() {
        val state = whereaboutsForm ?: return
        if (state.submitting) return
        val problem = state.draft.validate()
        if (problem != null) {
            whereaboutsForm = state.copy(error = problem)
            return
        }
        scope.launch {
            whereaboutsForm = state.copy(submitting = true, error = "")
            try {
                val client = XuegongStore.client()
                withContext(Dispatchers.IO) {
                    client.saveWhereabouts(token, state.draft.toApplyInfo())
                }
                whereaboutsForm = null
                GlassToaster.show("去向登记已提交")
                load(token)
            } catch (e: Exception) {
                val message = XuegongStore.handleFailure(context, accountKey, e)
                whereaboutsForm = whereaboutsForm?.copy(submitting = false, error = message)
            }
        }
    }

    /** 附件选图（相册 / 文件）。选完交给 [uploadLeaveImage]。 */
    val attachmentLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) uploadLeaveImage(uri)
    }

    fun submitLogin(inputPassword: String, silent: Boolean) {        if (studentId.isBlank()) {
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
            leaveForm != null -> "写请假"
            whereaboutsForm != null -> "去向登记"
            else -> "学工系统"
        },
        subtitle = when {
            detailOpen -> null
            token.isBlank() -> null
            else -> "日常请假与节假日去向登记"
        },
        onBack = {
            when {
                leaveDetail != null -> leaveDetail = null
                whereaboutsDetail != null -> whereaboutsDetail = null
                leaveForm != null -> if (!(leaveForm?.submitting ?: false)) leaveForm = null
                whereaboutsForm != null -> if (!(whereaboutsForm?.submitting ?: false)) whereaboutsForm = null
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
            val leaveFormSnapshot = leaveForm
            val whereaboutsFormSnapshot = whereaboutsForm
            when {
                leaveDetailSnapshot != null -> LeaveDetailView(leaveDetailSnapshot)
                whereaboutsDetailSnapshot != null -> WhereaboutsDetailView(whereaboutsDetailSnapshot)
                leaveFormSnapshot != null -> LeaveFormView(
                    state = leaveFormSnapshot,
                    onDraft = { leaveForm = leaveForm?.copy(draft = it) },
                    error = leaveFormSnapshot.error,
                    uploading = leaveFormSnapshot.uploading,
                    submitting = leaveFormSnapshot.submitting,
                    onPickAttachment = { attachmentLauncher.launch("image/*") },
                    onRemoveAttachment = ::removeLeaveAttachment,
                    onSubmit = { submitLeave() },
                )
                whereaboutsFormSnapshot != null -> WhereaboutsFormView(
                    state = whereaboutsFormSnapshot,
                    onDraft = { whereaboutsForm = whereaboutsForm?.copy(draft = it) },
                    error = whereaboutsFormSnapshot.error,
                    submitting = whereaboutsFormSnapshot.submitting,
                    onSubmit = { submitWhereabouts() },
                )

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
                        LeaveTab(
                            page = leavePage,
                            formLoading = formLoading,
                            onOpen = { leaveDetail = it },
                            onWriteLeave = { openLeaveForm() },
                        )
                    } else {
                        WhereaboutsTab(
                            page = whereaboutsPage,
                            formLoading = formLoading,
                            onOpen = { whereaboutsDetail = it },
                            // 批次的「已登记 → 详情 / 开放中 → 填表」分发在 WhereaboutsTab 内部做，
                            // 这里只负责打开表单
                            onOpenBatch = { openWhereaboutsForm(it) },
                        )
                    }
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
private fun LeaveTab(
    page: XuegongLeavePage?,
    formLoading: Boolean,
    onOpen: (XuegongLeaveRecord) -> Unit,
    onWriteLeave: () -> Unit,
) {
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
            rangeDistance = page.locationRule.rangeDistance,
            formLoading = formLoading,
            onWriteLeave = onWriteLeave,
        )
        if (page.page.items.isEmpty()) {
            SystemEmptyState(
                title = "没有请假记录",
                message = "你在学工系统里还没有提交过日常请假。校方入口开放时，可在上方直接填写并提交。"
            )
        } else {
            page.page.items.forEach { record ->
                LeaveRow(record) { onOpen(record) }
            }
            PagingHint(page.page.total, page.page.items.size)
        }
    }
}

/** 校方的"能不能申请"状态卡：开放时带「写请假」入口。 */
@Composable
private fun ApplyStatusCard(
    canApply: Boolean,
    actionText: String,
    blockedReason: String,
    locationRequired: Boolean,
    rangeDistance: String,
    formLoading: Boolean,
    onWriteLeave: () -> Unit,
) {
    SystemCard(Modifier.fillMaxWidth().moduleEntrance(2)) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
                            "提交后由校方审批，结果以学工系统为准"
                        } else {
                            blockedReason.ifBlank { "校方暂时关闭了请假申请" }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 18.sp
                    )
                }
            }
            if (canApply) {
                SystemPrimaryButton(
                    text = when {
                        formLoading -> "正在打开表单…"
                        actionText.isNotBlank() && actionText != "申请" -> actionText
                        else -> "写请假"
                    },
                    onClick = onWriteLeave,
                    enabled = !formLoading,
                    modifier = Modifier.fillMaxWidth()
                )
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
        Spacer(Modifier.height(8.dp))
    }
}

// ── 节假日去向登记 ──────────────────────────────────────────────────────

/**
 * 去向登记 Tab：批次列表只保留**有操作意义**的批次。
 *
 * ・登记中（open）的批次：未登记 → 点开填表提交；已登记 → 点开看详情。
 * ・已结束的批次：**登记过的**保留（点开看详情），没登记过的直接不显示 ——
 *   看不了也报不了名的历史批次堆在界面上只会干扰（用户点名要求删掉）。
 * ・下方不再重复列出已登记记录：登记内容统一从批次行进详情。
 */
@Composable
private fun WhereaboutsTab(
    page: XuegongWhereaboutsPage?,
    formLoading: Boolean,
    onOpen: (XuegongWhereaboutsRecord) -> Unit,
    onOpenBatch: (XuegongHolidayBatch) -> Unit,
) {
    if (page == null) {
        SystemEmptyState(title = "暂无数据", message = "没有读到去向登记信息。")
        return
    }
    fun recordOf(batch: XuegongHolidayBatch): XuegongWhereaboutsRecord? =
        page.page.items.firstOrNull { record ->
            (record.holidayId.isNotBlank() && record.holidayId == batch.id) ||
                record.holidayName == batch.name
        }
    // 已结束且没登记过的批次：不展示（用户点名要求删掉这一段）
    val visibleBatches = page.batches.filter { it.open || recordOf(it) != null }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (visibleBatches.isEmpty()) {
            SystemEmptyState(
                title = "没有登记批次",
                message = "学校还没有发布节假日去向登记批次，开放后这里会出现入口。"
            )
        } else {
            InsetGroupedSection(Modifier.moduleEntrance(2), header = "登记批次") {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    visibleBatches.forEach { batch ->
                        val record = recordOf(batch)
                        HolidayBatchRow(
                            batch = batch,
                            loading = formLoading,
                            // 已登记 → 看详情；开放中未登记 → 填表；其余（已结束没登记）根本不会出现在列表里
                            enabled = record != null || batch.open,
                            registered = record != null,
                            onClick = {
                                if (record != null) onOpen(record) else onOpenBatch(batch)
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * 批次行：显示假期 / 登记时间与状态。
 *
 * [enabled] 为假时不响应点击（纯信息展示）；[registered] 为真时行尾注明
 * 「已登记 · 点按查看详情」，开放中的批次则是「点按填写登记」。
 */
@Composable
private fun HolidayBatchRow(
    batch: XuegongHolidayBatch,
    loading: Boolean,
    enabled: Boolean,
    registered: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
            Text(
                text = when {
                    loading -> "正在打开…"
                    registered -> "已登记 · 点按查看详情"
                    else -> "点按填写登记"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 0.9f else 0.5f)
            )
        }
        if (enabled) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = if (loading) "正在打开" else "打开",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
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

// ── 提交表单（1.2.5）────────────────────────────────────────────────────

/** 「写请假」编辑页的状态：草稿 + 附件 + 瞬时状态。 */
data class XuegongLeaveFormUi(
    val draft: XuegongLeaveDraft,
    /** 表单骨架的 `FileList`（提交时原样回传，附件追加进 Imgs）。 */
    val fileList: org.json.JSONObject?,
    /** 已上传附件：url → 文件名。 */
    val attachments: List<Pair<String, String>> = emptyList(),
    val uploading: Boolean = false,
    val submitting: Boolean = false,
    val error: String = "",
)

/** 「去向登记」编辑页的状态。 */
data class XuegongWhereaboutsFormUi(
    val draft: XuegongWhereaboutsDraft,
    val batch: XuegongHolidayBatch? = null,
    /** true = 已按上一次登记记录自动回填，用户需核对后手动提交。 */
    val prefilled: Boolean = false,
    val submitting: Boolean = false,
    val error: String = "",
)

/** 站点时间格式：`2026-09-25 00`（现行 PC/H5 口径：横杠 + 整点小时，无分钟）。 */
private fun formatSiteTime(millis: Long): String =
    java.text.SimpleDateFormat("yyyy-MM-dd HH", java.util.Locale.CHINA).format(java.util.Date(millis))

private fun queryDisplayName(context: android.content.Context, uri: android.net.Uri): String? =
    runCatching {
        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }.getOrNull()

// ── 写请假 ──────────────────────────────────────────────────────────────

@Composable
private fun LeaveFormView(
    state: XuegongLeaveFormUi,
    onDraft: (XuegongLeaveDraft) -> Unit,
    error: String,
    uploading: Boolean,
    submitting: Boolean,
    onPickAttachment: () -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    val draft = state.draft
    /** 打开中的选择器："begin"/"end"/"outGo"/"outBack"/"reason"/"goVehicle"/"backVehicle"。 */
    var picker by remember { mutableStateOf<String?>(null) }
    var confirmSubmit by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (draft.notice.isNotBlank()) InlineWarning(draft.notice)
        if (error.isNotBlank()) InlineWarning(error)

        InsetGroupedSection(header = "请假信息") {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                FormTimeField("请假开始时间", required = true, value = draft.beginTime) { picker = "begin" }
                FormTimeField("请假结束时间", required = true, value = draft.endTime) { picker = "end" }
                if (draft.durationText.isNotBlank()) {
                    InfoLine("共计", draft.durationText)
                }
                FormPickField(
                    label = "请假原因",
                    required = true,
                    value = draft.reasonText,
                    placeholder = if (draft.reasonOptions.isEmpty()) "站点没有下发选项" else "请选择请假原因",
                    enabled = draft.reasonOptions.isNotEmpty(),
                    onClick = { picker = "reason" },
                )
                FormTextField(
                    label = "详细说明",
                    required = true,
                    value = draft.reasonDetail,
                    placeholder = "请填写详细说明",
                    onValueChange = { draft.reasonDetail = it; onDraft(draft) },
                )
                FormTextField(
                    label = "本人移动电话",
                    required = true,
                    value = draft.stuMoveTel,
                    placeholder = "请填写本人移动电话",
                    keyboardType = KeyboardType.Phone,
                    onValueChange = { draft.stuMoveTel = it; onDraft(draft) },
                )
            }
        }

        InsetGroupedSection(header = "离校信息") {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                FormSegmentField(
                    label = "是否离校",
                    required = true,
                    options = listOf("不离校", "离校"),
                    selectedIndex = if (draft.isOut == "1") 1 else 0,
                ) { index ->
                    draft.isOut = if (index == 1) "1" else "0"
                    onDraft(draft)
                }
                if (draft.isOut == "1") {
                    FormTimeField("外出开始时间", required = true, value = draft.outGoTime) { picker = "outGo" }
                    FormTimeField("外出结束时间", required = true, value = draft.outBackTime) { picker = "outBack" }
                    FormPickField(
                        label = "外出方式",
                        required = true,
                        value = draft.outGoVehicleText,
                        placeholder = "请选择外出方式",
                        enabled = draft.outGoOptions.isNotEmpty(),
                        onClick = { picker = "goVehicle" },
                    )
                    FormPickField(
                        label = "返回方式",
                        required = true,
                        value = draft.outBackVehicleText,
                        placeholder = "请选择返回方式",
                        enabled = draft.outBackOptions.isNotEmpty(),
                        onClick = { picker = "backVehicle" },
                    )
                    FormTextField(
                        label = "外出地点",
                        required = true,
                        value = draft.outAddress,
                        placeholder = "如：安徽省淮南市田家庵区",
                        onValueChange = { draft.outAddress = it; onDraft(draft) },
                    )
                    FormTextField(
                        label = "详细地址",
                        value = draft.outAddressStreet,
                        placeholder = "",
                        onValueChange = { draft.outAddressStreet = it; onDraft(draft) },
                    )
                }
            }
        }

        InsetGroupedSection(header = "监护与同行") {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                FormSegmentField(
                    label = "是否已告知监护人",
                    required = true,
                    options = listOf("未告知", "已告知"),
                    selectedIndex = if (draft.isTellGuarder == "1") 1 else 0,
                ) { index ->
                    draft.isTellGuarder = if (index == 1) "1" else "0"
                    onDraft(draft)
                }
                if (draft.isTellGuarder == "1") {
                    FormTextField(
                        label = "监护人姓名",
                        value = draft.guarderName,
                        placeholder = "请填写监护人姓名",
                        onValueChange = { draft.guarderName = it; onDraft(draft) },
                    )
                    FormTextField(
                        label = "监护人电话",
                        value = draft.guarderTel,
                        placeholder = "例：028-12345678或手机号",
                        keyboardType = KeyboardType.Phone,
                        onValueChange = { draft.guarderTel = it; onDraft(draft) },
                    )
                }
                FormSegmentField(
                    label = "是否结伴同行",
                    required = true,
                    options = listOf("独自", "结伴"),
                    selectedIndex = if (draft.isCompanion == "1") 1 else 0,
                ) { index ->
                    draft.isCompanion = if (index == 1) "1" else "0"
                    onDraft(draft)
                }
                if (draft.isCompanion == "1") {
                    FormTextField(
                        label = "同行人姓名",
                        value = draft.companionName,
                        placeholder = "请填写同行人姓名",
                        onValueChange = { draft.companionName = it; onDraft(draft) },
                    )
                    FormTextField(
                        label = "与本人关系",
                        value = draft.companionRelationship,
                        placeholder = "请填写与本人关系",
                        onValueChange = { draft.companionRelationship = it; onDraft(draft) },
                    )
                    FormTextField(
                        label = "联系电话",
                        value = draft.companionTel,
                        placeholder = "例：028-12345678或手机号",
                        keyboardType = KeyboardType.Phone,
                        onValueChange = { draft.companionTel = it; onDraft(draft) },
                    )
                }
                FormTextField(
                    label = "家长姓名",
                    value = draft.outContacts,
                    placeholder = "请填写家长姓名",
                    onValueChange = { draft.outContacts = it; onDraft(draft) },
                )
                FormTextField(
                    label = "与本人关系",
                    value = draft.outContactsRelationship,
                    placeholder = "例：父子 / 母子 / 父女",
                    onValueChange = { draft.outContactsRelationship = it; onDraft(draft) },
                )
                FormTextField(
                    label = "家长联系电话",
                    value = draft.outContactsTel,
                    placeholder = "例：028-12345678或手机号",
                    keyboardType = KeyboardType.Phone,
                    onValueChange = { draft.outContactsTel = it; onDraft(draft) },
                )
            }
        }

        InsetGroupedSection(
            header = "证明材料",
            footer = "图片单张不超过 8M；材料上传后由校方审核，非必传（以校方要求为准）。"
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                state.attachments.forEach { (url, name) ->
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = name,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "删除",
                            style = MaterialTheme.typography.labelMedium,
                            color = SemanticDanger,
                            modifier = Modifier.clickable { onRemoveAttachment(url) },
                        )
                    }
                }
                SystemSecondaryButton(
                    text = when {
                        uploading -> "正在上传…"
                        else -> "添加图片"
                    },
                    onClick = onPickAttachment,
                    enabled = !uploading && !submitting,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        SystemPrimaryButton(
            text = if (submitting) "正在提交…" else "提交请假申请",
            onClick = { confirmSubmit = true },
            enabled = !submitting && !uploading,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = "提交后请耐心等待校方审批，结果以学工系统为准。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            lineHeight = 18.sp,
        )
        Spacer(Modifier.height(8.dp))
    }

    when (picker) {
        "begin" -> FormTimeDialog(
            title = "请假开始时间",
            initial = draft.beginTime,
            onConfirm = { draft.beginTime = formatSiteTime(it); onDraft(draft); picker = null },
            onDismiss = { picker = null },
        )
        "end" -> FormTimeDialog(
            title = "请假结束时间",
            initial = draft.endTime,
            onConfirm = { draft.endTime = formatSiteTime(it); onDraft(draft); picker = null },
            onDismiss = { picker = null },
        )
        "outGo" -> FormTimeDialog(
            title = "外出开始时间",
            initial = draft.outGoTime,
            onConfirm = { draft.outGoTime = formatSiteTime(it); onDraft(draft); picker = null },
            onDismiss = { picker = null },
        )
        "outBack" -> FormTimeDialog(
            title = "外出结束时间",
            initial = draft.outBackTime,
            onConfirm = { draft.outBackTime = formatSiteTime(it); onDraft(draft); picker = null },
            onDismiss = { picker = null },
        )
        "reason" -> FormOptionDialog(
            title = "请假原因",
            options = draft.reasonOptions,
            selectedValue = draft.reason,
            onConfirm = { option ->
                draft.reason = option.value
                draft.reasonText = option.label
                onDraft(draft)
                picker = null
            },
            onDismiss = { picker = null },
        )
        "goVehicle" -> FormOptionDialog(
            title = "外出方式",
            options = draft.outGoOptions,
            selectedValue = draft.outGoVehicle,
            onConfirm = { option ->
                draft.outGoVehicle = option.value
                draft.outGoVehicleText = option.label
                onDraft(draft)
                picker = null
            },
            onDismiss = { picker = null },
        )
        "backVehicle" -> FormOptionDialog(
            title = "返回方式",
            options = draft.outBackOptions,
            selectedValue = draft.outBackVehicle,
            onConfirm = { option ->
                draft.outBackVehicle = option.value
                draft.outBackVehicleText = option.label
                onDraft(draft)
                picker = null
            },
            onDismiss = { picker = null },
        )
    }

    if (confirmSubmit) {
        SystemConfirmDialog(
            title = "提交请假申请",
            text = "开始 ${draft.beginTime}\n结束 ${draft.endTime}\n\n提交后由校方审批，确认提交？",
            confirmText = "确认提交",
            onConfirm = {
                confirmSubmit = false
                onSubmit()
            },
            onDismiss = { confirmSubmit = false },
        )
    }
}

// ── 去向登记 ────────────────────────────────────────────────────────────

@Composable
private fun WhereaboutsFormView(
    state: XuegongWhereaboutsFormUi,
    onDraft: (XuegongWhereaboutsDraft) -> Unit,
    error: String,
    submitting: Boolean,
    onSubmit: () -> Unit,
) {
    val draft = state.draft
    var picker by remember { mutableStateOf<String?>(null) }
    var confirmSubmit by remember { mutableStateOf(false) }
    val leaving = draft.leaveType != "2"

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        state.batch?.let { batch ->
            if (batch.holidayBegin.isNotBlank() || batch.holidayEnd.isNotBlank()) {
                InlineWarning(
                    "假期 ${batch.holidayBegin} 至 ${batch.holidayEnd}" +
                        if (batch.memo.isNotBlank()) " · ${batch.memo}" else ""
                )
            }
        }
        if (state.prefilled) {
            InlineWarning("已按你上一次的登记自动填写，请逐项核对后再提交。")
        }
        if (error.isNotBlank()) InlineWarning(error)

        InsetGroupedSection(header = "登记信息") {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                FormSegmentField(
                    label = "去向类型",
                    required = true,
                    options = listOf("离校", "留校"),
                    selectedIndex = if (leaving) 0 else 1,
                ) { index ->
                    draft.leaveType = if (index == 0) "1" else "2"
                    onDraft(draft)
                }
                if (leaving) {
                    FormTimeField("离校开始时间", required = true, value = draft.beginTime) { picker = "begin" }
                    FormTimeField("离校结束时间", required = true, value = draft.endTime) { picker = "end" }
                } else {
                    FormTimeField("留校开始时间", required = true, value = draft.stayBeginTime) { picker = "stayBegin" }
                    FormTimeField("留校结束时间", required = true, value = draft.stayEndTime) { picker = "stayEnd" }
                }
                if (draft.durationText.isNotBlank()) {
                    InfoLine("共计", draft.durationText)
                }
            }
        }

        // ── 离校才填的部分（站点：去向事由 / 交通方式 / 外出地点 / 同行人数）──
        if (leaving) {
            InsetGroupedSection(
                header = "去向信息",
                footer = "去向事由要求 1 ~ 400 字，请详细注明离校时间、去向与随行人员等信息。",
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    FormTextField(
                        label = "去向事由",
                        required = true,
                        value = draft.reason,
                        placeholder = "请填写去向事由",
                        singleLine = false,
                        minHeight = 96.dp,
                        onValueChange = { draft.reason = it; onDraft(draft) },
                    )
                    FormPickField(
                        label = "交通方式",
                        required = true,
                        value = draft.outGoVehicleText,
                        placeholder = "请选择交通方式",
                        enabled = draft.outGoOptions.isNotEmpty(),
                        onClick = { picker = "goVehicle" },
                    )
                    FormTextField(
                        label = "外出地点",
                        required = true,
                        value = draft.comeWhere,
                        placeholder = "如：安徽省合肥市蜀山区",
                        onValueChange = { draft.comeWhere = it; onDraft(draft) },
                    )
                    FormTextField(
                        label = "详细地址",
                        value = draft.outAddressStreet,
                        placeholder = "请填写详细地址",
                        onValueChange = { draft.outAddressStreet = it; onDraft(draft) },
                    )
                    FormTextField(
                        label = "同行人数",
                        value = draft.outNumber,
                        placeholder = "例：1",
                        keyboardType = KeyboardType.Number,
                        onValueChange = { draft.outNumber = it; onDraft(draft) },
                    )
                }
            }

            InsetGroupedSection(header = "外出联系信息") {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    FormTextField(
                        label = "联系人姓名",
                        required = true,
                        value = draft.outContacts,
                        placeholder = "请填写联系人姓名",
                        onValueChange = { draft.outContacts = it; onDraft(draft) },
                    )
                    FormTextField(
                        label = "与本人关系",
                        required = true,
                        value = draft.outContactsRelationship,
                        placeholder = "请填写与本人关系",
                        onValueChange = { draft.outContactsRelationship = it; onDraft(draft) },
                    )
                    FormTextField(
                        label = "移动电话",
                        required = true,
                        value = draft.outContactsMoveTel,
                        placeholder = "请输入手机号码",
                        keyboardType = KeyboardType.Phone,
                        onValueChange = { draft.outContactsMoveTel = it; onDraft(draft) },
                    )
                    FormTextField(
                        label = "固定电话",
                        value = draft.outContactsTel,
                        placeholder = "例：028-12345678",
                        keyboardType = KeyboardType.Phone,
                        onValueChange = { draft.outContactsTel = it; onDraft(draft) },
                    )
                }
            }
        }

        InsetGroupedSection(header = "本人联系方式") {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                FormTextField(
                    label = "本人移动电话",
                    required = true,
                    value = draft.stuMoveTel,
                    placeholder = "请填写本人移动电话",
                    keyboardType = KeyboardType.Phone,
                    onValueChange = { draft.stuMoveTel = it; onDraft(draft) },
                )
                FormTextField(
                    label = "其他联系方式",
                    value = draft.stuTel,
                    placeholder = "请填写其他联系方式",
                    onValueChange = { draft.stuTel = it; onDraft(draft) },
                )
            }
        }

        SystemPrimaryButton(
            text = if (submitting) "正在提交…" else "提交去向登记",
            onClick = { confirmSubmit = true },
            enabled = !submitting,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = "提交后如需修改，请在登记开放期内重新进入本页编辑；结果以学工系统为准。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            lineHeight = 18.sp,
        )
        Spacer(Modifier.height(8.dp))
    }

    when (picker) {
        "begin" -> FormTimeDialog(
            title = "离校开始时间",
            initial = draft.beginTime,
            onConfirm = { draft.beginTime = formatSiteTime(it); onDraft(draft); picker = null },
            onDismiss = { picker = null },
        )
        "end" -> FormTimeDialog(
            title = "离校结束时间",
            initial = draft.endTime,
            onConfirm = { draft.endTime = formatSiteTime(it); onDraft(draft); picker = null },
            onDismiss = { picker = null },
        )
        "stayBegin" -> FormTimeDialog(
            title = "留校开始时间",
            initial = draft.stayBeginTime,
            onConfirm = { draft.stayBeginTime = formatSiteTime(it); onDraft(draft); picker = null },
            onDismiss = { picker = null },
        )
        "stayEnd" -> FormTimeDialog(
            title = "留校结束时间",
            initial = draft.stayEndTime,
            onConfirm = { draft.stayEndTime = formatSiteTime(it); onDraft(draft); picker = null },
            onDismiss = { picker = null },
        )
        "goVehicle" -> FormOptionDialog(
            title = "交通方式",
            options = draft.outGoOptions,
            selectedValue = draft.outGoVehicle,
            onConfirm = { option ->
                draft.outGoVehicle = option.value
                draft.outGoVehicleText = option.label
                onDraft(draft)
                picker = null
            },
            onDismiss = { picker = null },
        )
    }

    if (confirmSubmit) {
        SystemConfirmDialog(
            title = "提交去向登记",
            text = "去向类型：${if (leaving) "离校" else "留校"}\n" +
                if (leaving) "离校 ${draft.beginTime} 至 ${draft.endTime}" else "留校 ${draft.stayBeginTime} 至 ${draft.stayEndTime}",
            confirmText = "确认提交",
            onConfirm = {
                confirmSubmit = false
                onSubmit()
            },
            onDismiss = { confirmSubmit = false },
        )
    }
}

// ── 表单零件 ────────────────────────────────────────────────────────────

/** 表单里的时间选择行：显示当前值，点击弹玻璃滚轮时间选择。 */
@Composable
private fun FormTimeField(
    label: String,
    value: String,
    required: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = if (required) "$label *" else label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.width(112.dp),
        )
        Text(
            text = value.ifBlank { "请选择" },
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (value.isBlank()) FontWeight.Normal else FontWeight.Medium,
            color = if (value.isBlank()) {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            modifier = Modifier.weight(1f),
        )
    }
}

/** 表单里的下拉选择行。 */
@Composable
private fun FormPickField(
    label: String,
    value: String,
    placeholder: String,
    required: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = if (required) "$label *" else label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.width(112.dp),
        )
        Text(
            text = value.ifBlank { placeholder },
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (value.isBlank()) FontWeight.Normal else FontWeight.Medium,
            color = if (value.isBlank()) {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            modifier = Modifier.weight(1f),
        )
    }
}

/** 表单里的两态分段（是否离校 / 是否告知监护人…）。 */
@Composable
private fun FormSegmentField(
    label: String,
    options: List<String>,
    selectedIndex: Int,
    required: Boolean = false,
    onSelect: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = if (required) "$label *" else label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        SystemSegmentedControl(
            options = options,
            selectedIndex = selectedIndex,
            onSelect = onSelect,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** 表单里的文本输入行。 */
@Composable
private fun FormTextField(
    label: String,
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit,
    required: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    /** false = 多行（去向事由这类长文本）。 */
    singleLine: Boolean = true,
    minHeight: Dp = 48.dp,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = if (required) "$label *" else label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        GlassTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = placeholder,
            singleLine = singleLine,
            minHeight = minHeight,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        )
    }
}

/** 时间选择弹窗：初始值取自站点时间格式。 */
@Composable
private fun FormTimeDialog(
    title: String,
    initial: String,
    onConfirm: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    GlassDateTimePickerDialog(
        title = title,
        initialMillis = parseSiteTime(initial) ?: System.currentTimeMillis(),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}

/** 字典单选弹窗：把站点的 `{value,text}` 字典喂给玻璃滚轮。 */
@Composable
private fun FormOptionDialog(
    title: String,
    options: List<XuegongDictOption>,
    selectedValue: String,
    onConfirm: (XuegongDictOption) -> Unit,
    onDismiss: () -> Unit,
) {
    val labels = options.map { it.label }
    val selectedIndex = options.indexOfFirst { it.value == selectedValue }.takeIf { it >= 0 } ?: 0
    GlassOptionWheelDialog(
        title = title,
        options = labels,
        selectedIndex = selectedIndex,
        onConfirm = { index -> onConfirm(options[index]) },
        onDismiss = onDismiss,
    )
}
