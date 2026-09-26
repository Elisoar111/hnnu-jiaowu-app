package com.hnnujw.course.ykt

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 一卡通登录：从 WebView Cookie 串里抽 `synjones-auth` 的回归。
 *
 * 这一段是**最容易悄悄写错**的地方：抽错了不会抛异常，只会让所有请求
 * 都带上一个无效令牌，表现为"登录明明成功了却一直提示未登录"。
 * 所以判据全部钉在纯函数 [YktClient.extractAuthFromCookie] 上。
 *
 * 真机实测的 Cookie 形态（`synjones-auth` 的值是 `'bearer ' + access_token`，
 * 服务端还给两侧加了引号）：
 *
 * ```
 * synjones-auth="bearer eyJhbGciOiJIUzI1NiJ9.xxx.yyy"; SESSION=abc123
 * ```
 */
class YktAuthCookieTest {

    /** 真机形态：带引号、且同串里混着别的 Cookie。 */
    @Test
    fun extractsBearerTokenAndStripsQuotes() {
        val cookie = """synjones-auth="bearer eyJhbGciOiJIUzI1NiJ9.xxx.yyy"; SESSION=abc123"""
        assertEquals(
            "bearer eyJhbGciOiJIUzI1NiJ9.xxx.yyy",
            YktClient.extractAuthFromCookie(cookie),
        )
    }

    /**
     * ⚠️ 核心回归：令牌值里自带 `=` 时必须完整取出。
     *
     * base64 令牌的 padding 就是 `=`，若用 `split("=")[1]` 取值会被截断，
     * 服务端认不出 → 表现为"登录成功但一直未登录"。
     */
    @Test
    fun valueContainingEqualsIsNotTruncated() {
        val cookie = """synjones-auth="bearer abc==="; SESSION=x"""
        assertEquals("bearer abc===", YktClient.extractAuthFromCookie(cookie))
    }

    /** 不带引号的形态同样要认（部分 WebView / 服务端实现不加引号）。 */
    @Test
    fun unquotedValueIsAccepted() {
        assertEquals("bearer tok123", YktClient.extractAuthFromCookie("synjones-auth=bearer tok123"))
    }

    /** 单引号包裹也要剥掉。 */
    @Test
    fun singleQuotedValueIsStripped() {
        assertEquals("bearer tok123", YktClient.extractAuthFromCookie("synjones-auth='bearer tok123'"))
    }

    /** 顺序无关：它排在最后也要能取到。 */
    @Test
    fun orderDoesNotMatter() {
        val cookie = "SESSION=abc; route=; synjones-auth=\"bearer zzz\""
        assertEquals("bearer zzz", YktClient.extractAuthFromCookie(cookie))
    }

    /** 大小写不敏感：头字段名不该因大小写差异而漏取。 */
    @Test
    fun nameMatchIsCaseInsensitive() {
        assertEquals("bearer v", YktClient.extractAuthFromCookie("SynJones-Auth=\"bearer v\""))
    }

    /** 没有该 Cookie 时返回空串 —— **绝不能**把整串 Cookie 当令牌返回。 */
    @Test
    fun missingCookieYieldsEmptyString() {
        val cookie = "SESSION=abc123; route=xx"
        assertEquals("", YktClient.extractAuthFromCookie(cookie))
    }

    /** 空串、纯空白、只有分隔符都不抛异常。 */
    @Test
    fun malformedInputDoesNotCrash() {
        assertEquals("", YktClient.extractAuthFromCookie(""))
        assertEquals("", YktClient.extractAuthFromCookie("   "))
        assertEquals("", YktClient.extractAuthFromCookie(";;;;"))
        assertEquals("", YktClient.extractAuthFromCookie("=novalue"))
    }

    /**
     * 值为空时不返回空壳 —— 空值的 `synjones-auth` 等于没登录，
     * 返回 "" 让上层正确进入"需要登录"分支。
     */
    @Test
    fun emptyValueYieldsEmptyString() {
        assertEquals("", YktClient.extractAuthFromCookie("synjones-auth="))
        assertEquals("", YktClient.extractAuthFromCookie("synjones-auth=\"\""))
    }

    /**
     * ⚠️ 不能把形近的 Cookie 名匹配进来。
     *
     * 站点上还有其它同前缀的 Cookie，用 `startsWith` 之类的模糊匹配会误取，
     * 拿到一个别的值当令牌 —— 同样表现为"登录了却一直未登录"。
     */
    @Test
    fun similarNamesAreNotMistaken() {
        val cookie = "synjones-auth-old=\"bearer wrong\"; synjones-auth=\"bearer right\""
        assertEquals("bearer right", YktClient.extractAuthFromCookie(cookie))
    }

    /** `; ` 分隔之外的紧贴形态（`;` 无空格）也要能切开。 */
    @Test
    fun handlesSeparatorWithoutSpace() {
        assertEquals("bearer v", YktClient.extractAuthFromCookie("a=1;synjones-auth=\"bearer v\";b=2"))
    }

    // ── Cookie 取值地址：顺序与去重 ───────────────────────────────────────

    /**
     * 落点是 `HOME` 形态时必须同时给出「原样地址」与「/charge 路径」两个候选。
     *
     * 少给 `/charge` 会在"Cookie scope 只写在 /charge"的学校取不到值 ——
     * 而取不到**不报错**，只表现为"登录成功却一直提示未登录"。
     */
    @Test
    fun homeUrlYieldsBothCandidates() {
        val urls = YktClient.authCookieUrls("https://yktapp.hnnu.edu.cn/plat/shouyeUser?appId=1")
        assertEquals(
            listOf(
                "https://yktapp.hnnu.edu.cn/plat/shouyeUser?appId=1",
                "https://yktapp.hnnu.edu.cn/charge",
            ),
            urls,
        )
    }

    /** 已经是 `/charge` 地址时不该再拼一个重复项。 */
    @Test
    fun chargeUrlIsDeduplicated() {
        val urls = YktClient.authCookieUrls("https://yktapp.hnnu.edu.cn/charge/sceneroom/comboxCampus")
        // 原地址 + 站点根 /charge（两者字符串不同，故都保留），但**不得重复**
        assertEquals(
            listOf(
                "https://yktapp.hnnu.edu.cn/charge/sceneroom/comboxCampus",
                "https://yktapp.hnnu.edu.cn/charge",
            ),
            urls,
        )
        assertEquals(urls.size, urls.distinct().size)
    }

    /** 空输入返回空列表，不产生 "null/charge" 这种畸形地址。 */
    @Test
    fun blankUrlYieldsNoCandidates() {
        assertEquals(emptyList<String>(), YktClient.authCookieUrls(""))
        assertEquals(emptyList<String>(), YktClient.authCookieUrls("   "))
    }

    /** 根地址（没有 /plat）也要能推出 /charge 候选。 */
    @Test
    fun rootUrlYieldsChargeCandidate() {
        val urls = YktClient.authCookieUrls("https://yktapp.hnnu.edu.cn/")
        assertEquals(listOf("https://yktapp.hnnu.edu.cn/", "https://yktapp.hnnu.edu.cn/charge"), urls)
    }

    // ── ★ 回归：登录入口换成 CAS 网关后，origin 推导不能失效 ──────────────

    /**
     * ⚠️ **最高价值回归**：登录入口现在是 `/berserker-auth/cas/login/wisedu?…`，
     * 路径里**没有 `/plat`**。
     *
     * 旧实现 `url.substringBefore("/plat")` 会返回整个 URL，
     * 于是 `origin == trimmed`、`/charge` 候选被跳过 ⇒ 只问 CAS 网关地址
     * ⇒ 拿不到 scope 在 `/charge` 的 `synjones-auth` ⇒ **点"我已登录完成"毫无反应**
     *（只弹"还没检测到登录状态"）。
     */
    @Test
    fun casGatewayEntryStillYieldsChargeCandidate() {
        val entry = "https://yktapp.hnnu.edu.cn/berserker-auth/cas/login/wisedu" +
            "?targetUrl=https%3A%2F%2Fyktapp.hnnu.edu.cn%2Fplat%3Fname%3DloginTransit"
        val urls = YktClient.authCookieUrls(entry)
        assertEquals(listOf(entry, "https://yktapp.hnnu.edu.cn/charge"), urls)
    }

    /** 中转页落点也要能推出 `/charge`（这是登录成功后真正的落点）。 */
    @Test
    fun transitLandingYieldsChargeCandidate() {
        val landing = "https://yktapp.hnnu.edu.cn/plat/loginTransit?ticket=abc&targetUrl=x"
        assertEquals(
            listOf(landing, "https://yktapp.hnnu.edu.cn/charge"),
            YktClient.authCookieUrls(landing),
        )
    }

    /** 带端口的站点根要保留端口，不能把 `:8443` 丢掉。 */
    @Test
    fun originKeepsNonDefaultPort() {
        assertEquals(
            "https://yktapp.hnnu.edu.cn:8443",
            YktClient.originOf("https://yktapp.hnnu.edu.cn:8443/charge/x?y=1"),
        )
    }

    /** 无端口时不补 `:443`，保持与站点 Cookie 匹配的书写形式。 */
    @Test
    fun originOmitsDefaultPort() {
        assertEquals("https://yktapp.hnnu.edu.cn", YktClient.originOf("https://yktapp.hnnu.edu.cn/plat"))
        assertEquals("http://xg.hnnu.edu.cn", YktClient.originOf("http://xg.hnnu.edu.cn/api/x"))
    }

    /** host 里出现 `plat`/`charge` 之类的词也不能被误当成路径。 */
    @Test
    fun originIsNotConfusedByHostContainingPathWords() {
        assertEquals(
            "https://plat.charge.example.cn",
            YktClient.originOf("https://plat.charge.example.cn/charge/x"),
        )
    }

    /** 无法解析的输入返回空串，而不是抛出异常或返回畸形地址。 */
    @Test
    fun originOfUnparsableInputIsEmpty() {
        for (value in listOf("", "   ", "not a url", "/plat/", "javascript:alert(1)")) {
            assertEquals(value, "", YktClient.originOf(value))
        }
    }

    // ── ★★ 主路径：令牌在 Web Storage（sessionStorage），不在 Cookie ──────

    /**
     * ⚠️ **本次修复的核心回归**。
     *
     * 站点 store 的 `Login` mutation 把令牌写进 `sessionStorage`：
     * ```js
     * Login(e,t){ t.noCatch||(sessionStorage.setItem("access_token",t.token),
     *                          sessionStorage.setItem("token_type",t.token_type)), … }
     * ```
     * 真机也确证 Cookie 里**没有** `synjones-auth`。此用例锁住
     * "从 storage JSON 拼出完整 `synjones-auth` 值"的行为。
     */
    @Test
    fun extractsBearerTokenFromSessionStorageJson() {
        val json = """{"access_token":"eyJhbGciOiJIUzI1NiJ9.xxx.yyy","token_type":"bearer"}"""
        assertEquals("bearer eyJhbGciOiJIUzI1NiJ9.xxx.yyy", YktClient.extractAuthFromStorageJson(json))
    }

    /** `token_type` 缺失时按小写 `bearer` 兜底（站点多数分支就是缺省 bearer）。 */
    @Test
    fun missingTokenTypeDefaultsToBearer() {
        val json = """{"access_token":"abc123"}"""
        assertEquals("bearer abc123", YktClient.extractAuthFromStorageJson(json))
    }

    /** `token_type` 显式给 `Basic` 等其它值时按其原值拼（不要硬编码 bearer）。 */
    @Test
    fun explicitTokenTypeIsHonored() {
        assertEquals(
            "Basic abc123",
            YktClient.extractAuthFromStorageJson("""{"access_token":"abc123","token_type":"Basic"}"""),
        )
    }

    /**
     * `access_token` 已经是完整形态时不再套前缀 ——
     * 否则会拼出 `bearer bearer xxx`，服务端认不出。
     */
    @Test
    fun alreadyPrefixedTokenIsNotDoublePrefixed() {
        assertEquals(
            "bearer abc123",
            YktClient.extractAuthFromStorageJson("""{"access_token":"bearer abc123","token_type":"bearer"}"""),
        )
        assertEquals(
            "bearer abc123",
            YktClient.composeAuthFromStorageValues("bearer abc123", "bearer"),
        )
    }

    /** 键名大小写不敏感（站点存在 `accessToken` 写法），别漏取。 */
    @Test
    fun storageKeyMatchIsCaseInsensitive() {
        assertEquals(
            "bearer v",
            YktClient.extractAuthFromStorageJson("""{"accessToken":"v","token_type":"bearer"}"""),
        )
    }

    /** 同串里混着 `userInfo` 等大对象时仍要正确定位到 `access_token`。 */
    @Test
    fun ignoresOtherStorageKeys() {
        val json = """{"loginType":"0","access_token":"tok","userInfo":"{\"name\":\"x\"}","token_type":"bearer"}"""
        assertEquals("bearer tok", YktClient.extractAuthFromStorageJson(json))
    }

    /** 值里含转义引号/反斜杠时不能截断令牌。 */
    @Test
    fun escapedCharactersInTokenAreUnescaped() {
        assertEquals(
            "bearer a\"b",
            YktClient.extractAuthFromStorageJson("""{"access_token":"a\"b","token_type":"bearer"}"""),
        )
    }

    /** 没有 `access_token`（未登录）→ 空串，**不能**返回半截值或抛异常。 */
    @Test
    fun storageWithoutAccessTokenYieldsEmpty() {
        assertEquals("", YktClient.extractAuthFromStorageJson("{}"))
        assertEquals("", YktClient.extractAuthFromStorageJson("""{"token_type":"bearer"}"""))
        assertEquals("", YktClient.extractAuthFromStorageJson("""{"access_token":""}"""))
        assertEquals("", YktClient.extractAuthFromStorageJson(""))
        assertEquals("", YktClient.extractAuthFromStorageJson("   "))
        assertEquals("", YktClient.extractAuthFromStorageJson("not json at all"))
    }

    /** `access_token` 两侧带引号（某些实现会写进去）也要剥掉。 */
    @Test
    fun quotedAccessTokenInStorageIsStripped() {
        assertEquals(
            "bearer v",
            YktClient.extractAuthFromStorageJson("""{"access_token":"\"v\"","token_type":"bearer"}"""),
        )
    }
}
