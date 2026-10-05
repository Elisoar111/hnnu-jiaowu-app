package com.hnnujw.course.review

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AiEndpoint] 的常量与 URL 拼接回归测试。
 *
 * 上一版这里测的是「归一化补 `/v1`」与「四类 URL 校验」—— 那两件事的存在前提是
 * "端点由用户填、可能填错"。2026-10 收成只支持 DeepSeek 后端点恒定，两者都已删除
 * （见 [AiEndpoint] 的类注释），所以这里改成钉住**真正会出错的东西**：
 * 拼接结果必须与官方文档给的 curl 地址逐字一致。
 *
 * 为什么这条值得测：文档原文是 `https://api.deepseek.com/chat/completions`，
 * 而上一版会给 host 补 `/v1`（变成 `…/v1/chat/completions`）。DeepSeek 两种写法都收，
 * 所以这个差异**在真机上不会报错**、只会在换到别家时炸 —— 属于必须靠单测钉住的类型。
 */
class AiEndpointTest {

    @Test
    fun `chatUrl 与官方文档给的地址逐字一致`() {
        assertEquals(
            "https://api.deepseek.com/chat/completions",
            AiEndpoint.chatUrl()
        )
        assertEquals("https://api.deepseek.com", AiEndpoint.BASE_URL)
    }

    @Test
    fun `chatUrl 去尾斜杠且不追加 v1`() {
        assertEquals(
            "https://api.deepseek.com/chat/completions",
            AiEndpoint.chatUrl("https://api.deepseek.com/")
        )
        assertEquals(
            "https://api.deepseek.com/chat/completions",
            AiEndpoint.chatUrl("https://api.deepseek.com///")
        )
        // 🔴 上一版会补成 `…/v1/chat/completions`。收成单端点后不再补 —— 文档原文没有 v1。
        assertTrue(!AiEndpoint.chatUrl().contains("/v1"))
    }

    @Test
    fun `modelsUrl 拼在 baseUrl 后`() {
        assertEquals("https://api.deepseek.com/models", AiEndpoint.modelsUrl())
        assertEquals("https://api.deepseek.com/models", AiEndpoint.modelsUrl("https://api.deepseek.com/"))
    }

    @Test
    fun `端点形参可注入以便单测指向本地服务器`() {
        // MockWebServer 只监听 127.0.0.1，所以这两个函数的 baseUrl 形参必须真的生效。
        assertEquals("http://127.0.0.1:8080/chat/completions", AiEndpoint.chatUrl("http://127.0.0.1:8080"))
        assertEquals("http://127.0.0.1:8080/models", AiEndpoint.modelsUrl("http://127.0.0.1:8080/"))
    }

    @Test
    fun `模型清单非空 无重复 且含默认模型`() {
        assertTrue(AiEndpoint.MODELS.isNotEmpty())
        assertEquals(AiEndpoint.MODELS.size, AiEndpoint.MODELS.toSet().size)
        assertTrue(AiEndpoint.DEFAULT_MODEL in AiEndpoint.MODELS)
        // 官方文档 2026-10 给出的两个取值，缺一个说明清单过期了。
        assertTrue("deepseek-flash" in AiEndpoint.MODELS)
        assertTrue("deepseek-v4-pro" in AiEndpoint.MODELS)
    }

    @Test
    fun `帮助链接都是 https 且非空`() {
        // 这两个地址要交给系统浏览器打开，写成 http 会被部分系统的网络安全策略拦掉。
        listOf(AiEndpoint.DOC_URL, AiEndpoint.KEY_URL).forEach { url ->
            assertTrue("$url 应以 https:// 开头", url.startsWith("https://"))
        }
    }
}
