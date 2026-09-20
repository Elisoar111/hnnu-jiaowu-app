package com.tyust.course.academic

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.tyust.course.manager.UserManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

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
        // 这些系统事件会把已排的闹钟清掉，收到就重排一次
        if (intent.action != MessageCenterNotifier.ACTION_CHECK) {
            MessageCenterNotifier.schedule(app)
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
                // force = true：闹钟本身已经是 12 小时一次，不再叠加节流
                MessageCenterNotifier.check(app, school, accountKey, force = true)
                // 成绩订阅与消息巡检共用这颗 12 小时闹钟：出成绩季自动 diff 推送
                runCatching { GradeWatcher.check(app, school, accountKey, force = true) }
            } catch (_: Throwable) {
                // 巡检失败不需要惊动用户：红点保持上一次缓存值即可
            } finally {
                pending.finish()
            }
        }
    }
}
