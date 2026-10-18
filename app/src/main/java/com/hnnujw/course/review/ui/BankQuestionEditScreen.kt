package com.hnnujw.course.review.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hnnujw.course.review.BankManageLogic
import com.hnnujw.course.review.ReviewDatabase
import com.hnnujw.course.review.ReviewQuestion
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
 * 逐题编辑 + 多选批量删除。
 *
 * 列表勾选 → 批量删（走 [ReviewDatabase.deleteQuestions]，连带清记录）；
 * 点某一行 → 进单题编辑（题干 / 选项 / 答案 / 解析），保存走 [ReviewDatabase.updateQuestion]。
 *
 * 长耗时操作走 IO + [busy] 防重复点击。
 */
@Composable
fun BankQuestionEditScreen(
    context: Context,
    bankId: String,
    onBack: () -> Unit
) {
    val db = remember { ReviewDatabase.get(context) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val questions = remember { mutableStateListOf<ReviewQuestion>() }
    val selected = remember { mutableStateListOf<String>() }
    var bankName by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    // 当前正在编辑的单题 qid（null = 不在编辑）；用 qid 而不是对象，避免列表刷新后对象失联
    var editingQid by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<List<String>>(emptyList()) }
    // 题目搜索：1059 题的库靠翻页找一道题是不现实的。
    var query by remember { mutableStateOf("") }

    /**
     * 过滤后的展示列表。
     *
     * 匹配题干、章节、答案字母三处 —— 用户手里可能只有一句题干、也可能只想看
     * "所有答案是 C 的题"。刻意**不匹配解析**：解析里出现的关键词太散，一击就命中一大片，
     * 反而让搜索失去定位作用。
     */
    val visible = remember(query, questions.size) {
        val keyword = query.trim()
        if (keyword.isEmpty()) questions.toList()
        else questions.filter { q ->
            q.stem.contains(keyword, ignoreCase = true) ||
                q.chapter.contains(keyword, ignoreCase = true) ||
                q.answer.equals(keyword.uppercase(), ignoreCase = true)
        }
    }

    suspend fun reload() {
        val result = withContext(Dispatchers.IO) {
            db.bankName(bankId) to db.listQuestions(bankId)
        }
        bankName = result.first
        questions.clear()
        questions.addAll(result.second)
        selected.clear()
        loaded = true
    }

    LaunchedEffect(bankId) { reload() }

    GlassPageScaffold(
        title = bankName.ifBlank { "题库题目" },
        subtitle = if (loaded) "${questions.size} 题" else null,
        onBack = onBack
    ) { padding ->
        if (!loaded) {
            GlassLoadingState(text = "正在读取题目…")
            return@GlassPageScaffold
        }
        if (questions.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = padding.calculateTopPadding() + 8.dp, start = 16.dp, end = 16.dp)
            ) {
                Card {
                    Text(
                        text = "这个题库还没有题目。回到首页导入 JSON 题库即可批量加题。",
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            return@GlassPageScaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    top = padding.calculateTopPadding() + 8.dp,
                    // 🔴 底部必须吃掉 scaffold 的 bottom inset：漏掉它，末位内容会被系统
                    // 导航条压住，表现为「底部内容被截断、要上滑才看得全」（2026-10 用户报）。
                    bottom = padding.calculateBottomPadding() + 24.dp,
                    start = 16.dp,
                    end = 16.dp
                ),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 批量选择工具行：**常显**（入口不按状态条件隐藏 —— 项目红线），
            // 否则「全选」只能在已勾选后出现，用户根本不知道有批量模式。
            // 集合操作全走 [BankManageLogic] 纯函数，这里只把结果写回 SnapshotStateList。
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryAction(
                    text = "全选",
                    enabled = !busy,
                    onClick = {
                        val next = BankManageLogic.selectAll(questions.map { it.id }, selected)
                        selected.clear()
                        selected.addAll(next)
                    },
                    modifier = Modifier.weight(1f)
                )
                SecondaryAction(
                    text = "反选",
                    enabled = !busy,
                    onClick = {
                        val next = BankManageLogic.invertSelection(questions.map { it.id }, selected)
                        selected.clear()
                        selected.addAll(next)
                    },
                    modifier = Modifier.weight(1f)
                )
                SecondaryAction(
                    text = "取消",
                    enabled = !busy && selected.isNotEmpty(),
                    onClick = { selected.clear() },
                    modifier = Modifier.weight(1f)
                )
            }

            // 批量删除栏：只在有勾选时出现，避免常驻占位
            if (selected.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SecondaryAction(
                        text = "删除选中（${selected.size}）",
                        enabled = !busy,
                        onClick = { pendingDelete = selected.toList() },
                        modifier = Modifier.weight(1f)
                    )
                }
                Text(
                    text = BankManageLogic.deleteQuestionsHint(selected.size),
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // 题目搜索：大题库（内置最大 320 题、导入的动辄上千）靠翻页找一道题不现实。
            // 匹配题干 / 章节 / 答案字母三处，见上面 `visible` 的说明。
            GlassTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = "搜索题干、章节或答案（如 A）",
                modifier = Modifier.fillMaxWidth(),
                trailing = if (query.isNotEmpty()) {
                    { Text("清除", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary) }
                } else null
            )
            if (query.isNotBlank()) {
                Text(
                    text = "命中 ${visible.size} / ${questions.size} 题",
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // 🔴 列表必须**懒加载**：1059 题的库如果用 Column + verticalScroll 全量
            // forEach，进页面就一次性组合全部题卡，卡顿肉眼可见。头部工具行常驻固定、
            // LazyColumn 独立滚动 —— 千万别给外层 Column 加 verticalScroll，那会与
            // LazyColumn 形成**同轴嵌套滚动**（子节点收到 Infinity 高度约束，
            // ScrollNode 抛异常崩进程，本项目踩过）。
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (visible.isEmpty() && query.isNotBlank()) {
                    item {
                        Text(
                            text = "没有匹配「$query」的题目。",
                            fontSize = 13.sp,
                            lineHeight = 19.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                items(visible, key = { it.id }) { q ->
                    Card {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = androidx.compose.ui.Alignment.Top
                        ) {
                            // 勾选框（自绘，不用 Material Checkbox）
                            Text(
                                text = if (q.id in selected) "◉" else "○",
                                fontSize = 16.sp,
                                color = if (q.id in selected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = q.stem,
                                    fontSize = 14.sp,
                                    lineHeight = 19.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "${q.chapter} · ${if (q.isMulti) "多选" else "单选"} · 答案 ${q.answer}",
                                    fontSize = 12.sp,
                                    lineHeight = 17.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    SecondaryAction(
                                        text = "勾选",
                                        enabled = !busy,
                                        onClick = {
                                            if (q.id in selected) selected.remove(q.id) else selected.add(q.id)
                                        },
                                        modifier = Modifier.weight(1f)
                                    )
                                    SecondaryAction(
                                        text = "编辑",
                                        enabled = !busy,
                                        onClick = { editingQid = q.id },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        // 单题编辑弹窗
        editingQid?.let { qid ->
            val question = questions.firstOrNull { it.id == qid }
            if (question != null) {
                QuestionEditDialog(
                    question = question,
                    onSave = { stem, options, answer, analysis ->
                        busy = true
                        editingQid = null
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) {
                                db.updateQuestion(bankId, qid, stem, options, answer, analysis)
                            }
                            busy = false
                            GlassToaster.show(if (ok) "已保存" else "保存失败")
                            if (ok) reload()
                        }
                    },
                    onDismiss = { editingQid = null }
                )
            } else {
                editingQid = null
            }
        }

        // 批量删除的二次确认 —— 删题连带清记录，必须确认
        if (pendingDelete.isNotEmpty()) {
            SystemDialog(
                onDismissRequest = { pendingDelete = emptyList() },
                title = { Text("删除题目", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface) },
                confirmButton = {
                    SystemPrimaryButton(
                        text = "删除",
                        onClick = {
                            val ids = pendingDelete
                            pendingDelete = emptyList()
                            busy = true
                            scope.launch {
                                val n = withContext(Dispatchers.IO) { db.deleteQuestions(bankId, ids) }
                                busy = false
                                GlassToaster.show("已删除 $n 道题")
                                reload()
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                dismissButton = {
                    SystemSecondaryButton(
                        text = "取消",
                        onClick = { pendingDelete = emptyList() },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            ) {
                Text(
                    text = BankManageLogic.deleteQuestionsHint(pendingDelete.size),
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** 单题编辑弹窗：题干 / 选项（每行一个）/ 答案 / 解析。 */
@Composable
private fun QuestionEditDialog(
    question: ReviewQuestion,
    onSave: (stem: String, options: List<String>, answer: String, analysis: String) -> Unit,
    onDismiss: () -> Unit
) {
    var stem by remember(question.id) { mutableStateOf(question.stem) }
    var optionsText by remember(question.id) { mutableStateOf(question.options.joinToString("\n")) }
    var answer by remember(question.id) { mutableStateOf(question.answer) }
    var analysis by remember(question.id) { mutableStateOf(question.explanation) }

    val options = remember(optionsText) {
        optionsText.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
    }
    // 至少要有题干和非空答案才能存
    val canSave = stem.isNotBlank() && answer.isNotBlank()

    SystemDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑题目", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface) },
        confirmButton = {
            SystemPrimaryButton(
                text = "保存",
                onClick = { onSave(stem.trim(), options, answer.trim().uppercase(), analysis.trim()) },
                enabled = canSave,
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
                .heightIn(max = 460.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            FieldLabel("题干")
            GlassTextField(
                value = stem,
                onValueChange = { stem = it },
                placeholder = "题目内容",
                singleLine = false,
                minHeight = 72.dp,
                modifier = Modifier.fillMaxWidth().testTag("edit-stem")
            )
            FieldLabel("选项（每行一个）")
            GlassTextField(
                value = optionsText,
                onValueChange = { optionsText = it },
                placeholder = "选项一\n选项二\n选项三\n选项四",
                singleLine = false,
                minHeight = 96.dp,
                modifier = Modifier.fillMaxWidth().testTag("edit-options")
            )
            FieldLabel("答案（如 A 或 ABD）")
            GlassTextField(
                value = answer,
                onValueChange = { answer = it },
                placeholder = "A",
                singleLine = true,
                isError = answer.isBlank(),
                modifier = Modifier.fillMaxWidth().testTag("edit-answer")
            )
            FieldLabel("解析（可留空）")
            GlassTextField(
                value = analysis,
                onValueChange = { analysis = it },
                placeholder = "答案解析",
                singleLine = false,
                minHeight = 72.dp,
                modifier = Modifier.fillMaxWidth().testTag("edit-analysis")
            )
        }
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(
        text = text,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSurface
    )
}
