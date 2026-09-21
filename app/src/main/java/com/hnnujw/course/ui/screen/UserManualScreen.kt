package com.hnnujw.course.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hnnujw.course.manual.ManualBlock
import com.hnnujw.course.manual.ManualChapter
import com.hnnujw.course.manual.ManualItem
import com.hnnujw.course.manual.ManualSpan
import com.hnnujw.course.manual.UserManual
import com.hnnujw.course.ui.system.GlassPageScaffold
import com.hnnujw.course.ui.system.GlassTextField
import com.hnnujw.course.ui.system.LocalAppOverlayBottomInset
import com.hnnujw.course.ui.system.PagePadding
import com.hnnujw.course.ui.system.SystemCard
import com.hnnujw.course.ui.system.SystemIconButton
import kotlinx.coroutines.launch

/**
 * 用户手册阅读页（原生 Compose）。
 *
 * 内容来自 `assets/users_manual.json` —— 构建期由 `scripts/build_manual.py` 从
 * `用户手册.md` 解析出的结构化块（标题 / 段落 / 列表 / 表格 / 引用），
 * 本文件只负责渲染。整页没有 WebView、没有内嵌 HTML、没有注入脚本：
 *
 * - 排版与配色全部走应用主题（浅色 / 暗色 / 壁纸玻璃），不需要另维护一份 CSS；
 * - 目录、搜索、跳转都是原生的，可离线、随主题实时变化；
 * - 手册正文仍然只有 `用户手册.md` 一处真源，改完重跑脚本即可。
 */
@Composable
fun UserManualScreen(
    manual: UserManual?,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var searchOpen by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var tocOpen by remember { mutableStateOf(false) }

    fun closeSearch() {
        searchOpen = false
        query = ""
    }

    // 手势返回优先收掉展开中的搜索 / 目录，再退出页面 —— 免得一按返回整页关掉，
    // 用户还得重新进来一次。
    BackHandler(enabled = searchOpen || tocOpen) {
        if (searchOpen) closeSearch() else tocOpen = false
    }

    if (manual == null) {
        GlassPageScaffold(title = "用户手册", onBack = onBack) { padding ->
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(
                    text = "手册内容缺失，请重装应用或联系开发者",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }

    // 「文档头 + 每章标题 + 每章的块」拍平成一维列表。章节标题在列表里的下标单独记
    // 一份，目录与搜索结果靠它做跳转。
    val rows = remember(manual) {
        buildList {
            add(ManualRow.DocHeader(manual.preface))
            manual.chapters.forEachIndexed { index, chapter ->
                add(ManualRow.ChapterRow(index, chapter.title))
                chapter.blocks.forEach { add(ManualRow.BlockRow(it)) }
            }
        }
    }
    val chapterRows = remember(manual, rows) {
        IntArray(manual.chapters.size) { index ->
            rows.indexOfFirst { it is ManualRow.ChapterRow && it.index == index }
        }
    }

    fun jumpTo(chapterIndex: Int) {
        val target = chapterRows.getOrElse(chapterIndex) { -1 }
        if (target < 0) return
        scope.launch { listState.animateScrollToItem(target) }
    }

    val results = remember(manual, query) { manual.search(query) }
    val searching = searchOpen && query.isNotBlank()

    GlassPageScaffold(
        title = "用户手册",
        subtitle = if (searching) "找到 ${results.size} 处" else "共 ${manual.chapters.size} 章 · 离线可读",
        onBack = { if (searchOpen) closeSearch() else onBack() },
        actions = {
            SystemIconButton(
                icon = if (searchOpen) Icons.Default.Close else Icons.Default.Search,
                contentDescription = if (searchOpen) "关闭搜索" else "搜索手册",
                onClick = { if (searchOpen) closeSearch() else searchOpen = true },
            )
            SystemIconButton(
                icon = Icons.AutoMirrored.Filled.List,
                contentDescription = "目录",
                onClick = { tocOpen = !tocOpen },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (searchOpen) {
                GlassTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = PagePadding, vertical = 6.dp),
                    placeholder = "搜索手册内容，例如「提醒」「验证码」",
                    leadingIcon = Icons.Default.Search,
                )
            }
            if (tocOpen) {
                TableOfContents(
                    chapters = manual.chapters,
                    onSelect = {
                        tocOpen = false
                        closeSearch()
                        jumpTo(it)
                    },
                )
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = PagePadding,
                    end = PagePadding,
                    top = 10.dp,
                    bottom = LocalAppOverlayBottomInset.current + 40.dp,
                ),
            ) {
                if (searching) {
                    if (results.isEmpty()) {
                        item {
                            Text(
                                text = "没有找到与「${query.trim()}」相关的内容",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 28.dp),
                            )
                        }
                    } else {
                        itemsIndexed(results) { _, result ->
                            SearchResultRow(
                                title = manual.chapters[result.first].title,
                                snippet = result.second,
                                onClick = {
                                    closeSearch()
                                    jumpTo(result.first)
                                },
                            )
                        }
                    }
                } else {
                    itemsIndexed(rows) { _, row ->
                        when (row) {
                            is ManualRow.DocHeader -> DocumentHeader(manual.title, row.preface)
                            is ManualRow.ChapterRow -> ChapterHeader(row.title)
                            is ManualRow.BlockRow -> BlockView(row.block)
                        }
                    }
                }
            }
        }
    }
}

/** 列表里的一行：文档头 / 章节标题 / 正文块。 */
private sealed interface ManualRow {
    data class DocHeader(val preface: List<ManualBlock>) : ManualRow
    data class ChapterRow(val index: Int, val title: String) : ManualRow
    data class BlockRow(val block: ManualBlock) : ManualRow
}

/** 文档头：标题 + 前言（版本说明引用块等）。 */
@Composable
private fun DocumentHeader(title: String, preface: List<ManualBlock>) {
    Column(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 6.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(10.dp))
        preface.forEach { BlockView(it) }
    }
}

/** 章节大标题（h2）：左侧竖条取主题色，和「我的」页的分组标题观感一致。 */
@Composable
private fun ChapterHeader(title: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 30.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(4.dp)
                .height(20.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.primary)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** 目录面板：点条目跳到对应章节。 */
@Composable
private fun TableOfContents(chapters: List<ManualChapter>, onSelect: (Int) -> Unit) {
    SystemCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PagePadding, vertical = 4.dp)
            .heightIn(max = 320.dp),
    ) {
        Text(
            text = "目录",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        LazyColumn {
            itemsIndexed(chapters) { index, chapter ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onSelect(index) }
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = chapter.title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

/** 一条搜索结果：章节名 + 命中摘要。 */
@Composable
private fun SearchResultRow(title: String, snippet: String, onClick: () -> Unit) {
    SystemCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        onClick = onClick,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = snippet,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 正文块分发。 */
@Composable
private fun BlockView(block: ManualBlock) {
    when (block) {
        is ManualBlock.Heading -> HeadingBlock(block)
        is ManualBlock.Paragraph -> ParagraphBlock(block.spans)
        is ManualBlock.Bullets -> BulletBlock(block.items)
        is ManualBlock.Steps -> StepBlock(block.items, block.start)
        is ManualBlock.Quote -> QuoteBlock(block)
        is ManualBlock.Table -> TableBlock(block)
        ManualBlock.Divider -> Box(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 18.dp)
                .height(1.dp)
                .background(MaterialTheme.colorScheme.outlineVariant)
        )
    }
}

@Composable
private fun HeadingBlock(block: ManualBlock.Heading) {
    Text(
        text = block.text,
        style = if (block.level <= 3) {
            MaterialTheme.typography.titleSmall
        } else {
            MaterialTheme.typography.bodyLarge
        },
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(top = if (block.level <= 3) 20.dp else 14.dp, bottom = 4.dp),
    )
}

@Composable
private fun ParagraphBlock(spans: List<ManualSpan>) {
    Text(
        text = renderSpans(spans, MaterialTheme.typography.bodyMedium.fontSize),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
        lineHeight = 24.sp,
        modifier = Modifier.padding(vertical = 3.dp),
    )
}

@Composable
private fun BulletBlock(items: List<ManualItem>) {
    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        items.forEach { item ->
            BulletItem(item.spans, marker = "•")
            item.sub.forEach { sub -> BulletItem(sub, marker = "–", indent = true) }
        }
    }
}

@Composable
private fun BulletItem(spans: List<ManualSpan>, marker: String, indent: Boolean = false) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = if (indent) 22.dp else 2.dp, top = 3.dp, bottom = 3.dp),
    ) {
        Text(
            text = marker,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(16.dp),
        )
        Text(
            text = renderSpans(spans, MaterialTheme.typography.bodyMedium.fontSize),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            lineHeight = 24.sp,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun StepBlock(items: List<ManualItem>, start: Int) {
    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        items.forEachIndexed { index, item ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 3.dp),
            ) {
                Box(
                    modifier = Modifier
                        .padding(top = 2.dp)
                        .size(19.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "${start + index}",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    text = renderSpans(item.spans, MaterialTheme.typography.bodyMedium.fontSize),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    lineHeight = 24.sp,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** 引用块：左侧竖条 + 弱化底色，用于「包名说明」这类提示。 */
@Composable
private fun QuoteBlock(block: ManualBlock.Quote) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .height(IntrinsicSize.Min)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)),
    ) {
        // IntrinsicSize.Min 让竖条和右侧文字等高（Rows 默认按内容取高，
        // fillMaxHeight 只有在父高度确定时才有意义，两者必须成对出现）。
        Box(
            Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.primary)
        )
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            block.paragraphs.forEachIndexed { index, paragraph ->
                if (index > 0) Spacer(Modifier.height(6.dp))
                Text(
                    text = renderSpans(paragraph, MaterialTheme.typography.bodySmall.fontSize),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 21.sp,
                )
            }
        }
    }
}

/** 表格：手绘 1px 网格 + 表头底色。第一列略窄，其余均分。 */
@Composable
private fun TableBlock(block: ManualBlock.Table) {
    val size = MaterialTheme.typography.bodySmall.fontSize
    val columns = maxOf(block.head.size, block.rows.maxOfOrNull { it.size } ?: 0)
    if (columns == 0) return

    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp)
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp)),
    ) {
        TableRow(
            cells = block.head,
            columns = columns,
            size = size,
            backgroundColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            bold = true,
        )
        block.rows.forEachIndexed { index, row ->
            if (index > 0) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant)
                )
            }
            TableRow(
                cells = row,
                columns = columns,
                size = size,
                backgroundColor = Color.Transparent,
                bold = false,
            )
        }
    }
}

@Composable
private fun TableRow(
    cells: List<List<ManualSpan>>,
    columns: Int,
    size: TextUnit,
    backgroundColor: Color,
    bold: Boolean,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .background(backgroundColor),
    ) {
        repeat(columns) { column ->
            val cell = cells.getOrNull(column).orEmpty()
            if (column > 0) {
                Box(
                    Modifier
                        .width(1.dp)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.outlineVariant)
                )
            }
            Text(
                text = renderSpans(cell, size),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurface,
                lineHeight = 19.sp,
                modifier = Modifier
                    .weight(if (column == 0) 0.9f else 1.15f)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            )
        }
    }
}

/**
 * 把 span 列表渲染成 [AnnotatedString]。
 *
 * 数据里目前没有「可点的正文链接」：Markdown 里的链接只出现在被构建脚本跳过的
 * 「目录」章节，而目录由 [TableOfContents] 原生实现。所以链接只上色 + 下划线，
 * 不挂点击回调 —— 留一个点不动的手势比留一个假的更糟。
 */
@Composable
private fun renderSpans(spans: List<ManualSpan>, baseSize: TextUnit): AnnotatedString {
    val codeBackground = MaterialTheme.colorScheme.surfaceVariant
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val accent = MaterialTheme.colorScheme.primary
    return buildAnnotatedString {
        spans.forEach { span ->
            if (span.text.isEmpty()) return@forEach
            val style = SpanStyle(
                fontWeight = if (span.bold) FontWeight.SemiBold else null,
                fontFamily = if (span.code) FontFamily.Monospace else null,
                background = if (span.code) codeBackground else Color.Unspecified,
                color = when {
                    span.link != null -> accent
                    span.italic -> muted
                    else -> Color.Unspecified
                },
                fontStyle = if (span.italic) FontStyle.Italic else null,
                textDecoration = if (span.link != null) TextDecoration.Underline else null,
                fontSize = if (span.code) baseSize * 0.94f else TextUnit.Unspecified,
            )
            withStyle(style) { append(span.text) }
        }
    }
}
