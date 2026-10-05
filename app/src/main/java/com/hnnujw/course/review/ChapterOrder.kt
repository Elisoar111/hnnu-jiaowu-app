package com.hnnujw.course.review

/**
 * 章节名的**自然序**排序。
 *
 * 为什么需要这个文件：章节名里的编号是**中文数字**。内置题库的章节实际长这样 ——
 *
 *     导论 / 第一章 毛泽东思想及其历史地位 / 第二章 … / 第十章
 *     绪论 / 第一章 / 第二章 / … / 第十章
 *
 * 而 SQLite 的 `ORDER BY chapter` 是逐字节比较，于是「第一章」按
 * 一(U+4E00)、七(U+4E03)、三(U+4E09)、九(U+4E5D)、二(U+4E8C)… 的顺序排出来，
 * 界面上就是「第一章、第七章、第三章、第九章、第二章、第五章、第八章、第六章、
 * 第四章」这种完全错乱的顺序（2026-10 用户报「章节顺序问题」）。
 *
 * 只把「中文数字」换成阿拉伯数字还不够 —— 一/七/三/九/二 的**字面量**顺序本身就乱，
 * 必须真的解析出数值。本文件做三件事，全部是纯函数：
 *  1. [chapterNumber]：从章节名里取出编号（中文数字与阿拉伯数字都认）；
 *  2. [chapterSortKey]：把编号与名称包成一个可比较的键；
 *  3. [sortChapters] / [sortChaptersWithCount]：给 [ReviewDatabase] 与界面直接用的入口。
 *
 * ⚠️ 排序放在 Kotlin 层而不是 SQL 里：SQLite 自定义排序规则要注册 collation，
 * 且解析逻辑会散进 SQL 字符串、无法单测。数据量是"一个题库几十章"，Kotlin 排序无成本。
 */

/** 阿拉伯数字（含 `1.1` 这种小节号）。 */
private val ARABIC_NUMBER = Regex("""\d+(?:\.\d+)*""")

/** 中文数字（含 `两`：口语里「第两章」不常见，但导入的题库里出现过）。 */
private val CHINESE_NUMBER = Regex("""[零一二三四五六七八九十百千两]+""")

private val CHINESE_DIGITS = mapOf(
    '零' to 0, '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4,
    '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9
)

private val CHINESE_UNITS = mapOf('十' to 10, '百' to 100, '千' to 1000)

/**
 * 编号后面的**量词**。数字后面除了标点/空白，只允许跟这些词才算编号。
 *
 * 这是「串首数字」规则的必要补丁。没有它时：
 *  - 「三个代表重要思想」因为**以「三」开头**被判成第 3 章；
 *  - 「一元函数微分学」「一般现在时」被判成第 1 章。
 * 这三种都不是编号，是词的一部分。真实数据里内置题库没有踩到（见下方说明），
 * 但用户导入的题库（英语语法、高数、历史）很容易出现。
 */
private val NUMBER_UNIT_WORDS =
    listOf("章", "节", "讲", "课", "篇", "卷", "册", "部", "题", "次", "单元", "部分", "模块")

/**
 * 章节排序键。
 *
 * [number] 为 null 表示"章节名里没有编号"—— 中文教材里这类章节（绪论 / 导论 /
 * 前言 / 概述）都是**前置内容**，排在第一章之前，所以 null 当负无穷处理。
 *
 * 权衡说明：这会把「附录」这类无编号的**后置**内容也排到最前。真出现时改名即可
 * （例如「附录一」），比引入一份"前置章节名白名单"更可预测 —— 白名单只覆盖中文教材，
 * 对导入的英文题库毫无帮助，还会让排序规则变成"看名字猜位置"。
 */
data class ChapterSortKey(val number: Double?, val name: String) : Comparable<ChapterSortKey> {
    override fun compareTo(other: ChapterSortKey): Int {
        val a = number ?: Double.NEGATIVE_INFINITY
        val b = other.number ?: Double.NEGATIVE_INFINITY
        val byNumber = a.compareTo(b)
        // 编号相同（如「第七章 “三个代表”重要思想」与「第七章 科学发展观」）时按名称定序，
        // 保证同一份数据每次渲染的顺序完全一致。
        return if (byNumber != 0) byNumber else name.compareTo(other.name)
    }
}

/**
 * 从章节名里解析编号；解析不出返回 null。
 *
 * 判定分两步，**两步都过**才算编号：
 *  1. **位置**：数字在串首，或紧跟 `第` 之后（允许中间有空格）；
 *  2. **终止符**：数字后面是串尾、标点/空白/符号，或编号量词（`章`/`节`/`讲`/`部分`…）。
 *
 * 第 2 步是必需的，只靠位置会误判：「三个代表重要思想」以「三」开头、
 * 「一元函数微分学」「一般现在时」以「一」开头，都会被当成第 3 / 第 1 章。
 * 真实数据（2026-10 核对 `app/src/main/assets/review` 下的题库 JSON）里内置题库的
 * 编号章节全部是 `第X章 …` 形式，前置章节是「导论 / 绪论 / 马克思主义基本原理」，
 * 因此加上终止符要求**不影响任何现有数据**，只挡掉误判。
 */
fun chapterNumber(name: String): Double? {
    val text = name.trim()
    if (text.isEmpty()) return null
    // 阿拉伯数字优先：`第1章` 与 `第一章` 同时出现时，前者更明确。
    firstAtNumberPosition(text, ARABIC_NUMBER)?.let { match ->
        match.value.toDoubleOrNull()?.let { return it }
    }
    firstAtNumberPosition(text, CHINESE_NUMBER)?.let { match ->
        return parseChineseNumeral(match.value)
    }
    return null
}

/** 该匹配是否落在"数字位置"上（串首，或紧跟 `第` 之后，允许中间有空格）。 */
private fun isNumberPosition(text: String, start: Int): Boolean {
    if (start == 0) return true
    return text.substring(0, start).trimEnd().endsWith("第")
}

/**
 * 数字后面是否跟着「终止符」。
 *
 * 终止符 = 串尾 / 非字母数字字符（空白、标点、符号）/ 编号量词。
 * 反例：「三个代表」的「三」后面是「个」——是汉字、又不是量词 ⇒ 不是编号。
 */
private fun hasNumberTerminator(text: String, endExclusive: Int): Boolean {
    val rest = text.substring(endExclusive)
    if (rest.isEmpty()) return true
    // Character.isLetterOrDigit 对汉字返回 true，所以这一步只放行空白/标点/符号。
    if (!Character.isLetterOrDigit(rest[0])) return true
    return NUMBER_UNIT_WORDS.any { rest.startsWith(it) }
}

/** 位置与终止符都满足才算编号。 */
private fun isNumberAt(text: String, match: MatchResult): Boolean =
    isNumberPosition(text, match.range.first) &&
        hasNumberTerminator(text, match.range.last + 1)

private fun firstAtNumberPosition(text: String, regex: Regex): MatchResult? =
    regex.findAll(text).firstOrNull { isNumberAt(text, it) }

/**
 * 解析中文数字串（`十` → 10、`十一` → 11、`二十三` → 23、`一百零五` → 105）。
 *
 * 含非数字字符、或结构无法解析（如 `十十`）时返回 null —— 宁可退化成"按名称排序"，
 * 也不要猜出一个错编号把章节挪到错误的位置。
 */
private fun parseChineseNumeral(text: String): Double? {
    var total = 0
    var current = 0
    var seen = false
    for (ch in text) {
        val digit = CHINESE_DIGITS[ch]
        if (digit != null) {
            current = digit
            seen = true
            continue
        }
        val unit = CHINESE_UNITS[ch] ?: return null
        seen = true
        // 「十」单独出现时是 10（省略了前面的「一」）；「二十三」里「十」前面有 2。
        total += (if (current == 0 && unit == 10) 1 else current) * unit
        current = 0
    }
    if (!seen) return null
    return (total + current).toDouble()
}

/** 章节名的排序键。 */
fun chapterSortKey(name: String): ChapterSortKey = ChapterSortKey(chapterNumber(name), name)

/** 章节名列表按自然序排序（不改原列表）。 */
fun sortChapters(chapters: List<String>): List<String> =
    chapters.sortedWith(compareBy(::chapterSortKey))

/** 「章节 → 题数」列表按章节自然序排序（不改原列表）。 */
fun sortChaptersWithCount(chapters: List<Pair<String, Int>>): List<Pair<String, Int>> =
    chapters.sortedWith(compareBy { chapterSortKey(it.first) })
