package com.hnnujw.course

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
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
import com.hnnujw.course.manager.AppThemeCoordinator
import com.hnnujw.course.manager.UserManager
import com.hnnujw.course.ui.screen.WebLoginAddressBar
import com.hnnujw.course.ui.system.GlassPageScaffold
import com.hnnujw.course.ui.system.SystemIconButton
import com.hnnujw.course.ui.theme.CourseSelectorTheme
import com.hnnujw.course.ykt.YktClient

/**
 * 一卡通**官方页面**的只读查看器（WebView）。
 *
 * ## 为什么需要它（而不是把明细做进原生界面）
 *
 * 校园卡的**逐笔消费明细**（`/campus-card/?name=billList`、`/merchant/?name=billList1`、
 * `/charge/…`）都托管在**另一台服务器的 jQuery + EasyUI 子应用**里，
 * 不带令牌直接访问会 **HTTP 401**；这些页面的渲染逻辑不在 SPA 的 95 个 chunk 里，
 * 无法在客户端内复刻。SPA 本身只提供**区间汇总**（见 [YktClient.turnoverSummary]）。
 *
 * 因此明细一律交给官方页面自行渲染：把已捕获的 `synjones-auth` 令牌拼进 URL，
 * 由 [YktWebActivity] 打开。这与「交电费跳官方充值页」是同一合规策略 ——
 * **本 App 不复制、不抓取、不代理任何支付/账单数据。**
 *
 * ## 与 [YktLoginActivity] 的区别（别合并）
 *
 * - [YktLoginActivity]：**登录用**，会从 Web Storage **导出令牌**并落盘。
 * - 本 Activity：**只读浏览用**，**不导出任何凭据**，只把调用方给的 URL 打开。
 *
 * 两者的存储目录同为 `ykt`（共用一套一卡通 Cookie/sessionStorage，
 * 所以免登录直接看到已认证的账单页），但职责严格分开，避免"随手一改把
 * 登录页的令牌导出逻辑也带到浏览页上"。
 */
class YktWebActivity : ComponentActivity() {

    companion object {
        /** 要打开的 URL（调用方负责拼好令牌等参数）。 */
        const val EXTRA_URL = "ykt_web_url"

        /** 页面标题（可选）。 */
        const val EXTRA_TITLE = "ykt_web_title"

        private var suffixConfigured = false

        /** 打开官方页面（便捷入口）。 */
        fun intent(context: Context, url: String, title: String = "一卡通"): Intent =
            Intent(context, YktWebActivity::class.java).apply {
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_TITLE, title)
            }

        /**
         * 白屏修复：绕开 `vh` / 百分比高度，直接用 `window.innerHeight` 算像素高度。
         *
         * 只做加法（补高度），不改站点颜色/字体/布局。与 [YktLoginActivity] 的补丁同源；
         * 这里内联一份，避免为复用把登录页的私有实现提升为公共 API。
         */
        private val WHITE_SCREEN_FIX_JS = """
            (function(){
              try {
                var h = window.innerHeight || document.documentElement.clientHeight || 0;
                if (!h) return;
                var px = h + 'px';
                function setH(el){ if(!el) return;
                  el.style.setProperty('height', px, 'important');
                  el.style.setProperty('min-height', px, 'important'); }
                setH(document.documentElement); setH(document.body);
                var l = document.getElementById('login-page'); setH(l);
                if (l) { l.style.setProperty('display','block','important');
                         l.style.setProperty('visibility','visible','important');
                         l.style.setProperty('overflow-y','auto','important'); }
              } catch(e){}
            })();
        """.trimIndent()
    }

    private var webView: WebView? = null
    private var startUrl = ""
    private var pageTitle by mutableStateOf("一卡通")
    private var currentUrl by mutableStateOf("")
    private var loadingProgress by mutableFloatStateOf(0f)
    private var pageError by mutableStateOf<String?>(null)

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppThemeCoordinator.wrapContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        UserManager.getInstance().init(applicationContext)

        startUrl = intent.getStringExtra(EXTRA_URL).orEmpty()
        pageTitle = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "一卡通" }
        currentUrl = startUrl
        if (startUrl.isBlank()) {
            finish()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && !suffixConfigured) {
            // 与一卡通登录页**共用**存储目录：这样令牌/sessionStorage 可见，
            // 打开账单页时免去二次认证。
            WebView.setDataDirectorySuffix("ykt")
            suffixConfigured = true
        }

        val browser = createWebView()
        webView = browser

        setContent {
            CourseSelectorTheme {
                BackHandler { navigateBack() }
                GlassPageScaffold(
                    title = pageTitle,
                    subtitle = Uri.parse(currentUrl).host.orEmpty(),
                    modifier = Modifier.imePadding(),
                    onBack = ::navigateBack,
                    actions = {
                        SystemIconButton(Icons.Default.Refresh, "刷新网页", { browser.reload() })
                    },
                ) { padding ->
                    Column(
                        Modifier
                            .fillMaxSize()
                            .padding(padding)
                            .padding(horizontal = 12.dp),
                    ) {
                        WebLoginAddressBar(currentUrl, ::navigateToInput) {
                            browser.loadUrl(startUrl)
                        }
                        Spacer(Modifier.height(8.dp))
                        if (loadingProgress < 1f) {
                            LinearProgressIndicator(
                                progress = { loadingProgress },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        AndroidView(
                            factory = { browser },
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(20.dp)),
                        )
                        pageError?.let { message ->
                            Text(
                                text = message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(vertical = 10.dp),
                            )
                        }
                    }
                }
            }
        }

        browser.loadUrl(startUrl)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView = WebView(this).apply {
        val instance = this
        settings.javaScriptEnabled = true
        AppThemeCoordinator.preserveWebContentColors(settings)
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
            setAcceptThirdPartyCookies(instance, true)
        }
        webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                loadingProgress = newProgress / 100f
            }

            override fun onReceivedTitle(view: WebView, t: String?) {
                // 官方页自己给的标题更准确（如"账单明细"），拿到就替换。
                t?.takeIf { it.isNotBlank() }?.let { pageTitle = it }
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
                // 一卡通 SPA 的 `vh`/百分比高度基准为 0，页首帧会高度塌缩成白屏
                // （根因见 YktLoginActivity 的说明）。这里同样补一次像素高度。
                view.evaluateJavascript(WHITE_SCREEN_FIX_JS, null)
                for (delay in longArrayOf(150L, 500L)) {
                    view.postDelayed({ view.evaluateJavascript(WHITE_SCREEN_FIX_JS, null) }, delay)
                }
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    pageError = "网页暂时无法加载，请点右上角刷新"
                    loadingProgress = 1f
                }
            }

            override fun onReceivedHttpError(
                view: WebView,
                request: WebResourceRequest,
                response: WebResourceResponse,
            ) {
                // 401/403 在下游子应用未携带令牌时会出现，页面通常随后会跳认证或空态，
                // 这里不弹错（与登录页口径一致）。其它错误码才提示。
                if (request.isForMainFrame && response.statusCode !in listOf(401, 403)) {
                    pageError = "网页返回 HTTP ${response.statusCode}，可刷新重试"
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                handleNavigation(request.url.toString(), request.isForMainFrame)

            @Deprecated("API 21 compatibility")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
                handleNavigation(url, true)
        }
    }

    private fun navigateBack() {
        if (webView?.canGoBack() == true) webView?.goBack()
        else finish()
    }

    private fun navigateToInput(input: String) {
        val target = com.hnnujw.course.login.WebLoginNavigation.resolveInput(input)
        if (target == null) {
            Toast.makeText(this, "请输入网址或搜索关键词", Toast.LENGTH_SHORT).show()
        } else {
            webView?.loadUrl(target)
        }
    }

    /** 只允许 http(s) 与 about:blank 在本 WebView 内导航。 */
    private fun handleNavigation(url: String, mainFrame: Boolean): Boolean {
        if (com.hnnujw.course.login.WebLoginNavigation.isWebUrl(url) ||
            url == "about:blank" ||
            url.startsWith("javascript:", true)
        ) {
            return false
        }
        if (mainFrame) Toast.makeText(this, "请使用网页方式继续", Toast.LENGTH_SHORT).show()
        return true
    }

    override fun onDestroy() {
        webView?.apply {
            stopLoading()
            destroy()
        }
        webView = null
        super.onDestroy()
    }
}
