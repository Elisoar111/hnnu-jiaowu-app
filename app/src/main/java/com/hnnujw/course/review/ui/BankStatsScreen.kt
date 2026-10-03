package com.hnnujw.course.review.ui

import android.content.Context
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hnnujw.course.review.ReviewDatabase
import com.hnnujw.course.review.accuracyPercent
import com.hnnujw.course.ui.system.GlassLoadingState
import com.hnnujw.course.ui.system.GlassPageScaffold
import com.hnnujw.course.ui.system.GlassProgressBar
import com.hnnujw.course.ui.system.GlassStatChip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 题库统计页。
 *
 * 上半部分是**该题库**的口径（走 `overview(bankId)`）；
 * 下半部分的「每日答题量」是**全库**口径 —— `daily_stats` 表只有 day 维度，
 * 没有 bank_id，`dailyStats()` 传不进 bankId。所以文案必须明写「全部题库」，
 * 不能把它谎报成该题库的曲线（那是在骗用户）。
 */
@Composable
fun BankStatsScreen(
    context: Context,
    bankId: String,
    onBack: () -> Unit
) {
    val db = remember { ReviewDatabase.get(context) }
    var overview by remember { mutableStateOf<ReviewDatabase.Overview?>(null) }
    var daily by remember { mutableStateOf<List<Pair<String, Pair<Int, Int>>>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(bankId) {
        val result = withContext(Dispatchers.IO) {
            db.overview(bankId) to db.dailyStats(7)
        }
        overview = result.first
        daily = result.second
        loaded = true
    }

    GlassPageScaffold(title = "统计", onBack = onBack) { padding ->
        if (!loaded) {
            GlassLoadingState(text = "正在统计…")
            return@GlassPageScaffold
        }
        val ov = overview ?: return@GlassPageScaffold

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
            Card {
                Text(
                    text = "本库概览",
                    fontSize = 15.sp,
                    lineHeight = 21.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GlassStatChip(value = "${ov.total}", label = "总题数", modifier = Modifier.weight(1f))
                    GlassStatChip(value = "${ov.answered}", label = "已答", modifier = Modifier.weight(1f))
                    GlassStatChip(value = "${ov.correct}", label = "答对", modifier = Modifier.weight(1f))
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GlassStatChip(value = "${ov.wrongPending}", label = "待复习错题", modifier = Modifier.weight(1f))
                    GlassStatChip(value = "${ov.favorites}", label = "收藏", modifier = Modifier.weight(1f))
                }
            }

            Card {
                val accuracy = accuracyPercent(ov.correct, ov.answered)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "正确率",
                        fontSize = 15.sp,
                        lineHeight = 21.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "$accuracy%",
                        fontSize = 15.sp,
                        lineHeight = 21.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(Modifier.height(10.dp))
                GlassProgressBar(
                    progress = accuracy / 100f,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Card {
                Text(
                    text = "最近 7 天每日答题量",
                    fontSize = 15.sp,
                    lineHeight = 21.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(4.dp))
                // 🔴 口径声明：daily_stats 不按 bankId 过滤，这是全库统计，不能谎报成本库。
                Text(
                    text = "近 7 天（全部题库）",
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                if (daily.isEmpty()) {
                    Text(
                        text = "还没有答题记录。",
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        daily.forEach { (day, pair) ->
                            GlassStatChip(
                                value = "${pair.first}",
                                label = dayShort(day),
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

/**
 * `yyyy-MM-dd` → `MM-dd`。
 *
 * 7 个日期的横排里，年份是噪音（都是最近一周），只留月日最省位。
 * 解析失败时原样返回，不抛。
 */
private fun dayShort(day: String): String {
    val parts = day.split("-")
    return if (parts.size >= 3) "${parts[1]}-${parts[2]}" else day
}
