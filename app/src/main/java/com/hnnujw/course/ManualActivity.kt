package com.hnnujw.course

import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import com.hnnujw.course.manager.AppearanceSettingsManager
import com.hnnujw.course.manager.resolveDarkTheme

/**
 * 用户手册阅读页：加载 assets 里预排版好的 users_manual.html。
 *
 * 手册的 Markdown 在构建期就转成了带样式的 HTML（见仓库根目录 用户手册.md 与
 * 生成脚本注释），WebView 只负责展示 —— 不引第三方 Markdown 渲染库，离线可用。
 * 主题（浅色/暗色）通过 URL 参数传给页面，与设置里的主题选择保持一致。
 */
class ManualActivity : ComponentActivity() {

    private var webView: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dark = resolveDarkTheme(AppearanceSettingsManager.themeMode, isSystemDark())
        val webView = WebView(this).apply {
            settings.javaScriptEnabled = true   // 仅本地资产：末尾一小段脚本读 ?theme= 切换暗色
            settings.allowFileAccess = true
            setBackgroundColor(0)
            webViewClient = WebViewClient()
            loadUrl("file:///android_asset/users_manual.html?theme=${if (dark) "dark" else "light"}")
        }
        this.webView = webView
        setContentView(webView)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack() else finish()
            }
        })
    }

    private fun isSystemDark(): Boolean =
        (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES

    override fun onDestroy() {
        webView?.destroy()
        webView = null
        super.onDestroy()
    }
}
