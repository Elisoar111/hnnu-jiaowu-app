package com.hnnujw.course

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.hnnujw.course.manager.AppThemeCoordinator
import com.hnnujw.course.manager.UserManager
import com.hnnujw.course.ui.screen.XuegongScreen
import com.hnnujw.course.ui.theme.CourseSelectorTheme

/**
 * 学工系统（xg.hnnu.edu.cn）宿主页：日常请假 + 节假日去向登记。
 *
 * 与 [AnnouncementActivity] / [MessageCenterActivity] 同构的独立全屏页，
 * 从「我的」页的入口进入。
 *
 * 与公告页的差别：这里要读账号信息（学号取自教务侧），所以启动时必须先
 * `UserManager.init` —— `isLoggedIn` / `currentAccountKey` 都是内存字段，
 * 只在 `init → loadLoginState()` 里从 prefs 恢复。
 */
class XuegongActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppThemeCoordinator.wrapContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        UserManager.getInstance().init(applicationContext)
        setContent {
            CourseSelectorTheme {
                XuegongScreen(onBack = { finish() })
            }
        }
    }
}
