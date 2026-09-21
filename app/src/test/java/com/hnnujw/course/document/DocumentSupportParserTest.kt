package com.hnnujw.course.document

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 内置附件查看器里「非 OOXML」那半边的回归：CSV、纯文本、以及类型嗅探。
 *
 * 与 [DocumentParserTest]（docx / xlsx）分开成两个文件，是因为这两组夹具
 * 来源完全不同：那边需要手写 OOXML，这边只需要几十个字节。
 */
class DocumentSupportParserTest {

    // ── CSV ───────────────────────────────────────────────────────────────

    @Test
    fun parsesQuotedFieldsAndEmbeddedDelimiters() {
        val text = "姓名,备注\n\"张三\",\"他说\"\"你好\"\"，然后走了\"\n李四,\"跨\n行\""
        val sheet = CsvParser.parseText(text, "名单").sheets.single()

        assertEquals("名单", sheet.name)
        assertEquals("姓名", sheet.rows[0][0])
        assertEquals("张三", sheet.rows[1][0])
        // 引号内的 `""` 是一个转义双引号，逗号也不能当分隔符
        assertEquals("他说\"你好\"，然后走了", sheet.rows[1][1])
        // 引号内的换行是字段内容，不是新的一行
        assertEquals("跨\n行", sheet.rows[2][1])
        assertEquals(3, sheet.rowCount)
        assertEquals(2, sheet.columnCount)
    }

    @Test
    fun detectsSemicolonAndTabDelimiters() {
        // 中文 Windows 上 Excel 导出的 "CSV" 常常是分号或制表符
        assertEquals(listOf("a", "b", "c"), CsvParser.parseText("a;b;c\n1;2;3").sheets.single().rows[0])
        assertEquals(listOf("a", "b", "c"), CsvParser.parseText("a\tb\tc\n1\t2\t3").sheets.single().rows[0])
    }

    @Test
    fun padsShortRowsAndIgnoresTrailingNewline() {
        val sheet = CsvParser.parseText("a,b,c\n1,2\n").sheets.single()

        // 末尾那个换行符不该多出来一行空行
        assertEquals(2, sheet.rowCount)
        assertEquals(3, sheet.columnCount)
        // 短行补空串，网格才能对齐
        assertEquals(listOf("1", "2", ""), sheet.rows[1])
    }

    @Test
    fun parsesCsvFileWithGbkEncoding() {
        val gbk = runCatching { Charset.forName("GBK") }.getOrNull() ?: return
        val file = tempFile("姓名,学号\n张三,10086".toByteArray(gbk))
        try {
            val sheet = CsvParser.parse(file, "名单").sheets.single()
            assertEquals("姓名", sheet.rows[0][0])
            assertEquals("张三", sheet.rows[1][0])
        } finally {
            file.delete()
        }
    }

    // ── 纯文本 ────────────────────────────────────────────────────────────

    @Test
    fun detectsUtf8AndReportsCharset() {
        val document = TextParser.parse("通知：请按时报名\n第二行".toByteArray())
        assertEquals("UTF-8", document.charsetName)
        assertEquals(2, document.lineCount)
        assertFalse(document.truncated)
    }

    @Test
    fun stripsUtf8Bom() {
        val withBom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
            "活动通知".toByteArray(Charsets.UTF_8)
        val document = TextParser.parse(withBom)

        // BOM 三个字节不能被解成正文开头的乱码字符
        assertEquals("活动通知", document.text)
    }

    @Test
    fun fallsBackToGbkWhenBytesAreNotValidUtf8() {
        val gbk = runCatching { Charset.forName("GBK") }.getOrNull() ?: return
        val document = TextParser.parse("姓名,学号\n张三".toByteArray(gbk))

        assertEquals("GBK", document.charsetName)
        assertTrue(document.text.startsWith("姓名"))
    }

    @Test
    fun truncatesOverlongText() {
        val document = TextParser.parse("a".repeat(TextParser.MAX_CHARS + 500).toByteArray())

        assertTrue(document.truncated)
        assertEquals(TextParser.MAX_CHARS, document.text.length)
    }

    // ── 类型嗅探 ──────────────────────────────────────────────────────────

    @Test
    fun sniffsOoxmlByZipStructureNotExtension() {
        // 临时文件一律叫 .bin：能认出来只可能是靠内容
        val docx = tempFile(docxBytes())
        val xlsx = tempFile(xlsxBytes())
        try {
            assertEquals(DocumentKind.Word, DocumentSniffer.sniff(docx))
            assertEquals(DocumentKind.Excel, DocumentSniffer.sniff(xlsx))
        } finally {
            docx.delete()
            xlsx.delete()
        }
    }

    @Test
    fun sniffsPdfAndImagesByMagicBytes() {
        val pdf = tempFile("%PDF-1.7\n1 0 obj\n".toByteArray())
        val jpeg = tempFile(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 16))
        val png = tempFile(
            byteArrayOf(
                0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
                0, 0, 0, 13, 0x49, 0x48, 0x44, 0x52
            )
        )
        try {
            assertEquals(DocumentKind.Pdf, DocumentSniffer.sniff(pdf))
            assertEquals(DocumentKind.Image, DocumentSniffer.sniff(jpeg))
            assertEquals(DocumentKind.Image, DocumentSniffer.sniff(png))
        } finally {
            pdf.delete()
            jpeg.delete()
            png.delete()
        }
    }

    @Test
    fun sniffsCsvAsSpreadsheetAndTxtAsPlainText() {
        val csv = tempFile("姓名,学号\n张三,10086".toByteArray())
        val txt = tempFile("请于周五前完成报名。".toByteArray())
        try {
            // .csv 走网格渲染（复用 Excel 界面），不是纯文本
            assertEquals(DocumentKind.Excel, DocumentSniffer.sniff(csv, "名单.csv"))
            assertEquals(DocumentKind.Text, DocumentSniffer.sniff(txt, "说明.txt"))
        } finally {
            csv.delete()
            txt.delete()
        }
    }

    @Test
    fun treatsLegacyOle2OfficeFilesAsUnsupported() {
        // 旧版 .doc / .xls 是 OLE2 复合文档，内置查看器不解析
        val legacy = tempFile(
            byteArrayOf(
                0xD0.toByte(), 0xCF.toByte(), 0x11.toByte(), 0xE0.toByte(),
                0xA1.toByte(), 0xB1.toByte(), 0x1A.toByte(), 0xE1.toByte(),
                0, 0, 0, 0
            )
        )
        try {
            assertEquals(DocumentKind.Unknown, DocumentSniffer.sniff(legacy, "旧版通知.doc"))
            assertFalse(DocumentKind.Unknown.renderableInApp)
        } finally {
            legacy.delete()
        }
    }

    @Test
    fun fallsBackToExtensionWhenContentIsAmbiguous() {
        assertEquals(DocumentKind.Word, DocumentSniffer.extensionKind("通知.DOCX"))
        assertEquals(DocumentKind.Excel, DocumentSniffer.extensionKind("名单.CSV"))
        assertEquals(DocumentKind.Pdf, DocumentSniffer.extensionKind("安排.pdf"))
        assertEquals(DocumentKind.Image, DocumentSniffer.extensionKind("海报.JPEG"))
        assertEquals(DocumentKind.Unknown, DocumentSniffer.extensionKind("附件.rar"))
    }

    // ── 夹具工具 ──────────────────────────────────────────────────────────

    /**
     * 建一个临时文件。
     *
     * 后缀刻意用 `.bin`：这样 [DocumentSniffer] 认出来的类型只可能来自**内容**，
     * 要测扩展名兜底的用例则显式传 `nameHint`。
     */
    private fun tempFile(bytes: ByteArray): File =
        File.createTempFile("hnnu-doc-", ".bin").apply { writeBytes(bytes) }

    /** 合成一个最小 docx（只要 ZIP 里有 `word/document.xml` 够嗅探用）。 */
    private fun docxBytes(): ByteArray = zip(
        "word/document.xml" to
            """<?xml version="1.0"?><w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p><w:r><w:t>通知</w:t></w:r></w:p></w:body></w:document>""",
        "docProps/core.xml" to """<?xml version="1.0"?><cp:coreProperties xmlns:cp="x"/>""",
    )

    /** 合成一个最小 xlsx（只要 ZIP 里有 `xl/workbook.xml` 够嗅探用）。 */
    private fun xlsxBytes(): ByteArray = zip(
        "xl/workbook.xml" to
            """<?xml version="1.0"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheets><sheet name="Sheet1" sheetId="1" r:id="rId1" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"/></sheets></workbook>""",
        "xl/_rels/workbook.xml.rels" to
            """<?xml version="1.0"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Target="worksheets/sheet1.xml" Type="worksheet"/></Relationships>""",
        "xl/worksheets/sheet1.xml" to
            """<?xml version="1.0"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData><row r="1"><c r="A1"><v>1</v></c></row></sheetData></worksheet>""",
    )

    private fun zip(vararg entries: Pair<String, String>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }
}
