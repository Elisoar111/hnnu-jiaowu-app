package com.hnnujw.course.document

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 内置附件查看器的解析层回归。
 *
 * 用**手工合成的最小 OOXML** 当夹具：不依赖任何真实文件，
 * 也不用联网，因此在任何机器上都能跑。
 *
 * 夹具刻意只放"必须支持"的元素（标题 / 加粗 / 编号 / 表格 / 图片引用、
 * 共享字符串 / 日期样式 / 布尔），这样哪天解析器被改坏，报错点很直接。
 */
class DocumentParserTest {

    // ── docx ──────────────────────────────────────────────────────────────

    private val documentXml = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"
                    xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"
                    xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"
                    xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing">
          <w:body>
            <w:p><w:pPr><w:pStyle w:val="Heading1"/></w:pPr><w:r><w:t>活动通知</w:t></w:r></w:p>
            <w:p><w:r><w:t>请于</w:t></w:r><w:r><w:rPr><w:b/></w:rPr><w:t>周五前</w:t></w:r><w:r><w:t>报名。</w:t></w:r></w:p>
            <w:p><w:pPr><w:numPr><w:ilvl w:val="0"/><w:numId w:val="1"/></w:numPr></w:pPr><w:r><w:t>第一项</w:t></w:r></w:p>
            <w:p><w:pPr><w:numPr><w:ilvl w:val="1"/><w:numId w:val="2"/></w:numPr></w:pPr><w:r><w:t>子项</w:t></w:r></w:p>
            <w:tbl>
              <w:tr><w:tc><w:p><w:r><w:t>姓名</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>学号</w:t></w:r></w:p></w:tc></w:tr>
              <w:tr><w:tc><w:p><w:r><w:t>张三</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>10086</w:t></w:r></w:p></w:tc></w:tr>
            </w:tbl>
            <w:p><w:r><w:br w:type="page"/></w:r></w:p>
            <w:p><w:r><w:drawing><wp:extent cx="952500" cy="476250"/>
              <a:blip r:embed="rId9"/></w:drawing></w:r></w:p>
          </w:body>
        </w:document>
    """.trimIndent()

    private val numberingXml = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <w:numbering xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
          <w:abstractNum w:abstractNumId="10"><w:lvl w:ilvl="0"><w:numFmt w:val="decimal"/></w:lvl></w:abstractNum>
          <w:abstractNum w:abstractNumId="20"><w:lvl w:ilvl="0"><w:numFmt w:val="bullet"/></w:lvl></w:abstractNum>
          <w:num w:numId="1"><w:abstractNumId w:val="10"/></w:num>
          <w:num w:numId="2"><w:abstractNumId w:val="20"/></w:num>
        </w:numbering>
    """.trimIndent()

    private val documentRels = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
          <Relationship Id="rId9" Target="media/image1.png" Type="image"/>
        </Relationships>
    """.trimIndent()

    private val coreXml = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <cp:coreProperties xmlns:cp="x" xmlns:dc="http://purl.org/dc/elements/1.1/">
          <dc:title>关于第二课堂活动的通知</dc:title>
        </cp:coreProperties>
    """.trimIndent()

    private fun docxBytes(): ByteArray = zip(
        "word/document.xml" to documentXml,
        "word/numbering.xml" to numberingXml,
        "word/_rels/document.xml.rels" to documentRels,
        "word/media/image1.png" to "PNGDATA",
        "docProps/core.xml" to coreXml,
    )

    @Test
    fun readsDocxStructure() {
        val document = DocxParser.parse(docxBytes())

        assertEquals("关于第二课堂活动的通知", document.title)

        val heading = document.blocks.filterIsInstance<WordBlock.Heading>().single()
        assertEquals(1, heading.level)
        assertEquals("活动通知", heading.text)

        val paragraph = document.blocks.filterIsInstance<WordBlock.Paragraph>().first()
        assertEquals("请于周五前报名。", paragraph.text)
        // 中间那段是加粗的，必须单独成为一个 run
        assertTrue(paragraph.runs.any { it.bold && it.text == "周五前" })
        assertFalse(paragraph.runs.first { it.text == "请于" }.bold)
    }

    @Test
    fun readsDocxListsWithNumberingFormat() {
        val items = DocxParser.parse(docxBytes()).blocks.filterIsInstance<WordBlock.ListItem>()

        assertEquals(2, items.size)
        // numbering.xml 里 numId=1 是 decimal → 有序；numId=2 是 bullet → 无序
        assertTrue(items[0].ordered)
        assertEquals(0, items[0].level)
        assertFalse(items[1].ordered)
        assertEquals(1, items[1].level)
    }

    @Test
    fun readsDocxTable() {
        val table = DocxParser.parse(docxBytes()).blocks.filterIsInstance<WordBlock.Table>().single()
        assertEquals(listOf(listOf("姓名", "学号"), listOf("张三", "10086")), table.rows)
    }

    @Test
    fun readsDocxPageBreakAndImage() {
        val blocks = DocxParser.parse(docxBytes()).blocks

        assertTrue(blocks.contains(WordBlock.PageBreak))

        val image = blocks.filterIsInstance<WordBlock.Image>().single()
        assertEquals("PNGDATA", String(image.bytes))
        // wp:extent 是 EMU：952500 / 9525 = 100px
        assertEquals(100, image.widthPx)
        assertEquals(50, image.heightPx)
    }

    @Test
    fun plainTextJoinsEverything() {
        val text = DocxParser.parse(docxBytes()).plainText()
        assertTrue(text.contains("活动通知"))
        assertTrue(text.contains("姓名\t学号"))
        assertTrue(text.contains("[图片]"))
    }

    @Test
    fun rejectsNonDocxBytes() {
        assertThrows(WordParseException::class.java) { DocxParser.parse("not a zip".toByteArray()) }
    }

    // ── xlsx ──────────────────────────────────────────────────────────────

    private val workbookXml = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"
                  xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
          <sheets>
            <sheet name="报名表" sheetId="1" r:id="rId1"/>
            <sheet name="统计" sheetId="2" r:id="rId2"/>
          </sheets>
        </workbook>
    """.trimIndent()

    private val workbookRels = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
          <Relationship Id="rId1" Target="worksheets/sheet1.xml" Type="worksheet"/>
          <Relationship Id="rId2" Target="worksheets/sheet2.xml" Type="worksheet"/>
        </Relationships>
    """.trimIndent()

    private val sharedStringsXml = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
          <si><t>姓名</t></si>
          <si><r><t>张</t></r><r><t>三</t></r></si>
          <si><t>注音</t><rPh><t>zhu yin</t></rPh></si>
        </sst>
    """.trimIndent()

    /** cellXfs[1] 指向内建日期格式 14（即 yyyy-MM-dd）。 */
    private val stylesXml = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
          <cellXfs count="3"><xf numFmtId="0"/><xf numFmtId="14"/><xf numFmtId="0"/></cellXfs>
        </styleSheet>
    """.trimIndent()

    private val sheet1Xml = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
          <sheetData>
            <row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1"><v>42.0</v></c><c r="C1" s="1"><v>44927</v></c></row>
            <row r="2"><c r="A2" t="s"><v>1</v></c><c r="B2" t="b"><v>1</v></c><c r="C2" t="s"><v>2</v></c></row>
          </sheetData>
        </worksheet>
    """.trimIndent()

    private val sheet2Xml = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
          <sheetData><row r="1"><c r="A1" t="inlineStr"><is><t>合计</t></is></c></row></sheetData>
        </worksheet>
    """.trimIndent()

    private fun xlsxBytes(): ByteArray = zip(
        "xl/workbook.xml" to workbookXml,
        "xl/_rels/workbook.xml.rels" to workbookRels,
        "xl/sharedStrings.xml" to sharedStringsXml,
        "xl/styles.xml" to stylesXml,
        "xl/worksheets/sheet1.xml" to sheet1Xml,
        "xl/worksheets/sheet2.xml" to sheet2Xml,
        "docProps/core.xml" to coreXml,
    )

    @Test
    fun readsAllSheetsInWorkbookOrder() {
        val document = XlsxParser.parse(xlsxBytes())
        assertEquals(listOf("报名表", "统计"), document.sheets.map { it.name })
        assertEquals("关于第二课堂活动的通知", document.title)
    }

    @Test
    fun resolvesSharedStringsRunsAndSkipsPhonetic() {
        val sheet = XlsxParser.parse(xlsxBytes()).sheets[0]

        // A1 = 共享字符串 0；A2 = 由两个 run 拼起来的"张三"
        assertEquals("姓名", sheet.rows[0][0])
        assertEquals("张三", sheet.rows[1][0])
        // C2 引用的共享串带 <rPh> 注音，注音不能被拼进来
        assertEquals("注音", sheet.rows[1][2])
    }

    @Test
    fun formatsNumbersBooleansAndDates() {
        val sheet = XlsxParser.parse(xlsxBytes()).sheets[0]

        // "42.0" → "42"（Excel 里整数不该显示小数点）
        assertEquals("42", sheet.rows[0][1])
        // 布尔按 Excel 的写法
        assertEquals("TRUE", sheet.rows[1][1])
        // s="1" 指向内建日期格式 14 → 序列号 44927 就是 2023-01-01
        assertEquals("2023-01-01", sheet.rows[0][2])
    }

    @Test
    fun padsRowsToRectangle() {
        val sheet = XlsxParser.parse(xlsxBytes()).sheets[0]

        assertEquals(3, sheet.columnCount)
        assertEquals(2, sheet.rowCount)
        sheet.rows.forEach { assertEquals(3, it.size) }
    }

    @Test
    fun readsInlineStringCells() {
        val sheet = XlsxParser.parse(xlsxBytes()).sheets[1]
        assertEquals("合计", sheet.rows[0][0])
        assertFalse(sheet.isEmpty)
    }

    @Test
    fun rejectsNonXlsxBytes() {
        assertThrows(SheetParseException::class.java) { XlsxParser.parse("not a zip".toByteArray()) }
    }

    // ── 夹具工具 ──────────────────────────────────────────────────────────

    /** 用给定的条目拼一个 ZIP。 */
    private fun zip(vararg entries: Pair<String, Any>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(if (content is ByteArray) content else content.toString().toByteArray())
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }
}
