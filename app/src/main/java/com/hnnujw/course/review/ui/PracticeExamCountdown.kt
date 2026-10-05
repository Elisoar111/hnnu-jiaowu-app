package com.hnnujw.course.review.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.hnnujw.course.review.formatRemaining
import com.hnnujw.course.review.remainingSeconds
import kotlinx.coroutines.delay

/**
 * 限时考试的**剩余时间显示**。
 *
 * 此前倒计时是一个每秒自增的顶层状态，被 GlassPageScaffold 的 subtitle 读取 ——
 * 结果是限时考试期间**整个 PracticeScreen 每秒重组一次**（子项大多被 remember 挡住，
 * 但函数体重启本身就有成本，还在作答频繁的页面上叠了一层常驻噪声）。
 *
 * 现在显示下沉到这个小 Text：每秒只有它自己重组。到点自动交卷与 5/1 分钟警告
 * 仍在 PracticeScreen 的超时循环里处理 —— 那条循环不再写任何"每秒变化"的状态，
 * 只在真正有动作（警告、交卷）时才碰界面。
 *
 * @param startedAt 会话开始时刻（毫秒墙钟）。`<= 0`（未设定）时以进入本屏为起点。
 */
@Composable
internal fun ExamCountdownText(
    totalSeconds: Int,
    startedAt: Long,
    modifier: Modifier = Modifier
) {
    // 初始值就按真实已流逝时间算：进程被杀重建会话沿用原 startedAt，
    // 重建后显示的剩余时间应当接着走，而不是从总时长重新跳出来。
    val effectiveStart = startedAt.takeIf { it > 0L } ?: System.currentTimeMillis()
    var remaining by remember(totalSeconds, effectiveStart) {
        mutableIntStateOf(remainingSeconds(totalSeconds, elapsedSecondsInt(effectiveStart)))
    }
    LaunchedEffect(totalSeconds, effectiveStart) {
        if (totalSeconds <= 0) return@LaunchedEffect
        while (true) {
            val now = System.currentTimeMillis()
            remaining = remainingSeconds(totalSeconds, elapsedSecondsInt(effectiveStart))
            // 对齐到下一秒边界再醒：避免忙等，也让抖动不至于累积成漂移。
            delay(1000L - (now % 1000L))
        }
    }
    Text(
        text = "⏱ ${formatRemaining(remaining)}",
        fontSize = 13.sp,
        lineHeight = 18.sp,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier
    )
}

private fun elapsedSecondsInt(startedAt: Long): Int =
    ((System.currentTimeMillis() - startedAt) / 1000L).toInt().coerceAtLeast(0)
