package com.tyust.course.academic

import com.tyust.course.model.SchoolConfig
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * Read-only study queries share the login jar and the protocol's session lock.
 *
 * 本应用只支持淮南师范学院的正方教务，因此这里只保留正方（JSON 接口）一条读取路径。
 */
internal class AcademicStudyReader(
    private val school: SchoolConfig,
    private val session: AcademicSession,
    private val http: AcademicHttpTransport
) : AcademicStudyAdapter {

    override suspend fun catalog(): AcademicStudyCatalog = session.withProtocolLock {
        val page = checked(http.get(http.appUrl(
            school.scheduleIndexPath.ifBlank { school.schedulePath.ifBlank { "kbcx/xskbcx_cxXsKb.html" } }
                + "?gnmkdm=${school.scheduleGnmkdm}")))
        val terms = AcademicStudyParser.terms(page.text)
        val current = AcademicStudyParser.selectedTerm(page.text) ?: calendarTerm()
        AcademicStudyCatalog((terms + current).distinctBy { it.id }.sortedByDescending { it.id }, current)
    }

    override suspend fun schedule(term: AcademicTerm): List<AcademicScheduleEntry> = session.withProtocolLock {
        val url = http.appUrl(school.schedulePath) + "?gnmkdm=${school.scheduleGnmkdm}"
        val response = checked(http.postForm(url, zfTerm(term).toList(), ajax = true))
        AcademicStudyParser.jsonSchedule(response.text)
    }

    override suspend fun grades(term: AcademicTerm?): AcademicGradeReport = session.withProtocolLock {
        val url = http.appUrl(school.gradesPath) + "?doType=query&gnmkdm=${school.gradeGnmkdm}"
        val (rows, _) = jsonPages { page ->
            http.postForm(url, (zfTerm(term) + mapOf(
                "queryModel.showCount" to PAGE_SIZE.toString(), "queryModel.currentPage" to page.toString(),
                "queryModel.sortName" to "", "queryModel.sortOrder" to "asc", "time" to "0"
            )).toList(), ajax = true)
        }
        AcademicGradeReport(AcademicStudyParser.jsonGrades(asList(rows)))
    }

    override suspend fun exams(term: AcademicTerm): List<AcademicExam> = session.withProtocolLock {
        val url = http.appUrl("kwgl/kscx_cxXsksxxIndex.html?doType=query&gnmkdm=N358105")
        val (rows, _) = jsonPages { page ->
            http.postForm(url, (zfTerm(term) + mapOf("queryModel.showCount" to PAGE_SIZE.toString(),
                "queryModel.currentPage" to page.toString())).toList(), ajax = true)
        }
        AcademicStudyParser.jsonExams(asList(rows))
    }

    private fun checked(page: AcademicResponse): AcademicResponse {
        if (AcademicHtml.isLoginPage(page.text)) throw AcademicException(AcademicStatus.SESSION_EXPIRED, "登录已失效，请重新登录")
        if (page.code !in 200..299) throw AcademicException(AcademicStatus.PAGE_CHANGED, "学校查询页面暂不可用")
        return page
    }

    private suspend fun jsonPages(request: suspend (Int) -> AcademicResponse): Pair<List<JSONObject>, JSONObject> {
        val result = mutableListOf<JSONObject>()
        val seenPages = mutableSetOf<String>()
        var summary = JSONObject()
        for (page in 1..100) {
            val response = checked(request(page))
            val rows = AcademicJson.objects(response.text, "items", "data")
            val root = runCatching { JSONObject(response.text) }.getOrDefault(JSONObject())
            if (page == 1) summary = root
            val total = AcademicJson.int(root, "count", "totalCount", "totalResult", "records")
            if (rows.isNotEmpty() && !seenPages.add(rows.joinToString { it.toString() }))
                throw AcademicException(AcademicStatus.PAGE_CHANGED, "学校重复返回同一页，请稍后重试")
            result += rows
            if (total != null && result.size >= total) return result to summary
            if (rows.isEmpty()) {
                if (total != null && result.size < total) throw AcademicException(AcademicStatus.PAGE_CHANGED, "学校返回的查询数据不完整，请刷新重试")
                return result to summary
            }
            if (total == null && rows.size < PAGE_SIZE) return result to summary
        }
        throw AcademicException(AcademicStatus.PAGE_CHANGED, "查询页数超过上限，请按学期查询")
    }

    private fun zfTerm(term: AcademicTerm?) = mapOf("xnm" to term?.year?.toString().orEmpty(),
        "xqm" to when (term?.semester) { 1 -> "3"; 2 -> "12"; 3 -> "16"; else -> "" })

    private fun asList(rows: List<JSONObject>) = JSONObject().put("items", JSONArray(rows)).toString()

    companion object {
        private const val PAGE_SIZE = 200
        internal fun calendarTerm(): AcademicTerm {
            val now = Calendar.getInstance()
            val year = now.get(Calendar.YEAR)
            val month = now.get(Calendar.MONTH)
            val start = if (month >= Calendar.AUGUST) year else year - 1
            val semester = if (month >= Calendar.AUGUST || month < Calendar.FEBRUARY) 1 else 2
            return AcademicTerm("$start-${start + 1}-$semester")
        }
    }
}
