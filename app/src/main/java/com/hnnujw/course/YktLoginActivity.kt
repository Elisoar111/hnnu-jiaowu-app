package com.hnnujw.course

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
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
import androidx.compose.foundation.layout.Arrangement
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
import com.hnnujw.course.manager.AppThemeCoordinator
import com.hnnujw.course.manager.UserManager
import com.hnnujw.course.ui.screen.WebLoginAddressBar
import com.hnnujw.course.ui.system.GlassPageScaffold
import com.hnnujw.course.ui.system.SystemIconButton
import com.hnnujw.course.ui.system.SystemPrimaryButton
import com.hnnujw.course.ui.theme.CourseSelectorTheme
import com.hnnujw.course.ykt.YktClient
import com.hnnujw.course.ykt.YktStore

/**
 * 一卡通的登录页（WebView + 统一身份认证）。
 *
 * ## 为什么必须是 WebView，而不是账号密码表单
 *
 * 淮师一卡通前端 `frontInfo.loginType` **只有 `sso`**（wisedu CAS）：
 * 站点根本没有对外暴露账密登录接口，客户端无法"自己换一个 token 出来"。
 * 因此唯一正确的做法是让用户在官方页面上走完 CAS，再从 Cookie 里接管
 * `synjones-auth` —— 与二课那种"`POST /token` 换 Bearer"是完全不同的形态。
 *
 * ## 为什么不做静默登录（重要，别以后又改回去）
 *
 * 站点的 `synjones-auth` 里其实带了 `access_token`，理论上可以拿
 * 用户保存的密码去 `berserker-auth/oauth/token` 静默续期。**本实现刻意不做**：
 *
 * 1. 那需要用户的一卡通密码。用户从未表示愿意把它交给"一卡通"这个新系统 ——
 *    教务密码 ≠ 一卡通密码，不能想当然地复用（见 [YktStore] 的凭据隔离说明）；
 * 2. 静默登录会在用户不知情的情况下访问另一个业务系统，属于越界；
 * 3. 现实收益很小：令牌有效期足够长，用户偶尔打开一次页面即可自动续上。
 *
 * 所以这里只提供**显式登录**：用户在 WebView 里自己走一遍 CAS。
 * 登录成功后令牌落盘，后台电费巡检（[com.hnnujw.course.ykt.YktAlertReceiver]）
 * 就能一直用到令牌过期为止；过期后界面会提示重新登录，而不是偷偷替用户登录。
 *
 * ## 与 [AcademicWebViewActivity] 的关系
 *
 * 复用了同一套骨架（进度条 / 地址栏 / 拦截非 http 协议 / 独立 WebView 存储目录），
 * 但**取的不是 Cookie 字符串而是其中一个字段**，且目标域名不同，
 * 因此独立成一个 Activity 而不是给它加参数 —— 两种 Cookie 导出语义混在一起
 * 很容易改出"教务登录顺手把一卡通 Cookie 也导出去"这种串味问题。
 */
class YktLoginActivity : ComponentActivity() {

    companion object {
        /** 登录成功：回传捕获到的 `synjones-auth` 令牌值。 */
        const val EXTRA_TOKEN = "ykt_login_token"

        /** 非 http(s) 协议一律拦截，避免 WebView 把 `synjones-*://` 当成可导航地址。 */
        private const val TAG = "YktLogin"

        /**
         * 一卡通域名白名单。只允许在这一个域名下取 Cookie，
         * 防止 CAS 跳转到第三方域名时把别人的 Cookie 当成令牌存下来。
         */
        private val ALLOWED_HOSTS = setOf("yktapp.hnnu.edu.cn")

        private var suffixConfigured = false

        /**
         * 白屏修复脚本。
         *
         * ## 已确证的根因（真机探针实证，别再猜别的）
         *
         * CAS 登录页 `common.css` 用**百分比高度链**撑开整页：
         * ```
         * html, body { height: 100% }
         * #login-page { height: 100% }
         * ```
         * 真机探针在 `onPageFinished` 后连采 6 次稳定得到：
         * ```
         * innerH = 500, deH(html) = 501      ← 视口 / <html> 高度正常
         * bodyCS = "0px/static/block/visible" ← <body> computed height = 0px
         * pageCS = "0px/..."                  ← #login-page computed height = 0px
         * hasForm = true, formVisible = true, jq/initCas = function, system.h5 = true
         * ```
         * ⇒ **DOM 完整、JS 正常、UA 判定正确**，纯粹是高度链塌缩；
         * UI 自动化读 DOM 能读到文字，但屏幕上**全白**（截图已确认）。
         *
         * ## ★ 关键：这个 WebView 里 `vh` 单位解析为 0
         *
         * 实测注入 `height:100vh` 的探测元素，`getBoundingClientRect().height` =
         * **0**（`testVh: 0`）。所以：
         * - 站点的 `height:100%` 链塌缩（父级基准为 0）；
         * - **我第一版用 `min-height:100vh` 的补丁同样无效**（那时候 `patched bodyH=0`）。
         *
         * 触发条件是 `useWideViewPort + loadWithOverviewMode` 组合下，
         * 页面**首次布局时视口尚未确定**，Chromium 把 `vh`/百分比基准当成 0
         * 并**缓存不复算**。教务页不依赖 `vh`/`height:100%` 所以没事。
         *
         * ## 修法：绕开 `vh` 与百分比，直接用**像素值**
         *
         * 取 `window.innerHeight`（实测正常 = 500）算出 px，写成内联 `height`，
         * 并给 `<html>/<body>/#login-page` 一起设；同时在 `resize` 时重算，
         * 避免横竖屏或软键盘弹出后高度不更新。
         *
         * 只做加法（补高度），**不改站点颜色/字体/其它布局**。
         */
        /**
         * 修复逻辑的 **JS 函数体**（返回一个可直接赋给 `window.__yktFix` 的
         * `function(){...}` 字面量）。
         *
         * 单独抽出来是为了让 [applyFixAndBindJs] 只做"挂载 + 绑定一次"，
         * 避免在 Kotlin 三引号字符串里嵌套调用（`$fn()` 会被当成函数引用而非调用）。
         */
        private fun fixFunctionJs(): String = """
            function(){
              try {
                var h = window.innerHeight || document.documentElement.clientHeight || 0;
                if (!h) return 'NO_HEIGHT';
                var px = h + 'px';
                function setH(el){
                  if (!el) return;
                  el.style.setProperty('height', px, 'important');
                  el.style.setProperty('min-height', px, 'important');
                }
                setH(document.documentElement);
                setH(document.body);
                var page = document.getElementById('login-page');
                setH(page);
                if (page) {
                  page.style.setProperty('display', 'block', 'important');
                  page.style.setProperty('visibility', 'visible', 'important');
                  page.style.setProperty('overflow-y', 'auto', 'important');
                }
                void document.body.offsetHeight;
                return 'patched h=' + h + ' bodyH=' + (document.body ? document.body.clientHeight : -1);
              } catch(e){ return 'PATCH_ERR:' + e.message; }
            }
        """.trimIndent()

        /**
         * 挂载修复函数（仅一次）+ 立即执行一次，并在视口变化时重算。
         *
         * 触发 + 延时补跑见 `onPageFinished`：302 后的首帧视口可能还没稳定，
         * 那时 `innerHeight` 可能还是旧值，所以需要多次机会。
         */
        private fun applyFixAndBindJs(): String = """
            (function(){
              if (!window.__yktFix) { window.__yktFix = ${fixFunctionJs()}; }
              var first = window.__yktFix();
              if (!window.__yktFixBound) {
                window.__yktFixBound = true;
                window.addEventListener('resize', function(){ window.__yktFix(); });
                window.addEventListener('orientationchange', function(){ setTimeout(function(){ window.__yktFix(); }, 300); });
              }
              return first;
            })();
        """.trimIndent()

        /**
         * 采集令牌的 JS：**从 Web Storage 读**，而不是从 Cookie。
         *
         * ## 为什么是 Web Storage（反混淆 + 真机双重确证）
         *
         * SPA 的 store `Login` mutation 把令牌写进 **`sessionStorage`**：
         * ```js
         * Login(e,t){ t.noCatch||(sessionStorage.setItem("access_token",t.token),
         *                          sessionStorage.setItem("token_type",t.token_type)), … }
         * ```
         * 真机上业务首页明明已登录，`CookieManager` 却**没有** `synjones-auth`
         * （`docCookie` 实测为空串），与源码结论一致。所以这里读 `sessionStorage`
         * （再兜底 `localStorage` 与 URL 的 `synjones-auth` 查询参数，后者是
         * App/小程序链的传递方式）。
         *
         * 返回 `{"access_token":"…","token_type":"…"}` 形态的 JSON 交给
         * Kotlin 侧 [YktClient.extractAuthFromStorageJson] 解析（纯函数、可单测）。
         * 取不到时返回空串。
         */
        private val CAPTURE_TOKEN_JS = """
            (function(){
              try {
                function pick(s){
                  try {
                    if (!s) return null;
                    var a = s.getItem('access_token');
                    return a ? { a: a, t: s.getItem('token_type') } : null;
                  } catch(e){ return null; }
                }
                var p = pick(window.sessionStorage) || pick(window.localStorage);
                var out = {};
                if (p) { out.access_token = String(p.a); out.token_type = p.t == null ? '' : String(p.t); }
                if (!out.access_token) {
                  var m = location.href.match(/[?&]synjones-auth=([^&#]+)/);
                  if (m) out.access_token = decodeURIComponent(m[1]);
                }
                return JSON.stringify(out);
              } catch(e){ return ''; }
            })();
        """.trimIndent()
    }

    private var webView: WebView? = null
    private var startUrl = ""
    private var currentUrl by mutableStateOf("")
    private var loadingProgress by mutableFloatStateOf(0f)
    private var pageError by mutableStateOf<String?>(null)
    /** 已捕获到令牌：按钮文案随之改变，避免用户反复点。 */
    private var capturedToken by mutableStateOf("")

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppThemeCoordinator.wrapContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        UserManager.getInstance().init(applicationContext)

        startUrl = YktStore.loginUrl()
        currentUrl = startUrl

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && !suffixConfigured) {
            // 与教务登录分开的存储目录：两套系统 Cookie 互不可见，
            // 也避免教务登录清 Cookie 时把一卡通会话一起清掉。
            WebView.setDataDirectorySuffix("ykt")
            suffixConfigured = true
        }

        val browser = createWebView()
        webView = browser

        setContent {
            CourseSelectorTheme {
                BackHandler { navigateBack() }
                GlassPageScaffold(
                    title = "一卡通登录",
                    subtitle = Uri.parse(currentUrl).host.orEmpty(),
                    modifier = Modifier.imePadding(),
                    onBack = ::navigateBack,
                    actions = {
                        SystemIconButton(Icons.Default.School, "一卡通首页", { browser.loadUrl(startUrl) })
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
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text(
                                text = pageError ?: hintText(),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (pageError != null) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            SystemPrimaryButton(
                                text = if (capturedToken.isNotBlank()) "完成登录" else "我已登录完成",
                                onClick = ::finishWithToken,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }

        browser.loadUrl(startUrl)
    }

    private fun hintText(): String = if (capturedToken.isNotBlank()) {
        "已获取到一卡通登录状态，点下方按钮返回即可。"
    } else {
        "请在页面内用学校统一身份认证登录。登录成功后本页会自动识别，无需手动操作。"
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
        // 与教务登录一致：不给文件/content 访问权限，减少攻击面
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
                // ⚠️ 令牌要靠 **每个页面加载完成后主动跑 JS** 来采集。
                //
                // 不能只依赖 shouldOverrideUrlLoading：CAS 登录成功后是**服务端 302**
                // 直接跳回 yktapp 并写 sessionStorage，客户端根本收不到"地址变化"回调
                //（302 由 WebView 内部跟随）。所以每页结束都采一次，采到即停。
                sniffToken()
                // 修白屏：本 WebView 里 `vh`/百分比高度基准为 0，页首帧高度链塌缩。
                // 立即修一次，再延时补两次（302 后的首帧视口可能还没稳定）。
                view.evaluateJavascript(applyFixAndBindJs(), null)
                for (delay in longArrayOf(150L, 500L)) {
                    view.postDelayed({
                        view.evaluateJavascript("(window.__yktFix ? window.__yktFix() : 'no-fix')", null)
                    }, delay)
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
                // 401/403 在这里不是错误：CAS 未登录时后端就可能回 401，
                // 页面随后会跳到认证页。只有其它错误码才提示。
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

    /**
     * 从 WebView 里探一次令牌（**异步**：`evaluateJavascript` 必须在 UI 线程回调）。
     *
     * ## 为什么读 Web Storage 而不是 Cookie（重要，别再改回去）
     *
     * 站点把 `access_token` 写进 **`sessionStorage`**（见 [CAPTURE_TOKEN_JS] 注释）。
     * 真机确证：业务首页已登录，`CookieManager.getCookie()` 在两个候选地址
     * 都读不到 `synjones-auth` —— 只读 Cookie 必然失败，表现为"点按钮没反应"。
     *
     * ## 为什么"只取一次"（已捕获就不再覆盖）
     *
     * 页面加载完成后还会持续发匿名请求，其中任何一个都可能让
     * `sessionStorage` 被更新成空值。若每次都覆盖，就会出现"先拿到令牌、
     * 随后又被清空"的竞态。
     *
     * ## 为什么要过白名单（必须）
     *
     * 登录流程**跨域**：一卡通业务在 `yktapp.hnnu.edu.cn`，CAS 在
     * `xxmh.hnnu.edu.cn`（见 [YktClient.CAS_HOST]）。CAS 域下同名键语义
     * 完全不同，**绝不能**把它的值当一卡通令牌落盘。因此只在
     * [isAllowedHost] 通过的页面上采。
     */
    private fun sniffToken(onDone: (() -> Unit)? = null) {
        if (capturedToken.isNotBlank()) {
            onDone?.invoke()
            return
        }
        val view = webView
        if (view == null || !isAllowedHost(currentUrl)) {
            onDone?.invoke()
            return
        }
        view.evaluateJavascript(CAPTURE_TOKEN_JS) { raw ->
            // 返回值是 JSON 字符串再套一层引号（例："{\"access_token\":\"…\"}"）
            val json = raw?.trim()?.trim('"')?.replace("\\\"", "\"")?.replace("\\\\", "\\").orEmpty()
            val value = YktClient.extractAuthFromStorageJson(json)
            if (value.isNotBlank() && capturedToken.isBlank()) {
                capturedToken = value
                Log.i(TAG, "已捕获一卡通登录令牌（来源 sessionStorage）")
            }
            onDone?.invoke()
        }
    }

    /**
     * 收集令牌并返回。
     *
     * 点按钮时**再探一次**（异步）：`onPageFinished` 可能早于令牌落地，
     * 且 `evaluateJavascript` 是回调式的，所以拿不到时只能提示用户再点一次，
     * 不能同步返回。
     */
    private fun finishWithToken() {
        sniffToken {
            if (capturedToken.isBlank()) {
                Toast.makeText(
                    this,
                    "还没检测到登录状态，请先在页面里完成学校统一身份认证",
                    Toast.LENGTH_LONG,
                ).show()
                return@sniffToken
            }
            CookieManager.getInstance().flush()
            val accountKey = UserManager.getInstance().currentAccountStorageKey
            YktStore.saveToken(this, accountKey, capturedToken)
            setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_TOKEN, capturedToken))
            Toast.makeText(this, "一卡通登录成功", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun navigateBack() {
        if (webView?.canGoBack() == true) webView?.goBack()
        else {
            setResult(Activity.RESULT_CANCELED)
            finish()
        }
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
        if (mainFrame) Toast.makeText(this, "请使用网页方式继续登录", Toast.LENGTH_SHORT).show()
        return true
    }

    /**
     * 判断某个 URL 是否属于可导出令牌的白名单域名。
     *
     * 实现委托给纯函数 [WebLoginNavigation.hostMatches]（可单测），
     * 这里只负责把 [ALLOWED_HOSTS] 喂进去。
     */
    fun isAllowedHost(url: String): Boolean =
        com.hnnujw.course.login.WebLoginNavigation.hostMatches(url, ALLOWED_HOSTS)

    override fun onDestroy() {
        webView?.apply {
            stopLoading()
            destroy()
        }
        webView = null
        super.onDestroy()
    }
}
