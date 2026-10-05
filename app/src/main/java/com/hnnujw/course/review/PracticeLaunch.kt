package com.hnnujw.course.review

/**
 * 一次「开练」请求。
 *
 * 开练的参数此前是一条长长的函数签名（bankId / chapter / mode / onlyWrong / onlyFavorite /
 * durationSeconds），已经 6 个位置参数；这一轮又加了「背题」等开关，再往
 * 位置参数上堆就会变成"第 5 个参数到底是哪个布尔"的噩梦 —— 而且位置参数**传错了不报错**。
 *
 * 收成一个具名数据类之后：
 *  - 每个开关都有名字，调用点一眼看得出在开什么；
 *  - 新增开关只改这里 + 实际用到的地方，签名不再膨胀；
 *  - 默认值集中一处，语义（0 秒 = 不限时、非背题 = 练习）不必在每个调用点重复表达。
 *
 * 本类刻意保持**纯数据、无 Android 依赖**，便于单测与后续序列化。
 */
data class PracticeLaunch(
    /** 目标题库；null = 跨题库（例如首页的"错题本 / 收藏"）。 */
    val bankId: String?,
    /**
     * 只练这些章节；**空列表 = 全部章节**（不按章节过滤）。
     *
     * 2026-10 用户要求「多选章节答题」，于是从 `String?` 改成集合 —— 一次能把
     * 「第一章 + 第二章」放一起练，而不是只能一章一章刷。
     * 空集合与原来的 `null` 语义**完全一致**，因此"只选一章"的行为与从前逐字相同。
     */
    val chapters: List<String> = emptyList(),
    val mode: ReviewMode = ReviewMode.SEQUENTIAL,
    /** 只练未掌握的错题。 */
    val onlyWrong: Boolean = false,
    /** 只练收藏。 */
    val onlyFavorite: Boolean = false,
    /** 只练**今日到期**的错题（间隔复习）。与 [onlyWrong] 类似但多一层 `due_at` 过滤。 */
    val dueOnly: Boolean = false,
    /** 限时考试时长（秒）；<= 0 = 不限时（普通练习）。 */
    val durationSeconds: Int = 0,
    /** 背题模式：直接看答案，不计入统计。 */
    val memorize: Boolean = false
) {
    /** 限时考试 = 有时长 且 不是背题（背题本身不计时）。 */
    val isTimedExam: Boolean get() = durationSeconds > 0 && !memorize

    /**
     * 开练时给这一轮起的标题（列表页顶部用）。
     *
     * 章节名要先滤掉空白项：`chapters = listOf("")` 这种脏值会让标题变成空串
     * （上一版判的是 `!chapter.isNullOrBlank()`，换成集合后必须显式再滤一次）。
     */
    fun title(): String {
        if (memorize) return "背题"
        if (onlyWrong) return "错题本"
        if (onlyFavorite) return "收藏"
        if (dueOnly) return "今日待复习"
        val named = chapters.filter { it.isNotBlank() }
        return when {
            // 单章时直接用章节名（与从前逐字一致）；多章点不出具体名字，报数量。
            named.size == 1 -> named.first()
            named.isNotEmpty() -> "已选 ${named.size} 章"
            else -> mode.label
        }
    }
}
