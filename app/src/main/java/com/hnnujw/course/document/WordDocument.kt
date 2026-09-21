package com.hnnujw.course.document

import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipFile

/**
 * `.docx` 的极简阅读器 —— 第二课堂活动附件的**内置查看器**用。
 *
 * ## 为什么自己解析而不是引库
 *
 * `.docx` 就是一个 ZIP，正文是 `word/document.xml`。用 JDK 自带的 `java.util.zip`
 * 加 `javax.xml.parsers`（Android 上同样可用）就能读出标题、段落、加粗、列表、表格与图片。
 * 而 Apache POI 在 Android 上要拖进 `xmlbeans`（几十 MB）并且依赖 `javax.xml.stream`，
 * 为了"看一个活动通知附件"付这个体积不划算。
 *
 * ## 覆盖范围
 *
 * | 元素 | 处理 |
 * | --- | --- |
 * | `w:p` 段落 | ✅ 含 `w:br` 换行、`w:tab` 制表 |
 * | `w:pStyle` 标题 | ✅ `Heading1..9` / `标题 1..9` |
 * | `w:numPr` 列表 | ✅ 序号 / 项目符号与层级（读 `numbering.xml` 判 numFmt） |
 * | `w:tbl` 表格 | ✅ 单元格文本；不做跨行跨列还原 |
 * | `w:drawing` 图片 | ✅ 走 rels 取 `word/media/` 里的图，尺寸取 `wp:extent` |
 * | 批注 / 修订 / 页眉页脚 / 域代码 / 样式表 | ❌ 忽略（只看正文可读性） |
 *
 * 结果是**纯数据**（[WordDocument] / [WordBlock]），图片只带原始字节、不碰 Bitmap，
 * 所以整套解析能在 JVM 单测里跑。
 */

/** 一段文字的统一格式属性。 */
data class WordRun(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    /** 字号，单位半磅（`w:sz` 原值）；0 表示未指定，界面用正文默认值。 */
    val halfPointSize: Int = 0,
)

sealed interface WordBlock {
    /** 标题。[level] 1..9。 */
    data class Heading(val level: Int, val runs: List<WordRun>) : WordBlock {
        val text: String get() = runs.joinToString("") { it.text }
    }

    /** 普通段落。 */
    data class Paragraph(val runs: List<WordRun>) : WordBlock {
        val text: String get() = runs.joinToString("") { it.text }
    }

    /** 列表项。[ordered] 为真时界面画序号，否则画圆点。 */
    data class ListItem(
        val runs: List<WordRun>,
        val level: Int,
        val ordered: Boolean,
    ) : WordBlock {
        val text: String get() = runs.joinToString("") { it.text }
    }

    /** 表格。 */
    data class Table(val rows: List<List<String>>) : WordBlock

    /** 内嵌图片。[widthPx] / [heightPx] 为 0 时由界面按原始比例缩放。 */
    class Image(
        val bytes: ByteArray,
        val widthPx: Int,
        val heightPx: Int,
    ) : WordBlock

    /** 显式分页符；界面画一条分隔即可。 */
    data object PageBreak : WordBlock
}

/** 解析结果。 */
data class WordDocument(
    val blocks: List<WordBlock> = emptyList(),
    /** `docProps/core.xml` 的 `dc:title`，取不到为空串。 */
    val title: String = "",
) {
    val isEmpty: Boolean get() = blocks.isEmpty()

    /** 纯文本形态，用于摘要与"文档太长"时的降级展示。 */
    fun plainText(): String = blocks.joinToString("\n") { block ->
        when (block) {
            is WordBlock.Heading -> block.text
            is WordBlock.Paragraph -> block.text
            is WordBlock.ListItem -> block.text
            is WordBlock.Table -> block.rows.joinToString("\n") { it.joinToString("\t") }
            is WordBlock.Image -> "[图片]"
            WordBlock.PageBreak -> ""
        }
    }
}

/** `.docx` 解析失败（不是 ZIP、缺 document.xml、XML 损坏…）。 */
class WordParseException(message: String, cause: Throwable? = null) : Exception(message, cause)

object DocxParser {

    /**
     * 解析 `.docx`。
     *
     * 不做流式处理：活动附件通常几十 KB 到几 MB，全读进内存可以接受，
     * 换来的是能随机访问 ZIP 条目（图片要等正文遇到引用时再取）。
     */
    fun parse(file: File): WordDocument {
        val bytes = try {
            file.readBytes()
        } catch (e: Exception) {
            throw WordParseException("读取文档失败", e)
        }
        return parse(bytes)
    }

    fun parse(bytes: ByteArray): WordDocument {
        val temp = try {
            File.createTempFile("docx-", ".zip").apply { writeBytes(bytes) }
        } catch (e: Exception) {
            throw WordParseException("无法创建临时文件", e)
        }
        return try {
            ZipFile(temp).use { zip -> DocxReader(zip).read() }
        } catch (e: WordParseException) {
            throw e
        } catch (e: Exception) {
            throw WordParseException("这不是一个有效的 Word 文档", e)
        } finally {
            temp.delete()
        }
    }
}

/**
 * 单次解析的读取器。
 *
 * 做成**实例**而不是 `object` 的全局字段：解析状态（ZIP 句柄、图片 rels、编号表）
 * 跟着一次解析走，既天然线程安全，也不会因为某次解析异常而把状态漏给下一次。
 */
private class DocxReader(private val zip: ZipFile) {

    private val imageRelations: Map<String, String> by lazy {
        parseRelationships(zip.readEntry("word/_rels/document.xml.rels"))
    }

    private val numberingFormats: Map<Int, String> by lazy {
        parseNumbering(zip.readEntry("word/numbering.xml"))
    }

    fun read(): WordDocument {
        val xml = zip.readEntry("word/document.xml")
            ?: throw WordParseException("这不是一个有效的 Word 文档（缺少 word/document.xml）")
        val document = parseXml(xml) ?: throw WordParseException("Word 文档内容损坏，无法解析")
        val body = document.documentElement?.let { childElement(it, "body") }
            ?: throw WordParseException("Word 文档缺少正文")

        val blocks = ArrayList<WordBlock>()
        for (node in childElements(body)) {
            when (node.tag) {
                "p" -> appendParagraph(node, blocks)
                "tbl" -> blocks += WordBlock.Table(parseTable(node))
                else -> Unit // sectPr 等节属性忽略
            }
        }
        return WordDocument(blocks = blocks, title = readCoreTitle(zip))
    }

    // ── 段落 ──────────────────────────────────────────────────────────────

    private fun appendParagraph(paragraph: Element, out: MutableList<WordBlock>) {
        val properties = childElement(paragraph, "pPr")
        val runs = parseRuns(paragraph, out)

        if (hasPageBreak(paragraph) && runs.all { it.text.isBlank() }) {
            // 分页符段落里只有 w:br（没有文字），上面会把它解析成一个换行 run，
            // 所以这里不能要求 runs.isEmpty() —— 否则分页符整段被丢掉。
            out += WordBlock.PageBreak
            return
        }
        // 空段落只当作间隙，不产出空块（否则整页被行距填满）
        if (runs.all { it.text.isBlank() }) return

        val numPr = properties?.let { childElement(it, "numPr") }
        if (numPr != null) {
            val numId = childElement(numPr, "numId")?.intAttr("w:val") ?: 0
            val level = childElement(numPr, "ilvl")?.intAttr("w:val") ?: 0
            out += WordBlock.ListItem(
                runs = runs,
                level = level.coerceIn(0, 8),
                // 只有明确是 bullet/none 才画圆点，其余（decimal、chineseCounting…）当编号
                ordered = numberingFormats[numId]?.let { it != "bullet" && it != "none" } ?: false,
            )
            return
        }

        val style = properties?.let { childElement(it, "pStyle") }?.attr("w:val").orEmpty()
        val headingLevel = HEADING_STYLE.find(style)?.groupValues?.get(1)?.toIntOrNull()
        out += if (headingLevel != null) {
            WordBlock.Heading(level = headingLevel, runs = runs)
        } else {
            WordBlock.Paragraph(runs)
        }
    }

    /** 收集段落里的文字与图片；图片会按出现顺序直接插进 [out]。 */
    private fun parseRuns(paragraph: Element, out: MutableList<WordBlock>): List<WordRun> {
        val runs = ArrayList<WordRun>()
        val buffer = StringBuilder()
        var bold = false
        var italic = false
        var underline = false
        var size = 0

        fun flush() {
            if (buffer.isNotEmpty()) {
                runs += WordRun(buffer.toString(), bold, italic, underline, size)
                buffer.setLength(0)
            }
        }

        for (node in childElements(paragraph)) {
            when (node.tag) {
                "r" -> {
                    val props = childElement(node, "rPr")
                    bold = props?.hasChild("b") == true
                    italic = props?.hasChild("i") == true
                    underline = props?.hasChild("u") == true
                    size = props?.let { childElement(it, "sz") }?.intAttr("w:val") ?: 0
                    for (child in childElements(node)) {
                        when (child.tag) {
                            "t" -> buffer.append(child.textContent)
                            "br" -> buffer.append('\n')
                            "tab" -> buffer.append('\t')
                            "drawing", "pict" -> {
                                flush()
                                readImage(child)?.let { out += it }
                            }
                            else -> Unit
                        }
                    }
                    flush()
                }
                // 超链接里还是 r，递归取文字
                "hyperlink" -> runs += parseRuns(node, out)
            }
        }
        flush()
        return runs.filter { it.text.isNotEmpty() }
    }

    private fun hasPageBreak(paragraph: Element): Boolean {
        val breaks = paragraph.getElementsByTagName("w:br")
        for (i in 0 until breaks.length) {
            val node = breaks.item(i) as? Element ?: continue
            if (node.attr("w:type") == "page") return true
        }
        return false
    }

    // ── 表格 ──────────────────────────────────────────────────────────────

    private fun parseTable(table: Element): List<List<String>> = childElements(table)
        .filter { it.matchesTag("tr") }
        .map { row ->
            childElements(row).filter { it.matchesTag("tc") }.map { cell -> cellText(cell) }
        }

    private fun cellText(cell: Element): String = childElements(cell)
        .filter { it.matchesTag("p") }
        .joinToString("\n") { rawText(it) }
        .trim()

    /** 只取文字（含换行与制表），忽略图片 —— 表格里塞图片的情况极少。 */
    private fun rawText(element: Element): String {
        val builder = StringBuilder()
        collectRawText(element, builder)
        return builder.toString()
    }

    private fun collectRawText(element: Element, builder: StringBuilder) {
        val nodes = element.childNodes
        for (i in 0 until nodes.length) {
            when (val node = nodes.item(i)) {
                is Element -> when (node.tag) {
                    "t" -> builder.append(node.textContent)
                    "br" -> builder.append('\n')
                    "tab" -> builder.append('\t')
                    else -> collectRawText(node, builder)
                }
                else -> Unit
            }
        }
    }

    // ── 图片 ──────────────────────────────────────────────────────────────

    private fun readImage(container: Element): WordBlock.Image? {
        val blip = container.getElementsByTagName("a:blip").item(0) as? Element ?: return null
        val relId = blip.attr("r:embed").ifBlank { blip.attr("embed") }
        if (relId.isBlank()) return null
        val target = imageRelations[relId] ?: return null
        val bytes = zip.readEntry(resolveMediaPath(target)) ?: return null

        val extent = container.getElementsByTagName("wp:extent").item(0) as? Element
        val widthPx = extent?.longAttr("cx")?.let { (it / EMU_PER_PIXEL).toInt() } ?: 0
        val heightPx = extent?.longAttr("cy")?.let { (it / EMU_PER_PIXEL).toInt() } ?: 0
        return WordBlock.Image(bytes, widthPx, heightPx)
    }

    /** rels 里的目标是相对 `word/` 的路径，也兼容绝对写法。 */
    private fun resolveMediaPath(target: String): String {
        val cleaned = target.removePrefix("/")
        return when {
            cleaned.startsWith("word/") -> cleaned
            cleaned.startsWith("../") -> cleaned.removePrefix("../")
            else -> "word/$cleaned"
        }
    }

    // ── 关系 / 编号 / 元数据 ──────────────────────────────────────────────

    private fun parseRelationships(xml: ByteArray?): Map<String, String> {
        if (xml == null) return emptyMap()
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

    /**
     * `numbering.xml` → `numId` 对应的第 0 级 `numFmt`。
     *
     * 要走 `w:num → w:abstractNumId → w:abstractNum/w:lvl/w:numFmt` 两级间接。
     * 只取第 0 级：通知类文档的列表几乎没有二级。
     * 读不出来就返回空表 → 列表按项目符号渲染（比误画成"1."安全）。
     */
    private fun parseNumbering(xml: ByteArray?): Map<Int, String> {
        if (xml == null) return emptyMap()
        val document = parseXml(xml) ?: return emptyMap()

        val abstractFormats = HashMap<Int, String>()
        val abstracts = document.getElementsByTagName("w:abstractNum")
        for (i in 0 until abstracts.length) {
            val node = abstracts.item(i) as? Element ?: continue
            val level0 = childElements(node).firstOrNull {
                it.matchesTag("lvl") && it.intAttr("w:ilvl") == 0
            } ?: continue
            childElement(level0, "numFmt")?.attr("w:val")?.let { abstractFormats[node.intAttr("w:abstractNumId")] = it }
        }

        val result = HashMap<Int, String>()
        val nums = document.getElementsByTagName("w:num")
        for (i in 0 until nums.length) {
            val node = nums.item(i) as? Element ?: continue
            val abstractId = childElement(node, "abstractNumId")?.intAttr("w:val") ?: continue
            abstractFormats[abstractId]?.let { result[node.intAttr("w:numId")] = it }
        }
        return result
    }

    private fun readCoreTitle(zip: ZipFile): String {
        val xml = zip.readEntry("docProps/core.xml") ?: return ""
        val document = parseXml(xml) ?: return ""
        val titles = document.getElementsByTagName("dc:title")
        return if (titles.length > 0) titles.item(0).textContent.orEmpty().trim() else ""
    }

    private companion object {
        /** 标题样式：兼容英文 `Heading1` 与中文 `标题 1`。 */
        val HEADING_STYLE = Regex("""^(?:heading|标题)\s*([1-9])$""", RegexOption.IGNORE_CASE)

        /** EMU → 像素。OOXML 里 1 英寸 = 914400 EMU，Android 基准密度 160dpi。 */
        const val EMU_PER_PIXEL = 9525.0
    }
}

// ── 共用工具 ──────────────────────────────────────────────────────────────

/**
 * 解析 XML。
 *
 * **刻意关掉 DTD 与外部实体**：附件来自外部系统，不能让它把本地文件读进来（XXE）。
 * `disallow-doctype-decl` 一开，带 DTD 的文档直接失败 —— 正常 OOXML 从不带 DTD，
 * 所以这个限制不会误伤。Android 与 JVM 都走这一套 `javax.xml`。
 */
internal fun parseXml(bytes: ByteArray): Document? = try {
    val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance()
    runCatching { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
    runCatching { factory.setFeature("http://xml.org/sax/features/external-general-entities", false) }
    runCatching { factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
    factory.isNamespaceAware = false
    factory.isExpandEntityReferences = false
    factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
} catch (e: Exception) {
    null
}

/** 取 ZIP 条目字节；条目不存在或读失败一律返回 null，由调用方决定怎么降级。 */
internal fun ZipFile.readEntry(name: String): ByteArray? = try {
    getEntry(name)?.let { entry -> getInputStream(entry).use { it.readBytes() } }
} catch (e: Exception) {
    null
}

/** 直接子元素（跳过空白文本节点）。 */
internal fun childElements(parent: Element): List<Element> {
    val result = ArrayList<Element>()
    val nodes = parent.childNodes
    for (i in 0 until nodes.length) {
        (nodes.item(i) as? Element)?.let { result += it }
    }
    return result
}

/** 去掉命名空间前缀的标签名。关掉命名空间感知后，`w:body` 的 tagName 就是 `w:body`。 */
internal val Element.tag: String get() = tagName.substringAfterLast(':')

/**
 * 标签是否匹配。
 *
 * **一律忽略命名空间前缀** —— 前缀是生成器的自由选择：Word 写 `w:body`，
 * 也有生成器直接挂默认命名空间写成 `body`，两者是同一个元素。精确比较 tagName
 * 会让后者整篇读不出来（这正是 docx 夹具一开始全部失败的原因）。
 */
internal fun Element.matchesTag(tag: String): Boolean =
    this.tag == tag.substringAfterLast(':')

internal fun childElement(parent: Element, tagName: String): Element? =
    childElements(parent).firstOrNull { it.matchesTag(tagName) }

internal fun Element.hasChild(tagName: String): Boolean =
    childElements(this).any { it.matchesTag(tagName) }

/**
 * 取属性。
 *
 * 必须同时试 `前缀:名称` 与 `名称`：这里关掉了命名空间感知，DOM 会把 `w:val`
 * 原样当属性名，而有的生成器写成无前缀的 `val`。两种都试才稳。
 */
internal fun Element.attr(name: String): String {
    val direct = getAttribute(name)
    if (direct.isNotEmpty()) return direct
    return getAttribute(name.substringAfterLast(':'))
}

internal fun Element.intAttr(name: String): Int = attr(name).trim().toDoubleOrNull()?.toInt() ?: 0

internal fun Element.longAttr(name: String): Long? = attr(name).trim().toLongOrNull()
