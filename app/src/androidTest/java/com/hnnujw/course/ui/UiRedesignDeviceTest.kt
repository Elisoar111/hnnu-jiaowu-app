package com.hnnujw.course.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hnnujw.course.BuildConfig
import com.hnnujw.course.demo.DemoData
import com.hnnujw.course.ui.screen.*
import com.hnnujw.course.ui.system.*
import com.hnnujw.course.ui.theme.CourseSelectorTheme
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** All fixtures use local UI state; no selection, grabbing, alarms, or network callbacks. */
@RunWith(AndroidJUnit4::class)
class UiRedesignDeviceTest {
    @get:Rule val compose = createComposeRule()
    @Before fun isolatedDemoOnly() = assumeTrue(BuildConfig.UI_PREVIEW)

    @Test fun gradesActionsNeverOverlapSegmentsAcrossWidthFontAndCollapse() {
        val width = mutableIntStateOf(360)
        val font = mutableFloatStateOf(1f)
        val dark = mutableStateOf(false)
        val collapse = mutableFloatStateOf(0f)
        val share = mutableStateOf(true)
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, font.floatValue)) {
                CourseSelectorTheme(darkTheme = dark.value) {
                    Column(Modifier.requiredWidth(width.intValue.dp).height(340.dp)
                        .background(MaterialTheme.colorScheme.background).testTag("frame")) {
                        MeasuredGradesHeader("累计成绩与分布概览", listOf("学期", "总体", "考试"), 0, {},
                            collapse.floatValue, null, share.value, true, false, {}, {}, { _, _ -> })
                    }
                }
            }
        }
        for (w in listOf(320, 360, 412)) for (f in listOf(1f, 1.3f, 1.6f)) for (night in listOf(false, true)) {
            compose.runOnIdle { width.intValue = w; font.floatValue = f; dark.value = night }
            for (hasShare in listOf(true, false)) for (p in listOf(0f, 0.35f, 0.7f, 1f)) {
                compose.runOnIdle { collapse.floatValue = p; share.value = hasShare }
                compose.waitForIdle()
                val actions = compose.onNodeWithTag("grades-actions").fetchSemanticsNode().boundsInRoot
                val segments = compose.onNodeWithTag("grades-segments").fetchSemanticsNode().boundsInRoot
                assertTrue("Actions overlap segments: width=" + w + ", font=" + f + ", progress=" + p,
                    actions.left >= segments.right - 1f || actions.bottom <= segments.top + 1f || segments.bottom <= actions.top + 1f)
                val root = compose.onNodeWithTag("frame").fetchSemanticsNode().boundsInRoot
                assertTrue(actions.right <= root.right + 1f && segments.right <= root.right + 1f)
                if (p == 0f) {
                    val title = compose.onNodeWithTag("grades-title").fetchSemanticsNode().boundsInRoot
                    assertTrue(title.right <= actions.left + 1f)
                    assertTrue(title.bottom < segments.top)
                }
                if (hasShare && f != 1.3f && p in listOf(0f, 0.35f, 1f)) {
                    capture("02-grades-" + w + "-font" + f + "-" + night + "-" + p)
                }
            }
        }
    }

    @Test fun detailUsesOpaqueSectionsKeepsFooterAndSurvivesThemeChange() {
        val dark = mutableStateOf(false)
        val reminder = mutableStateOf(false)
        val open = mutableStateOf(true)
        var edits = 0
        val sheet = ScheduleBottomSheetState()
        val course = ScheduleCourseUi("课程详情与交互设计实践", "教师姓名 · 联合授课团队",
            "创新中心三层 302 研讨教室，入口位于东侧走廊。".repeat(6),
            2, 3, 4, "1–16周（单）、18周", Color(0xFF2985CD), true, "fixture-course", id = "custom:fixture-course")
        compose.setContent {
            CourseSelectorTheme(darkTheme = dark.value) {
                val host = rememberDialogHostState()
                Box(Modifier.fillMaxSize().background(Color(0xFF38587A)).testTag("frame")) {
                    Text("背景课表", Modifier.align(Alignment.Center), color = Color.White)
                    if (open.value) DisposableEffect(Unit) {
                        val handle = host.show({ open.value = false }, DialogPresentation.Bottom, sheet) {
                            CourseDetailContent(CourseDetailUiState(course, reminderEnabled = reminder.value,
                                reminderDescription = if (reminder.value) "下次提醒：9月15日 09:45" else "开启后，在上课前通知你"),
                                sheet, { host.dismiss() }, { reminder.value = it }, {}, {}, { edits++ }, {})
                        }
                        onDispose { host.dismiss(handle, false) }
                    }
                    DialogHost(host)
                }
            }
        }
        compose.onNodeWithText("编辑课程").assertIsDisplayed()
        val bounds = compose.onNodeWithTag("course-detail-surface", true).fetchSemanticsNode().boundsInRoot
        val root = compose.onNodeWithTag("frame").fetchSemanticsNode().boundsInRoot
        assertTrue("Long content must stay inside the 85% viewport cap", bounds.height / root.height <= 0.85f)
        assertTrue("The footer needs a scrollable body above it", compose.onNodeWithTag("course-detail-scroll", true)
            .fetchSemanticsNode().boundsInRoot.height > 0f)
        capture("03-detail-long-light")
        compose.onNodeWithTag("course-detail-scroll", true).performTouchInput { swipeUp() }
        compose.onNode(isToggleable()).performClick()
        compose.runOnIdle { dark.value = true }
        compose.onNode(isToggleable()).assertIsOn()
        compose.onNodeWithText("编辑课程").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, edits) }
        capture("03-detail-long-dark-reminder")
        compose.onNodeWithContentDescription("关闭课程详情").performClick()
        compose.waitForIdle()
        compose.runOnIdle { assertFalse(open.value) }
    }

    @Test fun lineIconsHaveDistinctIntermediateFramesAndReverse() {
        val state = mutableStateOf(IconVisualState.Idle)
        val event = mutableIntStateOf(0)
        val icons = listOf(AnimatedIconSpec.Courses, AnimatedIconSpec.Calendar, AnimatedIconSpec.Grades, AnimatedIconSpec.Settings,
            AnimatedIconSpec.Filter, AnimatedIconSpec.Share, AnimatedIconSpec.PlayStop, AnimatedIconSpec.Eye,
            AnimatedIconSpec.Edit, AnimatedIconSpec.Delete, AnimatedIconSpec.Undo, AnimatedIconSpec.AddCheck,
            AnimatedIconSpec.ActionFan, AnimatedIconSpec.ScanLock, AnimatedIconSpec.Log, AnimatedIconSpec.ChevronUp)
        compose.setContent {
            CourseSelectorTheme {
                Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).testTag("frame")
                    .padding(16.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    Text("线性动态图标", style = MaterialTheme.typography.titleLarge)
                    icons.chunked(4).forEach { row ->
                        Row(Modifier.fillMaxWidth()) {
                            row.forEach { spec ->
                                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                                    AnimatedLineIcon(spec, Modifier.size(44.dp), state.value, event = event.intValue,
                                        tint = MaterialTheme.colorScheme.primary)
                                    Text(spec.name, fontSize = 10.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
        compose.mainClock.autoAdvance = false
        val initial = compose.onNodeWithTag("frame").captureToImage().asAndroidBitmap()
        capture("05-icons-start")
        compose.runOnIdle { state.value = IconVisualState.Success; event.intValue++ }
        compose.mainClock.advanceTimeBy(128)
        val middle = compose.onNodeWithTag("frame").captureToImage().asAndroidBitmap()
        capture("05-icons-middle")
        assertFalse(initial.sameAs(middle))
        compose.mainClock.advanceTimeBy(1400)
        val end = compose.onNodeWithTag("frame").captureToImage().asAndroidBitmap()
        capture("05-icons-end")
        assertFalse(middle.sameAs(end))
        compose.runOnIdle { state.value = IconVisualState.Idle }
        compose.mainClock.advanceTimeBy(80)
        compose.runOnIdle { state.value = IconVisualState.Success }
        compose.mainClock.advanceTimeBy(1400)
        assertTrue(end.sameAs(compose.onNodeWithTag("frame").captureToImage().asAndroidBitmap()))
    }

    private fun capture(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val output = File(context.getExternalFilesDir(null), "redesign-validation").apply { mkdirs() }
        File(output, name + ".png").outputStream().use {
            compose.onNodeWithTag("frame").captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
