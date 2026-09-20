package com.hnnujw.course.manager

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.hnnujw.course.academic.AcademicGrade
import com.hnnujw.course.academic.AcademicGradeReport
import com.hnnujw.course.ui.screen.ExamItemUi
import org.json.JSONArray
import org.json.JSONObject

/**
 * 成绩与考试数据的本地缓存。
 *
 * 设计目标：
 * - 登录后第一次拉到的成绩 / 考试结果落到 SharedPreferences，下次开 App 直接渲染，
 *   不再显示 loading 与"成绩加载失败"占位
 * - 缓存有效期 7 天；超过则在后台静默刷新一次（不打断用户正在浏览的列表）
 * - 手动刷新按钮无视缓存有效期强制拉取
 * - 按账号隔离；切换账号不会读到上一个账号的成绩
 *
 * 命名风格 / API 形态对齐 [CourseCacheManager]，便于复用。
 */
object GradesCacheManager {
    private const val TAG = "GradesCacheManager"
    private const val PREF_NAME = "academic_grades_cache"
    private const val KEY_REPORT_PREFIX = "report_"
    private const val KEY_EXAMS_PREFIX = "exams_"
    private const val KEY_EXAMS_FETCHED_PREFIX = "exams_fetched_"
    private const val KEY_TIMESTAMP_PREFIX = "ts_"
    /** 一周。超过这个时长就触发后台静默刷新。 */
    private const val FRESH_WINDOW_MS = 7L * 24 * 60 * 60 * 1000

    private fun normalizeAccountKey(raw: String): String {
        val key = raw.ifBlank { "default" }
        return key.replace(Regex("[^A-Za-z0-9_.-]"), "_")
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    private fun reportKey(accountKey: String) = KEY_REPORT_PREFIX + normalizeAccountKey(accountKey)
    private fun examsKey(accountKey: String) = KEY_EXAMS_PREFIX + normalizeAccountKey(accountKey)
    private fun examsFetchedKey(accountKey: String) = KEY_EXAMS_FETCHED_PREFIX + normalizeAccountKey(accountKey)
    private fun tsKey(accountKey: String) = KEY_TIMESTAMP_PREFIX + normalizeAccountKey(accountKey)

    /**
     * 缓存的快照。`report` 是核心，缺失即视为整条缓存无效；
     * `exams` 可能为空（用户从未打开过考试 Tab，考试未触发拉取）。
     */
    data class Cached(
        val report: AcademicGradeReport,
        val exams: List<ExamItemUi>,
        val fetchedAt: Long,
        val examsFetchedAt: Long,
    )

    /** 读取指定账号的缓存；report 缺失或 JSON 损坏时返回 null。 */
    fun load(context: Context, accountKey: String): Cached? {
        val normalized = normalizeAccountKey(accountKey)
        val p = prefs(context)
        val ts = p.getLong(tsKey(normalized), 0L)
        if (ts <= 0L) return null
        val reportJson = p.getString(reportKey(normalized), null) ?: return null
        val report = try {
            reportFromJson(JSONObject(reportJson))
        } catch (e: Exception) {
            Log.w(TAG, "report JSON 解析失败,丢弃: ${e.message}")
            clearAccount(context, normalized)
            return null
        }
        val examsFetchedAt = p.getLong(examsFetchedKey(normalized), 0L)
        val exams = try {
            val ej = p.getString(examsKey(normalized), null)
            if (ej.isNullOrBlank()) emptyList() else examsFromJson(JSONArray(ej))
        } catch (e: Exception) {
            Log.w(TAG, "exams JSON 解析失败,忽略考试部分: ${e.message}")
            emptyList()
        }
        return Cached(
            report = report,
            exams = exams,
            fetchedAt = ts,
            examsFetchedAt = examsFetchedAt,
        ).also {
            Log.d(TAG, "hit: account=${normalizeAccountKey(accountKey)} grades=${report.grades.size} exams=${exams.size} age=${
                (System.currentTimeMillis() - ts) / 86_400_000L
            }d")
        }
    }

    /** 仅写入成绩；时间戳随之刷新。这是"是否新鲜"的主时间源。 */
    fun saveReport(context: Context, accountKey: String, report: AcademicGradeReport) {
        val normalized = normalizeAccountKey(accountKey)
        val now = System.currentTimeMillis()
        try {
            prefs(context).edit()
                .putString(reportKey(normalized), reportToJson(report).toString())
                .putLong(tsKey(normalized), now)
                .apply()
        } catch (e: Exception) {
            Log.w(TAG, "缓存写入失败: ${e.message}")
        }
    }

    /** 仅写入考试，并记录考试结果自己的更新时间。 */
    fun saveExams(context: Context, accountKey: String, exams: List<ExamItemUi>) {
        val normalized = normalizeAccountKey(accountKey)
        try {
            prefs(context).edit()
                .putString(examsKey(normalized), examsToJson(exams).toString())
                .putLong(examsFetchedKey(normalized), System.currentTimeMillis())
                .apply()
        } catch (e: Exception) {
            Log.w(TAG, "考试缓存写入失败: ${e.message}")
        }
    }

    /** 当前账号最后一次成功写入成绩的 Unix 毫秒时间戳；未缓存时为 0。 */
    fun fetchedAt(context: Context, accountKey: String): Long {
        val normalized = normalizeAccountKey(accountKey)
        return prefs(context).getLong(tsKey(normalized), 0L)
    }

    /** 超过一周视为过期，需要后台静默刷新。0 / 负值视为缺失，也算过期。 */
    fun isStale(fetchedAt: Long): Boolean {
        if (fetchedAt <= 0L) return true
        return (System.currentTimeMillis() - fetchedAt) > FRESH_WINDOW_MS
    }

    fun clearAccount(context: Context, accountKey: String) {
        val normalized = normalizeAccountKey(accountKey)
        prefs(context).edit()
            .remove(reportKey(normalized))
            .remove(examsKey(normalized))
            .remove(examsFetchedKey(normalized))
            .remove(tsKey(normalized))
            .apply()
    }

    // ── JSON 互转 ──────────────────────────────────────────────────────────

    private fun reportToJson(report: AcademicGradeReport): JSONObject = JSONObject().apply {
        put("grades", JSONArray().also { arr ->
            report.grades.forEach { arr.put(gradeToJson(it)) }
        })
        put("gradePointAverage", report.gradePointAverage)
        put("totalCredits", report.totalCredits)
    }

    private fun reportFromJson(obj: JSONObject): AcademicGradeReport {
        val gradesJson = obj.optJSONArray("grades") ?: JSONArray()
        val grades = (0 until gradesJson.length()).map { i ->
            gradeFromJson(gradesJson.getJSONObject(i))
        }
        return AcademicGradeReport(
            grades = grades,
            gradePointAverage = obj.optString("gradePointAverage", ""),
            totalCredits = obj.optString("totalCredits", ""),
        )
    }

    private fun gradeToJson(g: AcademicGrade): JSONObject = JSONObject().apply {
        put("name", g.name)
        put("score", g.score)
        put("credits", g.credits)
        put("gradePoint", g.gradePoint)
        put("type", g.type)
        put("term", g.term)
        put("code", g.code)
        put("college", g.college)
        put("sectionId", g.sectionId)
        put("detail", g.detail)
    }

    private fun gradeFromJson(obj: JSONObject): AcademicGrade = AcademicGrade(
        name = obj.optString("name", ""),
        score = obj.optString("score", ""),
        credits = obj.optString("credits", ""),
        gradePoint = obj.optString("gradePoint", ""),
        type = obj.optString("type", ""),
        term = obj.optString("term", ""),
        code = obj.optString("code", ""),
        college = obj.optString("college", ""),
        sectionId = obj.optString("sectionId", ""),
        detail = obj.optString("detail", ""),
    )

    private fun examsToJson(items: List<ExamItemUi>): JSONArray = JSONArray().apply {
        items.forEach { item ->
            put(JSONObject().apply {
                put("courseName", item.courseName)
                put("examTime", item.examTime)
                put("location", item.location)
                put("seatNumber", item.seatNumber)
                put("examName", item.examName)
                put("teacher", item.teacher)
            })
        }
    }

    private fun examsFromJson(arr: JSONArray): List<ExamItemUi> = (0 until arr.length()).map { i ->
        val o = arr.getJSONObject(i)
        ExamItemUi(
            courseName = o.optString("courseName", ""),
            examTime = o.optString("examTime", ""),
            location = o.optString("location", ""),
            seatNumber = o.optString("seatNumber", ""),
            examName = o.optString("examName", ""),
            teacher = o.optString("teacher", ""),
        )
    }
}