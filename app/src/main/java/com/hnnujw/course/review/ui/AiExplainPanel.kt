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
import com.hnnujw.course.review.AiChatClient
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
 * 面板里显示的一轮对话。
 *
 * 换题时整份历史要清掉 —— 上一题的上下文对下一题是噪声，还白烧 token。
 */
data class AiTurn(val text: String, val isUser: Boolean) {
    /** 转成 API 需要的 role 串。 */
    val role: String get() = if (isUser) "user" else "assistant"
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
    /** 本题此前的对话，由外部持有（换题时清空），本面板只负责显示。 */
    history: List<AiTurn> = emptyList(),
    onAsk: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    // 🔴 输入框**预填当前题目**（2026-10 用户要求）：打开面板就能看到「AI 会看到哪道题」，
    // 光标停在「我想问：」后面等用户补充。key 里带 qid → 换题自动重填。
    var input by remember(question.id) {
        mutableStateOf(AiChatClient.buildPrefillText(question, userAnswer))
    }
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
            // 有历史时把上一轮 AI 的回答也列出来 —— 追问场景下不看上文很难接话。
            if (history.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                history.forEach { turn ->
                    if (turn.isUser) {
                        Text(
                            text = "我：${turn.text}",
                            fontSize = 13.sp,
                            lineHeight = 19.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    } else {
                        MarkdownBody(turn.text)
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
            Spacer(Modifier.height(12.dp))
            GlassTextField(
                value = input,
                onValueChange = { input = it },
                placeholder = "补充你想问的…",
                singleLine = false,
                minHeight = 64.dp,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            SecondaryAction(
                text = if (state is AiUiState.Done) "继续追问" else "发送给 AI",
                // 把预填的题干剥掉：模型已经从 buildQuestionPrompt 拿到完整题目了，
                // 再把题干塞一遍是重复且浪费 token 的。只发用户自己补充的部分。
                onClick = { onAsk(AiChatClient.stripPrefill(input, question, userAnswer)) },
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
