package com.hnnujw.course.document

import java.io.File

/**
 * `.csv` 阅读器 —— 产出 [SpreadsheetDocument]，直接复用 Excel 那套网格界面。
 *
 * 站点上确实有 `.csv` 附件（导出的名单、名单模板），如果按纯文本渲染会是一行行
 * 逗号挤在一起，很难读。既然已经有网格渲染器，就把它解析成网格。
 *
 * 支持 RFC 4180 里会真的遇到的几条：引号包裹、`""` 转义、字段内换行与逗号、
 * CRLF / LF / CR 三种行尾；分隔符在 `,` / `;` / `\t` 里按第一行的出现次数猜
 * （Excel 在中文 Windows 上导出的 "CSV" 常常是分号或制表符）。
 */
object CsvParser {

    val MAX_ROWS = XlsxParser.MAX_ROWS
    val MAX_COLUMNS = XlsxParser.MAX_COLUMNS

    fun parse(file: File, sheetName: String = ""): SpreadsheetDocument {
        val document = try {
            TextParser.parse(file)
        } catch (e: TextParseException) {
            throw SheetParseException("读取表格失败", e)
        }
        return parseText(document.text, sheetName.ifBlank { file.nameWithoutExtension })
    }

    fun parseText(text: String, sheetName: String = "Sheet1"): SpreadsheetDocument {
        val result = tokenize(text, detectDelimiter(text), MAX_ROWS, MAX_COLUMNS)
        val width = result.columnCount
        // 补成等宽矩形：界面按固定列宽画网格，行长短不一就没法对齐
        val padded = if (width == 0) {
            emptyList()
        } else {
            result.rows.map { row ->
                if (row.size >= width) row else row + List(width - row.size) { "" }
            }
        }
        return SpreadsheetDocument(
            sheets = listOf(
                SheetData(
                    name = sheetName.ifBlank { "Sheet1" },
                    rows = padded,
                    columnCount = width,
                    truncated = result.truncated,
                )
            ),
            title = "",
        )
    }

    /**
     * 猜分隔符：只看第一行里 `,` / `;` / `\t` 的出现次数，取最多的那个。
     * 引号里的分隔符不算 —— 第一行就带引号字段的表格不少（`"姓名","学号"`）。
     */
    private fun detectDelimiter(text: String): Char {
        var comma = 0
        var semicolon = 0
        var tab = 0
        var inQuotes = false
        var i = 0
        while (i < text.length && i < 4096) {
            val c = text[i]
            if (c == '\n') break
            if (c == '"') {
                inQuotes = !inQuotes
            } else if (!inQuotes) {
                if (c == ',') comma++ else if (c == ';') semicolon++ else if (c == '\t') tab++
            }
            i++
        }
        return when {
            semicolon > comma && semicolon >= tab -> ';'
            tab > comma && tab > semicolon -> '\t'
            else -> ','
        }
    }

    private class Tokenized(
        val rows: List<List<String>>,
        val columnCount: Int,
        val truncated: Boolean,
    )

    private fun tokenize(text: String, delimiter: Char, maxRows: Int, maxColumns: Int): Tokenized {
        val rows = ArrayList<List<String>>()
        val fields = ArrayList<String>()
        var field = StringBuilder()
        var columnCount = 0
        var truncated = false
        var inQuotes = false
        var i = 0

        while (i < text.length) {
            val c = text[i]
            if (inQuotes) {
                when {
                    // `""` 是一个转义的双引号
                    c == '"' && i + 1 < text.length && text[i + 1] == '"' -> {
                        field.append('"')
                        i += 2
                    }
                    c == '"' -> {
                        inQuotes = false
                        i++
                    }
                    else -> {
                        field.append(c)
                        i++
                    }
                }
                continue
            }
            when {
                c == '"' -> {
                    // 只有字段开头的引号才算引用开始；字段中间的引号当普通字符
                    if (field.length == 0) inQuotes = true else field.append(c)
                    i++
                }
                c == delimiter -> {
                    fields.add(field.toString())
                    field = StringBuilder()
                    i++
                }
                c == '\r' || c == '\n' -> {
                    if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                    i++
                    fields.add(field.toString())
                    field = StringBuilder()
                    if (fields.size > maxColumns) truncated = true
                    columnCount = maxOf(columnCount, minOf(fields.size, maxColumns))
                    rows.add(ArrayList(fields.subList(0, minOf(fields.size, maxColumns))))
                    fields.clear()
                    if (rows.size >= maxRows) {
                        return Tokenized(rows, columnCount, true)
                    }
                }
                else -> {
                    field.append(c)
                    i++
                }
            }
        }

        // 收尾：最后一行没有以换行结束
        if (field.isNotEmpty() || fields.isNotEmpty()) {
            fields.add(field.toString())
            if (fields.size > maxColumns) truncated = true
            columnCount = maxOf(columnCount, minOf(fields.size, maxColumns))
            rows.add(ArrayList(fields.subList(0, minOf(fields.size, maxColumns))))
            if (rows.size > maxRows) {
                rows.removeAt(rows.size - 1)
                truncated = true
            }
        }
        return Tokenized(rows, columnCount, truncated)
    }
}
