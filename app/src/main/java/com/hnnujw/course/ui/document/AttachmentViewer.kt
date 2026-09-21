package com.hnnujw.course.ui.document

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.hnnujw.course.document.DocumentKind
import com.hnnujw.course.secondclass.SecondClassAttachment
import com.hnnujw.course.secondclass.SecondClassAttachmentKind
import java.io.File

/**
 * 「站点附件分类 → 渲染器类型」的映射。
 *
 * 两个口径要保持一致：[SecondClassAttachment.canPreviewInApp] 决定详情页的
 * 提示语，[DocumentKind.renderableInApp] 决定查看器里走哪个渲染器 —— 都由
 * `canPreviewInApp == documentKind().renderableInApp` 这条恒等式锁死（有单测）。
 */
internal fun SecondClassAttachment.documentKind(): DocumentKind = when (kind) {
    SecondClassAttachmentKind.Image -> DocumentKind.Image
    SecondClassAttachmentKind.Pdf -> DocumentKind.Pdf
    SecondClassAttachmentKind.Text -> DocumentKind.Text
    SecondClassAttachmentKind.Word -> if (extension == "docx") DocumentKind.Word else DocumentKind.Unknown
    SecondClassAttachmentKind.Excel -> if (extension == "xlsx" || extension == "csv") DocumentKind.Excel else DocumentKind.Unknown
    SecondClassAttachmentKind.Unknown -> DocumentKind.Unknown
}

/** 交给系统其它应用时用的 MIME。doc/xls 这类旧格式要写全，不然列表里挑不出 WPS。 */
internal fun mimeTypeOf(fileName: String): String {
    val extension = fileName.substringAfterLast('.', "").lowercase()
    return when (extension) {
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "doc" -> "application/msword"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "xls" -> "application/vnd.ms-excel"
        "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        "ppt" -> "application/vnd.ms-powerpoint"
        "csv" -> "text/csv"
        "txt", "md", "log" -> "text/plain"
        "pdf" -> "application/pdf"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "bmp" -> "image/bmp"
        "json" -> "application/json"
        "xml" -> "application/xml"
        "zip" -> "application/zip"
        else -> "application/octet-stream"
    }
}

/**
 * 把已下载的文件交给系统里的其它应用打开（WPS / Office / 预览器…）。
 *
 * `cacheDir` 已经登记在 `file_paths.xml` 的 `cache-path path="."` 里，
 * 所以缓存里的附件可以直接被 FileProvider 暴露出去。
 */
internal fun Context.openFileExternally(file: File, fileName: String) {
    val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mimeTypeOf(fileName))
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { startActivity(Intent.createChooser(intent, "选择打开方式")) }
}

/** 系统分享（转发给同学用）。 */
internal fun Context.shareFile(file: File, fileName: String) {
    val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = mimeTypeOf(fileName)
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching { startActivity(Intent.createChooser(intent, "分享附件")) }
}

/** 下载到 `cacheDir/attachments` 的文件名：URL 指纹 + 清洗过的原始名。 */
internal fun cacheFileName(url: String, name: String): String {
    val sanitized = name.substringAfterLast('/')
        .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
        .ifBlank { "attachment" }
    return "${(url.hashCode().toLong() and 0xFFFFFFFFL).toString(16)}-$sanitized"
}
