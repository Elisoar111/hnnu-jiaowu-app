package com.hnnujw.course.utils

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

object LogUtils {

    fun exportLogs(context: Context) {
        // logcat 子进程拷贝 + 落盘是纯 IO，别卡在「导出日志」点击回调的主线程上。
        // 捕获在后台线程做完，回主线程再拉起分享（startActivity 需要主线程）。
        val appContext = context.applicationContext
        Thread {
            val file = runCatching { capture(appContext) }.getOrNull() ?: return@Thread
            android.os.Handler(android.os.Looper.getMainLooper()).post { shareFile(appContext, file) }
        }.start()
    }

    private fun capture(context: Context): File {
        val logFile = File(context.cacheDir, "logs/app_log.txt")
        if (!logFile.parentFile.exists()) {
            logFile.parentFile.mkdirs()
        }

        try {
            val pid = android.os.Process.myPid()
            val process = Runtime.getRuntime().exec("logcat -d --pid=$pid")
            val inputStream = process.inputStream
            val outputStream = FileOutputStream(logFile)

            val buffer = ByteArray(1024)
            var bytesRead: Int
            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                outputStream.write(buffer, 0, bytesRead)
            }

            outputStream.close()
            inputStream.close()
            // 把崩溃记录接在后面。
            //
            // 为什么合并而不是导出两个文件：用户反馈问题时只会点一次「导出日志」，
            // 而崩溃栈恰恰是最关键的那一段 —— 分成两份，多数人会只传其中一份。
            // 崩溃记录本身很小（几 KB），接在 logcat 后面不会撑大文件多少。
            runCatching {
                val crash = CrashGuard.readAll(context)
                if (crash.isNotBlank()) {
                    logFile.appendText("\n\n########## 崩溃记录 ##########\n\n")
                    logFile.appendText(crash)
                }
            }
            return logFile
        } catch (e: IOException) {
            e.printStackTrace()
            throw e
        }
    }

    private fun shareFile(context: Context, file: File) {
        val authority = "${context.packageName}.fileprovider"
        val contentUri = FileProvider.getUriForFile(context, authority, file)

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, contentUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            // 从 applicationContext 起跳必须带 NEW_TASK，否则 startActivity 直接抛异常
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        context.startActivity(Intent.createChooser(intent, "导出日志"))
    }
}
