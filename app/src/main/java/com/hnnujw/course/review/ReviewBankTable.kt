package com.hnnujw.course.review

/**
 * 表格题库（Excel / CSV / TSV）的解析。
 *
 * 为什么不复用 [parseJsonBank] 的入口：JSON 是**结构化**的，键名就是语义；表格是
 * **人类排版**的，语义全靠表头文字，而且同一个人今天写「题干」、明天写「题目」。
 * 所以这里做的核心工作其实是**认列**，不是认值。
 *
 * 认列规则刻意偏宽松：表头去空白、去大小写、去结尾冒号后，命中一组别名即可。
 * 只要「题干」和「答案」两列认出来，其余列缺了都有默认值 —— 宁可导进来 80%，
 * 也不要因为一列「解析」没写就把整份文件判死。
 *
 * 隐私：题干内容不进日志（本文件本来也不打日志）。
 */
object ReviewBankTable {

    /** 表头别名。**全部小写**，匹配前会先把表头归一化。 */
    private val ALIAS_STEM = listOf("题干", "题目", "问题", "题目内容", "stem", "question")
    private val ALIAS_TYPE = listOf("题型", "类型", "type")
    private val ALIAS_ANSWER = listOf("答案", "正确答案", "answer", "key")
    private val ALIAS_CHAPTER = listOf("章节", "所属章节", "章", "chapter")
    private val ALIAS_EXPLAIN = listOf("解析", "答案解析", "explanation", "analysis")
    private val ALIAS_OPTIONS = listOf("选项", "选项列表", "options")
    private val ALIAS_ID = listOf("id", "题号", "编号")

    /** 表头归一化：去空白、去大小写、去结尾的冒号。 */
    private fun normalizeHeader(raw: String): String =
        raw.trim().lowercase().replace(Regex("\\s+"), "").trimEnd(':', '：')

    /**
     * 解析二维表格（已是"行 × 列"的字符串矩阵）。[fallbackName] 用于没有标题行时的库名。
     *
     * 返回 [ParsedBank]：能用的题在 [ParsedBank.questions]，被跳过的行原因在 [ParsedBank.errors]
     * （每条都带行号，用户能回去改）。
     */
    fun parseRows(rows: List<List<String>>, fallbackName: String = "导入题库"): ParsedBank {
        val headerIndex = rows.indexOfFirst { isHeaderRow(it) }
        if (headerIndex < 0) {
            return ParsedBank(
                fallbackName, emptyList(),
                listOf("没找到表头：第一行需要包含「题干」和「答案」两列（也可以用 stem / answer）")
            )
        }
        val header = rows[headerIndex]
        val columns = resolveColumns(header)
        val stemCol = columns.stem
        val answerCol = columns.answer
        if (stemCol == null || answerCol == null) {
            return ParsedBank(
                fallbackName, emptyList(),
                listOf("没找到表头：第一行需要包含「题干」和「答案」两列（也可以用 stem / answer）")
            )
        }

        val questions = ArrayList<ReviewQuestion>()
        val errors = ArrayList<String>()
        for (i in headerIndex + 1 until rows.size) {
            val row = rows[i]
            val humanRow = i + 1
            // 整行空白：Excel 末尾常见，静默跳过，不算错误。
            if (row.all { it.isBlank() }) continue

            fun cell(index: Int?): String = index?.let { row.getOrNull(it)?.trim().orEmpty() }.orEmpty()

            val stem = cell(stemCol)
            if (stem.isBlank()) {
                errors.add("第 $humanRow 行：题干为空")
                continue
            }
            val options = pickOptions(row, columns)
            val answer = normalizeAnswer(cell(answerCol))
            val rawType = normalizeType(cell(columns.type))
            val type = rawType ?: if (answer.length > 1) ReviewQuestion.TYPE_MULTI else ReviewQuestion.TYPE_SINGLE
            val id = cell(columns.id).ifBlank { "row$humanRow" }

            val question = ReviewQuestion(
                id = id,
                type = type,
                stem = stem,
                options = options,
                answer = answer,
                chapter = cell(columns.chapter).ifBlank { "未分类" },
                explanation = cell(columns.explain)
            )
            val problem = question.validate()
            if (problem.isEmpty()) questions.add(question)
            else errors.add("第 $humanRow 行：${problem.joinToString("; ")}")
        }

        if (questions.isEmpty() && errors.isEmpty()) errors.add("表格里没有可用的题目")
        return ParsedBank(fallbackName, questions, errors)
    }

    /** 表头行：至少认出 2 个已知列，且其中包含「题干」或「答案」之一。 */
    private fun isHeaderRow(row: List<String>): Boolean {
        val hits = row.count { cell -> knownColumnKind(normalizeHeader(cell)) != null }
        if (hits < 2) return false
        return row.any { cell ->
            val norm = normalizeHeader(cell)
            norm in ALIAS_STEM || norm in ALIAS_ANSWER
        }
    }

    private enum class Kind { Stem, Type, Answer, Chapter, Explain, Options, Id }

    private fun knownColumnKind(norm: String): Kind? = when {
        norm.isEmpty() -> null
        norm in ALIAS_STEM -> Kind.Stem
        norm in ALIAS_TYPE -> Kind.Type
        norm in ALIAS_ANSWER -> Kind.Answer
        norm in ALIAS_CHAPTER -> Kind.Chapter
        norm in ALIAS_EXPLAIN -> Kind.Explain
        norm in ALIAS_OPTIONS -> Kind.Options
        norm in ALIAS_ID -> Kind.Id
        else -> null
    }

    /** 解析出的列下标。选项既可能是一列（`A|B|C|D`），也可能是 A/B/C/D 四列。 */
    private data class Columns(
        val stem: Int?,
        val type: Int?,
        val answer: Int?,
        val chapter: Int?,
        val explain: Int?,
        val id: Int?,
        val options: Int?,
        /** 单字母列（A/B/C/D…）的下标，按出现顺序。 */
        val letterColumns: List<Int>
    )

    private fun resolveColumns(header: List<String>): Columns {
        var stem: Int? = null
        var type: Int? = null
        var answer: Int? = null
        var chapter: Int? = null
        var explain: Int? = null
        var options: Int? = null
        var id: Int? = null
        val letters = ArrayList<Int>()
        header.forEachIndexed { index, raw ->
            val norm = normalizeHeader(raw)
            when (knownColumnKind(norm)) {
                Kind.Stem -> if (stem == null) stem = index
                Kind.Type -> if (type == null) type = index
                Kind.Answer -> if (answer == null) answer = index
                Kind.Chapter -> if (chapter == null) chapter = index
                Kind.Explain -> if (explain == null) explain = index
                Kind.Options -> if (options == null) options = index
                Kind.Id -> if (id == null) id = index
                null -> {
                    // 单字母列：A / B / C … 或 选项A / 选项a
                    val letter = norm.removePrefix("选项").uppercase()
                    if (letter.length == 1 && letter[0] in 'A'..'H') letters.add(index)
                }
            }
        }
        return Columns(stem, type, answer, chapter, explain, id, options, letters)
    }

    private fun pickOptions(row: List<String>, columns: Columns): List<String> {
        // ① 一列装全部选项：`A|B|C`、`甲；乙；丙`、换行分隔都认。
        columns.options?.let { index ->
            val raw = row.getOrNull(index)?.trim().orEmpty()
            if (raw.isNotEmpty()) {
                return raw.split('|', '；', ';', '\n')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
            }
        }
        // ② 每列一个选项（A/B/C/D…）。
        return columns.letterColumns
            .map { row.getOrNull(it)?.trim().orEmpty() }
            .filter { it.isNotEmpty() }
    }

    /**
     * 解析 CSV / TSV 文本。
     *
     * 分隔符自动判定：先看首个非空行，制表符比逗号多就用制表符（Excel 复制出来的
     * 通常是这种）。引号规则按 RFC 4180：`"` 包住含分隔符的内容，`""` 是一个字面引号。
     */
    fun parseDelimited(text: String, fallbackName: String = "导入题库"): ParsedBank {
        val clean = text.removePrefix("\uFEFF")   // Excel 存 CSV 时会带 BOM
        if (clean.isBlank()) return ParsedBank(fallbackName, emptyList(), listOf("文件内容为空"))
        val delimiter = detectDelimiter(clean)
        val rows = splitDelimited(clean, delimiter)
        return parseRows(rows, fallbackName)
    }

    private fun detectDelimiter(text: String): Char {
        val sample = text.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
        val tabs = sample.count { it == '\t' }
        val commas = sample.count { it == ',' }
        return if (tabs > commas) '\t' else ','
    }

    private fun splitDelimited(text: String, delimiter: Char): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val cell = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            when {
                inQuotes -> {
                    if (ch == '"') {
                        if (i + 1 < text.length && text[i + 1] == '"') { cell.append('"'); i++ } else inQuotes = false
                    } else cell.append(ch)
                }
                ch == '"' -> inQuotes = true
                ch == delimiter -> { row.add(cell.toString()); cell.clear() }
                ch == '\n' -> { row.add(cell.toString()); cell.clear(); rows.add(row); row = ArrayList() }
                ch == '\r' -> Unit   // CRLF：交给 \n 收尾
                else -> cell.append(ch)
            }
            i++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) {
            row.add(cell.toString())
            rows.add(row)
        }
        return rows
    }
}
