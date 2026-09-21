package com.hnnujw.course

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceResponse
import android.webkit.WebResourceError
import android.webkit.WebChromeClient
import com.hnnujw.course.academic.AcademicUrlPolicy
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.School
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.hnnujw.course.ui.system.GlassPageScaffold
import com.hnnujw.course.ui.system.SystemIconButton
import com.hnnujw.course.ui.system.SystemPrimaryButton
import com.hnnujw.course.ui.theme.CourseSelectorTheme
import com.hnnujw.course.login.WebLoginNavigation
import com.hnnujw.course.ui.screen.WebLoginAddressBar

/**
 * Interactive login browser for captcha and SSO pages. Navigation may cross
 * domains; cookie export remains bound to the configured academic address.
 * It has no JavaScript bridge and uses a separate WebView storage directory.
 */
class AcademicWebViewActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(com.hnnujw.course.manager.AppThemeCoordinator.wrapContext(newBase))
    }
    companion object {
        const val EXTRA_START_URL = "academic_webview_start_url"
        const val EXTRA_ALLOWED_HOSTS = "academic_webview_allowed_hosts"
        const val EXTRA_COOKIE_RESULT = "cookie_result"
        const val EXTRA_COOKIE_URL = "academic_cookie_url"
        /** 额外参与 Cookie 导出的 URL（如 CAS 登录后落在另一域名的教务站），主 URL 的同名 Cookie 优先。 */
        const val EXTRA_EXTRA_COOKIE_URLS = "academic_extra_cookie_urls"
        const val EXTRA_PAGE_URL = "academic_page_url"
        const val EXTRA_SEARCH_KEYWORD = "academic_search_keyword"
        private var suffixConfigured = false
    }

    private var webView: WebView? = null
    private var startUrl = ""
    private var cookieUrl = ""
    private var extraCookieUrls: List<String> = emptyList()
    private var searchUrl = ""
    private var currentUrl by mutableStateOf("")
    private var allowedHosts: Set<String> = emptySet()
    private var loadingProgress by mutableFloatStateOf(0f)
    private var pageError by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startUrl = intent.getStringExtra(EXTRA_START_URL).orEmpty()
        cookieUrl = intent.getStringExtra(EXTRA_COOKIE_URL).orEmpty().ifBlank { startUrl }
        extraCookieUrls = intent.getStringArrayListExtra(EXTRA_EXTRA_COOKIE_URLS).orEmpty()
            .map { it.trim() }
            .filter { WebLoginNavigation.isWebUrl(it) }
        val keyword = intent.getStringExtra(EXTRA_SEARCH_KEYWORD).orEmpty()
        searchUrl = WebLoginNavigation.searchUrl(keyword.ifBlank { "教务系统 登录" })
        // 入口策略：直接打开当前选中学校配置的登录 URL（备用站选择后就是 IP 直连），
        // 不再先开搜索 —— 搜索结果只会列出主站域名，备用站的 IP 搜不到，
        // 用户要多一步手动点「教务入口」按钮，体验差。
        // 搜索入口保留在顶栏按钮与地址栏里，供用户需要时自行触发。
        val initialUrl = if (WebLoginNavigation.isWebUrl(startUrl)) startUrl else searchUrl
        currentUrl = initialUrl
        allowedHosts = (intent.getStringArrayListExtra(EXTRA_ALLOWED_HOSTS).orEmpty())
            .map { normalizeHost(it) }
            .filter { it.isNotBlank() }
            .toSet()
        if (!WebLoginNavigation.isWebUrl(startUrl) || !isCookieUrlAllowed()) {
            Toast.makeText(this, "教务地址不在允许范围内", Toast.LENGTH_LONG).show()
            setResult(Activity.RESULT_CANCELED)
            finish()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && !suffixConfigured) {
            WebView.setDataDirectorySuffix("academic")
            suffixConfigured = true
        }
        val browser = createWebView()
        webView = browser
        setContent {
            CourseSelectorTheme {
                BackHandler { navigateBack() }
                GlassPageScaffold(
                    title = "教务网页登录",
                    subtitle = Uri.parse(currentUrl).host,
                    modifier = Modifier.imePadding(),
                    onBack = ::navigateBack,
                    actions = {
                        SystemIconButton(Icons.Default.School, "教务入口", { browser.loadUrl(startUrl) })
                        SystemIconButton(Icons.Default.Refresh, "刷新网页", { browser.reload() })
                    }
                ) { padding ->
                    Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp)) {
                        WebLoginAddressBar(currentUrl, ::navigateToInput, { browser.loadUrl(searchUrl) })
                        Spacer(Modifier.height(8.dp))
                        if (loadingProgress < 1f) {
                            LinearProgressIndicator(progress = { loadingProgress }, modifier = Modifier.fillMaxWidth())
                        }
                        AndroidView(
                            factory = { browser },
                            modifier = Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(20.dp))
                        )
                        Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                text = pageError ?: "完成学校验证后，点“完成登录”返回应用",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (pageError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            SystemPrimaryButton("完成登录", ::finishWithCookie, Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            CookieManager.getInstance().removeAllCookies { browser.loadUrl(initialUrl) }
        } else {
            // Before API 28 WebView has no per-process storage suffix. Clear only
            // the configured school cookies instead of unrelated browser sessions.
            for (host in allowedHosts) {
                val url = Uri.parse(startUrl).scheme + "://" + host + "/"
                CookieManager.getInstance().getCookie(url).orEmpty().split(';').forEach { part ->
                    val name = part.substringBefore('=').trim()
                    if (name.isNotEmpty()) CookieManager.getInstance().setCookie(url, name + "=; Max-Age=0; Path=/")
                }
            }
            browser.loadUrl(initialUrl)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView = WebView(this).apply {
        val webViewInstance = this
        settings.javaScriptEnabled = true
        com.hnnujw.course.manager.AppThemeCoordinator.preserveWebContentColors(settings)
        settings.domStorageEnabled = true
        settings.defaultTextEncodingName = "UTF-8"
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.javaScriptCanOpenWindowsAutomatically = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webViewInstance, true)
        }
        webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                loadingProgress = newProgress / 100f
            }
        }
        webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                pageError = null
                currentUrl = url
                loadingProgress = 0f
            }
            override fun onPageFinished(view: WebView, url: String) {
                loadingProgress = 1f
                currentUrl = url
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    pageError = "网页暂时无法加载，请点击右上角刷新"
                    loadingProgress = 1f
                }
            }
            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                if (request.isForMainFrame) pageError = "网页返回 HTTP ${response.statusCode}，可修改网址或搜索学校入口"
            }
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                if (request.url.scheme in setOf("data", "blob", "about") || WebLoginNavigation.isWebUrl(request.url.toString())) return null
                return WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", emptyMap(), java.io.ByteArrayInputStream(ByteArray(0)))
            }
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                return handleNavigation(request.url.toString(), request.isForMainFrame)
            }

            @Deprecated("API 21 compatibility")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                return handleNavigation(url, true)
            }
        }
    }

    private fun finishWithCookie() {
        if (!isCookieUrlAllowed()) return
        // 主 URL 的 Cookie 优先，额外 URL（如 CAS SSO 落在另一域名的教务站）只补缺；
        // 同名 Cookie 不覆盖，保证教务主站已有会话时不被干扰。
        val merged = linkedMapOf<String, String>()
        for (url in listOf(cookieUrl) + extraCookieUrls) {
            CookieManager.getInstance().getCookie(url).orEmpty().split(';').forEach { part ->
                val name = part.substringBefore('=').trim()
                val value = part.substringAfter('=', "").trim()
                if (name.isNotEmpty() && value.isNotEmpty() && !merged.containsKey(name)) {
                    merged[name] = "$name=$value"
                }
            }
        }
        val cookie = merged.values.joinToString("; ").trim()
        if (cookie.isBlank()) {
            Toast.makeText(this, "请先登录并进入 ${Uri.parse(cookieUrl).host} 的教务主页", Toast.LENGTH_LONG).show()
            return
        }
        CookieManager.getInstance().flush()
        setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_COOKIE_RESULT, cookie).putExtra(EXTRA_PAGE_URL, webView?.url))
        finish()
    }

    private fun navigateBack() {
        if (webView?.canGoBack() == true) webView?.goBack()
        else { setResult(Activity.RESULT_CANCELED); finish() }
    }

    private fun isCookieUrlAllowed(): Boolean =
        AcademicUrlPolicy.isAllowed(cookieUrl, Uri.parse(startUrl).scheme.orEmpty(), allowedHosts)

    private fun navigateToInput(input: String) {
        val target = WebLoginNavigation.resolveInput(input)
        if (target == null) Toast.makeText(this, "请输入网址或搜索关键词", Toast.LENGTH_SHORT).show()
        else webView?.loadUrl(target)
    }

    private fun handleNavigation(url: String, mainFrame: Boolean): Boolean {
        if (WebLoginNavigation.isWebUrl(url) || url == "about:blank" || url.startsWith("javascript:", true)) return false
        if (mainFrame) Toast.makeText(this, "请使用网页方式继续登录", Toast.LENGTH_SHORT).show()
        return true
    }

    private fun normalizeHost(raw: String): String {
        val value = raw.trim().removePrefix("http://").removePrefix("https://").substringBefore('/')
        return value.lowercase().trim('.')
    }

    override fun onDestroy() {
        webView?.apply { stopLoading(); destroy() }
        webView = null
        super.onDestroy()
    }
}
