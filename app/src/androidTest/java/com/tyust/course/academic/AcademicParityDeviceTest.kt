package com.tyust.course.academic

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tyust.course.model.Course
import com.tyust.course.ui.screen.CourseListScreen
import com.tyust.course.ui.screen.GrabProScreen
import com.tyust.course.ui.system.GlassWindowHost
import com.tyust.course.ui.theme.CourseSelectorTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Local UI fixtures never initialize a student account or send a school request.
 *
 * 「教务支持与限制」弹窗及其两个用例已随设置页入口一并移除（该功能只服务淮南师范学院，
 * 学校固定后不再需要支持范围说明）。
 */
@RunWith(AndroidJUnit4::class)
class AcademicParityDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "academic-parity").apply { mkdirs() }
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @Test fun immediateManualQueueAndSmartTargetBothReachTheirStartActions() {
        val fuzzy = mutableStateOf(false)
        var starts = 0
        var monitors = 0
        val manual = Course().apply { name = "高等数学"; teacher = ""; time = ""; classId = "" }
        compose.setContent { CourseSelectorTheme { GlassWindowHost {
            GrabProScreen(isRunning = false, successCount = 0, failCount = 0, retryCount = 0,
                targetCourseName = if (fuzzy.value) "高等数学" else null, targetCourseTeacher = "", logText = "",
                interval = "1500", onIntervalChange = {}, maxRetry = "100", onMaxRetryChange = {},
                onStart = { starts++ }, onStop = {}, onClearLog = {}, queue = listOf(manual),
                supportsParallel = false, supportsImmediateManual = true, systemNotice = "按账号串行提交",
                isFuzzyMatchMode = fuzzy.value, fuzzyMatchTarget = if (fuzzy.value) "高等数学" else null,
                onStartFuzzyMatch = { monitors++ })
        } } }
        compose.onNodeWithText("开始执行").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, starts); fuzzy.value = true }
        compose.onNodeWithText("开始监控").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, monitors) }
        capture("smart-target")
    }

    @Test fun sameCourseInTwoRoundsExpandsAndSelectsIndependently() {
        val courses = listOf("a", "b").map { round -> Course().apply {
            courseId = "C"; classId = "S"; name = "高等数学"; teacher = "教师$round"; time = "周一 1-2 节"; credit = "2"
            completeParams["academic_system"] = AcademicSystem.ZF.id
            completeParams["academic_scope_id"] = round
            completeParams["academic_course_id"] = "C"
        } }
        val selected = mutableStateOf(emptySet<String>())
        compose.setContent { CourseSelectorTheme { GlassWindowHost {
            CourseListScreen(courses = courses, isLoading = false, onRefresh = {}, onSearch = {},
                onCourseSelect = {}, onAutoGrab = {}, isDetailsReady = true, isMultiSelectMode = true,
                selectedClassIds = selected.value, onToggleSelection = { id, checked ->
                    selected.value = if (checked) selected.value - id else selected.value + id
                })
        } } }
        compose.onAllNodesWithContentDescription("展开教学班").assertCountEquals(2)
        compose.onAllNodesWithContentDescription("展开教学班")[0].performClick()
        compose.onAllNodesWithContentDescription("展开教学班")[0].performClick()
        compose.onAllNodesWithContentDescription("收起教学班").assertCountEquals(2)
        compose.onAllNodes(isToggleable())[0].performClick()
        compose.onAllNodes(isToggleable())[0].assertIsOn()
        compose.onAllNodes(isToggleable())[1].assertIsOff()
        compose.runOnIdle { assertEquals(setOf(courses[0].catalogSelectionKey()), selected.value) }
        capture("course-multiselect")
    }
}
