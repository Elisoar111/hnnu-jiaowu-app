package com.hnnujw.course.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import com.hnnujw.course.examreg.KaojiClient
import com.hnnujw.course.examreg.KaojiPage
import com.hnnujw.course.examreg.KaojiProject
import com.hnnujw.course.examreg.KaojiRegistered
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
import com.hnnujw.course.ui.theme.NeuPrimary
import com.hnnujw.course.ui.theme.SemanticDanger
import com.hnnujw.course.ui.theme.SemanticWarning
import kotlinx.coroutines.launch

/**
 * 教务系统「考级项目报名」页（kjgl/kjbm_*，gnmkdm=N2510）。
 *
 * 与 [XuegongScreen] 同构的独立全屏页，但数据源是**教务**：
 * 复用已登录的教务会话（[AcademicGatewayFactory.transportFor]），无需额外登录。
 *
 * ## 测试账号保护
 *
 * 测试账号（见 [KaojiClient.TEST_ACCOUNTS]）在这里只读演示：页面顶部有
 * 明显横幅，「报名 / 退报」点击后走本地模拟成功，**不会**向教务系统发出
 * 任何写请求 —— 这是开发者明确要求的硬性红线。
 */
@Composable
fun KaojiRegistrationScreen(
    school: SchoolConfig?,
    accountKey: String,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val testAccount = remember { KaojiClient.isTestAccount() }

    var page by remember { mutableStateOf<KaojiPage?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var needLogin by remember { mutableStateOf(false) }

    /** 正在提交的项目 id（报名）/ 记录 id（退报），用于按钮防抖。 */
    var submittingId by remember { mutableStateOf<String?>(null) }
    var confirmProject by remember { mutableStateOf<KaojiProject?>(null) }
    var confirmPhone by remember { mutableStateOf("") }
    var withdrawTarget by remember { mutableStateOf<KaojiRegistered?>(null) }

    fun load() {
        if (school == null || accountKey.isBlank()) {
            needLogin = true
            error = "尚未登录教务账号，无法读取考级报名信息"
            return
        }
        loading = true
        error = ""
        scope.launch {
            val transport = AcademicGatewayFactory.transportFor(school, accountKey)
            when (val result = KaojiClient.loadPage(transport, school)) {
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
            )) {
                is KaojiClient.SubmitResult.Simulated -> com.hnnujw.course.ui.system.GlassToaster.show(result.message)
                is KaojiClient.SubmitResult.Success -> com.hnnujw.course.ui.system.GlassToaster.show(result.message)
                is KaojiClient.SubmitResult.Failure -> com.hnnujw.course.ui.system.GlassToaster.show(result.message)
                is KaojiClient.SubmitResult.NeedLogin -> {
                    needLogin = true
                    error = result.message
                }
            }
            submittingId = null
            confirmProject = null
            if (needLogin.not()) load()
        }
    }

    fun withdraw(record: KaojiRegistered) {
        if (submittingId != null) return
        submittingId = record.id
        scope.launch {
            val transport = AcademicGatewayFactory.transportFor(school ?: return@launch, accountKey)
            when (val result = KaojiClient.withdrawRegistration(transport, school ?: return@launch, record)) {
                is KaojiClient.SubmitResult.Simulated -> com.hnnujw.course.ui.system.GlassToaster.show(result.message)
                is KaojiClient.SubmitResult.Success -> com.hnnujw.course.ui.system.GlassToaster.show(result.message)
                is KaojiClient.SubmitResult.Failure -> com.hnnujw.course.ui.system.GlassToaster.show(result.message)
                is KaojiClient.SubmitResult.NeedLogin -> {
                    needLogin = true
                    error = result.message
                }
            }
            submittingId = null
            withdrawTarget = null
            if (needLogin.not()) load()
        }
    }

    LaunchedEffect(school?.id, accountKey) {
        if (page == null && !loading) load()
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
            SystemIconButton(Icons.Outlined.Refresh, "刷新", { if (!loading) load() })
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
                title = page?.title.orEmpty(),
                registeredCount = page?.registered?.size ?: 0,
            )
            if (testAccount) {
                InlineWarning(
                    "当前为测试账号：仅作只读演示，「报名 / 退报」不会向教务系统提交任何数据。"
                )
            }
            when {
                needLogin -> Box(Modifier.fillMaxWidth().padding(top = 32.dp), contentAlignment = Alignment.Center) {
                    SystemEmptyState(
                        title = "需要登录",
                        message = error.ifBlank { "请先在首页登录教务账号，再回来查看考级报名" },
                        action = {
                            SystemPrimaryButton(text = "重新加载", onClick = { load() }, modifier = Modifier.fillMaxWidth())
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
                            SystemPrimaryButton(text = "重试", onClick = { load() }, modifier = Modifier.fillMaxWidth())
                        }
                    )
                }
                page != null -> {
                    if (error.isNotBlank()) InlineWarning(error)
                    KaojiProjectSection(
                        projects = page?.projects.orEmpty(),
                        submittingId = submittingId,
                        onRegister = {
                            confirmPhone = page?.registered?.firstOrNull()?.phone.orEmpty()
                            confirmProject = it
                        },
                    )
                    KaojiRegisteredSection(
                        records = page?.registered.orEmpty(),
                        submittingId = submittingId,
                        onWithdraw = { withdrawTarget = it },
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

/** 页头信息卡：图标 + 模块名 + 学期标题 + 已报数量。 */
@Composable
private fun KaojiHeaderCard(studentId: String, title: String, registeredCount: Int) {
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
            Column(Modifier.padding(start = 14.dp).weight(1f)) {
                Text(
                    text = "考级项目报名",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = title.ifBlank { "教务系统 · 等级考试报名" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
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

/** 可报名项目列表。 */
@Composable
private fun KaojiProjectSection(
    projects: List<KaojiProject>,
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
                    text = "当前没有开放报名的考级项目",
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

/** 已报名记录列表。 */
@Composable
private fun KaojiRegisteredSection(
    records: List<KaojiRegistered>,
    submittingId: String?,
    onWithdraw: (KaojiRegistered) -> Unit,
) {
    InsetGroupedSection(header = "已报名记录", footer = "退报前请先确认缴费状态；已缴费项目无法在线退报。") {
        if (records.isEmpty()) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    text = "暂无报名记录",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                records.forEach { record ->
                    KaojiRegisteredCard(
                        record = record,
                        submitting = submittingId == record.id,
                        onWithdraw = { onWithdraw(record) },
                    )
                }
            }
        }
    }
}

@Composable
private fun KaojiRegisteredCard(record: KaojiRegistered, submitting: Boolean, onWithdraw: () -> Unit) {
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
        val lines = buildList {
            if (record.term.isNotBlank()) add("学期：${record.term}")
            if (record.registeredAt.isNotBlank()) add("报名时间：${record.registeredAt}")
            if (record.examBegin.isNotBlank()) add("考试时间：${record.examBegin} 至 ${record.examEnd}")
            if (record.fee.isNotBlank()) add("费用：${record.fee} 元")
            if (record.ticketNo.isNotBlank()) add("准考证号：${record.ticketNo}")
            if (record.score.isNotBlank()) add("成绩：${record.score}")
            if (record.certNo.isNotBlank()) add("证书编号：${record.certNo}")
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
        SystemSecondaryButton(
            text = if (submitting) "正在提交…" else "退报",
            onClick = onWithdraw,
            enabled = !submitting,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp)
        )
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
