package com.hnnujw.course.utils

import android.content.Context
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃兜底：把未捕获异常的完整栈写到本地，供用户反馈时附上。
 *
 * ## 为什么不用第三方崩溃平台
 *
 * 接 Firebase Crashlytics / Bugly 这类 SDK 需要：
 *  - 在隐私政策里申报"收集崩溃信息"（本项目目前**没有**隐私政策，见更新计划 6.9）；
 *  - 申请对应的账号与密钥；
 *  - 接受数据出境（Crashlytics 的服务器在境外）。
 *
 * 对一个人维护的校内工具来说，这三项的成本都高于收益。而真正需要的信息
 * 其实只有一句"它在哪崩的" —— 本地存一份，用户反馈时主动附上，效果一样，
 * 且**完全在设备内**。
 *
 * ## 口径
 *
 * - 只保留最近 [MAX_FILES] 份，每份不超过 [MAX_BYTES]：不能让日志把用户存储撑爆。
 * - 记录完**照原样交给原来的处理器**：绝不吞掉异常。系统该弹崩溃对话框照样弹，
 *   这不是"崩溃恢复"，只是"顺手留个证据"。
 * - 写失败一律忽略：崩溃路径上再抛异常，会把原始崩溃信息彻底盖掉。
 */
object CrashGuard {

    private const val TAG = "CrashGuard"

    /** 保留最近几份崩溃记录。 */
    private const val MAX_FILES = 5

    /** 单份记录的上限：栈一般几 KB，超过说明有别的东西（比如 message 里塞了响应体）。 */
    private const val MAX_BYTES = 256 * 1024

    private const val DIR = "crash"

    @Volatile
    private var installed = false

    /**
     * 安装兜底处理器。
     *
     * @param enabled 传 `!BuildConfig.DEBUG` 或 `true` 都可以，由调用方决定。
     *   默认 release 才装 —— debug 下 Android Studio 的崩溃栈更完整，且开发时
     *   频繁崩溃会攒一堆无用文件。
     *
     * 幂等：重复调用只装一次。
     */
    fun install(context: Context, enabled: Boolean) {
        if (!enabled || installed) return
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        val current = Thread.currentThread()
        synchronized(this) {
            if (installed) return
            installed = true
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                // 写文件**必须**包起来：这里再抛异常，用户看到的就是
                // "CrashGuard 崩了"而不是原始崩溃，排查成本更高。
                runCatching { write(app, throwable, thread ?: current) }
                // 交还给原来的处理器（通常是系统默认的那个，负责弹崩溃框 + 结束进程）。
                previous?.uncaughtException(thread, throwable)
                    // 没有原处理器时（极少见）手动结束进程，否则系统可能"假死"在崩溃态。
                    ?: run {
                        android.os.Process.killProcess(android.os.Process.myPid())
                        kotlin.system.exitProcess(10)
                    }
            }
            Log.d(TAG, "崩溃兜底已安装（保留最近 $MAX_FILES 份）")
        }
    }

    /** 崩溃日志目录。不存在时返回 null（不在这里创建 —— 写失败就算了）。 */
    fun dir(context: Context): File? =
        context.applicationContext.getExternalFilesDir(null)?.let { File(it, DIR) }

    /** 列出已有的崩溃记录，最新在前。 */
    fun list(context: Context): List<File> {
        val d = dir(context) ?: return emptyList()
        val files = d.listFiles() ?: return emptyList()
        return files.filter { it.isFile }.sortedByDescending { it.lastModified() }
    }

    /** 拼接全部记录（反馈时附上）。超长会截断。 */
    fun readAll(context: Context, maxChars: Int = 60_000): String {
        val files = list(context)
        if (files.isEmpty()) return ""
        val sb = StringBuilder()
        for (f in files) {
            if (sb.length >= maxChars) break
            sb.append("=== ").append(f.name).append(" ===\n")
            runCatching { sb.append(f.readText()) }
            sb.append("\n\n")
        }
        return if (sb.length > maxChars) sb.substring(0, maxChars) else sb.toString()
    }

    /** 清空崩溃记录。 */
    fun clear(context: Context) {
        list(context).forEach { runCatching { it.delete() } }
    }

    private fun write(context: Context, throwable: Throwable, thread: Thread) {
        val folder = dir(context) ?: return
        if (!folder.exists() && !folder.mkdirs()) return
        // 先清理旧文件：以"写入前"为准，保证不会因为写失败而永远留着更老的垃圾。
        list(context).drop(MAX_FILES - 1).forEach { runCatching { it.delete() } }

        val stack = StringWriter().also { sw ->
            runCatching { throwable.printStackTrace(PrintWriter(sw)) }
        }.toString()

        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.CHINA).format(Date())
        val body = buildString {
            appendLine("时间: $stamp")
            appendLine("线程: ${thread.name}")
            appendLine("版本: ${appVersion(context)}")
            appendLine("设备: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            appendLine("系统: Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
            appendLine()
            appendLine(stack)
        }
        val trimmed = if (body.length > MAX_BYTES) body.substring(0, MAX_BYTES) else body
        File(folder, "crash-$stamp.txt").writeText(trimmed)
    }

    private fun appVersion(context: Context): String = runCatching {
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val info = pm.getPackageInfo(context.packageName, 0)
        "${info.versionName} (${info.versionCode})"
    }.getOrDefault("unknown")
}
