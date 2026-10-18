package com.hnnujw.course.review.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hnnujw.course.review.AnswerSheetCell
import com.hnnujw.course.review.answerSheetSections
import com.hnnujw.course.review.AnswerSheetSection
import com.hnnujw.course.review.AnswerSheetStatus
import com.hnnujw.course.review.answerSheetCell
import com.hnnujw.course.review.PracticeSession
import com.hnnujw.course.review.summarizeAnswerSheet
import com.hnnujw.course.ui.system.GlassPageScaffold
import com.hnnujw.course.ui.system.LiquidSwitch

/**
 * 答题卡相关 UI 的家：入口条、整页网格、格子视图、图例。
 *
 * 从 PracticeScreen.kt 拆出来 —— 那个文件此前同时装着练习页、总结页、答题卡页
 * 三套排版，改任何一个都要在千行文件里找位置。
 */

/**
 * 答题卡入口条：一行摘要（已答 N/M · 对 X · 错 Y），点一下打开答题卡页。
 *
 * 摘要**常驻**在题干上方：它是"我还有多少没答"的唯一提示，不该为了省一行而藏起来。
 *
 * 「自动下一题」开关并到了这一行行尾（2026-10 布局收敛）：它原来独占一行、还带两行
 * 说明，把题干顶下去一截。开关是练习过程中随时会动的偏好，放在"答题卡 / 进度"这条
 * 信息带上正合适 —— 打开时下方出现一行小字说明限制（多选不跳、跳过的题交卷时统一
 * 判分），关闭时不占地方。
 */
@Composable
internal fun AnswerSheetTrigger(
    session: PracticeSession,
    version: Int,
    autoNext: Boolean,
    onAutoNextChange: (Boolean) -> Unit,
    onOpen: () -> Unit
) {
    // 🔴 version 必须进 key：PracticeSession 内部是普通 Map，select/judge/unjudge
    // 改它不会触发重组，靠调用方自增的版本号驱动重算（与 QuestionCard 同一条机制）。
    val summary = remember(session, version) { summarizeAnswerSheet(answerSheetCells(session)) }

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 🔴 点击区域必须**物理分开**：此前整行挂了「打开答题卡」的 clickable，
            // 而 LiquidSwitch 内部不消费点击（只有拖拽与无障碍语义），点开关的
            // 手势会穿透到整行 —— 表现为"点自动下一题，答题卡页也跟着弹出来"。
            // 现在左半边（答题卡摘要）点击才打开答题卡；右半边（文字+开关）点击
            // 只切换开关。
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClick = onOpen),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "答题卡",
                    fontSize = 14.sp,
                    lineHeight = 19.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = buildString {
                        append("已答 ${summary.answered}/${summary.total}")
                        // 判分统计只在有已确认的题时显示 —— 一进练习页全是 0，纯噪声。
                        if (summary.judged > 0) {
                            append(" · 对 ${summary.correct} · 错 ${summary.wrong}")
                        }
                    },
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row(
                modifier = Modifier.clickable { onAutoNextChange(!autoNext) },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "自动下一题",
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(8.dp))
                LiquidSwitch(checked = autoNext, onCheckedChange = onAutoNextChange)
            }
        }
        if (autoNext) {
            Text(
                text = "多选题不会自动跳；跳过的题在提交时统一判分。",
                fontSize = 11.sp,
                lineHeight = 16.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }
    }
}

/**
 * 答题卡整页：题号网格 + 状态着色，点题号跳到那一题并自动关闭本页。
 *
 * 用 [LazyVerticalGrid] 是**安全**的 —— 这里没有外层滚动容器（`GlassSubpage` 给的是
 * 独立的一页），不会出现 `maxHeight = Infinity` 的同轴嵌套问题。页内版本只能退而求其次
 * 用非懒的 `FlowRow`，320 题的库要一次性测量全部格子。
 *
 * 2026-10 两处调整（用户要求）：
 *  - **底部加「提交」**：答题卡是"交卷前最后一眼"，用户在这里最容易想交卷；
 *    让他先关掉答题卡、再滑回练习页底部按提交，是多余的一步。背题模式不判分，
 *    位置改成一句说明而不是点了没反应的灰按钮。
 *  - **字号放大**：格子从 44dp / 12sp 提到 52dp / 17sp，并加了颜色图例。
 *    上一版的 12sp 题号在真机上被用户报「太小看不清」—— 答题卡的全部信息就是那个
 *    数字，它不该比正文还小。
 */
@Composable
internal fun AnswerSheetPage(
    session: PracticeSession,
    version: Int,
    currentIndex: Int,
    memorize: Boolean,
    onSubmit: () -> Unit,
    onJump: (Int) -> Unit
) {
    val cells = remember(session, version, currentIndex) { answerSheetCells(session, currentIndex) }
    val summary = remember(cells) { summarizeAnswerSheet(cells) }
    // 只有真的跨多个章节时才分组：整库单章时加一排标题是纯噪音。
    val sections = remember(session, cells) {
        answerSheetSections(cells = cells, chapters = session.questions.map { it.chapter })
    }

    GlassPageScaffold(
        title = "答题卡",
        subtitle = buildString {
            append("已答 ${summary.answered}/${summary.total}")
            if (summary.judged > 0) append(" · 对 ${summary.correct} · 错 ${summary.wrong}")
        },
        onBack = { onJump(currentIndex) },
        bottomBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    // 弹层里没有底部导航栏，但系统导航条还在 —— 不 pad 会被压住。
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                if (memorize) {
                    Text(
                        text = "背题模式只看答案、不计分，看完直接返回即可。",
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    ConfirmButton(
                        text = "提交",
                        enabled = true,
                        onClick = onSubmit,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    ) { padding ->
        LazyVerticalGrid(
            // 格子 52dp + 间距 10dp ⇒ 最小列宽 56dp 时每行放得下 5~6 个，与上一版的
            // 排布密度接近，但每个数字都大了一档。
            columns = GridCells.Adaptive(minSize = 56.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 16.dp,
                start = 16.dp,
                end = 16.dp
            ),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // 图例占满整行，跟着网格一起滚 —— 用 Column 包一层会让网格拿到
            // "剩余高度"，多一个会算错的地方，没必要。
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "点题号跳到那一题",
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    AnswerSheetLegend()
                }
            }
            if (sections.size <= 1) {
                items(cells, key = { it.index }) { cell ->
                    AnswerSheetCellView(cell = cell, onClick = { onJump(cell.index) })
                }
            } else {
                sections.forEach { section ->
                    item(span = { GridItemSpan(maxLineSpan) }, key = "chapter:${section.chapter}") {
                        AnswerSheetSectionHeader(section)
                    }
                    items(section.cells, key = { it.index }) { cell ->
                        AnswerSheetCellView(cell = cell, onClick = { onJump(cell.index) })
                    }
                }
            }
        }
    }
}

/** 把当前会话映射成答题卡格子。判分状态与当前题都要现算。 */
internal fun answerSheetCells(session: PracticeSession, currentIndex: Int = -1): List<AnswerSheetCell> =
    session.questions.mapIndexed { i, q ->
        answerSheetCell(
            index = i,
            answered = session.answerOf(q).isNotBlank(),
            judged = session.isJudged(q),
            correct = session.isCorrect(q),
            isCurrent = i == currentIndex
        )
    }

/**
 * 答题卡上的一个题号格子。当前题额外加一圈主色描边（纯色块之间看不出"我在哪"）。
 *
 * [onClick] 传 null = 只读（总结页的答题明细用它）。刻意**不**把只读做成"点了没反应"：
 * 只读格子不挂手势，滚动时也不会误触。
 */
@Composable
internal fun AnswerSheetCellView(cell: AnswerSheetCell, onClick: (() -> Unit)?) {
    val scheme = MaterialTheme.colorScheme
    val background = when (cell.status) {
        AnswerSheetStatus.Unanswered -> scheme.surface.copy(alpha = 0.45f)
        AnswerSheetStatus.Answered -> scheme.primaryContainer.copy(alpha = 0.45f)
        AnswerSheetStatus.Correct -> scheme.primaryContainer.copy(alpha = 0.8f)
        AnswerSheetStatus.Wrong -> scheme.errorContainer.copy(alpha = 0.8f)
    }
    val foreground = when (cell.status) {
        AnswerSheetStatus.Unanswered -> scheme.onSurfaceVariant
        AnswerSheetStatus.Answered -> scheme.onSurfaceVariant
        AnswerSheetStatus.Correct -> scheme.primary
        AnswerSheetStatus.Wrong -> scheme.error
    }
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = Modifier
            .size(52.dp)
            .clip(shape)
            .background(background)
            .then(if (cell.isCurrent) Modifier.border(2.dp, scheme.primary, shape) else Modifier)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "${cell.number}",
            fontSize = 17.sp,
            lineHeight = 22.sp,
            fontWeight = if (cell.isCurrent) FontWeight.Bold else FontWeight.Medium,
            color = foreground
        )
    }
}

/** 答题卡 / 总结页共用的颜色图例。没有它，光靠颜色用户得猜"这格是什么状态"。 */
@Composable
internal fun AnswerSheetLegend() {
    val scheme = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        LegendDot(color = scheme.surface.copy(alpha = 0.45f), label = "未答", textColor = scheme.onSurfaceVariant)
        LegendDot(color = scheme.primaryContainer.copy(alpha = 0.8f), label = "对", textColor = scheme.primary)
        LegendDot(color = scheme.errorContainer.copy(alpha = 0.8f), label = "错", textColor = scheme.error)
    }
}

@Composable
private fun LegendDot(color: Color, label: String, textColor: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(color)
        )
        Spacer(Modifier.width(4.dp))
        Text(text = label, fontSize = 11.sp, lineHeight = 15.sp, color = textColor)
    }
}

/**
 * 答题卡的章节分隔行。
 *
 * 只画一条淡分隔线 + 章名 + 该章题数：答题卡的主色是那些大号题号格子，
 * 分隔行必须弱到不抢视线，否则一屏里全是"条"。
 */
@Composable
private fun AnswerSheetSectionHeader(section: AnswerSheetSection) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = section.chapter.ifBlank { "未分章" },
            fontSize = 12.sp,
            lineHeight = 17.sp,
            fontWeight = FontWeight.SemiBold,
            color = scheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = "${section.cells.size} 题",
            fontSize = 12.sp,
            lineHeight = 17.sp,
            color = scheme.onSurfaceVariant
        )
    }
}
