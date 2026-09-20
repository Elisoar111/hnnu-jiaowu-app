package com.hnnujw.course

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.hnnujw.course.manager.AppThemeCoordinator
import com.hnnujw.course.manager.UserManager
import com.hnnujw.course.ui.screen.MessageCenterScreen
import com.hnnujw.course.ui.theme.CourseSelectorTheme
import java.util.ArrayList

/**
 * 原生消息中心宿主页：复用已登录的教务会话拉取并解析消息列表/详情；
 * 当原生解析失败时，回退到教务 WebView（[AcademicWebViewActivity]）打开消息页。
 */
class MessageCenterActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppThemeCoordinator.wrapContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val user = UserManager.getInstance()
        val school = user.getCurrentSchool()
        val accountKey = user.getCurrentAccountStorageKey()
        setContent {
            CourseSelectorTheme {
                MessageCenterScreen(
                    school = school,
                    accountKey = accountKey,
                    onBack = { finish() },
                    onOpenWeb = { openWeb(it) }
                )
            }
        }
    }

    private fun openWeb(url: String) {
        val school = UserManager.getInstance().getCurrentSchool() ?: return
        val hosts = (listOf(school.domain) + school.allowedAcademicHosts)
            .map { it.trim() }.filter { it.isNotBlank() }
        val intent = Intent(this, AcademicWebViewActivity::class.java).apply {
            putExtra(AcademicWebViewActivity.EXTRA_START_URL, url)
            putExtra(AcademicWebViewActivity.EXTRA_COOKIE_URL, school.getFullBasePath().trimEnd('/') + "/")
            putStringArrayListExtra(AcademicWebViewActivity.EXTRA_ALLOWED_HOSTS, ArrayList(hosts))
        }
        startActivity(intent)
    }
}
