package com.hnnujw.course.review.ui

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hnnujw.course.review.BankManageLogic
import com.hnnujw.course.review.ReviewDatabase
import com.hnnujw.course.ui.system.GlassLoadingIndicator
import com.hnnujw.course.ui.system.GlassLoadingState
import com.hnnujw.course.ui.system.GlassPageScaffold
import com.hnnujw.course.ui.system.GlassTextField
import com.hnnujw.course.ui.system.GlassToaster
import com.hnnujw.course.ui.system.SystemDialog
import com.hnnujw.course.ui.system.SystemPrimaryButton
import com.hnnujw.course.ui.system.SystemSecondaryButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 题库管理页：创建 / 改名 / 删除 / 合并。
 *
 * 所有判断都走 [BankManageLogic] 的纯函数，本页只负责渲染与收集输入 ——
 * 项目红线：判断逻辑不许长在 Composable 里。
 *
 * 长耗时的库操作（合并 / 批量删题）一律走 [Dispatchers.IO]，并用 [busy] 防重复点击。
 */
@Composable
fun BankManageScreen(
    context: Context,
    onBack: () -> Unit,
    /** 打开某题库的逐题编辑（进题目列表改题干/选项/答案/解析、多选批量删除）。 */
    onEditQuestions: (bankId: String) -> Unit
) {
    val db = remember { ReviewDatabase.get(context) }
    val banks = remember { mutableStateListOf<ReviewDatabase.BankSummary>() }
    var loaded by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    // 用 rememberCoroutineScope 而不是 MainScope()：前者随 Composable 一起取消，
    // 后者会在页面退出后继续跑（往已销毁的 state 写值）。
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    // 弹窗状态。用 sealed 形式表达「当前开的是哪个弹窗」，比一堆布尔干净。
    var dialog by remember { mutableStateOf<ManageDialog>(ManageDialog.None) }

    suspend fun reload() {
        val list = withContext(Dispatchers.IO) { db.listBanks() }
        banks.clear()
        banks.addAll(list)
        loaded = true
    }

    LaunchedEffect(Unit) { reload() }

    GlassPageScaffold(
        title = "题库管理",
        subtitle = "创建 · 编辑 · 删除 · 合并",
        onBack = onBack
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding() + 8.dp, start = 16.dp, end = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ConfirmButton(
                    text = "创建题库",
                    enabled = !busy,
                    onClick = { dialog = ManageDialog.Create },
                    modifier = Modifier.weight(1f)
                )
                SecondaryAction(
                    text = "合并题库",
                    enabled = !busy && banks.size >= 2,
                    onClick = { dialog = ManageDialog.Merge },
                    modifier = Modifier.weight(1f)
                )
            }

            if (!loaded) {
                GlassLoadingState(text = "正在读取题库…")
                return@GlassPageScaffold
            }
            if (banks.isEmpty()) {
                Card {
                    Text(
                        text = "还没有题库。可以先「创建题库」再逐题添加，或在首页导入 JSON 题库。",
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                return@GlassPageScaffold
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                banks.forEach { bank ->
                    Card {
                        Text(
                            text = bank.name,
                            fontSize = 16.sp,
                            lineHeight = 22.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "${bank.questionCount} 题 · 待复习错题 ${bank.pendingWrong}",
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            SecondaryAction(
                                text = "编辑题目",
                                enabled = !busy && bank.questionCount > 0,
                                onClick = { onEditQuestions(bank.id) },
                                modifier = Modifier.weight(1f)
                            )
                            SecondaryAction(
                                text = "改名",
                                enabled = !busy,
                                onClick = { dialog = ManageDialog.Rename(bank.id, bank.name) },
                                modifier = Modifier.weight(1f)
                            )
                            SecondaryAction(
                                text = "删除",
                                enabled = !busy,
                                onClick = { dialog = ManageDialog.Delete(bank.id, bank.name) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        // —— 弹窗区
        if (busy) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                GlassLoadingIndicator(size = 28.dp)
            }
        }

        when (val d = dialog) {
            is ManageDialog.None -> Unit

            is ManageDialog.Create -> NameDialog(
                title = "创建题库",
                initial = "",
                existingNames = banks.map { it.name }.toSet(),
                onConfirm = { name ->
                    busy = true
                    dialog = ManageDialog.None
                    // 用一个即刻协程做库写；写完刷新列表并回一句话
                    scope.launch {
                        val created = withContext(Dispatchers.IO) { db.createBank(name) }
                        busy = false
                        if (created != null) {
                            GlassToaster.show("已创建「$name」")
                            reload()
                        } else {
                            GlassToaster.show("创建失败：已有同名题库")
                        }
                    }
                },
                onDismiss = { dialog = ManageDialog.None }
            )

            is ManageDialog.Rename -> NameDialog(
                title = "重命名题库",
                initial = d.currentName,
                // 排掉自己：否则「不改名直接保存」会被自己挡下
                existingNames = banks.map { it.name }.toSet() - d.currentName,
                onConfirm = { name ->
                    busy = true
                    dialog = ManageDialog.None
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) { db.renameBank(d.id, name) }
                        busy = false
                        GlassToaster.show(if (ok) "已改名为「$name」" else "改名失败：已有同名题库")
                        if (ok) reload()
                    }
                },
                onDismiss = { dialog = ManageDialog.None }
            )

            is ManageDialog.Delete -> ConfirmDialog(
                title = "删除题库",
                // 删除会连带清空该库的答题记录与错题本 —— 和「删自定义课程不加确认」性质不同，
                // 这里必须二次确认（项目已驳回「不加确认」的那条只针对课程）。
                message = "将删除「${d.name}」及其全部题目、答题记录、错题与收藏。此操作不可撤销。",
                confirmText = "删除",
                onConfirm = {
                    busy = true
                    dialog = ManageDialog.None
                    scope.launch {
                        withContext(Dispatchers.IO) { db.deleteBank(d.id) }
                        busy = false
                        GlassToaster.show("已删除「${d.name}」")
                        reload()
                    }
                },
                onDismiss = { dialog = ManageDialog.None }
            )

            is ManageDialog.Merge -> MergeDialog(
                banks = banks.toList(),
                busy = busy,
                onRun = { target, sources ->
                    busy = true
                    scope.launch {
                        val report = withContext(Dispatchers.IO) { db.mergeBanks(target, sources) }
                        busy = false
                        dialog = ManageDialog.MergeResult(report, sources)
                        reload()
                    }
                },
                onDismiss = { dialog = ManageDialog.None }
            )

            is ManageDialog.MergeResult -> ConfirmDialog(
                title = "合并完成",
                message = buildString {
                    append("已合并 ${d.report.totalMoved} 道题")
                    if (d.report.hasRenamed) append("，其中 ${d.report.renamedCount} 道因题号冲突已重新编号")
                    append("。是否删除已合并的源题库：${d.report.sourceNames.joinToString("、")}？")
                },
                confirmText = "删除源题库",
                dismissText = "暂不删除",
                onConfirm = {
                    busy = true
                    dialog = ManageDialog.None
                    scope.launch {
                        withContext(Dispatchers.IO) { d.sources.forEach { db.deleteBank(it) } }
                        busy = false
                        GlassToaster.show("已删除源题库")
                        reload()
                    }
                },
                onDismiss = {
                    dialog = ManageDialog.None
                    GlassToaster.show("已保留源题库，可稍后手动删除")
                }
            )
        }
    }
}

/** 当前打开的弹窗。用密封类而不是若干布尔，避免出现「两个弹窗同时为 true」。 */
private sealed interface ManageDialog {
    data object None : ManageDialog
    data object Create : ManageDialog
    data class Rename(val id: String, val currentName: String) : ManageDialog
    data class Delete(val id: String, val name: String) : ManageDialog
    data object Merge : ManageDialog
    data class MergeResult(
        val report: BankManageLogic.MergeReport,
        val sources: List<String>
    ) : ManageDialog
}

/** 创建 / 改名共用的名字输入弹窗：实时校验并**显示具体原因**，不是只把按钮置灰。 */
@Composable
private fun NameDialog(
    title: String,
    initial: String,
    existingNames: Set<String>,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var input by remember { mutableStateOf(initial) }
    val check = remember(input, existingNames) { BankManageLogic.validateBankName(input, existingNames) }
    val ok = check is BankManageLogic.NameCheck.Ok

    SystemDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface) },
        confirmButton = {
            SystemPrimaryButton(
                text = "保存",
                onClick = { (check as? BankManageLogic.NameCheck.Ok)?.let { onConfirm(it.trimmed) } },
                enabled = ok,
                modifier = Modifier.fillMaxWidth()
            )
        },
        dismissButton = {
            SystemSecondaryButton(text = "取消", onClick = onDismiss, modifier = Modifier.fillMaxWidth())
        }
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassTextField(
                value = input,
                onValueChange = { input = it },
                placeholder = "题库名字",
                singleLine = true,
                isError = !ok,
                modifier = Modifier.fillMaxWidth().testTag("bank-name-field")
            )
            // 明确说出「哪里不对」——按项目红线，校验结果带原因，不能只靠禁用态暗示。
            if (!ok) {
                Text(
                    text = check.message,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

/** 合并弹窗：选一个目标库 + 多选源库，执行前显示「将合并 N 题、M 题重编号」的预估。 */
@Composable
private fun MergeDialog(
    banks: List<ReviewDatabase.BankSummary>,
    busy: Boolean,
    onRun: (target: String, sources: List<String>) -> Unit,
    onDismiss: () -> Unit
) {
    var target by remember { mutableStateOf("") }
    val selected = remember { mutableStateListOf<String>() }

    val check = remember(target, selected.toList(), banks) {
        BankManageLogic.resolveMerge(target, selected.toList(), banks)
    }
    val ok = check is BankManageLogic.MergeCheck.Ok

    // 预估：目标库已有 qid 集合对 UI 不可见（题多时开销大），
    // 这里只报「将合并的题数」，冲突重编号数在结果报告里给准确值。
    val movingCount = remember(selected.toList(), banks) {
        val ids = selected.toSet()
        banks.filter { it.id in ids }.sumOf { it.questionCount }
    }

    SystemDialog(
        onDismissRequest = onDismiss,
        title = { Text("合并题库", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface) },
        confirmButton = {
            SystemPrimaryButton(
                text = if (busy) "合并中…" else "开始合并",
                onClick = { (check as? BankManageLogic.MergeCheck.Ok)?.let { onRun(it.target, it.sources) } },
                enabled = ok && !busy,
                modifier = Modifier.fillMaxWidth()
            )
        },
        dismissButton = {
            SystemSecondaryButton(text = "取消", onClick = onDismiss, modifier = Modifier.fillMaxWidth())
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 420.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "选择一个「合并到」的题库，再勾选要合并进来的题库。",
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Text("合并到", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
            banks.forEach { bank ->
                SelectRow(
                    label = "${bank.name}（${bank.questionCount} 题）",
                    selected = target == bank.id,
                    onClick = {
                        target = bank.id
                        // 选成目标后，把它从源里摘掉（否则会被判 TargetInSources）
                        selected.remove(bank.id)
                    }
                )
            }

            Spacer(Modifier.height(4.dp))
            Text("要合并的题库（可多选）", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
            banks.forEach { bank ->
                val isTarget = target == bank.id
                SelectRow(
                    label = "${bank.name}（${bank.questionCount} 题）",
                    selected = bank.id in selected,
                    enabled = !isTarget,
                    onClick = {
                        if (bank.id in selected) selected.remove(bank.id) else selected.add(bank.id)
                    }
                )
            }

            if (ok) {
                // `check` 已被上面的 `check is MergeCheck.Ok` 智能转换过，这里不用再 as。
                val c = check
                Text(
                    text = "将合并 $movingCount 道题到「${banks.firstOrNull { it.id == c.target }?.name ?: ""}」。",
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else if (target.isNotBlank() || selected.isNotEmpty()) {
                Text(
                    text = check.message,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

/** 可勾选的一行（合并弹窗里选目标/源）。用玻璃卡片 + 对勾，不用 Material Checkbox。 */
@Composable
private fun SelectRow(
    label: String,
    selected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val alpha = if (enabled) 1f else 0.4f
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = if (enabled) onClick else null
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // 自绘选择指示：实心圆 = 选中，空心圆 = 未选。不用 Material Checkbox/RadioButton。
            Text(
                text = if (selected) "◉" else "○",
                fontSize = 15.sp,
                color = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = label,
                fontSize = 14.sp,
                lineHeight = 19.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha)
            )
        }
    }
}

/** 通用二次确认弹窗（删除题库 / 合并后删源库）。走项目已有的 [SystemDialog] 槽位。 */
@Composable
private fun ConfirmDialog(
    title: String,
    message: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    dismissText: String = "取消"
) {
    SystemDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface) },
        confirmButton = {
            SystemPrimaryButton(text = confirmText, onClick = onConfirm, modifier = Modifier.fillMaxWidth())
        },
        dismissButton = {
            SystemSecondaryButton(text = dismissText, onClick = onDismiss, modifier = Modifier.fillMaxWidth())
        }
    ) {
        Text(text = message, fontSize = 13.sp, lineHeight = 19.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
