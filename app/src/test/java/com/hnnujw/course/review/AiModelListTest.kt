package com.hnnujw.course.review

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AiChatClient.parseModelIds] 的容错回归测试。
 *
 * 背景：`GET /models` 是获取模型列表的来源，但**不保证一定可用**（网关改动、
 * 返回结构变化、返回非 JSON 都可能）。解析只要抛异常，用户「获取模型列表」
 * 就会卡死或闪退。所以这里逐条钉死退化路径：一律返回空列表、绝不抛，
 * 由 UI 层退化到内置候选 [AiChatClient.FALLBACK_MODELS]。
 */
class AiModelListTest {

    @Test
    fun `正常响应能取出全部 id`() {
        val raw = """
            {"object":"list","data":[{"id":"deepseek-chat","object":"model"},
            {"id":"deepseek-reasoner","object":"model"}]}
        """.trimIndent()
        assertEquals(listOf("deepseek-chat", "deepseek-reasoner"), AiChatClient.parseModelIds(raw))
    }

    @Test
    fun `data 缺失时返回空列表`() {
        assertTrue(AiChatClient.parseModelIds("""{"object":"list"}""").isEmpty())
    }

    @Test
    fun `data 为 null 时返回空列表`() {
        assertTrue(AiChatClient.parseModelIds("""{"object":"list","data":null}""").isEmpty())
    }

    @Test
    fun `data 非数组时返回空列表`() {
        assertTrue(AiChatClient.parseModelIds("""{"object":"list","data":"oops"}""").isEmpty())
    }

    @Test
    fun `元素缺 id 时跳过该项`() {
        val raw = """{"data":[{"object":"model"},{"id":"deepseek-chat"},{"id":"  "}]}"""
        assertEquals(listOf("deepseek-chat"), AiChatClient.parseModelIds(raw))
    }

    @Test
    fun `元素不是对象时跳过该项`() {
        val raw = """{"data":["deepseek-chat",{"id":"deepseek-reasoner"}]}"""
        assertEquals(listOf("deepseek-reasoner"), AiChatClient.parseModelIds(raw))
    }

    @Test
    fun `响应非 JSON 时不抛并返回空列表`() {
        assertTrue(AiChatClient.parseModelIds("<html>502 Bad Gateway</html>").isEmpty())
    }

    @Test
    fun `空串与空白串返回空列表`() {
        assertTrue(AiChatClient.parseModelIds("").isEmpty())
        assertTrue(AiChatClient.parseModelIds("   ").isEmpty())
    }

    @Test
    fun `内置候选不空且含默认模型`() {
        // UI 退化路径依赖它非空；否则「列表 + 自定义输入」会退化成空列表。
        assertTrue(AiChatClient.FALLBACK_MODELS.isNotEmpty())
        assertTrue(AiKeyStore.DEFAULT_MODEL in AiChatClient.FALLBACK_MODELS)
    }
}
