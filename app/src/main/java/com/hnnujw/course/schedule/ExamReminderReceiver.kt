package com.hnnujw.course.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.hnnujw.course.manager.GradesCacheManager
import com.hnnujw.course.manager.UserManager

/**
 * 考前提醒的闹钟接收器，同时承担开机 / 应用更新 / 改时间后的重排。
 *
 * 重排只读 [GradesCacheManager] 的考试缓存，不发网络——所以广播回调里
 * 不需要 goAsync 长驻进程，同步做完即返回。
 */
class ExamReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        if (intent.action == ExamReminderScheduler.ACTION) {
            ExamReminderScheduler.receive(app, intent.getStringExtra(ExamReminderScheduler.EXTRA_ID).orEmpty())
            return
        }
        // 系统事件：恢复账号后用本地缓存的考试列表重排
        UserManager.getInstance().init(app)
        val user = UserManager.getInstance()
        if (user.isDemoMode || !user.isLoggedIn) return
        val cached = GradesCacheManager.load(app, user.currentAccountStorageKey) ?: return
        ExamReminderScheduler.reconcile(app, cached.exams)
    }
}
