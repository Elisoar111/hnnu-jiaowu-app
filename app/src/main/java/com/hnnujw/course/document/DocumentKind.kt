package com.hnnujw.course.document

import java.io.File
import java.util.zip.ZipFile

/**
 * 内置查看器能渲染的类型。
 *
 * 比 [com.hnnujw.course.secondclass.SecondClassAttachmentKind] 更"面向渲染"：
 * 那个是列表里挑图标用的粗分类，这个决定**用哪个渲染器**。
 * [Excel] 同时覆盖 `.xlsx` 与 `.csv` —— 两者最终都变成一张文字网格。
 */
enum class DocumentKind(val label: String) {
    Word("Word 文档"),
    Excel("Excel 表格"),
    Pdf("PDF 文档"),
    Image("图片"),
    Text("文本"),
    Unknown("文件"),
    ;

    /** 内置查看器有没有对应的渲染器。[Unknown] 只能交给外部应用。 */
    val renderableInApp: Boolean get() = this != Unknown
}

/**
 * 按文件**内容**判断类型。
 *
 * 为什么不直接信站点的 `attachmentType`：实测它取 `0` 或 `6` 时都表示"没给"，
 * 而且附件可能被改名（`.docx` 存成 `.doc`）。所以下载拿到真文件后一律再嗅一次：
 *
 * 1. 魔数 —— `%PDF-`、PNG / JPEG / GIF / WebP / BMP；
 * 2. ZIP 就进去看目录结构 —— `word/document.xml` 是 Word，`xl/workbook.xml` 是 Excel；
 *    （`.docx` / `.xlsx` 本质就是 ZIP，这一条比扩展名可靠得多）
 * 3. 剩下按"是不是文本"判断，扩展名只用来区分文本要不要当表格（`.csv`）。
 *
 * 认不出来就是 [DocumentKind.Unknown]，由界面引导用户用外部应用打开 ——
 * 旧版 `.doc` / `.xls`（OLE2 复合文档）会走到这里，内置查看器本来就不支持。
 */
object DocumentSniffer {

    /** 读这么多个字节做判断；图片魔数最多用到前 12 字节，文本判定多采一点更稳。 */
    private const val HEAD_SIZE = 512

    fun sniff(file: File, nameHint: String? = null): DocumentKind {
        val head = readHead(file, HEAD_SIZE)
        magicKind(head)?.let { return it }
        if (hasTextBom(head)) return DocumentKind.Text
        if (isZip(head)) {
            // 是 ZIP：是 docx/xlsx 就认，否则（pptx、普通 zip）不猜
            return zipKind(file) ?: DocumentKind.Unknown
        }
        val byExtension = extensionKind(nameHint ?: file.name)
        return when {
            // 内容确实是文本，且扩展名说它是表格 —— .csv 走网格渲染
            byExtension == DocumentKind.Excel && looksLikeText(head) -> DocumentKind.Excel
            looksLikeText(head) -> DocumentKind.Text
            else -> DocumentKind.Unknown
        }
    }

    /** 只按扩展名判断。用于还没下载完就先决定图标/入口。 */
    fun extensionKind(name: String): DocumentKind = when (name.substringAfterLast('.', "").lowercase()) {
        "docx" -> DocumentKind.Word
        "xlsx", "csv" -> DocumentKind.Excel
        "pdf" -> DocumentKind.Pdf
        "png", "jpg", "jpeg", "gif", "webp", "bmp", "heic", "heif" -> DocumentKind.Image
        "txt", "md", "markdown", "json", "xml", "log", "ini", "yml", "yaml" -> DocumentKind.Text
        // 旧版二进制格式：站点上确实有，但内置查看器不解析，交给外部应用
        "doc", "xls", "ppt", "pptx" -> DocumentKind.Unknown
        else -> DocumentKind.Unknown
    }

    // ── 魔数 ────────────────────────────────────────────────────────────────

    private fun magicKind(head: ByteArray): DocumentKind? = when {
        startsWith(head, 0x25, 0x50, 0x44, 0x46) -> DocumentKind.Pdf          // %PDF
        startsWith(head, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) -> DocumentKind.Image  // PNG
        startsWith(head, 0xFF, 0xD8, 0xFF) -> DocumentKind.Image              // JPEG
        startsWith(head, 0x47, 0x49, 0x46, 0x38) -> DocumentKind.Image        // GIF8
        startsWith(head, 0x42, 0x4D) -> DocumentKind.Image                    // BM (BMP)
        // WebP: "RIFF" .... "WEBP"
        startsWith(head, 0x52, 0x49, 0x46, 0x46) && startsWith(head, 0x57, 0x45, 0x42, 0x50, offset = 8) ->
            DocumentKind.Image
        else -> null
    }

    /** `PK\x03\x04`（普通）、`PK\x05\x06`（空档案）、`PK\x07\x08`（跨卷）。 */
    private fun isZip(head: ByteArray): Boolean {
        if (head.size < 4) return false
        if (head[0] != 0x50.toByte() || head[1] != 0x4B.toByte()) return false
        val third = head[2].toInt() and 0xFF
        val fourth = head[3].toInt() and 0xFF
        return third in intArrayOf(3, 5, 7) && fourth in intArrayOf(4, 6, 8)
    }

    private fun zipKind(file: File): DocumentKind? = try {
        ZipFile(file).use { zip ->
            when {
                zip.getEntry("word/document.xml") != null -> DocumentKind.Word
                zip.getEntry("xl/workbook.xml") != null -> DocumentKind.Excel
                else -> null
            }
        }
    } catch (e: Exception) {
        // 目录损坏的 ZIP：当作认不出来
        null
    }

    private fun hasTextBom(head: ByteArray): Boolean =
        (head.size >= 3 && startsWith(head, 0xEF, 0xBB, 0xBF)) ||
            (head.size >= 2 && startsWith(head, 0xFF, 0xFE)) ||
            (head.size >= 2 && startsWith(head, 0xFE, 0xFF))

    /**
     * 这段字节像不像文本。
     *
     * 判据保守一点：不能含 NUL，控制字符占比要低。二进制里 NUL 几乎必然成片出现，
     * 所以这条足够把 `.doc` / `.xls`（OLE2 头是 `D0 CF 11 E0 A1 B1 1A E1`）挡在外面。
     */
    private fun looksLikeText(head: ByteArray): Boolean {
        if (head.isEmpty()) return false
        var control = 0
        for (byte in head) {
            val value = byte.toInt() and 0xFF
            if (value == 0) return false
            // 允许 \t \n \r \f，其余 C0 控制字符算异常
            if (value < 0x20 && value != 0x09 && value != 0x0A && value != 0x0D && value != 0x0C) control++
        }
        return control * 20 <= head.size   // 控制在 5% 以内
    }

    private fun startsWith(head: ByteArray, vararg bytes: Int, offset: Int = 0): Boolean {
        if (head.size < offset + bytes.size) return false
        for (i in bytes.indices) {
            if ((head[offset + i].toInt() and 0xFF) != bytes[i]) return false
        }
        return true
    }

    private fun readHead(file: File, size: Int): ByteArray = try {
        file.inputStream().use { stream ->
            val buffer = ByteArray(size)
            var read = 0
            while (read < size) {
                val count = stream.read(buffer, read, size - read)
                if (count <= 0) break
                read += count
            }
            if (read == size) buffer else buffer.copyOf(read)
        }
    } catch (e: Exception) {
        ByteArray(0)
    }
}
