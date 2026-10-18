package com.hnnujw.course.academic

import com.hnnujw.course.manager.UserManager

/**
 * 后台刷新拿到的会话 Cookie 回写持久化。
 *
 * ## 为什么需要它
 *
 * 教务的会话 Cookie 是**服务端每轮可能续期**的（访问一次就给一个新值，旧的作废）。
 * 应用里有两条完全不同的会话路径：
 *
 *  - **持久化 Cookie**（`UserManager.savedCookie` → SharedPreferences）：
 *    冷启动时靠它恢复登录态；
 *  - **内存 CookieJar**（`AcademicCookieJar`）：本次进程内真正发请求用的那份。
 *
 * 后台巡检（消息中心 / 成绩订阅 / 考试列表）走的是内存 Jar，服务端下发的新 Cookie
 * 只会进 Jar。原先**没有任何人把它写回持久化**，于是：
 *
 *  1. 内存 Jar 里的 Cookie 被续期了，持久化的还是旧的那个；
 *  2. 用户在 Jar 被续期后又杀掉进程重进 → 从持久化恢复出**旧 Cookie**；
 *  3. 旧 Cookie 若已被服务端作废，恢复出来的就是一张死票 —— 表现为「明明刚收到
 *     消息通知，重进应用却提示登录失效」。
 *
 * 更糟的是失效还可能被误判成「有人异地登录」，触发不必要的重登。
 *
 * ## 怎么写得安全
 *
 * 用 [UserManager.saveCookieIfCurrent] 而不是 `saveCookie`：它带 session token 校验，
 * 「这一轮请求发出时的会话」与「现在要写入的会话」不是同一个（用户中途切了号、
 * 或已经重新登录过一次）时**整笔丢弃**。少了这道校验，一个慢请求的返回会把
 * 新账号的 Cookie 覆盖成旧账号的。
 *
 * ## 什么时候写
 *
 * 只在**请求成功后**写，且只在 Cookie 真的变了时写：
 *  - 失败（网络/超时）时 Jar 里可能是半截状态，写进去只会污染；
 *  - 每次请求都写盘等于把 SharedPreferences 当热路径用（后台巡检 12 小时一次尚可，
 *    但用户连续翻消息列表时就是每页一次）。
 */
object AcademicCookieSync {

    /**
     * 把内存会话里**当前生效**的 Cookie 头回写持久化。
     *
     * @param expected 发起本轮请求时的会话 token。与当前会话不一致时整笔丢弃。
     * @return 是否真的写入了（false = 会话已切换 / Cookie 没变 / 为空）。
     *
     * ⚠️ 必须在 IO 线程调用：内部会读 SharedPreferences 并落盘。
     */
    fun persistCurrent(
        school: com.hnnujw.course.model.SchoolConfig?,
        expected: com.hnnujw.course.manager.SessionToken,
    ): Boolean {
        if (school == null) return false
        val user = UserManager.getInstance()
        // 会话已切换（用户重登 / 切号）：这一轮的 Cookie 属于旧会话，丢弃。
        if (!user.sessionState.isCurrent(expected)) return false

        val header = runCatching {
            AcademicGatewayFactory.cookieHeaderFor(school, expected.accountStorageKey)
        }.getOrNull().orEmpty()
        if (header.isBlank()) return false
        // 没变化就不要写盘。
        if (header == user.savedCookie) return false
        // 带 token 校验的写入：慢请求返回时若会话已换，这里会整笔丢弃。
        user.saveCookieIfCurrent(expected, header)
        return true
    }

    /**
     * 一次后台请求结束后的统一收尾：把可能被续期的 Cookie 落盘。
     *
     * 调用时机必须**在请求成功之后**（外层用 `runCatching` 包住也行，失败时
     * `cookieHeaderFor` 大概率与已存的一致，会被上面的「没变化就不写」挡掉）。
     */
    fun persistAfter(
        school: com.hnnujw.course.model.SchoolConfig?,
        expected: com.hnnujw.course.manager.SessionToken,
    ) {
        runCatching { persistCurrent(school, expected) }
    }
}
