package com.tyust.course.secondclass

import android.content.Context
import android.util.Log
import com.tyust.course.manager.CredentialStore
import com.tyust.course.manager.UserManager
import com.tyust.course.model.SchoolConfig

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
