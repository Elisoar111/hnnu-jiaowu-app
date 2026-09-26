package com.hnnujw.course.widgetboard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 桌面卡片的非组件刷新入口。
 *
 * 三件事都会让卡片上的说法失真，而且系统会顺手清掉所有闹钟：
 *  - 开机、应用更新：进程是新的，卡片还是上次画的那一版；
 *  - 改时间 / 改时区 / 改日期：「正在上课」「下一节 14:00」全都得重算。
 *
 * 所以这里只做一件事：把刷新转给 [CardWidgetUpdater.update]（它会顺带重排边界闹钟）。
 *
 * 单独一个 receiver、而不是把这几条 action 挂到 7 个组件上，是为了避免开机时被唤醒
 * 7 次 —— 七个组件收到广播后做的事一模一样，那 7 次里有 6 次是纯浪费。
 */
class CardWidgetRefreshReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        CardWidgetUpdater.update(context)
    }
}
