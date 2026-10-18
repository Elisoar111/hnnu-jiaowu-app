package com.hnnujw.course.web

import android.webkit.WebSettings

/**
 * WebView 的混合内容策略。
 *
 * ## 背景
 *
 * 三个内嵌 WebView 原先一律写死 `MIXED_CONTENT_COMPATIBILITY_MODE`：
 *  - `AcademicWebViewActivity`（教务登录，**承担学号密码输入**）
 *  - `YktLoginActivity`（一卡通登录，同样承担凭据）
 *  - `YktWebActivity`（一卡通账单 / 详情）
 *
 * 这个模式允许 https 页面加载 http 资源。对**登录页**来说这是实打实的风险：
 * 页面本身是 https，但只要有一个 http 子资源，攻击者就能在那个子资源上动手，
 * 而 WebView 里的 JavaScript 与主页面同源 —— 等于给中间人开了一个注入口。
 *
 * ## 为什么不能一刀切改成 NEVER_ALLOW
 *
 * 学校有两个只支持 http 的站点：
 *  - `211.70.176.172`：教务**备用入口**（IP 直连，只有 http）
 *  - `xg.hnnu.edu.cn`：学工系统（全站只有 http，实测 https 超时）
 *
 * 如果对这些站也 NEVER_ALLOW，页面会**白屏打不开**（主文档本身就是 http，
 * 其下所有资源都被判为混合内容）。所以策略必须**跟着主文档的 scheme 走**。
 *
 * ## 设计
 *
 * 判断结果先以 [Policy] 表达（纯 Kotlin 枚举，**不碰 android 常量**），
 * 再由 [applyMode] 映射到 `WebSettings`。分开的理由很实际：`android.jar` 在
 * JVM 单测里是空桩，直接读写 `WebSettings.MIXED_CONTENT_*` 会全部拿到 0，
 * 那样测出来的东西没有任何意义。策略本身必须能被真正测到。
 */
object WebViewMixedContent {

    /** 混合内容策略（与 `WebSettings` 一一对应，但可脱离 Android 测试）。 */
    enum class Policy {
        /** 禁止 https 页面加载任何 http 资源。用于所有 https 站点。 */
        Strict,

        /** 允许混合内容（仅限主文档本身就是 http 的站点）。 */
        Permissive
    }

    /**
     * 按要加载的主文档 URL 决定策略。
     *
     * @param url 即将加载的页面地址。解析不出来（空串 / 非法 / 非 http(s) scheme）时
     *   按**最严格**处理：宁可让一个畸形地址打不开，也不要默认放行明文资源。
     */
    fun policyFor(url: String?): Policy =
        if (schemeOf(url) == "http") Policy.Permissive else Policy.Strict

    /** 这个 URL 是否属于需要放宽混合内容的明文站点。 */
    fun isCleartextSite(url: String?): Boolean = schemeOf(url) == "http"

    /** 把策略写进 WebView 设置。这是唯一接触 `WebSettings` 的地方。 */
    fun applyMode(settings: WebSettings, url: String?) {
        settings.mixedContentMode = when (policyFor(url)) {
            Policy.Strict -> WebSettings.MIXED_CONTENT_NEVER_ALLOW
            Policy.Permissive -> WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }
    }

    private fun schemeOf(url: String?): String {
        val raw = url?.trim().orEmpty()
        if (raw.isEmpty()) return ""
        val colon = raw.indexOf(':')
        if (colon <= 0) return ""
        val scheme = raw.substring(0, colon).lowercase()
        // 只认两种真实 scheme：其它（file:、javascript:、about:…）一律当作未知，
        // 走最严档。`javascript:` 这类由页面自己拼的地址不该由这里放行。
        return if (scheme == "http" || scheme == "https") scheme else ""
    }
}
