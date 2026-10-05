package com.hnnujw.course.review.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hnnujw.course.review.AnswerSheetCell
import com.hnnujw.course.review.PracticeSummary
import com.hnnujw.course.ui.system.GlassPageScaffold
import com.hnnujw.course.ui.system.GlassStatChip

/**
 * 提交后的**总结页**。
 *
 * 2026-10 用户要求：顺序 / 乱序 / 考试答完最后一题后「完成」改成「提交」，并进一个
 * 总结页面，页面上要有**本次答题的数据**和一句**鼓励语**。
 *
 * 结构固定为「一句结论 → 数字 → 逐题明细 → 出口」：
 *  1. 标题由 [PracticeSummary.title] 给（练习完成 / 考试结束 / 考试时间到）；
 *  2. **鼓励语**紧跟标题 —— 它是用户第一眼想看到的东西，不该埋在数字下面；
 *  3. 正确率大号 + 一行汇总（共 N 题 · 答对 X · 答错 Y · 未作答 Z）；
 *  4. 「本次数据」胶囊：总题数 / 答对 / 答错 / 未作答 / 用时（限时再加一行考试时长）；
 *  5. 「答题明细」把答题卡的格子原样搬过来（**只读**），用户能一眼看到错的是哪几道；
 *  6. 出口只留一个「返回题库」—— 成绩在判分那一刻已逐题落库，这里不需要再做别的。
 *
 * 数字全部取自 [PracticeSummary]（纯逻辑、已单测），本函数只负责排版。
 * 从 PracticeScreen.kt 拆出来，排版原样保留。
 */
@Composable
internal fun SummaryPane(
    summary: PracticeSummary,
    cells: List<AnswerSheetCell>,
    onDone: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    GlassPageScaffold(title = summary.title) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    top = padding.calculateTopPadding() + 8.dp,
                    // 🔴 底部必须吃掉 scaffold 的 bottom inset，否则末位内容会被系统
                    // 导航条压住（与题库详情页同一个坑，2026-10 用户报过两次）。
                    bottom = padding.calculateBottomPadding() + 24.dp,
                    start = 16.dp,
                    end = 16.dp
                )
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // —— ① 鼓励语 + 正确率
            Card {
                Text(
                    text = summary.encouragement,
                    fontSize = 15.sp,
                    lineHeight = 23.sp,
                    fontWeight = FontWeight.Medium,
                    color = scheme.onSurface
                )
                Spacer(Modifier.height(14.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${summary.accuracyPercent}%",
                        fontSize = 40.sp,
                        lineHeight = 46.sp,
                        fontWeight = FontWeight.Bold,
                        color = scheme.primary
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = summary.tallyLabel,
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // —— ② 本次数据
            Card(entranceGroup = 1) {
                Text(
                    text = "本次数据",
                    fontSize = 15.sp,
                    lineHeight = 21.sp,
                    fontWeight = FontWeight.Medium,
                    color = scheme.onSurface
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GlassStatChip(
                        value = "${summary.total}",
                        label = "总题数",
                        modifier = Modifier.weight(1f)
                    )
                    GlassStatChip(
                        value = "${summary.correct}",
                        label = "答对",
                        valueColor = scheme.primary,
                        modifier = Modifier.weight(1f)
                    )
                    GlassStatChip(
                        value = "${summary.wrong}",
                        label = "答错",
                        valueColor = if (summary.wrong > 0) scheme.error else scheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GlassStatChip(
                        value = "${summary.unjudged}",
                        label = "未作答",
                        modifier = Modifier.weight(1f)
                    )
                    GlassStatChip(
                        value = summary.elapsedLabel,
                        label = "用时",
                        modifier = Modifier.weight(1f)
                    )
                    if (summary.timed) {
                        // 限时才有意义的一格。常显的话，普通练习会多出一个
                        // 「考试时长 0 分钟」，纯噪声。
                        GlassStatChip(
                            value = "${summary.durationMinutes}",
                            label = if (summary.timedOut) "考试时长 · 超时" else "考试时长（分）",
                            modifier = Modifier.weight(1f)
                        )
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }

            // —— ③ 答题明细（只读，格子与答题卡同一套配色）
            Card(entranceGroup = 2) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "答题明细",
                        fontSize = 15.sp,
                        lineHeight = 21.sp,
                        fontWeight = FontWeight.Medium,
                        color = scheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    AnswerSheetLegend()
                }
                Spacer(Modifier.height(12.dp))
                // FlowRow 而不是懒加载网格：这里在 verticalScroll 里，任何同轴滚动容器
                // 都会拿到 maxHeight = Infinity 并在测量阶段抛异常（项目红线）。
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    cells.forEach { cell ->
                        AnswerSheetCellView(cell = cell, onClick = null)
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            ConfirmButton(
                text = "返回题库",
                enabled = true,
                onClick = onDone,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
