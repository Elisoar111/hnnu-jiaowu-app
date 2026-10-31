package com.hnnujw.course.ykt

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * 一卡通原生账号密码登录：**在应用进程里用一个不显示的 WebView 跑学校真实的登录页**。
 *
 * ## 为什么不再自己拼 HTTP 表单
 *
 * 早先按浏览器表单一字不差地 POST `username` / `password` / `captcha` / `execution` /
 * `_eventId` / `geolocation` / `key`，服务器一律 401。抓页面源码逐表单 dump 后找到原因：
 * 校方 CAS（金智 zs 主题）的账号密码框**根本没有 `name` 属性**，
 *
 * ```html
 * <input type="text" class="lg-input username" id="username">
 * <input type="password" class="lg-input password" id="passwrod">
 * ```
 *
 * 真实登录是页面 JS 读 `#username` / `#passwrod` 的 `.value` 自己拼包提交，服务端压根
 * 不认我们手写的字段；而故意输错密码返回的响应与正确密码**字节相同**，所以连"密码错了"
 * 都判断不出来。这就是"网页能登、原生不能"的全部原因。
 *
 * ## 令牌到底存在哪里（关键，别再去 Cookie 里找）
 *
 * CAS 换票成功后，浏览器被送到 `/plat/loginTransit`，那页前端把令牌写进 **sessionStorage**：
 *
 * ```js
 * Login(e,t){ t.noCatch || (sessionStorage.setItem("access_token", t.token), …) }
 * ```
 *
 * 真机验证：登录后 `document.cookie` **是空串**、`CookieManager` 里也**没有**
 * `synjones-auth`，而 `sessionStorage.access_token` 里有 JWT。所以令牌只能从 Web Storage
 * 读（[YktClient.extractAuthFromStorageJson]），Cookie 那条只是兜底。
 *
 * ## 为什么"找不到输入框"是误报（实测踩到的坑）
 *
 * 第一版只在 Cookie 里找令牌、找不到就判定失败。但应用里的 CAS 会话（`SESSION` Cookie）
 * 是**持久化**的：用户先前用「网页登录」成功过一次，隐藏 WebView 再打开入口时 CAS 直接
 * 静默换票跳到 `/plat/loginTransit` —— 那页**本来就没有任何输入框**，于是被误判成
 * "学校改了登录页结构"。
 *
 * 现在每一步都同时做三件事：读 Web Storage 令牌 → 读 Cookie 令牌 → 有账号密码框才提交。
 * 已经登录（SSO 直通）时第一步就直接成功；只有真的既没令牌也没输入框才继续等，
 * 到超时再把**实际停在哪一页、页面上写了什么**报出来，便于定位。
 *
 * 密码不落盘：只在一次 JS 调用里出现，WebView 用完即 `destroy()`。
 */
object YktCasLogin {

    /** 登录结果。**只要拿不到令牌一律算失败**，不做"假成功"。 */
    sealed interface Result {
        data class Success(val auth: String) : Result
        data class Failure(val message: String) : Result
    }

    private const val TAG = "YktCasLogin"

    /** 总时限。换票 + SPA 首屏实测十几秒，留足余量。 */
    private const val TIMEOUT_MS = 75_000L

    /** 单步（读令牌 / 提交表单）的轮询间隔。 */
    private const val STEP_MS = 500L

    /**
     * 桌面 Chrome 的 UA。
     *
     * 移动 UA 会拿到另一套 H5 主题，表单结构与注入用的 id 都不同；用户"网页能登录"用的
     * 就是桌面版，所以必须伪装成桌面浏览器。
     */
    private const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

    /**
     * 执行一次登录。
     *
     * 内部自己切到 `Dispatchers.Main`（WebView 只能在有 Looper 的线程上创建和驱动），
     * 在协程里直接 await 即可。
     *
     * @param username 学号（校方 CAS 的账号就是学号）
     * @param password 统一身份认证密码，仅本次使用，不保存
     */
    suspend fun login(context: Context, username: String, password: String): Result {
        if (username.isBlank()) return Result.Failure("请先填写学号")
        if (password.isEmpty()) return Result.Failure("请先填写统一身份认证密码")
        return withContext(Dispatchers.Main) {
            drive(context.applicationContext, username.trim(), password)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun drive(
        app: Context,
        username: String,
        password: String,
    ): Result = suspendCancellableCoroutine { cont ->
        val handler = Handler(Looper.getMainLooper())
        var done = false
        var steps = 0
        var lastUrl = ""
        var lastBody = ""
        var lastDid = ""
        var pageError = ""

        com.hnnujw.course.utils.WebViewDataDir.ensureConfigured("ykt")

        val web = WebView(app).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.cacheMode = WebSettings.LOAD_NO_CACHE
            settings.userAgentString = DESKTOP_UA
            settings.allowFileAccess = false
            settings.allowContentAccess = false
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)

        val finish: (Result) -> Unit = { result ->
            if (!done) {
                done = true
                handler.removeCallbacksAndMessages(null)
                runCatching { web.stopLoading() }
                runCatching { web.destroy() }
                if (cont.isActive) cont.resume(result)
            }
        }

        fun cookieToken(): String {
            val manager = CookieManager.getInstance()
            val fromYkt =
                YktClient.extractAuthFromCookie(manager.getCookie(YktClient.BASE_URL).orEmpty())
            if (fromYkt.isNotEmpty()) return fromYkt
            return YktClient.extractAuthFromCookie(
                manager.getCookie(YktClient.BASE_URL + "/plat?name=loginTransit").orEmpty()
            )
        }

        val stepScript = buildStepScript(username, password)

        /** 一步：读 Web Storage 令牌 → 读 Cookie 令牌 → 没有令牌才提交表单。 */
        val stepper = object : Runnable {
            override fun run() {
                if (done) return
                steps++
                web.evaluateJavascript(stepScript) { raw ->
                    if (done) return@evaluateJavascript
                    val text = jsUnquote(raw)
                    // 1) Web Storage 里的 access_token（正常登录路径）
                    if (text.isNotEmpty()) {
                        val fromStorage = YktClient.extractAuthFromStorageJson(text)
                        if (fromStorage.isNotEmpty()) {
                            Log.i(TAG, "从 sessionStorage 拿到令牌")
                            finish(Result.Success(fromStorage))
                            return@evaluateJavascript
                        }
                    }
                    // 2) Cookie 兜底（部分环境会把令牌种成 Cookie）
                    val fromCookie = cookieToken()
                    if (fromCookie.isNotEmpty()) {
                        Log.i(TAG, "从 Cookie 拿到令牌")
                        finish(Result.Success(fromCookie))
                        return@evaluateJavascript
                    }
                    // 3) 记录现场，供失败时报告
                    if (text.isNotEmpty()) {
                        runCatching {
                            val json = JSONObject(text)
                            lastUrl = json.optString("url")
                            lastBody = json.optString("body")
                            lastDid = json.optString("did")
                        }
                    }
                    handler.postDelayed(this, STEP_MS)
                }
            }
        }

        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                if (done) return
                if (url != null) lastUrl = url
                stepper.run()
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?,
            ) {
                if (request?.isForMainFrame == true) {
                    pageError = error?.description?.toString().orEmpty()
                }
            }
        }

        handler.postDelayed({
            val detail = buildString {
                if (lastUrl.isNotBlank()) append("停在：").append(lastUrl)
                if (lastBody.isNotBlank()) {
                    if (isNotEmpty()) append("；")
                    append("页面内容：").append(lastBody.take(80))
                }
            }
            val message = when {
                pageError.isNotEmpty() -> "网络错误：$pageError"
                lastDid == "nofield" && detail.isNotEmpty() ->
                    "登录页没有出现账号密码框（$detail）"
                detail.isNotEmpty() -> "登录没有完成（$detail）"
                else -> "登录超时：学校服务器没有返回结果，可以改用网页登录"
            }
            finish(Result.Failure(message))
        }, TIMEOUT_MS)

        handler.postDelayed(stepper, STEP_MS)

        cont.invokeOnCancellation {
            done = true
            handler.removeCallbacksAndMessages(null)
            handler.post {
                runCatching { web.stopLoading() }
                runCatching { web.destroy() }
            }
        }

        web.loadUrl(YktClient.loginEntryUrl())
    }

    /**
     * 单步脚本：读令牌 → 否则填表提交 → 否则报告现场。
     *
     * 返回 `JSON.stringify({url, did, body, access_token, token_type})`：
     * - `did=submit/js`   已经点过登录（下一步会在新页面上继续读令牌）
     * - `did=form`        没有 `userLogin()`，补 `name` 后提交了真实表单
     * - `did=waiting`     本页已提交过，正在等跳转（`__yktSubmitting` 防重复提交）
     * - `did=nofield`     页面上没有账号密码框（已登录 SSO 直通时属正常，继续等令牌）
     *
     * 令牌优先从 `sessionStorage` 读（真机实测 `access_token` 只在这里，Cookie 是空的），
     * 再兜底 `localStorage` 与 URL 上的 `synjones-auth` 查询参数。
     */
    private fun buildStepScript(username: String, password: String): String {
        val user = JSONObject.quote(username)
        val pass = JSONObject.quote(password)
        return """
            (function(){
              var out = { url: String(location.href) };
              try {
                var a = null, t = '';
                try {
                  if (window.sessionStorage) {
                    a = window.sessionStorage.getItem('access_token');
                    t = window.sessionStorage.getItem('token_type') || '';
                  }
                } catch (e) { }
                if (!a) {
                  try {
                    if (window.localStorage) {
                      a = window.localStorage.getItem('access_token');
                      t = t || (window.localStorage.getItem('token_type') || '');
                    }
                  } catch (e) { }
                }
                if (a) {
                  out.access_token = String(a);
                  out.token_type = String(t || '');
                  out.did = 'token';
                  return JSON.stringify(out);
                }
                var m = out.url.match(/[?&]synjones-auth=([^&#]+)/);
                if (m) {
                  out.access_token = decodeURIComponent(m[1]);
                  out.did = 'urltoken';
                  return JSON.stringify(out);
                }
              } catch (e) { }
              try {
                function textInput(d) {
                  return d.getElementById('username')
                    || d.querySelector('input[name="username"]')
                    || d.querySelector('input[type="text"]');
                }
                function passInput(d) {
                  // 校方把 password 的 id 拼成了 passwrod，两种都认。
                  return d.getElementById('passwrod')
                    || d.getElementById('password')
                    || d.querySelector('input[name="password"]')
                    || d.querySelector('input[type="password"]');
                }
                var d = document, iu = textInput(d), ip = passInput(d);
                if (iu && ip) {
                  if (!window.__yktSubmitting) {
                    window.__yktSubmitting = 1;
                    iu.value = $user;
                    ip.value = $pass;
                    try {
                      iu.dispatchEvent(new Event('input', { bubbles: true }));
                      ip.dispatchEvent(new Event('input', { bubbles: true }));
                      iu.dispatchEvent(new Event('change', { bubbles: true }));
                      ip.dispatchEvent(new Event('change', { bubbles: true }));
                    } catch (e) { }
                    try {
                      if (typeof userLogin === 'function') { userLogin('login'); out.did = 'js'; return JSON.stringify(out); }
                    } catch (e) { }
                    var f = iu.form || ip.form || (iu.closest ? iu.closest('form') : null);
                    if (f) {
                      // 原页面这两个框没有 name（就是为了让 JS 自己拼包），
                      // 补上后浏览器提交会连同 execution / captcha / key / geolocation 一起带上。
                      iu.setAttribute('name', 'username');
                      ip.setAttribute('name', 'password');
                      try { f.submit(); out.did = 'form'; } catch (e) { out.did = 'formfail'; }
                      return JSON.stringify(out);
                    }
                    out.did = 'noform';
                    return JSON.stringify(out);
                  }
                  out.did = 'waiting';
                  return JSON.stringify(out);
                }
              } catch (e) { }
              out.did = 'nofield';
              try { out.body = (document.body ? document.body.innerText : '').slice(0, 200); } catch (e) { }
              return JSON.stringify(out);
            })()
        """.trimIndent()
    }

    /**
     * 把 `evaluateJavascript` 回传的 JSON 字符串字面量拆回普通文本。
     *
     * WebView 回传的是 JSON 编码后的字符串，直接当文本用会把反斜杠带进来，
     * 导致后面的键值匹配全部失效。
     */
    private fun jsUnquote(raw: String?): String {
        if (raw.isNullOrEmpty() || raw == "null") return ""
        val trimmed = raw.trim()
        val body = when {
            trimmed.length >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"") ->
                trimmed.substring(1, trimmed.length - 1)
            trimmed.startsWith("\"") -> trimmed.substring(1)
            else -> trimmed
        }
        return body
            .replace("\\r", "\r")
            .replace("\\n", "\n")
            .replace("\\/", "/")
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
    }
}