package com.hnnujw.course.review

import org.json.JSONArray
import org.json.JSONObject

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
    val startedAt: Long = 0L,
    /**
     * 背题模式：**直接看答案与解析**，不要求作答、**不计入任何统计**。
     *
     * 与"练习"的区别是根本性的：练习是"先答 → 再判 → 落库"，背题是"直接看"。
     * 所以背题时 [recordsAnswers] 为 false，界面不得调用 `recordAnswer` ——
     * 否则背一遍就会把题刷成"已掌握"、把错题洗白，统计全失真（本项目明确守这条：
     * **背题不计入任何统计**）。
     */
    val memorize: Boolean = false,
    /**
     * 选项乱序：**只改显示顺序**，判分、落库、错题本、答题卡全部照旧走原始字母。
     *
     * 映射规则见 [displayOptions]：显示层用重新编号的 A/B/C…，作答时立刻翻回原始字母，
     * 所以本类与数据库一行都不用改。
     */
    val shuffleOptions: Boolean = false,
    /**
     * 本轮会话的乱序种子。**每轮开练都必须给一个新值**（如 `System.currentTimeMillis()`），
     * 否则同一道题每轮都是同一个排列，等于没打乱。
     */
    val shuffleSeed: Long = 0L
) {
    /** 每题的用户作答。key 仍用 bankId::qid 作复合键，跟数据库主键一致。 */
    private val answers = LinkedHashMap<String, String>()

    /**
     * 已判过分的题（点过「确认答案」，或交卷时被 [judgePending] 补判）。
     *
     * ⚠️ 「选了答案」与「判过分」是**两件事**：前者只是把字母记进 [answers]，后者才会
     * 出现在正确率里。这个区分本身是对的（界面上要能画"选了但还没揭晓"的中间态），
     * 但它**绝不能变成"这题算不算数"的开关** —— 交卷时由 [judgePending] 兜底。
     */
    private val judged = LinkedHashSet<String>()

    val total: Int get() = questions.size

    val answeredCount: Int get() = answers.size
    val judgedCount: Int get() = judged.size
    val isEmpty: Boolean get() = questions.isEmpty()
    val isFinished: Boolean get() = questions.isNotEmpty() && judgedCount == questions.size

    /**
     * 本次会话是否应当把作答落库（records / 错题本 / 每日统计）。
     *
     * 背题模式恒为 false —— 抽成属性而不是让每个调用点自己写 `!memorize`，
     * 是为了让"背题不计入统计"这条不变量**只有一个判定处**、且可被单测钉住。
     */
    val recordsAnswers: Boolean get() = !memorize

    fun indexOf(question: ReviewQuestion): Int = questions.indexOfFirst { same(it, question) }

    private fun same(a: ReviewQuestion, b: ReviewQuestion) =
        a.bankId == b.bankId && a.id == b.id

    // 🔴 复合键必须是 bankId::qid 两个纯标识符。历史版本写成 "$q.id"：$q 把整题
    // toString()（题干+选项+解析全文）插进了 key，.id 只是字面文本 —— 作答快照
    // 因此膨胀几十倍，大题库 rememberSaveable 有超 Bundle 上限被系统杀掉的风险。
    // q.id 必须用 ${} 包住，别依赖 $ 的"只吃一段标识符"规则。
    private fun key(q: ReviewQuestion) = "${q.bankId}::${q.id}"

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

    /**
     * 选完选项后**是否应当自动跳到下一题**（2026-10 用户要求「开启自动下一题的按钮」）。
     *
     * 三道闸门，缺一不可：
     *  - [enabled] —— 用户把开关打开了吗。**默认关**：开着就看不到这道题的解析与对错，
     *    那是"刷题"的用法而不是"学题"的用法，不该替用户默认打开。
     *  - **多选题不跳**。多选题要选好几项才完整，程序无从判断"选完了没有" ——
     *    按点击次数猜，会把只想选一项的用户弹走。多选题仍走「确认答案」或底部「下一题」。
     *  - **背题模式不跳**。背题是直接看答案、选项根本不可点，这里再守一道，
     *    免得以后有人把自动跳题接到背题上。
     *
     * ⚠️ 它**只管跳不跳，不管判分**：跳过的题作答照旧留在 [answers] 里，交卷时由
     * [judgePending] 统一判对错。抽成函数是为了让"多选题不跳"这条能单测 ——
     * 漏了它，界面看起来是"开关没生效"，用户只会以为程序坏了。
     */
    fun canAutoAdvance(q: ReviewQuestion, enabled: Boolean): Boolean =
        enabled && !memorize && !q.isMulti

    /**
     * **交卷时**把"选了答案但没点确认答案"的题一并判分，返回本次新判分的题（按出题顺序）。
     *
     * 2026-10 用户要求：「若用户只选择选项之后提交要判断对错，不要只是在该题做完后点
     * 确认答案后给出对错」。也就是说 ——「确认答案」的语义只是**现在揭晓解析**，
     * 它不是"这题算不算数"的开关。用户选了 A 就点了「下一题」往下走，交卷时这题必须
     * 判对错；否则总结页会把它报成「未作答」，用户明明选了却看到未作答，正确率也是假的。
     *
     * 三条边界：
     *  - **只补判"有作答"的题**。从头到尾没碰过的题保持未作答 —— 没答就是没答，
     *    编一个"错"出来比留白更糟（限时考试到点交卷会留下大片这种题）。
     *  - **已经判过的不动**。用户点过「确认答案」的题可能有后续的「再想一下」，
     *    重复判分会让撤回失效。
     *  - **背题模式恒返回空**。背题不判分、不落库，这条不变量在 [recordsAnswers] 之外
     *    再守一道，免得以后有人把交卷入口接到背题上。
     *
     * 返回列表是给调用方**落库**用的（`records` / 错题本）：判分即落库，补判的这批
     * 也不能漏，否则交卷后正确率变了、错题本却没记住。
     */
    fun judgePending(): List<ReviewQuestion> {
        if (memorize) return emptyList()
        val newly = mutableListOf<ReviewQuestion>()
        for (q in questions) {
            val k = key(q)
            if (k in judged) continue
            if (answers[k].isNullOrBlank()) continue
            judged.add(k)
            newly += q
        }
        return newly
    }

    fun clear() {
        answers.clear()
        judged.clear()
    }

    /**
     * 把作答与判分状态导出成一个字符串（JSON），供 `rememberSaveable` 保存 ——
     * 进程被杀后能原样恢复，用户不必从第一题重来。
     *
     * **只导作答与判分**，不导题目本身：题目由 [ReviewDatabase.pickQuestions] 用同一组
     * 参数按**确定性顺序**重抽（顺序模式走 `ORDER BY rowid`，随机/错题优先走 qid 哈希，
     * 都不依赖时间），所以重建出来的题目列表与原来逐项一致。
     *
     * 用 JSON 而不是自定义分隔符：题目的 `bankId::qid` 复合键来自题库数据，任何
     * "选一个不会出现的分隔符"都是在赌，JSON 的转义是确定的。
     */
    fun exportState(): String {
        val answerArr = JSONArray()
        answers.forEach { (k, v) -> answerArr.put(JSONObject().put(KEY, k).put(ANS, v)) }
        val judgedArr = JSONArray()
        judged.forEach { judgedArr.put(it) }
        return JSONObject().put(ANSWERS, answerArr).put(JUDGED, judgedArr).toString()
    }

    /**
     * 把 [exportState] 的结果灌回本会话。
     *
     * 只在**新会话还没有任何作答**时调用（见 PracticeScreen 的恢复逻辑）——
     * 否则会把用户这一轮已经做的题覆盖掉。
     */
    fun restoreState(raw: String) {
        if (raw.isBlank()) return
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return
        answers.clear()
        judged.clear()
        root.optJSONArray(ANSWERS)?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val k = o.optString(KEY)
                val a = o.optString(ANS)
                if (k.isNotEmpty() && a.isNotEmpty()) answers[k] = a
            }
        }
        root.optJSONArray(JUDGED)?.let { arr ->
            for (i in 0 until arr.length()) {
                val k = arr.optString(i)
                if (k.isNotEmpty()) judged.add(k)
            }
        }
    }

    /** 正确率：按已判分的题算。没判分时返回 0。 */
    val accuracy: Float
        get() = if (judgedCount == 0) 0f else {
            questions.count { key(it) in judged && isCorrect(it) }.toFloat() / judgedCount
        }

    /**
     * 本轮的汇总数字。
     *
     * 正常交卷与**到点自动交卷走的是同一个函数**：调用方（`PracticeScreen.submit`）先
     * [judgePending] 把"选了没确认"的题补判，再取这里的数字。所以"超时交卷"不需要
     * 单独一套口径 —— 从前那个 `resultForTimeout()` 只是 `result()` 的别名，
     * 在补判逻辑落地后它彻底没有存在意义了，已删除。
     *
     * `unjudged = total - judgedCount`：从头到尾没碰过的题计为未作答，**不算错**，
     * 正确率的分母仍是已判分数（限时考试到点会留下大片这种题）。
     */
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

    /** [exportState] / [restoreState] 的 JSON 键名，集中一处避免写错对不上。 */
    private companion object {
        const val ANSWERS = "answers"
        const val JUDGED = "judged"
        const val KEY = "k"
        const val ANS = "a"
    }
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
