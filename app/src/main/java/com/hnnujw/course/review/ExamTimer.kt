package com.hnnujw.course.review

/**
 * 限时考试的计时纯逻辑。
 *
 * 与 [PracticeSession] 一样，这里刻意**不碰 Android / Compose**：倒计时是"考试"这种
 * 强规则的场景，一旦把剩余时间、警告时机的判断写进 Composable，就只能靠上真机等
 * 到点来验证，而且"到底提醒过几次""归零那一刻该不该交卷"这类边界根本没法回归。
 *
 * 三条约定：
 *  1. **不限时用 0 表示**（`durationSeconds <= 0`）—— 不用 -1、不用 null，只有一种
 *     "关闭"写法，调用方判断时不必记得两套。
 *  2. 所有判定**返回原因枚举/列表而非 Boolean**：调用方需要知道"为什么"，而且一条
 *     时间轴上可能同时命中多个动作（例如一口气跳了好几分钟）。
 *  3. 时间一律以**秒**为单位、由调用方传进来（`elapsedSeconds` / `remaining`）；
 *     本文件不读 `System.currentTimeMillis()`，因此可测、可复现。
 */

/** 一次限时考试的时长配置。`durationSeconds <= 0` 表示不限时。 */
data class ExamTiming(val durationSeconds: Int) {
    /** 是否限时。 */
    val isTimed: Boolean get() = durationSeconds > 0
}

/**
 * 「限时考试」时长档位的纯数据（放这里而不是 Composable，便于复用与单测）。
 *
 * 第一档固定「不限时」(`seconds = 0`)，与 [ExamTiming] 的关闭约定一致。
 */
data class ExamDurationOption(val label: String, val seconds: Int)

/** 界面下拉的时长档位：不限时 / 20 / 30 / 60 分钟。 */
val ExamDurationOptions: List<ExamDurationOption> = listOf(
    ExamDurationOption("不限时", 0),
    ExamDurationOption("20 分钟", 20 * 60),
    ExamDurationOption("30 分钟", 30 * 60),
    ExamDurationOption("60 分钟", 60 * 60)
)

/** 不限时（或时长非法）时 `remainingSeconds` 的返回值，取 Int 上界当"无限"。 */
private const val UNLIMITED_REMAINING: Int = Int.MAX_VALUE

/** 剩余 5 分钟的警告阈值（秒）。 */
private const val WARN_FIVE_SECONDS: Int = 5 * 60

/** 剩余 1 分钟的警告阈值（秒）。 */
private const val WARN_ONE_SECONDS: Int = 60

/**
 * 剩余秒数。
 *
 * [totalSeconds] 为不限时（<= 0）时返回 [UNLIMITED_REMAINING]；否则返回
 * `totalSeconds - elapsedSeconds` 并**夹取到 >= 0**（超时后不会出现负数剩余）。
 */
fun remainingSeconds(totalSeconds: Int, elapsedSeconds: Int): Int {
    if (totalSeconds <= 0) return UNLIMITED_REMAINING
    val elapsed = elapsedSeconds.coerceAtLeast(0)
    return (totalSeconds - elapsed).coerceAtLeast(0)
}

/**
 * 把剩余秒数格式化成倒计时文本。
 *
 * - 不限时（[UNLIMITED_REMAINING]）→ `"--:--"`；
 * - 不足 60 分钟 → `"mm:ss"`（分补零到两位，如 `"05:09"`）；
 * - 满 60 分钟及以上 → `"h:mm:ss"`（小时不补零，如 `"1:00:00"`）。
 *
 * 负数一律按 0 处理，不显示 `"-1:-1"` 这种东西。
 */
fun formatRemaining(seconds: Int): String {
    if (seconds == UNLIMITED_REMAINING) return "--:--"
    val total = seconds.coerceAtLeast(0)
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val secs = total % 60
    return if (hours > 0) {
        String.format("%d:%02d:%02d", hours, minutes, secs)
    } else {
        String.format("%02d:%02d", minutes, secs)
    }
}

/** 时间轴上应当触发的动作。返回列表（而非 Boolean）以保留"为什么"。 */
enum class ExamTimeoutAction {
    /** 时间已耗尽，应自动交卷。 */
    Submit,

    /** 剩余 5 分钟，提醒一次。 */
    WarnFiveMinutes,

    /** 剩余 1 分钟，提醒一次。 */
    WarnOneMinute
}

/**
 * 根据当前剩余时间与"已警告过"标记，算出本次 tick 应当触发的动作。
 *
 * 规则：
 *  - 剩余归零（`<= 0`）→ [ExamTimeoutAction.Submit]；
 *  - 剩余 `<= 300` 秒且**尚未**警告过 5 分钟 → [ExamTimeoutAction.WarnFiveMinutes]；
 *  - 剩余 `<= 60` 秒且**尚未**警告过 1 分钟 → [ExamTimeoutAction.WarnOneMinute]。
 *
 * 一次性跳过多档（例如后台回来直接剩 30 秒）时会同时返回两条警告 + 未归零则不含
 * Submit —— 让调用方自己决定提示的先后，本函数不吞掉任何一档。
 *
 * @param remaining 剩余秒数（取自 [remainingSeconds]，不限时会极大，自然一条都不命中）
 * @param warnedFive 是否已经提示过 5 分钟
 * @param warnedOne 是否已经提示过 1 分钟
 */
fun examTimeouts(
    remaining: Int,
    warnedFive: Boolean,
    warnedOne: Boolean
): List<ExamTimeoutAction> {
    val actions = ArrayList<ExamTimeoutAction>(2)
    if (remaining <= 0) {
        actions.add(ExamTimeoutAction.Submit)
        return actions
    }
    if (remaining <= WARN_FIVE_SECONDS && !warnedFive) {
        actions.add(ExamTimeoutAction.WarnFiveMinutes)
    }
    if (remaining <= WARN_ONE_SECONDS && !warnedOne) {
        actions.add(ExamTimeoutAction.WarnOneMinute)
    }
    return actions
}
