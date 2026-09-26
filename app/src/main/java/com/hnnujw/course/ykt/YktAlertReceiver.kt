package com.hnnujw.course.ykt

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.hnnujw.course.manager.UserManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 电费巡检入口（由 [YktAlertScheduler] 的闹钟触发，或开机/更新后重排）。
 *
 * 广播回调只有约 10 秒预算，所以走 `goAsync()` + 短命协程。
 *
 * ## 前置条件（不满足就静默跳过，不报错）
 *
 * 1. 已登录且有当前账号；
 * 2. **提醒开关是开的**（用户可能关掉了）；
 * 3. **本地有 `synjones-auth` 令牌**；
 * 4. **本地有"上次查询的房间选择"**。
 *
 * 第 3 条是这个功能的能力边界：一卡通的认证体系与教务独立，
 * 未登录过一卡通（没在 App 内获取过令牌）时，后台**无法**替用户建会话——
 * 那需要用户的一卡通密码，而静默使用保存的密码去登录新系统属于越界行为，
 * 本实现**不做**。因此后台巡检只在用户曾成功打开过一卡通页面时工作。
 *
 * 第 4 条同样是这个功能的边界：电费**按房间计量**（真机实测），
 * 后台不可能替用户"猜"房间，所以必须复用用户在界面上选定的那一间。
 * 用户没选过 → 说明他还不知道要选，界面上会引导，后台这轮就跳过。
 */
class YktAlertReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val app = context.applicationContext
        // 系统事件（开机/更新/改时间）会清掉已排的闹钟，收到就重排一次
        if (intent?.action != YktAlertScheduler.ACTION) {
            YktAlertScheduler.schedule(app)
        }

        if (!YktAlertSettings.isEnabled(app)) return

        val pending = goAsync()
        UserManager.getInstance().init(app)
        val user = UserManager.getInstance()
        if (user.isDemoMode || !user.isLoggedIn) {
            pending.finish()
            return
        }
        val accountKey = user.currentAccountStorageKey
        val token = YktStore.token(app, accountKey)
        if (token.isBlank()) {
            // 从未在 App 内打开过一卡通：没有令牌，本轮无法巡检。
            // 这不是错误——用户下次打开一卡通页面即可恢复后台提醒能力。
            pending.finish()
            return
        }

        // 复用车用界面选定的房间：电费按房间计量，后台无法代为选择。
        // 这里把记忆里的 picks（code -> value）原样重放给 getThirdData（type=IEC）取读数。
        val selection = YktSelectionStore.read(app, accountKey)
        if (!selection.isQueryable) {
            pending.finish()
            return
        }

        val threshold = YktAlertSettings.thresholdYuan(app)
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
                    val client = YktStore.client()
                    val result = client.thirdData(
                        token = token,
                        feeItemId = selection.feeItemId,
                        picks = selection.pickMap,
                        level = selection.picks.size,
                        type = YktClient.TYPE_IEC,
                    )
                    val balance = result.reading
                    if (balance == null) {
                        Log.i(TAG, "后台巡检未取到读数，跳过本轮")
                        return@withTimeoutOrNull
                    }
                    // 只有金额型费项才套"元"阈值。度数型（如 201 的剩余电量）
                    // 拿 20 元去比会得出荒谬结论，这里直接跳过而不是硬比。
                    if (!balance.supportsAmountAlert) {
                        Log.i(TAG, "当前费项为${balance.unit}口径，跳过金额阈值判定")
                        return@withTimeoutOrNull
                    }
                    YktAlertNotifier.evaluateAndNotify(app, accountKey, balance.amount, threshold)
                }
            } catch (e: Exception) {
                // 后台巡检失败不惊动用户：余额提醒保持上一轮状态即可。
                // 令牌失效时顺手清掉，避免每轮都白跑一次。
                Log.w(TAG, "电费巡检失败：${e.javaClass.simpleName}")
                if ((e as? YktException)?.sessionExpired == true) {
                    YktStore.clearToken(app, accountKey)
                }
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "YktAlert"

        /** 留出余量：广播预算约 10 秒，正常一轮两三个请求 2 秒内完成。 */
        const val REQUEST_TIMEOUT_MS = 9_000L
    }
}
