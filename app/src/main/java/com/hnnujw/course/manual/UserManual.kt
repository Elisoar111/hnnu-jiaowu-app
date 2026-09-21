package com.hnnujw.course.manual

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 行内片段：手册正文的最小单位。
 *
 * 与 Markdown 的对应关系：`**粗体**` → [bold]、`` `代码` `` → [code]、
 * `*斜体*` → [italic]、`[文字](链接)` → [link]。
 */
data class ManualSpan(
    val text: String,
    val bold: Boolean = false,
    val code: Boolean = false,
    val italic: Boolean = false,
    val link: String? = null,
)

/** 列表项：一行正文，外加可选的子项（手册里只有一层缩进）。 */
data class ManualItem(
    val spans: List<ManualSpan>,
    val sub: List<List<ManualSpan>> = emptyList(),
)

/** 手册的一个内容块。类型来自构建期解析 Markdown 的结果。 */
sealed interface ManualBlock {
    data class Heading(val level: Int, val text: String, val anchor: String) : ManualBlock
    data class Paragraph(val spans: List<ManualSpan>) : ManualBlock
    data class Bullets(val items: List<ManualItem>) : ManualBlock
    data class Steps(val items: List<ManualItem>, val start: Int) : ManualBlock
    data class Quote(val paragraphs: List<List<ManualSpan>>) : ManualBlock
    data class Table(
        val head: List<List<ManualSpan>>,
        val rows: List<List<List<ManualSpan>>>,
    ) : ManualBlock

    data object Divider : ManualBlock
}

/** 一章（对应 Markdown 的 h2）。 */
data class ManualChapter(
    val id: String,
    val title: String,
    val blocks: List<ManualBlock>,
)

/**
 * 应用内用户手册。
 *
 * 内容不是硬编码在代码里，而是构建期由 `scripts/build_manual.py` 把仓库根目录的
 * `用户手册.md` 解析成 `assets/users_manual.json`（结构化块，不是 HTML）——
 * 这样正文只有一处真源，应用侧只负责用 Compose 渲染，排版与主题全归应用管。
 *
 * 搜索用的纯文本在首次查询时惰性拼接一次（[chapterText]），避免每次输入都重新遍历。
 */
data class UserManual(
    val title: String,
    val preface: List<ManualBlock>,
    val chapters: List<ManualChapter>,
) {
    private val chapterTexts: List<String> by lazy { chapters.map { chapterText(it) } }

    /** 章节里所有文字拼成的一行，用于关键词匹配。 */
    fun textOf(index: Int): String = chapterTexts.getOrElse(index) { "" }

    /**
     * 按关键词搜索：命中章节则给出一条结果，摘要取第一个命中的块。
     *
     * 返回 `(章节下标, 摘要)`，摘要保证包含关键词周围的上下文。
     */
    fun search(query: String): List<Pair<Int, String>> {
        val needle = query.trim()
        if (needle.isEmpty()) return emptyList()
        val lower = needle.lowercase()
        return buildList {
            chapters.forEachIndexed { index, chapter ->
                val text = chapterTexts[index]
                if (!text.lowercase().contains(lower)) return@forEachIndexed
                val hit = text.lowercase().indexOf(lower)
                val from = (hit - 24).coerceAtLeast(0)
                val to = (hit + needle.length + 48).coerceAtMost(text.length)
                val snippet = buildString {
                    if (from > 0) append('…')
                    append(text.substring(from, to).replace('\n', ' '))
                    if (to < text.length) append('…')
                }
                add(index to snippet)
            }
        }
    }

    companion object {
        const val ASSET_NAME = "users_manual.json"

        /**
         * 解析手册 JSON 文本。单独抽出来是为了让单测能直接喂文件内容，
         * 不必依赖 Android 的 [Context]（单测里 assets 是取不到的）。
         */
        internal fun parse(json: String): UserManual? = runCatching {
            fromJson(JSONObject(json))
        }.getOrNull()

        /** 从 assets 读取并解析；文件缺失或结构异常时返回 null（调用方给兜底空态）。 */
        fun load(context: Context): UserManual? =
            runCatching {
                context.assets.open(ASSET_NAME).bufferedReader().use { it.readText() }
            }.getOrNull()?.let { parse(it) }

        private fun fromJson(root: JSONObject): UserManual = UserManual(
            title = root.optString("title", "用户手册"),
            preface = blocks(root.optJSONArray("preface")),
            chapters = root.optJSONArray("chapters")?.let { array ->
                (0 until array.length()).map { i ->
                    val o = array.getJSONObject(i)
                    ManualChapter(
                        id = o.optString("id"),
                        title = o.optString("title"),
                        blocks = blocks(o.optJSONArray("blocks")),
                    )
                }
            }.orEmpty(),
        )

        private fun blocks(array: JSONArray?): List<ManualBlock> {
            if (array == null) return emptyList()
            return (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                when (o.optString("t")) {
                    "h" -> ManualBlock.Heading(
                        level = o.optInt("level", 3),
                        text = o.optString("text"),
                        anchor = o.optString("id"),
                    )

                    "p" -> ManualBlock.Paragraph(spanList(o.optJSONArray("s")))

                    "ul" -> ManualBlock.Bullets(items(o.optJSONArray("items")))

                    "ol" -> ManualBlock.Steps(
                        items = items(o.optJSONArray("items")),
                        start = o.optInt("start", 1),
                    )

                    "quote" -> ManualBlock.Quote(
                        paragraphs = o.optJSONArray("p")?.let { arr ->
                            (0 until arr.length()).map { spanList(arr.optJSONArray(it)) }
                        }.orEmpty(),
                    )

                    "table" -> ManualBlock.Table(
                        head = cells(o.optJSONArray("head")),
                        rows = o.optJSONArray("rows")?.let { arr ->
                            (0 until arr.length()).map { cells(arr.optJSONArray(it)) }
                        }.orEmpty(),
                    )

                    "hr" -> ManualBlock.Divider

                    else -> null
                }
            }
        }

        private fun items(array: JSONArray?): List<ManualItem> {
            if (array == null) return emptyList()
            return (0 until array.length()).map { i ->
                val o = array.optJSONObject(i) ?: JSONObject()
                ManualItem(
                    spans = spanList(o.optJSONArray("s")),
                    sub = o.optJSONArray("sub")?.let { arr ->
                        (0 until arr.length()).map { spanList(arr.optJSONArray(it)) }
                    }.orEmpty(),
                )
            }
        }

        /** 扁平的一串 span（段落、列表项、引用段落都是这一层）。 */
        private fun spanList(array: JSONArray?): List<ManualSpan> {
            if (array == null) return emptyList()
            return (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                val text = o.optString("x")
                if (text.isEmpty()) return@mapNotNull null
                ManualSpan(
                    text = text,
                    bold = o.optInt("b") == 1,
                    code = o.optInt("c") == 1,
                    italic = o.optInt("i") == 1,
                    link = o.optString("a").takeIf { it.isNotEmpty() },
                )
            }
        }

        /** 表格的一行 / 表头：每个元素是一个单元格（本身又是一串 span）。 */
        private fun cells(array: JSONArray?): List<List<ManualSpan>> {
            if (array == null) return emptyList()
            return (0 until array.length()).map { spanList(array.optJSONArray(it)) }
        }

        /** 反查：把一章里的所有文本拼起来（搜索用）。 */
        private fun chapterText(chapter: ManualChapter): String = buildString {
            append(chapter.title)
            append('\n')
            fun push(spans: List<ManualSpan>) {
                spans.forEach { append(it.text) }
                append('\n')
            }

            fun walk(blocks: List<ManualBlock>) {
                blocks.forEach { block ->
                    when (block) {
                        is ManualBlock.Heading -> {
                            append(block.text); append('\n')
                        }

                        is ManualBlock.Paragraph -> push(block.spans)

                        is ManualBlock.Bullets -> block.items.forEach { item ->
                            push(item.spans)
                            item.sub.forEach { push(it) }
                        }

                        is ManualBlock.Steps -> block.items.forEach { item ->
                            push(item.spans)
                            item.sub.forEach { push(it) }
                        }

                        is ManualBlock.Quote -> block.paragraphs.forEach { push(it) }

                        is ManualBlock.Table -> {
                            block.head.forEach { push(it) }
                            block.rows.forEach { row -> row.forEach { push(it) } }
                        }

                        ManualBlock.Divider -> Unit
                    }
                }
            }

            walk(chapter.blocks)
        }
    }
}
