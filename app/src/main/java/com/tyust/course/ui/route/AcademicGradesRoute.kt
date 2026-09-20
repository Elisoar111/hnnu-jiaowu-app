package com.tyust.course.ui.route

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import com.tyust.course.ui.system.rememberPageData
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import com.tyust.course.academic.*
import com.tyust.course.manager.GradesCacheManager
import com.tyust.course.manager.UserManager
import com.tyust.course.model.SchoolConfig
import com.tyust.course.ui.screen.ExamItemUi
import com.tyust.course.ui.screen.GradeItemUi
import com.tyust.course.ui.screen.GradesScreen
import com.tyust.course.ui.system.GlassToaster
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun AcademicGradesRoute(school: SchoolConfig) {
    val context = LocalContext.current
    val account = UserManager.getInstance().currentAccountStorageKey
    val sessions = UserManager.getInstance().sessionState
    val session by sessions.state.collectAsState()
    val expectedSession = session.token
    var tab by rememberSaveable(account) { mutableIntStateOf(0) }
    val cached = remember(account) {
        // 空账号不读磁盘,避免读到上一个账号的残留
        if (account.isBlank()) null else GradesCacheManager.load(context, account)
    }
    var report by rememberPageData("academic.grades") { cached?.report ?: AcademicGradeReport(emptyList()) }
    var reportLoaded by rememberPageData("academic.grades.loaded") { cached != null }
    var semester by rememberSaveable(account) { mutableStateOf("") }
    var loading by remember(account) { mutableStateOf(cached == null) }
    var cacheFetchedAt by remember(account) { mutableLongStateOf(cached?.fetchedAt ?: 0L) }
    var examCacheFetchedAt by remember(account) { mutableLongStateOf(cached?.examsFetchedAt ?: 0L) }
    var error by remember(account) { mutableStateOf("") }
    var revision by remember(account) { mutableIntStateOf(0) }
    var exams by rememberPageData<List<ExamItemUi>>("academic.exams") { cached?.exams ?: emptyList() }
    var examsLoaded by rememberPageData("academic.exams.loaded") {
        // 空考试列表也是一次成功结果,必须记住,否则每次切到考试页都会重复请求。
        cached != null && cached.examsFetchedAt > 0L
    }
    var examLoading by remember(account) { mutableStateOf(false) }
    var examError by remember(account) { mutableStateOf("") }
    var examRevision by remember(account) { mutableIntStateOf(0) }
    val semesters = remember(report) { AcademicStudyBridge.semesters(report.grades).ifEmpty { listOf(AcademicStudyReader.calendarTerm().id) } }
    val semesterGrades = remember(report, semester) { report.grades.filter { it.term == semester }.map(AcademicStudyBridge::grade) }
    val overallGrades = remember(report) { report.grades.map(AcademicStudyBridge::grade) }
    val overallStats = remember(report) { AcademicStudyBridge.stats(report) }

    LaunchedEffect(account, revision, session.token) {
        val expected = expectedSession
        // 缓存命中 + 非手动刷新(revision==0) → 直接渲染,跳过 loading
        if (revision == 0 && reportLoaded) {
            loading = false
            // 缓存超过一周就在后台静默刷一次,不打断用户
            if (GradesCacheManager.isStale(cacheFetchedAt)) {
                try {
                    val loaded = withContext(Dispatchers.IO) {
                        AcademicStudyBridge.reader(school, account, expected).grades()
                    }
                    if (!sessions.isCurrent(expected)) return@LaunchedEffect
                    report = loaded
                    GradesCacheManager.saveReport(context, account, loaded)
                    // 成绩已在页面上可见：重建成绩推送基线，避免后台巡检重复播报
                    com.tyust.course.academic.GradeWatcher.markSeen(context, account)
                    cacheFetchedAt = System.currentTimeMillis()
                    val available = AcademicStudyBridge.semesters(loaded.grades)
                    if (semester.isBlank() || semester !in available)
                        semester = available.firstOrNull() ?: AcademicStudyReader.calendarTerm().id
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { /* 后台静默,失败不打扰用户 */ }
            }
            return@LaunchedEffect
        }
        loading = true; error = ""
        try {
            val loaded = withContext(Dispatchers.IO) { AcademicStudyBridge.reader(school, account, expected).grades() }
            if (!sessions.isCurrent(expected)) return@LaunchedEffect
            report = loaded
            reportLoaded = true
            GradesCacheManager.saveReport(context, account, loaded)
            com.tyust.course.academic.GradeWatcher.markSeen(context, account)
            cacheFetchedAt = System.currentTimeMillis()
            val available = AcademicStudyBridge.semesters(loaded.grades)
            if (semester.isBlank() || semester !in available) semester = available.firstOrNull() ?: AcademicStudyReader.calendarTerm().id
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            if (!sessions.isCurrent(expected)) return@LaunchedEffect
            error = e.message ?: "成绩加载失败，请重试"
            if ((e as? AcademicException)?.status == AcademicStatus.SESSION_EXPIRED)
                com.tyust.course.network.CourseApiClient.getInstance().notifyCookieExpired(expected)
        }
        finally { if (sessions.isCurrent(expected) && coroutineContext[kotlinx.coroutines.Job]?.isActive == true) loading = false }
    }
    LaunchedEffect(account, tab, examRevision, session.token) {
        val expected = expectedSession
        if (tab != 2) return@LaunchedEffect
        // 考试缓存也按一周更新；有缓存时先显示旧数据，后台静默更新。
        if (examRevision == 0 && examsLoaded) {
            examLoading = false
            if (GradesCacheManager.isStale(examCacheFetchedAt)) {
                try {
                    val loaded = withContext(Dispatchers.IO) {
                        val reader = AcademicStudyBridge.reader(school, account, expected)
                        reader.exams(reader.catalog().currentTerm).map(AcademicStudyBridge::exam)
                    }
                    if (!sessions.isCurrent(expected)) return@LaunchedEffect
                    exams = loaded
                    GradesCacheManager.saveExams(context, account, loaded)
                    // 考试数据更新即对齐考前提醒（解析不出时间的考试自动跳过）
                    com.tyust.course.schedule.ExamReminderScheduler.reconcile(context, loaded)
                    examCacheFetchedAt = System.currentTimeMillis()
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { /* 后台静默,失败不打扰用户 */ }
            }
            return@LaunchedEffect
        }
        if (examsLoaded) return@LaunchedEffect
        examLoading = true; examError = ""
        try {
            val loaded = withContext(Dispatchers.IO) {
                val reader = AcademicStudyBridge.reader(school, account, expected)
                reader.exams(reader.catalog().currentTerm).map(AcademicStudyBridge::exam)
            }
            if (!sessions.isCurrent(expected)) return@LaunchedEffect
            exams = loaded
            examsLoaded = true
            GradesCacheManager.saveExams(context, account, loaded)
            com.tyust.course.schedule.ExamReminderScheduler.reconcile(context, loaded)
            examCacheFetchedAt = System.currentTimeMillis()
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            if (!sessions.isCurrent(expected)) return@LaunchedEffect
            examError = e.message ?: "考试安排加载失败，请重试"
            if ((e as? AcademicException)?.status == AcademicStatus.SESSION_EXPIRED)
                com.tyust.course.network.CourseApiClient.getInstance().notifyCookieExpired(expected)
        }
        finally { if (sessions.isCurrent(expected) && coroutineContext[kotlinx.coroutines.Job]?.isActive == true) examLoading = false }
    }
    com.tyust.course.ui.system.ReportPageContent(report.grades.isNotEmpty() || exams.isNotEmpty())
    GradesScreen(currentTab = tab, onTabChange = { tab = it },
        semesterGrades = semesterGrades,
        semesters = semesters, currentSemester = semester, onSemesterChange = { semester = it },
        semesterIsLoading = loading, overallGrades = overallGrades,
        overallStats = overallStats, overallIsLoading = loading,
        examList = exams, examIsLoading = examLoading,
        onRefresh = { if (tab == 2) { examsLoaded = false; examRevision++ } else {
                // 手动刷新时强制请求；同时清除已加载标记，避免考试页被缓存分支短路。
                reportLoaded = false
                revision++
            } },
        semesterError = error, overallError = error, examError = examError,
        onExportGrades = { exportAcademicGrades(context, it) })
}

private fun exportAcademicGrades(context: Context, grades: List<GradeItemUi>) {
    try {
        fun cell(value: String): String = "\"" + value.replace("\"", "\"\"") + "\""
        val csv = buildString {
            append('\uFEFF')
            appendLine("学年,学期,课程名称,课程代码,开课学院,学分,成绩,绩点,成绩说明")
            grades.forEach { item ->
                val year = item.year.toIntOrNull()?.let { "$it-${it + 1}" }.orEmpty()
                appendLine(listOf(year, item.term, item.courseName, item.courseCode, item.college,
                    item.credits, item.grade, item.gpa, item.detail).joinToString(",", transform = ::cell))
            }
        }
        val directory = File(context.externalCacheDir, "exports").apply { mkdirs() }
        val file = File(directory, "成绩单_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date())}.csv")
        file.writeText(csv, Charsets.UTF_8)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "导出成绩单"))
    } catch (e: Exception) { GlassToaster.show("导出失败：${e.message}") }
}
