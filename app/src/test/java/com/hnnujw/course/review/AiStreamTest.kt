package com.hnnujw.course.review

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 流式协议解析 + 端到端（MockWebServer）回归测试。
 *
 * 为什么必须端到端跑一次：SSE 的坑几乎全在"真实字节怎么切"上 —— 首包
 * `content: null`、尾包 `choices: []` 只带 usage、`data:` 被代理拆成多行、
 * 服务商不认 `stream_options`。这些用纯函数单测钉住解析，再用一次真 HTTP
 * 钉住"读流 + 重发"这条链路。
 *
 * 客户端会把增量回调**切到主线程**（Compose 状态只能在主线程写），所以这里
 * 必须装一个测试用的 Main 调度器；用 Unconfined 让回调就地执行，避免死等。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AiStreamTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
        Dispatchers.resetMain()
    }

    private fun config(model: String = AiEndpoint.DEFAULT_MODEL) = AiConfig(
        // 🔴 仍然显式写 127.0.0.1，不要用 `server.url("/").toString()` ——
        // 它的 host 取自 `InetSocketAddress.getHostName()`，在 Windows 上是**本机机器名**
        // （反向 DNS）。上一版之所以必须这么写，是因为 `AiEndpoint.validate` 会把
        // "非环回的明文 http" 直接拒掉，于是所有端到端测试都栽在校验上、而不是被测的
        // 请求/解析逻辑上（2026-10 实测：10 项失败里 8 项是这一条）。校验层已随
        // 「只支持 DeepSeek」删除，JVM 单测也不再受 network_security_config 约束，
        // 但写死环回地址仍是更稳的做法：端口随 MockWebServer 走，host 不该有第二种可能。
        baseUrl = "http://127.0.0.1:${server.port}",
        apiKey = "sk-test",
        model = model
    )

    private fun sse(vararg chunks: String) = chunks.joinToString("")

    // ── 纯解析 ───────────────────────────────────────────────────────────

    @Test
    fun `增量解析：正常 content 与 reasoning`() {
        val content = AiChatClient.parseSseDelta("""{"choices":[{"delta":{"content":"你好"}}]}""")
        assertEquals("你好", content?.content)
        assertEquals("", content?.reasoning)

        val reasoning = AiChatClient.parseSseDelta("""{"choices":[{"delta":{"reasoning_content":"嗯…"}}]}""")
        assertEquals("嗯…", reasoning?.reasoning)
        assertEquals("", reasoning?.content)
    }

    @Test
    fun `增量解析：content 为 JSON null 时不产出字符串 null`() {
        // 首包只带 role，content 是 JSON null。optString 会给出字符串 "null"，
        // 那四个字母会被当成正文吐到界面上 —— 这是最典型的 SSE 踩坑。
        val delta = AiChatClient.parseSseDelta("""{"choices":[{"delta":{"role":"assistant","content":null}}]}""")
        assertNull(delta)
    }

    @Test
    fun `增量解析：DONE 空 choices 非 JSON 一律返回 null 且不抛`() {
        assertNull(AiChatClient.parseSseDelta("[DONE]"))
        assertNull(AiChatClient.parseSseDelta("""{"choices":[]}"""))
        assertNull(AiChatClient.parseSseDelta("<html>502</html>"))
        assertNull(AiChatClient.parseSseDelta(""))
        assertNull(AiChatClient.parseSseDelta("""{"choices":[{"delta":{}}]}"""))
    }

    @Test
    fun `用量解析：尾包 usage 能取到 缺字段则 null`() {
        val usage = AiChatClient.parseSseUsage(
            """{"choices":[],"usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15}}"""
        )
        assertEquals(10, usage?.promptTokens)
        assertEquals(5, usage?.completionTokens)
        assertEquals(15, usage?.totalTokens)

        assertNull(AiChatClient.parseSseUsage("""{"choices":[]}"""))
        assertNull(AiChatClient.parseSseUsage("""{"usage":{"prompt_tokens":1}}"""))
        assertNull(AiChatClient.parseSseUsage("not json"))
    }

    @Test
    fun `用量相加与摘要`() {
        val a = AiUsage(1, 2, 3)
        val b = AiUsage(10, 20, 30)
        assertEquals(AiUsage(11, 22, 33), a + b)
        assertTrue(a.summary().contains("3"))
    }

    @Test
    fun `SSE 行流：空行是事件边界 多行 data 拼接为一个载荷`() {
        val contents = ArrayList<String>()
        val parser = AiChatClient.SseParser(onContent = { contents.add(it) })
        parser.feed("event: message")
        // 载荷被代理拆成两行 —— 必须直接拼接（JSON 里的换行都是 \n 转义）
        parser.feed("""data: {"choices":[{"delta":{"content":"AB""")
        parser.feed("""data: "}}]}""")
        parser.feed("")
        parser.feed("data: [DONE]")
        parser.feed("")
        parser.close()
        assertEquals(listOf("AB"), contents)
    }

    @Test
    fun `SSE 行流：close 冲刷流末未遇边界的尾包`() {
        // 有些服务端最后一段不带结尾空行；不冲刷就会丢掉最后一句回答。
        val contents = ArrayList<String>()
        val parser = AiChatClient.SseParser(onContent = { contents.add(it) })
        parser.feed("""data: {"choices":[{"delta":{"content":"尾巴"}}]}""")
        parser.close()
        assertEquals(listOf("尾巴"), contents)
    }

    @Test
    fun `整包解析：取 message 内容与用量 结构不符返回 null`() {
        val reply = AiChatClient.parseChatResponse(
            """{"choices":[{"message":{"content":"答案","reasoning_content":"想"}}],
               "usage":{"prompt_tokens":3,"completion_tokens":4,"total_tokens":7}}"""
        )
        assertEquals("答案", reply?.text)
        assertEquals("想", reply?.reasoning)
        assertEquals(7, reply?.usage?.totalTokens)

        assertNull(AiChatClient.parseChatResponse(""))
        assertNull(AiChatClient.parseChatResponse("<html>502</html>"))
        assertNull(AiChatClient.parseChatResponse("""{"error":{"message":"bad"}}"""))
    }

    @Test
    fun `探测回复压成单行并截断`() {
        // 换行等空白压成**单个空格**（而不是直接删掉）：删掉会把英文单词粘成
        // "helloworld"，压成空格才是"单行"该有的样子。所以中文之间会留一个空格，
        // 这是有意为之，不是 bug。
        assertEquals("你好 世界", AiChatClient.replySnippet("  你好\n世界  "))
        val long = "x".repeat(200)
        val snippet = AiChatClient.replySnippet(long, limit = 10)
        assertEquals(11, snippet.length)
        assertTrue(snippet.endsWith("…"))
    }

    // ── 重试策略 ─────────────────────────────────────────────────────────

    @Test
    fun `重试只覆盖可能自愈的失败`() {
        assertTrue(AiChatClient.RetryPolicy.shouldRetry(null, ioError = true))
        assertTrue(AiChatClient.RetryPolicy.shouldRetry(429, ioError = false))
        assertTrue(AiChatClient.RetryPolicy.shouldRetry(500, ioError = false))
        assertTrue(AiChatClient.RetryPolicy.shouldRetry(503, ioError = false))
        // 这些原地重试 100 次还是同一个结果，只会让用户多等几秒。
        assertFalse(AiChatClient.RetryPolicy.shouldRetry(401, ioError = false))
        assertFalse(AiChatClient.RetryPolicy.shouldRetry(403, ioError = false))
        assertFalse(AiChatClient.RetryPolicy.shouldRetry(404, ioError = false))
        assertFalse(AiChatClient.RetryPolicy.shouldRetry(null, ioError = false))
    }

    @Test
    fun `退避延迟线性且有上限`() {
        assertEquals(600L, AiChatClient.RetryPolicy.retryDelayMs(1))
        assertEquals(1200L, AiChatClient.RetryPolicy.retryDelayMs(2))
        assertEquals(3000L, AiChatClient.RetryPolicy.retryDelayMs(9))
    }

    @Test
    fun `只有 400 且报文点名 stream_options 才去掉该参数重发`() {
        assertTrue(
            AiChatClient.RetryPolicy.shouldRetryWithoutStreamOptions(
                400, """{"error":{"message":"unknown parameter: stream_options"}}"""
            )
        )
        // 400 的原因五花八门，一律重发等于把真正的错误重试一遍还盖掉报错。
        assertFalse(
            AiChatClient.RetryPolicy.shouldRetryWithoutStreamOptions(
                400, """{"error":{"message":"model not found"}}"""
            )
        )
        assertFalse(
            AiChatClient.RetryPolicy.shouldRetryWithoutStreamOptions(
                500, "stream_options boom"
            )
        )
    }

    // ── 端到端 ───────────────────────────────────────────────────────────

    @Test
    fun `端到端：SSE 流式取回全文 增量回调与用量都对`() = runBlocking {
        val body = sse(
            "data: {\"choices\":[{\"delta\":{\"role\":\"assistant\",\"content\":null}}]}\n",
            "\n",
            "data: {\"choices\":[{\"delta\":{\"content\":\"这是\"}}]}\n",
            "\n",
            "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"先想想\"}}]}\n",
            "\n",
            "data: {\"choices\":[{\"delta\":{\"content\":\"答案\"}}]}\n",
            "\n",
            "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5,\"total_tokens\":15}}\n",
            "\n",
            "data: [DONE]\n",
            "\n"
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(body)
        )

        val deltas = ArrayList<String>()
        val reasonings = ArrayList<String>()
        val result = AiChatClient.askFreeform(
            config = config(),
            prompt = "讲讲这题",
            onDelta = { deltas.add(it) },
            onReasoning = { reasonings.add(it) }
        )

        assertTrue("期望成功，实际 $result", result is AiChatClient.Result.Success)
        val success = result as AiChatClient.Result.Success
        assertEquals("这是答案", success.text)
        assertEquals(15, success.usage?.totalTokens)
        assertEquals("这是答案", deltas.joinToString(""))
        assertEquals("先想想", reasonings.joinToString(""))
        // 请求体里应带上流式与用量开关
        val sent = server.takeRequest().body.readUtf8()
        assertTrue(sent.contains("\"stream\":true"))
        assertTrue(sent.contains("stream_options"))
    }

    @Test
    fun `端到端：服务商忽略 stream 返回整包 JSON 时按非流式解析`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"choices":[{"message":{"content":"整包答案"}}],
                       "usage":{"prompt_tokens":1,"completion_tokens":2,"total_tokens":3}}"""
                )
        )
        val deltas = ArrayList<String>()
        val result = AiChatClient.askFreeform(
            config = config(),
            prompt = "讲讲这题",
            onDelta = { deltas.add(it) }
        )
        assertEquals("整包答案", (result as AiChatClient.Result.Success).text)
        assertEquals(3, result.usage?.totalTokens)
        // 非流式也要把全文推给界面一次，否则面板永远停在"正在思考…"。
        assertEquals(listOf("整包答案"), deltas)
    }

    @Test
    fun `端到端：400 点名 stream_options 时去掉该参数重发一次`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(400)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"error":{"message":"unknown parameter: stream_options"}}""")
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    sse(
                        "data: {\"choices\":[{\"delta\":{\"content\":\"重发成功\"}}]}\n",
                        "\n"
                    )
                )
        )

        val result = AiChatClient.askFreeform(config = config(), prompt = "hi")
        assertEquals("重发成功", (result as AiChatClient.Result.Success).text)
        assertEquals(2, server.requestCount)
        // 第二次请求体里不该再有 stream_options。
        server.takeRequest()
        val second = server.takeRequest().body.readUtf8()
        assertFalse(second.contains("stream_options"))
    }

    @Test
    fun `端到端：空回复算失败`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: [DONE]\n\n")
        )
        val result = AiChatClient.askFreeform(config = config(), prompt = "hi")
        assertTrue(result is AiChatClient.Result.Failure)
    }

    @Test
    fun `端到端：鉴权失败不重试`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"error":{"message":"Authentication Fails"}}""")
        )
        val result = AiChatClient.askFreeform(config = config(), prompt = "hi", retries = 3)
        assertTrue(result is AiChatClient.Result.Failure)
        assertEquals("Authentication Fails", (result as AiChatClient.Result.Failure).message)
        // 401 是 Key 的问题，原地重试没有意义（RetryPolicy 不重试）。
        // 上一版这里还会断言 allowFailover=true（换一条档案重试）；只支持 DeepSeek 后
        // 没有第二条档案可换，该字段已删除 —— 失败就如实报错，让用户去改 Key。
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `端到端：没配 Key 时不发请求`() = runBlocking {
        // 这是"提前失败"仅剩的一条路径（端点已恒定，不再需要 URL 校验）。
        val result = AiChatClient.askFreeform(config = config().copy(apiKey = ""), prompt = "hi")
        assertTrue(result is AiChatClient.Result.Failure)
        assertEquals("还没有配置 API Key", (result as AiChatClient.Result.Failure).message)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `端到端：测试连接回报延迟与回复摘要`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"choices":[{"message":{"content":"你好！有什么可以帮你"}}]}""")
        )
        val probe = AiChatClient.testConnection(config())
        assertTrue(probe.ok)
        assertTrue(probe.latencyMs >= 0)
        assertEquals("你好！有什么可以帮你", probe.reply)
        assertEquals("", probe.error)
    }

    @Test
    fun `端到端：模型列表去重排序后按行返回`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"data":[{"id":"b"},{"id":"a"},{"id":"b"}]}""")
        )
        val result = AiChatClient.listModels(config())
        assertEquals("a\nb", (result as AiChatClient.Result.Success).text)
    }
}
