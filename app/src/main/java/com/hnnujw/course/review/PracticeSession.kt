package com.hnnujw.course.review

/**
 * 一次练习会话的纯逻辑：出题顺序、逐题判分、进度、结果汇总。
 *
 * 刻意不碰数据库与 Compose —— 会话是纯内存状态机，好处有三个：
 *  1. 判分口径（多选必须全对才算对）能直接单测，不用起 Activity；
 *  2. 界面只管渲染 [PracticeState]，不会把判分逻辑长在 Composable 里；
 *  3. 旋转屏幕/切后台时只要保住这个对象，进度就不会丢。
 */
class PracticeSession(
    val questions: List<ReviewQuestion>,
    /** 答完一题才切下一题（顺序练习）；false = 全部题目同时铺开（自测模式）。 */
    val oneByOne: Boolean = true,
    /**
     * 限时考试配置，默认 [ExamTiming]`(0)` = 不限时（普通练习行为不变）。
     *
     * ⚠️ 本参数**带默认值**：新增调用点时不会因为漏传而报编译错。凡是"限时考试"
     * 的场景，务必在创建会话处**显式回传** `timing`（本项目踩过带默认值参数的坑）。
     */
    val timing: ExamTiming = ExamTiming(0),
    /**
     * 会话开始时刻（毫秒墙钟），用于按真实时间算已用时长。
     *
     * 默认 `0L` 表示"未设定"——此时 [isExpired] 视为未开始、恒 false，普通练习
     * 不需要关心它。限时考试必须在开练时传入 `System.currentTimeMillis()`。
     */
    val startedAt: Long = 0L
) {
    /** 每题的用户作答。key 仍用 bankId::qid 作复合键，跟数据库主键一致。 */
    private val answers = LinkedHashMap<String, String>()

    /** 已判过分的题（点过「确认」之后才算分）。 */
    private val judged = LinkedHashSet<String>()

    val total: Int get() = questions.size

    val answeredCount: Int get() = answers.size
    val judgedCount: Int get() = judged.size
    val isEmpty: Boolean get() = questions.isEmpty()
    val isFinished: Boolean get() = questions.isNotEmpty() && judgedCount == questions.size

    fun indexOf(question: ReviewQuestion): Int = questions.indexOfFirst { same(it, question) }

    private fun same(a: ReviewQuestion, b: ReviewQuestion) =
        a.bankId == b.bankId && a.id == b.id

    private fun key(q: ReviewQuestion) = "${q.bankId}::$q.id"

    fun answerOf(q: ReviewQuestion): String = answers[key(q)].orEmpty()

    fun isJudged(q: ReviewQuestion): Boolean = key(q) in judged

    fun isCorrect(q: ReviewQuestion): Boolean = judgeAnswer(q, answerOf(q))

    /**
     * 记录一次选择/取消选择。
     *
     * 单选：直接覆盖。多选：切换该字母。
     * 已判过分的题不允许再改答案（要改得先撤回判定），否则正确率会被改到中途的数字上。
     */
    fun select(q: ReviewQuestion, letter: String): Boolean {
        if (isJudged(q)) return false
        val k = key(q)
        val current = answers[k].orEmpty()
        val next = when {
            q.isMulti -> {
                // current 是 "ABD" 这样的串，要按**字符**取出再装回 Set<String>。
                // 直接 current.toSet() 在 Kotlin 里会推成 Set<Char>，
                // 空串（"".toSet()）更是直接退化成空 Set<Char>，后面 add(String) 就编不过。
                val set = LinkedHashSet<String>()
                current.forEach { if (it != ' ') set.add(it.toString()) }
                if (!set.add(letter)) set.remove(letter)
                set.sorted().joinToString("")
            }
            else -> letter
        }
        answers[k] = next
        return true
    }

    /** 确认判分。返回该题是否正确。 */
    fun judge(q: ReviewQuestion): Boolean {
        val k = key(q)
        if (k in judged) return isCorrect(q)
        if (answers[k].isNullOrBlank()) return false
        judged.add(k)
        return isCorrect(q)
    }

    /** 撤回判定，让这题能重新作答（用户点"再想一下"）。 */
    fun unjudge(q: ReviewQuestion) {
        judged.remove(key(q))
    }

    fun clear() {
        answers.clear()
        judged.clear()
    }

    /** 正确率：按已判分的题算。没判分时返回 0。 */
    val accuracy: Float
        get() = if (judgedCount == 0) 0f else {
            questions.count { key(it) in judged && isCorrect(it) }.toFloat() / judgedCount
        }

    fun result(): PracticeResult = PracticeResult(
        total = total,
        answered = answeredCount,
        correct = questions.count { key(it) in judged && isCorrect(it) },
        wrong = questions.count { key(it) in judged && !isCorrect(it) },
        unjudged = total - judgedCount
    )

    /**
     * 到点没到点（按墙钟判断）。
     *
     * 不限时恒 false；`startedAt == 0L`（未设定开始时刻）也恒 false，避免把"没记
     * 开始时间"误判成"立刻过期"。到点后返回 true 由调用方触发自动交卷。
     *
     * 用**真实时间差**而非累加计数：应用被切到后台、系统把 tick 挂起时，累加计数
     * 会停在原地（回来后还显示剩很多），而墙钟差值不会漂。
     */
    fun isExpired(nowMillis: Long): Boolean {
        if (!timing.isTimed || startedAt <= 0L) return false
        val elapsedMillis = nowMillis - startedAt
        if (elapsedMillis < 0L) return false
        return elapsedMillis >= timing.durationSeconds * 1000L
    }

    /**
     * 超时交卷的结果。
     *
     * 与"正常做完"共用 [result]：**已判分的题照算，没判分的题自然计为未作答**
     * （`unjudged = total - judgedCount`）。这样超时交卷不会把没答的题算成答错，
     * 正确率的分母仍是"已判分数"。
     */
    fun resultForTimeout(): PracticeResult = result()
}

data class PracticeResult(
    val total: Int,
    val answered: Int,
    val correct: Int,
    val wrong: Int,
    val unjudged: Int
) {
    val accuracy: Float get() = if (answered == 0) 0f else correct.toFloat() / answered
}

/**
 * 判分口径：**多选必须完全一致才算对**（少选、多选、错选一律算错）。
 *
 * 为什么这么严：多选题的考查点就是"选全且选准"，宽松判会让用户误以为掌握了。
 * 抽成纯函数是为了能单测，也为了以后想改成"部分得分"时只改这一处。
 */
fun judgeAnswer(question: ReviewQuestion, userAnswer: String): Boolean {
    val a = normalizeAnswer(userAnswer)
    if (a.isEmpty()) return false
    // 两边都排序去重后再比：用户按 D→B→A 的顺序点选，答案本身就是 "ABD"，
    // 不排序会把"选全了"误判成错；去重则能挡住 "AAB" 这类脏输入。
    // 不能用 String.toSet()（推成 Set<Char> 后和另一个 Set 比较虽可行，
    // 但重复字母会被静默吞掉），所以显式走 toSortedSet().joinToString()。
    return a.toSortedSet().joinToString("") == normalizeAnswer(question.answer).toSortedSet().joinToString("")
}
