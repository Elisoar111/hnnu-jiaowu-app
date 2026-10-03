package com.hnnujw.course.review

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * DeepSeek Chat API 客户端（OpenAI 兼容协议）。
 *
 * 端点：`POST https://api.deepseek.com/chat/completions`，`Authorization: Bearer <key>`。
 * https 走公共 CA，不需要在 network_security_config.xml 里登记明文例外
 * （那里只放教务/学工那两个确实只有 http 的站点）。
 *
 * 隐私红线：
 *  - API Key **只出现在 Authorization 头**，绝不进日志（连异常 message 都要小心，
 *    OkHttp 的异常里可能带请求头，所以这里不打整个 exception，只打自己的文案）。
 *  - 只有用户主动点「问 AI」才会把题目发出去；默认不发。
 */
object AiChatClient {
    private const val TAG = "AiChatClient"
    const val BASE_URL = "https://api.deepseek.com"
    private const val PATH_CHAT = "/chat/completions"
    private const val PATH_MODELS = "/models"

    /**
     * 服务端模型列表不可用时的兜底候选。
     *
     * `GET /models` 是 DeepSeek 文档里的端点，但**不保证一定可用**（网关改动、
     * 返回结构变化都可能）。拿不到时不能让用户卡在空列表上，直接列这两个常用模型。
     */
    val FALLBACK_MODELS = listOf("deepseek-chat", "deepseek-reasoner")

    /**
     * 最多带多少轮历史。
     *
     * 8 轮足够覆盖一道题的追问（讲不清 → 追问 → 再纠错…），又不会把请求撑爆。
     * 再长对单题讲解也没意义，用户早就该换下一题了。
     */
    const val HISTORY_LIMIT = 8

    /** 预填到输入框里的题干最大字数，超出部分截断。 */
    const val PREFILL_STEM_LIMIT = 60

    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    /** 一轮问答的结果。[Failure] 里带**可以直接展示给用户**的中文原因。 */
    sealed class Result {
        data class Success(val text: String) : Result()
        data class Failure(val message: String) : Result()
    }

    /**
     * 问一道题。
     *
     * @param question 题目（题干/选项/正确答案/章节），传给模型做讲解。
     * @param userAnswer 用户本次所选；为空表示用户还没作答就问 AI。
     * @param extraPrompt 用户自己补的追问，可空。
     * @param history 本题此前的对话（AI 上一轮说了什么、用户又问了什么）。
     *   🔴 **必须带历史**（2026-10 用户要求「AI 配置时把题目作为提示词，等用户补充发送」）：
     *   不带历史时每次追问都是**全新单轮请求**，AI 不知道上文，
     *   用户「继续追问」问「为什么」时它会答非所问。历史条数封顶（见 HISTORY_LIMIT），
     *   防止长对话把 token 撑爆。
     */
    suspend fun askAboutQuestion(
        apiKey: String,
        model: String,
        question: ReviewQuestion,
        userAnswer: String = "",
        extraPrompt: String = "",
        history: List<Turn> = emptyList()
    ): Result = ask(
        apiKey = apiKey,
        model = model,
        systemPrompt = SYSTEM_PROMPT,
        userPrompt = buildQuestionPrompt(question, userAnswer, extraPrompt),
        history = history
    )

    /** 自由提问（问知识点、问为什么错等）。 */
    suspend fun askFreeform(apiKey: String, model: String, prompt: String): Result =
        ask(apiKey, model, SYSTEM_PROMPT, prompt)

    /** 一轮对话。role 只用 "user" / "assistant"，与 OpenAI 兼容协议一致。 */
    data class Turn(val role: String, val content: String) {
        companion object {
            fun user(content: String) = Turn("user", content)
            fun assistant(content: String) = Turn("assistant", content)
        }
    }

    private suspend fun ask(
        apiKey: String,
        model: String,
        systemPrompt: String,
        userPrompt: String,
        history: List<Turn> = emptyList()
    ): Result = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext Result.Failure("还没有配置 API Key")
        val body = JSONObject().apply {
            put("model", model.ifBlank { AiKeyStore.DEFAULT_MODEL })
            put("stream", false)
            put("temperature", 0.3)
            put("max_tokens", 1200)
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
        val request = Request.Builder()
            .url(BASE_URL + PATH_CHAT)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "application/json")
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    Log.w(TAG, "chat failed http=${response.code} len=${raw.length}")
                    return@withContext Result.Failure(httpMessage(response.code, raw))
                }
                val text = parseContent(raw)
                if (text.isBlank()) Result.Failure("AI 返回内容为空") else Result.Success(text)
            }
        } catch (e: IOException) {
            Log.w(TAG, "chat io error: ${e.javaClass.simpleName}")
            Result.Failure("网络请求失败：${e.message ?: "请检查网络后重试"}")
        } catch (e: Exception) {
            Log.w(TAG, "chat error: ${e.javaClass.simpleName}")
            Result.Failure("调用 AI 失败：${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** 取 `choices[0].message.content`。 */
    private fun parseContent(raw: String): String {
        val root = JSONObject(raw)
        val choices = root.optJSONArray("choices") ?: return ""
        val first = choices.optJSONObject(0) ?: return ""
        val message = first.optJSONObject("message") ?: return ""
        return message.optString("content")
    }

    /**
     * 从 `GET /models` 响应里取出模型 id 列表。
     *
     * 期望结构：`{"object":"list","data":[{"id":"deepseek-chat",...},...]}`。
     * **容错是本方法的唯一职责**：`data` 缺失 / 为 null / 非数组、元素缺 `id`、
     * 响应不是 JSON、空串 —— 一律返回空列表，**绝不抛异常**。
     * 上层（[listModels] / UI）看到空列表就退化到 [FALLBACK_MODELS]。
     *
     * 抽成纯函数是为了能脱离网络单测这几条退化路径。
     */
    internal fun parseModelIds(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()
        return try {
            val data = JSONObject(raw).optJSONArray("data") ?: return emptyList()
            val out = ArrayList<String>(data.length())
            for (i in 0 until data.length()) {
                val id = data.optJSONObject(i)?.optString("id").orEmpty().trim()
                if (id.isNotEmpty()) out.add(id)
            }
            out
        } catch (e: Exception) {
            // 非 JSON / 结构异常：只记类别，不打原始响应（可能含敏感信息）。
            Log.w(TAG, "parseModelIds failed: ${e.javaClass.simpleName}")
            emptyList()
        }
    }

    /**
     * 拉取服务端模型列表。
     *
     * 成功且解析出非空 id 列表时返回 [Result.Success]（内容为换行拼接的 id）；
     * 任何失败或空列表都返回 [Result.Failure]，由 UI 决定是否退化到 [FALLBACK_MODELS]。
     */
    suspend fun listModels(apiKey: String): Result = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext Result.Failure("还没有配置 API Key")
        val request = Request.Builder()
            .url(BASE_URL + PATH_MODELS)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Accept", "application/json")
            .get()
            .build()
        try {
            client.newCall(request).execute().use { response ->
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
     * 🔴 `max_tokens = 1`：只验证 2xx 与错误信息，不产出实质内容 ——
     * 绝不能复用 [ask] 的 1200，那是在烧用户的钱包。
     */
    suspend fun testConnection(apiKey: String, model: String): Result = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext Result.Failure("还没有配置 API Key")
        val effectiveModel = model.ifBlank { AiKeyStore.DEFAULT_MODEL }
        val body = JSONObject().apply {
            put("model", effectiveModel)
            put("stream", false)
            put("max_tokens", 1)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", "hi")
                })
            })
        }
        val request = Request.Builder()
            .url(BASE_URL + PATH_CHAT)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "application/json")
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .build()
        try {
            client.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    Log.w(TAG, "testConnection failed http=${response.code}")
                    return@withContext Result.Failure(httpMessage(response.code, raw))
                }
                Result.Success(effectiveModel)
            }
        } catch (e: IOException) {
            Log.w(TAG, "testConnection io error: ${e.javaClass.simpleName}")
            Result.Failure("网络请求失败：${e.message ?: "请检查网络后重试"}")
        } catch (e: Exception) {
            Log.w(TAG, "testConnection error: ${e.javaClass.simpleName}")
            Result.Failure("测试连接失败：${e.message ?: e.javaClass.simpleName}")
        }
    }

    /**
     * 把 HTTP 错误翻成人话。
     *
     * 401/403 = key 无效；402 = 余额不足；429 = 限流；5xx = 服务端故障。
     * 优先透出 DeepSeek 自己给的 `error.message`（它比任何自造文案都准）。
     */
    private fun httpMessage(code: Int, raw: String): String {
        val fromApi = try {
            JSONObject(raw).optJSONObject("error")?.optString("message").orEmpty()
        } catch (e: Exception) {
            ""
        }
        if (fromApi.isNotBlank()) return fromApi
        return when (code) {
            401, 403 -> "API Key 无效或已失效，请检查后重试"
            402 -> "账户余额不足，请前往平台充值"
            429 -> "请求过于频繁，请稍后再试"
            in 500..599 -> "AI 服务暂时不可用（$code），请稍后再试"
            else -> "请求失败（HTTP $code）"
        }
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
}
