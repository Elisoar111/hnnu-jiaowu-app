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
     */
    suspend fun askAboutQuestion(
        apiKey: String,
        model: String,
        question: ReviewQuestion,
        userAnswer: String = "",
        extraPrompt: String = ""
    ): Result = ask(
        apiKey = apiKey,
        model = model,
        systemPrompt = SYSTEM_PROMPT,
        userPrompt = buildQuestionPrompt(question, userAnswer, extraPrompt)
    )

    /** 自由提问（问知识点、问为什么错等）。 */
    suspend fun askFreeform(apiKey: String, model: String, prompt: String): Result =
        ask(apiKey, model, SYSTEM_PROMPT, prompt)

    private suspend fun ask(
        apiKey: String,
        model: String,
        systemPrompt: String,
        userPrompt: String
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
}
