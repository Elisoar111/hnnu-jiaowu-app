package com.hnnujw.course.review.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hnnujw.course.review.PracticeLaunch
import com.hnnujw.course.review.ReviewDatabase
import com.hnnujw.course.review.WrongSort
import com.hnnujw.course.review.WrongStatus
import com.hnnujw.course.review.WrongStatusFilter
import com.hnnujw.course.review.dueLabel
import com.hnnujw.course.review.filterWrongEntries
import com.hnnujw.course.review.sortWrongEntries
import com.hnnujw.course.review.summarizeWrongBook
import com.hnnujw.course.ui.screen.GlassFilterChip
import com.hnnujw.course.ui.system.GlassLoadingState
import com.hnnujw.course.ui.system.GlassPageScaffold
import com.hnnujw.course.ui.system.GlassStatChip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 题库错题本页。
 *
 * 2026-10 用户要求把错题本做成**独立入口 + 独立页面**（而不是"点一下就开始刷错题"）：
 * 用户需要先看清"我到底错了哪些、哪些还没掌握、哪些今天该复习"，再决定练哪一批。
 * 直接开练会把这份信息完全藏起来。
 *
 * 数据一次性全取（含已掌握），筛选与排序全走 [filterWrongEntries] / [sortWrongEntries]
 * 两个纯函数 —— 界面不自己判掌握状态，判据只有一处。
 *
 * 🔴 根布局用 [LazyColumn] 而不是 `Column(verticalScroll)`：后者装不下几百条错题，
 * 且一旦有人在 item 里再放一个滚动容器就会踩"无限高约束"那个崩（见 QuestionCard 的注释）。
 */
@Composable
fun BankWrongBookScreen(
    context: Context,
    bankId: String,
    onBack: () -> Unit,
    onStartPractice: (PracticeLaunch) -> Unit
) {
    val db = remember { ReviewDatabase.get(context) }
    var bankName by remember { mutableStateOf("") }
    var entries by remember { mutableStateOf<List<ReviewDatabase.WrongBookEntry>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf(WrongStatusFilter.Active) }
    var sort by remember { mutableStateOf(WrongSort.LastWrong) }
    // 只在载入时取一次的"现在"。放 state 而不是每次重组现取：否则同一次渲染里不同
    // item 的 dueLabel 可能跨过一秒边界，同一屏出现"3 天后"和"2 天后"两种说法。
    // 🔴 必须是 Long —— 墙钟毫秒远超 Int 范围，用 Int 会溢出成负数。
    var now by remember { mutableStateOf(0L) }

    LaunchedEffect(bankId) {
        val result = withContext(Dispatchers.IO) {
            Triple(
                db.listWrongBook(bankId),
                db.listBanks().firstOrNull { it.id == bankId }?.name.orEmpty(),
                System.currentTimeMillis()
            )
        }
        entries = result.first
        bankName = result.second
        now = result.third
        loaded = true
    }

    val summary = remember(entries, now) { summarizeWrongBook(entries, now) }
    val visible = remember(entries, filter, sort) {
        sortWrongEntries(filterWrongEntries(entries, filter), sort)
    }

    GlassPageScaffold(
        title = "错题本",
        subtitle = if (bankName.isBlank()) null else bankName,
        onBack = onBack
    ) { padding ->
        if (!loaded) {
            GlassLoadingState(text = "正在读取错题…")
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
            // —— ① 汇总
            item {
                Card {
                    Text(
                        text = bankName.ifBlank { "本题库" },
                        fontSize = 15.sp,
                        lineHeight = 21.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        GlassStatChip(
                            value = "${summary.total}",
                            label = "在册错题",
                            modifier = Modifier.weight(1f)
                        )
                        GlassStatChip(
                            value = "${summary.active}",
                            label = "待复习",
                            valueColor = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        GlassStatChip(
                            value = "${summary.due}",
                            label = "今日到期",
                            modifier = Modifier.weight(1f)
                        )
                        GlassStatChip(
                            value = "${summary.mastered}",
                            label = "已掌握",
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            // —— ② 开练（只针对未掌握的错题）
            item {
                ConfirmButton(
                    text = "练习待复习错题（${summary.active}）",
                    enabled = summary.active > 0,
                    onClick = {
                        onStartPractice(
                            PracticeLaunch(
                                bankId = bankId,
                                onlyWrong = true
                            )
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            if (entries.isEmpty()) {
                item {
                    Card {
                        Text(
                            text = "这个题库还没有错题。答错一道题后它会自动进入这里，" +
                                "按「复习中 → 未掌握 → 已掌握」三态推进。",
                            fontSize = 13.sp,
                            lineHeight = 19.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                return@LazyColumn
            }

            // —— ③ 筛选
            item {
                SectionLabel("筛选")
                Spacer(Modifier.height(8.dp))
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    WrongStatusFilter.entries.forEach { option ->
                        GlassFilterChip(
                            label = option.label,
                            selected = option == filter,
                            onClick = { filter = option }
                        )
                    }
                }
            }

            // —— ④ 排序
            item {
                SectionLabel("排序")
                Spacer(Modifier.height(8.dp))
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    WrongSort.entries.forEach { option ->
                        GlassFilterChip(
                            label = option.label,
                            selected = option == sort,
                            onClick = { sort = option }
                        )
                    }
                }
            }

            item {
                SectionLabel("共 ${visible.size} 题")
            }

            if (visible.isEmpty()) {
                item {
                    Card {
                        Text(
                            text = "当前筛选下没有题目。换个筛选条件看看。",
                            fontSize = 13.sp,
                            lineHeight = 19.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                return@LazyColumn
            }

            items(visible, key = { "${it.question.bankId}::${it.question.id}" }) { entry ->
                WrongEntryRow(entry = entry, now = now)
            }
        }
    }
}

/** 一条错题。只展示，不提供编辑入口（编辑统一在题库管理页）。 */
@Composable
private fun WrongEntryRow(entry: ReviewDatabase.WrongBookEntry, now: Long) {
    val question = entry.question
    Card {
        Text(
            text = question.stem,
            fontSize = 15.sp,
            lineHeight = 22.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            StatusTag(entry.status)
            Text(
                text = "错 ${entry.wrongCount} 次 · ${dueLabel(entry.dueAt, now)}",
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (question.chapter.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = question.chapter,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = "正确答案 ${question.answer}",
            fontSize = 13.sp,
            lineHeight = 18.sp,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

/** 掌握状态的小标签。已掌握用主色、未掌握用错误色、复习中用弱化色。 */
@Composable
private fun StatusTag(status: WrongStatus) {
    val scheme = MaterialTheme.colorScheme
    val color = when (status) {
        WrongStatus.Mastered -> scheme.primary
        WrongStatus.NotMastered -> scheme.error
        WrongStatus.Reviewing -> scheme.onSurfaceVariant
    }
    Text(
        text = status.label,
        fontSize = 12.sp,
        lineHeight = 17.sp,
        fontWeight = FontWeight.SemiBold,
        color = color
    )
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp)
    )
}
