package com.hnnujw.course.ui.document

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.exifinterface.media.ExifInterface
import com.hnnujw.course.document.CsvParser
import com.hnnujw.course.document.DocxParser
import com.hnnujw.course.document.SheetData
import com.hnnujw.course.document.SpreadsheetDocument
import com.hnnujw.course.document.TextParser
import com.hnnujw.course.document.WordBlock
import com.hnnujw.course.document.WordDocument
import com.hnnujw.course.document.WordRun
import com.hnnujw.course.document.XlsxParser
import com.hnnujw.course.ui.system.PagePadding
import com.hnnujw.course.ui.system.SystemEmptyState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File

/**
 * 五个渲染器：Word / Excel / PDF / 图片 / 文本。
 *
 * 共同约定：解析一律丢到 [Dispatchers.IO]，解析结果用 `Result` 装着 ——
 * "没解析完"用 null 表示，"解析失败"用 failure 表示，两态分开才能
 * 分别给出"正在解析"与"打不开"两套界面。
 */

// ── Word ──────────────────────────────────────────────────────────────────

@Composable
internal fun WordViewerScreen(file: File, name: String) {
    var result by remember(file) { mutableStateOf<Result<WordDocument>?>(null) }

    LaunchedEffect(file) {
        result = withContext(Dispatchers.IO) { runCatching { DocxParser.parse(file) } }
    }

    val document = result?.getOrNull()
    when {
        result == null -> CenterMessage("正在解析文档…")
        document == null -> CenterMessage(
            title = "文档解析失败",
            message = "文件可能已损坏，或不是 Word 2007（.docx）以后的格式。"
        )
        document.isEmpty -> CenterMessage(title = "文档是空的", message = "这份文档没有可显示的正文。")
        else -> WordBlocks(document, name)
    }
}

@Composable
private fun WordBlocks(document: WordDocument, name: String) {
    val numbers = remember(document) { listNumbers(document.blocks) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = PagePadding, vertical = 16.dp
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            if (document.title.isNotBlank() && !name.contains(document.title)) {
                Text(
                    text = document.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        items(document.blocks.size) { index ->
            WordBlockItem(document.blocks[index], numbers[index])
        }
    }
}

@Composable
private fun WordBlockItem(block: WordBlock, listNumber: Int?) {
    when (block) {
        is WordBlock.Heading -> WordRuns(
            runs = block.runs,
            baseSize = headingSize(block.level),
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        is WordBlock.Paragraph -> WordRuns(block.runs, 16.sp, color = MaterialTheme.colorScheme.onSurface)
        is WordBlock.ListItem -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = listMarker(block.ordered, listNumber, block.level),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 2.dp)
            )
            WordRuns(
                runs = block.runs,
                baseSize = 16.sp,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = (block.level.coerceIn(0, 8) * 12).dp),
            )
        }
        is WordBlock.Table -> WordTable(block.rows)
        is WordBlock.Image -> WordImage(block)
        WordBlock.PageBreak -> Box(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .height(0.5.dp)
                .background(MaterialTheme.colorScheme.outlineVariant)
        )
    }
}

@Composable
private fun WordRuns(
    runs: List<WordRun>,
    baseSize: androidx.compose.ui.unit.TextUnit,
    color: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
    fontWeight: FontWeight? = null,
) {
    val annotated: AnnotatedString = buildAnnotatedString {
        runs.forEach { run ->
            withStyle(
                SpanStyle(
                    fontWeight = if (run.bold) FontWeight.Bold else fontWeight,
                    fontStyle = if (run.italic) FontStyle.Italic else null,
                    textDecoration = if (run.underline) TextDecoration.Underline else null,
                    fontSize = if (run.halfPointSize > 0) (run.halfPointSize / 2f).sp else baseSize,
                )
            ) { append(run.text) }
        }
    }
    Text(
        text = annotated,
        color = color,
        fontSize = baseSize,
        lineHeight = baseSize * 1.55f,
        modifier = modifier,
    )
}

private fun headingSize(level: Int): androidx.compose.ui.unit.TextUnit = when (level) {
    1 -> 22.sp
    2 -> 19.sp
    else -> 17.sp
}

/** 有序列表画 `1.`，无序按层级画不同符号。 */
private fun listMarker(ordered: Boolean, number: Int?, level: Int): String {
    if (ordered) return "${number ?: 1}."
    return when (level.coerceIn(0, 2)) {
        0 -> "•"
        1 -> "◦"
        else -> "▪"
    }
}

/**
 * 编号表：`blockIndex → 序号`。
 *
 * 编号必须整段连续数，而 LazyColumn 的 item 是懒组合的，没法在组合时数 ——
 * 所以先在普通函数里把整篇扫一遍。遇到非列表块就重新计数（Word 的习惯：
 * 两段列表中间夹一段正文，第二段从 1 重新开始）。
 */
private fun listNumbers(blocks: List<WordBlock>): Map<Int, Int> {
    val counters = IntArray(9)
    val result = HashMap<Int, Int>()
    var inList = false
    blocks.forEachIndexed { index, block ->
        when {
            block is WordBlock.ListItem -> {
                if (!inList) counters.fill(0)
                inList = true
                val level = block.level.coerceIn(0, 8)
                counters[level]++
                for (deeper in level + 1..8) counters[deeper] = 0
                result[index] = counters[level]
            }
            block == WordBlock.PageBreak -> Unit // 分页不打断列表
            else -> inList = false
        }
    }
    return result
}

/**
 * 表格：整表作为一个单元横向滚动（滚动状态只有一份，各行才对得上列），
 * 单元格固定宽度。列数少时表格窄于屏宽也没关系，左侧对齐即可。
 */
@Composable
private fun WordTable(rows: List<List<String>>) {
    if (rows.isEmpty()) return
    val borderColor = MaterialTheme.colorScheme.outlineVariant
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
    ) {
        Column {
            rows.forEach { row ->
                Row {
                    row.forEach { cell ->
                        Box(
                            Modifier
                                .width(104.dp)
                                .border(0.5.dp, borderColor, RectangleShape)
                                .padding(horizontal = 8.dp, vertical = 7.dp)
                        ) {
                            Text(
                                text = cell,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WordImage(image: WordBlock.Image) {
    val bitmap by produceState<Bitmap?>(initialValue = null, image) {
        value = withContext(Dispatchers.IO) { decodeAttachmentBitmap(image.bytes, maxDimension = 1600) }
    }
    val decoded = bitmap ?: return
    if (decoded.width <= 0 || decoded.height <= 0) return
    Image(
        bitmap = decoded.asImageBitmap(),
        contentDescription = "文档图片",
        contentScale = ContentScale.FillWidth,
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(decoded.width.toFloat() / decoded.height)
    )
}

// ── Excel / CSV ───────────────────────────────────────────────────────────

@Composable
internal fun SheetViewerScreen(file: File, name: String) {
    var result by remember(file) { mutableStateOf<Result<SpreadsheetDocument>?>(null) }
    var selectedSheet by remember(file) { mutableIntStateOf(0) }
    var selectedCell by remember(file) { mutableStateOf<String?>(null) }

    LaunchedEffect(file) {
        result = withContext(Dispatchers.IO) {
            runCatching {
                val isCsv = name.substringAfterLast('.', "").lowercase() == "csv"
                if (isCsv) CsvParser.parse(file) else XlsxParser.parse(file)
            }
        }
    }

    val document = result?.getOrNull()
    when {
        result == null -> CenterMessage("正在解析表格…")
        document == null -> CenterMessage(
            title = "表格解析失败",
            message = "文件可能已损坏，或不是 .xlsx / .csv 格式。"
        )
        document.isEmpty -> CenterMessage(title = "表格是空的", message = "这份表格没有可显示的数据。")
        else -> SheetGrid(
            document = document,
            selectedSheet = selectedSheet,
            onSelectSheet = { selectedSheet = it },
            onCellClick = { selectedCell = it },
            selectedCell = selectedCell,
        )
    }
}

private val CELL_WIDTH = 104.dp
private val HEADER_HEIGHT = 40.dp
private val CELL_HEIGHT = 36.dp

@Composable
private fun SheetGrid(
    document: SpreadsheetDocument,
    selectedSheet: Int,
    onSelectSheet: (Int) -> Unit,
    onCellClick: (String?) -> Unit,
    selectedCell: String?,
) {
    val sheet = document.sheets.getOrElse(selectedSheet) { document.sheets.first() }
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    // 网格固定宽：列多时超屏（靠横向滚动看全），列少时至少铺满屏幕
    val gridWidth = maxOf(CELL_WIDTH * sheet.columnCount.coerceAtLeast(1), screenWidth)
    val horizontal = rememberScrollState()

    Column(modifier = Modifier.fillMaxSize()) {
        if (document.sheets.size > 1) {
            SheetTabs(
                names = document.sheets.map { it.name },
                selected = selectedSheet,
                onSelect = onSelectSheet,
            )
        }
        if (sheet.truncated) {
            Text(
                text = "表格太大，只显示前 ${XlsxParser.MAX_ROWS} 行 × ${XlsxParser.MAX_COLUMNS} 列",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = PagePadding, vertical = 6.dp)
            )
        }
        // 表头冻结在网格外面：不随纵向滚动消失，列宽与数据行用同一套固定宽度对齐
        if (sheet.rows.isNotEmpty() && sheet.columnCount > 0) {
            SheetRow(
                cells = sheet.rows.first(),
                gridWidth = gridWidth,
                header = true,
                onCellClick = onCellClick,
                modifier = Modifier.horizontalScroll(horizontal),
            )
        }
        Box(modifier = Modifier
            .fillMaxWidth()
            .weight(1f)
            .horizontalScroll(horizontal)
        ) {
            LazyColumn(modifier = Modifier.width(gridWidth)) {
                val body = sheet.rows.drop(1)
                items(body.size) { index ->
                    SheetRow(
                        cells = body[index],
                        gridWidth = gridWidth,
                        header = false,
                        onCellClick = onCellClick,
                    )
                }
            }
        }
        // 点中的单元格在这里看全文（单元格里只显示一行省略号）
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                .padding(horizontal = PagePadding, vertical = 8.dp)
        ) {
            Text(
                text = selectedCell?.takeIf { it.isNotBlank() } ?: "点击单元格查看完整内容",
                style = MaterialTheme.typography.labelMedium,
                color = if (selectedCell.isNullOrBlank()) {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SheetRow(
    cells: List<String>,
    gridWidth: Dp,
    header: Boolean,
    onCellClick: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val borderColor = MaterialTheme.colorScheme.outlineVariant
    Row(modifier = modifier.width(gridWidth)) {
        cells.forEach { cell ->
            Box(
                modifier = Modifier
                    .width(CELL_WIDTH)
                    .height(if (header) HEADER_HEIGHT else CELL_HEIGHT)
                    .border(0.5.dp, borderColor, RectangleShape)
                    .background(
                        if (header) {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                        } else {
                            androidx.compose.ui.graphics.Color.Transparent
                        }
                    )
                    .clickable { onCellClick(cell) }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Text(
                    text = cell,
                    style = if (header) {
                        MaterialTheme.typography.labelMedium
                    } else {
                        MaterialTheme.typography.bodySmall
                    },
                    fontWeight = if (header) FontWeight.SemiBold else null,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun SheetTabs(names: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val appearance = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = PagePadding, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        names.forEachIndexed { index, name ->
            val isSelected = index == selected
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(
                        if (isSelected) appearance.primary.copy(alpha = 0.16f)
                        else appearance.surfaceVariant.copy(alpha = 0.5f)
                    )
                    .clickable { onSelect(index) }
                    .padding(horizontal = 14.dp, vertical = 7.dp)
            ) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (isSelected) appearance.primary else appearance.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

// ── PDF ───────────────────────────────────────────────────────────────────

/** `PdfRenderer` 同一时刻只允许打开一页，全 App 串行化渲染即可。 */
private val pdfRenderMutex = Mutex()

@Composable
internal fun PdfViewerScreen(file: File) {
    var renderer by remember(file) { mutableStateOf<PdfRenderer?>(null) }
    var pageCount by remember(file) { mutableIntStateOf(0) }
    var failed by remember(file) { mutableStateOf<String?>(null) }
    var zoom by remember(file) { mutableFloatStateOf(1f) }
    val horizontal = rememberScrollState()

    DisposableEffect(file) {
        val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val opened = try {
            PdfRenderer(descriptor)
        } catch (t: Throwable) {
            runCatching { descriptor.close() }
            null
        }
        renderer = opened
        pageCount = opened?.pageCount ?: 0
        if (opened == null) failed = "无法打开这个 PDF，文件可能已损坏或带有密码。"
        onDispose { runCatching { opened?.close() } }
    }

    if (failed != null) {
        CenterMessage(title = "PDF 打不开", message = failed.orEmpty())
        return
    }
    if (pageCount == 0) {
        CenterMessage("正在读取 PDF…")
        return
    }

    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    val pageWidth = (screenWidth - PagePadding * 2) * zoom

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = PagePadding),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "第 1 页 / 共 $pageCount 页",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = if (zoom > 1f) "双击页面缩小" else "双击页面放大",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(if (zoom > 1f) Modifier.horizontalScroll(horizontal) else Modifier)
        ) {
            LazyColumn(
                modifier = Modifier.width(pageWidth),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = PagePadding, vertical = 12.dp
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(pageCount) { index ->
                    PdfPage(
                        renderer = renderer,
                        index = index,
                        width = pageWidth,
                        onDoubleTap = { zoom = if (zoom > 1f) 1f else 2f },
                    )
                }
            }
        }
    }
}

@Composable
private fun PdfPage(
    renderer: PdfRenderer?,
    index: Int,
    width: Dp,
    onDoubleTap: () -> Unit,
) {
    var bitmap by remember(renderer, index, width) { mutableStateOf<Bitmap?>(null) }
    val density = LocalDensity.current

    LaunchedEffect(renderer, index, width, density) {
        if (renderer == null) return@LaunchedEffect
        // Dp.roundToPx() 只在 Density 作用域里可用，从组合里把 LocalDensity 带进来
        val widthPx = with(density) { width.roundToPx() }
        bitmap = renderPdfPage(renderer, index, widthPx)
    }

    val decoded = bitmap
    if (decoded == null) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(width * 1.4f),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "第 ${index + 1} 页",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }
    Image(
        bitmap = decoded.asImageBitmap(),
        contentDescription = "第 ${index + 1} 页",
        contentScale = ContentScale.FillWidth,
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(decoded.width.toFloat() / decoded.height)
            // 双击切换缩放。不用 clickable：tap 手势不吞拖动，列表滚动不受影响
            .pointerInput(onDoubleTap) {
                detectTapGestures(onDoubleTap = { onDoubleTap() })
            }
    )
}

private suspend fun renderPdfPage(renderer: PdfRenderer, index: Int, widthPx: Int): Bitmap? =
    pdfRenderMutex.withLock {
        withContext(Dispatchers.IO) {
            runCatching {
                val page = renderer.openPage(index)
                try {
                    val scale = widthPx.toFloat() / page.width.coerceAtLeast(1)
                    val height = (page.height * scale).toInt().coerceAtLeast(1)
                    val bitmap = Bitmap.createBitmap(widthPx.coerceAtLeast(1), height, Bitmap.Config.ARGB_8888)
                    // PdfRenderer 只在已有像素上画，透明底会变黑，先铺白
                    bitmap.eraseColor(android.graphics.Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bitmap
                } finally {
                    page.close()
                }
            }.getOrNull()
        }
    }

// ── 图片 ──────────────────────────────────────────────────────────────────

@Composable
internal fun ImageViewerScreen(file: File, name: String) {
    var result by remember(file) { mutableStateOf<Result<Bitmap>?>(null) }
    val zoomState = rememberZoomState()

    LaunchedEffect(file) {
        result = withContext(Dispatchers.IO) {
            runCatching {
                decodeAttachmentBitmap(file.readBytes(), maxDimension = 2400)
                    ?: throw IllegalArgumentException("不是可识别的图片")
            }
        }
    }

    val bitmap = result?.getOrNull()
    when {
        result == null -> CenterMessage("正在加载图片…")
        bitmap == null -> CenterMessage(
            title = "图片打不开",
            message = "文件可能不是图片，或已经损坏。"
        )
        bitmap.width <= 0 || bitmap.height <= 0 -> CenterMessage("图片打不开", "这张图没有有效尺寸。")
        else -> Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = PagePadding),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            ZoomableBox(
                state = zoomState,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(
                        (bitmap.width.toFloat() / bitmap.height).coerceIn(0.4f, 2.6f)
                    )
            ) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "双指缩放 · 双击复原",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ── 文本 ──────────────────────────────────────────────────────────────────

@Composable
internal fun TextViewerScreen(file: File, name: String) {
    var result by remember(file) { mutableStateOf<Result<com.hnnujw.course.document.TextDocument>?>(null) }

    LaunchedEffect(file) {
        result = withContext(Dispatchers.IO) { runCatching { TextParser.parse(file) } }
    }

    val document = result?.getOrNull()
    when {
        result == null -> CenterMessage("正在读取文本…")
        document == null -> CenterMessage(title = "文本读取失败", message = "文件可能不是文本，或已损坏。")
        else -> Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = PagePadding, vertical = 16.dp)
        ) {
            if (document.truncated) {
                Text(
                    text = "内容过长，只显示前 ${TextParser.MAX_CHARS} 字符",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }
            SelectionContainer {
                Text(
                    text = document.text,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "编码 ${document.charsetName} · 共 ${document.lineCount} 行",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ── 共用 ──────────────────────────────────────────────────────────────────

@Composable
private fun CenterMessage(
    title: String,
    message: String? = null,
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (message == null) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            SystemEmptyState(title = title, message = message)
        }
    }
}

/**
 * 解码图片字节，长边压到 [maxDimension] 以内（内存可控），并按 EXIF 转正。
 *
 * 部分图（截图、扫描件）不带方向标记时原样返回；`decodeByteArray` 返回 null
 * 说明根本不是图片，调用方据此给出"打不开"。
 */
internal fun decodeAttachmentBitmap(bytes: ByteArray, maxDimension: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    val options = BitmapFactory.Options().apply {
        inSampleSize = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (inSampleSize * 2) >= maxDimension) {
            inSampleSize *= 2
        }
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null

    val orientation = runCatching {
        ExifInterface(ByteArrayInputStream(bytes))
            .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
    val degrees = when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90
        ExifInterface.ORIENTATION_ROTATE_180 -> 180
        ExifInterface.ORIENTATION_ROTATE_270 -> 270
        else -> 0
    }
    if (degrees == 0) return bitmap
    val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}
