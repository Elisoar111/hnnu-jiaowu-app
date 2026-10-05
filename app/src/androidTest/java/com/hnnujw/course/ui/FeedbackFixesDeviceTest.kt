package com.hnnujw.course.ui

import android.os.SystemClock
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hnnujw.course.BuildConfig
import com.hnnujw.course.academic.AcademicCapabilities
import com.hnnujw.course.academic.AcademicSystem
import com.hnnujw.course.manager.AppearanceSettingsManager
import com.hnnujw.course.manager.WallpaperMode
import com.hnnujw.course.model.SchoolConfig
import com.hnnujw.course.ui.screen.SettingsScreen
import com.hnnujw.course.ui.screen.MeasuredGradesHeader
import com.hnnujw.course.ui.screen.WeekHeaderCompact
import com.hnnujw.course.ui.system.*
import com.hnnujw.course.ui.theme.CourseSelectorTheme
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class FeedbackFixesDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Before fun isolatedVariantOnly() = assumeTrue(BuildConfig.UI_PREVIEW)

    @Test fun addSchoolWaitsForRetractionAndRepeatedTapsOpenOneDialog() {
        var opened = 0
        val dialog = mutableStateOf(false)
        compose.setContent {
            CourseSelectorTheme {
                GlassWindowHost(Modifier.testTag("feedback-frame")) {
                    Column(Modifier.fillMaxSize().systemBarsPadding().padding(24.dp)) {
                        Text("学校选择")
                        Spacer(Modifier.height(24.dp))
                        SystemPicker(listOf("测试大学", "另一所大学"), 0, {},
                            modifier = Modifier.testTag("feedback-picker"),
                            actionLabel = "添加学校", onAction = { opened++; dialog.value = true })
                    }
                    if (dialog.value) SystemDialog(onDismissRequest = { dialog.value = false },
                        title = { Text("添加学校弹窗") },
                        confirmButton = { SystemPrimaryButton("完成", { dialog.value = false }) }) {
                        Text("下拉浮层已完成回收")
                    }
                }
            }
        }
        compose.mainClock.autoAdvance = false
        DisplayRecording(compose.activity, "feedback-picker-retraction").use {
            compose.onNodeWithTag("feedback-picker").performTouchInput { click() }
            movieFrames(75)
            capture("picker-open")
            compose.onNodeWithText("添加学校").performTouchInput { doubleClick() }
            movieFrames(5)
            assertEquals("The action must wait for the closing spring", 0, opened)
            compose.onNodeWithText("添加学校弹窗").assertDoesNotExist()
            capture("picker-retracting")
            movieFrames(110)
            compose.onNodeWithText("添加学校弹窗").assertIsDisplayed()
            assertEquals(1, opened)
            capture("picker-dialog-after-retraction")
        }
    }

    @Test fun leavingThePageCancelsTheDeferredSchoolAction() {
        val visible = mutableStateOf(true)
        var opened = 0
        compose.setContent {
            CourseSelectorTheme {
                GlassWindowHost {
                    if (visible.value) Column(Modifier.fillMaxSize().padding(24.dp)) {
                        Spacer(Modifier.height(80.dp))
                        SystemPicker(listOf("测试大学"), 0, {}, Modifier.testTag("cancel-picker"),
                            actionLabel = "添加学校", onAction = { opened++ })
                    }
                }
            }
        }
        compose.onNodeWithTag("cancel-picker").performTouchInput { click() }
        compose.onNodeWithText("添加学校").assertIsDisplayed()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("添加学校").performTouchInput { click() }
        compose.runOnUiThread { visible.value = false }
        compose.mainClock.advanceTimeBy(2500)
        compose.waitForIdle()
        assertEquals(0, opened)
    }

    @Test fun pickerFitsWidthFontThemeAndGlassCombinations() {
        val width = mutableIntStateOf(320)
        val font = mutableFloatStateOf(1f)
        val night = mutableStateOf(false)
        val selected = mutableIntStateOf(0)
        val oldGlass = AppearanceSettingsManager.glassEffectEnabled
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, font.floatValue)) {
                CourseSelectorTheme(darkTheme = night.value) {
                    Box(Modifier.requiredWidth(width.intValue.dp).height(680.dp).testTag("feedback-frame")) {
                        GlassWindowHost {
                            Column(Modifier.fillMaxSize().systemBarsPadding().padding(20.dp)) {
                                Text("演示选项")
                                Spacer(Modifier.height(32.dp))
                                SystemPicker(listOf("选项一", "选项二", "选项三", "选项四", "选项五"), selected.intValue,
                                    { selected.intValue = it }, Modifier.testTag("matrix-picker"))
                                Text("下方内容保持原位", Modifier.testTag("matrix-following"))
                            }
                        }
                    }
                }
            }
        }
        try {
            for (w in listOf(320, 360, 412)) for (f in listOf(1f, 1.6f)) for (dark in listOf(false, true)) for (glass in listOf(false, true)) {
                compose.runOnIdle {
                    width.intValue = w; font.floatValue = f; night.value = dark
                    selected.intValue = 0; AppearanceSettingsManager.updateGlassEffect(glass)
                }
                val baseline = compose.onNodeWithTag("matrix-following").fetchSemanticsNode().boundsInRoot.top
                compose.onNodeWithTag("matrix-picker").performTouchInput { click() }
                val frame = compose.onNodeWithTag("feedback-frame").fetchSemanticsNode().boundsInRoot
                val option = compose.onNode(hasText("选项二") and isSelectable())
                option.assertIsDisplayed()
                val bounds = option.fetchSemanticsNode().boundsInRoot
                assertTrue(bounds.left >= frame.left && bounds.right <= frame.right + 1)
                assertEquals(baseline, compose.onNodeWithTag("matrix-following").fetchSemanticsNode().boundsInRoot.top, 1f)
                if (w == 320 && f == 1.6f) capture("picker-320-large-" + if (dark) "dark-$glass" else "light-$glass")
                option.performClick()
                compose.waitForIdle()
                assertEquals(1, selected.intValue)
            }
        } finally { compose.runOnIdle { AppearanceSettingsManager.updateGlassEffect(oldGlass) } }
    }

    @Test fun customImageHeadersDoNotWashOutTheWallpaperAtTheirEdges() {
        assumeTrue("This visual regression uses an imported image in the isolated preview app",
            AppearanceSettingsManager.mode == WallpaperMode.Image)
        compose.waitUntil(5_000) { AppearanceSettingsManager.style.image != null }
        val page = mutableIntStateOf(-1)
        val collapse = mutableFloatStateOf(0f)
        val night = mutableStateOf(false)
        compose.setContent {
            val density = LocalDensity.current
            val actualWindow = LocalWindowInfo.current
            val previewWindow = remember(actualWindow, density) {
                object : WindowInfo by actualWindow {
                    override val containerSize = with(density) {
                        IntSize(320.dp.roundToPx(), 620.dp.roundToPx())
                    }
                }
            }
            val configuration = Configuration(LocalConfiguration.current).apply {
                screenWidthDp = 320
                screenHeightDp = 620
                smallestScreenWidthDp = 320
            }
            CompositionLocalProvider(LocalConfiguration provides configuration, LocalWindowInfo provides previewWindow) {
                CourseSelectorTheme(darkTheme = night.value) {
                    Box(Modifier.requiredWidth(320.dp).height(620.dp)) {
                        GlassWindowHost(Modifier.testTag("feedback-frame")) {
                            when (page.intValue) {
                                0 -> MeasuredGradesHeader("7 门课程", listOf("学期", "总体", "考试"), 0, {},
                                    collapse.floatValue, LocalAppBackdrop.current, true, true, false, {}, {}, { _, _ -> })
                                1 -> WeekHeaderCompact(1, {}, {}, collapseFraction = collapse.floatValue,
                                    sampleBackdrop = LocalAppBackdrop.current)
                            }
                        }
                    }
                }
            }
        }
        val root = compose.onNodeWithTag("feedback-frame")
        for (dark in listOf(false, true)) {
            compose.runOnIdle { page.intValue = -1; night.value = dark; collapse.floatValue = 0f }
            val baseline = root.captureToImage().asAndroidBitmap()
            val scale = baseline.width / 320f
            for (header in 0..1) {
                compose.runOnIdle { page.intValue = header; collapse.floatValue = 0f }
                val expanded = root.captureToImage().asAndroidBitmap()
                for (xDp in listOf(2f, 318f)) for (yDp in listOf(45f, 85f, 125f)) {
                    val x = (xDp * scale).toInt().coerceIn(0, baseline.width - 1)
                    val y = (yDp * scale).toInt().coerceIn(0, baseline.height - 1)
                    val before = baseline.getPixel(x, y)
                    val after = expanded.getPixel(x, y)
                    val difference = listOf(0, 8, 16).maxOf { shift ->
                        kotlin.math.abs(((before shr shift) and 255) - ((after shr shift) and 255))
                    }
                    assertTrue("Header $header/$dark adds a pale sheet at $xDp,$yDp (difference=$difference)", difference <= 6)
                }
                capture("image-header-$header-$dark-expanded")
                compose.runOnIdle { collapse.floatValue = 0.65f }
                capture("image-header-$header-$dark-moving")
                compose.runOnIdle { collapse.floatValue = 1f }
                capture("image-header-$header-$dark-collapsed")
            }
        }
    }

    private fun movieFrames(count: Int) {
        repeat(count) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle(); SystemClock.sleep(16) }
    }

    private fun capture(name: String) {
        val bitmap = compose.onNodeWithTag("feedback-frame").captureToImage().asAndroidBitmap()
        val folder = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "feedback-validation").apply { mkdirs() }
        File(folder, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
}
