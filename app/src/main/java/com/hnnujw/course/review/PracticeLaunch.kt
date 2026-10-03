package com.hnnujw.course.review

/**
 * 一次「开练」请求。
 *
 * 开练的参数此前是一条长长的函数签名（bankId / chapter / mode / onlyWrong / onlyFavorite /
 * durationSeconds），已经 6 个位置参数；这一轮又加了「背题」「只练斩题本」两项，再往
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
    val chapter: String? = null,
    val mode: ReviewMode = ReviewMode.SEQUENTIAL,
    /** 只练未掌握的错题。 */
    val onlyWrong: Boolean = false,
    /** 只练收藏。 */
    val onlyFavorite: Boolean = false,
    /** 只练已斩的题（从斩题本开练）。与 [excludeSlashed] 语义互斥，本项优先。 */
    val onlySlashed: Boolean = false,
    /** 只练**今日到期**的错题（间隔复习）。与 [onlyWrong] 类似但多一层 `due_at` 过滤。 */
    val dueOnly: Boolean = false,
    /**
     * 是否把已斩题排除在练习之外。默认 true —— 斩题的意思就是"别再让我练它"。
     * 从斩题本开练（[onlySlashed]）时该值无实际作用。
     */
    val excludeSlashed: Boolean = true,
    /** 限时考试时长（秒）；<= 0 = 不限时（普通练习）。 */
    val durationSeconds: Int = 0,
    /** 背题模式：直接看答案，不计入统计。 */
    val memorize: Boolean = false
) {
    /** 限时考试 = 有时长 且 不是背题（背题本身不计时）。 */
    val isTimedExam: Boolean get() = durationSeconds > 0 && !memorize

    /** 开练时给这一轮起的标题（列表页顶部用）。 */
    fun title(): String = when {
        memorize -> "背题"
        onlyWrong -> "错题本"
        onlyFavorite -> "收藏"
        onlySlashed -> "斩题本"
        dueOnly -> "今日待复习"
        !chapter.isNullOrBlank() -> chapter
        else -> mode.label
    }
}
