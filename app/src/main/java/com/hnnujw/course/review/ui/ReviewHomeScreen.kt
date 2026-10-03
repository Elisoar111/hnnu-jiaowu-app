package com.hnnujw.course.review.ui

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hnnujw.course.review.PracticeEntry
import com.hnnujw.course.review.AiKeyStore
import com.hnnujw.course.review.BundledBanks
import com.hnnujw.course.review.PracticeLaunch
import com.hnnujw.course.review.ReviewDatabase
import com.hnnujw.course.review.ReviewMode
import com.hnnujw.course.review.parseJsonBank
import com.hnnujw.course.review.resolvePracticeEntry
import com.hnnujw.course.ui.system.GlassCircleButton
import com.hnnujw.course.ui.system.GlassLoadingState
import com.hnnujw.course.ui.system.GlassPageScaffold
import com.hnnujw.course.ui.system.GlassStatChip
import com.hnnujw.course.ui.system.GlassToaster
import com.hnnujw.course.ui.system.SystemDialog
import com.hnnujw.course.ui.system.SystemPrimaryButton
import com.hnnujw.course.ui.system.SystemSecondaryButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 复习模块主屏：题库列表 + 练习入口 + 导入。
 *
 * 三个练习入口（顺序/随机/错题）走同一套 [pickQuestions]，只是参数不同 ——
 * 不为每个模式各写一份列表页。
 */
@Composable
fun ReviewHomeScreen(
    context: Context,
    onBack: () -> Unit,
    onStartPractice: (PracticeLaunch) -> Unit,
    onOpenBankDetail: (bankId: String) -> Unit,
    onOpenAiSettings: () -> Unit,
    onOpenTemplate: () -> Unit,
    /** 打开题库管理页（创建 / 改名 / 删除 / 合并）。 */
    onOpenBankManage: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val db = remember { ReviewDatabase.get(context) }
    var banks by remember { mutableStateOf<List<ReviewDatabase.BankSummary>>(emptyList()) }
    var overview by remember { mutableStateOf<ReviewDatabase.Overview?>(null) }
    var importing by remember { mutableStateOf(false) }
    var reloadKey by remember { mutableIntStateOf(0) }
    // 内置题库自动导入只做一次：reloadKey 会在导入后自增，靠这个标记挡住第二次触发。
    // 不加的话每轮 reload 都会重新进 importAll，白跑一遍 700KB 解析。
    var bundledImportTried by remember { mutableStateOf(false) }
    // 待确认删除的题库（首页行内删除的二次确认）。null = 没有待确认项。
    var pendingDeleteBank by remember { mutableStateOf<ReviewDatabase.BankSummary?>(null) }

    // 每次 reloadKey 变化就重读一次库状态。数据量小（几千题），直接全量读。
    LaunchedEffect(reloadKey) {
        val loaded = withContext(Dispatchers.IO) {
            db.listBanks() to db.overview()
        }
        banks = loaded.first
        overview = loaded.second

        // 库里一个题库都没有 = 首次进入（或用户清空），自动导入随包内置题库。
        // 判定用 bankCount == 0 而不是「内置库缺失就补」——importBank 对同名库是覆盖语义，
        // 会在删旧题时连带清掉 records / wrong_book / favorites，答题途中触发就是丢数据。
        if (loaded.second.bankCount == 0 && !bundledImportTried) {
            bundledImportTried = true
            importing = true
            val imported = BundledBanks.importAll(context, db)
            importing = false
            if (imported > 0) {
                reloadKey++
                GlassToaster.show("已内置 $imported 题，可直接练习")
            } else {
                GlassToaster.show("内置题库导入失败，可用「导入题库」手动选择文件")
            }
        }
    }

    // 导入：系统文件选择器 → 读文本 → 解析 → 落库。
    val importLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        importing = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val text = context.contentResolver.openInputStream(uri)?.use {
                        it.readBytes().toString(Charsets.UTF_8)
                    } ?: error("无法读取所选文件")
                    val bank = parseJsonBank(text, fallbackName = uri.lastPathSegment?.substringAfterLast('/') ?: "导入题库")
                    if (bank.questions.isEmpty()) {
                        error(bank.errors.firstOrNull() ?: "题库里没有可用的题目")
                    }
                    db.importBank(bank.name, bank.questions) to bank
                }
            }
            importing = false
            result.onSuccess { (bankId, bank) ->
                reloadKey++
                val dropped = if (bank.errors.isEmpty()) "" else "，${bank.errors.size} 条被跳过"
                GlassToaster.show("已导入「${bank.name}」${bank.questions.size} 题$dropped")
            }.onFailure { e ->
                GlassToaster.show("导入失败：${e.message ?: "文件无法识别"}")
            }
        }
    }

    GlassPageScaffold(
        title = "复习",
        subtitle = if (banks.isEmpty()) null else "${banks.size} 个题库 · ${overview?.total ?: 0} 题",
        onBack = onBack
    ) { padding ->
        if (banks.isEmpty()) {
            EmptyState(
                padding = padding,
                importing = importing,
                onImport = {
                    importLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
                },
                onOpenTemplate = onOpenTemplate,
                // 🔴 空题库时整页走这个早返回分支，AI 入口原本只在下面的 LazyColumn 里，
                // 所以「一个题库都没有」的新用户**根本看不到 AI 配置**（2026-10 用户报）。
                // 空状态也得给入口 —— 配 Key 是跟题库无关的独立设置。
                onOpenAiSettings = onOpenAiSettings
            )
            return@GlassPageScaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 24.dp,
                start = 16.dp,
                end = 16.dp
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // 🔴 AI 入口放在**第一个 item**，不是列表末尾。
            //
            // 原先它排在「题库列表」+「按章节练习」之后，题库一多就得滑过好几屏才看得到，
            // 形同隐藏（2026-10 用户报「没有 AI 配置界面」）。现在提到顶部常驻，
            // 一进复习页就能看见，且明确带上「已配置 / 未配置」状态。
            item { AiEntryRow(context = context, onOpenSettings = onOpenAiSettings) }

            item { PracticeEntryCard(banks = banks, onStartPractice = onStartPractice) }

            // 提到局部 val：LazyColumn 的 item{} 是延迟求值的 lambda，
            // 在里面写 overview!! 万一将来 overview 被重置回 null 就会 NPE。
            val ov = overview
            if (ov != null && (ov.wrongPending > 0 || ov.favorites > 0)) {
                item {
                    ReviewToolsRow(
                        wrongPending = ov.wrongPending,
                        favorites = ov.favorites,
                        onWrongBook = { onStartPractice(PracticeLaunch(bankId = null, onlyWrong = true)) },
                        onFavorites = { onStartPractice(PracticeLaunch(bankId = null, onlyFavorite = true)) }
                    )
                }
            }

            item {
                SectionTitle("题库", trailing = {
                    // 管理入口挨着导入：两者都是"对题库整体做点什么"。
                    SecondaryAction("管理", enabled = !importing, onClick = onOpenBankManage)
                    SecondaryAction("导入题库", enabled = !importing, onClick = {
                        importLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
                    })
                })
            }

            items(banks, key = { it.id }) { bank ->
                BankRow(
                    bank = bank,
                    onOpen = { onOpenBankDetail(bank.id) },
                    onDelete = {
                        // 首页行内删除**必须二次确认**：题库带着用户的答题记录与错题本，
                        // 误触即丢（和「删自定义课程不加确认」性质不同，那条已驳回的
                        // 只是课程）。这里走 pendingDelete 弹确认，不直接删。
                        pendingDeleteBank = bank
                    }
                )
            }
        }

        // 删除题库的二次确认。放在 scaffold 内容末尾，
        // SystemDialog 会自己找 DialogHost 渲染成玻璃弹窗。
        pendingDeleteBank?.let { bank ->
            SystemDialog(
                onDismissRequest = { pendingDeleteBank = null },
                title = {
                    Text(
                        text = "删除题库",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                },
                confirmButton = {
                    SystemPrimaryButton(
                        text = "删除",
                        onClick = {
                            pendingDeleteBank = null
                            scope.launch {
                                withContext(Dispatchers.IO) { db.deleteBank(bank.id) }
                                reloadKey++
                                GlassToaster.show("已删除「${bank.name}」")
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                dismissButton = {
                    SystemSecondaryButton(
                        text = "取消",
                        onClick = { pendingDeleteBank = null },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            ) {
                Text(
                    text = "将删除「${bank.name}」及其全部题目、答题记录、错题与收藏。此操作不可撤销。",
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun PracticeEntryCard(
    banks: List<ReviewDatabase.BankSummary>,
    onStartPractice: (PracticeLaunch) -> Unit
) {
    // 判空逻辑在纯函数里（resolvePracticeEntry），这里只按结果渲染。
    // 原来直接 banks.first().id：空列表时抛 NoSuchElementException，
    // 而按钮 enabled 硬编码 true，题库一空点「顺序练习」就闪退。
    val entry = remember(banks) { resolvePracticeEntry(banks.map { it.id }) }
    val ready = entry as? PracticeEntry.Ready
    Card {
        Text(
            text = "开始练习",
            fontSize = 16.sp,
            lineHeight = 22.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ConfirmButton("顺序练习", enabled = ready != null, onClick = {
                // ready 为 null 时按钮已禁用，这里再取一次也只是拿到 null，onStartPractice 不会被调。
                ready?.let { onStartPractice(PracticeLaunch(bankId = it.bankId, mode = ReviewMode.SEQUENTIAL)) }
            }, modifier = Modifier.weight(1f))
            SecondaryAction("随机练习", enabled = ready != null, onClick = {
                ready?.let { onStartPractice(PracticeLaunch(bankId = it.bankId, mode = ReviewMode.RANDOM)) }
            }, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = if (ready == null) {
                "还没有题库，先在下方导入一个 JSON 题库。"
            } else {
                "默认练习第一个题库，点题库行可切换到指定题库。"
            },
            fontSize = 12.sp,
            lineHeight = 17.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ReviewToolsRow(
    wrongPending: Int,
    favorites: Int,
    onWrongBook: () -> Unit,
    onFavorites: () -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StatCard("错题本", "$wrongPending", onWrongBook, Modifier.weight(1f))
        StatCard("收藏", "$favorites", onFavorites, Modifier.weight(1f))
    }
}

@Composable
private fun StatCard(label: String, value: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    // GlassStatChip 自身不可点，而这一行有「点进去刷错题/收藏」的语义，
    // 所以外层保留可点的 Card 容器，只把内部换成玻璃数字胶囊 ——
    // 不为了迁就组件而丢掉点击能力。
    Card(onClick = onClick, modifier = modifier) {
        GlassStatChip(value = value, label = label, valueColor = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun BankRow(
    bank: ReviewDatabase.BankSummary,
    onOpen: () -> Unit,
    onDelete: () -> Unit
) {
    // 点行 = **进题库详情页**，不是直接开练。
    // 之前是 Card(onClick = onPractice)，用户点题库名后直接跳进做题，
    // 体感就是「进不去题库」（2026-10 用户报）。
    Card(onClick = onOpen) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = bank.name,
                    fontSize = 15.sp,
                    lineHeight = 21.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = buildString {
                        append("${bank.questionCount} 题")
                        if (bank.pendingWrong > 0) append(" · 待复习 ${bank.pendingWrong}")
                    },
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            GlassCircleButton(
                onClick = onDelete,
                icon = Icons.Filled.Delete,
                contentDescription = "删除题库",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * AI 设置入口。
 *
 * 顶部常驻（不是列表末尾），并**显式标出配置状态** —— 入口看不见和「不知道配没配」
 * 是两回事，用户报「没有 AI 配置界面」时这两点都要解决。
 */
@Composable
private fun AiEntryRow(context: Context, onOpenSettings: () -> Unit) {
    // Key 存在 AndroidKeyStore 里，读它要碰 Keystore，不能在每次重组都同步读。
    var configured by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        // 走 AiKeyStore.hasApiKey，不要自己写 !apiKey(...).isNullOrBlank() ——
        // 后者在 apiKey 返回 null 时是个易错的空安全写法，而 hasApiKey 已是唯一口径。
        configured = withContext(Dispatchers.IO) { AiKeyStore.hasApiKey(context) }
    }
    Card(onClick = onOpenSettings) {
        Column {
            Text(
                text = "AI 答疑设置",
                fontSize = 15.sp,
                lineHeight = 21.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = if (configured) {
                    "已配置 DeepSeek API Key，答题时可让 AI 讲解题目。"
                } else {
                    "配置 DeepSeek API Key，答题时可让 AI 讲解题目。"
                },
                fontSize = 13.sp,
                lineHeight = 18.sp,
                color = if (configured) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
    }
}

@Composable
private fun EmptyState(
    padding: PaddingValues,
    importing: Boolean,
    onImport: () -> Unit,
    onOpenTemplate: () -> Unit,
    onOpenAiSettings: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(
                top = padding.calculateTopPadding() + 48.dp,
                start = 32.dp,
                end = 32.dp
            ),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "还没有题库",
            fontSize = 18.sp,
            lineHeight = 24.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "已内置 ${BundledBanks.SPECS.size} 个题库（${BundledBanks.TOTAL_QUESTION_COUNT} 题）无需导入即可练习。" +
                "也可导入自己的 JSON 题库，支持 {\"name\": \"题库名\", \"questions\": [...]} 或直接一个题目数组。",
            fontSize = 13.sp,
            lineHeight = 20.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(20.dp))
        if (importing) {
            GlassLoadingState(text = "正在导入…")
        } else {
            ConfirmButton("导入 JSON 题库", enabled = true, onClick = onImport)
        }
        Spacer(Modifier.height(10.dp))
        SecondaryAction("查看题库格式说明", onClick = onOpenTemplate, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(10.dp))
        // 空题库时也要能配 AI Key —— 它跟有没有题库无关。
        SecondaryAction("AI 答疑设置", onClick = onOpenAiSettings, modifier = Modifier.fillMaxWidth())
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

/** 通用玻璃卡片容器。 */
@Composable
internal fun Card(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(16.dp)
    val base = modifier
        .fillMaxWidth()
        .clip(shape)
        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.38f))
        .padding(14.dp)
    if (onClick == null) {
        Column(modifier = base, content = content)
    } else {
        Column(modifier = base.clickable(onClick = onClick), content = content)
    }
}
