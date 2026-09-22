package com.hnnujw.course.network

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.hnnujw.course.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 后台版本巡检：由 [UpdateCheckScheduler] 的闹钟唤起来，发现新版本就发系统通知。
 *
 * 这条链路**不依赖登录态**（查的是 Gitee 公开 Release 接口），所以不去碰
 * `UserManager`，也不需要 `init` —— 后台入口里少一个可能的初始化坑。
 */
class UpdateCheckReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val app = context.applicationContext
        // goAsync：网络请求不能在 onReceive 里同步做完，又不能让系统以为接收器已经结束
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                // BroadcastReceiver 的 onReceive 只有约 10 秒预算，网络请求必须自己封顶。
                // 超时就放弃这一轮：闹钟已经在 finally 里排好下一轮，不需要在这里补救。
                val result = withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
                    AppUpdateChecker.check(BuildConfig.VERSION_NAME)
                }
                if (result is AppUpdateChecker.Result.Update) {
                    UpdateNotifier.notifyIfNew(app, result.info)
                }
            } catch (e: Exception) {
                Log.w(TAG, "后台检查更新失败：${e.javaClass.simpleName}")
            } finally {
                // 自我续期：无论这一步成功与否，都要把下一轮巡检排上，
                // 否则用户只要错过一次闹钟，后台检查就永远停了。
                UpdateCheckScheduler.schedule(app)
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "UpdateCheck"

        /** 留出余量：Gitee 接口正常在 1～2 秒内返回。 */
        private const val REQUEST_TIMEOUT_MS = 9_000L
    }
}
