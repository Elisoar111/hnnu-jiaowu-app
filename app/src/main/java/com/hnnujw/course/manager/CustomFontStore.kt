package com.hnnujw.course.manager

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import java.io.File

/**
 * 自定义字体（用户从手机存储里选的 ttf / otf）的本机存储。
 *
 * 导入即**复制**进应用私有目录（`filesDir/fonts/custom.ttf`）：SAF 的读授权是
 * 临时的，源文件可能被文件管理器清理，不复制的话重启就没了。显示名（导入时
 * 的文件名）单独存进外观偏好里给「我的」页展示。
 *
 * 校验口径：复制到临时文件后先 `Typeface.createFromFile` 一次，建不出字体
 * （损坏 / 根本不是字体文件）就判导入失败并丢弃临时文件，**旧字体保持不动**。
 */
object CustomFontStore {

    private const val DIR_NAME = "fonts"
    private const val FILE_NAME = "custom.ttf"
    private const val PREFS_NAME = "appearance_settings"
    private const val KEY_DISPLAY_NAME = "custom_font_display_name"

    fun fontFile(context: Context): File =
        File(File(context.applicationContext.filesDir, DIR_NAME), FILE_NAME)

    fun exists(context: Context): Boolean =
        fontFile(context).let { it.isFile && it.length() > 0L }

    /** 导入时的显示名；没有自定义字体时为空串。 */
    fun displayName(context: Context): String =
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_DISPLAY_NAME, "")
            .orEmpty()

    /** 已导入的字体；文件缺失或损坏时返回 null，调用方回退系统字体。 */
    fun loadTypeface(context: Context): Typeface? {
        if (!exists(context)) return null
        return runCatching { Typeface.createFromFile(fontFile(context)) }.getOrNull()
    }

    /**
     * 从 SAF Uri 导入字体：复制 → 校验 → 原子替换 → 落盘显示名。
     *
     * @return 导入成功的显示名（用于「我的」页副标题与 Toast）。
     * @throws IllegalArgumentException 文件读不出或不是有效字体（旧文件不受影响）。
     */
    fun import(context: Context, uri: Uri, suggestedName: String): String {
        val app = context.applicationContext
        val dir = File(app.filesDir, DIR_NAME).apply { mkdirs() }
        val tmp = File(dir, "$FILE_NAME.importing")
        try {
            app.contentResolver.openInputStream(uri)?.use { input ->
                tmp.outputStream().use { output -> input.copyTo(output) }
            } ?: throw IllegalArgumentException("无法读取所选的字体文件")

            if (runCatching { Typeface.createFromFile(tmp) }.getOrNull() == null) {
                throw IllegalArgumentException("所选文件不是有效的 ttf / otf 字体")
            }

            val target = File(dir, FILE_NAME)
            if (target.exists() && !target.delete()) {
                throw IllegalArgumentException("旧字体文件占用中，请重启应用后重试")
            }
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
        } finally {
            if (tmp.exists()) tmp.delete()
        }

        val name = suggestedName.trim().ifBlank { "自定义字体" }
        app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_DISPLAY_NAME, name)
            .apply()
        return name
    }
}
