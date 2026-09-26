package com.hnnujw.course.ykt

/**
 * 电费低余额提醒的**决策逻辑**（纯函数，无 Android 依赖，可直接单测）。
 *
 * ## 判定口径（2026-09-25 用户明确要求后调整）
 *
 * **只要余额低于阈值就提醒，不限制提醒次数。**
 *
 * 早期实现是"同一欠费周期只响一次"（余额回升到阈值以上才允许再次提醒）。
 * 用户明确要求改为**每次巡检只要低于阈值就发提醒**——理由是欠费会断电，
 * 而用户往往正是"知道低了但没去充"，此时反复提醒比只响一次更有实际价值。
 *
 * ## 变更同时带来的两个必须处理好的点
 *
 * 1. **不能变成骚扰**：后台巡检是**一天一次**（[YktAlertScheduler.INTERVAL_MS]），
 *    所以"每次都提醒"的实际频率是每日一条，不会刷屏。若将来把巡检改密，
 *    必须重新评估这个决定（这正是把判据抽成纯函数的原因，改一处即可）。
 * 2. **解析失败绝不能报**：余额为 `null` 说明"接口通了但没解析出数字"，
 *    此时余额未知，报"欠费"会吓到用户 → **不提醒**。宁可漏报不可误报。
 *
 * ## 为什么保留 `AlertState`
 *
 * 既然不再去重，留状态只为两件事：**排查**（上次提醒时的余额与时间）
 * 与**未来可能的节流**（例如"同一天内不重复"）。判定本身已不看 active。
 */
object YktAlertPolicy {

    /**
     * 提醒判定的结果。
     *
     * @param shouldNotify 是否应当发通知
     * @param nextState 调用方应保存的新状态（无论是否发通知都要落盘）
     */
    data class Decision(
        val shouldNotify: Boolean,
        val nextState: AlertState,
    )

    /**
     * 提醒状态（仅用于查询/排查，**不再参与去重决策**）。
     *
     * @param active true = 最近一次判定时余额低于阈值
     * @param lastNotifiedAmount 上次提醒时的余额（展示与排查用）
     */
    data class AlertState(
        val active: Boolean = false,
        val lastNotifiedAmount: Double? = null,
    ) {
        companion object {
            val NONE = AlertState(active = false, lastNotifiedAmount = null)
        }
    }

    /**
     * 核心判定。
     *
     * 规则（逐条都可单测）：
     * - 余额为 `null`（接口通了但解析不出数字）→ **不提醒**，状态原样保留。
     * - 余额 < 阈值 → **提醒**（不论此前是否提醒过），状态记为 active。
     * - 余额 **≥** 阈值 → 不提醒，状态记为 NONE（用于排查"上次何时回正"）。
     *
     * @param balance 当前电费余额（元）；null = 未能获取
     * @param threshold 阈值（元）
     * @param previous 上一次的状态（**不影响是否提醒**，仅用于继承 lastNotifiedAmount）
     */
    fun decide(balance: Double?, threshold: Double, previous: AlertState): Decision {
        // 余额未知：什么都不做，也**不清空**已有状态
        if (balance == null) return Decision(shouldNotify = false, nextState = previous)

        // 余额充足：不提醒，状态置空
        if (balance >= threshold) return Decision(shouldNotify = false, nextState = AlertState.NONE)

        // 余额低于阈值 → **每次都提醒**（用户要求：不限制次数）
        return Decision(
            shouldNotify = true,
            nextState = AlertState(active = true, lastNotifiedAmount = balance),
        )
    }

    /** 通知文案：余额与阈值都要给出，用户才知道是"谁触发的"。 */
    fun notificationTitle(): String = "宿舍电费余额不足"

    fun notificationText(balance: Double, threshold: Double): String =
        "当前余额 ${YktBalance.formatYuan(balance)} 元，" +
            "已低于设定的 ${YktBalance.formatYuan(threshold)} 元，请及时充值。" +
            "（充值请前往校园卡官方渠道）"

    /** 去重状态在 prefs 里的键。 */
    fun stateKeyPrefix(): String = "alert_state_"
}
