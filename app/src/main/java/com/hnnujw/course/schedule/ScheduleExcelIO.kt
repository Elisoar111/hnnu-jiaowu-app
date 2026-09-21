package com.hnnujw.course.schedule

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.hnnujw.course.document.SpreadsheetWriter
import com.hnnujw.course.document.XlsxParser
import java.io.File

/**
 * 课表 ↔ Excel 的双向转换，纯数据可单测。
 *
 * 导出格式（也是导入推荐的列布局，按表头名识别，与顺序无关）：
 *   课程名称 | 教师 | 地点 | 星期 | 开始节次 | 结束节次 | 周次
 *
 * 「星期」写 `周一`…`周日`；导入时同时接受 `星期一` / `周1` / `1` / `Sunday` 等
 * 常见写法。「周次」原样落库，交给 [ScheduleWeeks.parse] 解释（支持 `1-16周`、
 * `1,3,5`、`1-13单` 等），解释不了的课程按"每周都上"处理 —— 与手动添加一致。
 */
data class ScheduleExcelCourse(
    val name: String,
    val teacher: String = "",
    val location: String = "",
    val day: Int = 1,
    val startPeriod: Int = 1,
    val endPeriod: Int = 1,
    val weeks: String = "",
)

object ScheduleExcelIO {

    val EXPORT_HEADER = listOf("课程名称", "教师", "地点", "星期", "开始节次", "结束节次", "周次")

    private val DAY_NAMES = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

    fun dayName(day: Int): String = DAY_NAMES.getOrElse(day - 1) { "周一" }

    // ── 导出 ──────────────────────────────────────────────────────────────

    fun exportRows(courses: List<ScheduleExcelCourse>): List<List<String>> =
        listOf(EXPORT_HEADER) + courses.map {
            listOf(
                it.name, it.teacher, it.location, dayName(it.day),
                it.startPeriod.toString(), it.endPeriod.toString(), it.weeks,
            )
        }

    fun exportWorkbook(courses: List<ScheduleExcelCourse>): ByteArray =
        SpreadsheetWriter.writeTexts(exportRows(courses), "课表")

    /**
     * 写到 `externalCacheDir/exports` 并返回文件。交给系统分享/打开
     * 走 FileProvider（`external-cache-path` 已在 `file_paths.xml` 登记）。
     */
    fun exportToCache(context: Context, courses: List<ScheduleExcelCourse>): File {
        val dir = File(context.externalCacheDir, "exports").apply { mkdirs() }
        val file = File(dir, "课表_${SpreadsheetWriter.fileTimestamp()}.xlsx")
        file.writeBytes(exportWorkbook(courses))
        return file
    }

    /** 拉起系统分享（发同学 / 存到网盘）。 */
    fun shareWorkbook(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "分享课表"))
    }

    // ── 导入 ──────────────────────────────────────────────────────────────

    /**
     * 把一张工作表网格解析成课程列表。
     *
     * 表头识别：找第一行含「课程」且含「星期 / 节次 / 周次」之一的行作为表头，
     * 按列名取列（顺序无关，缺列用默认值）；找不到表头则按导出格式按位置取列。
     * 课程名为空的行跳过。
     */
    fun parseImported(grid: List<List<String>>): List<ScheduleExcelCourse> {
        val cleaned = grid.map { row -> row.map { it.trim() } }
            .filter { row -> row.any { it.isNotBlank() } }
        if (cleaned.isEmpty()) return emptyList()

        val headerIndex = cleaned.indexOfFirst { row -> isHeaderRow(row) }
        val mapping: Map<String, Int>
        val dataRows: List<List<String>>
        if (headerIndex >= 0) {
            mapping = columnMapping(cleaned[headerIndex])
            dataRows = cleaned.drop(headerIndex + 1)
        } else {
            // 无表头：按导出格式按位置取列（0=课程 1=教师 2=地点 3=星期 4=开始 5=结束 6=周次）
            mapping = mapOf(
                "name" to 0, "teacher" to 1, "location" to 2,
                "day" to 3, "start" to 4, "end" to 5, "weeks" to 6,
            )
            dataRows = cleaned
        }
        return dataRows.mapNotNull { row -> rowToCourse(row, mapping) }
    }

    private fun isHeaderRow(row: List<String>): Boolean {
        val hasName = row.any { it.contains("课程") || it == "名称" }
        val hasTime = row.any { it.contains("星期") || it.contains("节次") || it.contains("周次") }
        return hasName && hasTime
    }

    /** 列名 → 列下标。同一行里同名列取第一个。 */
    private fun columnMapping(header: List<String>): Map<String, Int> {
        val mapping = mutableMapOf<String, Int>()
        fun put(names: List<String>, key: String) {
            val index = header.indexOfFirst { h -> names.any { n -> h.contains(n) } }
            if (index >= 0 && key !in mapping) mapping[key] = index
        }
        put(listOf("课程名称", "课程", "名称"), "name")
        put(listOf("教师", "老师"), "teacher")
        put(listOf("地点", "教室", "位置"), "location")
        put(listOf("星期", "周几", "礼拜"), "day")
        put(listOf("开始节次", "起始节次", "开始"), "start")
        put(listOf("结束节次", "结束"), "end")
        put(listOf("周次"), "weeks")
        return mapping
    }

    private fun rowToCourse(row: List<String>, mapping: Map<String, Int>): ScheduleExcelCourse? {
        fun cell(key: String): String = mapping[key]?.let { row.getOrNull(it) }?.trim().orEmpty()
        val name = cell("name")
        if (name.isBlank()) return null
        val start = parsePeriod(cell("start")) ?: 1
        val end = (parsePeriod(cell("end")) ?: start).coerceAtLeast(start)
        return ScheduleExcelCourse(
            name = name,
            teacher = cell("teacher"),
            location = cell("location"),
            day = parseDay(cell("day")),
            startPeriod = start,
            endPeriod = end,
            weeks = cell("weeks"),
        )
    }

    /** `周一` / `星期三` / `周1` / `3` / `Sunday` → 1..7；认不出按周一。 */
    internal fun parseDay(raw: String): Int {
        val text = raw.trim()
        if (text.isEmpty()) return 1
        // 英文星期
        val english = listOf("mon", "tue", "wed", "thu", "fri", "sat", "sun")
        val lower = text.lowercase()
        english.forEachIndexed { index, prefix -> if (lower.startsWith(prefix)) return index + 1 }
        // 中文星期：取字符串里的「一二三..日/天」，没有汉字序号就试阿拉伯数字
        val cn = "一二三四五六日天"
        val cnIndex = text.indexOfFirst { it in "一二三四五六日天" }
        if (cnIndex >= 0) {
            val ch = text[cnIndex]
            return when (ch) {
                '日', '天' -> 7
                else -> cn.indexOf(ch) + 1
            }
        }
        val number = Regex("\\d{1,2}").find(text)?.value?.toIntOrNull() ?: return 1
        return if (number in 1..7) number else 1
    }

    /** 取字符串里第一个整数；`1-2` 取 1，`第3节` 取 3。 */
    internal fun parsePeriod(raw: String): Int? {
        val number = Regex("\\d{1,2}").find(raw)?.value?.toIntOrNull() ?: return null
        return if (number in 1..30) number else null
    }
}
