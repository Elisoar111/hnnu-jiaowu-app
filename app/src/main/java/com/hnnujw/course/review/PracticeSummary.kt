package com.hnnujw.course.review

/**
 * 「提交」之后的**总结页**要用的全部数字与文案（纯逻辑）。
 *
 * 2026-10 用户要求：顺序练习 / 乱序练习 / 考试答完最后一题后，底部的「完成」改成
 * 「提交」，并进一个**总结页面**，页面上要有本次答题的数据与一句鼓励语。
 *
 * 为什么单独抽一层：
 *  - **正确率的分母必须只有一个口径**。`PracticeResult.answered` 是"碰过的题"
 *    （含"选了但没确认"），而界面副标题里的正确率用的是"已判分的题"。两个口径混用
 *    会出现"顶部写 67%、总结页写 40%"这种自相矛盾的数字，而它**不崩不报错**，
 *    只有用户自己发现。这里统一取 **judged = 对 + 错**。
 *  - **鼓励语要可单测**。它是分档文案，档位边界（85 还是 80）写错没人看得出来，
 *    而且"一题都没答"和"全错"必须给不同的话 —— 否则会对着 0% 说"非常棒"。
 *
 * 本文件不读墙钟、不碰 Compose：用时由调用方按墙钟算好传进来（[elapsedSeconds]）。
 */

/** 一轮练习 / 考试的总结。[timedOut] 与 [durationSeconds] 只影响措辞与展示，不改数字。 */
data class PracticeSummary(
    val total: Int,
    val correct: Int,
    val wrong: Int,
    val unjudged: Int,
    /** 本次用时（秒）。开始时刻未知时传 0，界面照常显示 00:00。 */
    val elapsedSeconds: Int,
    /**
     * 本轮限时长度（秒）。`<= 0` = 不限时 —— 与 [ExamTiming] 用同一个约定，
     * 不引入第二种"关闭"写法。
     */
    val durationSeconds: Int = 0,
    /** 是否因时间耗尽被自动交卷。 */
    val timedOut: Boolean = false
) {
    /** 是否为限时考试。 */
    val timed: Boolean get() = durationSeconds > 0

    /** 考试时长（分钟，向上取整）。仅 [timed] 时有意义。 */
    val durationMinutes: Int
        get() = if (durationSeconds <= 0) 0 else (durationSeconds + 59) / 60

    /** 已判分的题数（对 + 错）。正确率与鼓励语都以它为分母。 */
    val judged: Int get() = correct + wrong

    /**
     * 正确率（0..100）。
     *
     * ⚠️ 分母是 [judged] 而**不是** `total`：没答的题不该被算成答错。
     * 一题都没判分时返回 0（不抛、不出 NaN）。
     */
    val accuracyPercent: Int get() = accuracyPercent(correct, judged)

    /** 页面标题。 */
    val title: String get() = when {
        timedOut -> "考试时间到"
        timed -> "考试结束"
        else -> "练习完成"
    }

    /** 一句鼓励语。分档规则见 [encouragementFor]。 */
    val encouragement: String get() = encouragementFor(accuracyPercent, judged, total)

    /** 用时文案（`mm:ss`，满一小时走 `h:mm:ss`）。复用倒计时的格式化，两处口径一致。 */
    val elapsedLabel: String get() = formatRemaining(elapsedSeconds)

    /**
     * 数据行：`共 10 题 · 答对 8 · 答错 2`，未作答数 > 0 时才追加。
     *
     * 与旧的 `ResultPane` 逐字一致 —— 那是用户已经见过的写法，不必为了"新页面"换一套。
     */
    val tallyLabel: String get() = buildString {
        append("共 $total 题 · 答对 $correct · 答错 $wrong")
        if (unjudged > 0) append(" · 未作答 $unjudged")
    }
}

/** 由会话结果 + 用时拼出总结。[timedOut] 只影响标题措辞，不改任何数字。 */
fun practiceSummaryOf(
    result: PracticeResult,
    elapsedSeconds: Int,
    durationSeconds: Int = 0,
    timedOut: Boolean = false
): PracticeSummary = PracticeSummary(
    total = result.total,
    correct = result.correct,
    wrong = result.wrong,
    unjudged = result.unjudged,
    elapsedSeconds = elapsedSeconds.coerceAtLeast(0),
    durationSeconds = durationSeconds.coerceAtLeast(0),
    timedOut = timedOut
)

/**
 * 本次用时（秒）。
 *
 * [startedAt] 为 `0L`（未设定开始时刻）或时钟倒退时返回 0，而不是负数 ——
 * 界面上出现 `-00:03` 只会让人以为程序坏了。
 */
fun elapsedSeconds(startedAt: Long, nowMillis: Long): Int {
    if (startedAt <= 0L) return 0
    val delta = nowMillis - startedAt
    if (delta <= 0L) return 0
    return (delta / 1000L).toInt()
}

/**
 * 鼓励语：按正确率分档，**永远不说"你很差"**，但也不撒谎。
 *
 * 分档刻意做成"先说成绩、再给下一步"，因为总结页的真正用途是**决定接下来做什么**
 * （继续下一轮 / 去错题本 / 看解析），而不是发一个分数就走。
 *
 * @param accuracyPercent 正确率（0..100）
 * @param judged 已判分的题数。为 0 时说明这轮压根没作答，不能按"正确率 0%"发鼓励语 ——
 *   那两件事对用户是完全不同的意思（一个是"还没开始"，一个是"全错"）。
 * @param total 本轮总题数。
 */
fun encouragementFor(accuracyPercent: Int, judged: Int, total: Int): String = when {
    total <= 0 -> "这一轮没有题目。"
    judged <= 0 -> "这一轮还没有作答记录 —— 先做几道题再提交吧。"
    accuracyPercent >= 95 -> "几乎全对，这个题库你已经吃透了。"
    accuracyPercent >= 85 -> "非常棒，手感很稳，保持住。"
    accuracyPercent >= 70 -> "不错，大部分都拿下了 —— 把错的几道再过一遍就稳了。"
    accuracyPercent >= 50 -> "已经过半了。错题本帮你记着，刷一遍会明显好转。"
    accuracyPercent > 0 -> "别急着下结论，先把错题的解析读一遍，再来一轮会不一样。"
    else -> "万事开头难，先看懂解析，下一轮一定比这一轮强。"
}
