package com.hnnujw.course.login

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URLEncoder

/** Interactive browsing is independent of the protocol adapter's host allowlist. */
object WebLoginNavigation {
    fun isWebUrl(value: String): Boolean = value.toHttpUrlOrNull()?.let {
        it.username.isEmpty() && it.password.isEmpty()
    } == true

    fun searchUrl(keyword: String): String =
        "https://www.bing.com/search?q=" + URLEncoder.encode(keyword.trim(), "UTF-8")

    fun resolveInput(input: String): String? {
        val value = input.trim()
        if (value.isEmpty()) return null
        if (value.startsWith("http://", true) || value.startsWith("https://", true))
            return value.toHttpUrlOrNull()?.takeIf { isWebUrl(value) }?.toString()
        // Only web URLs may be entered; do not execute pasted javascript/file links.
        if (Regex("^[A-Za-z][A-Za-z0-9+.-]*:").containsMatchIn(value) &&
            !Regex("^[^/\\s]+:\\d+(?:/|$)").containsMatchIn(value)) return null
        if (value.none(Char::isWhitespace) && (value.substringBefore('/').contains('.') || value.startsWith("localhost"))) {
            val url = "https://$value".toHttpUrlOrNull()
            return url?.takeIf { isWebUrl(it.toString()) }?.toString()
        }
        return searchUrl(value)
    }

    /**
     * 某个 URL 的 host 是否落在白名单内（含子域）。
     *
     * **纯函数**，供 WebView 登录页判断"能不能在这一页上取令牌"。
     *
     * 为什么不能只看 `endsWith`：形如 `evilyktapp.hnnu.edu.cn` 的域名
     * 以 `yktapp.hnnu.edu.cn` 结尾，但**不是**它的一部分。必须先确认
     * 边界字符是 `.`（子域）或整串相等，否则白名单形同虚设。
     *
     * 输入不可解析（空串、相对地址、含用户信息）时一律返回 `false`。
     */
    fun hostMatches(url: String, allowed: Set<String>): Boolean {
        val host = url.toHttpUrlOrNull()?.host?.lowercase()?.trimEnd('.') ?: return false
        if (host.isEmpty()) return false
        return allowed.any { entry ->
            val normalized = entry.lowercase().trim().trimStart('.').trimEnd('.')
            normalized.isNotEmpty() && (host == normalized || host.endsWith(".$normalized"))
        }
    }
}
