package com.hnnujw.course.academic

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class AcademicStudyTest {
    @Test fun separatesNonConsecutivePeriodsAndKeepsTheDayAndWeeks() {
        val result = AcademicStudyParser.jsonSchedule("""{"kbList":[{"kcmc":"课程甲","xqj":"3","jcs":"1-2,5-6","zcd":"1-16周(单)"}]}""")
        assertEquals(listOf(1 to 2, 5 to 6), result.map { it.startPeriod to it.endPeriod })
        assertTrue(result.all { it.day == 3 && it.weeks == "1-16周(单)" })
    }

    @Test fun parsesModernQzScheduleOnceWithoutTooltipDuplicates() {
        val result = AcademicStudyParser.htmlSchedule(qzSchedule)
        assertEquals(1, result.size)
        val course = result.single()
        assertEquals("课程甲", course.name)
        assertEquals("教师甲", course.teacher)
        assertEquals("教室甲", course.location)
        assertEquals(1, course.day)
        assertEquals(1 to 2, course.startPeriod to course.endPeriod)
        assertEquals("1-8周", course.weeks)
    }

    @Test fun parsesOldQzScheduleAndItsWeekNotation() {
        val cell = """<div class="kbcontent">课程乙<br><font title="老师">教师乙</font><br><font title="周次(节次)">1-16(周)[03-04节]</font><br><font title="教室">教室乙</font></div>"""
        val result = AcademicStudyParser.htmlSchedule(grid(cell)).single()
        assertEquals("课程乙", result.name)
        assertEquals("教师乙", result.teacher)
        assertEquals("教室乙", result.location)
        assertEquals("1-16周", result.weeks)
        assertEquals(3 to 4, result.startPeriod to result.endPeriod)
    }

    @Test fun parsesMultipleOldZfCoursesSharingTheSameCell() {
        val cell = "课程甲<br>周一第1,2节{第1-8周}<br>教师甲<br>教室甲<br><br>课程乙<br>周一第1,2节{第9-16周}<br>教师乙<br>教室乙"
        val result = AcademicStudyParser.htmlSchedule(grid(cell))
        assertEquals(listOf("课程甲", "课程乙"), result.map { it.name })
        assertEquals(listOf("1-8周", "9-16周"), result.map { it.weeks })
        assertEquals(listOf("教师甲", "教师乙"), result.map { it.teacher })
    }

    @Test fun followsRowspanColumnsWhenParsingTheSchedule() {
        val html = """<table><tr><th colspan="2">节次</th><th>星期一</th><th>星期二</th></tr>
            <tr><td rowspan="2">上午</td><td>1</td><td></td><td></td></tr>
            <tr><td>2</td><td>课程甲<br>周一第2节{第1-16周}<br>教师甲<br>教室甲</td><td></td></tr></table>"""
        assertEquals(1, AcademicStudyParser.htmlSchedule(html).single().day)
    }

    @Test fun retainsAllWeekRangesAndTheirOddEvenConstraints() {
        val cell = "课程甲<br>周一第1,2节{第1-4周,6-14周(双),15-16周}<br>教师甲<br>教室甲"
        assertEquals("1-4周,6-14周(双),15-16周", AcademicStudyParser.htmlSchedule(grid(cell)).single().weeks)
    }

    @Test fun rejectsMalformedScheduleInsteadOfAnEmptySuccess() {
        try { AcademicStudyParser.htmlSchedule(grid("""<div class="kbcontent">课程甲<br>新格式</div>""")); fail() }
        catch (e: AcademicException) { assertEquals(AcademicStatus.PAGE_CHANGED, e.status) }
        try { AcademicStudyParser.jsonSchedule("""{"kbList":[{"kcmc":"课程甲"}]}"""); fail() }
        catch (e: AcademicException) { assertEquals(AcademicStatus.PAGE_CHANGED, e.status) }
    }

    @Test fun gradeAliasesSkipNullAndKeepDecimalScores() {
        val row = AcademicStudyParser.jsonGrades("""{"data":[{"kc_mc":"课程甲","zcjstr":null,"zcj":"80.0","xf":"2.0","jd":"3.0","xnxqid":"2025-2026-2"}]}""").single()
        assertEquals("80.0", row.score)
        assertEquals("2025-2026-2", row.term)
        val stats = AcademicStudyBridge.stats(AcademicGradeReport(listOf(row)))
        assertEquals(0, stats.excellent)
        assertEquals(1, stats.good)
        assertEquals("3.00", stats.gpa)
        assertEquals("2.0", stats.credits)
    }

    @Test fun gradeComponentsKeepZeroAndUseTheSharedDisplayFormat() {
        val row = AcademicStudyParser.jsonGrades("""{"data":[{"kcmc":"Course","cj":"80","pscj":0,"qmcj":"90","sycj":null,"ksxz":"正常考试"}]}""").single()
        assertEquals("平时: 0 | 期末: 90 | 正常考试", row.detail)
        val html = "<table><tr><th>课程名称</th><th>成绩</th><th>平时成绩</th><th>期末成绩</th><th>实验成绩</th></tr><tr><td>Course</td><td>80</td><td>0</td><td>90</td><td>85</td></tr></table>"
        assertEquals("平时: 0 | 期末: 90 | 实验: 85", AcademicStudyParser.htmlGrades(html, "https://school.example/grades").single().detail)
    }

    @Test fun semesterCatalogUsesOnlyPublishedOptionsAndRecognizesOldZfSummer() {
        val html = """<select name='ctl${'$'}ddlXN'><option value='2025-2026'>2025</option></select><select name='ctl${'$'}ddlXQ'><option value='1'>1</option><option value='2' disabled>2</option><option value='3' selected>3</option></select>"""
        assertEquals(listOf("2025-2026-1", "2025-2026-3"), AcademicStudyParser.terms(html).map { it.id })
        assertEquals("2025-2026-3", AcademicStudyParser.selectedTerm(html)?.id)
        assertTrue(AcademicStudyParser.terms("<select name='xnd'><option value='2025-2026'>2025</option></select>").isEmpty())
    }

    @Test fun usesSchoolStatisticsAndDefaultsToLatestGradedTerm() {
        val rows = AcademicStudyParser.jsonGrades("""{"data":[
            {"kc_mc":"课程甲","zcjstr":"<span>良好</span>","xf":"2","jd":"3","xnxqid":"2025-2026-1"},
            {"kc_mc":"课程乙","zcjstr":"90","xf":"3","jd":"4","xnxqid":"2025-2026-2"}]}""")
        assertEquals("良好", rows.first().score)
        assertEquals(listOf("2025-2026-2", "2025-2026-1"), AcademicStudyBridge.semesters(rows))
        val stats = AcademicStudyBridge.stats(AcademicGradeReport(rows, "3.76", "115.5"))
        assertEquals("3.76", stats.gpa)
        assertEquals("115.5", stats.credits)
    }

    @Test fun understandsCombinedAndSeparateSemesterSelectors() {
        assertEquals("2026-2027-1", AcademicStudyParser.selectedTerm(qzSchedule)?.id)
        val zf = """<select name="xnm"><option selected value="2025">2025-2026</option></select><select name="xqm"><option value="3">一</option><option value="12" selected>二</option></select>"""
        assertEquals("2025-2026-2", AcademicStudyParser.selectedTerm(zf)?.id)
        assertEquals(2, AcademicStudyParser.terms(zf).size)
        assertEquals("2026-2027-1", AcademicTerm("2025-2026-2").next().id)
    }

    @Test fun newZfQueriesKeepTheCustomRootAndCorrectSemesterCode() = runBlocking {
        server(AcademicSystem.ZF) { server, reader ->
            enqueue(server, """{"items":[{"kcmc":"课程甲","cj":"85.0","xnm":"2025","xqm":"12"}],"totalCount":1}""")
            assertEquals("2025-2026-2", reader.grades(AcademicTerm("2025-2026-2")).grades.single().term)
            val request = server.takeRequest()
            assertTrue(request.path!!.startsWith("/jsxsd/cjcx/"))
            assertTrue(request.body.readUtf8().contains("xqm=12"))
        }
    }

    @Test fun aValidEmptyExamListDiffersFromARejectedQuery() {
        assertTrue(AcademicStudyParser.jsonExams("""{"code":0,"data":[],"count":0}""").isEmpty())
        try { AcademicStudyParser.jsonExams("""{"code":500,"data":[],"msg":"请刷新"}"""); fail() }
        catch (e: AcademicException) { assertNotEquals(AcademicStatus.SUCCESS, e.status) }
    }

    private suspend fun server(system: AcademicSystem, block: suspend (MockWebServer, AcademicStudyAdapter) -> Unit) {
        val server = MockWebServer()
        server.start()
        try {
            val school = AcademicCoreTest.testSchool(server, system)
            val session = AcademicSessionStore().session(school.id, "study", school.fullBasePath)
            block(server, AcademicStudyReader(school, session, AcademicHttpTransport(school, session)))
        } finally { server.shutdown() }
    }

    private fun enqueue(server: MockWebServer, vararg bodies: String) = bodies.forEach { server.enqueue(MockResponse().setBody(it)) }

    companion object {
        private fun grid(cell: String) = "<table><tr><th>节次</th><th>星期一</th><th>星期二</th></tr><tr><td>1-2</td><td>$cell</td><td></td></tr></table>"
        private val qzSchedule = """<form action='/jsxsd/xskb/xskb_list.do?viweType=0'>
            <select name='xnxq01id'><option value='2026-2027-1' selected>2026-2027-1</option></select>
            <select name='kbjcmsid'><option value='mode1' selected>默认</option></select></form>""" + grid("""
            <li class='courselists-item'><div class='qz-hasCourse-title'>课程甲</div>
            <span class='qz-hasCourse-abbrinfo'>老师:教师甲未定义;时间:1-8周[1-2节];地点:教室甲</span>
            <span class='qz-hasCourse-fullinfo'>课程甲 老师:教师甲;时间:1-8周[1-2节];地点:教室甲</span></li>""")
        private val modernGradeForm = """<script src='/assets_newL/js/qzTable.js'></script><form><input name='sfxsbcxq' type='checkbox' checked value='1'></form>"""
        private val htmlGrades = """<table><tr><td>学年</td><td>学期</td><td>课程名称</td><td>成绩</td><td>学分</td><td>绩点</td></tr>
            <tr><td>2025-2026</td><td>2</td><td>课程甲</td><td>80.0</td><td>2</td><td>3</td></tr></table>"""
        private fun oldZfForm(state: String) = """<form action='xscjcx.aspx'><input type='hidden' name='__VIEWSTATE' value='$state'>
            <select name='xnd'><option value='2025-2026' selected>2025-2026</option></select>
            <select name='xqd'><option value='1' selected>1</option><option value='2'>2</option></select>
            <input type='submit' name='btnCx' value='按学期查询'></form>"""
    }
}
