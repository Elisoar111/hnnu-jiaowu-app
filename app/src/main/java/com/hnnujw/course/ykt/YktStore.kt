package com.hnnujw.course.ykt

import android.content.Context
import android.util.Log
import com.hnnujw.course.manager.CredentialStore
import com.hnnujw.course.manager.UserManager

/**
 * 一卡通的本地状态：**仅** `synjones-auth` 会话令牌。
 *
 * ## 安全边界（最重要的一条）
 *
 * 本 Store **不存储任何一卡通密码**，也没有对应的存取 API
 *（详见 [clearAccount] 上方的说明）。一卡通的登录在官方页面上完成，
 * 本应用只接管服务端下发的会话令牌 —— 那条令牌泄露的后果远小于
 * 用户口令泄露（令牌可失效、可重登，口令不能）。
 *
 * ## 账号口径（本项目最高频踩坑，务必注意）
 *
 * 本工程并存两种账号键：`currentAccountKey`（`hnnu::2024001`）与
 * `currentAccountStorageKey`（`hnnu__2024001`），**永不相等**，且调用方不统一。
 * 因此本 Store 的**所有**按账号落盘的读写一律先过 [normalize]，
 * 否则会出现"写进一个键、读另一个键"→ 表现为"明明绑定过却提示未绑定"。
 */
object YktStore {

    private const val TAG = "Ykt"
    private const val PREFS = "ykt_prefs"
    private const val KEY_TOKEN_PREFIX = "token_"

    /**
     * 旧版本遗留的凭据键前缀。
     *
     * 只在 [clearAccount] 里用来**清理残留**，不再有任何写入路径。
     */
    private const val LEGACY_CREDENTIAL_PREFIX = "ykt::"

    /** 全应用共用一个 OkHttp 实例（连接池/线程池复用）。 */
    private val sharedClient: YktClient by lazy { YktClient() }

    fun client(): YktClient = sharedClient

    /** 当前账号在教务侧的学号，作为一卡通的默认登录账号。 */
    fun academicStudentId(): String {
        val manager = UserManager.getInstance()
        return manager.studentId?.trim().orEmpty().ifBlank { manager.username?.trim().orEmpty() }
    }

    /**
     * 账号键归一化：与 `UserManager.toStorageKey` 用同一条规则
     * （非 `[A-Za-z0-9_.-]` 一律换成 `_`）。理由见类注释。
     */
    internal fun normalize(accountKey: String): String =
        accountKey.ifBlank { "default" }.replace(Regex("[^A-Za-z0-9_.-]"), "_")

    fun token(context: Context, accountKey: String): String =
        prefs(context).getString(KEY_TOKEN_PREFIX + normalize(accountKey), "").orEmpty()

    /**
     * 一卡通的统一身份认证入口（供 WebView 打开）。
     *
     * ## 正确的入口是 `berserker-auth` 的 CAS 网关，**不是**首页地址
     *
     * 淮师前端 `frontInfo.loginType` **只有 `sso`**（wisedu CAS），没有账密登录接口，
     * 所以不能在 App 内做账号密码表单，只能走 WebView 完成 CAS 跳转，
     * 再从 Cookie 里接管 `synjones-auth`。
     *
     * ⚠️ **实测教训**：最初图省事直接用登录后的首页 `/plat/shouyeUser?appId=1`
     * 作为入口，假设"未登录时 CAS 会自动重定向"。**这是错的**：
     *
     * | 入口 | 实测行为 |
     * |---|---|
     * | `/plat/shouyeUser?appId=1` | **200**，直接返回 SPA 空壳 —— **不跳认证**，用户看不到登录框 |
     * | `/berserker-auth/cas/login/wisedu` | **302 → `xxmh.hnnu.edu.cn/cas/`** ✅ 真正触发认证 |
     *
     * 前端 SPA 是"客户端路由"：它自己也不主动跳 CAS，未登录时只渲染一个空壳。
     * 真正的认证跳转由**服务端的 `berserker-auth` 网关**发起。
     *
     * ## 参数链（逐层解码）
     *
     * ```
     * https://xxmh.hnnu.edu.cn/cas/login?service=<A>      # 统一身份认证页（跨域！xxmh 域）
     *   A = https://yktapp.hnnu.edu.cn/berserker-auth/cas/login/wisedu?targetUrl=<B>
     *   B = https://yktapp.hnnu.edu.cn/plat?name=loginTransit
     * ```
     *
     * `targetUrl` 指向 `/plat?name=loginTransit`（"登录中转"页）—— **由它负责
     * 把 SSO 票据换成 `synjones-auth` Cookie 并落盘**，所以必须保留这一层，
     * 不能把 targetUrl 换成首页地址。
     *
     * ## 白名单提醒
     *
     * 这条链路会**跨到 `xxmh.hnnu.edu.cn`**（CAS 页），因此取令牌的域名白名单
     * 仍只认 `yktapp.hnnu.edu.cn` —— CAS 域只用于登录，不在那里取业务令牌。
     */
    fun loginUrl(): String = YktClient.loginEntryUrl()

    fun saveToken(context: Context, accountKey: String, token: String) {
        prefs(context).edit().putString(KEY_TOKEN_PREFIX + normalize(accountKey), token).apply()
    }

    fun clearToken(context: Context, accountKey: String) {
        prefs(context).edit().remove(KEY_TOKEN_PREFIX + normalize(accountKey)).apply()
    }

    // ── 关于"一卡通密码"：本 Store **刻意不提供任何密码存取 API** ─────────────
    //
    // 曾经这里有 hasPassword / savePassword / loadPassword 三个方法（走 CredentialStore），
    // 但**调用点始终为零**，现已删除。这不是顺手清理，而是**安全边界**：
    //
    // 淮师一卡通只有 SSO（见 [loginUrl]），登录全程在官方页面完成，
    // 本应用拿到的只是一个会话令牌，**从头到尾不需要、也拿不到用户的一卡通密码**。
    // 那三个方法放在这里等于给出一个"存密码"的钩子 —— 后人看到就会顺手调用它去做
    // 静默登录，从而把"永不接触一卡通密码"这条边界悄悄破坏掉。
    //
    // 因此：**不要重新加回密码存取**。若将来真需要自动续期，
    // 正确做法是走 refresh_token（见 docs/adaptation/2026-09-25-hnnu-ykt-feasibility.md），
    // 而不是保存用户口令。

    fun clearAccount(context: Context, accountKey: String) {
        clearToken(context, accountKey)
        // 兼容旧版本：历史上曾写入过 `ykt::` 前缀的凭据键，删号时一并清掉，
        // 避免升级用户的旧密钥残留在系统密钥库 / 加密存储里。
        CredentialStore.remove(context, credentialKey(accountKey))
        // 电费查询的"上次选择"同样是账号私有数据，删号必须一起清，
        // 否则下一个登进同一账号的人会看到上一个学生的宿舍号。
        YktSelectionStore.clear(context, accountKey)
    }

    /**
     * 把失败降级成一句可展示的文案，顺手清掉已失效的令牌。
     *
     * 只有 `sessionExpired` 才清令牌：网络抖动不能把用户的登录状态抹掉，
     * 否则重连一次就要重新输密码。
     */
    fun handleFailure(context: Context, accountKey: String, error: Throwable): String {
        val failure = error as? YktException
        if (failure?.sessionExpired == true) {
            Log.i(TAG, "一卡通令牌已失效，清理本地缓存")
            clearToken(context, accountKey)
        }
        return failure?.message ?: error.message ?: "一卡通加载失败，请重试"
    }

    /** 旧版本遗留凭据键（只为删号时清理残留，无写入路径）。 */
    private fun credentialKey(accountKey: String) = LEGACY_CREDENTIAL_PREFIX + normalize(accountKey)

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
