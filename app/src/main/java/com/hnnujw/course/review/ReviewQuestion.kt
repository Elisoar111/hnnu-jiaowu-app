package com.hnnujw.course.review

import org.json.JSONArray
import org.json.JSONObject

/**
 * 题目模型。字段与 D:\zhiyu（知隅题库）的 [Question] 一一对应，
 * 便于直接复用那边的 questions.json 格式。
 *
 * 隐私红线：题干/选项里可能带用户自己的隐私内容，**禁止**写进日志。
 */
data class ReviewQuestion(
    val id: String,
    /** "single"=单选，"multi"=多选。 */
    val type: String,
    val stem: String,
    val options: List<String>,
    /** 大写字母，如 "A" 或 "ABD"。 */
    val answer: String,
    val chapter: String,
    val explanation: String = "",
    val source: String = "",
    val tags: List<String> = emptyList(),
    /** 所属题库 id（导入时生成；同 id 不同库要靠它区分）。 */
    val bankId: String = "",
    val createdAt: Long = 0L
) {
    val isMulti: Boolean get() = type == TYPE_MULTI
    val isSingle: Boolean get() = type == TYPE_SINGLE

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("type", type)
        put("stem", stem)
        put("options", JSONArray(options))
        put("answer", answer)
        put("chapter", chapter)
        put("explanation", explanation)
        put("source", source)
        put("tags", JSONArray(tags))
    }

    companion object {
        const val TYPE_SINGLE = "single"
        const val TYPE_MULTI = "multi"

        fun fromJson(json: JSONObject): ReviewQuestion = ReviewQuestion(
            id = json.optStringAny("id", "ID", "题号"),
            type = normalizeType(json.optStringAny("type", "题型")) ?: TYPE_SINGLE,
            stem = json.optStringAny("stem", "题干").trim(),
            options = json.optStringListAny("options", "选项", "选项列表"),
            answer = normalizeAnswer(json.optStringAny("answer", "答案")),
            chapter = json.optStringAny("chapter", "章节").ifBlank { "未分类" },
            explanation = json.optStringAny("explanation", "解析"),
            source = json.optStringAny("source", "来源"),
            tags = json.optStringListAny("tags", "标签")
        )
    }
}

/**
 * 归一化答案：只保留 A..H 并大写，例如 "a、c" -> "AC"。
 *
 * 刻意只收 A..H：选项最多 8 个（见 [ReviewQuestion.validate]），多写的字母一律丢掉，
 * 避免题库里混入 "正确答案：ABC（多选）" 这类整句时把脏数据当答案存进去。
 */
fun normalizeAnswer(raw: String): String {
    val sb = StringBuilder()
    for (ch in raw.uppercase()) {
        if (ch in 'A'..'H') sb.append(ch)
    }
    return sb.toString()
}

/** 归一化题型：兼容 single/multi/单选/多选/单项/多项/1/2。 */
fun normalizeType(raw: String): String? {
    val t = raw.trim().lowercase()
    if (t == ReviewQuestion.TYPE_SINGLE || t.contains("单选") || t.contains("单项") || t == "1") {
        return ReviewQuestion.TYPE_SINGLE
    }
    if (t == ReviewQuestion.TYPE_MULTI || t.contains("多选") || t.contains("多项") || t == "2") {
        return ReviewQuestion.TYPE_MULTI
    }
    return null
}

/** 题库级校验，返回错误描述（空列表代表合法）。 */
fun ReviewQuestion.validate(): List<String> {
    val errors = ArrayList<String>()
    if (id.isBlank()) errors.add("id 为空")
    if (stem.isBlank()) errors.add("题干为空")
    if (type != ReviewQuestion.TYPE_SINGLE && type != ReviewQuestion.TYPE_MULTI) {
        errors.add("题型不合法: $type")
    }
    if (options.size < 2) errors.add("选项少于 2 个")
    if (options.any { it.isBlank() }) errors.add("存在空选项")
    if (options.size > 8) errors.add("选项超过 8 个")
    if (answer.isEmpty()) {
        errors.add("答案为空")
    } else {
        answer.forEach { ch ->
            val idx = ch - 'A'
            if (idx < 0 || idx >= options.size) {
                val last = 'A' + options.size - 1
                errors.add("答案 $ch 超出选项范围(A-$last)")
            }
        }
        if (isSingle && answer.length > 1) errors.add("单选题答案包含多个选项: $answer")
        if (isMulti && answer.length < 2) errors.add("多选题答案应至少包含 2 个选项: $answer")
    }
    return errors
}

/** 一份题库的解析结果。[errors] 非空表示有条目被丢弃。 */
data class ParsedBank(
    val name: String,
    val questions: List<ReviewQuestion>,
    val errors: List<String>
) {
    val isValid: Boolean get() = errors.isEmpty()
}

/**
 * 解析 JSON 题库文本。兼容两种结构：
 *  1. `{"version":2,"name":"...","questions":[...]}`
 *  2. 直接的题目数组 `[...]`
 */
fun parseJsonBank(content: String, fallbackName: String = "导入题库"): ParsedBank {
    val trimmed = content.trim()
    if (trimmed.isEmpty()) return ParsedBank(fallbackName, emptyList(), listOf("文件内容为空"))
    val root = try {
        org.json.JSONTokener(trimmed).nextValue()
    } catch (e: Exception) {
        return ParsedBank(fallbackName, emptyList(), listOf("JSON 解析失败: ${e.message ?: e.javaClass.simpleName}"))
    }
    val list: JSONArray
    var name = fallbackName
    when (root) {
        is JSONObject -> {
            name = root.optStringAny("name", "title", "题库名", "题目库")
                .ifBlank { fallbackName }
            list = root.optJSONArray("questions")
                ?: root.optJSONArray("list")
                ?: root.optJSONArray("题目")
                ?: root.optJSONArray("试题")
                ?: JSONArray()
        }
        is JSONArray -> list = root
        else -> return ParsedBank(name, emptyList(), listOf("JSON 结构不合法：顶层既不是对象也不是数组"))
    }
    val questions = ArrayList<ReviewQuestion>()
    val errors = ArrayList<String>()
    for (i in 0 until list.length()) {
        val obj = list.optJSONObject(i)
        if (obj == null) {
            errors.add("第 ${i + 1} 条不是 JSON 对象")
            continue
        }
        val q = ReviewQuestion.fromJson(obj)
        val errs = q.validate()
        if (errs.isEmpty()) {
            questions.add(q)
        } else {
            errors.add("第 ${i + 1} 条(id=${q.id}): ${errs.joinToString("; ")}")
        }
    }
    if (questions.isEmpty() && errors.isEmpty()) {
        errors.add("题库里没有可用的题目")
    }
    return ParsedBank(name, questions, errors)
}

/**
 * 取第一个存在的键对应的字符串。
 *
 * 键名要**平铺**写进 vararg（`optStringAny("answer", "答案")`），不要写成
 * `optStringAny("answer", optStringAny("答案"))` —— Kotlin 会先求值内层参数，
 * 内层找不到时返回 `""`，而 `""` 不是 null，循环会拿它去 `get("")`，
 * 于是中文键那条路被一个空键名顶掉（2026-10-03 踩过：所有中文键题库全被判"答案为空"）。
 * 下面的 `isBlank()` 判断是给这种写法留的最后一道保险。
 */
private fun JSONObject.optStringAny(vararg keys: String): String {
    for (k in keys) {
        if (k.isBlank() || !has(k) || isNull(k)) continue
        when (val v = get(k)) {
            is String -> return v
            else -> return v.toString()
        }
    }
    return ""
}

private fun JSONObject.optStringListAny(vararg keys: String): List<String> {
    for (k in keys) {
        if (k.isBlank() || !has(k) || isNull(k)) continue
        return when (val v = get(k)) {
            is JSONArray -> (0 until v.length()).map { v.optString(it) }
            // 兼容 CSV 风格："A选项|B选项" 或 ["A. xxx"] 都收
            is String -> v.split('|', '\n').map { it.trim() }.filter { it.isNotEmpty() }
            else -> emptyList()
        }
    }
    return emptyList()
}
