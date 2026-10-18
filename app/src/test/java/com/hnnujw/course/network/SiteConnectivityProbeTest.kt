package com.hnnujw.course.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

/**
 * 站点连通性探测的「失败原因翻译」回归。
 *
 * 这段逻辑的价值全在**用户看得懂**：登录失败时用户点一下「测试连接」，
 * 看到的必须是「域名解析失败」而不是 `java.net.UnknownHostException: ...`。
 *
 * 也是最容易写错的一处：OkHttp 会把底层失败**再包一层** `IOException`，
 * 只看最外层类型永远匹配不上，必须沿 `cause` 链往下找。
 */
class SiteConnectivityProbeTest {

    @Test
    fun `域名解析失败被翻译成可读文案`() {
        val msg = SiteConnectivityProbe.describe(UnknownHostException("jwgl.hnnu.edu.cn"))
        assertTrue("应提到 DNS 或域名解析: $msg", msg.contains("DNS") || msg.contains("域名"))
    }

    @Test
    fun `连接超时被翻译成可读文案`() {
        val msg = SiteConnectivityProbe.describe(SocketTimeoutException("timeout"))
        assertTrue("应提到超时: $msg", msg.contains("超时"))
    }

    @Test
    fun `证书问题被翻译成可读文案`() {
        val msg = SiteConnectivityProbe.describe(SSLHandshakeException("bad cert"))
        assertTrue("应提到证书: $msg", msg.contains("证书"))
    }

    @Test
    fun `无法连接被翻译成可读文案`() {
        val msg = SiteConnectivityProbe.describe(ConnectException("refused"))
        assertTrue("应提到连接: $msg", msg.contains("连接"))
    }

    /**
     * 🔴 核心：OkHttp 的典型包装形态 —— 外层 `IOException`，真正的 cause 在里面。
     *
     * 只看最外层类型的话，这条会退化成「显示原始英文异常」，
     * 而那正是这个功能想避免的。
     */
    @Test
    fun `沿 cause 链找到真实原因`() {
        val wrapped = IOException("failed to connect", UnknownHostException("no host"))
        val msg = SiteConnectivityProbe.describe(wrapped)
        assertTrue("必须穿透到 cause: $msg", msg.contains("DNS") || msg.contains("域名"))
    }

    @Test
    fun `多层包装也能穿透`() {
        val deep = IOException("outer",
            IOException("middle",
                SSLHandshakeException("certificate verify failed")))
        val msg = SiteConnectivityProbe.describe(deep)
        assertTrue("必须穿透两层: $msg", msg.contains("证书"))
    }

    /** 明文被网络安全策略拦下是常见的一类失败，要单独说清楚。 */
    @Test
    fun `明文被拦截有专门文案`() {
        val msg = SiteConnectivityProbe.describe(
            IOException("CLEARTEXT communication to xg.hnnu.edu.cn not permitted by network security policy")
        )
        assertTrue("应提到明文或安全策略: $msg", msg.contains("明文") || msg.contains("安全策略"))
    }

    /** 没有匹配到已知类型时，退回原始 message（截断），而不是抛异常。 */
    @Test
    fun `未知异常退回 message`() {
        val msg = SiteConnectivityProbe.describe(IOException("some odd failure"))
        assertTrue("应包含原始信息: $msg", msg.contains("some odd failure"))
    }

    @Test
    fun `过长的 message 被截断`() {
        val long = "x".repeat(200)
        val msg = SiteConnectivityProbe.describe(IOException(long))
        assertTrue("必须截断到 48 字符内，实际 ${msg.length}", msg.length <= 48)
    }

    @Test
    fun `没有 message 时退回类名`() {
        val msg = SiteConnectivityProbe.describe(IOException())
        assertTrue("不该是空串", msg.isNotBlank())
        assertTrue("应含类名: $msg", msg.contains("IOException"))
    }

    /** 任何异常都不能让 describe 抛出去 —— 它在探测失败路径上，抛了就是二次崩溃。 */
    @Test
    fun `任意异常都不会抛`() {
        val cases = listOf(
            RuntimeException(),
            IllegalStateException("boom"),
            Error("weird"),
            IOException("a", RuntimeException("b")),
        )
        cases.forEach { e ->
            val msg = runCatching { SiteConnectivityProbe.describe(e) }.getOrNull()
            assertTrue("describe(${e.javaClass.simpleName}) 不该抛", msg != null)
        }
    }

    /** 结果对象在可达/不可达两种形态下字段语义要对。 */
    @Test
    fun `结果对象字段语义正确`() {
        val ok = SiteConnectivityProbe.Result("主站", "https://a/", true, 120, "HTTP 200")
        assertTrue(ok.reachable)
        assertEquals("HTTP 200", ok.detail)

        val bad = SiteConnectivityProbe.Result("备用", "http://b/", false, 8000, "连接超时（8s）")
        assertTrue(!bad.reachable)
        assertEquals(8000L, bad.latencyMs)
    }
}
