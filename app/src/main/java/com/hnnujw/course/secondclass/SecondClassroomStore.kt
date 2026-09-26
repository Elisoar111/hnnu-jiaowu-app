package com.hnnujw.course.secondclass

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.hnnujw.course.manager.CredentialStore
import com.hnnujw.course.manager.UserManager
import com.hnnujw.course.model.SchoolConfig

/**
 * 第二课堂的本地状态：站点地址、access_token、以及可选的密码。
 *
 * 与教务凭据的关系：**学号直接复用教务账号**（同一学生的两个系统账号一致），
 * 密码是第二课堂自己的，和教务密码不是一回事，所以单独存一份。
 *
 * 密码走 [CredentialStore]（AndroidKeyStore 加密），token 是短生命周期凭据，
 * 放在普通 prefs 里即可，过期就重新登录。
 */
object SecondClassroomStore {

    private const val TAG = "SecondClassroom"
    private const val PREFS = "second_classroom_prefs"
    private const val KEY_TOKEN_PREFIX = "token_"

    /** 该校是否接入了第二课堂。未接入时整个功能在界面上不出现。 */
    fun isAvailable(school: SchoolConfig?): Boolean =
        school != null && school.secondClassroomBaseUrl.isNotBlank() && school.secondClassroomSchoolCode.isNotBlank()

    fun clientFor(school: SchoolConfig?): SecondClassroomClient? {
        if (!isAvailable(school)) return null
        return SecondClassroomClient(
            baseUrl = school!!.secondClassroomBaseUrl,
            schoolCode = school.secondClassroomSchoolCode,
        )
    }

    /** 当前账号在教务侧的学号，作为第二课堂的登录账号。 */
    fun academicStudentId(): String {
        val manager = UserManager.getInstance()
        return manager.studentId?.trim().orEmpty().ifBlank { manager.username?.trim().orEmpty() }
    }

    /**
     * 第二课堂的**初始密码**：学号 + 固定后缀 `&Zhtx`（"智慧团学"的拼音首字母）。
     *
     * 这是学校统一发的初始口令，不是用户设的。所以：
     * - 登录弹窗里预填它，用户通常直接点"登录"就行，不用去翻通知；
     * - 既然预填，就必须**允许用户改**——改过的学校/改过密码的账号在这个基础上覆盖；
     * - 本函数只是"提供默认值"，不是"记住密码"，不涉及任何隐私回显：
     *   学号本来就在界面上明文显示，后缀是全校统一的公开常量。
     */
    fun defaultPassword(studentId: String): String =
        if (studentId.isBlank()) "" else studentId.trim() + DEFAULT_PASSWORD_SUFFIX

    /**
     * 账号键归一化：与 `UserManager.toStorageKey`、[SecondClassOverviewCache.normalize]
     * 用同一条规则（非 `[A-Za-z0-9_.-]` 一律换成 `_`）。
     *
     * 为什么必须有：本工程并存两种账号键口径 ——
     * `currentAccountKey`（`hnnu::2024001`）与 `currentAccountStorageKey`（`hnnu__2024001`）。
     * 调用方并不统一（二课各页面传前者，桌面组件传后者）。不归一化就会
     * **写进一个键、读另一个键**，读出来永远是空串：表现为"明明绑定过二课，
     * 卡片却说未绑定"，并且 `WidgetBoardRoute` 会因此提前 return，
     * 让积分概览缓存永远写不进去。归一化之后两种口径落到同一个键上。
     */
    internal fun normalize(accountKey: String): String =
        accountKey.ifBlank { "default" }.replace(Regex("[^A-Za-z0-9_.-]"), "_")

    private fun tokenKey(accountKey: String) = KEY_TOKEN_PREFIX + normalize(accountKey)

    /**
     * 与 [accountKey] 指向同一个账号、但**写法不同**的历史键（如 `token_hnnu::2024001`）。
     *
     * 为什么不能直接拼 `token_ + accountKey` 来还原旧键：调用方可能传的是已归一化的
     * storage key，从它反推不出旧键的字面量。所以统一按"归一化后是否指向同一账号"来认，
     * 这样无论调用方传哪种口径都能认出老数据。
     */
    private fun staleTokenKeys(p: SharedPreferences, accountKey: String): List<String> {
        val normalized = normalize(accountKey)
        val current = KEY_TOKEN_PREFIX + normalized
        return p.all.keys.filter { candidate ->
            candidate.startsWith(KEY_TOKEN_PREFIX) && candidate != current &&
                normalize(candidate.removePrefix(KEY_TOKEN_PREFIX)) == normalized
        }
    }

    fun token(context: Context, accountKey: String): String = readToken(prefs(context), accountKey)

    fun saveToken(context: Context, accountKey: String, token: String) =
        writeToken(prefs(context), accountKey, token)

    fun clearToken(context: Context, accountKey: String) = removeToken(prefs(context), accountKey)

    // ---- 以下三个是纯存储原语，直接吃 SharedPreferences ----
    // 不经过 Context 是为了让 JVM 单测能喂内存实现（同 ScheduleCacheStore 的做法）：
    // 键口径的一致性完全由这几个函数决定，必须有回归测试盯着。

    internal fun readToken(p: SharedPreferences, accountKey: String): String {
        if (accountKey.isBlank()) return ""
        val key = tokenKey(accountKey)
        p.getString(key, "").orEmpty().takeIf { it.isNotBlank() }?.let { return it }
        // 迁移：老版本把 token 写在未归一化的键上，读到就搬到归一化键（只搬一次）。
        val stale = staleTokenKeys(p, accountKey).firstOrNull() ?: return ""
        val migrated = p.getString(stale, "").orEmpty()
        if (migrated.isNotBlank()) p.edit().putString(key, migrated).remove(stale).apply()
        return migrated
    }

    internal fun writeToken(p: SharedPreferences, accountKey: String, token: String) {
        if (accountKey.isBlank()) return
        val editor = p.edit().putString(tokenKey(accountKey), token)
        // 顺手清掉历史写法，避免同一账号留两份、日后被迁移逻辑"复活"成旧值。
        // staleTokenKeys 已排除当前键本身，所以不会把刚写进去的 token 删掉。
        staleTokenKeys(p, accountKey).forEach { editor.remove(it) }
        editor.apply()
    }

    internal fun removeToken(p: SharedPreferences, accountKey: String) {
        if (accountKey.isBlank()) return
        val editor = p.edit()
        staleTokenKeys(p, accountKey).forEach { editor.remove(it) }
        // 显式再删一次目标键：staleTokenKeys 不包含它，且这样在"目标键不存在"时也幂等
        editor.remove(tokenKey(accountKey)).apply()
    }

    fun hasPassword(context: Context, accountKey: String): Boolean =
        CredentialStore.has(context, credentialKey(accountKey))

    fun savePassword(context: Context, accountKey: String, password: String) {
        CredentialStore.save(context, credentialKey(accountKey), password)
    }

    fun loadPassword(context: Context, accountKey: String): String? =
        CredentialStore.load(context, credentialKey(accountKey))

    fun clearPassword(context: Context, accountKey: String) {
        CredentialStore.remove(context, credentialKey(accountKey))
    }

    fun clearAccount(context: Context, accountKey: String) {
        clearToken(context, accountKey)
        clearPassword(context, accountKey)
    }

    /** 把第二课堂的失败降级成一句可展示的文案，同时顺手清掉失效的 token。 */
    fun handleFailure(context: Context, accountKey: String, error: Throwable): String {
        val failure = error as? SecondClassException
        if (failure?.sessionExpired == true) {
            Log.i(TAG, "token 已失效，清理本地缓存")
            clearToken(context, accountKey)
        }
        return failure?.message ?: error.message ?: "第二课堂加载失败，请重试"
    }

    /** 与教务密码的存储键区分开，避免同一个账号两条密码互相覆盖。 */
    private fun credentialKey(accountKey: String) = "ekta::$accountKey"

    /** 学校统一发的初始密码后缀。见 [defaultPassword]。 */
    private const val DEFAULT_PASSWORD_SUFFIX = "&Zhtx"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
