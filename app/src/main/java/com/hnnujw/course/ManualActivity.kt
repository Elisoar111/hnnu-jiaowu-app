package com.hnnujw.course

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.hnnujw.course.manager.AppThemeCoordinator
import com.hnnujw.course.manual.UserManual
import com.hnnujw.course.ui.screen.UserManualScreen
import com.hnnujw.course.ui.theme.CourseSelectorTheme

/**
 * 用户手册阅读页：原生 Compose 渲染，**不再用 WebView**。
 *
 * 手册正文在构建期由 `scripts/build_manual.py` 从仓库根目录的 `用户手册.md`
 * 解析成 `assets/users_manual.json`（结构化块，不是 HTML），这里只负责把它读出来
 * 交给 [UserManualScreen]。好处是排版完全跟随应用主题（浅色 / 暗色 / 壁纸玻璃），
 * 目录与搜索都是原生的，也不必维护一份 CSS 或往页面里注入脚本。
 *
 * 读的是 40KB 左右的本地资产、没有二次网络请求，直接在主线程解析一次即可，
 * 不值得为它铺一套异步加载状态。
 */
class ManualActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppThemeCoordinator.wrapContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CourseSelectorTheme {
                val context = LocalContext.current
                val manual = remember { UserManual.load(context) }
                UserManualScreen(manual = manual, onBack = { finish() })
            }
        }
    }
}
