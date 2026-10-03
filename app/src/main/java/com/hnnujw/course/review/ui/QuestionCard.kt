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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
    /** 本题是否已斩（只影响按钮图标与提示，不影响作答）。 */
    slashed: Boolean = false,
    onToggleSlash: () -> Unit = {},
    /**
     * 背题模式：进来就直接摊开答案与解析，不要求作答。
     *
     * 与"已判分"共用同一套"揭晓"渲染（[revealed]），但**语义不同**：判分揭晓是
     * "你答完了"，背题揭晓是"我直接给你看"。落库与否由 [PracticeSession.recordsAnswers]
     * 决定，本组件不碰数据库。
     */
    memorize: Boolean = false,
    /**
     * 作答版本号（见 PracticeScreen 的 answerVersion）。
     *
     * [PracticeSession] 内部是普通 Map，改它不触发快照；调用方在
     * select / judge / unjudge 后把 version+1，下面的 remember 以它为 key
     * 重新取值 —— 这是选项高亮与「确认答案」亮起的唯一驱动。
     */
    version: Int = 0,
    modifier: Modifier = Modifier
) {
    val answer = remember(question, version) { session.answerOf(question) }
    val judged = remember(question, version) { session.isJudged(question) }
    val correct = remember(question, version) { session.isCorrect(question) }
    // 揭晓 = 已判分 或 背题模式。选项着色、答案/解析展示都看它。
    val revealed = judged || memorize

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
            // 斩题：一眼就会的题一刀移出后续练习。**斩 ≠ 已掌握** —— 只影响练习范围，
            // 不改变掌握度与错题本（见 SlashPolicy）。
            GlassCircleButton(
                onClick = onToggleSlash,
                icon = if (slashed) Icons.Filled.Delete else Icons.Outlined.Delete,
                contentDescription = if (slashed) "放回练习" else "斩题（移出后续练习）",
                tint = if (slashed) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant
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
                    !revealed && picked -> OptionState.PICKED
                    revealed && letter in question.answer -> OptionState.CORRECT
                    revealed && picked -> OptionState.WRONG
                    else -> OptionState.IDLE
                },
                enabled = !revealed,
                multi = question.isMulti,
                onClick = { onSelect(letter) }
            )
            Spacer(Modifier.height(8.dp))
        }

        // 确认 / 撤回
        if (!revealed) {
            Spacer(Modifier.height(4.dp))
            ConfirmButton(
                enabled = answer.isNotBlank(),
                text = if (question.isMulti) "提交答案" else "确认答案",
                onClick = onConfirm
            )
        } else {
            Spacer(Modifier.height(8.dp))
            if (judged) {
                VerdictBanner(correct = correct, answer = question.answer, userAnswer = answer)
            } else {
                // 背题模式：用户没作答，所以不判对错，直接摊开正确答案。
                AnswerBanner(answer = question.answer)
            }
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
                // 背题模式没有"判定"可撤回，就不显示「再想一下」。
                if (judged) {
                    SecondaryAction("再想一下", onClick = onUnjudge, modifier = Modifier.weight(1f))
                }
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

/**
 * 背题模式的答案条。
 *
 * 刻意**不判对错**（用户根本没作答，判了就是编造），只把正确答案摊开。
 * 配色走主色而非错误色，避免让人误以为"答错了"。
 */
@Composable
private fun AnswerBanner(answer: String) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(scheme.primary.copy(alpha = 0.12f))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "正确答案",
            fontSize = 15.sp,
            lineHeight = 20.sp,
            fontWeight = FontWeight.SemiBold,
            color = scheme.primary
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = answer,
            fontSize = 15.sp,
            lineHeight = 20.sp,
            fontWeight = FontWeight.SemiBold,
            color = scheme.onSurface
        )
    }
}
