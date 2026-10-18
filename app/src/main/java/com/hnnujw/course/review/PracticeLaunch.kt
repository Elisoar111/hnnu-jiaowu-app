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

    /**
     * 编码成一个字符串（进 `rememberSaveable` / SharedPreferences）。
     *
     * 字段分隔用 `\u001E`、列表元素用 `\u001F`：都是 ASCII 控制字符，中文章节名里
     * 不可能出现（题库数据里的引号、顿号、全角括号都不冲突）。
     */
    fun encode(): String = listOf(
        bankId.orEmpty(),
        chapters.joinToString(ITEM_SEP.toString()),
        mode.name,
        onlyWrong.toString(),
        onlyFavorite.toString(),
        dueOnly.toString(),
        durationSeconds.toString(),
        memorize.toString()
    ).joinToString(FIELD_SEP.toString())

    companion object {
        private const val FIELD_SEP = '\u001E'
        private const val ITEM_SEP = '\u001F'

        /**
         * 解析 [encode] 的产物；解析失败返回 null。
         *
         * 字段数必须**严格相等**：格式换代后（如还带着已删除的斩题字段的旧串）直接拒收，
         * 宁可让用户重新开练，也不能错位读出一个错误的练习模式。
         */
        fun decode(raw: String): PracticeLaunch? {
            if (raw.isBlank()) return null
            val f = raw.split(FIELD_SEP)
            if (f.size != 8) return null
            return PracticeLaunch(
                bankId = f[0].ifBlank { null },
                chapters = if (f[1].isEmpty()) emptyList() else f[1].split(ITEM_SEP),
                mode = runCatching { ReviewMode.valueOf(f[2]) }.getOrDefault(ReviewMode.SEQUENTIAL),
                onlyWrong = f[3].toBooleanStrictOrNull() ?: false,
                onlyFavorite = f[4].toBooleanStrictOrNull() ?: false,
                dueOnly = f[5].toBooleanStrictOrNull() ?: false,
                durationSeconds = f[6].toIntOrNull() ?: 0,
                memorize = f[7].toBooleanStrictOrNull() ?: false
            )
        }
    }
}

/**
 * 「继续上次练习」的本地存档。
 *
 * 进程**被杀**时进度由 `rememberSaveable` 兜住（系统会恢复 saved state）；
 * 但**主动退出**练习页时那条链路会被清掉（退出意味着作废这一轮）。
 * 这里补的是第三种情况：用户退回首页、App 还活着，回来时能接着刷 ——
 * 题库练习是典型的"碎片时间做几题"，每次退出去都从第一题重来非常劝退。
 *
 * 只存"重建会话所需的最小信息 + 作答快照"，题目本身仍由
 * `ReviewDatabase.pickQuestions` 用同一组参数确定性重抽（见 PracticeSession 的说明）。
 */
object PracticeResume {

    private const val PREFS = "review_practice_resume"
    private const val KEY_LAUNCH = "launch"
    private const val KEY_STARTED_AT = "started_at"
    private const val KEY_SEED = "seed"
    private const val KEY_TITLE = "title"
    private const val KEY_INDEX = "index"
    private const val KEY_SNAPSHOT = "snapshot"

    /** 一份可恢复的练习进度。 */
    data class Snapshot(
        val launch: PracticeLaunch,
        val startedAt: Long,
        val seed: Long,
        val title: String,
        val index: Int,
        val answers: String
    )

    fun save(context: android.content.Context, snapshot: Snapshot) {
        // 空快照没有恢复价值（刚进来还没答题就走了）。
        if (snapshot.answers.isBlank()) return
        context.applicationContext.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAUNCH, snapshot.launch.encode())
            .putLong(KEY_STARTED_AT, snapshot.startedAt)
            .putLong(KEY_SEED, snapshot.seed)
            .putString(KEY_TITLE, snapshot.title)
            .putInt(KEY_INDEX, snapshot.index)
            .putString(KEY_SNAPSHOT, snapshot.answers)
            .apply()
    }

    fun load(context: android.content.Context): Snapshot? {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_LAUNCH, null) ?: return null
        val launch = PracticeLaunch.decode(raw) ?: return null
        val answers = prefs.getString(KEY_SNAPSHOT, null).orEmpty()
        if (answers.isBlank()) return null
        return Snapshot(
            launch = launch,
            startedAt = prefs.getLong(KEY_STARTED_AT, 0L),
            seed = prefs.getLong(KEY_SEED, 0L),
            title = prefs.getString(KEY_TITLE, null).orEmpty().ifBlank { launch.title() },
            index = prefs.getInt(KEY_INDEX, 0),
            answers = answers
        )
    }

    fun clear(context: android.content.Context) {
        context.applicationContext.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .edit().clear().apply()
    }
}
