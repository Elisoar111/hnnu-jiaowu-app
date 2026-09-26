package com.hnnujw.course

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.hnnujw.course.manager.AppThemeCoordinator
import com.hnnujw.course.manager.UserManager
import com.hnnujw.course.ui.screen.KaojiRegistrationScreen
import com.hnnujw.course.ui.theme.CourseSelectorTheme

/**
 * 教务系统「考级项目报名」宿主页（kjgl/kjbm_*，gnmkdm=N2510）。
 *
 * 与 [MessageCenterActivity] 同构的独立全屏页，
 * 从「我的」页「校园服务」里的入口进入。
 *
 * 数据复用已登录的教务会话（无需额外登录）；测试账号在页内只读演示，
 * 报名/退报不会向教务系统发出任何写请求（见 [com.hnnujw.course.examreg.KaojiClient]）。
 */
class KaojiActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppThemeCoordinator.wrapContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        UserManager.getInstance().init(applicationContext)
        val user = UserManager.getInstance()
        val school = user.getCurrentSchool()
        val accountKey = user.getCurrentAccountStorageKey()
        setContent {
            CourseSelectorTheme {
                KaojiRegistrationScreen(
                    school = school,
                    accountKey = accountKey,
                    onBack = { finish() }
                )
            }
        }
    }
}
