package com.hnnujw.course.secondclass

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * 网关间歇重定向的回归测试。
 *
 * 真实现象（2026-09-21 实测）：不带显式端口请求 `…/api/app/client/v1/token` 时，
 * openresty 偶尔回
 *
 * ```
 * 302 Location: https://ekta.hnnu.edu.cn:443/api/app/client/v1/token
 * ```
 *
 * 同一 URL 连打三次得到 `302 / 302 / 200` —— **不是必现**，是多节点/网关行为。
 *
 * 而 OkHttp 默认会自动跟随重定向，且对 301/302/303 会把 **POST 降级成 GET 并丢掉请求体**，
 * 服务端于是回 `405 Request method 'GET' not supported`，用户看到的就是
 * "第二课堂登录失败"，且极难复现。
 *
 * 修法：客户端 `followRedirects(false)`，由 `request()` 自己跟随**同源**跳转并保留方法与请求体。
 * 下面的用例把这个行为钉死 —— 尤其是"第二跳必须仍是 POST"。
 *
 * 注意：这里**不注入** OkHttpClient，直接走生产用的 `defaultClient()`，
 * 否则测的就不是真实配置了。
 */
class SecondClassroomRedirectTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun client() = SecondClassroomClient(
        baseUrl = server.url("/").toString().trimEnd('/'),
        schoolCode = "10381",
    )

    /** 同源 302：必须自己跟随，且**两跳都是 POST**（方法不能被降级）。 */
    @Test
    fun followsSameHostRedirectWithoutDowngradingPost() {
        server.enqueue(
            MockResponse().setResponseCode(302)
                .setHeader("Location", server.url("/token").toString())
        )
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"code":0,"data":{"access_token":"tok-123"}}""")
        )

        val token = runBlocking { client().login("20240001", "pw") }

        assertEquals("tok-123", token)

        val first = server.takeRequest()
        val second = server.takeRequest()
        assertEquals("POST", first.method)
        assertEquals("POST", second.method)
        // 请求体必须原样跟过去，不能被重定向吃掉
        assertTrue(first.body.readUtf8().contains("params="))
        assertTrue(second.body.readUtf8().contains("params="))
    }

    /** 只跟同源跳转：跨站必须拒绝（请求头里带着 access_token）。 */
    @Test
    fun rejectsCrossHostRedirect() {
        server.enqueue(
            MockResponse().setResponseCode(302)
                .setHeader("Location", "https://evil.example.com/token")
        )

        try {
            runBlocking { client().login("20240001", "pw") }
            fail("跨站跳转应当被拒绝")
        } catch (e: SecondClassException) {
            assertTrue(e.message.orEmpty().contains("跨站"))
        }

        // 关键：只打了第一跳，令牌没有被发到第三方
        assertEquals(1, server.requestCount)
    }

    /** 同 host 但 scheme 变化同样拒绝。 */
    @Test
    fun rejectsSchemeChangeRedirect() {
        server.enqueue(
            MockResponse().setResponseCode(302)
                .setHeader("Location", "https://${server.hostName}:${server.port}/token")
        )

        try {
            runBlocking { client().login("20240001", "pw") }
            fail("scheme 变化的跳转应当被拒绝")
        } catch (e: SecondClassException) {
            assertTrue(e.message.orEmpty().contains("跨站"))
        }

        assertEquals(1, server.requestCount)
    }

    /** 重定向环不能把请求打成死循环：MAX_REDIRECTS = 3，第 4 跳要么成功要么报错。 */
    @Test
    fun stopsAfterMaxRedirects() {
        // 恰好 3 次跳转 + 1 次成功
        repeat(3) {
            server.enqueue(
                MockResponse().setResponseCode(302)
                    .setHeader("Location", server.url("/token").toString())
            )
        }
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"code":0,"data":{"access_token":"ok"}}""")
        )

        val token = runBlocking { client().login("20240001", "pw") }
        assertEquals("ok", token)
        assertEquals(4, server.requestCount)
    }

    /** 跳数用尽后不再跟随，而是如实报"服务不可用"，不会无限循环。 */
    @Test
    fun givesUpWhenRedirectsExhausted() {
        repeat(6) {
            server.enqueue(
                MockResponse().setResponseCode(302)
                    .setHeader("Location", server.url("/token").toString())
            )
        }

        try {
            runBlocking { client().login("20240001", "pw") }
            fail("应当抛出 SecondClassException")
        } catch (e: SecondClassException) {
            assertTrue(e.message.orEmpty().contains("302"))
        }
        assertEquals(4, server.requestCount)
    }

    /** 405 不再被翻译成误导性的"登录失败"，而是如实带上状态码。 */
    @Test
    fun surfacesHttp405AsServiceUnavailable() {
        server.enqueue(MockResponse().setResponseCode(405).setBody("405 Method Not Allowed"))

        try {
            runBlocking { client().login("20240001", "pw") }
            fail("应当抛出 SecondClassException")
        } catch (e: SecondClassException) {
            assertTrue(e.message.orEmpty().contains("405"))
        }
    }
}
