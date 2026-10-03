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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.hnnujw.course.review.AiChatClient
import com.hnnujw.course.review.AiKeyStore
import com.hnnujw.course.review.ExamTimeoutAction
import com.hnnujw.course.review.PracticeResult
import com.hnnujw.course.review.PracticeSession
import com.hnnujw.course.review.ReviewDatabase
import com.hnnujw.course.review.examTimeouts
import com.hnnujw.course.review.formatRemaining
import com.hnnujw.course.review.remainingSeconds
import com.hnnujw.course.ui.system.GlassPageScaffold
import com.hnnujw.course.ui.system.GlassProgressBar
import com.hnnujw.course.ui.system.GlassToaster
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 练习屏：逐题作答。
 *
 * 状态分层刻意做得很死：
 *  - 作答与判分全在 [PracticeSession]（纯逻辑，可单测）；
 *  - 本 Composable 只持有 index / aiState 这类**界面状态**；
 *  - 落库（records、错题本、每日统计）在判分那一刻做，走 IO。
 */
@Composable
fun PracticeScreen(
    context: Context,
    session: PracticeSession,
    title: String,
    onBack: () -> Unit,
    onFinished: (PracticeResult) -> Unit,
    /**
     * 「问 AI」在**还没配 Key** 时被点时调用 —— 用来跳去 AI 设置页。
     *
     * 以前这个按钮在没配 Key 时整个隐藏，用户看不到任何 AI 入口，也就无从配置
     * （2026-10 用户报「没有 AI 配置界面」）。现在按钮常显，这里就是它的兜底去向。
     */
    onOpenAiSettings: () -> Unit = {}
) {
    val db = remember { ReviewDatabase.get(context) }
    val scope = rememberCoroutineScope()
    var index by remember { mutableStateOf(0) }
    var showAi by remember { mutableStateOf(false) }
    var aiState by remember { mutableStateOf<AiUiState>(AiUiState.Idle) }
    // AI 对话历史（换题即清空 —— 上一题的问答对下一题是噪声，还白烧 token）
    var aiHistory by remember { mutableStateOf<List<AiTurn>>(emptyList()) }
    // 收藏状态放这里（界面状态），初始值从库里读一次；点完立刻改本地值，
    // 不用等数据库回来 —— 星星的反馈必须是即时的。
    var favorite by remember { mutableStateOf(false) }
    // 本题是否已斩（同样先给即时反馈，再落库）。
    var slashed by remember { mutableStateOf(false) }

    // ── 限时考试：剩余秒数 + 两条"已警告过"标记 ──────────────────────────
    // 只在限时（timing.isTimed）时才真正生效；不限时时下面的循环根本不启动，
    // 也不会显示倒计时。remaining 初始值就是总时长（不限时则是一个极大值，不显示）。
    var remaining by remember { mutableStateOf(Int.MAX_VALUE) }
    var warnedFive by remember { mutableStateOf(false) }
    var warnedOne by remember { mutableStateOf(false) }
    // 用 session 的 startedAt 作 key 之一：换一轮考试（新的 session）时重置状态。
    // 墙钟换算 elapsed（见下），不用累加计数 —— 后台回来计数会漂。
    LaunchedEffect(session.timing.durationSeconds, session.startedAt) {
        if (!session.timing.isTimed) return@LaunchedEffect
        warnedFive = false
        warnedOne = false
        val total = session.timing.durationSeconds
        val startedAt = session.startedAt.takeIf { it > 0L }
            // startedAt 未设定（<= 0）时以进入本屏为起点，至少让倒计时能走。
            ?: System.currentTimeMillis()
        while (true) {
            val now = System.currentTimeMillis()
            val elapsedSec = ((now - startedAt) / 1000L).toInt()
            val left = remainingSeconds(total, elapsedSec)
            remaining = left
            val actions = examTimeouts(left, warnedFive, warnedOne)
            for (action in actions) {
                when (action) {
                    ExamTimeoutAction.Submit -> {
                        // 到点自动交卷：未判分的题计为未作答（resultForTimeout）。
                        // 交完立即 return 结束循环，避免重复触发。
                        GlassToaster.show("时间到，已自动交卷")
                        onFinished(session.resultForTimeout())
                        return@LaunchedEffect
                    }
                    ExamTimeoutAction.WarnFiveMinutes -> {
                        warnedFive = true
                        GlassToaster.show("还剩 5 分钟")
                    }
                    ExamTimeoutAction.WarnOneMinute -> {
                        warnedOne = true
                        GlassToaster.show("还剩 1 分钟")
                    }
                }
            }
            // 对齐到下一秒边界再醒：避免忙等，也让抖动不至于累积成漂移。
            delay(1000L - (now % 1000L))
        }
    }

    val question = session.questions.getOrNull(index)
    var aiKey by remember { mutableStateOf<String?>(null) }
    var aiModel by remember { mutableStateOf(AiKeyStore.DEFAULT_MODEL) }
    // API Key 走 AndroidKeyStore 解密，主线程同步读会卡首帧，所以放 IO 线程。
    //
    // 🔴 每次回到本页都重读 Key（而不是只读一次）：从 AI 设置页配好 Key 再返回时，
    // 本页并没有被销毁重建，若只读一次 `aiKey` 会一直是 null —— 按钮永远停在
    // 「配置 AI」，用户以为没保存成功（2026-10 用户报「没有 AI 配置界面」）。
    //
    // 用 LifecycleEventObserver 而不是 LifecycleResumeEffect：项目只依赖
    // lifecycle-runtime-ktx，没有 lifecycle-runtime-compose，那个 Composable 不可用。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val owner = lifecycleOwner
        fun refresh() {
            scope.launch {
                val loaded = withContext(Dispatchers.IO) {
                    AiKeyStore.apiKey(context) to AiKeyStore.model(context)
                }
                aiKey = loaded.first
                aiModel = loaded.second
            }
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }

    // 换题时重置本题的界面状态：收藏读库、AI 面板收起、**对话历史清空**。
    LaunchedEffect(index) {
        val q = session.questions.getOrNull(index) ?: return@LaunchedEffect
        favorite = withContext(Dispatchers.IO) { db.isFavorite(q.bankId, q.id) }
        slashed = withContext(Dispatchers.IO) { db.isSlashed(q.bankId, q.id) }
        showAi = false
        aiState = AiUiState.Idle
        // 历史必须随题清：把上一题的问答带进下一题，模型会被无关上下文带偏。
        aiHistory = emptyList()
    }

    GlassPageScaffold(
        title = title,
        subtitle = if (session.total == 0) {
            null
        } else {
            buildString {
                append("第 ${index + 1} / ${session.total} 题 · 正确率 ${(session.accuracy * 100).toInt()}%")
                // 限时才追加倒计时；不限时 subtitle 与从前逐字一致。
                if (session.timing.isTimed) {
                    append(" · ⏱ ${formatRemaining(remaining)}")
                }
            }
        },
        onBack = onBack
    ) { padding ->
        if (question == null) {
            ResultPane(
                result = session.result(),
                onBack = onBack,
                onRestart = { onFinished(session.result()) },
                modifier = Modifier.padding(padding)
            )
            return@GlassPageScaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(top = padding.calculateTopPadding())
        ) {
            // 进度条：走玻璃件的弹簧动画，与课表/成绩页同一条观感
            GlassProgressBar(
                progress = session.accuracy.coerceIn(0f, 1f),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                height = 4.dp
            )

            QuestionCard(
                question = question,
                session = session,
                onSelect = { letter ->
                    session.select(question, letter)
                },
                onConfirm = {
                    val correct = session.judge(question)
                    // 判分即落库：答对答错都要记，否则统计和错题本会漏。
                    // ⚠️ 背题模式（session.recordsAnswers == false）**不落库** ——
                    // 背题不计入统计，否则背一遍就会把题洗成"已掌握"。
                    if (session.recordsAnswers) {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                db.recordAnswer(question.bankId, question.id, correct)
                            }
                        }
                    }
                },
                onUnjudge = { session.unjudge(question) },
                isFavorite = favorite,
                onToggleFavorite = {
                    // 先翻转本地状态给即时反馈，再落库。
                    favorite = !favorite
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            db.toggleFavorite(question.bankId, question.id)
                        }
                    }
                },
                // 斩题：只写 slashed 表，绝不碰掌握度（斩题 ≠ 已掌握）。
                slashed = slashed,
                onToggleSlash = {
                    slashed = !slashed
                    val nowSlashed = slashed
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            if (nowSlashed) {
                                db.slashQuestion(question.bankId, question.id)
                            } else {
                                db.unslashQuestion(question.bankId, question.id)
                            }
                        }
                    }
                    GlassToaster.show(if (nowSlashed) "已斩，移出后续练习" else "已放回练习")
                },
                memorize = session.memorize,
                // 没配 Key 时点「配置 AI」直接去设置页，不打开讲解面板。
                onAskAi = { if (aiKey.isNullOrBlank()) onOpenAiSettings() else showAi = true },
                aiEnabled = !aiKey.isNullOrBlank()
            )

            if (showAi) {
                AiExplainPanel(
                    question = question,
                    state = aiState,
                    userAnswer = session.answerOf(question),
                    aiConfigured = !aiKey.isNullOrBlank(),
                    history = aiHistory,
                    onAsk = { extra ->
                        val key = aiKey
                        if (key.isNullOrBlank()) {
                            aiState = AiUiState.Failed("还没有配置 API Key")
                            return@AiExplainPanel
                        }
                        aiState = AiUiState.Loading
                        // 先把「用户问了什么」记进历史，请求回来再补上 AI 的回答。
                        // 用户追问「为什么」时模型能看到上几轮在聊什么（2026-10 用户要求）。
                        val asked = extra.ifBlank { "请帮我讲解这道题。" }
                        scope.launch {
                            val result = AiChatClient.askAboutQuestion(
                                apiKey = key,
                                model = aiModel,
                                question = question,
                                userAnswer = session.answerOf(question),
                                extraPrompt = extra,
                                history = aiHistory.map { AiChatClient.Turn(it.role, it.text) }
                            )
                            when (result) {
                                is AiChatClient.Result.Success -> {
                                    aiHistory = (aiHistory + AiTurn(asked, true) + AiTurn(result.text, false))
                                        .takeLast(AiChatClient.HISTORY_LIMIT)
                                    aiState = AiUiState.Done(result.text)
                                }
                                is AiChatClient.Result.Failure -> {
                                    // 失败**不入历史**：失败的那句不是有效上下文，留在里面
                                    // 会让模型以为用户问过一个根本没得到答案的问题。
                                    aiState = AiUiState.Failed(result.message)
                                }
                            }
                        }
                    },
                    onDismiss = { showAi = false }
                )
                Spacer(Modifier.height(16.dp))
            }

            // 底部导航：上一题 / 下一题
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                SecondaryAction(
                    text = "上一题",
                    enabled = index > 0,
                    onClick = { index-- },
                    modifier = Modifier.weight(1f)
                )
                SecondaryAction(
                    text = if (index >= session.total - 1) "完成" else "下一题",
                    enabled = true,
                    onClick = {
                        if (index >= session.total - 1) onFinished(session.result()) else index++
                    },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

/** 练习结果页。 */
@Composable
private fun ResultPane(
    result: PracticeResult,
    onBack: () -> Unit,
    onRestart: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "练习完成",
            fontSize = 22.sp,
            lineHeight = 30.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(20.dp))
        Text(
            text = "${(result.accuracy * 100).toInt()}%",
            fontSize = 44.sp,
            lineHeight = 52.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "共 ${result.total} 题 · 答对 ${result.correct} · 答错 ${result.wrong}" +
                if (result.unjudged > 0) " · 未作答 ${result.unjudged}" else "",
            fontSize = 14.sp,
            lineHeight = 20.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(28.dp))
        ConfirmButton("返回题库", enabled = true, onClick = onBack, modifier = Modifier.fillMaxWidth())
    }
}
