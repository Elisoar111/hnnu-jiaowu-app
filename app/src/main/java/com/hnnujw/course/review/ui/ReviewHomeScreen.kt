package com.hnnujw.course.review.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.hnnujw.course.review.AiConfigStore
import com.hnnujw.course.review.BundledBanks
import com.hnnujw.course.review.ReviewDatabase
import com.hnnujw.course.ui.system.GlassCircleButton
import com.hnnujw.course.ui.system.GlassToaster
import com.hnnujw.course.ui.system.GlassPageScaffold
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * 题库练习主屏：**我的题库**。
 *
 * 2026-10 用户要求「将题库仓库做成页面，将题库管理功能移到题库仓库里」。此前本页是
 * 「我的题库 + 题库仓库」两段式，仓库只是列表里的一段；现在仓库独立成页
 * （[BankLibraryScreen]），**取出 / 导入 / 创建 / 合并 / 改名 / 删除**全部搬了过去。
 *
 * 于是本页只剩一件事：**我取出来的题库，按什么顺序练**。
 *
 *  - 题库行的按钮收成三个：上移 / 下移 / 移出。删除移走了 —— 「移出」只是改显示状态、
 *    数据全留着，而「删除」不可撤销，两者**不该并排放在一行**（用户很容易点错）。
 *  - 排序两种操作都支持：长按整行拖动，或点箭头按钮。箭头按钮不是多余的 ——
 *    无障碍模式下拖拽手势不可用，且拖动一格的精度不如点一下。
 *  - 「移出」把题库放回仓库，题目、答题记录、错题本、收藏**一概不删**，随时能取回来。
 *
 * 点题库行 → 进题库详情页（[BankDetailScreen]），在那里选章节与练习模式。
 */
@Composable
fun ReviewHomeScreen(
    context: Context,
    onBack: () -> Unit,
    onOpenBankDetail: (bankId: String) -> Unit,
    onOpenAiSettings: () -> Unit,
    /** 打开题库仓库页（取出 / 导入 / 创建 / 合并 / 改名 / 删除）。 */
    onOpenLibrary: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val db = remember { ReviewDatabase.get(context) }
    var banks by remember { mutableStateOf<List<ReviewDatabase.BankSummary>>(emptyList()) }
    var reloadKey by remember { mutableIntStateOf(0) }
    // 拖拽排序：正在拖的题库 id + 当前纵向位移（px）。都不落库，松手才写。
    var draggingId by remember { mutableStateOf<String?>(null) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    // 行高固定，拖拽位移换算成"跨过几行"才有一个确定的除数。
    val rowHeightPx = with(LocalDensity.current) { MyBankRowHeight.toPx() }

    // 每次 reloadKey 变化就重读一次库状态。数据量小（十几行），直接全量读。
    LaunchedEffect(reloadKey) {
        // 首次进入时播种演示题库（幂等：已播种只读一次偏好就返回）。
        // ⚠️ 必须**在读取列表之前**做完 —— 放在后面的话首屏看不到它，得等下一次 reload。
        if (reloadKey == 0) BundledBanks.ensureDemoBank(context, db)
        banks = withContext(Dispatchers.IO) { db.listBanks() }
    }

    val mine = banks.filter { it.selected }
    // 仓库数量 = 还没导入的内置库 + 已导入但被移出的库。首页只报个数，明细在仓库页。
    val pendingSpecs = BundledBanks.pendingSpecs(banks.map { it.name }.toSet())
    val shelvedCount = banks.count { !it.selected }
    val warehouseCount = pendingSpecs.size + shelvedCount

    GlassPageScaffold(
        title = "题库练习",
        subtitle = "我的题库 ${mine.size} 个 · 仓库 $warehouseCount 个",
        onBack = onBack
    ) { padding ->
        LazyColumn(
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
            // AI 入口放在**第一个 item**，不是列表末尾。原先排在题库列表之后，
            // 题库一多就得滑过好几屏才看得到，形同隐藏（2026-10 用户报「没有 AI 配置界面」）。
            item { AiEntryRow(context = context, onOpenSettings = onOpenAiSettings) }

            item {
                SectionTitle("我的题库", trailing = {
                    // 仓库入口挨着列表标题：它是"我的题库从哪来"的唯一入口，必须在首屏可见。
                    SecondaryAction("题库仓库", enabled = true, onClick = onOpenLibrary)
                })
            }

            if (mine.isEmpty()) {
                item {
                    EmptyHint(
                        "还没有取出任何题库。点右上角「题库仓库」进去，从里面取一个出来就能开始练习。"
                    )
                }
            } else {
                item {
                    Text(
                        text = "长按拖动或用右侧箭头调整顺序；顺序会保存。",
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                items(mine, key = { "mine:${it.id}" }) { bank ->
                    val position = mine.indexOfFirst { it.id == bank.id }
                    MyBankRow(
                        bank = bank,
                        position = position,
                        total = mine.size,
                        dragging = draggingId == bank.id,
                        dragOffsetY = if (draggingId == bank.id) dragOffsetY else 0f,
                        onOpen = { onOpenBankDetail(bank.id) },
                        onMove = { delta ->
                            scope.launch {
                                withContext(Dispatchers.IO) { db.moveBank(bank.id, delta) }
                                reloadKey++
                            }
                        },
                        onShelf = {
                            scope.launch {
                                withContext(Dispatchers.IO) { db.setBankSelected(bank.id, false) }
                                reloadKey++
                                GlassToaster.show("已把「${bank.name}」移回仓库，题目与记录都还在")
                            }
                        },
                        onDragStart = { draggingId = bank.id; dragOffsetY = 0f },
                        onDrag = { dragOffsetY += it },
                        onDragEnd = {
                            val from = mine.indexOfFirst { it.id == bank.id }
                            val steps = (dragOffsetY / rowHeightPx).roundToInt()
                            val to = (from + steps).coerceIn(0, mine.lastIndex)
                            draggingId = null
                            dragOffsetY = 0f
                            if (from >= 0 && to != from) {
                                val ordered = mine.map { it.id }.toMutableList()
                                ordered.removeAt(from)
                                ordered.add(to, bank.id)
                                scope.launch {
                                    withContext(Dispatchers.IO) { db.reorderSelectedBanks(ordered) }
                                    reloadKey++
                                }
                            }
                        },
                        onDragCancel = { draggingId = null; dragOffsetY = 0f }
                    )
                }
            }
        }
    }
}

/** 「我的题库」行高固定：拖拽位移要换算成"跨过几行"，除数必须确定。 */
private val MyBankRowHeight = 84.dp

/**
 * 「我的题库」里的一行：点名字进详情；右侧三个按钮分别是上移 / 下移 / 移出到仓库。
 *
 * 排序两种操作都支持（2026-10 用户要求）：长按整行拖动，或点箭头按钮。
 * 箭头按钮不是多余的 —— 无障碍模式下拖拽手势不可用，且拖动一格的精度不如点一下。
 *
 * 🔴 这里**没有**删除按钮（2026-10 用户要求把题库管理移进仓库页）。删除不可撤销，
 * 与「移出」（只改显示状态）并排放在一行极易点错。
 */
@Composable
private fun MyBankRow(
    bank: ReviewDatabase.BankSummary,
    position: Int,
    total: Int,
    dragging: Boolean,
    dragOffsetY: Float,
    onOpen: () -> Unit,
    onMove: (Int) -> Unit,
    onShelf: () -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit
) {
    Card(
        // 按行号错峰入场（0..3，超出由 ModuleMotion 收敛到最后一组）。
        entranceGroup = position,
        modifier = Modifier
            .fillMaxWidth()
            .height(MyBankRowHeight)
            // 拖动中的行浮在其它行之上，否则位移后会被后面的行盖住。
            .zIndex(if (dragging) 1f else 0f)
            .graphicsLayer {
                if (dragging) {
                    translationY = dragOffsetY
                    scaleX = 1.02f
                    scaleY = 1.02f
                }
            }
            .pointerInput(bank.id, total) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { onDragStart() },
                    onDrag = { change, amount ->
                        change.consume()
                        onDrag(amount.y)
                    },
                    onDragEnd = { onDragEnd() },
                    onDragCancel = { onDragCancel() }
                )
            }
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.DragHandle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(6.dp))
            // 点名字区域进详情页（不用整行 clickable：整行可点会和长按拖动抢手势）。
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onOpen)
                    .padding(vertical = 6.dp, horizontal = 2.dp)
            ) {
                Text(
                    text = bank.name,
                    fontSize = 15.sp,
                    lineHeight = 21.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = buildString {
                        append("${bank.questionCount} 题")
                        if (bank.pendingWrong > 0) append(" · 待复习 ${bank.pendingWrong}")
                    },
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(6.dp))
            GlassCircleButton(
                onClick = { onMove(-1) },
                icon = Icons.Filled.KeyboardArrowUp,
                contentDescription = "上移",
                enabled = position > 0,
                size = 34.dp,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(4.dp))
            GlassCircleButton(
                onClick = { onMove(1) },
                icon = Icons.Filled.KeyboardArrowDown,
                contentDescription = "下移",
                enabled = position < total - 1,
                size = 34.dp,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(4.dp))
            GlassCircleButton(
                onClick = onShelf,
                icon = Icons.Filled.RemoveCircleOutline,
                contentDescription = "移出到题库仓库",
                size = 34.dp,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
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
        // 🔴 判据走 AiConfigStore.isConfigured（Key + 模型 + 端点三者齐全），
        // 不要自己写 "Key 非空就算配好"。
        // runCatching：Keystore 在真机上可能抛异常（密钥随卸载失效），失败按未配置处理。
        configured = withContext(Dispatchers.IO) {
            runCatching { AiConfigStore.isConfigured(context) }.getOrDefault(false)
        }
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
                    "已配置，答题时可让 AI 讲解题目。"
                } else {
                    "配置 DeepSeek 的 API Key，答题时可让 AI 讲解题目。"
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

/**
 * 通用玻璃卡片容器。
 *
 * @param entranceGroup 入场错峰组号。复习模块的页面级时间轴（见 [rememberReviewEntrance]）
 *   会按它让同屏卡片依次「淡入上移」；未提供进度源时无副作用。
 */
@Composable
internal fun Card(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    entranceGroup: Int = 0,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(16.dp)
    val base = modifier
        .fillMaxWidth()
        .reviewCardEntrance(entranceGroup)
        .clip(shape)
        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.38f))
        .padding(14.dp)
    if (onClick == null) {
        Column(modifier = base, content = content)
    } else {
        Column(modifier = base.clickable(onClick = onClick), content = content)
    }
}
