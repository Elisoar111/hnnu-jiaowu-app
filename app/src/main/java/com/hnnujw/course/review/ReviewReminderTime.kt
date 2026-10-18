package com.hnnujw.course.review

/**
 * 「今日待复习」提醒的时间计算（纯函数，可直接单测）。
 *
 * ## 为什么不用固定间隔
 *
 * 电费提醒用「每 6 小时」是对的 —— 余额下降与钟点无关。
 * 复习提醒不一样：它必须落在**用户方便刷题的时刻**（默认晚上 20:00），
 * 而不是「上次触发 + 24 小时」，否则用户周六早上刷完题，
 * 下次提醒就飘到周日早上了，一天天越飘越离谱。
 *
 * ## 口径
 *
 * - 按**本地时区**算，用 [java.util.TimeZone.getDefault] 的偏移；
 * - "今天 20:00" 已经过了，就排到明天 20:00；
 * - 跨夏令时/改系统时间不需要特殊处理：每次重排都从"现在"重新算。
 *
 * 这里刻意不用 java.time：minSdk 24 上 `java.time` 需要 desugaring，
 * 而本项目没有开。`Calendar` 在这个精度上完全够用，且不引入任何构建配置。
 */
object ReviewReminderTime {

    /**
     * 下一次触发时刻（毫秒时间戳）。
     *
     * @param now 当前时刻。
     * @param hourOfDay 目标小时（0..23）。越界会被夹回合法范围而不是抛异常 ——
     *   这个值来自本地偏好，用户手改过存储就可能越界，那时应当"退回默认"而不是崩溃。
     * @param minute 目标分钟（0..59），同样夹取。
     * @return 严格**大于** [now] 的时刻（同一分钟已经过了就排明天）。
     */
    fun nextTrigger(now: Long, hourOfDay: Int = DEFAULT_HOUR, minute: Int = DEFAULT_MINUTE): Long {
        val hour = hourOfDay.coerceIn(0, 23)
        val min = minute.coerceIn(0, 59)
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = now
        cal.set(java.util.Calendar.HOUR_OF_DAY, hour)
        cal.set(java.util.Calendar.MINUTE, min)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        var candidate = cal.timeInMillis
        // 严格大于：正好落在目标分钟上（秒=0）也算"已经过了"，排明天。
        // 否则会出现"设成现在这一分钟 → 立刻触发一次"的意外。
        if (candidate <= now) {
            cal.add(java.util.Calendar.DAY_OF_MONTH, 1)
            candidate = cal.timeInMillis
        }
        return candidate
    }

    /**
     * 该不该发提醒。
     *
     * 只看「有没有到期题」和「开关开没开」。刻意**不**判断"今天是否已经提醒过" ——
     * 那是节流的职责，放在这里会让本函数有状态，就不再是纯函数了。
     *
     * @param enabled 用户开关。
     * @param dueCount 今日到期题数。
     */
    fun shouldNotify(enabled: Boolean, dueCount: Int): Boolean = enabled && dueCount > 0

    /** 默认提醒时间：晚上 8 点。 */
    const val DEFAULT_HOUR = 20
    const val DEFAULT_MINUTE = 0

    /** 提醒文案（通知标题与正文）。抽出来是为了让文案与单测口径一致。 */
    fun title(): String = "今天的错题该复习了"

    fun body(dueCount: Int): String = when {
        dueCount <= 0 -> "没有到期的错题"
        dueCount == 1 -> "有 1 道错题到期了，花两分钟过一遍吧。"
        else -> "有 $dueCount 道错题到期了，复习一遍记得更牢。"
    }
}
