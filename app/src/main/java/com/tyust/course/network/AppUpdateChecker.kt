package com.tyust.course.network

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
    val downloadUrl: String
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

    sealed class Result {
        data class Update(val info: AppUpdateInfo) : Result()
        data class UpToDate(val versionName: String) : Result()
        data class Failure(val message: String) : Result()
    }

    suspend fun check(currentVersion: String): Result = withContext(Dispatchers.IO) {
        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
        try {
            val body = client.newCall(Request.Builder().url(LATEST_API).build()).execute().use { resp ->
                // Gitee 的 releases/latest 在仓库还没有任何已发布 Release 时返回 404，
                // 这不是网络故障，给出明确提示而不是笼统的"检查更新失败"。
                if (resp.code == 404) return@withContext Result.Failure("Gitee 仓库还没有发布任何版本")
                if (!resp.isSuccessful) return@withContext Result.Failure("服务返回 ${resp.code}，请稍后重试")
                resp.body?.string().orEmpty()
            }
            val json = JSONObject(body)
            val tag = json.optString("tag_name", "").removePrefix("v").removePrefix("V").trim()
            if (tag.isEmpty()) return@withContext Result.Failure("未解析到版本号")
            val notes = stripMarkdown(json.optString("body", ""))
            val assetUrl = json.optJSONArray("assets")?.let { arr ->
                (0 until arr.length()).asSequence()
                    .map { arr.getJSONObject(it) }
                    .firstOrNull { it.optString("name", "").endsWith(".apk", ignoreCase = true) }
                    ?.optString("browser_download_url")
                    ?.takeIf { it.isNotBlank() }
            }
            val downloadUrl = assetUrl ?: RELEASE_PAGE
            if (isNewer(tag, currentVersion)) {
                Result.Update(AppUpdateInfo(tag, notes, downloadUrl))
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

    /** Release 正文剥成纯文本：去标题号、加粗、行内代码、链接语法，列表符号换成「·」。 */
    fun stripMarkdown(md: String): String = md.lines()
        .map { line ->
            line.trim()
                .replace(Regex("^#{1,6}\\s*"), "")
                .replace(Regex("\\*\\*([^*]+)\\*\\*"), "$1")
                .replace(Regex("`([^`]*)`"), "$1")
                .replace(Regex("^[-*+]\\s+"), "· ")
                .replace(Regex("\\[([^]]+)]\\([^)]*\\)"), "$1")
        }
        .joinToString("\n")
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()
}
