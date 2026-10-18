package com.hnnujw.course.review.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.hnnujw.course.review.AiChatClient
import com.hnnujw.course.review.AiConfig
import com.hnnujw.course.review.AiConfigStore
import com.hnnujw.course.review.AiUsage
import com.hnnujw.course.review.ReviewQuestion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 练习页「问 AI」的状态持有者：配置加载、对话历史、用量累计、流式请求状态机。
 *
 * 从 PracticeScreen.kt 拆出来 —— 此前这段逻辑长在主 Composable 的 `onAsk` 闭包里
 * （约 60 行嵌套），与收藏、翻页、答题卡的状态挤在一起，谁都不敢动。
 * 现在它是一个独立的普通类，字段全部走 Compose `mutableStateOf`，重组驱动与
 * 原来的 `var aiState by remember { ... }` 完全一致；本类**不做**任何 Composable 排版，
 * 面板 UI 仍在 [AiExplainPanel]。
 *
 * ⚠️ 不持有 PracticeSession：练习页重组间 session 引用可能被外层换掉，控制器拿着
 * 过期引用会把答案写串。作答文本由调用方在 `ask` 时显式传入。
 */
internal class PracticeAiController(
    private val context: Context,
    private val scope: kotlinx.coroutines.CoroutineScope
) {
    /** DeepSeek 单份配置。初始为空（未配置），等 ON_RESUME 从存储里读。 */
    var config by mutableStateOf(AiConfig())
        private set

    /** 面板状态机：Idle → Loading → Streaming → Done / Failed。 */
    var state by mutableStateOf<AiUiState>(AiUiState.Idle)
        private set

    /** 本题的对话历史（换题即清空 —— 上一题的问答对下一题是噪声，还白烧 token）。 */
    var history by mutableStateOf<List<AiTurn>>(emptyList())
        private set

    /** 本题**累计**用量（跨追问累加，换题清零）。null = 服务端一次都没返回过用量。 */
    var usageTotal by mutableStateOf<AiUsage?>(null)
        private set

    val configured: Boolean get() = config.isConfigured

    /** 「AI 正在生成」—— 外层拿它画常驻胶囊（面板收起后用户唯一能看到的信号）。 */
    val busy: Boolean get() = state is AiUiState.Loading || state is AiUiState.Streaming

    /**
     * 从存储重读 AI 配置。
     *
     * 读它要碰 AndroidKeyStore 解密，主线程同步读会卡首帧，所以走 IO。
     * 🔴 runCatching 必须包：AndroidKeyStore 读取在真机上可能抛异常
     * （密钥随卸载/恢复备份失效等），这里又在协程里，裸抛就是崩进程。
     * 失败按「未配置」处理 —— 「问 AI」按钮本来就有未配置的分流。
     */
    fun refreshConfig() {
        scope.launch {
            val loaded = withContext(Dispatchers.IO) {
                runCatching { AiConfigStore.load(context) }.getOrNull()
            }
            config = loaded ?: AiConfig()
        }
    }

    /**
     * 换题时重置：面板状态、对话历史、累计用量（收藏与面板开合不在这管）。
     *
     * @param keepHistory 保留上一题的问答历史（用户可在 AI 设置里开）。
     *   默认关：把上一题的问答带进下一题，模型容易被无关上下文带偏，还白烧 token。
     *   打开后上下文延续，"就着刚才那段继续问"就能直接问。
     */
    fun resetForNewQuestion(keepHistory: Boolean = false) {
        state = AiUiState.Idle
        // 历史是否随题清空由偏好决定；用量始终是"本题累计"，必须归零。
        if (!keepHistory) history = emptyList()
        usageTotal = null
    }

    /**
     * 发起一次讲解请求（含追问）。[userAnswer] 是用户当前作答（可能为空）。
     *
     * 流式增量在 IO 线程到达（客户端已切回主线程回调），用两个 StringBuilder 累积；
     * state 每次更新都触发一次重组。客户端内部按 60ms 合并过增量，所以不是逐 token 重组。
     */
    fun ask(question: ReviewQuestion, userAnswer: String, extra: String) {
        val currentConfig = config
        if (!currentConfig.isConfigured) {
            state = AiUiState.Failed("还没有配置好 AI，请先到「AI 答疑设置」填写 DeepSeek 的 API Key")
            return
        }
        state = AiUiState.Loading
        // 先把「用户问了什么」记进历史，请求回来再补上 AI 的回答。
        // 用户追问「为什么」时模型能看到上几轮在聊什么（2026-10 用户要求）。
        val asked = extra.ifBlank { "请帮我讲解这道题。" }
        scope.launch {
            val answer = StringBuilder()
            val reasoning = StringBuilder()
            suspend fun runOnce(): AiChatClient.Result =
                AiChatClient.askAboutQuestion(
                    config = currentConfig,
                    question = question,
                    userAnswer = userAnswer,
                    extraPrompt = extra,
                    history = history.map { AiChatClient.Turn(it.role, it.text) },
                    timeoutSeconds = currentConfig.timeoutSeconds,
                    retries = currentConfig.retries,
                    onDelta = { delta ->
                        answer.append(delta)
                        state = AiUiState.Streaming(answer.toString(), reasoning.toString())
                    },
                    onReasoning = { delta ->
                        reasoning.append(delta)
                        state = AiUiState.Streaming(answer.toString(), reasoning.toString())
                    }
                )

            // 只支持 DeepSeek 一家，没有"换一条档案重试"的余地：
            // 失败就如实报错，让用户去设置页改 Key / 模型。
            when (val result = runOnce()) {
                is AiChatClient.Result.Success -> {
                    history = (history + AiTurn(asked, true) + AiTurn(result.text, false))
                        .takeLast(AiChatClient.HISTORY_LIMIT)
                    // 累计用量：只有服务端确实返回了用量才加。
                    // 服务端不返回时保持原值（而不是当成 0 加），否则
                    // "没返回"会被算成"这轮 0 token"，总数偏小且无从察觉。
                    result.usage?.let { turn ->
                        usageTotal = (usageTotal ?: AiUsage.Zero) + turn
                    }
                    // reasoning 一并带进 Done：思考过程要在流式结束后
                    // 仍能折叠回看（此前丢了，流式一结束就永久消失）。
                    state = AiUiState.Done(result.text, result.reasoning, result.usage)
                }
                is AiChatClient.Result.Failure -> {
                    // 失败**不入历史**：失败的那句不是有效上下文，留在里面
                    // 会让模型以为用户问过一个根本没得到答案的问题。
                    state = AiUiState.Failed(result.message)
                }
            }
        }
    }
}

/**
 * 创建并记住练习页的 [PracticeAiController]，并挂上「每次回到本页重读配置」的生命周期。
 *
 * 🔴 每次回到本页都重读配置（而不是只读一次）：从 AI 设置页配好再返回时，
 * 本页并没有被销毁重建，若只读一次就会一直是"未配置" —— 按钮永远停在
 * 「配置 AI」，用户以为没保存成功（2026-10 用户报「没有 AI 配置界面」）。
 *
 * 用 LifecycleEventObserver 而不是 LifecycleResumeEffect：项目只依赖
 * lifecycle-runtime-ktx，没有 lifecycle-runtime-compose，那个 Composable 不可用。
 */
@Composable
internal fun rememberPracticeAiController(context: Context): PracticeAiController {
    val scope = rememberCoroutineScope()
    val controller = remember { PracticeAiController(context, scope) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val owner = lifecycleOwner
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) controller.refreshConfig()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    return controller
}
