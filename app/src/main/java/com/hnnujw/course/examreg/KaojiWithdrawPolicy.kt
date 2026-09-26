package com.hnnujw.course.examreg

import com.hnnujw.course.xuegong.parseSiteTime

/**
 * 「这条已报名记录还能不能退报」的判定口径。
 *
 * 抽成纯函数是为了**可测**：这段判断原先长在 Composable 里
 * （`KaojiRegistrationScreen.canWithdraw`），既没法单测，也把"为什么不能退"
 * 这个信息压成了一个 `Boolean` —— 界面只好给所有不能退的情况印同一句话。
 * 而实际上不能退的理由至少有三种，说错了会让人以为是自己错过了时间。
 *
 * ## 与服务端的分工
 *
 * 真正的权威在服务端，客户端只做**不会误伤**的前置过滤：
 * 凡是我们能确定服务端一定会拒的，就别把按钮摆出来；剩下的放行到确认弹窗，
 * 由 [KaojiClient.withdrawRegistration] 里的即时查询兜底。
 *
 * 服务端规则（`xskjbm.js` 的退报分支，实测复刻）：
 * ```
 * POST kjgl/kjbm_cxXskjbmjfzt.html?xsbmqk_id=…
 * if (data.sfqr == '1' || data.sfzfzzt == '1') → 该项目已经缴费或正在缴费，无法退报！
 * ```
 */
object KaojiWithdrawPolicy {

    /**
     * 不能退报的原因；**null = 可以退报**。
     *
     * @param record 要判断的记录。
     * @param currentProjects 当前类别的开放批次（来自入口页卡片）。
     * @param nowMillis 当前时间，注入进来便于测试。
     */
    fun blockReason(
        record: KaojiRegistered,
        currentProjects: List<KaojiProject>,
        nowMillis: Long,
    ): String? {
        // ① 已缴费：服务端明确拒绝（sfqr == '1'），且这条信息就在列表里，无需请求。
        //    注意只能拦 sfqr；sfzfzzt（正在缴费）列表信封里没有，交给退报请求里的
        //    即时查询去拦 —— 少拦一种不会让用户退掉已缴的记录，只是多一次"点了才被拒"。
        if (record.paid) return "已缴费，不支持退报"

        // ② 批次不在当前开放列表里 = 往期学期或已收起的批次。
        //    站点只在当前批次上挂退报入口，所以这里也不给。
        val project = currentProjects.firstOrNull { it.id == record.projectId }
            ?: return "该批次已不在本学期开放列表中，不支持退报"

        // ③ 过了报名截止时间。截止时间解析不出来时**放行**（交给服务端判断），
        //    宁可多问一次，也别因为解析失败就武断地把入口藏掉。
        val deadline = parseSiteTime(project.endTime) ?: return null
        return if (nowMillis <= deadline) null else "已过报名截止时间，不支持退报"
    }
}
