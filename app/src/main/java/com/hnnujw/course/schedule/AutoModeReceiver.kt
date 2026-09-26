package com.hnnujw.course.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.hnnujw.course.manager.UserManager

/**
 * 上课 / 下课闹钟的接收者。
 *
 * 收到就整轮重新对齐，**不在这里判断「这个闹钟是来开还是来关的」**：闹钟可能迟到
 * （Doze、刚开机、被系统压制），按"现在几点、课表上有没有课"重新算，永远比按
 * "当初这个闹钟是为什么排的"可靠。两个闹钟用的是不同的 data URI，因此可以同时存在。
 */
class AutoModeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        UserManager.getInstance().init(context)
        ScheduleReminderScheduler.get(context).reconcile()
    }
}
