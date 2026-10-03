package com.hnnujw.course.review.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hnnujw.course.review.ReviewQuestion
import com.hnnujw.course.ui.system.GlassTextField
import com.hnnujw.course.ui.system.MarkdownText

/** AI 答疑的界面状态。 */
sealed interface AiUiState {
    data object Idle : AiUiState
    data object Loading : AiUiState
    data class Done(val text: String) : AiUiState
    data class Failed(val reason: String) : AiUiState
}

/**
 * AI 讲解面板。
 *
 * 直接嵌在题目下方（而不是弹窗）：AI 是对当前题的补充说明，弹窗会把题和答案挡住，
 * 反而看不清自己错在哪。
 */
@Composable
fun AiExplainPanel(
    question: ReviewQuestion,
    state: AiUiState,
    userAnswer: String,
    aiConfigured: Boolean,
    onAsk: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    var input by remember(question.id) { mutableStateOf("") }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .background(
                MaterialTheme.colorScheme.surface.copy(alpha = 0.4f),
                RoundedCornerShape(16.dp)
            )
            .padding(14.dp)
    ) {
        AiPanelHeader(
            title = if (aiConfigured) "AI 讲解" else "AI 讲解（未配置 API Key）",
            onDismiss = onDismiss
        )
        Spacer(Modifier.height(10.dp))

        when (state) {
            is AiUiState.Idle -> {
                if (aiConfigured) {
                    Text(
                        text = "想让 AI 讲讲这道题？也可以在下面追问。",
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        text = "在「复习 → AI 设置」里填入 DeepSeek API Key 后即可使用。" +
                            "题目只在你主动点击时才会发送。",
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            is AiUiState.Loading -> InlineSpinner("AI 正在思考…")
            is AiUiState.Failed -> {
                Text(
                    text = state.reason,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    color = MaterialTheme.colorScheme.error
                )
            }
            is AiUiState.Done -> {
                MarkdownBody(state.text)
            }
        }

        if (aiConfigured && state !is AiUiState.Loading) {
            Spacer(Modifier.height(12.dp))
            GlassTextField(
                value = input,
                onValueChange = { input = it },
                placeholder = "还想问点什么…（可留空）",
                singleLine = false,
                minHeight = 64.dp,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            SecondaryAction(
                text = if (state is AiUiState.Done) "继续追问" else "发送给 AI",
                onClick = { onAsk(input.trim()) },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/**
 * 渲染 AI 的回答。
 *
 * 复用项目现成的 [MarkdownText]（内部含 Markdown 语法时才切富文本排版），
 * 不自己搭 richtext：AI 输出带 `**加粗**`、`- 分点` 是常态，直接富文本排版最稳，
 * 而版本不匹配的 richtext 导入路径很容易踩坑。
 */
@Composable
private fun MarkdownBody(text: String) {
    MarkdownText(
        text = text,
        modifier = Modifier.fillMaxWidth(),
        fontSize = 14.sp,
        lineHeight = 22.sp,
        color = MaterialTheme.colorScheme.onSurface
    )
}

@Composable
private fun AiPanelHeader(title: String, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            fontSize = 15.sp,
            lineHeight = 20.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        SecondaryAction(
            text = "收起",
            onClick = onDismiss,
            enabled = true,
            modifier = Modifier.height(30.dp)
        )
    }
}
