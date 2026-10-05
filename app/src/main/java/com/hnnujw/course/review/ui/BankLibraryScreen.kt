package com.hnnujw.course.review.ui

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hnnujw.course.review.BankManageLogic
import com.hnnujw.course.review.BundledBanks
import com.hnnujw.course.review.ReviewDatabase
import com.hnnujw.course.review.parseJsonBank
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
 * **题库仓库**页 —— 题库的唯一集散地。
 *
 * 2026-10 用户要求「将题库仓库做成页面，将题库管理功能移到题库仓库里」。此前
 * 仓库只是首页列表的一段，而「改名 / 删除」散在另一个「题库管理」页，
 * 两者都在干"对题库整体做点什么"，却分在两个地方。现在合成一页，三段：
 *
 *  1. **取出到我的题库** —— 仓库里的东西：还没导入的随包内置库（[BundledBanks.pendingSpecs]）
 *     加上用户自己导入过、后来被「移出」的库。点「取出」才真正进「我的题库」。
 *  2. **导入** —— 从手机里选一个 JSON 文件导进来（导入即取出）。
 *  3. **题库管理** —— 逐题编辑 / 改名 / 删除。这里列出**全部**题库
 *     （含已经取出的），因为"改名""删除"是对题库本身的操作，与它当前在不在
 *     「我的题库」里无关。
 *
 * 为什么第 1 段和第 3 段会有重叠：两段的**动词**不同 —— 第 1 段是"拿出来 / 放回去"
 * （改的是显示状态，不碰数据），第 3 段是"改它 / 删它"（不可逆）。混成一段的话，
 * 用户很容易把「移出」和「删除」当成同一件事（首页曾经就是四个按钮排在一行）。
 *
 * 「创建题库」「合并题库」「格式说明」已于 2026-10 按用户要求下线（DB 层的
 * createBank / mergeBanks 一并删除）—— 题库的来源只剩内置库取出与 JSON 导入。
 *
 * 所有判断都走 [BankManageLogic] 的纯函数，本页只负责渲染与收集输入 ——
 * 项目红线：判断逻辑不许长在 Composable 里。
 */
@Composable
fun BankLibraryScreen(
    context: Context,
    onBack: () -> Unit,
    /** 点题库名进详情页（看概况、开练）。 */
    onOpenBankDetail: (bankId: String) -> Unit,
    /** 打开某题库的逐题编辑（改题干/选项/答案/解析 + 批量删除）。 */
    onEditQuestions: (bankId: String) -> Unit
) {
    val db = remember { ReviewDatabase.get(context) }
    val scope = rememberCoroutineScope()
    val banks = remember { mutableStateListOf<ReviewDatabase.BankSummary>() }
    var loaded by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    // 正在取出的项（内置库要读最多 255KB 的 asset 并解析，需要一个"正在…"）。
    var takingOutKey by remember { mutableStateOf<String?>(null) }
    var reloadKey by remember { mutableIntStateOf(0) }
    // 用 sealed 形式表达「当前开的是哪个弹窗」，比一堆布尔干净（避免两个弹窗同时为 true）。
    var dialog by remember { mutableStateOf<ManageDialog>(ManageDialog.None) }

    // ── 顶栏跟手折叠 ─────────────────────────────────────────────────────
    // 此前本页走 GlassPageScaffold 的默认值（collapseFraction = 0f），于是内容滚动时
    // 「题库仓库」大标题**始终不变** —— 而课表 / 成绩 / 设置 / 二课 / 一卡通都会收成
    // 细玻璃条。同一个手势在不同页面表现不一致，用户一眼就能看出"这页没做"（2026-10
    // 用户报「在题库仓库页面向上滑动后，题库仓库的字样没有改变」）。
    //
    // 判据与其他页面对齐：滚动 96dp 收满；首项已经滚出屏幕时直接收满
    // （否则长列表快滚时首项偏移会来回跳，标题跟着抖）。
    val listState = rememberLazyListState()
    val headerCollapse by remember {
        derivedStateOf {
            if (listState.firstVisibleItemIndex > 0) 1f
            else (listState.firstVisibleItemScrollOffset / 96f).coerceIn(0f, 1f)
        }
    }

    LaunchedEffect(reloadKey) {
        val list = withContext(Dispatchers.IO) { db.listBanks() }
        banks.clear()
        banks.addAll(list)
        loaded = true
    }

    suspend fun reload() {
        val list = withContext(Dispatchers.IO) { db.listBanks() }
        banks.clear()
        banks.addAll(list)
    }

    // 从系统文件选择器导入 JSON 题库。
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        importing = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val text = context.contentResolver.openInputStream(uri)?.use {
                        it.readBytes().toString(Charsets.UTF_8)
                    } ?: error("无法读取所选文件")
                    val bank = parseJsonBank(
                        text,
                        fallbackName = uri.lastPathSegment?.substringAfterLast('/') ?: "导入题库"
                    )
                    if (bank.questions.isEmpty()) {
                        error(bank.errors.firstOrNull() ?: "题库里没有可用的题目")
                    }
                    // 用户主动导入 ⇒ 直接取出（默认参数就是 true）。
                    db.importBank(bank.name, bank.questions) to bank
                }
            }
            importing = false
            result.onSuccess { (_, bank) ->
                reload()
                val dropped = if (bank.errors.isEmpty()) "" else "，${bank.errors.size} 条被跳过"
                GlassToaster.show("已导入「${bank.name}」${bank.questions.size} 题$dropped")
            }.onFailure { e ->
                GlassToaster.show("导入失败：${e.message ?: "文件无法识别"}")
            }
        }
    }

    // 仓库内容 = 还没导入的内置库 + 已导入但被移出的库。
    // 内置库用**题库名**去重（见 BundledBanks.pendingSpecs），与库里的名字同一口径。
    val pendingSpecs = BundledBanks.pendingSpecs(banks.map { it.name }.toSet())
    val shelved = banks.filter { !it.selected }
    val mineCount = banks.count { it.selected }
    val warehouseCount = pendingSpecs.size + shelved.size

    GlassPageScaffold(
        title = "题库仓库",
        subtitle = "我的题库 $mineCount 个 · 仓库 $warehouseCount 个",
        onBack = onBack,
        collapseFraction = headerCollapse
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding() + 8.dp,
                // 🔴 底部必须吃掉 scaffold 的 bottom inset，否则末位内容会被系统导航条压住。
                bottom = padding.calculateBottomPadding() + 24.dp,
                start = 16.dp,
                end = 16.dp
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // —— ① 取出到我的题库
            item {
                SectionTitle("取出到我的题库", trailing = {
                    Text(
                        text = "共 $warehouseCount 个",
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                })
            }

            if (pendingSpecs.isEmpty() && shelved.isEmpty()) {
                item {
                    EmptyHint(
                        "仓库是空的 —— 所有题库都取出来了。" +
                            "想让它回到这里，在首页「我的题库」里点「移出」即可（不会丢数据）。"
                    )
                }
            }

            // 未导入的内置库：点「取出」才真正解析并写库（单份最大 255KB，走 IO）。
            items(pendingSpecs, key = { "spec:${it.assetPath}" }) { spec ->
                val key = "spec:${spec.assetPath}"
                WarehouseRow(
                    name = spec.bankName,
                    detail = "${spec.expectedCount} 题 · 随包内置",
                    busy = takingOutKey == key,
                    onTake = {
                        takingOutKey = key
                        scope.launch {
                            val bankId = BundledBanks.importOne(context, db, spec)
                            takingOutKey = null
                            reload()
                            if (bankId != null) {
                                GlassToaster.show("已取出「${spec.bankName}」${spec.expectedCount} 题")
                            } else {
                                GlassToaster.show("取出失败，可能已存在同名题库")
                            }
                        }
                    }
                )
            }

            // 已导入但被移出的库：取回来只是翻一个标志位，不重新解析。
            items(shelved, key = { "shelf:${it.id}" }) { bank ->
                val key = "shelf:${bank.id}"
                WarehouseRow(
                    name = bank.name,
                    detail = buildString {
                        append("${bank.questionCount} 题")
                        if (bank.pendingWrong > 0) append(" · 待复习 ${bank.pendingWrong}")
                    },
                    busy = takingOutKey == key,
                    onTake = {
                        scope.launch {
                            withContext(Dispatchers.IO) { db.setBankSelected(bank.id, true) }
                            reload()
                            GlassToaster.show("已取出「${bank.name}」")
                        }
                    }
                )
            }

            // —— ② 导入
            item {
                SectionTitle("导入题库")
            }
            item {
                Card(entranceGroup = 1) {
                    Text(
                        text = "从手机里选一个 JSON 题库文件导进来。导入后会自动出现在「我的题库」里，" +
                            "可以直接开始练习。",
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    SecondaryAction(
                        text = "选择文件导入",
                        enabled = !importing && !busy,
                        onClick = {
                            importLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // —— ③ 题库管理（逐题编辑 / 改名 / 删除）
            item {
                SectionTitle("题库管理", trailing = {
                    Text(
                        text = "共 ${banks.size} 个",
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                })
            }

            if (!loaded) {
                item { GlassLoadingState(text = "正在读取题库…") }
            } else if (banks.isEmpty()) {
                item {
                    EmptyHint("还没有任何题库。可以从上面「取出」一个内置库，或导入一个 JSON 题库。")
                }
            } else {
                items(banks, key = { "bank:${it.id}" }) { bank ->
                    val position = banks.indexOfFirst { it.id == bank.id }
                    ManageBankRow(
                        bank = bank,
                        entranceGroup = position.coerceAtMost(3),
                        enabled = !busy && !importing,
                        onOpen = { onOpenBankDetail(bank.id) },
                        onEditQuestions = { onEditQuestions(bank.id) },
                        onRename = { dialog = ManageDialog.Rename(bank.id, bank.name) },
                        onDelete = { dialog = ManageDialog.Delete(bank.id, bank.name) }
                    )
                }
            }

            if (importing) {
                item { GlassLoadingState(text = "正在导入…") }
            }
        }

        if (busy) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                GlassLoadingIndicator(size = 28.dp)
            }
        }

        when (val d = dialog) {
            is ManageDialog.None -> Unit

            is ManageDialog.Rename -> NameDialog(
                title = "重命名题库",
                initial = d.currentName,
                // 排掉自己：否则「不改名直接保存」会被自己挡下。
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
                // 删除会连带清空该库的答题记录与错题本 —— 与「移出」（只改显示状态）
                // 是完全不同量级的操作，必须二次确认并说清区别。
                message = "将删除「${d.name}」及其全部题目、答题记录、错题与收藏。此操作不可撤销。" +
                    "只是想让它从「我的题库」消失的话，回首页点「移出」即可 —— 那样不会丢数据。",
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
        }
    }
}

/** 当前打开的弹窗。用密封类而不是若干布尔，避免出现「两个弹窗同时为 true」。 */
private sealed interface ManageDialog {
    data object None : ManageDialog
    data class Rename(val id: String, val currentName: String) : ManageDialog
    data class Delete(val id: String, val name: String) : ManageDialog
}

/** 仓库里的一行：名字 + 说明 + 「取出」。 */
@Composable
private fun WarehouseRow(
    name: String,
    detail: String,
    busy: Boolean,
    onTake: () -> Unit
) {
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = name,
                    fontSize = 15.sp,
                    lineHeight = 21.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = detail,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(10.dp))
            if (busy) {
                Text(
                    text = "取出中…",
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                SecondaryAction(text = "取出", enabled = true, onClick = onTake)
            }
        }
    }
}

/**
 * 题库管理里的一行：名字 + 题数 + **当前在哪**（我的题库 / 仓库）+ 三个操作。
 *
 * 状态标签不是装饰：同一个库既能在这里被改名删除，又可能正躺在仓库里，
 * 不标出来用户会以为"管理列表里的库都是已经能练的"。
 */
@Composable
private fun ManageBankRow(
    bank: ReviewDatabase.BankSummary,
    entranceGroup: Int,
    enabled: Boolean,
    onOpen: () -> Unit,
    onEditQuestions: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    Card(entranceGroup = entranceGroup) {
        Text(
            text = bank.name,
            fontSize = 16.sp,
            lineHeight = 22.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = buildString {
                append("${bank.questionCount} 题")
                if (bank.pendingWrong > 0) append(" · 待复习错题 ${bank.pendingWrong}")
                append(if (bank.selected) " · 在我的题库" else " · 在仓库")
            },
            fontSize = 12.sp,
            lineHeight = 17.sp,
            color = if (bank.selected) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.primary
            }
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryAction(
                text = "查看",
                enabled = enabled,
                onClick = onOpen,
                modifier = Modifier.weight(1f)
            )
            SecondaryAction(
                text = "编辑题目",
                enabled = enabled && bank.questionCount > 0,
                onClick = onEditQuestions,
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryAction(
                text = "改名",
                enabled = enabled,
                onClick = onRename,
                modifier = Modifier.weight(1f)
            )
            SecondaryAction(
                text = "删除",
                enabled = enabled,
                onClick = onDelete,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/** 段落内的浅色说明块（不是错误态，只是"这里暂时没东西"）。 */
@Composable
private fun EmptyHint(text: String) {
    Card {
        Text(
            text = text,
            fontSize = 13.sp,
            lineHeight = 19.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SectionTitle(text: String, trailing: @Composable (() -> Unit)? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        trailing?.invoke()
    }
}

/** 改名弹窗：实时校验并**显示具体原因**，不是只把按钮置灰。 */
@Composable
private fun NameDialog(
    title: String,
    initial: String,
    existingNames: Set<String>,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var input by remember { mutableStateOf(initial) }
    val check = remember(input, existingNames) {
        BankManageLogic.validateBankName(input, existingNames)
    }
    val ok = check is BankManageLogic.NameCheck.Ok

    SystemDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(title, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
        },
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
            // 明确说出「哪里不对」—— 按项目红线，校验结果带原因，不能只靠禁用态暗示。
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

/** 通用二次确认弹窗（删除题库）。走项目已有的 [SystemDialog] 槽位。 */
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
        title = {
            Text(title, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
        },
        confirmButton = {
            SystemPrimaryButton(text = confirmText, onClick = onConfirm, modifier = Modifier.fillMaxWidth())
        },
        dismissButton = {
            SystemSecondaryButton(text = dismissText, onClick = onDismiss, modifier = Modifier.fillMaxWidth())
        }
    ) {
        Text(
            text = message,
            fontSize = 13.sp,
            lineHeight = 19.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
