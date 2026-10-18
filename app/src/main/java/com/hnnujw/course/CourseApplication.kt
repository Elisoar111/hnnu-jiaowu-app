package com.hnnujw.course

import android.app.Application
import com.hnnujw.course.manager.AppearanceSettingsManager
import com.hnnujw.course.manager.AppThemeCoordinator
import com.hnnujw.course.ui.system.GlassRuntimeGuard

class CourseApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // 开发期诊断：只在 debug 装，违反策略会带着完整调用栈打 logcat。
        // 放在最前面 —— 后面这些 initialize 本身就可能踩主线程 IO，早装早能看见。
        com.hnnujw.course.utils.StrictModePolicy.install(BuildConfig.DEBUG)
        // 崩溃兜底：release 才装（debug 下 Android Studio 的栈更完整）。
        // 只写本地文件，不上报任何数据；用户反馈时主动附上。
        com.hnnujw.course.utils.CrashGuard.install(this, enabled = !BuildConfig.DEBUG)
        // 后台预热 SharedPreferences：把十几个 prefs 文件的首次解析从主线程挪走。
        // 放在最前面启动（异步、不阻塞），让它在后面这些 initialize 跑的同时进行。
        com.hnnujw.course.utils.PrefsWarmer.warmUp(this)
        GlassRuntimeGuard.initialize(this)
        AppearanceSettingsManager.initialize(this)
        AppThemeCoordinator.initialize(this)
        val processName = if (android.os.Build.VERSION.SDK_INT >= 28) getProcessName() else {
            getSystemService(android.app.ActivityManager::class.java).runningAppProcesses
                ?.firstOrNull { it.pid == android.os.Process.myPid() }?.processName
        }
        if (processName == packageName) {
            com.hnnujw.course.schedule.ScheduleReminderScheduler.get(this).start(this)
            // 桌面卡片刷新守护：缓存 / 会话 / 设置变化监听 + 节次边界闹钟。
            // 只在主进程启动，避免 :xxx 子进程重复注册 prefs 监听。
            com.hnnujw.course.widgetboard.CardWidgetUpdater.start(this)
            // 消息中心巡检：每 12 小时一次。开机/更新后闹钟会被系统清掉，
            // MessageCheckReceiver 也会收到那些广播并自行重排，这里负责首次排程。
            com.hnnujw.course.academic.MessageCenterNotifier.schedule(this)
            // 电费余额巡检：每天一次，低于阈值发通知。开机/更新后闹钟会被系统清掉，
            // YktAlertReceiver 也会收到那些广播并自行重排，这里负责首次排程。
            com.hnnujw.course.ykt.YktAlertScheduler.schedule(this)
            // 错题复习提醒：每天固定时刻检查「今日待复习」，有到期题就发通知。
            // 默认关闭，schedule() 内部会读开关 —— 关着时它顺便清掉可能残留的闹钟。
            com.hnnujw.course.review.ReviewReminderScheduler.schedule(this)
            // 旧格式课表日历迁移：一次性 prefs 写，刻意只在启动时做。
            //
            // 它原先藏在 ScheduleRepository.timeBase() 里，而那个方法被桌面卡片每次刷新都会
            // 调用 —— 读接口不该有副作用；更糟的是 timeBase 只由 snapshot 触达，等于
            // "从没加过桌面组件、也没打开过组件工作台的用户永远迁移不到"。挪到这里后
            // 两个问题一起解决。失败只记日志：迁移不成功也只是时间基准回落到推算值。
            runCatching {
                val user = com.hnnujw.course.manager.UserManager.getInstance().apply { init(this@CourseApplication) }
                val account = user.currentAccountStorageKey
                val school = user.currentSchool?.id.orEmpty()
                if (account.isNotBlank() && school.isNotBlank()) {
                    com.hnnujw.course.schedule.ScheduleRepository(this@CourseApplication)
                        .migrateLegacyCalendar(account, school)
                }
            }.onFailure {
                android.util.Log.w("CourseApplication", "课表日历历史迁移失败", it)
            }
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        AppThemeCoordinator.configurationChanged()
    }
}
