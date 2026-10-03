package com.hnnujw.course.review

/**
 * 「到期复习 / 间隔重复」的纯逻辑。
 *
 * 错题本此前只有"在册 / 已掌握"两档，用户每天打开都要面对整本错题，没有"今天该看哪些"
 * 的抓手。间隔重复把每道错题按掌握程度排一个**下次复习时间**（`due_at`），只把到期的
 * 推到用户面前 —— 这才是"复习"该有的节奏。
 *
 * 三条刻意的取舍：
 *  1. **间隔只由掌握等级决定，不引入易度系数（SM-2 那套）**：错题本的量级不大，简单的
 *     固定间隔（0/1/3/7 天）已经够用，且每一档都能被单测逐字钉死；复杂算法反而难解释。
 *  2. **纯函数、时间由调用方传入**：本文件不读 `System.currentTimeMillis()`，因此
 *     "恰好到期 / 还差一秒 / 已过期"这类边界可以离线测，不受跑测时刻影响。
 *  3. **升级纯增量**：`due_at` 是后加列，老数据 `due_at = 0`（视为"立即到期"），
 *     既不丢数据也不强制回填，用户一进错题本就自然进入复习队列。
 */

/** 掌握等级。数值序即"掌握程度"，切勿打乱声明顺序依赖处（见 [wrongStatusOrder] 的教训）。 */
enum class MasteryLevel(val label: String) {
    /** 新入册，从未按新间隔复习过。 */
    New("新题"),

    /** 复习中。 */
    Learning("学习中"),

    /** 较熟。 */
    Familiar("较熟"),

    /** 已掌握（连对达到 [ReviewDatabase.MASTER_STREAK] 次）。 */
    Mastered("已掌握")
}

/** 连对达到这个次数即认为「已掌握」（与 [ReviewDatabase.MASTER_STREAK] 同源）。 */
private const val MASTERED_STREAK: Int = 3

/**
 * 由连对次数与错误次数推出掌握等级。
 *
 * 判定顺序：**连对优先** —— 只要连对够数就是已掌握，即使历史错误很多（错误多说明在
 * 复习中，"现在掌握了"是更近的事实，不该被旧错误数否掉）。
 *
 * 错误次数 [wrongCount] 用来把"刚入册"和"复习中"分开：从没答错过（0 次）算 [New]…… 
 * 但错题本里的题按定义都错过至少一次，所以这里 [New] 只会在调用方传入未入册数据时出现。
 */
fun masteryLevelOf(streak: Int, wrongCount: Int): MasteryLevel = when {
    streak >= MASTERED_STREAK -> MasteryLevel.Mastered
    streak >= 2 -> MasteryLevel.Familiar
    streak >= 1 -> MasteryLevel.Learning
    wrongCount <= 0 -> MasteryLevel.New
    else -> MasteryLevel.Learning
}

/**
 * 各等级对应的复习间隔（天）。
 *
 * [MasteryLevel.New] 为 0 —— 新题应当**立即**进入复习队列，不是"明天再说"。
 */
fun reviewIntervalDays(level: MasteryLevel): Int = when (level) {
    MasteryLevel.New -> 0
    MasteryLevel.Learning -> 1
    MasteryLevel.Familiar -> 3
    MasteryLevel.Mastered -> 7
}

/** 一天的毫秒数。 */
private const val DAY_MILLIS: Long = 24L * 60L * 60L * 1000L

/**
 * 下次到期时刻（毫秒墙钟）。`< = 0` 的 [lastAtMillis] 视为"从未复习过" → 立即到期。
 */
fun dueAt(level: MasteryLevel, lastAtMillis: Long): Long {
    if (lastAtMillis <= 0L) return 0L
    return lastAtMillis + reviewIntervalDays(level) * DAY_MILLIS
}

/**
 * 是否到期。
 *
 * 约定：`dueAtMillis <= 0` 表示"从未排期" → **恒为到期**（老数据/新入册都走这条，
 * 保证它们不会被永久埋在队列里）。否则 `nowMillis >= dueAtMillis` 即到期（**恰好在
 * 到期时刻也算到期**，不留一秒的缝）。
 */
fun isDue(dueAtMillis: Long, nowMillis: Long): Boolean {
    if (dueAtMillis <= 0L) return true
    return nowMillis >= dueAtMillis
}

/** 一组条目的到期数（`items` 每项是"该题的 due_at"）。 */
fun dueCount(items: List<Long>, nowMillis: Long): Int =
    items.count { isDue(it, nowMillis) }
