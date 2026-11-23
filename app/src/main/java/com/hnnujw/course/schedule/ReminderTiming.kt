package com.hnnujw.course.schedule

/**
 * 提醒从「排出去」到「真的响」之间那段系统延迟。
 *
 * 现场：提醒设置里选的是「提前 45 分钟」，课程详情写着「下次提醒：11月30日 07:15」
 * —— 计划本身是对的（第 1 节 08:00 − 45 分钟 = 07:15），闹钟也确实排进了 AlarmManager。
 * 但通知实际在 07:45 才弹出来，离上课只剩 15 分钟，看上去就像「提前量设置没生效」。
 *
 * 原因不在规划，而在**送达**：`setExactAndAllowWhileIdle` 只保证「不早于」这个时刻，
 * 不保证「就是」这个时刻。Doze、应用待机分组，以及国产 ROM（ColorOS 等）对后台应用的
 * 冻结，都会把已经到点的闹钟压到下一次被唤醒时才派发。
 * 于是以前这里有两处口径都不可靠：
 *
 *  1. 迟到超过 [LATE_GRACE_MS] 就**干脆不发**（课都上了一半，通知才到，确实没意义）；
 *  2. 迟到但没超窗的那条，标题写死「即将上课」—— 07:45 响、08:00 上课时是对的，
 *     但 08:20 才响时这句话就成了假话。
 *
 * 现在两条都按真实时间说话：迟到多少，就如实写还剩多少 / 已经上了多久。
 */

/**
 * 迟到的闹钟仍然补发通知的宽限窗口。
 *
 * 以前是 10 分钟：Doze 推迟十几分钟就把整条提醒丢掉，用户看到的是「这节课没提醒」。
 * 放宽到 45 分钟（并且文案改成「已经开始 N 分钟」）之后，被冻结的 ROM 也至少能在课上
 * 提醒一次，而不是什么都不说。
 */
internal const val LATE_GRACE_MS = 45L * 60 * 1000L

/** 到点时这条提醒还值不值得弹。 */
internal enum class ReminderDelivery {
    /** 发通知。 */
    Deliver,

    /** 课已经上完，或迟到超过宽限：不再打扰，只把闹钟链推下去。 */
    Stale
}

/**
 * 到点（[now]）之后这条提醒该不该发。
 *
 * @param now 实际送达时刻（广播到达的时间，不是计划触发时刻）
 * @param startsAt 上课时刻
 * @param endsAt 下课时刻；拿不到节次时间时传 null，此时只按宽限窗口判断
 */
internal fun reminderDelivery(now: Long, startsAt: Long, endsAt: Long?, graceMs: Long = LATE_GRACE_MS): ReminderDelivery =
    when {
        // 课已经上完：这时候弹通知只会让人以为系统在瞎响。
        endsAt != null && now >= endsAt -> ReminderDelivery.Stale
        // 迟到超过宽限（或本来就过了很久才被唤醒）：同上。
        now - startsAt > graceMs -> ReminderDelivery.Stale
        else -> ReminderDelivery.Deliver
    }

/**
 * 通知标题里描述课次状态的那一句。
 *
 * - 还没到上课时间 → `即将上课`（提前量比系统延迟大时的正常情形）
 * - 已经在上课 → `已经开始 N 分钟`（N 取整分钟，至少 1）
 *
 * 纯函数，所以「迟到 30 分钟还写着即将上课」这类口径错误能被单测直接钉住。
 */
internal fun reminderTitleSuffix(now: Long, startsAt: Long): String {
    if (now < startsAt) return "即将上课"
    val minutes = ((now - startsAt) / 60_000L).coerceAtLeast(1L)
    return "已经开始 $minutes 分钟"
}
