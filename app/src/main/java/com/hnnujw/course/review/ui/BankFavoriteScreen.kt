package com.hnnujw.course.review.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hnnujw.course.review.PracticeLaunch
import com.hnnujw.course.review.ReviewDatabase
import com.hnnujw.course.review.ReviewQuestion
import com.hnnujw.course.ui.system.GlassLoadingState
import com.hnnujw.course.ui.system.GlassPageScaffold
import com.hnnujw.course.ui.system.GlassStatChip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 题库收藏页。
 *
 * 2026-10 用户要求「收藏的题…可以练习」。抽题侧（[ReviewDatabase.pickQuestions] 的
 * `onlyFavorite`）本来就有，缺的是**入口**与"我到底收藏了哪些"的可见性 ——
 * 与错题本（[BankWrongBookScreen]）同一套理由：先看清，再决定练。
 *
 * 刻意与错题本保持对称：汇总 → 开练按钮 → 列表。差别只在数据源：
 * 收藏没有掌握度、没有错次、没有到期时间，所以这里没有筛选与排序控件 ——
 * 摆一排只会有一两种取值的芯片，等于给用户出选择题。
 *
 * 🔴 根布局用 [LazyColumn]（理由同错题本）：`Column(verticalScroll)` 装不下几百条，
 * 而且一旦有人在 item 里再放滚动容器就会踩"无限高约束"那个崩。
 *
 * 列表**只读**：不提供"取消收藏"按钮。收藏的增删留在答题页（星星）——
 * 在列表里取消收藏后条目会当场消失，用户想再收藏回来就得回去找那道题，
 * 比"先取消、发现自己点错了"更麻烦。
 */
@Composable
fun BankFavoriteScreen(
    context: Context,
    bankId: String,
    onBack: () -> Unit,
    onStartPractice: (PracticeLaunch) -> Unit
) {
    val db = remember { ReviewDatabase.get(context) }
    var bankName by remember { mutableStateOf("") }
    var questions by remember { mutableStateOf<List<ReviewQuestion>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(bankId) {
        val result = withContext(Dispatchers.IO) {
            db.listFavorites(bankId) to db.listBanks().firstOrNull { it.id == bankId }?.name.orEmpty()
        }
        questions = result.first
        bankName = result.second
        loaded = true
    }

    // 章节数：让用户知道收藏是集中在某几章还是散在全库。
    val chapterCount = remember(questions) { questions.map { it.chapter }.toSet().size }

    GlassPageScaffold(
        title = "收藏",
        subtitle = if (bankName.isBlank()) null else bankName,
        onBack = onBack
    ) { padding ->
        if (!loaded) {
            GlassLoadingState(text = "正在读取收藏…")
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
                            value = "${questions.size}",
                            label = "收藏题数",
                            valueColor = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f)
                        )
                        GlassStatChip(
                            value = "$chapterCount",
                            label = "涉及章节",
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            item {
                ConfirmButton(
                    text = "练习收藏题（${questions.size}）",
                    enabled = questions.isNotEmpty(),
                    onClick = {
                        onStartPractice(
                            PracticeLaunch(
                                bankId = bankId,
                                onlyFavorite = true
                            )
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            if (questions.isEmpty()) {
                item {
                    Card {
                        Text(
                            text = "这个题库还没有收藏题。答题页题干右上角的星标就是收藏，" +
                                "收藏后可以在这里一次性重练。",
                            fontSize = 13.sp,
                            lineHeight = 19.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                return@LazyColumn
            }

            item {
                Text(
                    text = "共 ${questions.size} 题（最近收藏在前）",
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            items(questions, key = { "${it.bankId}::${it.id}" }) { question ->
                FavoriteRow(question)
            }
        }
    }
}

/** 一条收藏题。只展示，不提供编辑/取消收藏入口。 */
@Composable
private fun FavoriteRow(question: ReviewQuestion) {
    Card {
        Text(
            text = question.stem,
            fontSize = 15.sp,
            lineHeight = 22.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "${if (question.isMulti) "多选" else "单选"} · 正确答案 ${question.answer}",
            fontSize = 13.sp,
            lineHeight = 18.sp,
            color = MaterialTheme.colorScheme.primary
        )
        if (question.chapter.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = question.chapter,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
