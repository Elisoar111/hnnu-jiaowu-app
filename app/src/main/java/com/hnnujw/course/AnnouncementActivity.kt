package com.hnnujw.course

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.hnnujw.course.manager.AppThemeCoordinator
import com.hnnujw.course.ui.screen.AnnouncementScreen
import com.hnnujw.course.ui.theme.CourseSelectorTheme

/**
 * 原生公告中心宿主页。
 *
 * 与 [MessageCenterActivity] 同构：全屏页 + 顶栏 + 未读/已读分段 + 卡片列表 + 页内详情。
 * 差别在于公告不需要登录 —— 数据随包内置（`assets/announcement.json`，见
 * [com.hnnujw.course.announcement.AnnouncementManager]），全局共享，
 * 因此这里不读 [com.hnnujw.course.manager.UserManager] 的任何账号信息。
 */
class AnnouncementActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppThemeCoordinator.wrapContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CourseSelectorTheme {
                AnnouncementScreen(onBack = { finish() })
            }
        }
    }
}
