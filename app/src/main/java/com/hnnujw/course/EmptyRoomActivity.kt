package com.hnnujw.course

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.hnnujw.course.manager.AppThemeCoordinator
import com.hnnujw.course.manager.UserManager
import com.hnnujw.course.ui.screen.EmptyRoomScreen
import com.hnnujw.course.ui.theme.CourseSelectorTheme

/**
 * 教务系统「空闲教室查询」宿主页（`cdjy/cdjy_cxKxcdlb.html`，gnmkdm=N2155）。
 *
 * 与 [KaojiActivity] / [YktActivity] 同构的独立全屏页，从「我的」页「校园服务」进入。
 *
 * 复用已登录的教务会话（无需额外登录）。**纯只读**：只发查询页 GET 与查询 POST，
 * 网页上的「场地借用申请」不实现、不提交。
 */
class EmptyRoomActivity : ComponentActivity() {
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
                EmptyRoomScreen(
                    school = school,
                    accountKey = accountKey,
                    onBack = { finish() },
                )
            }
        }
    }
}
