package com.hnnujw.course.ykt

/**
 * 电费低余额提醒的**决策逻辑**（纯函数，无 Android 依赖，可直接单测）。
 *
 * ## 判定口径（2026-09-25 用户明确要求后调整；2026-10-05 加同余额节流）
 *
 * **只要余额低于阈值就提醒**——但同一余额在 [SAME_BALANCE_REPEAT_INTERVAL_MS] 内
 * 不重复发。起因：巡检从一天一次加密到一天四次（见 [YktAlertScheduler]）后，
 * "每次都提醒"会变成一天 4 条同样的通知；而用户当初要"不限次数"针对的是旧策略
 * "同一欠费周期只响一次、不充值就永远沉默"——余额还在掉的**节流不影响**这个诉求：
 *  - 余额没变（用户知道低了但没充）→ 一天最多一条，够了；
 *  - 余额继续下降 → **立即再提醒**（这是"反复提醒有价值"的核心场景）；
 *  - 充值回升后再跌破 → 重新提醒（状态被重置）。
 *
 * ## 变更同时带来的两个必须处理好的点
 *
 * 1. **不能变成骚扰**：见上，同余额 24h 节流 + 巡检一天 4 次 ⇒ 最坏一天 4 条
 *    （只有余额持续下降时才可能），正常情况一天一条。
 * 2. **解析失败绝不能报**：余额为 `null` 说明"接口通了但没解析出数字"，
 *    此时余额未知，报"欠费"会吓到用户 → **不提醒**。宁可漏报不可误报。
 */
object YktAlertPolicy {

    /** 同一余额的重复提醒间隔：24 小时内余额没变就不再发第二条。 */
    const val SAME_BALANCE_REPEAT_INTERVAL_MS: Long = 24 * 60 * 60 * 1000L

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
     * 提醒状态。
     *
     * @param active true = 最近一次判定时余额低于阈值
     * @param lastNotifiedAmount 上次提醒时的余额（节流判据 + 排查用）
     * @param lastNotifiedAtMillis 上次真正发出通知的时刻（节流判据 + 排查用）
     */
    data class AlertState(
        val active: Boolean = false,
        val lastNotifiedAmount: Double? = null,
        val lastNotifiedAtMillis: Long = 0L,
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
     * - 余额 **≥** 阈值 → 不提醒，状态记为 NONE（用于排查"上次何时回正"）。
     * - 余额 < 阈值 → 提醒；但**同一余额在 24 小时内已提醒过则不重复**
     *   （余额继续下降、或距上次提醒已超 24 小时，都会再发）。
     *
     * @param balance 当前电费余额（元）；null = 未能获取
     * @param threshold 阈值（元）
     * @param previous 上一次的状态
     * @param nowMillis 当前时刻（毫秒墙钟），节流窗口的基准
     */
    fun decide(
        balance: Double?,
        threshold: Double,
        previous: AlertState,
        nowMillis: Long,
    ): Decision {
        // 余额未知：什么都不做，也**不清空**已有状态
        if (balance == null) return Decision(shouldNotify = false, nextState = previous)

        // 余额充足：不提醒，状态置空
        if (balance >= threshold) return Decision(shouldNotify = false, nextState = AlertState.NONE)

        // 低于阈值：提醒，但同一余额在窗口期内不重复（骚扰防线）
        val sameAmountRecentlyReminded = previous.active &&
            previous.lastNotifiedAmount == balance &&
            nowMillis - previous.lastNotifiedAtMillis < SAME_BALANCE_REPEAT_INTERVAL_MS
        return if (sameAmountRecentlyReminded) {
            Decision(shouldNotify = false, nextState = previous.copy(active = true))
        } else {
            Decision(
                shouldNotify = true,
                nextState = AlertState(
                    active = true,
                    lastNotifiedAmount = balance,
                    lastNotifiedAtMillis = nowMillis,
                ),
            )
        }
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
