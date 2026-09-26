package com.hnnujw.course.network

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * 应用内下载器：把新版 APK 直接下到应用自己的目录，再交给系统安装器。
 *
 * ## 为什么不把用户送去浏览器
 *
 * Gitee 附件的响应头是 `Content-Type: application/zip` —— APK 本来就是 zip 容器，
 * 服务端按容器类型给了 MIME。而手机浏览器保存文件时**按 MIME 定扩展名**，会忽略
 * `Content-Disposition: attachment; filename="app-release.apk"`，于是下载落地成一个
 * `.zip`，用户点开只有解压软件。这条链路上 Gitee 的行为我们改不了，所以把下载这一
 * 步收回应用自己做：不管服务端声明什么 MIME，一律按 `.apk` 存盘并校验，再走安装器。
 *
 * ## 落盘位置与 FileProvider
 *
 * `getExternalFilesDir("Download")` 对应 `res/xml/file_paths.xml` 里已有的
 * `<external-files-path name="downloads" path="Download/" />`，所以 FileProvider
 * 能直接把这个文件授权给系统安装器，不需要任何存储权限。
 *
 * ## 为什么还要往公共「下载」目录写一份（[publishToPublicDownloads]）
 *
 * 私有目录里的文件**会在卸载时被系统一并删除**。正常升级是覆盖安装、安装器读私有
 * 目录里的文件即可，所以这件事一直不是问题；但 v1.2.6 换了发布签名：新旧包签名不
 * 一致，覆盖安装必定失败，用户必须先卸载旧版 —— 那一刻私有目录里的安装包就没了，
 * 「下载 → 卸载 → 安装」这条链正好断在最后一步。因此下载成功后额外写一份到系统
 * 「下载」目录（不随卸载消失），用户卸载完从「文件管理 → 下载」点它即可安装。
 */
object AppDownloader {

    private const val TAG = "AppDownloader"

    /**
     * 小于这个体积一律判为错误页 / 占位文件。真实 APK 在 4~15MB 量级，
     * 留 512KB 的下限只是为了挡住"服务端返回了一段 HTML"这种最坏情况。
     */
    private const val MIN_APK_BYTES = 512L * 1024L

    /** zip 本地文件头魔数，APK 必然以它开头。 */
    private val ZIP_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04)

    private const val APK_MIME = "application/vnd.android.package-archive"

    /**
     * 公共「下载」目录里那份备份的文件名前缀。
     *
     * 与私有目录里的 `hnnu-jiaowu-v<版本>.apk` 同名，用户一眼就知道是自己刚下的
     * 更新包；同时用它做前缀清理，保证「下载」目录里只留最新的一份、不随版本堆积。
     * 改这里要连着 [apkFileName] 一起看。
     */
    private const val PUBLIC_COPY_PREFIX = "hnnu-jiaowu-v"

    /** Gitee/镜像站对裸请求体不太友好，带上 UA 与下载站常见身份。 */
    private val USER_AGENT =
        "Mozilla/5.0 (Linux; Android ${Build.VERSION.RELEASE}) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    sealed interface State {
        /** 还没开始。 */
        data object Idle : State

        /** [total] 为 0 表示服务端没给 Content-Length，此时只能显示已下载字节数。 */
        data class Running(
            val bytes: Long,
            val total: Long,
            /** 整段下载的平均速度（字节/秒），起步 500ms 内为 0 不显示。 */
            val bytesPerSecond: Long = 0L
        ) : State {
            val progress: Float
                get() = if (total > 0L) (bytes.toFloat() / total).coerceIn(0f, 1f) else 0f

            /** 传输未知时给一个缓慢爬升的假进度，避免进度条一直停在 0 看着像卡死。 */
            val indeterminateProgress: Float
                get() = (bytes.toFloat() / (8f * 1024f * 1024f)).coerceIn(0.02f, 0.92f)

            /** 按平均速度估算的剩余秒数；样本不足或总长未知时返回 null。 */
            val remainingSeconds: Long?
                get() {
                    if (total <= 0L || bytesPerSecond <= 0L || bytes >= total) return null
                    return ((total - bytes) / bytesPerSecond).coerceAtLeast(1L)
                }
        }

        data class Done(val file: File) : State

        data class Failed(val message: String) : State
    }

    /** 全局下载状态，供更新弹窗直接观察。 */
    var state by mutableStateOf<State>(State.Idle)
        private set

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        // 附件链路是 302 → 302 → CDN，必须跟随；跨站跳转也要跟（gitee.com → foruda.gitee.com）。
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    /**
     * 下载目录。file_paths.xml 里**两个**条目对应这里的两种落点：
     * `external-files-path downloads`（正常）与 `files-path internal_downloads`
     * （外部存储不可用时的兜底）。改这里的相对名要把两处一起改。
     */
    fun downloadDir(context: Context): File {
        val dir = context.getExternalFilesDir("Download") ?: File(context.filesDir, "Download")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** 本版本已下好**且校验通过**的安装包，用于「已下载，直接安装」。 */
    fun existingApk(context: Context, versionName: String): File? =
        File(downloadDir(context), apkFileName(versionName)).takeIf { isValidApk(it) }

    private fun apkFileName(versionName: String) = "hnnu-jiaowu-v$versionName.apk"

    fun reset() {
        state = State.Idle
    }

    /**
     * 下载 [url] 指向的 APK，成功返回落盘文件，失败返回 null 并把原因写进 [state]。
     *
     * 全程在 IO 调度器上跑；调用方取消协程即中断下载，半截文件会被删掉。
     * 进度通过 [state] 发布，内部按 150ms 节流，避免高频重组。
     */
    suspend fun download(context: Context, url: String, versionName: String): File? {
        // 先（在调用方线程上）把状态切到 Running，再进 IO 干活。
        // 这一步不能拖进 withContext 里面：否则"按钮已按下、状态还没更新"的那个窗口里，
        // 确认按钮仍然是 enabled 的，连点两下会起两个协程写同一个目标文件 —— 下出来的
        // 包必定是坏的（两个流交叉写）。放在这里，窗口就没了。
        state = State.Running(0L, 0L)
        return withContext(Dispatchers.IO) {
            val app = context.applicationContext
            // 换版本时清掉旧包，避免 user 装到上一版的残留文件。
            downloadDir(app).listFiles()
                ?.filter { it.name.startsWith("hnnu-jiaowu-v") && it.name != apkFileName(versionName) }
                ?.forEach { runCatching { it.delete() } }

            val target = File(downloadDir(app), apkFileName(versionName))
            try {
                if (target.exists() && !target.delete()) {
                    return@withContext fail("无法清理上一次的安装包，请手动删除后重试")
                }
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", USER_AGENT)
                    .header(
                        "Accept",
                        "application/vnd.android.package-archive,application/octet-stream,*/*"
                    )
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext fail("下载失败：服务返回 ${response.code}")
                    }
                    val body = response.body ?: return@withContext fail("下载失败：响应内容为空")
                    val declared = body.contentLength().takeIf { it > 0L } ?: 0L
                    if (declared in 1 until MIN_APK_BYTES) {
                        return@withContext fail("下载到的文件只有 ${declared} 字节，不像是安装包")
                    }

                    var written = 0L
                    var lastEmit = 0L
                    val startedAt = System.currentTimeMillis()
                    body.byteStream().use { input ->
                        target.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                coroutineContext.ensureActive()
                                val read = input.read(buffer)
                                if (read == -1) break

                                // 首块就验魔数：服务端返回网页 / 错误 JSON 时立刻止损，
                                // 而不是把一坨 HTML 存成 .apk 让用户在安装器里报错。
                                if (written == 0L && !looksLikeZip(buffer, read)) {
                                    return@withContext fail("服务端返回的不是安装包（可能是网页或源码压缩包）")
                                }

                                output.write(buffer, 0, read)
                                written += read

                                val now = System.currentTimeMillis()
                                if (now - lastEmit >= 150L) {
                                    lastEmit = now
                                    state = State.Running(written, declared, speedOf(written, startedAt, now))
                                }
                            }
                            output.flush()
                        }
                    }

                    if (declared > 0L && written < declared) {
                        return@withContext fail("下载中断：只收到 $written/$declared 字节")
                    }
                    if (written < MIN_APK_BYTES) {
                        return@withContext fail("下载到的安装包不完整（$written 字节）")
                    }
                }

                // 落盘后再按 zip 容器真解一次：只比体积挡不住"服务器/代理截断但
                // 内容仍大于 512KB"这种情况，那种包装到一半会在安装器里报解析失败。
                if (!isValidApk(target)) {
                    return@withContext fail("下载到的安装包已损坏，请重新下载")
                }
                // 再往公共「下载」目录留一份：换签名的版本必须先卸载旧版才能装，
                // 而私有目录里的文件会被卸载一并删掉（详见文件头注释）。
                publishToPublicDownloads(app, target)
                state = State.Done(target)
                target
            } catch (e: Exception) {
                runCatching { if (target.exists()) target.delete() }
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w(TAG, "download failed", e)
                fail(
                    when {
                        e.message.isNullOrBlank() -> "网络异常，请稍后重试"
                        else -> e.message!!
                    }
                )
            }
        }
    }

    /**
     * 安装包完整性校验：**真的当 zip 解一次**，能读出中央目录且含 AndroidManifest.xml 才算合格。
     *
     * 为什么不能只看体积：下载中途进程被杀 / 协程被取消后来又没清干净，会留下一个
     * 「大于 512KB 但残缺」的文件；下次打开弹窗 [existingApk] 会把它当成下好的包，
     * 直接给用户一个「安装更新」按钮，点下去只能在系统安装器里看到"解析软件包时出现问题"。
     * ZipFile 打开时会读文件尾部的中央目录，被截断的文件在这里就会抛异常。
     */
    fun isValidApk(file: File): Boolean {
        if (!file.isFile || file.length() < MIN_APK_BYTES) return false
        return runCatching {
            java.util.zip.ZipFile(file).use { zip -> zip.getEntry("AndroidManifest.xml") != null }
        }.getOrDefault(false)
    }

    private fun looksLikeZip(buffer: ByteArray, length: Int): Boolean {
        if (length < ZIP_MAGIC.size) return false
        return ZIP_MAGIC.indices.all { buffer[it] == ZIP_MAGIC[it] }
    }

    /**
     * 把刚落盘的安装包再往系统公共「下载」目录写一份（best-effort，失败不影响下载结果）。
     *
     * 为什么不能只靠私有目录那一份：私有目录（`getExternalFilesDir` / `filesDir`）里的
     * 文件会在**卸载时被系统一并删除**，而换发布签名的版本必须先卸载旧版才能覆盖安装 ——
     * 用户卸载的那一刻，安装包就没了。写一份到公共「下载」目录后，文件不随卸载消失，
     * 卸载完从「文件管理 → 下载」点它即可安装。
     *
     * 权限分级：Android 10（API 29）起 `MediaStore.Downloads` 免存储权限即可写公共目录；
     * API 29 以下写公共目录需要 `WRITE_EXTERNAL_STORAGE`，本项目刻意不申请任何存储权限，
     * 因此那些设备直接跳过（这条路上仍用"浏览器到发布页下载"的老办法）。
     *
     * 只用 `IS_PENDING` 做原子落盘：先占位、写完再置 0，避免用户在写一半时从下载目录里
     * 点到半截文件。清理与写入都包在 runCatching 里 —— 这里任何一步失败都不该让用户
     * 重新下一遍，安装用的始终是私有目录里那份。
     */
    private fun publishToPublicDownloads(context: Context, file: File) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI

        // 先清掉以前留下的备份：否则每升一次版本就在「下载」目录里多堆一个 27MB 的包。
        // 只删得到本应用自己创建的条目（别家的行会抛 SecurityException），所以包一层。
        runCatching {
            resolver.delete(
                collection,
                "${MediaStore.Downloads.DISPLAY_NAME} LIKE ?",
                arrayOf("$PUBLIC_COPY_PREFIX%")
            )
        }.onFailure { Log.w(TAG, "clean previous public copy failed", it) }

        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, file.name)
            put(MediaStore.Downloads.MIME_TYPE, APK_MIME)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = runCatching { resolver.insert(collection, values) }
            .onFailure { Log.w(TAG, "reserve public download slot failed", it) }
            .getOrNull() ?: return

        runCatching {
            val output = resolver.openOutputStream(uri) ?: error("openOutputStream 返回 null")
            output.use { out -> file.inputStream().use { input -> input.copyTo(out) } }
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
                null,
                null
            )
            Log.i(TAG, "published a copy to public Downloads: ${file.name}")
        }.onFailure {
            Log.w(TAG, "publish to public Downloads failed", it)
            // 半截文件不能留在「下载」里：用户点它会看到"解析软件包时出现问题"。
            runCatching { resolver.delete(uri, null, null) }
        }
    }

    /**
     * 整段下载的平均速度（字节/秒）。
     *
     * 必须真的算出来：之前 [State.Running.bytesPerSecond] 一直停在默认值 0，
     * 弹窗里辛苦拼的「· 1.2 MB/s · 剩余约 8 秒」那两段**永远不会出现**。
     * 前 300ms 样本太短，先返回 0 让界面不显示，避免开局跳一个失真的数字。
     */
    private fun speedOf(written: Long, startedAt: Long, now: Long): Long {
        val elapsed = now - startedAt
        if (elapsed < 300L || written <= 0L) return 0L
        return written * 1000L / elapsed
    }

    private fun fail(message: String): Nothing? {
        Log.w(TAG, "download aborted: $message")
        state = State.Failed(message)
        return null
    }

    sealed interface InstallResult {
        /** 安装器已拉起（用户还要在系统界面上点一次「安装」）。 */
        data object Launched : InstallResult
        /** 需要先去系统设置里允许本应用「安装未知应用」，已替用户跳转过去。 */
        data object NeedUnknownSourcePermission : InstallResult
        data class Unavailable(val message: String) : InstallResult
    }

    /**
     * 唤起系统安装器。
     *
     * Android 8.0 起安装第三方 APK 需要 `REQUEST_INSTALL_PACKAGES`（清单已声明）
     * **并且**用户在系统设置里为本应用打开「安装未知应用」。没有这个开关时直接
     * 拉起安装器会静默失败，所以这里先探测再跳设置页，而不是让用户对着没反应的
     * 屏幕发呆。
     */
    fun install(context: Context, file: File): InstallResult {
        if (!isValidApk(file)) {
            return InstallResult.Unavailable("安装包不完整或已损坏，请点「重新下载」")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            val opened = runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                        .setData(android.net.Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }.isSuccess
            return if (opened) {
                InstallResult.NeedUnknownSourcePermission
            } else {
                InstallResult.Unavailable("请到系统设置里允许本应用安装未知来源应用后重试")
            }
        }
        return runCatching {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            context.startActivity(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, APK_MIME)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        }.fold(
            onSuccess = { InstallResult.Launched },
            onFailure = { error ->
                Log.w(TAG, "install failed", error)
                InstallResult.Unavailable("没有找到可用的安装器，请到文件管理器里手动打开")
            }
        )
    }
}
