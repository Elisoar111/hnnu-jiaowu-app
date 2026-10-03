package com.hnnujw.course.review

/**
 * 错题本的三态掌握模型。
 *
 * 此前错题本只有一个 `mastered` 布尔：要么"还在错题本里"，要么"已掌握"。这丢掉了
 * 一个很关键的中段信息 —— **刚入册的题**（只错过一次，还在复习中）和**反复错的题**
 * （错两次以上，属于顽固错题）在界面上长得一模一样，用户没法优先处理后者。
 *
 * 因此拆成三态，但**刻意不改动既有的掌握判据**：
 *  - `mastered` 仍由 [ReviewDatabase.MASTER_STREAK]（连续答对 3 次）决定，**不加也不减**；
 *  - 三态里的 `未掌握 / 复习中` 是**展示层**对 `wrong_count` 的再切分，不写库、不迁移数据。
 *
 * 这样升级是**纯增量**的：老用户的 `wrong_book` 一行都不用动，状态由已有两列现算。
 */

/** 错题本的三种状态。 */
enum class WrongStatus(val label: String) {
    /** 刚入册 / 连对中但还没到「已掌握」，错误次数只有 1 次。 */
    Reviewing("复习中"),

    /** 错误 2 次及以上的顽固错题，应当优先重练。 */
    NotMastered("未掌握"),

    /** 连续答对 [ReviewDatabase.MASTER_STREAK] 次，已掌握（再答错会掉回复习中）。 */
    Mastered("已掌握")
}

/** 错误次数达到这个值即记作「未掌握」。 */
const val NOT_MASTERED_THRESHOLD: Int = 2

/**
 * 由库里已有的两列现算三态。
 *
 * **纯函数、不查库**：`mastered` 与 `wrongCount` 都由调用方从 `wrong_book` 读出来传进去，
 * 这样状态分类能直接单测，也不必为"展示三态"多写一条 SQL。
 *
 * 判定顺序：**已掌握优先** —— 一道题即使错过很多次，只要连对到 [ReviewDatabase.MASTER_STREAK]
 * 次就是已掌握（否则会出现"已掌握但显示未掌握"的自相矛盾）。
 */
fun wrongStatus(mastered: Boolean, wrongCount: Int): WrongStatus = when {
    mastered -> WrongStatus.Mastered
    wrongCount >= NOT_MASTERED_THRESHOLD -> WrongStatus.NotMastered
    else -> WrongStatus.Reviewing
}

/** 错题本列表的筛选口径。 */
enum class WrongStatusFilter(val label: String) {
    /** 还没掌握的（复习中 + 未掌握）—— 默认口径，等于从前的"未掌握"列表。 */
    Active("待复习"),
    Reviewing("复习中"),
    NotMastered("未掌握"),
    Mastered("已掌握"),
    All("全部");

    /** 是否把某个状态算作"待复习"。已掌握不算。 */
    fun accepts(status: WrongStatus): Boolean = when (this) {
        Active -> status != WrongStatus.Mastered
        Reviewing -> status == WrongStatus.Reviewing
        NotMastered -> status == WrongStatus.NotMastered
        Mastered -> status == WrongStatus.Mastered
        All -> true
    }

    companion object {
        /**
         * 从持久化的名字还原，认不出来就回默认的 [Active]。
         *
         * 用名字而不是下标持久化：以后往枚举中间插值也不会串筛选条件。
         */
        fun fromName(name: String?): WrongStatusFilter =
            entries.firstOrNull { it.name == name } ?: Active
    }
}

/** 错题本排序口径。 */
enum class WrongSort(val label: String) {
    LastWrong("最近答错"),
    WrongCount("错误最多"),
    Status("按掌握状态");

    companion object {
        fun fromName(name: String?): WrongSort =
            entries.firstOrNull { it.name == name } ?: LastWrong
    }
}

/**
 * 状态在"按掌握状态"排序时的次序：越该先练的越靠前。
 *
 * 单独抽出来是为了让排序键可测 —— 直接写 `compareBy { it.status.ordinal }` 会把
 * 顺序**绑死在枚举声明顺序**上，以后往枚举里插一个值，排序就悄悄变了。
 */
fun wrongStatusOrder(status: WrongStatus): Int = when (status) {
    WrongStatus.Reviewing -> 0
    WrongStatus.NotMastered -> 1
    WrongStatus.Mastered -> 2
}
