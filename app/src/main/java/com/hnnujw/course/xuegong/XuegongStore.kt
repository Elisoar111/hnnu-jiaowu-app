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

    fun hasPassword(context: Context, accountKey: String): Boolean {
        return credentialKeys(accountKey).any { CredentialStore.has(context, it) }
    }

    fun savePassword(context: Context, accountKey: String, password: String) {
        val keys = credentialKeys(accountKey)
        if (keys.isEmpty()) return
        CredentialStore.save(context, keys.first(), password)
        // 旧键留一份会和规范键各存一条，日后改密码只改得动一条，静默续期就可能读到旧值
        keys.drop(1).forEach { CredentialStore.remove(context, it) }
    }

    fun loadPassword(context: Context, accountKey: String): String? {
        for ((index, key) in credentialKeys(accountKey).withIndex()) {
            val value = CredentialStore.load(context, key)
            if (!value.isNullOrBlank()) {
                // 命中历史键就顺手搬到规范键，之后只认一处
                if (index > 0) savePassword(context, accountKey, value)
                return value
            }
        }
        return null
    }

    fun clearPassword(context: Context, accountKey: String) {
        credentialKeys(accountKey).forEach { CredentialStore.remove(context, it) }
    }

    fun clearAccount(context: Context, accountKey: String) {
        clearToken(context, accountKey)
        credentialKeys(accountKey).forEach { CredentialStore.remove(context, it) }
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

    /**
     * 同一条密码可能存在的两种键写法：归一化后的 "xg::hnnu__2505050111"，
     * 以及老版本按 currentAccountKey 原样存下的 "xg::hnnu::2505050111"。
     *
     * 为什么必须两种都认：写入侧（登录成功时）传的是原始账号键，读取侧（设置里的
     * 密码面板）传的是 storage key。两边口径不同就会"明明登录成功却显示未存密码"，
     * 而且清除也删不掉真正的条目。所以统一成"写归一化键、读写都容忍旧键"，
     * 老数据不必迁移也能立刻显示正确。
     */
    private fun credentialKeys(accountKey: String): List<String> {
        if (accountKey.isBlank()) return emptyList()
        val canonical = "xg::" + normalize(accountKey)
        val legacy = "xg::$accountKey"
        return if (canonical == legacy) listOf(canonical) else listOf(canonical, legacy)
    }

    private fun credentialKey(accountKey: String) = "xg::" + normalize(accountKey)

    /** 账号键归一化：只留 [A-Za-z0-9_.-]，与 UserManager/二课的口径保持一致。 */
    private fun normalize(accountKey: String): String =
        accountKey.ifBlank { "default" }.replace(Regex("[^A-Za-z0-9_.-]"), "_")

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
