package com.hnnujw.course.ui.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hnnujw.course.network.AppDownloader
import com.hnnujw.course.network.AppUpdateInfo
import com.hnnujw.course.ui.system.GlassProgressBar
import com.hnnujw.course.ui.system.SystemDialog
import com.hnnujw.course.ui.system.SystemPrimaryButton
import com.hnnujw.course.ui.system.SystemSecondaryButton
import com.hnnujw.course.ui.system.rememberScreenMetrics
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

/**
 * 更新弹窗（液态玻璃）。
 *
 * 从「我的 → 检查更新」和应用启动时的自动检查两条路进来，行为一致：
 * 点「立即更新」后是**应用内下载**，不再把用户丢给浏览器 —— 浏览器会把 Gitee
 * 返回的 `Content-Type: application/zip` 当成扩展名依据，把 APK 存成压缩包。
 * 下载完成后直接唤起系统安装器。
 *
 * 正文区高度按屏幕可用高算上限，保证短屏上「立即更新 / 以后再说」两个按钮始终
 * 完整可见 —— 更新提示里最重要的按钮被挤出屏幕，比正文少显示两行糟得多。
 */
@Composable
fun AppUpdateDialog(
    info: AppUpdateInfo,
    currentVersion: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val screen = rememberScreenMetrics()

    // 320dp 级弹窗在 16:9 短屏上留给正文的余量：固定 300dp 会把按钮顶出屏幕。
    val notesMaxHeight = (screen.usableHeightDp * 0.32f).coerceIn(120.dp, 300.dp)

    val downloadState = AppDownloader.state
    // 已经下好过同一个版本就直接给「安装更新」，别让用户白等一次下载。
    var readyApk by remember(info.versionName) {
        mutableStateOf(AppDownloader.existingApk(context, info.versionName))
    }
    var hint by remember(info.versionName) { mutableStateOf<String?>(null) }
    // 下载协程句柄：「取消下载」要真的把传输掐掉，而不是只把界面关掉留个孤儿任务
    var downloadJob by remember(info.versionName) { mutableStateOf<Job?>(null) }

    // 直链才走内置下载器；没有 .apk 资产时 downloadUrl 是 Release 网页，只能交给浏览器。
    val directApkUrl = remember(info.downloadUrl) {
        info.downloadUrl.takeIf {
            it.substringBefore('?').endsWith(".apk", ignoreCase = true)
        }
    }

    fun launchInstaller(file: File) {
        hint = null
        when (val result = AppDownloader.install(context, file)) {
            is AppDownloader.InstallResult.Launched -> onDismiss()
            AppDownloader.InstallResult.NeedUnknownSourcePermission ->
                hint = "请在系统设置里允许本应用「安装未知应用」，回来后点「安装更新」继续。"
            is AppDownloader.InstallResult.Unavailable -> {
                // 包本身有问题（损坏、被系统清理）才退回「重新下载」，否则按钮会一直停在
                // 「安装更新」上，点几次都一样。只是"没有可用安装器"时保留已下好的包，
                // 用户还能自己去文件管理器里手动装。
                if (readyApk?.let { AppDownloader.isValidApk(it) } != true) {
                    readyApk = null
                    AppDownloader.reset()
                }
                hint = result.message
            }
        }
    }

    fun startDownload() {
        val url = directApkUrl
        if (url == null) {
            // 兜底：没有可直连的 APK 资产，只能打开 Release 页让用户自己选。
            hint = "本次发布未提供安装包直链，已为你打开下载页面。"
            runCatching {
                context.startActivity(
                    android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse(info.downloadUrl)
                    )
                )
            }
            return
        }
        hint = null
        downloadJob = scope.launch {
            val file = AppDownloader.download(context, url, info.versionName)
            downloadJob = null
            if (file != null) {
                readyApk = file
                launchInstaller(file)
            }
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        AppDownloader.reset()
        onDismiss()
    }

    val running = downloadState as? AppDownloader.State.Running
    val failed = (downloadState as? AppDownloader.State.Failed)
        ?.takeIf { readyApk == null }

    SystemDialog(
        onDismissRequest = { if (running == null) onDismiss() },
        icon = {
            Surface(
                shape = CircleShape,
                color = Color(0xFFE3F2FD),
                shadowElevation = 4.dp,
                modifier = Modifier.size(64.dp)
            ) {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.SystemUpdate,
                        contentDescription = null,
                        modifier = Modifier.size(32.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        },
        title = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "发现新版本 v${info.versionName}",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = "当前版本 v$currentVersion",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        },
        confirmButton = {
            SystemPrimaryButton(
                text = when {
                    // 下载时把百分比直接印在按钮上，用户不用盯着进度条也知道走到哪了
                    running != null -> if (running.total > 0L) {
                        "下载中 ${(running.progress * 100).toInt()}%"
                    } else {
                        "下载中…"
                    }
                    readyApk != null -> "安装更新"
                    failed != null -> "重新下载"
                    else -> "立即更新"
                },
                onClick = {
                    val apk = readyApk
                    if (apk != null) launchInstaller(apk) else startDownload()
                },
                enabled = running == null
            )
        },
        dismissButton = {
            // 这里必须是「取消下载」而不是「后台下载」：下载协程挂在
            // rememberCoroutineScope() 上，弹窗一旦离开组合树，作用域就被取消，
            // 下载**会真的断掉**。写成"后台下载"会让用户以为它还在后台跑，其实早就停了。
            // 想做成真·后台下载，得把任务挪到进程级作用域，并在完成时发通知。
            SystemSecondaryButton(
                text = if (running != null) "取消下载" else "以后再说",
                onClick = { if (running != null) cancelDownload() else onDismiss() },
                enabled = true
            )
        }
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // 进度条：只在真正下载时出现，避免静止状态下多一条没意义的横线。
            if (running != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "正在下载安装包",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = if (running.total > 0L) {
                            "${(running.progress * 100).toInt()}%"
                        } else {
                            formatSize(running.bytes)
                        },
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                GlassProgressBar(
                    progress = if (running.total > 0L) running.progress else running.indeterminateProgress,
                    height = 8.dp
                )
                Spacer(modifier = Modifier.height(6.dp))
                // 第二行给"已下载 / 总量 · 速度 · 预计剩余"，让下载过程可解释
                Text(
                    text = buildString {
                        append(formatSize(running.bytes))
                        if (running.total > 0L) {
                            append(" / ")
                            append(formatSize(running.total))
                        }
                        if (running.bytesPerSecond > 0L) {
                            append("  ·  ")
                            append(formatSpeed(running.bytesPerSecond))
                        }
                        running.remainingSeconds?.let {
                            append("  ·  剩余约 ")
                            append(formatDuration(it))
                        }
                    },
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(14.dp))
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = notesMaxHeight)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = info.notes.ifBlank { "本次更新暂无详细说明，建议更新到最新版本。" },
                    fontSize = 14.sp,
                    // 固定高度容器里的文字必须显式给 lineHeight，否则会继承默认行高
                    // 在测量阶段撑破约束，表现为"内容看不见"。
                    lineHeight = 21.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            val message = hint ?: failed?.message
            if (message != null) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = message,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    color = Color(0xFFF44336),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

private fun formatSize(bytes: Long): String =
    String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)

private fun formatSpeed(bytesPerSecond: Long): String =
    String.format(Locale.US, "%.1f MB/s", bytesPerSecond / 1024.0 / 1024.0)

private fun formatDuration(seconds: Long): String =
    if (seconds >= 60L) "${seconds / 60L} 分 ${seconds % 60L} 秒" else "$seconds 秒"
