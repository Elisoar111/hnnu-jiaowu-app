package com.hnnujw.course.utils

import android.os.Build
import android.util.Log
import android.webkit.WebView

/**
 * WebView 数据目录后缀的**进程级一次性守护**（API 28+）。
 *
 * 🔴 `WebView.setDataDirectorySuffix` 在同一进程里**只能成功调用一次**，且 WebView
 * 一旦初始化就再也不能调 —— 此前教务网页（"academic"）与一卡通两个网页（"ykt"）
 * 各自持有一个 `companion` 级的 `suffixConfigured` 标记，互不知晓：同一次进程会话里
 * 先后打开两类页面，第二次调用必然抛 `IllegalStateException` 直接崩掉 Activity。
 *
 * 现在的语义是「**先到先得**」：进程内第一次初始化 WebView 的那个页面决定后缀，
 * 之后的所有调用都跳过。P+ 本就只允许一个后缀，"academic / ykt 两个目录"在这个
 * 机制下从来做不到；Cookie 的跨域隔离由 CookieManager 按域名天然保证。
 */
object WebViewDataDir {

    private const val TAG = "WebViewDataDir"

    @Volatile
    private var configured = false

    fun ensureConfigured(suffix: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        if (configured) return
        synchronized(this) {
            if (configured) return
            configured = true
            runCatching { WebView.setDataDirectorySuffix(suffix) }
                .onFailure { Log.w(TAG, "setDataDirectorySuffix failed", it) }
        }
    }
}
