package com.hnnujw.course.document

import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * `.xlsx` 写出器 —— 课表 / 成绩导出用，与 [XlsxParser] 配对。
 *
 * 与解析器同一取舍：不引 POI，直接手拼最小 OOXML 包。只写 Excel 必需的
 * 六个条目，字符串用 `inlineStr`（省掉 sharedStrings 那份索引表），
 * Excel 与 WPS 都认。字符串一律转义，日期走自定义 numFmt（`yyyy-mm-dd hh:mm`）。
 *
 * 产出用 [XlsxParser] 读回来即可校验（单测里做了一次完整往返）。
 */
object SpreadsheetWriter {

    /** 单元格的三种值。其余类型（公式、富文本）导出场景用不上。 */
    sealed interface Cell {
        data class Text(val value: String) : Cell

        data class Number(val value: Double) : Cell

        /** 写成 Excel 日期序列号，带日期时间显示格式。 */
        data class DateTime(val epochMillis: Long) : Cell
    }

    /** Excel 日期起点：1900-01-00 的序列号 25569 对应 Unix 纪元（1970-01-01）。 */
    private const val EXCEL_EPOCH_OFFSET = 25569.0
    private const val MILLIS_PER_DAY = 86_400_000.0

    /** 额外表头行：加了表头会加粗，且计入 [XlsxParser.MAX_ROWS] 上限之内。 */
    fun write(
        rows: List<List<Cell?>>,
        sheetName: String = "Sheet1",
        boldFirstRow: Boolean = true,
    ): ByteArray {
        val sheet = buildSheetXml(rows, boldFirstRow)
        return zipPackage(sheetName, sheet)
    }

    /** 全是文本的快捷方式（课表矩阵、成绩单大多只需要这个）。 */
    fun writeTexts(rows: List<List<String>>, sheetName: String = "Sheet1"): ByteArray =
        write(
            rows = rows.map { row -> row.map { Cell.Text(it) as Cell? } },
            sheetName = sheetName,
        )

    // ── XML 拼装 ──────────────────────────────────────────────────────────

    private fun buildSheetXml(rows: List<List<Cell?>>, boldFirstRow: Boolean): String {
        val builder = StringBuilder()
        builder.append(XML_DECLARATION)
        builder.append(
            "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
        )
        builder.append("<sheetData>")
        rows.forEachIndexed { rowIndex, row ->
            val r = rowIndex + 1
            builder.append("<row r=\"").append(r).append("\">")
            row.forEachIndexed { columnIndex, cell ->
                if (cell == null) return@forEachIndexed
                val ref = columnName(columnIndex) + r
                val style = if (boldFirstRow && rowIndex == 0) 2 else 0
                when (cell) {
                    is Cell.Text -> {
                        if (cell.value.isEmpty()) return@forEachIndexed
                        builder.append("<c r=\"").append(ref).append("\"")
                        if (style != 0) builder.append(" s=\"").append(style).append("\"")
                        builder.append(" t=\"inlineStr\"><is><t>")
                        builder.append(escape(cell.value))
                        builder.append("</t></is></c>")
                    }

                    is Cell.Number -> {
                        builder.append("<c r=\"").append(ref).append("\"")
                        if (style != 0) builder.append(" s=\"").append(style).append("\"")
                        builder.append("><v>").append(formatNumber(cell.value)).append("</v></c>")
                    }

                    is Cell.DateTime -> {
                        builder.append("<c r=\"").append(ref).append("\" s=\"1\"><v>")
                        builder.append(excelSerial(cell.epochMillis))
                        builder.append("</v></c>")
                    }
                }
            }
            builder.append("</row>")
        }
        builder.append("</sheetData></worksheet>")
        return builder.toString()
    }

    private const val XML_DECLARATION = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"

    private const val CONTENT_TYPES_XML =
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
            "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
            "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
            "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
            "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>" +
            "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>" +
            "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>" +
            "</Types>"

    private const val ROOT_RELS_XML =
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
            "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>" +
            "</Relationships>"

    private fun workbookXml(sheetName: String): String =
        XML_DECLARATION +
            "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"" +
            " xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">" +
            "<sheets><sheet name=\"" + escape(sheetName.ifBlank { "Sheet1" }) + "\" sheetId=\"1\" r:id=\"rId1\"/></sheets>" +
            "</workbook>"

    private const val WORKBOOK_RELS_XML =
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
            "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/>" +
            "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>" +
            "</Relationships>"

    /**
     * 最小样式表：0=默认、1=日期时间（自定义格式号 176，必须 >=164）、2=加粗表头。
     * fills 必须凑够 none + gray125 两项，Excel 会按位索引。
     */
    private const val STYLES_XML =
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
            "<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">" +
            "<numFmts count=\"1\"><numFmt numFmtId=\"176\" formatCode=\"yyyy\\-mm\\-dd\\ hh:mm\"/></numFmts>" +
            "<fonts count=\"2\">" +
            "<font><sz val=\"11\"/><name val=\"Calibri\"/></font>" +
            "<font><b/><sz val=\"11\"/><name val=\"Calibri\"/></font>" +
            "</fonts>" +
            "<fills count=\"2\">" +
            "<fill><patternFill patternType=\"none\"/></fill>" +
            "<fill><patternFill patternType=\"gray125\"/></fill>" +
            "</fills>" +
            "<borders count=\"1\"><border/></borders>" +
            "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>" +
            "<cellXfs count=\"3\">" +
            "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>" +
            "<xf numFmtId=\"176\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\"/>" +
            "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\"/>" +
            "</cellXfs>" +
            "</styleSheet>"

    private fun zipPackage(sheetName: String, sheetXml: String): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putEntry("[Content_Types].xml", CONTENT_TYPES_XML)
            zip.putEntry("_rels/.rels", ROOT_RELS_XML)
            zip.putEntry("xl/workbook.xml", workbookXml(sheetName))
            zip.putEntry("xl/_rels/workbook.xml.rels", WORKBOOK_RELS_XML)
            zip.putEntry("xl/styles.xml", STYLES_XML)
            zip.putEntry("xl/worksheets/sheet1.xml", sheetXml)
        }
        return output.toByteArray()
    }

    private fun ZipOutputStream.putEntry(name: String, content: String) {
        putNextEntry(ZipEntry(name))
        write(content.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    // ── 数值与转义 ────────────────────────────────────────────────────────

    /** 列号 → Excel 列名：0=A，25=Z，26=AA。 */
    internal fun columnName(index: Int): String {
        require(index >= 0) { "列号不能为负" }
        var value = index
        val letters = StringBuilder()
        while (value >= 0) {
            letters.insert(0, ('A' + value % 26))
            value = value / 26 - 1
        }
        return letters.toString()
    }

    /** 毫秒 → Excel 序列号（UTC 计算，避免时区把日期挪一天）。 */
    internal fun excelSerial(epochMillis: Long): String {
        val serial = epochMillis / MILLIS_PER_DAY + EXCEL_EPOCH_OFFSET
        return formatNumber(serial)
    }

    /** Excel 日期序列号 → 毫秒（与 [excelSerial] 互逆，测试用）。 */
    internal fun serialToMillis(serial: Double): Long =
        ((serial - EXCEL_EPOCH_OFFSET) * MILLIS_PER_DAY).toLong()

    /** 整数不带小数点，小数最多留 6 位且去掉尾零。 */
    private fun formatNumber(value: Double): String {
        if (value == value.toLong().toDouble()) return value.toLong().toString()
        var text = String.format(Locale.US, "%.6f", value).trimEnd('0').trimEnd('.')
        if (text.isEmpty() || text == "-") text = "0"
        return text
    }

    /** XML 五个保留字符。文本内容里还可能出现非法控制字符，一并清掉。 */
    private fun escape(value: String): String = buildString {
        for (ch in value) {
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> if (ch >= ' ' || ch == '\t' || ch == '\n' || ch == '\r') append(ch)
            }
        }
    }

    /** 给导出文件名用的时间戳：`20260921-1354`。 */
    fun fileTimestamp(millis: Long = System.currentTimeMillis()): String =
        SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).apply { timeZone = TimeZone.getDefault() }.format(Date(millis))
}
