package com.hnnujw.course.manual

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 用户手册解析测试。
 *
 * 手册从 WebView + HTML 换成原生 Compose 之后，链路上多了一段**构建期解析**：
 * `用户手册.md` →（scripts/build_manual.py）→ `assets/users_manual.json` →
 * `UserManual` → `UserManualScreen`。这段解析是纯逻辑，出错的后果却很直接 ——
 * 少一环用户就看不到正文，而且**不会报任何错**（页面照常渲染，只是内容缺一块）。
 *
 * 所以这里既校验结构（章节数、块类型、行内片段），也把根目录的 Markdown 一起读进来
 * 对账，确保"源码里有的章节，资产里一个不少"。
 */
class UserManualTest {

    // ── 资产本身可用 ────────────────────────────────────────────────────

    @Test
    fun `内置资产解析成功且章节非空`() {
        val manual = bundled()
        assertTrue("标题不能为空", manual.title.isNotBlank())
        assertEquals("章节数必须与 用户手册.md 的 h2 数量一致", markdownChapterTitles(), manual.chapters.map { it.title })
        assertTrue("前言（版本说明等）不能是空的", manual.preface.isNotEmpty())
        manual.chapters.forEach { chapter ->
            assertTrue("章节「${chapter.title}」标题为空", chapter.title.isNotBlank())
            assertTrue("章节「${chapter.title}」没有任何内容块", chapter.blocks.isNotEmpty())
        }
    }

    @Test
    fun `章节不含目录且锚点唯一非空`() {
        val manual = bundled()
        assertFalse(
            "「目录」章节由客户端原生生成，构建脚本必须跳过它",
            manual.chapters.any { it.title == "目录" }
        )
        val ids = manual.chapters.map { it.id }
        assertTrue("锚点不能有空值", ids.all { it.isNotBlank() })
        assertEquals("锚点必须唯一", ids.size, ids.toSet().size)
    }

    // ── 每种块都要真的解析出来 ──────────────────────────────────────────

    @Test
    fun `各类块都被解析而非被丢弃`() {
        val all = allBlocks()

        fun count(predicate: (ManualBlock) -> Boolean) = all.count(predicate)

        assertTrue("段落必须解析出来", count { it is ManualBlock.Paragraph } > 20)
        assertTrue("小节标题必须解析出来", count { it is ManualBlock.Heading } > 20)
        assertTrue("无序列表必须解析出来", count { it is ManualBlock.Bullets } > 20)
        assertTrue("有序列表必须解析出来", count { it is ManualBlock.Steps } > 0)
        assertTrue("表格必须解析出来", count { it is ManualBlock.Table } > 3)
        assertTrue("引用块必须解析出来", count { it is ManualBlock.Quote } > 0)
    }

    @Test
    fun `表格行列完整且首行为表头`() {
        val tables = allBlocks().filterIsInstance<ManualBlock.Table>()
        assertTrue("手册里应当有表格", tables.isNotEmpty())
        tables.forEach { table ->
            assertTrue("表头不能为空", table.head.isNotEmpty())
            assertTrue(
                "Markdown 的 `| --- |` 分隔行必须被丢掉，不能当成数据行",
                table.rows.none { row -> row.all { cells -> cells.isEmpty() } }
            )
        }
    }

    @Test
    fun `列表的子项被保留`() {
        val withSub = allBlocks()
            .filterIsInstance<ManualBlock.Bullets>()
            .flatMap { it.items }
            .filter { it.sub.isNotEmpty() }
        assertTrue(
            "手册里有缩进子项（如 4.1 课表状态图标、7.2 模块权重），必须保留层级",
            withSub.isNotEmpty()
        )
    }

    @Test
    fun `行内记号解析成片段且不残留记号本身`() {
        val spans = allBlocks().flatMap { block ->
            when (block) {
                is ManualBlock.Paragraph -> block.spans
                is ManualBlock.Quote -> block.paragraphs.flatten()
                is ManualBlock.Bullets -> block.items.flatMap { it.spans }
                is ManualBlock.Steps -> block.items.flatMap { it.spans }
                is ManualBlock.Table -> block.head.flatten() + block.rows.flatten().flatten()
                else -> emptyList()
            }
        }
        assertTrue("应当解析出粗体片段", spans.any { it.bold })
        assertTrue("应当解析出斜体片段（页脚那句）", spans.any { it.italic })
        assertTrue(
            "行内记号必须被消费掉，正文里不能出现 ** 或反引号",
            spans.none { it.text.contains("**") || it.text.contains('`') }
        )
    }

    // ── 正文内容没有"空壳" ────────────────────────────────────────────

    @Test
    fun `正文里没有空块`() {
        val blocks = allBlocks()
        assertTrue("手册不可能一块内容都没有", blocks.size > 100)
        blocks.forEach { block ->
            when (block) {
                is ManualBlock.Paragraph -> assertTrue("空的段落", block.spans.any { it.text.isNotBlank() })
                is ManualBlock.Heading -> assertTrue("空的小节标题", block.text.isNotBlank())
                is ManualBlock.Bullets -> assertTrue(
                    "空的无序列表",
                    block.items.all { item -> item.spans.any { it.text.isNotBlank() } }
                )

                is ManualBlock.Steps -> assertTrue(
                    "空的有序列表",
                    block.items.all { item -> item.spans.any { it.text.isNotBlank() } }
                )

                else -> Unit
            }
        }
    }

    // ── 搜索 ─────────────────────────────────────────────────────────

    @Test
    fun `搜索命中的摘要包含关键词`() {
        val manual = bundled()
        val results = manual.search("验证码")
        assertTrue("手册里多处提到验证码，不能一处都搜不到", results.isNotEmpty())
        results.forEach { (index, snippet) ->
            assertTrue("命中的章节下标越界", index in manual.chapters.indices)
            assertTrue("摘要必须包含关键词：$snippet", snippet.contains("验证码"))
        }
    }

    @Test
    fun `空查询与无命中都返回空结果`() {
        val manual = bundled()
        assertTrue(manual.search("").isEmpty())
        assertTrue(manual.search("   ").isEmpty())
        assertTrue(manual.search("这个词手册里绝对不存在zzzz").isEmpty())
    }

    @Test
    fun `搜索大小写不敏感`() {
        val manual = bundled()
        assertEquals(
            manual.search("GitHub").map { it.first },
            manual.search("github").map { it.first }
        )
        assertTrue(manual.search("github").isNotEmpty())
    }

    // ── 坏数据不崩 ────────────────────────────────────────────────────

    @Test
    fun `坏 JSON 返回 null 而不是抛异常`() {
        assertNull(UserManual.parse("这不是 JSON"))
        assertNull(UserManual.parse(""))
        assertNull(UserManual.parse("{ not json }"))
    }

    @Test
    fun `缺字段的 JSON 退化成空手册而不是崩溃`() {
        val manual = UserManual.parse("{}")
            ?: throw AssertionError("缺字段也要能解析出对象")
        assertTrue("标题兜底", manual.title.isNotBlank())
        assertTrue(manual.chapters.isEmpty())
        assertTrue(manual.preface.isEmpty())
        assertTrue(manual.search("任意").isEmpty())
    }

    // ── 工具 ─────────────────────────────────────────────────────────

    private fun allBlocks(): List<ManualBlock> {
        val manual = bundled()
        return manual.preface + manual.chapters.flatMap { it.blocks }
    }

    private fun bundled(): UserManual {
        val file = File(moduleDir(), "src/main/assets/${UserManual.ASSET_NAME}")
        assertTrue("找不到内置手册资产：${file.absolutePath}", file.isFile)
        return UserManual.parse(file.readText(Charsets.UTF_8))
            ?: throw AssertionError("assets/${UserManual.ASSET_NAME} 解析失败（检查 scripts/build_manual.py）")
    }

    /** 仓库根目录 `用户手册.md` 里所有 h2 标题（跳过「目录」，它由客户端原生生成）。 */
    private fun markdownChapterTitles(): List<String> {
        val md = File(repoRoot(), "用户手册.md")
        assertTrue("找不到 用户手册.md：${md.absolutePath}", md.isFile)
        return md.readLines()
            .filter { it.startsWith("## ") }
            .map { it.removePrefix("## ").trim() }
            .filter { it != "目录" }
    }

    /** Gradle 单测的工作目录是模块目录（app/），这里向上找，换个跑法也不会挂。 */
    private fun moduleDir(): File = ascendUntil("version.properties")

    private fun repoRoot(): File = ascendUntil("用户手册.md")

    private fun ascendUntil(marker: String): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            if (File(dir, marker).isFile) return dir
            dir = dir.parentFile
        }
        error("找不到 $marker（当前工作目录 ${System.getProperty("user.dir")}）")
    }
}
