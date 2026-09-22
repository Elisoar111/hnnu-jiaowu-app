package com.hnnujw.course.xuegong

import android.content.Context
import android.util.Log
import com.hnnujw.course.manager.CredentialStore
import com.hnnujw.course.manager.UserManager

/**
 * 学工系统的本地状态：access_token 与可选的密码。
 *
 * 账号复用教务侧学号（同一个学生的两个系统账号一致），密码是学工系统自己的，
 * 与教务密码、二课密码都分开存（不同的 credentialKey 前缀）。
 *
 * token 是 24 小时短生命周期凭据，放普通 prefs；密码走 [CredentialStore]
 * （AndroidKeyStore 加密），只在 token 过期后用来静默续期。
 */
object XuegongStore {

    private const val TAG = "Xuegong"
    private const val PREFS = "xuegong_prefs"
    private const val KEY_TOKEN_PREFIX = "token_"

    /** 全应用共用一个 OkHttp 实例（连接池/线程池复用）。 */
    private val sharedClient: XuegongClient by lazy { XuegongClient() }

    fun client(): XuegongClient = sharedClient

    /** 当前账号在教务侧的学号，作为学工系统的登录账号。 */
    fun academicStudentId(): String {
        val manager = UserManager.getInstance()
        return manager.studentId?.trim().orEmpty().ifBlank { manager.username?.trim().orEmpty() }
    }

    fun token(context: Context, accountKey: String): String =
        prefs(context).getString(KEY_TOKEN_PREFIX + accountKey, "").orEmpty()

    fun saveToken(context: Context, accountKey: String, token: String) {
        prefs(context).edit().putString(KEY_TOKEN_PREFIX + accountKey, token).apply()
    }

    fun clearToken(context: Context, accountKey: String) {
        prefs(context).edit().remove(KEY_TOKEN_PREFIX + accountKey).apply()
    }

    fun hasPassword(context: Context, accountKey: String): Boolean =
        CredentialStore.has(context, credentialKey(accountKey))

    fun savePassword(context: Context, accountKey: String, password: String) {
        CredentialStore.save(context, credentialKey(accountKey), password)
    }

    fun loadPassword(context: Context, accountKey: String): String? =
        CredentialStore.load(context, credentialKey(accountKey))

    fun clearAccount(context: Context, accountKey: String) {
        clearToken(context, accountKey)
        CredentialStore.remove(context, credentialKey(accountKey))
    }

    /**
     * 把失败降级成一句可展示的文案，顺手清掉已失效的 token。
     *
     * `sessionExpired` 才清 token：网络抖动不能把用户的登录状态抹掉，
     * 否则重连一次就要重新输密码。
     */
    fun handleFailure(context: Context, accountKey: String, error: Throwable): String {
        val failure = error as? XuegongException
        if (failure?.sessionExpired == true) {
            Log.i(TAG, "token 已失效，清理本地缓存")
            clearToken(context, accountKey)
        }
        return failure?.message ?: error.message ?: "学工系统加载失败，请重试"
    }

    /** 与教务、二课的凭据键都区分开，避免同一账号的几套密码互相覆盖。 */
    private fun credentialKey(accountKey: String) = "xg::$accountKey"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
