package com.hnnujw.course.login

import com.hnnujw.course.academic.AcademicUrlPolicy
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

class WebLoginNavigationTest {
    @Test fun addressBarSupportsFullAddressesBareDomainsAndSchoolSearch() {
        assertEquals("http://jwxt.hut.edu.cn/jsxsd/", WebLoginNavigation.resolveInput(" http://jwxt.hut.edu.cn/jsxsd/ "))
        assertEquals("https://authserver.ntu.edu.cn/authserver/login", WebLoginNavigation.resolveInput("authserver.ntu.edu.cn/authserver/login"))
        assertEquals("https://jw.example.edu.cn:8443/jsxsd", WebLoginNavigation.resolveInput("jw.example.edu.cn:8443/jsxsd"))
        val query = "湖南工业大学 教务系统 & 登录"
        assertEquals(query, WebLoginNavigation.resolveInput(query)!!.toHttpUrl().queryParameter("q"))
    }

    @Test fun interactiveSsoAndSearchCanCrossDomainsWithoutRelaxingProtocolRequests() {
        val host = listOf("jw.ntu.edu.cn")
        for (url in listOf("https://authserver.ntu.edu.cn/authserver/login", "https://www.bing.com/search?q=ntu", "http://jwxt.hut.edu.cn/jsxsd/")) {
            assertTrue(WebLoginNavigation.isWebUrl(url))
            assertFalse(AcademicUrlPolicy.isAllowed(url, "https", host))
        }
    }

    @Test fun pastedExecutableFileAndCredentialUrlsAreRejected() {
        for (value in listOf("javascript:alert(1)", "file:///sdcard/private", "content://contacts/1", "intent://login", "data:text/html,test", "https://user:secret@jw.example.edu.cn/", "https://")) {
            assertNull(value, WebLoginNavigation.resolveInput(value))
            assertFalse(value, WebLoginNavigation.isWebUrl(value))
        }
        assertNull(WebLoginNavigation.resolveInput("  "))
    }

    // ── hostMatches：一卡通令牌只能在业务域上取，CAS 域必须被拒 ──────────────

    private val yktHosts = setOf("yktapp.hnnu.edu.cn")

    @Test fun exactAndSubdomainHostsAreAllowed() {
        assertTrue(WebLoginNavigation.hostMatches("https://yktapp.hnnu.edu.cn/plat/", yktHosts))
        assertTrue(WebLoginNavigation.hostMatches("https://yktapp.hnnu.edu.cn", yktHosts))
        assertTrue(WebLoginNavigation.hostMatches("https://charge.yktapp.hnnu.edu.cn/x", yktHosts))
        // 大小写与尾点（FQDN 写法）都要认
        assertTrue(WebLoginNavigation.hostMatches("https://YKTAPP.HNNU.EDU.CN/plat/", yktHosts))
        assertTrue(WebLoginNavigation.hostMatches("https://yktapp.hnnu.edu.cn./plat/", yktHosts))
    }

    @Test fun prefixLookalikeHostIsRejected() {
        // 关键回归：以白名单域名结尾但并无子域边界，属于伪造域名
        assertFalse(WebLoginNavigation.hostMatches("https://evilyktapp.hnnu.edu.cn/plat/", yktHosts))
        assertFalse(WebLoginNavigation.hostMatches("https://notyktapp.hnnu.edu.cn/", yktHosts))
        // 后缀被改掉的主域
        assertFalse(WebLoginNavigation.hostMatches("https://yktapp.hnnu.edu.com/", yktHosts))
        assertFalse(WebLoginNavigation.hostMatches("https://yktapp.evil.cn/", yktHosts))
    }

    @Test fun casHostIsOutsideTokenAllowlist() {
        // 登录流程跨域到 CAS，令牌绝不能在那一页上取
        val cas = "https://xxmh.hnnu.edu.cn/cas/login?service=..."
        assertFalse(WebLoginNavigation.hostMatches(cas, yktHosts))
        // 但 CAS 页本身是合法的可导航网页（拦截逻辑不受白名单影响）
        assertTrue(WebLoginNavigation.isWebUrl(cas))
    }

    @Test fun unparsableOrEmptyInputsAreRejected() {
        for (value in listOf("", "   ", "not a url", "/plat/", "javascript:alert(1)")) {
            assertFalse(value, WebLoginNavigation.hostMatches(value, yktHosts))
        }
        // 空白名单恒为 false
        assertFalse(WebLoginNavigation.hostMatches("https://yktapp.hnnu.edu.cn/", emptySet()))
    }

    @Test fun hostMatchesOnlyLooksAtHostNotCredentials() {
        // hostMatches 的职责**只是比对域名**，不管 URL 里有没有用户信息。
        // 带凭据的地址由导航层挡掉（isWebUrl / resolveInput），那是另一道闸。
        // 这里把边界钉住，避免有人误以为"hostMatches 会拒绝带凭据的地址"。
        val withCreds = "https://user:pw@yktapp.hnnu.edu.cn/"
        assertTrue(WebLoginNavigation.hostMatches(withCreds, yktHosts))
        assertFalse(WebLoginNavigation.isWebUrl(withCreds))
        assertNull(WebLoginNavigation.resolveInput(withCreds))
    }

    @Test fun allowlistEntriesAreNormalizedBeforeCompare() {
        assertTrue(WebLoginNavigation.hostMatches("https://yktapp.hnnu.edu.cn/", setOf(".YKTAPP.HNNU.EDU.CN.")))
        // 全是点的条目应归一成空并被忽略，而不是匹配一切
        assertFalse(WebLoginNavigation.hostMatches("https://anything.example.cn/", setOf(".")))
    }
}
