package com.hnnujw.course.review

/**
 * 题库详情页「练习模式」的四档 —— **界面上的说法**。
 *
 * 为什么要单独一个枚举，而不是直接用 [ReviewMode]：
 *
 *  1. 界面上的"练习模式"和抽题模式**不是一对一**的。「考试」与「背题模式」都是
 *     按顺序抽题（`ReviewMode.SEQUENTIAL`），区别在"限不限时""判不判分"，
 *     那是 [PracticeLaunch] 上的另外两个字段。硬套 [ReviewMode] 会让"考试"
 *     没有自己的位置。
 *  2. [ReviewMode] 同时是**落库值**（`ReviewMode.name` 写进记录），
 *     界面文案不该反过来牵动它。
 *
 * 纯数据、无 Android 依赖，可 JVM 单测。
 */
enum class PracticeModeOption(val label: String) {
    SEQUENTIAL("顺序练习"),
    SHUFFLED("乱序练习"),
    EXAM("考试"),
    MEMORIZE("背题模式");

    /** 只有「考试」需要选时长 —— 顺序 / 乱序 / 背题都不限时。 */
    val needsDuration: Boolean get() = this == EXAM

    /**
     * 选中该模式后给用户的一句话说明。
     *
     * 四个模式在界面上只是滚轮里的四行字，光看名字分不清「乱序练习」和
     * 「选项乱序」、也看不出「背题」不判分。这句说明是它们的唯一解释处。
     */
    fun hint(durationMinutes: Int): String = when (this) {
        SEQUENTIAL -> "按题库里的原始顺序逐题练，不限时。"
        SHUFFLED -> "打乱**题目**的出现顺序，不限时。" +
            "（与下面「选项乱序」不同 —— 那个打乱的是同一道题里 A/B/C/D 的排列。）"
        EXAM -> "限时 ${durationLabel(durationMinutes)}，到点自动交卷；未作答的题记为未作答，不算答错。"
        MEMORIZE -> "直接显示答案，不计入统计 —— 不进错题本、也不影响掌握度。"
    }
}

/** 由「练习模式 + 时长」算出的开练参数。 */
data class PracticeParams(
    val mode: ReviewMode,
    val memorize: Boolean,
    val durationSeconds: Int
)

/**
 * 把界面上的模式选择翻译成开练参数。**纯函数**，UI 里只做渲染。
 *
 * 时长只对「考试」生效：其余模式一律 `0`（= 不限时）。这样即便界面上残留了
 * 一个时长值，也不会让"顺序练习"莫名其妙变成限时考试。
 */
fun resolvePracticeParams(mode: PracticeModeOption, durationMinutes: Int): PracticeParams = when (mode) {
    PracticeModeOption.SEQUENTIAL -> PracticeParams(ReviewMode.SEQUENTIAL, memorize = false, durationSeconds = 0)
    PracticeModeOption.SHUFFLED -> PracticeParams(ReviewMode.RANDOM, memorize = false, durationSeconds = 0)
    // 考试 = 按顺序抽题 + 限时。时长非法（<= 0）时退回不限时，至少不会"点了没反应"。
    PracticeModeOption.EXAM -> PracticeParams(
        ReviewMode.SEQUENTIAL,
        memorize = false,
        durationSeconds = (durationMinutes * 60).coerceAtLeast(0)
    )
    PracticeModeOption.MEMORIZE -> PracticeParams(ReviewMode.SEQUENTIAL, memorize = true, durationSeconds = 0)
}
