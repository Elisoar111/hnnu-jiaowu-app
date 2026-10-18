package com.hnnujw.course

import android.content.Context
import android.content.Intent
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
 * 长文档阅读页：用户手册与隐私政策共用。
 *
 * 两者正文都在构建期 / 编写期准备好，放在 `assets/` 下，是**结构化块**而不是 HTML；
 * 这里只负责读出来交给 [UserManualScreen]。好处是排版完全跟随应用主题
 * （浅色 / 暗色 / 壁纸玻璃），目录与搜索都是原生的，也不必维护一份 CSS
 * 或往页面里注入脚本。
 *
 * ## 为什么一个 Activity 承载两份文档
 *
 * 隐私政策与用户手册的展示需求完全一致：分章、可搜索、有目录、可返回。
 * 为它再开一个 Activity 只会多一份生命周期代码与一处 Manifest 注册，
 * 而两者唯一的差别是**读哪个 asset** —— 用一个 Intent extra 表达就够了。
 *
 * 读的是几十 KB 的本地资产、没有二次网络请求，直接在主线程解析一次即可，
 * 不值得为它铺一套异步加载状态。
 */
class ManualActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppThemeCoordinator.wrapContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 缺省是用户手册 —— 既有入口（intent 不带 extra）行为不变。
        val assetName = intent.getStringExtra(EXTRA_ASSET)
            ?: UserManual.PRIVACY_ASSET_NAME.takeIf { intent.getBooleanExtra(EXTRA_PRIVACY, false) }
            ?: UserManual.ASSET_NAME
        setContent {
            CourseSelectorTheme {
                val context = LocalContext.current
                val manual = remember(assetName) { UserManual.load(context, assetName) }
                UserManualScreen(manual = manual, onBack = { finish() })
            }
        }
    }

    companion object {
        private const val EXTRA_ASSET = "extra_asset"
        private const val EXTRA_PRIVACY = "extra_privacy"

        /** 打开用户手册（既有行为）。 */
        fun intent(context: Context): Intent = Intent(context, ManualActivity::class.java)

        /** 打开隐私政策。 */
        fun privacyIntent(context: Context): Intent =
            Intent(context, ManualActivity::class.java)
                .putExtra(EXTRA_ASSET, UserManual.PRIVACY_ASSET_NAME)
                .putExtra(EXTRA_PRIVACY, true)
    }
}
