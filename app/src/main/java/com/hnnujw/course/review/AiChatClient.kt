package com.hnnujw.course.review

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * DeepSeek 官方 API 的 Chat 客户端（OpenAI 兼容协议）。
 *
 * 所有请求吃一份 [AiConfig]（Key / 模型 / 超时 / 重试）。端点恒定，由
 * [AiEndpoint.chatUrl] 直接拼出 —— 上一版「端点由用户填、由 [AiEndpoint] 归一化
 * 补 `/v1`」的多服务商能力已随「只支持 DeepSeek」的决定删除。
 *
 * 协议层能力：
 *  - **SSE 流式**：`stream: true` 逐字上屏；服务端忽略该参数返回整包 JSON 时按
 *    非流式分支兼容。
 *  - **token 用量**：请求带 `stream_options.include_usage`，尾包 `usage` 解析后回传；
 *    端点不认这个参数（400 且报文提到它）时**去掉该参数重发一次**，不让一个可选
 *    参数把整次问答打死。
 *  - **超时 / 重试可配**：见 [RetryPolicy] 与 [AiConfigRules]。
 *
 * 刻意**没有**移植参考实现里翻译专用的东西（批次拆分、断点续传、术语表、译文缓存、
 * 润色二阶段）：单题答疑没有"批次"概念，照搬只会引入永远走不到的代码。
 *
 * 也没有启用官方文档新给的 `thinking` / `reasoning_effort`：那会让每次答疑都多花
 * 一笔思考 token 并显著拉长首字延迟，而单题讲解并不需要长链推理。协议层仍然认得
 * `reasoning_content`（服务端若返回就照常上屏），只是不主动要求。
 *
 * 隐私红线：
 *  - API Key **只出现在 Authorization 头**，绝不进日志（OkHttp 的异常里可能带请求头，
 *    所以这里从不打印整个 exception，只打自己的文案与异常类名）。
 *  - 只有用户主动点「问 AI」才会把题目发出去；默认不发。
 */
object AiChatClient {
    private const val TAG = "AiChatClient"

    /**
     * 最多带多少轮历史。
     *
     * 8 轮足够覆盖一道题的追问（讲不清 → 追问 → 再纠错…），又不会把请求撑爆。
     * 再长对单题讲解也没意义，用户早就该换下一题了。
     */
    const val HISTORY_LIMIT = 8

    /** 预填到输入框里的题干最大字数，超出部分截断。 */
    const val PREFILL_STEM_LIMIT = 60

    /** 讲解回复的 token 上限。 */
    private const val MAX_TOKENS = 1200

    /**
     * 连通性探测的 token 上限。
     *
     * 为什么不是 1：1 个 token 经常只回半个字甚至空串，界面上就只剩一句"连接正常"，
     * 用户看不出服务端到底有没有正常应答。8 个 token 的成本可忽略（一次约
     * 0.0000x 元），却能把"服务端到底回了什么"摆出来。
     */
    private const val PROBE_MAX_TOKENS = 8

    /** 连通性探测回复的展示长度上限。 */
    private const val PROBE_REPLY_LIMIT = 60

    /**
     * 流式增量的合并窗口（毫秒）。
     *
     * 不能每个 token 都往界面推：讲解面板用 [com.hnnujw.course.ui.system.MarkdownText]
     * 渲染，每次更新都要把已到达的全文重新解析一遍 —— 逐 token 更新等于 O(n²)，
     * 长回答会明显掉帧。60ms 一帧的粒度既看不出卡顿，也把解析次数压到几十次。
     */
    private const val STREAM_FLUSH_MS = 60L

    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    /**
     * 按超时值缓存客户端。
     *
     * 超时是用户可配的，而 [OkHttpClient] 创建时要建连接池/线程池 —— 每次请求新建
     * 一个既浪费又会让连接复用失效。超时值域被夹在 10–600 秒，缓存条目数有上界。
     */
    private val clients = ConcurrentHashMap<Long, OkHttpClient>()

    private fun clientFor(timeoutSeconds: Int): OkHttpClient {
        val seconds = AiConfigRules.clampTimeoutSeconds(timeoutSeconds).toLong()
        return clients.computeIfAbsent(seconds) { s ->
            OkHttpClient.Builder()
                .connectTimeout(minOf(20L, s), TimeUnit.SECONDS)
                .writeTimeout(minOf(20L, s), TimeUnit.SECONDS)
                // 读超时按"两次读之间"计算，因此流式响应下它就是"多久没吐字算卡死"。
                .readTimeout(s, TimeUnit.SECONDS)
                .build()
        }
    }

    // ── 结果类型 ─────────────────────────────────────────────────────────

    /** 一轮问答的结果。[Failure] 里带**可以直接展示给用户**的中文原因。 */
    sealed class Result {
        /**
         * [reasoning] 是推理模型的思考过程（DeepSeek-R1 系的 `reasoning_content`）。
         *
         * 🔴 **必须带回来**：思考过程在流式结束后不能丢 —— 界面要把它收成可点开的一行，
         * 让用户回看"AI 当时是怎么想的"。只在 [Streaming] 阶段有值等于用完即焚。
         */
        data class Success(
            val text: String,
            val usage: AiUsage? = null,
            val reasoning: String = ""
        ) : Result()

        data class Failure(val message: String) : Result()
    }

    /** 「测试连接」的结果。比 [Result] 多带延迟与回复原文，供设置页展示。 */
    data class TestResult(
        val ok: Boolean,
        val latencyMs: Long,
        /** 成功时模型回复原文（已截断为单行）。 */
        val reply: String,
        /** 失败原因（可直接展示）。 */
        val error: String
    )

    /** 一轮对话。role 只用 "user" / "assistant"，与 OpenAI 兼容协议一致。 */
    data class Turn(val role: String, val content: String) {
        companion object {
            fun user(content: String) = Turn("user", content)
            fun assistant(content: String) = Turn("assistant", content)
        }
    }

    // ── 对外入口 ─────────────────────────────────────────────────────────

    /**
     * 问一道题。
     *
     * @param question 题目（题干/选项/正确答案/章节），传给模型做讲解。
     * @param userAnswer 用户本次所选；为空表示用户还没作答就问 AI。
     * @param extraPrompt 用户自己补的追问，可空。
     * @param history 本题此前的对话。🔴 **必须带历史**：不带时每次追问都是全新单轮
     *   请求，AI 不知道上文，用户问「为什么」时它会答非所问。条数封顶见 [HISTORY_LIMIT]。
     * @param onDelta 流式增量（已切回主线程，可直接写 Compose 状态）。
     * @param onReasoning 推理模型的思考增量（DeepSeek-R1 系的 `reasoning_content`）。
     */
    suspend fun askAboutQuestion(
        config: AiConfig,
        question: ReviewQuestion,
        userAnswer: String = "",
        extraPrompt: String = "",
        history: List<Turn> = emptyList(),
        timeoutSeconds: Int = AiConfigRules.DEFAULT_TIMEOUT_SECONDS,
        retries: Int = AiConfigRules.DEFAULT_RETRIES,
        onDelta: (suspend (String) -> Unit)? = null,
        onReasoning: (suspend (String) -> Unit)? = null
    ): Result = ask(
        config = config,
        systemPrompt = SYSTEM_PROMPT,
        userPrompt = buildQuestionPrompt(question, userAnswer, extraPrompt),
        history = history,
        timeoutSeconds = timeoutSeconds,
        retries = retries,
        onDelta = onDelta,
        onReasoning = onReasoning
    )

    /** 自由提问（问知识点、问为什么错等）。 */
    suspend fun askFreeform(
        config: AiConfig,
        prompt: String,
        timeoutSeconds: Int = AiConfigRules.DEFAULT_TIMEOUT_SECONDS,
        retries: Int = AiConfigRules.DEFAULT_RETRIES,
        onDelta: (suspend (String) -> Unit)? = null,
        onReasoning: (suspend (String) -> Unit)? = null
    ): Result = ask(
        config = config,
        systemPrompt = SYSTEM_PROMPT,
        userPrompt = prompt,
        history = emptyList(),
        timeoutSeconds = timeoutSeconds,
        retries = retries,
        onDelta = onDelta,
        onReasoning = onReasoning
    )

    private suspend fun ask(
        config: AiConfig,
        systemPrompt: String,
        userPrompt: String,
        history: List<Turn>,
        timeoutSeconds: Int,
        retries: Int,
        onDelta: (suspend (String) -> Unit)?,
        onReasoning: (suspend (String) -> Unit)?
    ): Result = withContext(Dispatchers.IO) {
        if (config.apiKey.isBlank()) {
            return@withContext Result.Failure("还没有配置 API Key")
        }
        runWithRetry(
            url = AiEndpoint.chatUrl(config.baseUrl),
            client = clientFor(timeoutSeconds),
            model = config.model.ifBlank { AiEndpoint.DEFAULT_MODEL },
            apiKey = config.apiKey,
            systemPrompt = systemPrompt,
            userPrompt = userPrompt,
            history = history,
            maxRetries = AiConfigRules.clampRetries(retries),
            onDelta = onDelta,
            onReasoning = onReasoning
        )
    }

    /**
     * 带重试的请求循环。
     *
     * 🔴 **已经吐出过内容就不再重试**：流式下重试会让用户看到两段半截回答拼在一起，
     * 比直接报错更糟。所以重试只发生在"一个字都还没出来"的时候。
     */
    private suspend fun runWithRetry(
        url: String,
        client: OkHttpClient,
        model: String,
        apiKey: String,
        systemPrompt: String,
        userPrompt: String,
        history: List<Turn>,
        maxRetries: Int,
        onDelta: (suspend (String) -> Unit)?,
        onReasoning: (suspend (String) -> Unit)?
    ): Result {
        var attempt = 0
        var emitted = false
        while (true) {
            val outcome = attemptChat(
                url, client, model, apiKey, systemPrompt, userPrompt, history,
                onDelta = onDelta,
                onReasoning = onReasoning,
                markEmitted = { emitted = true }
            )
            when (outcome) {
                is Attempt.Ok -> return Result.Success(outcome.text, outcome.usage, outcome.reasoning)
                is Attempt.Err -> {
                    val canRetry = !emitted && attempt < maxRetries &&
                        RetryPolicy.shouldRetry(outcome.status, outcome.ioError)
                    if (!canRetry) {
                        return Result.Failure(outcome.message)
                    }
                    attempt++
                    delay(RetryPolicy.retryDelayMs(attempt))
                }
            }
        }
    }

    /** 单次请求的结局。 */
    private sealed interface Attempt {
        /** [reasoning] 一并带出，供界面在流式结束后仍能折叠展示思考过程。 */
        data class Ok(val text: String, val reasoning: String, val usage: AiUsage?) : Attempt

        data class Err(
            val message: String,
            val status: Int?,
            val ioError: Boolean
        ) : Attempt
    }

    private suspend fun attemptChat(
        url: String,
        client: OkHttpClient,
        model: String,
        apiKey: String,
        systemPrompt: String,
        userPrompt: String,
        history: List<Turn>,
        onDelta: (suspend (String) -> Unit)?,
        onReasoning: (suspend (String) -> Unit)?,
        markEmitted: () -> Unit
    ): Attempt {
        // `stream_options` 是可选参数，部分中转站不认（400）。不认就去掉重发一次 ——
        // 代价是这一次拿不到用量统计，但不至于整个功能不可用。
        var includeUsage = true
        while (true) {
            val body = buildChatBody(
                model = model,
                systemPrompt = systemPrompt,
                userPrompt = userPrompt,
                history = history,
                stream = true,
                includeUsage = includeUsage
            )
            val request = try {
                Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Accept", "application/json")
                    .post(body.toString().toRequestBody(JSON_MEDIA))
                    .build()
            } catch (e: Exception) {
                return Attempt.Err("API 地址不合法", null, false)
            }

            var retryWithoutUsage = false
            var outcome: Attempt? = null
            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        val raw = response.body?.string().orEmpty()
                        if (includeUsage && RetryPolicy.shouldRetryWithoutStreamOptions(response.code, raw)) {
                            retryWithoutUsage = true
                        } else {
                            Log.w(TAG, "chat failed http=${response.code} len=${raw.length}")
                            outcome = Attempt.Err(httpMessage(response.code, raw), response.code, false)
                        }
                    } else {
                        val contentType = response.header("Content-Type").orEmpty()
                        val responseBody = response.body
                        outcome = when {
                            responseBody == null ->
                                Attempt.Err("AI 返回内容为空", null, false)
                            contentType.contains("text/event-stream", ignoreCase = true) ->
                                readStream(responseBody, onDelta, onReasoning, markEmitted)
                            // 服务商忽略了 stream 参数：整包 JSON，走非流式解析。
                            else -> readWholeBody(responseBody.string(), onDelta, onReasoning)
                        }
                    }
                }
            } catch (e: IOException) {
                Log.w(TAG, "chat io error: ${e.javaClass.simpleName}")
                outcome = Attempt.Err(
                    "网络请求失败：${e.message ?: "请检查网络后重试"}", null, true
                )
            } catch (e: Exception) {
                Log.w(TAG, "chat error: ${e.javaClass.simpleName}")
                outcome = Attempt.Err(
                    "调用 AI 失败：${e.message ?: e.javaClass.simpleName}", null, false
                )
            }

            if (retryWithoutUsage) {
                includeUsage = false
                continue
            }
            return outcome ?: Attempt.Err("调用 AI 失败", null, false)
        }
    }

    /** 读 SSE 流：逐行喂解析器，按 [STREAM_FLUSH_MS] 合并增量后推给界面。 */
    private suspend fun readStream(
        body: ResponseBody,
        onDelta: (suspend (String) -> Unit)?,
        onReasoning: (suspend (String) -> Unit)?,
        markEmitted: () -> Unit
    ): Attempt {
        val collected = StringBuilder()
        // 思考过程也要整段留一份：流式回调只负责"上屏"，返回结果里带的是给界面折叠回看的。
        val collectedReasoning = StringBuilder()
        // `pending` 必须是 var：flush 里每推完一段就换一个新的 StringBuilder。
        var pending = StringBuilder()
        var pendingReasoning = StringBuilder()
        var usage: AiUsage? = null
        var lastFlush = 0L

        suspend fun flush(force: Boolean) {
            if (pending.isEmpty() && pendingReasoning.isEmpty()) return
            val now = System.currentTimeMillis()
            if (!force && now - lastFlush < STREAM_FLUSH_MS) return
            lastFlush = now
            if (pendingReasoning.isNotEmpty()) {
                val chunk = pendingReasoning.toString()
                pendingReasoning = StringBuilder()
                withContext(Dispatchers.Main.immediate) { onReasoning?.invoke(chunk) }
            }
            if (pending.isNotEmpty()) {
                val chunk = pending.toString()
                pending = StringBuilder()
                withContext(Dispatchers.Main.immediate) { onDelta?.invoke(chunk) }
            }
        }

        val parser = SseParser(
            onContent = { delta ->
                collected.append(delta)
                pending.append(delta)
                markEmitted()
            },
            onReasoning = { delta ->
                collectedReasoning.append(delta)
                pendingReasoning.append(delta)
            },
            onUsage = { usage = it }
        )
        val source = body.source()
        while (true) {
            val line = source.readUtf8Line() ?: break
            parser.feed(line)
            flush(force = false)
        }
        parser.close()
        // 收尾必须强制 flush：最后一段增量常常不足一个窗口，丢了就是"回答少一句"。
        flush(force = true)

        val text = collected.toString()
        return if (text.isBlank()) {
            Attempt.Err("AI 返回内容为空", null, false)
        } else {
            Attempt.Ok(text, collectedReasoning.toString(), usage)
        }
    }

    /** 读整包 JSON（服务商忽略 stream 参数）。整段文本一次推给界面。 */
    private suspend fun readWholeBody(
        raw: String,
        onDelta: (suspend (String) -> Unit)?,
        onReasoning: (suspend (String) -> Unit)?
    ): Attempt {
        val reply = parseChatResponse(raw)
            ?: return Attempt.Err("AI 返回内容为空", null, false)
        if (reply.text.isBlank()) return Attempt.Err("AI 返回内容为空", null, false)
        // 非流式包同样可能带 reasoning_content（服务商忽略 stream 时），一并上屏 + 带出。
        if (reply.reasoning.isNotBlank()) {
            withContext(Dispatchers.Main.immediate) { onReasoning?.invoke(reply.reasoning) }
        }
        withContext(Dispatchers.Main.immediate) { onDelta?.invoke(reply.text) }
        return Attempt.Ok(reply.text, reply.reasoning, reply.usage)
    }

    /**
     * 拉取服务端模型列表。
     *
     * 成功且解析出非空 id 列表时返回 [Result.Success]（内容为换行拼接的 id）；
     * 任何失败或空列表都返回 [Result.Failure]，由 UI 决定是否保留预置清单。
     *
     * 为什么只支持 DeepSeek 了还留这个：模型名**会变**（2026-10 官方刚把
     * `deepseek-chat` 一类旧名对应的模型下线）。留着它，模型改名后用户能自己
     * 拉到真实清单，不必等应用更新。
     */
    suspend fun listModels(
        config: AiConfig,
        timeoutSeconds: Int = AiConfigRules.DEFAULT_TIMEOUT_SECONDS
    ): Result = withContext(Dispatchers.IO) {
        if (config.apiKey.isBlank()) {
            return@withContext Result.Failure("还没有配置 API Key")
        }
        val request = Request.Builder()
            .url(AiEndpoint.modelsUrl(config.baseUrl))
            .addHeader("Authorization", "Bearer ${config.apiKey}")
            .addHeader("Accept", "application/json")
            .get()
            .build()
        try {
            clientFor(timeoutSeconds).newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    Log.w(TAG, "listModels failed http=${response.code}")
                    return@withContext Result.Failure(httpMessage(response.code, raw))
                }
                val ids = parseModelIds(raw)
                if (ids.isEmpty()) {
                    return@withContext Result.Failure("服务端未返回可用的模型列表")
                }
                Result.Success(ids.joinToString("\n"))
            }
        } catch (e: IOException) {
            Log.w(TAG, "listModels io error: ${e.javaClass.simpleName}")
            Result.Failure("网络请求失败：${e.message ?: "请检查网络后重试"}")
        } catch (e: Exception) {
            Log.w(TAG, "listModels error: ${e.javaClass.simpleName}")
            Result.Failure("获取模型列表失败：${e.message ?: e.javaClass.simpleName}")
        }
    }

    /**
     * 测试连通性：发一条**极短**的 chat 请求，只关心鉴权与端点是否可用。
     *
     * 为什么不拿 [listModels] 冒充连通性测试：那只证明 `/models` 鉴权通过，
     * 不证明 `/chat/completions` 可用（配额、模型下线、网关差异都可能让它单独失败）。
     *
     * 与旧版的区别：现在**回报延迟与模型回复**。只说"连接正常"时，用户无法区分
     * "真的通了"和"服务端回了句错误但状态码是 200"；给出延迟还能顺带看出线路质量。
     */
    suspend fun testConnection(
        config: AiConfig,
        timeoutSeconds: Int = AiConfigRules.DEFAULT_TIMEOUT_SECONDS
    ): TestResult = withContext(Dispatchers.IO) {
        if (config.apiKey.isBlank()) {
            return@withContext TestResult(false, 0, "", "还没有配置 API Key")
        }
        val model = config.model.ifBlank { AiEndpoint.DEFAULT_MODEL }
        val started = System.nanoTime()
        fun elapsedMs() = (System.nanoTime() - started) / 1_000_000
        val body = JSONObject().apply {
            put("model", model)
            put("stream", false)
            put("max_tokens", PROBE_MAX_TOKENS)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", "hi")
                })
            })
        }
        val request = Request.Builder()
            .url(AiEndpoint.chatUrl(config.baseUrl))
            .addHeader("Authorization", "Bearer ${config.apiKey}")
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "application/json")
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .build()
        try {
            clientFor(timeoutSeconds).newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                val ms = elapsedMs()
                if (!response.isSuccessful) {
                    Log.w(TAG, "testConnection failed http=${response.code}")
                    TestResult(false, ms, "", httpMessage(response.code, raw))
                } else {
                    TestResult(true, ms, replySnippet(parseChatResponse(raw)?.text.orEmpty()), "")
                }
            }
        } catch (e: IOException) {
            Log.w(TAG, "testConnection io error: ${e.javaClass.simpleName}")
            TestResult(false, elapsedMs(), "", "网络请求失败：${e.message ?: "请检查网络后重试"}")
        } catch (e: Exception) {
            Log.w(TAG, "testConnection error: ${e.javaClass.simpleName}")
            TestResult(false, elapsedMs(), "", "测试连接失败：${e.message ?: e.javaClass.simpleName}")
        }
    }

    // ── 协议解析（纯函数，可脱离网络单测） ────────────────────────────────

    /** 解析出的整包回复。 */
    internal data class ChatReply(val text: String, val reasoning: String, val usage: AiUsage?)

    /**
     * 解析非流式响应：取 `choices[0].message.content`，顺带 `reasoning_content` 与 `usage`。
     *
     * 结构不符一律返回 null，**绝不抛** —— 网关返回 HTML 错误页、返回
     * `{"error":...}` 都是常态，抛出去就是闪退。
     */
    internal fun parseChatResponse(raw: String): ChatReply? {
        if (raw.isBlank()) return null
        val root = try {
            JSONObject(raw)
        } catch (e: Exception) {
            return null
        }
        val choices = root.optJSONArray("choices") ?: return null
        val message = choices.optJSONObject(0)?.optJSONObject("message") ?: return null
        return ChatReply(
            text = message.stringOrEmpty("content"),
            reasoning = message.stringOrEmpty("reasoning_content"),
            usage = parseUsage(root.optJSONObject("usage"))
        )
    }

    /**
     * 从 `GET /models` 响应里取出模型 id 列表（**去重 + 字典序**）。
     *
     * 期望结构：`{"object":"list","data":[{"id":"deepseek-chat",...},...]}`。
     * **容错是本方法的唯一职责**：`data` 缺失 / 为 null / 非数组、元素缺 `id`、
     * 响应不是 JSON、空串 —— 一律返回空列表，**绝不抛异常**。
     * 上层（[listModels] / UI）看到空列表就退化到内置候选。
     *
     * 排序是为了让界面上的模型芯片**顺序稳定**：服务端返回顺序不保证，不排的话
     * 同一个账号两次点「获取模型列表」得到的芯片顺序可能不同，看着像在乱跳。
     */
    internal fun parseModelIds(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()
        return try {
            val data = JSONObject(raw).optJSONArray("data") ?: return emptyList()
            val out = LinkedHashSet<String>(data.length())
            for (i in 0 until data.length()) {
                val id = data.optJSONObject(i)?.stringOrEmpty("id").orEmpty().trim()
                if (id.isNotEmpty()) out.add(id)
            }
            out.sorted()
        } catch (e: Exception) {
            // 非 JSON / 结构异常：只记类别，不打原始响应（可能含敏感信息）。
            Log.w(TAG, "parseModelIds failed: ${e.javaClass.simpleName}")
            emptyList()
        }
    }

    /** 单个 SSE `data:` 载荷里的增量。 */
    internal data class SseDelta(val reasoning: String, val content: String)

    /**
     * 解析单个 SSE data 载荷（已去掉 `data:` 前缀）：返回该 chunk 的
     * content / reasoning 增量；`[DONE]`、空 choices（usage 尾包）或
     * 非 JSON 载荷返回 null（跳过，不视为错误）。
     */
    internal fun parseSseDelta(payload: String): SseDelta? {
        if (payload.trim() == "[DONE]") return null
        val root = try {
            JSONObject(payload)
        } catch (e: Exception) {
            return null
        }
        val choices = root.optJSONArray("choices") ?: return null
        if (choices.length() == 0) return null
        val delta = choices.optJSONObject(0)?.optJSONObject("delta") ?: return null
        // ⚠️ 必须走 stringOrEmpty：首包 `"content": null`（只带 role）用 optString
        // 会得到字符串 "null"，那四个字母会被当成正文吐到界面上。
        val content = delta.stringOrEmpty("content")
        val reasoning = delta.stringOrEmpty("reasoning_content")
        if (content.isEmpty() && reasoning.isEmpty()) return null
        return SseDelta(reasoning, content)
    }

    /** SSE 尾包 / 非流式响应里的 token 用量。结构不符返回 null。 */
    internal fun parseSseUsage(payload: String): AiUsage? {
        val root = try {
            JSONObject(payload)
        } catch (e: Exception) {
            return null
        }
        return parseUsage(root.optJSONObject("usage"))
    }

    private fun parseUsage(usage: JSONObject?): AiUsage? {
        if (usage == null) return null
        val prompt = usage.optInt("prompt_tokens", -1)
        val completion = usage.optInt("completion_tokens", -1)
        val total = usage.optInt("total_tokens", -1)
        if (prompt < 0 || completion < 0 || total < 0) return null
        return AiUsage(prompt, completion, total)
    }

    /**
     * SSE 行流解析器：喂入原始响应行，产出 content / reasoning 增量回调。
     *
     * 空行 = 事件边界（多行 `data:` 拼接为一个载荷），[close] 冲刷流末未遇边界的尾包。
     * 其余 SSE 字段（`event:` / `id:` / 注释行）与 `[DONE]`、非 JSON 载荷一律忽略。
     */
    internal class SseParser(
        private val onContent: ((String) -> Unit)? = null,
        private val onReasoning: ((String) -> Unit)? = null,
        private val onUsage: ((AiUsage) -> Unit)? = null
    ) {
        private val buffer = ArrayList<String>()

        fun feed(line: String) {
            if (line.isEmpty()) {
                flush()
                return
            }
            if (line.startsWith("data:")) buffer.add(line.substring(5).trimStart())
        }

        fun close() = flush()

        private fun flush() {
            if (buffer.isEmpty()) return
            // 直接拼接而非按 SSE 规范插 \n：chat/completions 的 JSON 载荷内不会出现
            // 裸换行（换行均为 \n 转义），代理拆行时直接连接才能还原。
            val payload = buffer.joinToString("")
            buffer.clear()
            val delta = parseSseDelta(payload)
            if (delta == null) {
                parseSseUsage(payload)?.let { onUsage?.invoke(it) }
                return
            }
            if (delta.reasoning.isNotEmpty()) onReasoning?.invoke(delta.reasoning)
            if (delta.content.isNotEmpty()) onContent?.invoke(delta.content)
        }
    }

    /** 把 HTTP 错误翻成人话。优先透出服务端自己给的 `error.message`（它比任何自造文案都准）。 */
    private fun httpMessage(code: Int, raw: String): String {
        val fromApi = try {
            JSONObject(raw).optJSONObject("error")?.stringOrEmpty("message").orEmpty()
        } catch (e: Exception) {
            ""
        }
        if (fromApi.isNotBlank()) return fromApi
        return when (code) {
            401, 403 -> "API Key 无效或已失效，请检查后重试"
            402 -> "账户余额不足，请前往平台充值"
            404 -> "端点不存在（HTTP 404），请检查 API 地址是否写错"
            429 -> "请求过于频繁，请稍后再试"
            in 500..599 -> "AI 服务暂时不可用（$code），请稍后再试"
            else -> "请求失败（HTTP $code）"
        }
    }

    /** 把探测回复压成单行短文本。空串原样返回（界面只显示延迟）。 */
    internal fun replySnippet(raw: String, limit: Int = PROBE_REPLY_LIMIT): String {
        val oneLine = raw.replace(Regex("\\s+"), " ").trim()
        return if (oneLine.length > limit) oneLine.take(limit) + "…" else oneLine
    }

    /** `JSONObject.optString` 在值是 JSON `null` 时会返回字符串 "null"，必须挡掉。 */
    private fun JSONObject.stringOrEmpty(key: String): String {
        val value = opt(key)
        return if (value is String) value else ""
    }

    // ── 请求体 ───────────────────────────────────────────────────────────

    private fun buildChatBody(
        model: String,
        systemPrompt: String,
        userPrompt: String,
        history: List<Turn>,
        stream: Boolean,
        includeUsage: Boolean
    ): JSONObject = JSONObject().apply {
        put("model", model)
        put("stream", stream)
        put("temperature", 0.3)
        put("max_tokens", MAX_TOKENS)
        if (includeUsage) {
            put("stream_options", JSONObject().apply { put("include_usage", true) })
        }
        put("messages", JSONArray().apply {
            put(JSONObject().apply {
                put("role", "system")
                put("content", systemPrompt)
            })
            // 历史在前、当前提问在后 —— 顺序反了模型会把当前问题当成回顾。
            history.takeLast(HISTORY_LIMIT).forEach { turn ->
                put(JSONObject().apply {
                    put("role", turn.role)
                    put("content", turn.content)
                })
            }
            put(JSONObject().apply {
                put("role", "user")
                put("content", userPrompt)
            })
        })
    }

    private const val SYSTEM_PROMPT =
        "你是一位耐心的学习助手，正在为学生讲解一道练习题。" +
            "请用简体中文回答，条理清晰、直接给出结论，不要寒暄。" +
            "格式要求：先给一句话结论，再用简短的分点展开；篇幅控制在 300 字以内。"

    /** 把一道题拼成给模型的提问文本。 */
    fun buildQuestionPrompt(
        question: ReviewQuestion,
        userAnswer: String = "",
        extraPrompt: String = ""
    ): String = buildString {
        append("题目类型：").append(if (question.isMulti) "多选题" else "单选题").append('\n')
        if (question.chapter.isNotBlank()) append("所属章节：").append(question.chapter).append('\n')
        append("题干：").append(question.stem).append('\n')
        if (question.options.isNotEmpty()) {
            append("选项：\n")
            question.options.forEachIndexed { i, opt ->
                append(('A' + i)).append(". ").append(opt).append('\n')
            }
        }
        append("正确答案：").append(question.answer).append('\n')
        if (userAnswer.isNotBlank()) {
            val right = userAnswer == question.answer
            append("我的作答：").append(userAnswer)
                .append(if (right) "（回答正确）" else "（回答错误）").append('\n')
            if (!right) append("请重点分析：我为什么会选错，以及正确选项为什么对。\n")
        }
        if (question.explanation.isNotBlank()) {
            append("题库已有解析（可参考但请用自己的话讲）：").append(question.explanation).append('\n')
        }
        if (extraPrompt.isNotBlank()) {
            append("我还想问：").append(extraPrompt).append('\n')
        }
        if (userAnswer.isBlank()) {
            append("请帮我讲解这道题的考查点和解题思路。")
        }
    }.trim()

    /**
     * 输入框里的**预填文案**：把当前题目的关键信息摆出来，让用户「看到哪题就聊哪题」。
     *
     * 2026-10 用户要求：「AI 配置的时候，用户看到哪题，软件就将题目作为提示词，
     * 然后等用户补充发送」。所以打开 AI 面板时输入框不是空的 —— 已经带上了这道题，
     * 用户可以在后面接着写「为什么选 B」「这个考点在哪章」再发送。
     *
     * 只放**题干摘要 + 我的作答**，不放正确答案：答案由 [buildQuestionPrompt]
     * 在真正发请求时附给模型，输入框里提前剧透会让用户以为自己已经知道答案了。
     */
    fun buildPrefillText(question: ReviewQuestion, userAnswer: String = ""): String =
        buildString {
            // 真截断，不是「原样贴完再补个省略号」—— 后者对超长题干毫无作用，
            // 200 字的题干会整整塞进输入框，把用户自己要写的内容挤到屏幕外。
            append(
                if (question.stem.length > PREFILL_STEM_LIMIT) {
                    question.stem.take(PREFILL_STEM_LIMIT) + "…"
                } else {
                    question.stem
                }
            )
            if (userAnswer.isNotBlank()) append("\n（我选了 ").append(userAnswer).append("）")
            append("\n\n我想问：")
        }

    /**
     * 把输入框里**预填的题干部分剥掉**，只留用户自己写的补充。
     *
     * 剥不干净会有两个后果：一是题干被当成用户提问重复发一遍（白烧 token），
     * 二是模型看到「我还想问：中国近现代史…」这种话会答非所问。
     *
     * 做法是原样重建 [buildPrefillText] 的前缀再按长度截掉 —— 比"找 '我想问：'
     * 最后一个位置"稳，用户若在前面正文里也写了「我想问：」不会误伤。
     */
    fun stripPrefill(input: String, question: ReviewQuestion, userAnswer: String = ""): String {
        val prefix = buildPrefillText(question, userAnswer)
        return input.removePrefix(prefix).trim()
    }

    /**
     * 重试策略（纯函数，可单测）。
     *
     * 只重试**可能自愈**的失败：
     *  - IO 异常（网络抖动、超时）：值得再试；
     *  - 429（限流）：退避后再试有意义；
     *  - 5xx（服务端故障）：值得再试。
     *
     * 不重试 4xx 的其它码：401/403 是 Key 的问题、404 是地址写错、402 是没钱 ——
     * 原地重试 100 次也还是同一个结果，只会让用户多等好几秒。上一版这类失败会
     * 交给主备降级（换一条档案 = 换 Key/端点）处理；只支持 DeepSeek 后没有"另一条
     * 档案"可换，直接报错让用户去改 Key 才是唯一有意义的出口。
     */
    object RetryPolicy {
        fun shouldRetry(status: Int?, ioError: Boolean): Boolean {
            if (ioError) return true
            val code = status ?: return false
            return code == 429 || code in 500..599
        }

        /**
         * 退避延迟。线性而非指数：单题答疑是**交互式**的，等 2s/4s/8s 用户早就退出了；
         * 600ms 起步足够躲开瞬时抖动，重试上限也小（见 [AiConfigRules.RETRIES_MAX]）。
         */
        fun retryDelayMs(attempt: Int): Long = (600L * attempt).coerceIn(0L, 3_000L)

        /**
         * 服务商不认 `stream_options`（400 且报文里点名）→ 去掉该参数重发。
         *
         * 为什么要判报文而不是只看 400：400 的原因五花八门（模型名错、参数越界），
         * 一律重发等于把真正的错误重试一遍，还把报错信息盖掉。
         */
        fun shouldRetryWithoutStreamOptions(status: Int, body: String): Boolean =
            status == 400 && body.contains("stream_options", ignoreCase = true)
    }
}

/** 一次请求的 token 用量。服务端不返回时为 null（不是 0）。 */
data class AiUsage(
    val promptTokens: Int,
    val completionTokens: Int,
    val totalTokens: Int
) {
    operator fun plus(other: AiUsage): AiUsage = AiUsage(
        promptTokens = promptTokens + other.promptTokens,
        completionTokens = completionTokens + other.completionTokens,
        totalTokens = totalTokens + other.totalTokens
    )

    /** 界面上的一行摘要：本轮用量。 */
    fun summary(): String = "本次 $totalTokens tokens（输入 $promptTokens / 输出 $completionTokens）"

    /**
     * 界面上的一行摘要：本题累计用量。
     *
     * 追问是常态（用户会连问"为什么""再讲细点"），只看单轮用量看不出这一道题
     * 一共烧了多少 token，故与 [summary] 并列展示。
     */
    fun cumulative(): String = "本题累计 $totalTokens tokens"

    companion object {
        /** 累加起点。⚠️ 只用于 [plus] 的初值 —— "服务端没返回用量"仍由可空类型表达。 */
        val Zero = AiUsage(0, 0, 0)
    }
}
