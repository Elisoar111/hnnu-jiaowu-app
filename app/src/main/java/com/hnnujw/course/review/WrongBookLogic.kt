package com.hnnujw.course.review

/**
 * 错题本列表的筛选 / 排序 / 汇总。**纯函数，不碰 Android 与 Compose**，可直接单测。
 *
 * 为什么抽出来：这三个动作的判据一旦写进 Composable，就只能靠上真机一个个点着看
 * （"筛到「未掌握」时到底该不该包含已掌握的题""到期数算不算已掌握的题"）。
 * 抽出来之后，边界由 `WrongBookLogicTest` 逐条钉死。
 *
 * 三条口径（刻意写死，别在界面里另立一套）：
 *  1. 筛选**只认 [WrongStatusFilter.accepts]**，界面不自己判 `mastered`；
 *  2. 排序**不依赖枚举声明顺序** —— 「按掌握状态」用 [wrongStatusOrder] 显式排序键；
 *  3. 「到期」判据复用 [isDue]（`dueAt <= 0` 恒为到期），与「今日待复习」入口同一套。
 */

/** 按状态筛选。 */
fun filterWrongEntries(
    entries: List<ReviewDatabase.WrongBookEntry>,
    filter: WrongStatusFilter
): List<ReviewDatabase.WrongBookEntry> = entries.filter { filter.accepts(it.status) }

/**
 * 按口径排序。同键时用「错误更多 → 最近答错」兜底，保证顺序稳定 ——
 * 否则同一批数据每次进页面顺序都可能变，用户会以为列表在乱跳。
 */
fun sortWrongEntries(
    entries: List<ReviewDatabase.WrongBookEntry>,
    sort: WrongSort
): List<ReviewDatabase.WrongBookEntry> = when (sort) {
    WrongSort.LastWrong -> entries.sortedWith(
        compareByDescending<ReviewDatabase.WrongBookEntry> { it.lastWrongAt }
            .thenByDescending { it.wrongCount }
    )
    WrongSort.WrongCount -> entries.sortedWith(
        compareByDescending<ReviewDatabase.WrongBookEntry> { it.wrongCount }
            .thenByDescending { it.lastWrongAt }
    )
    WrongSort.Status -> entries.sortedWith(
        compareBy<ReviewDatabase.WrongBookEntry> { wrongStatusOrder(it.status) }
            .thenByDescending { it.wrongCount }
            .thenByDescending { it.lastWrongAt }
    )
}

/** 错题本的汇总口径（给页头一行字用）。 */
data class WrongBookSummary(
    /** 全部在册错题数（含已掌握）。 */
    val total: Int,
    /** 还没掌握的题数（= 从前的「待复习」口径）。 */
    val active: Int,
    /** 未掌握**且**已到期的题数（= 「今日待复习」）。 */
    val due: Int,
    /** 已掌握的题数。 */
    val mastered: Int
)

/**
 * 汇总。已掌握的题**不计入** [WrongBookSummary.due] —— 到期队列只服务未掌握的题，
 * 与 [ReviewDatabase.dueWrongQuestions] 的 SQL 条件保持一致（那里也带 `mastered = 0`）。
 */
fun summarizeWrongBook(
    entries: List<ReviewDatabase.WrongBookEntry>,
    now: Long = System.currentTimeMillis()
): WrongBookSummary {
    var active = 0
    var due = 0
    var mastered = 0
    for (e in entries) {
        if (e.status == WrongStatus.Mastered) {
            mastered++
        } else {
            active++
            if (isDue(e.dueAt, now)) due++
        }
    }
    return WrongBookSummary(total = entries.size, active = active, due = due, mastered = mastered)
}

/**
 * 到期状态的展示文案。
 *
 * 刻意把「从未排期」和「已到期」说成同一件事（都是"现在就能复习"）—— 老数据
 * `due_at = 0` 在判据上就等价于立即到期，文案上分成两句只会让人以为有区别。
 */
fun dueLabel(dueAtMillis: Long, now: Long = System.currentTimeMillis()): String {
    if (isDue(dueAtMillis, now)) return "现在可复习"
    val days = ((dueAtMillis - now) / (24L * 60L * 60L * 1000L)).coerceAtLeast(1L)
    return "${days} 天后复习"
}
