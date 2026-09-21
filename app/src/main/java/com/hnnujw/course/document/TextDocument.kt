package com.hnnujw.course.document

import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * 一份纯文本附件。
 *
 * [charsetName] 会显示在界面角落："UTF-8" / "GBK" —— 教务与二课系统里
 * 导出的 txt 大概率是 GBK，直接按 UTF-8 读会得到一串问号，所以这里做一次判定。
 * 判错的代价只是显示乱码，不会崩，因此判定策略可以激进一点（优先 UTF-8）。
 */
data class TextDocument(
    val text: String,
    /** 实际用于解码的字符集名，展示给用户看。 */
    val charsetName: String = "UTF-8",
    /** 因为超过 [TextParser.MAX_CHARS] 被截断。 */
    val truncated: Boolean = false,
    /** 原始字节数，用来显示文件大小。 */
    val byteSize: Long = 0L,
) {
    val isEmpty: Boolean get() = text.isEmpty()

    val lineCount: Int get() = if (text.isEmpty()) 0 else text.count { it == '\n' } + 1
}

/** 文本读取失败（文件不存在、没权限…）。 */
class TextParseException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * 文本解码。
 *
 * 字符集判定顺序（先 BOM，再严格 UTF-8，再 GBK 兜底）：
 * 严格 UTF-8 试解能挡掉绝大多数情况 —— UTF-8 的字节序列约束很紧，一段 GBK 中文
 * 很难恰好也是合法 UTF-8。反过来先试 GBK 则会把 UTF-8 文本解成乱码。
 */
object TextParser {

    /**
     * 字符上限。
     *
     * 内置查看器把整段文本塞进一个可选中的 `Text`，几十万字符之后滚动与
     * 选区都会开始卡；活动附件里没有正经文本文件需要这么长，截断并明说更合适。
     */
    const val MAX_CHARS = 400_000

    /** 试解 UTF-8 时最多看这么多字节 —— 大文件全量试解等于白读两遍。 */
    private const val SNIFF_SIZE = 64 * 1024

    fun parse(file: File): TextDocument = try {
        parse(file.readBytes(), file.length())
    } catch (e: TextParseException) {
        throw e
    } catch (e: Exception) {
        throw TextParseException("读取文本失败", e)
    }

    fun parse(bytes: ByteArray, byteSize: Long = bytes.size.toLong()): TextDocument {
        val (charset, bomLength) = detectCharset(bytes)
        val body = if (bomLength > 0 && bomLength <= bytes.size) {
            bytes.copyOfRange(bomLength, bytes.size)
        } else {
            bytes
        }
        val decoded = decode(body, charset)
        val truncated = decoded.length > MAX_CHARS
        return TextDocument(
            text = if (truncated) decoded.substring(0, MAX_CHARS) else decoded,
            charsetName = charset.name(),
            truncated = truncated,
            byteSize = byteSize,
        )
    }

    /** @return 选中的字符集，以及要跳过的 BOM 长度（0 表示没有 BOM）。 */
    fun detectCharset(bytes: ByteArray): Pair<Charset, Int> {
        if (bytes.size >= 3 &&
            (bytes[0].toInt() and 0xFF) == 0xEF &&
            (bytes[1].toInt() and 0xFF) == 0xBB &&
            (bytes[2].toInt() and 0xFF) == 0xBF
        ) {
            return Charsets.UTF_8 to 3
        }
        if (bytes.size >= 2 && (bytes[0].toInt() and 0xFF) == 0xFF && (bytes[1].toInt() and 0xFF) == 0xFE) {
            return Charsets.UTF_16LE to 2
        }
        if (bytes.size >= 2 && (bytes[0].toInt() and 0xFF) == 0xFE && (bytes[1].toInt() and 0xFF) == 0xFF) {
            return Charsets.UTF_16BE to 2
        }
        val sniffLength = minOf(bytes.size, SNIFF_SIZE)
        if (isValidUtf8(bytes, sniffLength)) return Charsets.UTF_8 to 0
        gbk()?.let { return it to 0 }
        return Charsets.ISO_8859_1 to 0
    }

    /** GBK 在 Android 与桌面 JDK 上都有；万一没有就退回 ISO-8859-1（至少不抛）。 */
    private fun gbk(): Charset? = runCatching { Charset.forName("GBK") }.getOrNull()

    private fun isValidUtf8(bytes: ByteArray, length: Int): Boolean = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes, 0, length))
        true
    } catch (e: CharacterCodingException) {
        false
    }

    /** 解码时一律用 REPLACE：个别坏字节不该让整份文件读不出来。 */
    private fun decode(bytes: ByteArray, charset: Charset): String = try {
        charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (e: Exception) {
        String(bytes, charset)
    }
}
