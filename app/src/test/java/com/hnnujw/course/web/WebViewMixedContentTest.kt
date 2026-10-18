package com.hnnujw.course.web

import com.hnnujw.course.web.WebViewMixedContent.Policy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 混合内容策略的回归。
 *
 * 这条策略同时承担两个互相冲突的目标，任何一侧被改坏都会出事：
 *  - **放太松**：https 登录页允许 http 子资源 → 中间人可在子资源上注入脚本，
 *    而 WebView 里的 JS 与主页面同源，等于拿到凭据输入框；
 *  - **放太紧**：只支持 http 的备用站/学工系统整页白屏，用户直接无法登录。
 *
 * 所以这里同时钉住两侧。⚠️ 只测 [WebViewMixedContent.policyFor] 这类纯函数 ——
 * `android.jar` 在 JVM 单测里是空桩，`WebSettings.MIXED_CONTENT_*` 会全部读到 0，
 * 测它没有任何意义（这也是把策略与 `applyMode` 拆开的原因）。
 */
class WebViewMixedContentTest {

    @Test
    fun `https 主文档使用最严格策略`() {
        assertEquals(
            Policy.Strict,
            WebViewMixedContent.policyFor("https://jwgl.hnnu.edu.cn/jwglxt/xtgl/login_slogin.html")
        )
    }

    /** 一卡通 SSO 也是 https，且同样承担凭据输入。 */
    @Test
    fun `一卡通 SSO 是 https 所以同样最严`() {
        assertEquals(Policy.Strict, WebViewMixedContent.policyFor("https://yktapp.hnnu.edu.cn/plat?name=loginTransit"))
        assertEquals(Policy.Strict, WebViewMixedContent.policyFor("https://xxmh.hnnu.edu.cn/cas/login"))
    }

    /** 教务备用入口是 IP 直连的 http，必须放宽，否则整页打不开。 */
    @Test
    fun `教务备用入口是 http 所以放宽`() {
        assertEquals(
            Policy.Permissive,
            WebViewMixedContent.policyFor("http://211.70.176.172/jwglxt/xtgl/login_slogin.html")
        )
    }

    /** 学工系统全站只有 http（实测 https 超时），同样必须放宽。 */
    @Test
    fun `学工系统是 http 所以放宽`() {
        assertEquals(Policy.Permissive, WebViewMixedContent.policyFor("http://xg.hnnu.edu.cn/Phone/index.html"))
    }

    @Test
    fun `scheme 大小写不影响判断`() {
        assertEquals(Policy.Strict, WebViewMixedContent.policyFor("HTTPS://jwgl.hnnu.edu.cn/"))
        assertEquals(Policy.Permissive, WebViewMixedContent.policyFor("HTTP://211.70.176.172/"))
    }

    /** 空串 / null：宁可让畸形地址打不开，也不要默认放行明文。 */
    @Test
    fun `空地址按最严处理`() {
        assertEquals(Policy.Strict, WebViewMixedContent.policyFor(null))
        assertEquals(Policy.Strict, WebViewMixedContent.policyFor(""))
        assertEquals(Policy.Strict, WebViewMixedContent.policyFor("   "))
    }

    /** 只认 http/https：其它 scheme 一律最严（`javascript:` 不该由这里放行）。 */
    @Test
    fun `其它 scheme 一律最严`() {
        assertEquals(Policy.Strict, WebViewMixedContent.policyFor("javascript:alert(1)"))
        assertEquals(Policy.Strict, WebViewMixedContent.policyFor("file:///etc/passwd"))
        assertEquals(Policy.Strict, WebViewMixedContent.policyFor("about:blank"))
        assertEquals(Policy.Strict, WebViewMixedContent.policyFor("content://media/1"))
    }

    @Test
    fun `没有 scheme 的裸地址按最严处理`() {
        assertEquals(Policy.Strict, WebViewMixedContent.policyFor("jwgl.hnnu.edu.cn/path"))
        assertEquals(Policy.Strict, WebViewMixedContent.policyFor("//jwgl.hnnu.edu.cn/"))
    }

    @Test
    fun `isCleartextSite 与 policyFor 口径一致`() {
        val cleartext = "http://211.70.176.172/"
        val secure = "https://jwgl.hnnu.edu.cn/"
        assertTrue(WebViewMixedContent.isCleartextSite(cleartext))
        assertFalse(WebViewMixedContent.isCleartextSite(secure))
        assertEquals(Policy.Permissive, WebViewMixedContent.policyFor(cleartext))
        assertEquals(Policy.Strict, WebViewMixedContent.policyFor(secure))
    }

    @Test
    fun `末尾空白与查询串不影响 scheme 判断`() {
        assertEquals(Policy.Strict, WebViewMixedContent.policyFor("  https://jwgl.hnnu.edu.cn/x?a=1#b  "))
        assertEquals(Policy.Permissive, WebViewMixedContent.policyFor("  http://xg.hnnu.edu.cn/x?a=1  "))
    }
}
