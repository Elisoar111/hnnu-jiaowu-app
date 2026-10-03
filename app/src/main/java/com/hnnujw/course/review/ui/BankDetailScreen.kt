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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hnnujw.course.review.ReviewDatabase
import com.hnnujw.course.review.ReviewMode
import com.hnnujw.course.review.accuracyPercent
import com.hnnujw.course.review.chapterOptions
import com.hnnujw.course.review.modeAt
import com.hnnujw.course.review.modeIndex
import com.hnnujw.course.review.resolveSelectedChapter
import com.hnnujw.course.ui.screen.GlassFilterChip
import com.hnnujw.course.ui.system.GlassLoadingState
import com.hnnujw.course.ui.system.GlassPageScaffold
import com.hnnujw.course.ui.system.GlassStatChip
import com.hnnujw.course.ui.system.LiquidSegmentedControl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 题库详情页。
 *
 * 这是「点题库名」的落点（之前直接进顺序练习，用户以为进不去题库）。
 * 页面内聚三件事：看这个库的概况、选练习模式与章节、走错题本/统计入口。
 *
 * 判空与映射全走 [com.hnnujw.course.review] 里的纯函数，Composable 只按结果渲染。
 */
@Composable
fun BankDetailScreen(
    context: Context,
    bankId: String,
    onBack: () -> Unit,
    onStartPractice: (bankId: String, chapter: String?, mode: ReviewMode, onlyWrong: Boolean, onlyFavorite: Boolean) -> Unit,
    onOpenStats: () -> Unit
) {
    val db = remember { ReviewDatabase.get(context) }
    var bankName by remember { mutableStateOf("") }
    var overview by remember { mutableStateOf<ReviewDatabase.Overview?>(null) }
    var chapters by remember { mutableStateOf<List<Pair<String, Int>>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }

    // 模式与章节下标是界面状态；真正的 chapter / mode 值由纯函数在渲染时解析。
    var modeIdx by remember { mutableIntStateOf(0) }
    var chapterIdx by remember { mutableIntStateOf(0) }

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
            // —— 概况
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
                        value = "${ov.answered}",
                        label = "已答",
                        modifier = Modifier.weight(1f)
                    )
                    GlassStatChip(
                        value = "${accuracyPercent(ov.correct, ov.answered)}%",
                        label = "正确率",
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

            // —— 练习模式
            Card {
                SectionHeader("练习模式")
                Spacer(Modifier.height(10.dp))
                LiquidSegmentedControl(
                    options = ReviewMode.entries.map { it.label },
                    selectedIndex = modeIndex(modeAt(modeIdx)),
                    onSelect = { modeIdx = it },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "顺序按题库原始顺序；随机打乱；错题优先把错题排在前面。",
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // —— 章节
            Card {
                SectionHeader("练习章节")
                Spacer(Modifier.height(10.dp))
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
                }
            }

            // —— 入口
            ConfirmButton(
                text = "开始练习",
                enabled = ov.total > 0,
                onClick = {
                    onStartPractice(
                        bankId,
                        resolveSelectedChapter(options, safeChapterIdx),
                        modeAt(modeIdx),
                        false,
                        false
                    )
                },
                modifier = Modifier.fillMaxWidth()
            )
            // 只有真有未掌握错题时才显示「错题本」入口，避免空入口点进去是空列表。
            if (ov.wrongPending > 0) {
                SecondaryAction(
                    text = "错题本（${ov.wrongPending}）",
                    onClick = {
                        onStartPractice(bankId, null, modeAt(modeIdx), true, false)
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            SecondaryAction(
                text = "统计",
                onClick = onOpenStats,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(16.dp))
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
