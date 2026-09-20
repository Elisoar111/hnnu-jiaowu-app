package com.hnnujw.course

import android.app.Application
import com.hnnujw.course.manager.AppearanceSettingsManager
import com.hnnujw.course.manager.AppThemeCoordinator
import com.hnnujw.course.ui.system.GlassRuntimeGuard

class CourseApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        GlassRuntimeGuard.initialize(this)
        AppearanceSettingsManager.initialize(this)
        AppThemeCoordinator.initialize(this)
        val processName = if (android.os.Build.VERSION.SDK_INT >= 28) getProcessName() else {
            getSystemService(android.app.ActivityManager::class.java).runningAppProcesses
                ?.firstOrNull { it.pid == android.os.Process.myPid() }?.processName
        }
        if (processName == packageName) {
            com.hnnujw.course.schedule.ScheduleReminderScheduler.get(this).start(this)
            // 消息中心巡检：每 12 小时一次。开机/更新后闹钟会被系统清掉，
            // MessageCheckReceiver 也会收到那些广播并自行重排，这里负责首次排程。
            com.hnnujw.course.academic.MessageCenterNotifier.schedule(this)
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        AppThemeCoordinator.configurationChanged()
    }
}
