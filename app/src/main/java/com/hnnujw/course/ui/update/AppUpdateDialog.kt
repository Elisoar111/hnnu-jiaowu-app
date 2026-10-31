package com.hnnujw.course.ui.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hnnujw.course.network.AppDownloader
import com.hnnujw.course.network.AppUpdateChecker
import com.hnnujw.course.network.AppUpdateInfo
import com.hnnujw.course.network.AppUpdateIntegrity
import com.hnnujw.course.ui.system.GlassProgressBar
import com.hnnujw.course.ui.system.MarkdownText
import com.hnnujw.course.ui.system.SystemDialog
import com.hnnujw.course.ui.system.SystemPrimaryButton
import com.hnnujw.course.ui.system.SystemSecondaryButton
import com.hnnujw.course.ui.system.rememberScreenMetrics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * 更新弹窗（液态玻璃外壳 + 参考项目的三段式信息布局）。
 *
 * 从「我的 → 检查更新」和应用启动时的自动检查两条路进来，行为一致：
 * 点「下载更新」后是**应用内下载**，不再把用户丢给浏览器 —— 浏览器会把 Gitee
 * 返回的 `Content-Type: application/zip` 当成扩展名依据，把 APK 存成压缩包。
 * 下载完成后先核对安装包，再唤起系统安装器。
 *
 * 布局照参考项目（BiliPai 的 Material3 渲染器）的骨架重排，而不是整体搬：
 * - 标题左对齐 + 右侧关闭钮（原来是居中大图标 + 居中标题，占掉正文三分之一高度）；
 * - 「当前版本 / 更新至」并排的版本卡，一眼看清从哪升到哪；
 * - 下载/校验状态收进一张独立的状态卡，SHA-256 的结论写在卡里而不是漂在正文末尾；
 * - 「本次更新」小标题 + 正文；「其他下载渠道」在最下面。
 *
 * 没抄的部分及原因：
 * - 参考项目自带 `parseUpdateReleaseNotes` 那套 Heading/Bullet/Divider 解析器 —— 本项目
 *   已有 [MarkdownText] 直接吃 Markdown，再移植一套解析器等于同一件事两份实现；
 * - `LinearProgressIndicator` —— 本项目有玻璃质感的 [GlassProgressBar]，换掉反而更不像这个 App；
 * - 参考项目「正式版 / 测试版」两个渠道 —— Gitee 只有一个发布页，硬凑第二个按钮是骗人，
 *   这里给的是「浏览器打开直链 / 前往发布页」两个**真实可用**的出路。
 *
 * 正文可滚动区高度按屏幕可用高算上限，保证短屏上主按钮始终完整可见 —— 更新提示里
 * 最重要的按钮被挤出屏幕，比正文少显示两行糟得多。
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

    // 整段可滚动区（版本卡 + 状态卡 + 说明）的高度上限。参考项目用 0.48f/180..380，
    // 本项目弹窗还要容纳标题行与两个按钮，压到 0.42f/170..340 才不会顶出屏幕。
    val contentMaxHeight = (screen.usableHeightDp * 0.42f).coerceIn(170.dp, 340.dp)

    val downloadState = AppDownloader.state
    // 已经下好过同一个版本就直接给「安装更新」，别让用户白等一次下载。
    var readyApk by remember(info.versionName) {
        mutableStateOf(AppDownloader.existingApk(context, info.versionName))
    }
    var hint by remember(info.versionName) { mutableStateOf<String?>(null) }
    /**
     * 是否遇到**真的走不通**的安装障碍（如系统没有可用的安装器）。
     *
     * 与 [hint] 分开记：`hint` 只是"上屏一句话"，其中"请允许安装未知应用"
     * 是**用户能自己解决**的（去系统设置授权后回来再点），不能因为它就把
     * 强制更新的弹窗放成可关闭 —— 否则最常遇到的那个拦路石正好成了后门。
     */
    var blockedPermanently by remember(info.versionName) { mutableStateOf(false) }
    // 下载协程句柄：「取消下载」要真的把传输掐掉，而不是只把界面关掉留个孤儿任务
    var downloadJob by remember(info.versionName) { mutableStateOf<Job?>(null) }
    /**
     * 最近一次安装包校验的结论（一句话）。
     *
     * 只在**装之前**由 [launchInstaller] 写入：校验通过写"已核对 SHA-256"，
     * 不通过写清楚为什么拦。发布说明没声明校验和时会如实说"改用签名校验"，
     * 而不是含糊地让人以为"已经验过了"。
     */
    var integrityNote by remember(info.versionName) { mutableStateOf<String?>(null) }
    /** 正在算校验和：这一小会儿主按钮不能再点，否则会并发拉起两次安装。 */
    var verifying by remember(info.versionName) { mutableStateOf(false) }
    /**
     * 校验连续失败的次数。
     *
     * 第一次失败只提示「重新下载」，因为更可能是传输截断；同一个版本连着两次对不上，
     * 基本可以判定是发布说明里的校验和写错了或包被替换 —— 这时如果还锁着强制更新的
     * 弹窗，用户就真的一条路都没有了，所以允许退出（`stuck` 里会看它）。
     */
    var integrityFailures by remember(info.versionName) { mutableStateOf(0) }

    // 直链才走内置下载器；没有 .apk 资产时 downloadUrl 是 Release 网页，只能交给浏览器。
    val directApkUrl = remember(info.downloadUrl) {
        info.downloadUrl.takeIf {
            it.substringBefore('?').endsWith(".apk", ignoreCase = true)
        }
    }
    // 版本卡里那行「安装包 xxx.apk」：从直链里摘文件名，摘不出来就不显示这行。
    val assetName = remember(info.downloadUrl) {
        directApkUrl?.substringBefore('?')?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
    }

    fun openExternal(url: String) {
        val opened = runCatching {
            context.startActivity(
                android.content.Intent(
                    android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse(url)
                )
            )
        }.isSuccess
        if (!opened) hint = "没有找到可用的浏览器，请手动打开：$url"
    }

    fun installNow(file: File) {
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
                // 系统连安装器都给不出来：这条路确实走不通，强制更新才允许退出弹窗，
                // 否则用户会卡在一个关不掉也点不动的框里。
                blockedPermanently = true
                hint = result.message
            }
        }
    }

    /**
     * 装之前先核对安装包（第 16 轮，借鉴参考项目的 build-verification 思路）。
     *
     * 参考项目会拿 Release 里的 `build-metadata.json` / `checksums.txt` 核对下载下来的
     * 安装包，对不上就不许装。Gitee 的 Release 没有那些元数据，所以这里退一步：
     * 发布说明里写了 `sha256: <64 位>` 就核对，**对不上直接拦住并清掉缓存包**，
     * 让用户点「重新下载」；没写就交给 [AppDownloader.install] 里原有的签名比对。
     *
     * 为什么要拦：强制更新把"不装"这个选项拿走了，那"装上去的确实是发布方构建的包"
     * 就必须能被证明。算一次 SHA-256 是几百毫秒的事，比装上一个被替换过的包便宜太多。
     */
    fun launchInstaller(file: File) {
        hint = null
        verifying = true
        scope.launch {
            val report = withContext(Dispatchers.IO) {
                AppUpdateIntegrity.verify(file, info.declaredSha256, info.versionName)
            }
            integrityNote = report.summary
            if (!report.canInstall) {
                // 校验不过的包必须扔掉：留着它，用户下次进来还是点"安装更新"，
                // 只会重复失败。
                runCatching { file.delete() }
                readyApk = null
                AppDownloader.reset()
                hint = report.summary
                integrityFailures += 1
                verifying = false
                return@launch
            }
            verifying = false
            installNow(file)
        }
    }

    fun startDownload() {
        val url = directApkUrl
        if (url == null) {
            // 兜底：没有可直连的 APK 资产，只能打开 Release 页让用户自己选。
            hint = "本次发布未提供安装包直链，已为你打开发布页面。"
            openExternal(info.downloadUrl)
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
        // 强制更新时「取消下载」只中止这一次传输，**不能**顺势把弹窗也关掉 ——
        // 那等于给了一个绕过强制更新的后门按钮。
        if (!info.forceUpdate) onDismiss()
    }

    val running = downloadState as? AppDownloader.State.Running
    val failed = (downloadState as? AppDownloader.State.Failed)
        ?.takeIf { readyApk == null }

    /**
     * 强制更新时弹窗不可关闭 —— 但**确实装不上时例外**。
     *
     * 「没有直链可下载」「下载失败」「系统没有可用安装器」这几种情况下如果还锁着弹窗，
     * 用户会卡在一个关不掉、也点不动的框里，比不弹这个版本还糟。所以只要真的
     * 无路可走，就把他放出去。
     */
    val stuck = directApkUrl == null || failed != null || blockedPermanently || integrityFailures >= 2
    val dismissible = !info.forceUpdate || stuck

    // 状态卡只在有状态可讲时出现：刚打开、什么都没发生时不该先摆一张空卡。
    val statusText = when {
        verifying -> "正在校验安装包…"
        running != null -> "正在下载安装包"
        failed != null -> "下载失败"
        readyApk != null -> "下载完成，可以安装"
        hint != null -> "需要处理"
        integrityNote != null -> "校验完成"
        else -> null
    }

    SystemDialog(
        onDismissRequest = { if (dismissible && running == null) onDismiss() },
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = "发现新版本",
                        fontSize = 19.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "版本 v${info.versionName}",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    // 强制更新是唯一会让弹窗不可关闭的情形，用错误色单列一行说清楚，
                    // 不塞进副标题里跟版本号混在一起。
                    if (info.forceUpdate) {
                        Text(
                            text = "本次更新需要升级后才能继续使用",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFFF44336)
                        )
                    }
                }
                // 强制更新时不给关闭钮：摆一个点了没反应的 X 比不摆更让人糊涂。
                if (dismissible) {
                    IconButton(
                        onClick = { if (running == null) onDismiss() },
                        modifier = Modifier.sizeIn(minWidth = 40.dp, minHeight = 40.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = "关闭",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            SystemPrimaryButton(
                text = when {
                    verifying -> "校验安装包…"
                    // 下载时把百分比直接印在按钮上，用户不用盯着进度条也知道走到哪了
                    running != null -> if (running.total > 0L) {
                        "下载中 ${(running.progress * 100).toInt()}%"
                    } else {
                        "下载中…"
                    }
                    readyApk != null -> "安装更新"
                    failed != null -> "重新下载"
                    directApkUrl == null -> "前往下载"
                    else -> "下载更新"
                },
                onClick = {
                    val apk = readyApk
                    if (apk != null) launchInstaller(apk) else startDownload()
                },
                enabled = running == null && !verifying
            )
        },
        dismissButton = if (dismissible) {
            {
                // 这里必须是「取消下载」而不是「后台下载」：下载协程挂在
                // rememberCoroutineScope() 上，弹窗一旦离开组合树，作用域就被取消，
                // 下载**会真的断掉**。写成"后台下载"会让用户以为它还在后台跑，其实早就停了。
                // 想做成真·后台下载，得把任务挪到进程级作用域，并在完成时发通知。
                SystemSecondaryButton(
                    text = if (running != null) "取消下载" else "稍后",
                    onClick = { if (running != null) cancelDownload() else onDismiss() },
                    enabled = true
                )
            }
        } else null,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = contentMaxHeight)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                VersionSummary(
                    currentVersion = currentVersion,
                    latestVersion = info.versionName,
                    assetName = assetName
                )
                if (statusText != null) {
                    StatusSummary(
                        statusText = statusText,
                        running = running,
                        downloaded = running == null && readyApk != null,
                        checksumDeclared = info.declaredSha256 != null,
                        integrityNote = integrityNote.takeIf { hint == null },
                        message = hint ?: failed?.message
                    )
                }
                ReleaseNotes(notes = info.notes)
            }
            DownloadChannels(
                onOpenDirect = { openExternal(info.downloadUrl) },
                onOpenReleasePage = { openExternal(AppUpdateChecker.RELEASE_PAGE) }
            )
        }
    }
}

/**
 * 版本卡：「当前版本 / 更新至」并排 + 安装包文件名。
 *
 * 参考项目把这两个值做成并排的两栏而不是一句"从 A 升到 B"：升级前后都是用户关心的
 * 事实，并排能直接对读；写成句子会被折行，短屏上正好断在两版之间。
 */
@Composable
private fun VersionSummary(
    currentVersion: String,
    latestVersion: String,
    assetName: String?
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                VersionValue(
                    label = "当前版本",
                    value = "v$currentVersion",
                    emphasized = false,
                    modifier = Modifier.weight(1f)
                )
                VersionValue(
                    label = "更新至",
                    value = "v$latestVersion",
                    emphasized = true,
                    modifier = Modifier.weight(1f)
                )
            }
            if (assetName != null) {
                Text(
                    text = "安装包  $assetName",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    softWrap = false,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun VersionValue(
    label: String,
    value: String,
    emphasized: Boolean,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            fontSize = 16.sp,
            fontWeight = if (emphasized) FontWeight.SemiBold else FontWeight.Medium,
            // 「更新至」用主色强调：目标版本才是这一屏的主角，当前版本只是参照。
            color = if (emphasized) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            softWrap = false
        )
    }
}

/**
 * 状态卡：下载进度 / 失败原因 / 校验结论。
 *
 * 用 `primaryContainer` 铺底是参考项目的做法：进度与错误属于"正在发生的事"，
 * 视觉上要比正文的说明性文字更靠前；本项目里这张卡放在玻璃弹窗内部，
 * 实色底也正好符合「弹窗内控件一律实色、避免玻璃叠玻璃发糊」的既有约定。
 */
@Composable
private fun StatusSummary(
    statusText: String,
    running: AppDownloader.State.Running?,
    downloaded: Boolean,
    checksumDeclared: Boolean,
    integrityNote: String?,
    message: String?
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = statusText,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
                if (running != null && running.total > 0L) {
                    Text(
                        text = "${(running.progress * 100).toInt()}%",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            if (running != null) {
                GlassProgressBar(
                    progress = if (running.total > 0L) {
                        running.progress
                    } else {
                        running.indeterminateProgress
                    },
                    height = 6.dp
                )
                // 已下载 / 总量 · 速度 · 预计剩余：让下载过程可解释，而不是一条光会动的条
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
                    lineHeight = 17.sp,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // 校验和这一行必须区分三种情况：已核对通过 / 本次没提供 / 还没到核对的时候。
            // 含糊成一句"已校验"就是在拿用户的信任换省事。
            val checksumText = when {
                integrityNote != null -> integrityNote
                downloaded && checksumDeclared -> "SHA-256 完整性校验已通过"
                downloaded -> "此版本未提供 SHA-256 校验信息，将改用安装包签名校验"
                checksumDeclared -> "发布说明已提供 SHA-256，下载完成后会自动核对"
                else -> null
            }
            if (checksumText != null) {
                Text(
                    text = checksumText,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            if (message != null) {
                Text(
                    text = message,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    color = Color(0xFFD32F2F),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun ReleaseNotes(notes: String) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "本次更新",
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        // Release 说明可以带 Markdown（**加粗** / - 列表 / ## 小标题），
        // 由 MarkdownText 决定走富文本还是普通 Text：不含标记时与以前完全一致。
        MarkdownText(
            text = notes.ifBlank { "本次更新暂无详细说明，建议更新到最新版本。" },
            fontSize = 13.sp,
            // 固定高度容器里的文字必须显式给 lineHeight，否则会继承默认行高
            // 在测量阶段撑破约束，表现为"内容看不见"。
            lineHeight = 20.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/**
 * 其他下载渠道。
 *
 * 参考项目在这里放「正式版 / 测试版」两个 GitHub 渠道；本项目只有一个 Gitee 发布页，
 * 所以给的是「浏览器打开直链」与「前往发布页」—— 两个都是真能点通的路，
 * 对应内置下载器不合用（比如公司网络拦了直连）时的两种退路。
 */
@Composable
private fun DownloadChannels(
    onOpenDirect: () -> Unit,
    onOpenReleasePage: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "其他下载渠道",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = onOpenDirect,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 44.dp)
            ) {
                Text(text = "浏览器打开", fontSize = 13.sp, maxLines = 1)
            }
            OutlinedButton(
                onClick = onOpenReleasePage,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 44.dp)
            ) {
                Text(text = "前往发布页", fontSize = 13.sp, maxLines = 1)
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