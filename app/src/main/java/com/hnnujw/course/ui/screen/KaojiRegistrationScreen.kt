package com.hnnujw.course.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.HowToReg
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hnnujw.course.academic.AcademicGatewayFactory
import com.hnnujw.course.examreg.KaojiCategory
import com.hnnujw.course.examreg.KaojiClient
import com.hnnujw.course.examreg.KaojiExpiredProject
import com.hnnujw.course.examreg.KaojiParser
import com.hnnujw.course.examreg.KaojiPage
import com.hnnujw.course.examreg.KaojiProject
import com.hnnujw.course.examreg.KaojiRegistered
import com.hnnujw.course.examreg.KaojiWithdrawPolicy
import com.hnnujw.course.manager.UserManager
import com.hnnujw.course.model.SchoolConfig
import com.hnnujw.course.ui.system.GlassPageScaffold
import com.hnnujw.course.ui.system.GlassTextField
import com.hnnujw.course.ui.system.InsetGroupedSection
import com.hnnujw.course.ui.system.PagePadding
import com.hnnujw.course.ui.system.SystemCard
import com.hnnujw.course.ui.system.SystemConfirmDialog
import com.hnnujw.course.ui.system.SystemEmptyState
import com.hnnujw.course.ui.system.SystemIconButton
import com.hnnujw.course.ui.system.SystemLoadingState
import com.hnnujw.course.ui.system.SystemPrimaryButton
import com.hnnujw.course.ui.system.SystemSecondaryButton
import com.hnnujw.course.ui.system.SystemDialog
import com.hnnujw.course.ui.system.GlassToaster
import com.hnnujw.course.ui.theme.NeuPrimary
import com.hnnujw.course.ui.theme.SemanticDanger
import com.hnnujw.course.ui.theme.SemanticSuccess
import com.hnnujw.course.ui.theme.SemanticWarning
import kotlinx.coroutines.launch

/**
 * 教务系统「考级项目报名」页（kjgl/kjbm_*，gnmkdm=N2510）。
 *
 * 与 [XuegongScreen] 同构的独立全屏页，但数据源是**教务**：
 * 复用已登录的教务会话（[AcademicGatewayFactory.transportFor]），无需额外登录。
 *
 * 报名/退报都是对教务系统的真实写请求，与网页端操作等价。
 */
@Composable
fun KaojiRegistrationScreen(
    school: SchoolConfig?,
    accountKey: String,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()

    // ⚠️ 全部按 accountKey 重置：切换账号后这些状态必须回到初始值，
    // 否则会把上一个账号的报名数据展示给新账号（与 XuegongScreen 同一约定）。
    var page by remember(accountKey) { mutableStateOf<KaojiPage?>(null) }
    var loading by remember(accountKey) { mutableStateOf(false) }
    var error by remember(accountKey) { mutableStateOf("") }
    var needLogin by remember(accountKey) { mutableStateOf(false) }

    /** 正在提交的项目 id（报名）/ 记录 id（退报），用于按钮防抖。 */
    var submittingId by remember(accountKey) { mutableStateOf<String?>(null) }
    var confirmProject by remember(accountKey) { mutableStateOf<KaojiProject?>(null) }
    var confirmPhone by remember(accountKey) { mutableStateOf("") }
    var withdrawTarget by remember(accountKey) { mutableStateOf<KaojiRegistered?>(null) }

    /**
     * 本账号可用的考级类别，来自教务功能菜单（见 [KaojiClient.loadCategories]）。
     * 初始值是兜底的单类别，所以**第一次进入页面就能正常读**，
     * 菜单返回后若发现有多类别（如「大类分流报名」「推免报名」）才多出切换条。
     */
    var categories by remember(accountKey) { mutableStateOf(listOf(KaojiClient.DEFAULT_CATEGORY)) }
    var categoriesLoaded by remember(accountKey) { mutableStateOf(false) }
    var selectedXmlbfl by remember(accountKey) {
        mutableStateOf(KaojiClient.DEFAULT_CATEGORY.xmlbfl)
    }

    /**
     * 「本学期过期项目报名信息」。null = 还没点过 —— 与网页端一致，**按需**加载，
     * 不因为进页面就多打一个请求。
     */
    var expired by remember(accountKey) { mutableStateOf<List<KaojiExpiredProject>?>(null) }
    var expiredTotal by remember(accountKey) { mutableStateOf(0) }
    var expiredLoading by remember(accountKey) { mutableStateOf(false) }
    var expiredError by remember(accountKey) { mutableStateOf("") }

    /**
     * 该记录不能退报的原因；null = 可以退报。
     *
     * 口径在 [KaojiWithdrawPolicy]（纯函数，有单测）。这里只负责把
     * "当前类别的开放批次"和"现在几点"喂进去。
     */
    fun withdrawBlockReason(record: KaojiRegistered): String? =
        KaojiWithdrawPolicy.blockReason(
            record = record,
            currentProjects = page?.projects.orEmpty(),
            nowMillis = System.currentTimeMillis(),
        )

    /**
     * 读取指定类别。[refreshCategories] 为 true 时连教务菜单一起重取
     * （菜单不是常量：学校开了新类别、或换了账号权限都可能变）。
     */
    fun load(xmlbfl: String, refreshCategories: Boolean) {
        if (school == null || accountKey.isBlank()) {
            needLogin = true
            error = "尚未登录教务账号，无法读取考级报名信息"
            return
        }
        loading = true
        error = ""
        scope.launch {
            val transport = AcademicGatewayFactory.transportFor(school, accountKey)
            if (!categoriesLoaded || refreshCategories) {
                val discovered = KaojiClient.loadCategories(transport)
                categories = discovered
                categoriesLoaded = true
                // 菜单里没有当前类别（如权限收回）时退回第一条，别对着空气发请求。
                if (discovered.none { it.xmlbfl == xmlbfl }) {
                    selectedXmlbfl = discovered.first().xmlbfl
                }
            }
            when (val result = KaojiClient.loadPage(transport, school, selectedXmlbfl)) {
                is KaojiClient.LoadResult.Success -> {
                    page = result.page
                    needLogin = false
                }
                is KaojiClient.LoadResult.NeedLogin -> {
                    needLogin = true
                    error = result.message
                }
                is KaojiClient.LoadResult.Failure -> {
                    error = result.message
                }
            }
            loading = false
        }
    }

    // Kotlin 局部函数不能前向引用：submit / withdraw 声明在 load 之后、使用之前。
    fun submit(project: KaojiProject) {
        val current = page ?: return
        if (submittingId != null) return
        submittingId = project.id
        scope.launch {
            val transport = AcademicGatewayFactory.transportFor(school ?: return@launch, accountKey)
            when (val result = KaojiClient.submitRegistration(
                transport = transport,
                school = school ?: return@launch,
                project = project,
                xnm = current.xnm,
                xqm = current.xqm,
                phone = confirmPhone,
                xmlbfl = selectedXmlbfl,
            )) {
                is KaojiClient.SubmitResult.Success -> GlassToaster.show(result.message)
                is KaojiClient.SubmitResult.Failure -> GlassToaster.show(result.message)
                is KaojiClient.SubmitResult.NeedLogin -> {
                    needLogin = true
                    error = result.message
                }
            }
            submittingId = null
            confirmProject = null
            if (needLogin.not()) load(selectedXmlbfl, false)
        }
    }

    fun withdraw(record: KaojiRegistered) {
        if (submittingId != null) return
        submittingId = record.id
        scope.launch {
            val transport = AcademicGatewayFactory.transportFor(school ?: return@launch, accountKey)
            when (val result = KaojiClient.withdrawRegistration(transport, school ?: return@launch, record)) {
                is KaojiClient.SubmitResult.Success -> GlassToaster.show(result.message)
                is KaojiClient.SubmitResult.Failure -> GlassToaster.show(result.message)
                is KaojiClient.SubmitResult.NeedLogin -> {
                    needLogin = true
                    error = result.message
                }
            }
            submittingId = null
            withdrawTarget = null
            if (needLogin.not()) load(selectedXmlbfl, false)
        }
    }

    /** 按需拉「本学期过期项目」。 */
    fun loadExpired() {
        if (expiredLoading) return
        expiredLoading = true
        expiredError = ""
        scope.launch {
            val transport = AcademicGatewayFactory.transportFor(school ?: return@launch, accountKey)
            when (val result = KaojiClient.loadExpired(transport, selectedXmlbfl)) {
                is KaojiClient.ExpiredResult.Success -> {
                    expired = result.projects
                    expiredTotal = result.totalCount
                }
                is KaojiClient.ExpiredResult.Failure -> expiredError = result.message
                is KaojiClient.ExpiredResult.NeedLogin -> {
                    needLogin = true
                    error = result.message
                }
            }
            expiredLoading = false
        }
    }

    LaunchedEffect(school?.id, accountKey) {
        if (page == null && !loading) load(selectedXmlbfl, true)
    }

    GlassPageScaffold(
        title = "考级项目报名",
        onBack = {
            if (confirmProject != null || withdrawTarget != null) {
                confirmProject = null
                withdrawTarget = null
            } else {
                onBack()
            }
        },
        actions = {
            SystemIconButton(Icons.Outlined.Refresh, "刷新", {
                if (!loading) load(selectedXmlbfl, true)
            })
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = PagePadding, vertical = 12.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            KaojiHeaderCard(
                studentId = UserManager.getInstance().studentId,
                registeredCount = page?.registered?.size ?: 0,
                pageTitle = page?.title.orEmpty(),
            )
            when {
                needLogin -> Box(Modifier.fillMaxWidth().padding(top = 32.dp), contentAlignment = Alignment.Center) {
                    SystemEmptyState(
                        title = "需要登录教务",
                        message = error.ifBlank { "请先在首页登录教务账号，再回来查看考级报名" },
                        action = {
                            SystemPrimaryButton(
                                text = "重新加载",
                                onClick = { load(selectedXmlbfl, true) },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    )
                }
                loading && page == null -> Box(Modifier.fillMaxWidth().padding(top = 32.dp), contentAlignment = Alignment.Center) {
                    SystemLoadingState("正在读取考级报名信息…")
                }
                error.isNotBlank() && page == null -> Box(Modifier.fillMaxWidth().padding(top = 32.dp), contentAlignment = Alignment.Center) {
                    SystemEmptyState(
                        title = "读取失败",
                        message = error,
                        action = {
                            SystemPrimaryButton(
                                text = "重试",
                                onClick = { load(selectedXmlbfl, true) },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    )
                }
                page != null -> {
                    if (error.isNotBlank()) InlineWarning(error)
                    // 多类别才给切换条：绝大多数账号只有「考级项目报名」一条，
                    // 单条时多一排只有一个选项的芯片纯属噪音。
                    if (categories.size > 1) {
                        KaojiCategoryChips(
                            categories = categories,
                            selected = selectedXmlbfl,
                            onSelect = { category ->
                                // 已选中的芯片再点一次不做任何事（省一次请求）。
                                if (category.xmlbfl != selectedXmlbfl) {
                                    selectedXmlbfl = category.xmlbfl
                                    // 过期项目是「按类别 + 按需」查的，换类别必须丢弃旧结果，
                                    // 否则会把上一个类别的项目挂在新类别下面。
                                    expired = null
                                    expiredTotal = 0
                                    expiredError = ""
                                    load(category.xmlbfl, false)
                                }
                            },
                        )
                    }
                    KaojiProjectSection(
                        projects = page?.projects.orEmpty(),
                        notice = page?.pageNotice.orEmpty(),
                        submittingId = submittingId,
                        onRegister = {
                            confirmPhone = page?.registered?.firstOrNull()?.phone.orEmpty()
                            confirmProject = it
                        },
                    )
                    KaojiRegisteredSection(
                        records = page?.registered.orEmpty(),
                        totalCount = page?.registeredTotal ?: 0,
                        submittingId = submittingId,
                        blockReason = ::withdrawBlockReason,
                        onWithdraw = { withdrawTarget = it },
                    )
                    KaojiExpiredSection(
                        projects = expired,
                        totalCount = expiredTotal,
                        loading = expiredLoading,
                        error = expiredError,
                        onLoad = { loadExpired() },
                    )
                    Text(
                        text = "报名结果以教务系统为准；缴费、打印准考证等后续环节请留意教务通知。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 18.sp,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    // ── 报名确认弹窗（含报名说明与联系电话）───────────────────────────────
    val pendingProject = confirmProject
    if (pendingProject != null) {
        SystemDialog(
            onDismissRequest = { confirmProject = null },
            title = {
                Text(
                    text = "确认报名",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            },
            dismissButton = {
                SystemSecondaryButton(
                    text = "取消",
                    onClick = { confirmProject = null },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                SystemPrimaryButton(
                    text = "提交报名",
                    onClick = { submit(pendingProject) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (pendingProject.notice.isNotBlank()) {
                    Text(
                        text = pendingProject.notice,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 18.sp,
                    )
                }
                if (pendingProject.feeText.isNotBlank() || pendingProject.beginTime.isNotBlank()) {
                    Text(
                        text = listOf(
                            if (pendingProject.beginTime.isNotBlank()) {
                                "报名时间 ${pendingProject.beginTime} 至 ${pendingProject.endTime}"
                            } else "",
                            pendingProject.feeText,
                        ).filter { it.isNotBlank() }.joinToString("\n"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 18.sp,
                    )
                }
                GlassTextField(
                    value = confirmPhone,
                    onValueChange = { confirmPhone = it },
                    placeholder = "联系电话（用于考务联系）",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                )
            }
        }
    }
    val pendingWithdraw = withdrawTarget
    if (pendingWithdraw != null) {
        SystemConfirmDialog(
            title = "确认退报",
            text = "项目：${pendingWithdraw.name}\n已缴费用是否退还以学校规定为准。确定要退报吗？",
            confirmText = "确认退报",
            onConfirm = { withdraw(pendingWithdraw) },
            onDismiss = { withdrawTarget = null },
        )
    }
}

// ── 页面零件 ────────────────────────────────────────────────────────────

/**
 * 页头信息卡：图标 + 模块名 + 站点页头标题 + 已报数量。
 *
 * [pageTitle] 是教务自己在 `sl_tit_kbmxm` 里写的标题（如
 * 「2026-2027学年1学期等级考试报名」），带学年学期与类别名 —— 多类别时它是
 * 唯一能确认"我现在看的是哪个类别、哪个学期"的依据，所以照原样显示。
 */
@Composable
private fun KaojiHeaderCard(studentId: String, registeredCount: Int, pageTitle: String) {
    SystemCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.72f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Outlined.HowToReg,
                    contentDescription = null,
                    tint = NeuPrimary,
                    modifier = Modifier.size(24.dp)
                )
            }
            Column(Modifier.padding(start = 14.dp)) {
                Text(
                    text = "考级项目报名",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (pageTitle.isNotBlank()) {
                    Text(
                        text = pageTitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
        }
        if (studentId.isNotBlank()) {
            Text(
                text = "学号 $studentId · 已报名 $registeredCount 项",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 10.dp)
            )
        }
    }
}

/**
 * 考级类别切换条。
 *
 * 只在菜单里发现**多于一个**类别时才出现。类别名用教务菜单里的原文
 * （如「大类分流报名」「推免报名」），不是我们编的 —— 学生对这些名字本来就有认知。
 */
@Composable
private fun KaojiCategoryChips(
    categories: List<KaojiCategory>,
    selected: String,
    onSelect: (KaojiCategory) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        categories.forEach { category ->
            GlassFilterChip(
                label = category.title,
                selected = category.xmlbfl == selected,
                onClick = { onSelect(category) },
            )
        }
    }
}

/**
 * 可报名项目列表。
 *
 * [notice] 是**站点自己**写的提示语（如「对不起，当前不属于考级报名阶段」）。列表为空时
 * 优先显示它 —— 教务侧的真实原因比我们统一的「当前没有开放报名的考级项目」有用。
 */
@Composable
private fun KaojiProjectSection(
    projects: List<KaojiProject>,
    notice: String,
    submittingId: String?,
    onRegister: (KaojiProject) -> Unit,
) {
    InsetGroupedSection(
        header = "可报名项目",
        footer = "报名开放时间、名额与费用以教务系统为准。"
    ) {
        if (projects.isEmpty()) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    text = notice.ifBlank { "当前没有开放报名的考级项目" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                projects.forEach { project ->
                    KaojiProjectCard(
                        project = project,
                        submitting = submittingId == project.id,
                        onRegister = { onRegister(project) },
                    )
                }
            }
        }
    }
}

@Composable
private fun KaojiProjectCard(project: KaojiProject, submitting: Boolean, onRegister: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = project.title,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        val facts = listOf(project.remainDaysText, project.remainSeatsText, project.feeText)
            .filter { it.isNotBlank() }
        if (facts.isNotEmpty()) {
            Text(
                text = facts.joinToString("　"),
                style = MaterialTheme.typography.bodySmall,
                color = SemanticWarning,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        if (project.beginTime.isNotBlank()) {
            Text(
                text = "报名 ${project.beginTime} 至 ${project.endTime}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        if (project.notice.isNotBlank()) {
            Text(
                text = "报名说明：${project.notice.take(120)}${if (project.notice.length > 120) "…" else ""}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 18.sp,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
        if (project.canRegister) {
            SystemPrimaryButton(
                text = if (submitting) "正在提交…" else "报名",
                onClick = onRegister,
                enabled = !submitting,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
            )
        }
    }
}

/**
 * 已报名记录列表。
 *
 * [totalCount] 是教务自报的总条数。🔴 实测该接口**忽略一切分页参数**、
 * 一次固定只回 [KaojiParser.REGISTERED_PAGE_LIMIT] 条，所以总条数大于实际返回条数时
 * 必须明说「只显示了最近 N 条」，否则用户会以为记录丢了。
 */
@Composable
private fun KaojiRegisteredSection(
    records: List<KaojiRegistered>,
    totalCount: Int,
    submittingId: String?,
    blockReason: (KaojiRegistered) -> String?,
    onWithdraw: (KaojiRegistered) -> Unit,
) {
    InsetGroupedSection(header = "已报名记录", footer = "报名截止后不支持退报；已缴费项目以教务系统规定为准。") {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (records.isEmpty()) {
                Text(
                    text = "暂无报名记录",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                records.forEach { record ->
                    KaojiRegisteredCard(
                        record = record,
                        submitting = submittingId == record.id,
                        blockReason = blockReason(record),
                        onWithdraw = { onWithdraw(record) },
                    )
                }
            }
            if (totalCount > records.size) {
                Text(
                    text = "教务系统该接口一次最多返回 ${KaojiParser.REGISTERED_PAGE_LIMIT} 条，这里显示的是最近 " +
                        "${records.size} 条（共 $totalCount 条）。更早的记录请到教务系统网页端查看。",
                    style = MaterialTheme.typography.bodySmall,
                    color = SemanticWarning,
                    lineHeight = 18.sp,
                )
            }
        }
    }
}

/**
 * 「本学期过期项目」：与网页端页头那个信封按钮同源
 * （`kjgl/kjbm_cxGqxm.html?doType=query`）。
 *
 * [projects] 为 null 表示**还没查过** —— 这里刻意不自动请求，点一下才发，
 * 与网页端 `cxGqxm()` 的行为一致。
 */
@Composable
private fun KaojiExpiredSection(
    projects: List<KaojiExpiredProject>?,
    totalCount: Int,
    loading: Boolean,
    error: String,
    onLoad: () -> Unit,
) {
    InsetGroupedSection(
        header = "本学期过期项目",
        footer = "已过报名时间的项目，仅供参考；能否补报以教务系统网页端为准。"
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when {
                loading -> Text(
                    text = "正在读取本学期过期项目…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                error.isNotBlank() -> {
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 18.sp,
                    )
                    SystemSecondaryButton(
                        text = "重试",
                        onClick = onLoad,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                projects == null -> {
                    Text(
                        text = "这里列出本学期已经截止报名的项目。需要时再查，不占用进入页面的时间。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 18.sp,
                    )
                    SystemSecondaryButton(
                        text = "查看本学期过期项目",
                        onClick = onLoad,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                projects.isEmpty() -> Text(
                    text = "本学期没有过期项目",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                else -> {
                    projects.forEach { KaojiExpiredCard(it) }
                    if (totalCount > projects.size) {
                        Text(
                            text = "共 $totalCount 个项目，这里显示 ${projects.size} 个。",
                            style = MaterialTheme.typography.bodySmall,
                            color = SemanticWarning,
                            lineHeight = 18.sp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun KaojiExpiredCard(project: KaojiExpiredProject) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = project.name.ifBlank { "未命名项目" },
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f, fill = false)
            )
            if (project.category.isNotBlank() && project.category != project.name) {
                Text(
                    text = project.category,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
        }
        val lines = buildList {
            if (project.term.isNotBlank()) add("学期：${project.term}")
            if (project.beginTime.isNotBlank()) add("报名时间：${project.beginTime} 至 ${project.endTime}")
            if (project.fee.isNotBlank()) add("费用：${project.fee} 元")
        }
        lines.forEach { line ->
            Text(
                text = line,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 18.sp,
                modifier = Modifier.padding(top = 3.dp)
            )
        }
    }
}

/**
 * 一条已报名记录。
 *
 * [blockReason] 是「为什么不能退报」，null 表示可以退报 —— 用原因而不是布尔，
 * 是为了不再把「已缴费」也说成「已过报名截止时间」。
 */
@Composable
private fun KaojiRegisteredCard(
    record: KaojiRegistered,
    submitting: Boolean,
    blockReason: String?,
    onWithdraw: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = record.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f, fill = false)
            )
            if (record.category.isNotBlank()) {
                Text(
                    text = record.category,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
        }
        // 每行 = 文案 + 可选强调色。只有**需要看见**的状态才上色：
        // 「未缴」是要去办的事，「已缴」是办完的事；两者都比默认灰字更该被注意到。
        val facts = buildList<Pair<String, Color?>> {
            if (record.term.isNotBlank()) add("学期：${record.term}" to null)
            if (record.registeredAt.isNotBlank()) add("报名时间：${record.registeredAt}" to null)
            // 审核异常才显示（「已通过」是常态，逐条印出来是噪音）
            if (record.auditText.isNotBlank()) add("审核状态：${record.auditText}" to SemanticWarning)
            if (record.fee.isNotBlank()) add("费用：${record.fee} 元" to null)
            // 缴费状态来自列表信封的 sfqr（站点自己的口径：0 未缴 / 1 已缴）。
            // 空值 = 该条不涉及缴费，整行不出现。
            if (record.paymentText.isNotBlank()) {
                add("缴费状态：${record.paymentText}" to if (record.paid) SemanticSuccess else SemanticWarning)
            }
            // 注：接口的 kssj/jssj 实测是报名起止窗口而不是考试时间，不在这里展示；
            // 考试时间以准考证/教务通知为准。
            if (record.ticketNo.isNotBlank()) add("准考证号：${record.ticketNo}" to null)
            if (record.score.isNotBlank()) add("成绩：${record.score}" to null)
            if (record.certNo.isNotBlank()) add("证书编号：${record.certNo}" to null)
        }
        facts.forEach { (line, accent) ->
            Text(
                text = line,
                style = MaterialTheme.typography.bodySmall,
                color = accent ?: MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 18.sp,
                modifier = Modifier.padding(top = 3.dp)
            )
        }
        if (blockReason == null) {
            SystemSecondaryButton(
                text = if (submitting) "正在提交…" else "退报",
                onClick = onWithdraw,
                enabled = !submitting,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
            )
        } else {
            Text(
                text = blockReason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 10.dp)
            )
        }
    }
}

/** 考级页的内联警示条（XuegongScreen 的同名组件是 private，这里单独一份）。 */
@Composable
private fun InlineWarning(text: String) {
    SystemCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Outlined.Info,
                contentDescription = null,
                tint = SemanticDanger,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                lineHeight = 18.sp,
                modifier = Modifier.padding(start = 8.dp)
            )
        }
    }
}
