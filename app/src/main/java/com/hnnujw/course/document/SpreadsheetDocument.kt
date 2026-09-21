package com.hnnujw.course.document

import org.w3c.dom.Element
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipFile

/**
 * `.xlsx` 的极简阅读器 —— 第二课堂活动附件的**内置查看器**用。
 *
 * 与 [DocxParser] 同样的取舍：`.xlsx` 是 ZIP + XML，`java.util.zip` 加 `javax.xml`
 * 就能把工作表读成一张文字网格，不需要 POI（POI 在 Android 上要拖进 xmlbeans）。
 *
 * ## 覆盖范围
 *
 * | 内容 | 处理 |
 * | --- | --- |
 * | 多工作表 | ✅ `xl/workbook.xml` + rels 解析表名与顺序 |
 * | 共享字符串 | ✅ `xl/sharedStrings.xml`（含富文本 run 与注音排除） |
 * | 公式单元格 | ✅ 取缓存值 `<v>`（不求值） |
 * | 日期 / 时间 | ✅ 按 `cellXfs` 的 `numFmtId` 识别并格式化 |
 * | 布尔 / 错误值 | ✅ `TRUE`/`FALSE` 与错误码原样 |
 * | 单元格样式（颜色、字体、边框） | ❌ 只取文本 |
 * | 合并单元格 | ❌ 不还原（左上角有值，其余为空） |
 * | 图表 / 图片 / 数据透视 | ❌ 不展示 |
 *
 * 结果同样是纯数据（[SpreadsheetDocument]），可单测。
 */

/** 一张工作表的内容，已经规整成等宽的矩形。 */
data class SheetData(
    val name: String,
    /** 每行的单元格文本；所有行长度都等于 [columnCount]（缺的补空串）。 */
    val rows: List<List<String>>,
    val columnCount: Int,
    /** 因为超过行/列上限而被截断了。 */
    val truncated: Boolean = false,
) {
    val rowCount: Int get() = rows.size
    val isEmpty: Boolean get() = rows.isEmpty() || columnCount == 0
}

/** 整个工作簿。 */
data class SpreadsheetDocument(
    val sheets: List<SheetData> = emptyList(),
    /** `docProps/core.xml` 的 `dc:title`，取不到为空串。 */
    val title: String = "",
) {
    val isEmpty: Boolean get() = sheets.isEmpty() || sheets.all { it.isEmpty }
}

/** `.xlsx` 解析失败。 */
class SheetParseException(message: String, cause: Throwable? = null) : Exception(message, cause)

object XlsxParser {

    /** 行 / 列上限。超过就截断并在界面上明说 —— 宁可少画，不要把几百 MB 的表格读爆内存。 */
    const val MAX_ROWS = 3_000
    const val MAX_COLUMNS = 80

    fun parse(file: File): SpreadsheetDocument {
        val bytes = try {
            file.readBytes()
        } catch (e: Exception) {
            throw SheetParseException("读取表格失败", e)
        }
        return parse(bytes)
    }

    fun parse(bytes: ByteArray): SpreadsheetDocument {
        val temp = try {
            File.createTempFile("xlsx-", ".zip").apply { writeBytes(bytes) }
        } catch (e: Exception) {
            throw SheetParseException("无法创建临时文件", e)
        }
        return try {
            ZipFile(temp).use { zip -> XlsxReader(zip).read() }
        } catch (e: SheetParseException) {
            throw e
        } catch (e: Exception) {
            throw SheetParseException("这不是一个有效的 Excel 表格", e)
        } finally {
            temp.delete()
        }
    }
}

private class XlsxReader(private val zip: ZipFile) {

    private val sharedStrings: List<String> by lazy { readSharedStrings() }
    private val styleIsDateFormat: List<Boolean> by lazy { readStyleDateFlags() }

    fun read(): SpreadsheetDocument {
        val workbook = zip.readEntry("xl/workbook.xml")
            ?: throw SheetParseException("这不是一个有效的 Excel 表格（缺少 xl/workbook.xml）")
        val rels = readWorkbookRelations()
        val document = parseXml(workbook) ?: throw SheetParseException("表格内容损坏，无法解析")

        val sheets = ArrayList<SheetData>()
        val nodes = document.getElementsByTagName("sheet")
        for (i in 0 until nodes.length) {
            val node = nodes.item(i) as? Element ?: continue
            val name = node.attr("name").ifBlank { "工作表 ${i + 1}" }
            val relId = node.attr("r:id").ifBlank { node.attr("id") }
            val target = rels[relId] ?: continue
            val xml = zip.readEntry(resolveSheetPath(target)) ?: continue
            val sheetDocument = parseXml(xml) ?: continue
            sheets += readSheet(name, sheetDocument)
        }
        if (sheets.isEmpty()) throw SheetParseException("这个表格里没有可显示的工作表")
        return SpreadsheetDocument(sheets = sheets, title = readCoreTitle())
    }

    // ── 单张工作表 ────────────────────────────────────────────────────────

    private fun readSheet(name: String, document: org.w3c.dom.Document): SheetData {
        // 只认 sheetData 下的 row，避免把 chart 里的行也扫进来
        val sheetData = document.getElementsByTagName("sheetData").item(0) as? Element
            ?: return SheetData(name = name, rows = emptyList(), columnCount = 0)

        val grid = ArrayList<List<String>>()
        var columnCount = 0
        var truncated = false

        val rowNodes = sheetData.getElementsByTagName("row")
        for (r in 0 until rowNodes.length) {
            if (grid.size >= XlsxParser.MAX_ROWS) {
                truncated = true
                break
            }
            val rowElement = rowNodes.item(r) as? Element ?: continue
            val cells = HashMap<Int, String>()
            for (cell in childElements(rowElement)) {
                if (cell.tagName != "c") continue
                val index = columnIndexOf(cell.attr("r"))
                if (index < 0 || index >= XlsxParser.MAX_COLUMNS) {
                    if (index >= XlsxParser.MAX_COLUMNS) truncated = true
                    continue
                }
                cells[index] = cellText(cell)
            }
            val width = (cells.keys.maxOrNull() ?: -1) + 1
            if (width <= 0) {
                grid.add(emptyList())
                continue
            }
            columnCount = maxOf(columnCount, width)
            grid += (0 until width).map { cells[it].orEmpty() }
        }

        // 补成等宽矩形，界面才能按固定列宽画网格
        val padded = if (columnCount == 0) emptyList() else grid.map { row ->
            if (row.size == columnCount) row else row + List(columnCount - row.size) { "" }
        }
        return SheetData(name = name, rows = padded, columnCount = columnCount, truncated = truncated)
    }

    /**
     * 单元格文本。
     *
     * `t` 决定 `<v>` 怎么解释：
     * - `s` 共享字符串下标
     * - `str` 公式的字符串结果（原样）
     * - `inlineStr` 值在 `<is>` 里
     * - `b` 布尔
     * - `e` 错误码
     * - 缺省 / `n` 数字，需要按样式判断是不是日期
     */
    private fun cellText(cell: Element): String {
        val type = cell.attr("t")
        if (type == "inlineStr") {
            val inline = childElement(cell, "is") ?: return ""
            return inlineText(inline).trim()
        }
        val raw = childElement(cell, "v")?.textContent?.trim().orEmpty()
        if (raw.isEmpty()) return ""
        return when (type) {
            "s" -> raw.toIntOrNull()?.let { sharedStrings.getOrNull(it) }.orEmpty()
            "str" -> raw
            "b" -> if (raw == "1") "TRUE" else "FALSE"
            "e" -> raw
            else -> formatNumeric(raw, styleIndexOf(cell))
        }
    }

    /** 数字单元格：是日期样式就按日期显示，否则去掉无意义的 `.0` 尾巴。 */
    private fun formatNumeric(raw: String, styleIndex: Int): String {
        val value = raw.toDoubleOrNull() ?: return raw
        if (styleIsDateFormat.getOrNull(styleIndex) == true) {
            excelSerialToDate(value)?.let { return it }
        }
        // Excel 里 `3` 存成 "3"，但有的生成器写 "3.0"；只在这一种情况下去尾
        if (raw.endsWith(".0") && value == Math.floor(value) && !value.isInfinite()) {
            return value.toLong().toString()
        }
        return raw
    }

    /**
     * Excel 日期序列号 → 可读字符串。
     *
     * 序列号是「1899-12-30 起的天数」（1900 系统含那个著名的闰年 bug，
     * 用 12-30 做基点正好把它抹平）。`25569` 是 1970-01-01 的序列号，
     * 于是 `(serial - 25569) * 86400000` 就是 UTC 毫秒。
     * 用 **UTC** 格式化，避免按本地时区平移掉一天。
     */
    private fun excelSerialToDate(serial: Double): String? {
        if (serial <= 0.0 || serial > 2_958_465.0) return null // 超出 9999 年
        val millis = Math.round((serial - 25569.0) * 86_400_000.0)
        val hasTime = serial - Math.floor(serial) > 1e-9
        val pattern = if (hasTime) "yyyy-MM-dd HH:mm" else "yyyy-MM-dd"
        return runCatching {
            SimpleDateFormat(pattern, Locale.CHINA).apply { timeZone = TimeZone.getTimeZone("UTC") }
                .format(java.util.Date(millis))
        }.getOrNull()
    }

    // ── 共享字符串 / 样式 / 关系 ──────────────────────────────────────────

    private fun readSharedStrings(): List<String> {
        val xml = zip.readEntry("xl/sharedStrings.xml") ?: return emptyList()
        val document = parseXml(xml) ?: return emptyList()
        val result = ArrayList<String>()
        val nodes = document.getElementsByTagName("si")
        for (i in 0 until nodes.length) {
            val node = nodes.item(i) as? Element ?: continue
            result += inlineText(node).trim()
        }
        return result
    }

    /**
     * `<si>` / `<is>` 的文字。
     *
     * 富文本会把文字拆进多个 `<r><t>`，所以要拼；
     * 但要跳过 `<rPh>`（日文注音）—— 它是重复发音，拼进去会变成乱码般的重复文本。
     */
    private fun inlineText(container: Element): String {
        val builder = StringBuilder()
        val texts = container.getElementsByTagName("t")
        for (i in 0 until texts.length) {
            val textNode = texts.item(i) as? Element ?: continue
            if (isInsidePhonetic(textNode)) continue
            builder.append(textNode.textContent)
        }
        return builder.toString()
    }

    private fun isInsidePhonetic(node: Element): Boolean {
        var parent = node.parentNode
        while (parent is Element) {
            if (parent.tagName == "rPh") return true
            parent = parent.parentNode
        }
        return false
    }

    /** 每个 `cellXfs/xf` 是否指向一个日期格式。下标即单元格的 `s` 属性。 */
    private fun readStyleDateFlags(): List<Boolean> {
        val xml = zip.readEntry("xl/styles.xml") ?: return emptyList()
        val document = parseXml(xml) ?: return emptyList()

        // 自定义格式：numFmtId → formatCode
        val customFormats = HashMap<Int, String>()
        val numFmts = document.getElementsByTagName("numFmt")
        for (i in 0 until numFmts.length) {
            val node = numFmts.item(i) as? Element ?: continue
            customFormats[node.intAttr("numFmtId")] = node.attr("formatCode")
        }

        val flags = ArrayList<Boolean>()
        val cellXfs = document.getElementsByTagName("cellXfs").item(0) as? Element ?: return emptyList()
        for (xf in childElements(cellXfs)) {
            if (xf.tagName != "xf") continue
            val numFmtId = xf.intAttr("numFmtId")
            flags += isDateFormat(numFmtId, customFormats[numFmtId])
        }
        return flags
    }

    private fun styleIndexOf(cell: Element): Int = cell.attr("s").trim().toIntOrNull() ?: 0

    /**
     * 判断 numFmtId 是不是日期/时间格式。
     *
     * 内建格式号有权威列表（14–22 是日期时间，27–36、50–58 是东亚历），
     * 自定义格式只能看格式串：含 `y` / `d` / `h` 就是日期时间，
     * 纯 `m` 不认（`0.00` 里没有 m，但 `mm` 单独出现无法与"月"区分，
     * 靠同时要求 y 或 d 兜住）。
     */
    private fun isDateFormat(numFmtId: Int, formatCode: String?): Boolean {
        if (numFmtId in BUILTIN_DATE_FORMATS) return true
        if (formatCode.isNullOrBlank()) return false
        // 去掉引号里的字面量，避免 [$-409] 之类的区域前缀被误判
        val code = formatCode.replace(Regex("\"[^\"]*\""), "").lowercase()
        return code.contains('y') || code.contains('d') || code.contains('h')
    }

    private fun readWorkbookRelations(): Map<String, String> {
        val xml = zip.readEntry("xl/_rels/workbook.xml.rels") ?: return emptyMap()
        val document = parseXml(xml) ?: return emptyMap()
        val result = LinkedHashMap<String, String>()
        val nodes = document.getElementsByTagName("Relationship")
        for (i in 0 until nodes.length) {
            val node = nodes.item(i) as? Element ?: continue
            val id = node.attr("Id")
            val target = node.attr("Target")
            if (id.isNotBlank() && target.isNotBlank()) result[id] = target
        }
        return result
    }

    /** rels 里的目标是相对 `xl/` 的路径，也可能是绝对写法。 */
    private fun resolveSheetPath(target: String): String {
        val cleaned = target.removePrefix("/")
        return when {
            cleaned.startsWith("xl/") -> cleaned
            cleaned.startsWith("../") -> cleaned.removePrefix("../")
            else -> "xl/$cleaned"
        }
    }

    private fun readCoreTitle(): String {
        val xml = zip.readEntry("docProps/core.xml") ?: return ""
        val document = parseXml(xml) ?: return ""
        val titles = document.getElementsByTagName("dc:title")
        return if (titles.length > 0) titles.item(0).textContent.orEmpty().trim() else ""
    }

    private companion object {
        /**
         * 内建日期/时间格式号。来自 OOXML 规范（ECMA-376 §18.8.30）：
         * 14–22 是日期时间，27–36 与 50–58 是东亚历法变体。
         */
        val BUILTIN_DATE_FORMATS = (14..22) + (27..36) + (45..47) + (50..58)

        /**
         * "A" → 0，"B" → 1，"AA" → 26。Excel 的列名是 26 进制但没有 0，
         * 所以每位是 `字母 - 'A' + 1`。
         */
        fun columnIndexOf(reference: String): Int {
            if (reference.isEmpty()) return -1
            var value = 0
            var counted = 0
            for (char in reference) {
                if (char in 'A'..'Z') {
                    value = value * 26 + (char - 'A' + 1)
                    counted++
                } else if (char in 'a'..'z') {
                    value = value * 26 + (char - 'a' + 1)
                    counted++
                } else {
                    break
                }
            }
            return if (counted == 0) -1 else value - 1
        }
    }
}
