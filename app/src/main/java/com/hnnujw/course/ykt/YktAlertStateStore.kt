package com.hnnujw.course.ykt

import android.content.Context
import android.util.Log
import com.hnnujw.course.manager.UserManager

/**
 * 电费提醒去重状态的持久化。
 *
 * 存在这里而不是跟着 [YktAlertSettings]：那个文件放的是"用户偏好"（可跨账号），
 * 这里放的是"这个账号提醒到哪一步了"（必须按账号隔离）。
 *
 * 键名走 `UserManager.toStorageKey` 同款归一化规则——本工程并存
 * `hnnu::2024001` 与 `hnnu__2024001` 两种账号键口径，不归一化会「写一个键、读另一个键」，
 * 表现为提醒反复发或永不发。
 *
 * 🔴 **三个字段必须成套读写**：[YktAlertPolicy.AlertState.lastNotifiedAtMillis]
 * 是"同一余额 24 小时内不重复"节流窗口的基准（巡检一天 4 次）。它若不落盘，
 * 读回来的时刻恒为 0、窗口判定恒不成立 —— 同余额节流形同虚设，低余额用户会
 * 每 6 小时收到一条一模一样的通知。
 */
object YktAlertStateStore {

    private const val TAG = "YktAlert"
    private const val PREFS = "ykt_alert_state"
    private const val KEY_ACTIVE = "active_"
    private const val KEY_AMOUNT = "amount_"
    private const val KEY_TIME = "time_"

    fun read(context: Context, accountKey: String): YktAlertPolicy.AlertState {
        val prefs = prefs(context)
        val suffix = YktAlertSettings.stateKey(accountKey)
        val active = prefs.getBoolean(KEY_ACTIVE + suffix, false)
        if (!active) return YktAlertPolicy.AlertState.NONE
        val amount = prefs.getString(KEY_AMOUNT + suffix, null)?.toDoubleOrNull()
        val time = prefs.getLong(KEY_TIME + suffix, 0L)
        return YktAlertPolicy.AlertState(active = true, lastNotifiedAmount = amount, lastNotifiedAtMillis = time)
    }

    fun write(context: Context, accountKey: String, state: YktAlertPolicy.AlertState) {
        val suffix = YktAlertSettings.stateKey(accountKey)
        val editor = prefs(context).edit()
        if (state.active) {
            editor.putBoolean(KEY_ACTIVE + suffix, true)
            state.lastNotifiedAmount?.let { editor.putString(KEY_AMOUNT + suffix, it.toString()) }
            editor.putLong(KEY_TIME + suffix, state.lastNotifiedAtMillis)
        } else {
            editor.remove(KEY_ACTIVE + suffix)
            editor.remove(KEY_AMOUNT + suffix)
            editor.remove(KEY_TIME + suffix)
        }
        editor.apply()
    }

    fun clear(context: Context, accountKey: String) {
        write(context, accountKey, YktAlertPolicy.AlertState.NONE)
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
