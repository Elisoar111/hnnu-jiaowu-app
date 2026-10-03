package com.hnnujw.course.review.ui

import android.content.Context
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hnnujw.course.review.ExamDurationOptions
import com.hnnujw.course.review.PracticeLaunch
import com.hnnujw.course.review.ReviewDatabase
import com.hnnujw.course.review.ReviewMode
import com.hnnujw.course.review.accuracyPercent
import com.hnnujw.course.review.chapterOptions
import com.hnnujw.course.review.resolveSelectedChapter
import com.hnnujw.course.ui.screen.GlassFilterChip
import com.hnnujw.course.ui.system.GlassLoadingState
import com.hnnujw.course.ui.system.GlassPageScaffold
import com.hnnujw.course.ui.system.GlassStatChip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 题库详情页 —— 本题的**开练中枢**。
 *
 * 这是「点题库名」的落点。页面信息层级（2026-10 重构，对标大入口卡 + 数量徽标模式）：
 * 概况卡 → 「开始练习」主按钮 → 入口网格（顺序/随机/错题/收藏/背题/斩题/今日待复习/统计）
 * → 设置区（章节/时长）→ 管理入口。
 *
 * 🔴 项目红线写死在这里：**入口常显，不按状态条件隐藏**。
 * 错题本（0）/ 斩题本（0）/ 今日待复习（0）也要渲染出来、只是禁用 ——
 * 数量为 0 就整个入口消失的话，用户会以为功能根本不存在（真机报「没有错题本」）。
 *
 * 判空与映射全走 [com.hnnujw.course.review] 里的纯函数，Composable 只按结果渲染。
 */
@Composable
fun BankDetailScreen(
    context: Context,
    bankId: String,
    onBack: () -> Unit,
    onStartPractice: (PracticeLaunch) -> Unit,
    onOpenStats: () -> Unit,
    /** 逐题编辑（管理入口）。 */
    onEditQuestions: () -> Unit
) {
    val db = remember { ReviewDatabase.get(context) }
    var bankName by remember { mutableStateOf("") }
    var overview by remember { mutableStateOf<ReviewDatabase.Overview?>(null) }
    var chapters by remember { mutableStateOf<List<Pair<String, Int>>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }

    // 章节下标与时长档下标是界面状态；真正的 chapter / 秒数由纯函数在渲染时解析。
    var chapterIdx by remember { mutableIntStateOf(0) }
    // 章节区默认折叠（2026-10 用户要求）：章节多（近现代史 11 章）时整排 chip 会把
    // 主按钮挤出首屏。折叠态只露「当前选中的章节 + 展开全部」，展开后才给完整横滑列表。
    var chaptersExpanded by remember { mutableStateOf(false) }
    // 限时考试的时长档位下标（0=不限时，其余按 ExamDurationOptions 取）。
    var durationIdx by remember { mutableIntStateOf(0) }
    var slashedCount by remember { mutableIntStateOf(0) }
    var dueCount by remember { mutableIntStateOf(0) }

    LaunchedEffect(bankId) {
        val result = withContext(Dispatchers.IO) {
            Triple(
                db.overview(bankId),
                db.chaptersWithCount(bankId),
                db.listBanks().firstOrNull { it.id == bankId }?.name.orEmpty()
            )
        }
        overview = result.first
        chapters = result.second
        bankName = result.third
        slashedCount = withContext(Dispatchers.IO) { db.slashedCount(bankId) }
        dueCount = withContext(Dispatchers.IO) { db.dueWrongCount(bankId) }
        loaded = true
    }

    GlassPageScaffold(
        title = bankName.ifBlank { "题库" },
        subtitle = overview?.let { "${it.total} 题" },
        onBack = onBack
    ) { padding ->
        if (!loaded) {
            GlassLoadingState(text = "正在读取题库…")
            return@GlassPageScaffold
        }
        val ov = overview ?: return@GlassPageScaffold
        val options = remember(chapters) { chapterOptions(chapters) }
        // 章节选项可能因重算而收缩，把下标夹回合法范围，避免高亮落在不存在的项上。
        val safeChapterIdx = chapterIdx.coerceIn(0, (options.size - 1).coerceAtLeast(0))
        val safeDurationIdx = durationIdx.coerceIn(0, ExamDurationOptions.lastIndex)

        // 所有开练入口共用的一组参数：当前章节 + 当前时长档。
        fun launch(
            mode: ReviewMode = ReviewMode.SEQUENTIAL,
            memorize: Boolean = false,
            onlyWrong: Boolean = false,
            dueOnly: Boolean = false,
            onlyFavorite: Boolean = false,
            onlySlashed: Boolean = false
        ) = onStartPractice(
            PracticeLaunch(
                bankId = bankId,
                chapter = resolveSelectedChapter(options, safeChapterIdx),
                mode = mode,
                onlyWrong = onlyWrong,
                dueOnly = dueOnly,
                onlyFavorite = onlyFavorite,
                onlySlashed = onlySlashed,
                memorize = memorize,
                durationSeconds = ExamDurationOptions[safeDurationIdx].seconds
            )
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    top = padding.calculateTopPadding() + 8.dp,
                    start = 16.dp,
                    end = 16.dp
                )
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // —— ① 概况：题库名、总题数、章节数、已练正确率
            Card {
                Text(
                    text = bankName.ifBlank { "题库" },
                    fontSize = 17.sp,
                    lineHeight = 23.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GlassStatChip(
                        value = "${ov.total}",
                        label = "总题数",
                        modifier = Modifier.weight(1f)
                    )
                    GlassStatChip(
                        value = "${chapters.size}",
                        label = "章节数",
                        modifier = Modifier.weight(1f)
                    )
                    GlassStatChip(
                        value = "${accuracyPercent(ov.correct, ov.answered)}%",
                        label = "已练正确率",
                        valueColor = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GlassStatChip(
                        value = "${ov.wrongPending}",
                        label = "待复习错题",
                        modifier = Modifier.weight(1f)
                    )
                    GlassStatChip(
                        value = "${ov.favorites}",
                        label = "收藏",
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // —— ② 主按钮：按当前章节/时长档直接开练（顺序模式）。
            ConfirmButton(
                text = "开始练习",
                enabled = ov.total > 0,
                onClick = { launch() },
                modifier = Modifier.fillMaxWidth()
            )

            // —— ③ 入口网格：两行两列排布，**全部常显**，数量为 0 的禁用不隐藏。
            EntryGridRow {
                EntryCell("顺序练习", enabled = ov.total > 0, onClick = { launch(ReviewMode.SEQUENTIAL) }, modifier = Modifier.weight(1f))
                EntryCell("随机练习", enabled = ov.total > 0, onClick = { launch(ReviewMode.RANDOM) }, modifier = Modifier.weight(1f))
            }
            EntryGridRow {
                EntryCell("错题本（${ov.wrongPending}）", enabled = ov.wrongPending > 0, onClick = { launch(onlyWrong = true) }, modifier = Modifier.weight(1f))
                EntryCell("收藏（${ov.favorites}）", enabled = ov.favorites > 0, onClick = { launch(onlyFavorite = true) }, modifier = Modifier.weight(1f))
            }
            EntryGridRow {
                // 背题模式：直接看答案，不计入统计（不判对错、不进错题本、不改掌握度）。
                EntryCell("背题模式", enabled = ov.total > 0, onClick = { launch(memorize = true) }, modifier = Modifier.weight(1f))
                EntryCell("斩题本（$slashedCount）", enabled = slashedCount > 0, onClick = { launch(onlySlashed = true) }, modifier = Modifier.weight(1f))
            }
            EntryGridRow {
                // 今日待复习：间隔复习的口径（见 ReviewScheduling），0 时常显禁用。
                EntryCell("今日待复习（$dueCount）", enabled = dueCount > 0, onClick = { launch(onlyWrong = true, dueOnly = true) }, modifier = Modifier.weight(1f))
                EntryCell("统计", enabled = true, onClick = onOpenStats, modifier = Modifier.weight(1f))
            }

            // —— ④ 设置区：章节筛选 + 限时考试时长档（作用于主按钮与顺序/随机入口）。
            Card {
                SectionHeader("练习章节")
                Spacer(Modifier.height(10.dp))
                // 折叠/展开双态：章节 ≤ 3 个时没必要折叠，直接全展示。
                if (!chaptersExpanded && options.size > 3) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val current = options.getOrNull(safeChapterIdx)
                        if (current != null) {
                            GlassFilterChip(
                                label = "${current.label}（${current.count}）",
                                selected = true,
                                onClick = { chaptersExpanded = true }
                            )
                        }
                        GlassFilterChip(
                            label = "展开全部（${options.size}）▾",
                            selected = false,
                            onClick = { chaptersExpanded = true }
                        )
                    }
                } else {
                    // 章节多（近现代史 11 章）时横向滚动，不把页面撑爆。
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        options.forEachIndexed { index, option ->
                            GlassFilterChip(
                                label = "${option.label}（${option.count}）",
                                selected = index == safeChapterIdx,
                                onClick = { chapterIdx = index }
                            )
                        }
                        if (options.size > 3) {
                            GlassFilterChip(
                                label = "收起 ▴",
                                selected = false,
                                onClick = { chaptersExpanded = false }
                            )
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
                SectionHeader("限时考试")
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ExamDurationOptions.forEachIndexed { index, option ->
                        GlassFilterChip(
                            label = option.label,
                            selected = index == safeDurationIdx,
                            onClick = { durationIdx = index }
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "选「不限时」就是普通练习；选 20 / 30 / 60 分钟即为限时考试，" +
                        "倒计时到点自动交卷（未作答的题计为未作答，不判错）。",
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // —— ⑤ 管理入口（次级操作，压底）。
            SecondaryAction(
                text = "管理题目（逐题编辑）",
                onClick = onEditQuestions,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun EntryGridRow(content: @Composable () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { content() }
}

/**
 * 入口网格单元格：玻璃 Card + 文案，**禁用态降透明度且不响应点击**。
 *
 * 不用「if (count > 0) 才渲染」—— 那是本页 2026-10 修掉的红线问题
 * （数量为 0 入口就消失，用户以为功能不存在）。这里永远渲染，只切可用性。
 */
@Composable
private fun EntryCell(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        onClick = if (enabled) onClick else null,
        modifier = modifier.alpha(if (enabled) 1f else 0.45f)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                fontSize = 14.sp,
                lineHeight = 19.sp,
                fontWeight = FontWeight.Medium,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        fontSize = 15.sp,
        lineHeight = 21.sp,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSurface
    )
}
