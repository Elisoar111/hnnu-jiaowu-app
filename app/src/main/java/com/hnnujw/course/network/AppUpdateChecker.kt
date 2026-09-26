package com.hnnujw.course.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Gitee 最新 Release 的摘要。 */
data class AppUpdateInfo(
    /** 版本名，如 "1.1.8"（tag 的 v 前缀已剥掉）。 */
    val versionName: String,
    /** Release 说明，已剥掉 Markdown 符号，可直接纯文本展示。 */
    val notes: String,
    /** APK 资产直链；找不到 .apk 资产时退回 Release 网页。 */
    val downloadUrl: String,
    /**
     * 强制更新：弹窗不可关闭，必须走一次下载安装才能继续用。
     *
     * 用来兜"不升级就没法用"的版本（例如教务端改了协议、旧版的登录直接失败）。
     * 这**不是**安全边界 —— 用户永远可以直接杀掉进程，它只表达"请务必更新"。
     */
    val forceUpdate: Boolean = false
)

/**
 * 「检查更新」：从 Gitee 拉取最新 Release 与当前安装版本比较。
 *
 * 接口：GET /api/v5/repos/{owner}/{repo}/releases/latest，匿名即可（公开仓库）。
 * 版本比较按点分段逐段比数字（1.2.10 > 1.2.9），任何一段不是数字就保守地
 * 判为"不提示"，避免把 `beta`、`rc1` 之类的 tag 误报成更新。
 */
object AppUpdateChecker {

    private const val TAG = "AppUpdateChecker"

    const val RELEASE_PAGE = "https://gitee.com/Elisoar/hnnu-jiaowu-app/releases"
    private const val LATEST_API = "https://gitee.com/api/v5/repos/Elisoar/hnnu-jiaowu-app/releases/latest"

    private val USER_AGENT =
        "Mozilla/5.0 (Linux; Android ${android.os.Build.VERSION.RELEASE}) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    sealed class Result {
        data class Update(val info: AppUpdateInfo) : Result()
        data class UpToDate(val versionName: String) : Result()
        data class Failure(val message: String) : Result()
    }

    /**
     * 「强制更新」标记。在 `release-notes/` 的 `## notes` 区块里独立占一行写上它，
     * 这一版就会被所有客户端当成必须更新的版本。
     *
     * 标记只认**独立成行**（前后可以留空白），所以正文里正常提到这个词不会被误判；
     * 展示给用户的说明里这一行会被 [notesForDisplay] 摘掉，不会露出一串方括号。
     *
     * 选方括号而不是 `#` 开头，是因为 `scripts/release_notes.sh` 的 `notes_violations`
     * 会拒绝行首带 `#` 的说明（`## notes` 区块靠 `^## <段名>` 切分，notes 里出现
     * `### xxx` 会让剩下的内容在抽取时被整段丢掉）。
     */
    const val FORCE_UPDATE_MARKER = "[force-update]"

    private val forceUpdateLine = Regex("(?im)^[ \\t]*\\[force-update][ \\t]*$")

    /** Release 正文里是否声明了强制更新。 */
    fun isForceUpdate(body: String): Boolean = forceUpdateLine.containsMatchIn(body)

    suspend fun check(currentVersion: String): Result = withContext(Dispatchers.IO) {
        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
        try {
            val body = client.newCall(
                Request.Builder().url(LATEST_API).header("User-Agent", USER_AGENT).build()
            ).execute().use { resp ->
                // Gitee 的 releases/latest 在仓库还没有任何已发布 Release 时返回 404，
                // 这不是网络故障，给出明确提示而不是笼统的"检查更新失败"。
                if (resp.code == 404) return@withContext Result.Failure("Gitee 仓库还没有发布任何版本")
                if (!resp.isSuccessful) return@withContext Result.Failure("服务返回 ${resp.code}，请稍后重试")
                resp.body?.string().orEmpty()
            }
            val json = JSONObject(body)
            val tag = json.optString("tag_name", "").removePrefix("v").removePrefix("V").trim()
            if (tag.isEmpty()) return@withContext Result.Failure("未解析到版本号")
            val rawBody = json.optString("body", "")
            val force = isForceUpdate(rawBody)
            val notes = notesForDisplay(rawBody)
            // 只有 .apk 资产才是安装包。同一个 Release 下还有 v1.2.2.zip / .tar.gz
            // 两个**源码包**，它们排在前面的可能性不小，所以这里必须按扩展名严格挑，
            // 挑不到就退回 Release 网页让用户自己选，绝不能把源码包当安装包下下来。
            val assetUrl = json.optJSONArray("assets")?.let { arr ->
                (0 until arr.length()).asSequence()
                    .mapNotNull { arr.optJSONObject(it) }
                    .firstOrNull { it.optString("name", "").endsWith(".apk", ignoreCase = true) }
                    ?.optString("browser_download_url")
                    ?.takeIf { it.isNotBlank() }
            }
            val downloadUrl = assetUrl ?: RELEASE_PAGE
            if (isNewer(tag, currentVersion)) {
                Result.Update(AppUpdateInfo(tag, notes, downloadUrl, forceUpdate = force))
            } else {
                Result.UpToDate(tag)
            }
        } catch (e: Exception) {
            Log.w(TAG, "check update failed", e)
            Result.Failure(if (e.message.isNullOrBlank()) "网络异常，请稍后重试" else e.message ?: "网络异常")
        }
    }

    fun isNewer(remote: String, current: String): Boolean {
        val r = remote.split('.').map { it.trim().toIntOrNull() }
        val c = current.split('.').map { it.trim().toIntOrNull() }
        if (r.any { it == null } || c.any { it == null }) return false
        for (i in 0 until maxOf(r.size, c.size)) {
            val rv = r.getOrElse(i) { 0 } ?: 0
            val cv = c.getOrElse(i) { 0 } ?: 0
            if (rv != cv) return rv > cv
        }
        return false
    }

    /**
     * Release 正文里给用户看的那一半：只摘掉 [FORCE_UPDATE_MARKER] 标记行，其余原样保留。
     *
     * 以前这里会把 Markdown 一律剥成纯文本（标题号、`**加粗**`、行内代码、链接语法，
     * 列表符号换成「·」），因为弹窗那时只能用普通 `Text` 渲染，留着标记就会露出一串
     * 星号和井号。现在弹窗走 `MarkdownText`，**保留标记才是对的**：说明里写加粗和列表
     * 就是为了让层次看得出来，剥掉等于白写。
     *
     * 唯一必须剥的仍然只有标记行——那是给机器看的。
     */
    fun notesForDisplay(body: String): String = body
        .replace(forceUpdateLine, "")
        .lines()
        .joinToString("\n")
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()
}
