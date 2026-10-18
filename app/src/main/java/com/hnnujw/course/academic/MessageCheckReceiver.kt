package com.hnnujw.course.academic

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.hnnujw.course.manager.UserManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 每 12 小时一次的消息巡检入口（由 [MessageCenterNotifier.schedule] 排的闹钟触发）。
 *
 * 广播回调只有 10 秒左右，所以走 `goAsync()` 把结果交回给一个短命协程；
 * 网络请求期间必须持有它，否则系统会在 `onReceive` 返回后立刻回收进程。
 *
 * 开机 / 应用更新后也会收到这个广播：那时进程刚起来，会话要靠
 * [UserManager.init] 从本地恢复，否则[巡检会以为"没登录"而整轮空转]。
 */
class MessageCheckReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        // 系统事件（开机/更新/改时间）会清掉已排的闹钟：按持久化时刻恢复；
        // 本轮巡检闹钟触发（ACTION_CHECK）：立即续期下一轮 —— 链式闹钟靠这里保持不断。
        if (intent.action != MessageCenterNotifier.ACTION_CHECK) {
            MessageCenterNotifier.schedule(app)
        } else {
            MessageCenterNotifier.scheduleNext(app)
        }
        val pending = goAsync()
        UserManager.getInstance().init(app)
        val user = UserManager.getInstance()
        if (user.isDemoMode || !user.isLoggedIn) {
            pending.finish()
            return
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            try {
                val school = user.currentSchool
                val accountKey = user.currentAccountStorageKey
                // 必须 9 秒封顶（与 Update/Ykt 接收器同款）：goAsync 的广播预算约 10 秒，
                // 消息巡检最多 2 桶 × 10 页 POST、成绩订阅还要翻页，教务接口一慢进程就被
                // 系统回收、本轮巡检静默作废 —— 下一轮要再等 12 小时。
                withTimeoutOrNull(9_000L) {
                    // 本轮会话的 token：后台请求可能让服务端**续期 Cookie**，
                    // 结束时要把新 Cookie 落盘（见 AcademicCookieSync 的说明）。
                    // 少了这一步，进程下次冷启动会从持久化恢复出一张可能已作废的旧票。
                    val token = user.sessionState.token
                    // 考试列表**排在最前**：它通常只有一页（正常 1 秒内返回），自带 2.5 秒上限。
                    // 放在消息巡检之后会被饿死 —— 而"考试提醒从不出现"正是本次要修的问题。
                    // 失败/超时都不影响后面的消息与成绩巡检。
                    runCatching { ExamWatcher.check(app, school, accountKey) }
                    // force = true：闹钟本身已经是 12 小时一次，不再叠加节流
                    MessageCenterNotifier.check(app, school, accountKey, force = true)
                    // 成绩订阅与消息巡检共用这颗 12 小时闹钟：出成绩季自动 diff 推送
                    runCatching { GradeWatcher.check(app, school, accountKey, force = true) }
                    // 三条请求都发完之后统一收尾：Cookie 若被续期，此刻落盘最合适
                    // （放在最前面写会把"还没发请求"的旧值写回去，等于空转一次）。
                    AcademicCookieSync.persistAfter(school, token)
                }
            } catch (_: Throwable) {
                // 巡检失败不需要惊动用户：红点保持上一次缓存值即可
            } finally {
                pending.finish()
            }
        }
    }
}
