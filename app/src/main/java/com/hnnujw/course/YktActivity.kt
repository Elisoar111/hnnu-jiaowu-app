package com.hnnujw.course

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.hnnujw.course.manager.AppThemeCoordinator
import com.hnnujw.course.manager.UserManager
import com.hnnujw.course.ui.screen.YktScreen
import com.hnnujw.course.ui.theme.CourseSelectorTheme

/**
 * 一卡通（校园卡余额 / 宿舍电费）宿主页。
 *
 * 与 [KaojiActivity] 同构的独立全屏页，从「我的」页「校园服务」进入。
 *
 * 认证是**独立的**：一卡通用 `synjones-auth` 令牌，与教务的 Cookie 会话、
 * 二课的 Bearer 都不通用，因此本页不复用教务登录态（[YktScreen] 自己取令牌）。
 *
 * 只读：页面不提供充值/支付入口，理由见 [com.hnnujw.course.ykt.YktClient] 类注释。
 */
class YktActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppThemeCoordinator.wrapContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        UserManager.getInstance().init(applicationContext)
        val accountKey = UserManager.getInstance().currentAccountStorageKey
        setContent {
            CourseSelectorTheme {
                YktScreen(
                    accountKey = accountKey,
                    onBack = { finish() }
                )
            }
        }
    }
}
