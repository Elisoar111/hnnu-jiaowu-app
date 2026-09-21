package com.hnnujw.course.ui.document

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.hnnujw.course.document.DocumentKind
import com.hnnujw.course.document.DocumentSniffer
import com.hnnujw.course.manager.AppThemeCoordinator
import com.hnnujw.course.secondclass.SecondClassAttachment
import com.hnnujw.course.ui.system.GlassPageScaffold
import com.hnnujw.course.ui.system.GlassProgressBar
import com.hnnujw.course.ui.system.PagePadding
import com.hnnujw.course.ui.system.SystemEmptyState
import com.hnnujw.course.ui.system.SystemIconButton
import com.hnnujw.course.ui.system.SystemSecondaryButton
import com.hnnujw.course.ui.theme.CourseSelectorTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 附件查看器宿主页。
 *
 * 与 [com.hnnujw.course.AnnouncementActivity] 同构：独立全屏页 + 液态玻璃页壳。
 * 职责只有三件 —— 把直链附件下载到缓存、按**内容**嗅探类型、分发给对应渲染器；
 * 具体怎么渲染（Word/Excel/PDF/图片/文本）在 [DocumentRenderers]。
 */
class DocumentViewerActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppThemeCoordinator.wrapContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra(EXTRA_URL).orEmpty()
        val name = intent.getStringExtra(EXTRA_NAME).orEmpty().ifBlank { "附件" }
        val attachmentType = intent.getIntExtra(EXTRA_TYPE, 0)

        setContent {
            CourseSelectorTheme {
                DocumentViewerScreen(
                    url = url,
                    name = name,
                    attachmentType = attachmentType,
                    onBack = { finish() },
                )
            }
        }
    }

    companion object {
        private const val EXTRA_URL = "document_viewer.url"
        private const val EXTRA_NAME = "document_viewer.name"
        private const val EXTRA_TYPE = "document_viewer.attachment_type"

        /** 详情页附件行点击的统一入口。 */
        fun start(context: Context, attachment: SecondClassAttachment) {
            val intent = Intent(context, DocumentViewerActivity::class.java).apply {
                putExtra(EXTRA_URL, attachment.url)
                putExtra(EXTRA_NAME, attachment.name)
                putExtra(EXTRA_TYPE, attachment.attachmentType)
            }
            context.startActivity(intent)
        }
    }
}

private sealed interface ViewerState {
    data object Loading : ViewerState

    data class Ready(val file: File, val kind: DocumentKind) : ViewerState

    data class Failed(val message: String) : ViewerState
}

/**
 * 附件下载。
 *
 * 附件 URL 是**直链**（站点自己就是 `window.open(url)`），不需要带任何登录态，
 * 所以这里用一个独立的裸 OkHttp，不碰教务的 Cookie/会话。
 *
 * 缓存按「URL 指纹 + 原始文件名」落在 `cacheDir/attachments`：同一个附件
 * 重复点开不重新下载；系统清缓存时自然回收，不需要自己管过期。
 */
internal object DocumentLoader {

    /** 附件体量上限。超过这个量级基本是地址不对或被挂了别的文件。 */
    const val MAX_BYTES = 25L * 1024 * 1024

    fun cacheFile(context: Context, url: String, name: String): File {
        val dir = File(context.cacheDir, "attachments")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, cacheFileName(url, name))
    }

    suspend fun download(
        context: Context,
        url: String,
        name: String,
        onProgress: (Float) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val target = cacheFile(context, url, name)
        if (target.exists() && target.length() > 0L) {
            onProgress(1f)
            return@withContext target
        }
        if (target.exists()) target.delete()

        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("下载失败（HTTP ${response.code}）")
            val body = response.body ?: throw IOException("下载失败：没有内容")
            val total = body.contentLength()
            if (total > MAX_BYTES) throw IOException(sizeMessage())

            // 先写 .part，完整后才落正式名 —— 中途失败不会留下半个文件被当成缓存复用
            val temp = File(target.parentFile, "${target.name}.part")
            temp.outputStream().use { output ->
                val input = body.byteStream()
                val buffer = ByteArray(16 * 1024)
                var read = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count <= 0) break
                    read += count
                    if (read > MAX_BYTES) {
                        temp.delete()
                        throw IOException(sizeMessage())
                    }
                    output.write(buffer, 0, count)
                    if (total > 0) onProgress((read.toFloat() / total).coerceIn(0f, 1f))
                }
            }
            if (!temp.renameTo(target)) {
                // 极少见：跨文件系统等 rename 失败。直接用临时文件渲染，用完随缓存回收
                return@withContext temp
            }
            target
        }
    }

    private fun sizeMessage(): String =
        "附件超过 ${MAX_BYTES / 1024 / 1024} MB，请用电脑或其它应用打开"
}

@Composable
internal fun DocumentViewerScreen(
    url: String,
    name: String,
    attachmentType: Int,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var state by remember { mutableStateOf<ViewerState>(ViewerState.Loading) }
    var progress by remember { mutableFloatStateOf(0f) }
    var attempt by remember { mutableIntStateOf(0) }

    LaunchedEffect(url, attempt) {
        if (url.isBlank()) {
            state = ViewerState.Failed("附件地址无效")
            return@LaunchedEffect
        }
        state = ViewerState.Loading
        progress = 0f
        runCatching { DocumentLoader.download(context, url, name) { progress = it } }
            .onSuccess { file ->
                // 站点的 attachmentType 只是图标提示（0/6 都表示"没给"），
                // 下载完一律按内容再判一次，扩展名只作兜底
                state = ViewerState.Ready(file, DocumentSniffer.sniff(file, name))
            }
            .onFailure { state = ViewerState.Failed(it.message ?: "下载失败") }
    }

    GlassPageScaffold(
        title = name,
        subtitle = when (val current = state) {
            is ViewerState.Ready -> current.kind.label
            is ViewerState.Loading -> "正在下载 ${displayProgress(progress)}"
            is ViewerState.Failed -> null
        },
        onBack = onBack,
        actions = {
            val ready = state as? ViewerState.Ready
            if (ready != null) {
                SystemIconButton(
                    icon = Icons.Outlined.Share,
                    contentDescription = "分享附件",
                    onClick = { context.shareFile(ready.file, name) }
                )
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when (val current = state) {
                is ViewerState.Loading -> LoadingBody(progress)
                is ViewerState.Failed -> ErrorBody(
                    message = current.message,
                    onRetry = { attempt++ },
                )
                is ViewerState.Ready -> when (current.kind) {
                    DocumentKind.Word -> WordViewerScreen(current.file, name)
                    DocumentKind.Excel -> SheetViewerScreen(current.file, name)
                    DocumentKind.Pdf -> PdfViewerScreen(current.file)
                    DocumentKind.Image -> ImageViewerScreen(current.file, name)
                    DocumentKind.Text -> TextViewerScreen(current.file, name)
                    DocumentKind.Unknown -> UnsupportedBody(current.file, name)
                }
            }
        }
    }
}

private fun displayProgress(progress: Float): String = when {
    progress <= 0f -> "…"
    progress >= 1f -> "完成"
    else -> "${(progress * 100).toInt()}%"
}

@Composable
private fun LoadingBody(progress: Float) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PagePadding, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        GlassProgressBar(progress = progress, modifier = Modifier.fillMaxWidth())
        Text(
            text = "正在下载附件…",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ErrorBody(message: String, onRetry: () -> Unit) {
    SystemEmptyState(
        title = "附件打不开",
        message = message,
        icon = Icons.Outlined.CloudOff,
        action = {
            SystemSecondaryButton(text = "重试", onClick = onRetry, leadingIcon = {
                Icon(Icons.Outlined.Refresh, contentDescription = null)
            })
        }
    )
}

@Composable
private fun UnsupportedBody(file: File, name: String) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PagePadding),
    ) {
        SystemEmptyState(
            title = "这个格式内置查看器不支持",
            message = "旧版 .doc / .xls 是二进制格式，需要用 WPS 或 Office 打开。",
            icon = Icons.Outlined.Description,
            action = {
                SystemSecondaryButton(
                    text = "用其它应用打开",
                    onClick = { context.openFileExternally(file, name) }
                )
            }
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "文件已下载：${file.name}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
