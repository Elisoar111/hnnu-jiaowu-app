package com.hnnujw.course.review.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hnnujw.course.review.AiChatClient
import com.hnnujw.course.review.AiUsage
import com.hnnujw.course.review.ReviewQuestion
import com.hnnujw.course.ui.system.AnimatedIconSpec
import com.hnnujw.course.ui.system.AnimatedLineIcon
import com.hnnujw.course.ui.system.GlassTextField
import com.hnnujw.course.ui.system.IconVisualState
import com.hnnujw.course.ui.system.MarkdownText

/**
 * AI 答疑的界面状态。
 *
 * [Streaming] 与 [Loading] 必须分开：流式下模型是**逐字吐**的，一上来就有内容可渲染，
 * 再显示"正在思考…"会让用户以为卡住了。两者的区别是"有没有拿到第一个字"。
 */
sealed interface AiUiState {
    data object Idle : AiUiState

    /** 请求已发出，**一个字都还没回来**。 */
    data object Loading : AiUiState

    /** 正在逐字上屏。[reasoning] 是推理模型的思考过程（DeepSeek-R1 系的 `reasoning_content`）。 */
    data class Streaming(val text: String, val reasoning: String = "") : AiUiState

    /**
     * 本轮已完成。
     *
     * 🔴 [reasoning] **必须带到这里**。此前它只在 [Streaming] 里存在，流式一结束
     * 思考内容就永久消失了（界面上再也点不开）—— 而用户恰恰要求"思考可折叠回看"。
     * 解析层（`ChatReply`）本来就有这个字段，是 `Result.Success` 与这里把它丢了。
     */
    data class Done(
        val text: String,
        val reasoning: String = "",
        val usage: AiUsage? = null
    ) : AiUiState

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
    /**
     * 本题**累计**用量（已含本轮）。null = 服务端从未返回过用量。
     *
     * 追问是常态，只看单轮看不出这一道题一共花了多少 token，所以与单轮用量并列展示。
     */
    totalUsage: AiUsage? = null,
    onAsk: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    // 🔴 输入框**预填当前题目**（2026-10 用户要求）：打开面板就能看到「AI 会看到哪道题」，
    // 光标停在「我想问：」后面等用户补充。key 里带 qid → 换题自动重填。
    var input by remember(question.id) {
        mutableStateOf(AiChatClient.buildPrefillText(question, userAnswer))
    }
    val busy = state is AiUiState.Loading || state is AiUiState.Streaming
    // 思考折叠块的复位键：每完成一轮 history 就多两条，用它区分"这是第几轮"。
    // 不用 `remember(question.id)` —— 同一题追问多轮时，上一轮的展开状态会串到下一轮。
    val turnKey = history.size
    // Done 态下 history 的最后两条就是"本轮提问 + 本轮回答"，与上面 state 渲染的是
    // 同一份内容；不剔掉会把同一段回答在面板里显示两遍（真机可见）。
    val pastTurns = if (state is AiUiState.Done && history.size >= 2) history.dropLast(2) else history

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
                        text = "在「复习 → AI 答疑设置」里配置服务商与 API Key 后即可使用。" +
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
            is AiUiState.Streaming -> {
                // 推理模型的思考过程先于正文到达。流式期间默认**展开**，让用户看到
                // "它在动"而不是盯十几秒空白（DeepSeek-R1 系思考可以很长）。
                ThinkingBlock(
                    reasoning = state.reasoning,
                    streaming = true,
                    hasAnswer = state.text.isNotBlank(),
                    turnKey = turnKey
                )
                if (state.text.isNotBlank()) MarkdownBody(state.text)
                Spacer(Modifier.height(8.dp))
                InlineSpinner("正在生成…")
            }
            is AiUiState.Done -> {
                // 正文已到 ⇒ 思考自动收成一行，随时可点开回看（2026-10 用户要求）。
                ThinkingBlock(
                    reasoning = state.reasoning,
                    streaming = false,
                    hasAnswer = true,
                    turnKey = turnKey
                )
                MarkdownBody(state.text)
                UsageFooter(turn = state.usage, total = totalUsage)
            }
        }

        // 生成中不允许再发问：流式回答还没结束就叠一条新请求，两段输出会互相覆盖。
        if (aiConfigured && !busy) {
            // 有历史时把此前几轮列出来 —— 追问场景下不看上文很难接话。
            if (pastTurns.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                pastTurns.forEach { turn ->
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
 * 思考过程折叠块。
 *
 * 默认行为（2026-10 用户确认）：**流式中展开、正文到达后收起**。
 *  - [streaming] 且正文还没到 ⇒ 展开，让用户看着思考在长；
 *  - 正文到了（[hasAnswer]）或本轮已结束 ⇒ 收起成一行「思考过程（N 字）」；
 *  - 用户手动点过之后**一律听用户的**，不再自动改。
 *
 * [reasoning] 为空时整块不渲染（服务端没返回 `reasoning_content` 是常态，
 * 不该留一条空标题占位）。
 */
@Composable
private fun ThinkingBlock(
    reasoning: String,
    streaming: Boolean,
    hasAnswer: Boolean,
    /** 轮次标识：变了就重置展开状态（`history.size`）。 */
    turnKey: Int,
    modifier: Modifier = Modifier
) {
    if (reasoning.isBlank()) return
    // null = 用户还没手动切过 ⇒ 用自动判定；非 null = 用户的选择优先。
    var userChoice by remember(turnKey) { mutableStateOf<Boolean?>(null) }
    val expanded = userChoice ?: (streaming && !hasAnswer)

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.045f))
                .clickable { userChoice = !expanded }
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AnimatedLineIcon(
                spec = AnimatedIconSpec.Chevron,
                modifier = Modifier.size(16.dp),
                state = if (expanded) IconVisualState.Expanded else IconVisualState.Idle,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = if (streaming && !hasAnswer) {
                    "思考中…（${reasoning.length} 字）"
                } else {
                    "思考过程（${reasoning.length} 字）"
                },
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = if (expanded) "收起" else "展开",
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = MaterialTheme.colorScheme.primary
            )
        }
        if (expanded) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = reasoning,
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(10.dp))
    }
}

/**
 * 用量页脚：本轮 + 本题累计。
 *
 * 第一轮时两者数值相同，第二行不渲染 —— 否则会出现两行一模一样的数字。
 */
@Composable
private fun UsageFooter(turn: AiUsage?, total: AiUsage?) {
    if (turn == null && total == null) return
    Spacer(Modifier.height(8.dp))
    Column {
        turn?.let {
            Text(
                text = it.summary(),
                fontSize = 11.sp,
                lineHeight = 16.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (turn != null && total != null && total.totalTokens != turn.totalTokens) {
            Text(
                text = total.cumulative(),
                fontSize = 11.sp,
                lineHeight = 16.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
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
