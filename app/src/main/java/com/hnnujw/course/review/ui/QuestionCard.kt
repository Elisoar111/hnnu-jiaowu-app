package com.hnnujw.course.review.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hnnujw.course.review.PracticeSession
import com.hnnujw.course.review.ReviewQuestion
import com.hnnujw.course.ui.system.GlassCircleButton

/**
 * 单题卡片：题干 + 选项 + 判分后的解析。
 *
 * 这块是纯展示：所有判定都来自 [PracticeSession]（答案、是否已判、是否正确），
 * Composable 里**不做任何判分逻辑**，也不持有作答状态。
 */
@Composable
fun QuestionCard(
    question: ReviewQuestion,
    session: PracticeSession,
    onSelect: (String) -> Unit,
    onConfirm: () -> Unit,
    onUnjudge: () -> Unit,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    onAskAi: () -> Unit,
    aiEnabled: Boolean,
    modifier: Modifier = Modifier
) {
    val answer = session.answerOf(question)
    val judged = session.isJudged(question)
    val correct = session.isCorrect(question)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        // 题干 + 收藏
        Row(verticalAlignment = Alignment.Top) {
            Text(
                text = question.stem,
                fontSize = 17.sp,
                lineHeight = 26.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            GlassCircleButton(
                onClick = onToggleFavorite,
                icon = if (isFavorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                contentDescription = if (isFavorite) "取消收藏" else "收藏",
                tint = if (isFavorite) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (question.chapter.isNotBlank()) {
            Text(
                text = question.chapter,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(14.dp))

        // 选项
        question.options.forEachIndexed { index, option ->
            val letter = ('A' + index).toString()
            val picked = answer.contains(letter)
            OptionRow(
                letter = letter,
                text = option,
                state = when {
                    !judged && picked -> OptionState.PICKED
                    judged && letter in question.answer -> OptionState.CORRECT
                    judged && picked -> OptionState.WRONG
                    else -> OptionState.IDLE
                },
                enabled = !judged,
                multi = question.isMulti,
                onClick = { onSelect(letter) }
            )
            Spacer(Modifier.height(8.dp))
        }

        // 确认 / 撤回
        if (!judged) {
            Spacer(Modifier.height(4.dp))
            ConfirmButton(
                enabled = answer.isNotBlank(),
                text = if (question.isMulti) "提交答案" else "确认答案",
                onClick = onConfirm
            )
        } else {
            Spacer(Modifier.height(8.dp))
            VerdictBanner(correct = correct, answer = question.answer, userAnswer = answer)
            if (question.explanation.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = question.explanation,
                    fontSize = 14.sp,
                    lineHeight = 22.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                // 🔴 「问 AI」按钮**常显**，不再按 aiEnabled 隐藏。
                //
                // 原逻辑 `if (aiEnabled)` 在没配 Key 时让按钮整个消失，于是形成死循环：
                // 用户看不到「问 AI」→ 不知道有 AI 功能 → 找不到配置入口 → 永远配不上
                // （2026-10 用户报「没有 AI 配置界面」）。改成常显、点击时再分流：
                // 没配 Key 就直接去设置页，配了才打开讲解面板。
                SecondaryAction(
                    text = if (aiEnabled) "问 AI" else "配置 AI",
                    onClick = onAskAi,
                    modifier = Modifier.weight(1f)
                )
                SecondaryAction("再想一下", onClick = onUnjudge, modifier = Modifier.weight(1f))
            }
        }

        Spacer(Modifier.height(28.dp))
    }
}

internal enum class OptionState { IDLE, PICKED, CORRECT, WRONG }

@Composable
private fun OptionRow(
    letter: String,
    text: String,
    state: OptionState,
    enabled: Boolean,
    multi: Boolean,
    onClick: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val (bg, border, markColor) = when (state) {
        OptionState.PICKED -> Triple(scheme.primaryContainer.copy(alpha = 0.55f), scheme.primary, scheme.primary)
        OptionState.CORRECT -> Triple(scheme.primaryContainer.copy(alpha = 0.7f), scheme.primary, scheme.primary)
        OptionState.WRONG -> Triple(scheme.errorContainer.copy(alpha = 0.55f), scheme.error, scheme.error)
        OptionState.IDLE -> Triple(scheme.surface.copy(alpha = 0.35f), scheme.outlineVariant, scheme.onSurfaceVariant)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(bg)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(if (multi) RoundedCornerShape(7.dp) else CircleShape)
                .background(if (state == OptionState.IDLE) scheme.surface.copy(alpha = 0.6f) else markColor),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = letter,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (state == OptionState.IDLE) scheme.onSurfaceVariant else scheme.onPrimary
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text = text,
            fontSize = 15.sp,
            lineHeight = 21.sp,
            color = scheme.onSurface
        )
    }
}

@Composable
private fun VerdictBanner(correct: Boolean, answer: String, userAnswer: String) {
    val scheme = MaterialTheme.colorScheme
    val color = if (correct) scheme.primary else scheme.error
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = if (correct) "回答正确" else "回答错误",
            fontSize = 15.sp,
            lineHeight = 20.sp,
            fontWeight = FontWeight.SemiBold,
            color = color
        )
        if (!correct) {
            Spacer(Modifier.width(10.dp))
            Text(
                text = "正确答案 $answer · 你选 $userAnswer",
                fontSize = 13.sp,
                lineHeight = 18.sp,
                color = scheme.onSurfaceVariant
            )
        }
    }
}
