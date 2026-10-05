package com.hnnujw.course.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.hnnujw.course.ui.system.LocalAppBackdrop
import com.hnnujw.course.ui.theme.CourseSelectorTheme
import com.hnnujw.course.ui.theme.LocalModuleEntrance
import com.hnnujw.course.ui.theme.moduleEntrance
import kotlin.math.abs
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** A fading task module must include the shadow outside its layout bounds. */
@RunWith(AndroidJUnit4::class)
class ModuleShadowDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun taskShadowIsVisibleBeforeEntranceCompletes() {
        val timeline = mutableFloatStateOf(0.60f)
        var density = 1f
        compose.setContent {
            CourseSelectorTheme {
                density = LocalDensity.current.density
                val backdrop = rememberLayerBackdrop()
                Box(Modifier.size(340.dp, 180.dp).testTag("shadow-frame"), contentAlignment = Alignment.Center) {
                    Box(Modifier.fillMaxSize().layerBackdrop(backdrop).background(Color.White))
                    CompositionLocalProvider(LocalAppBackdrop provides backdrop,
                        LocalModuleEntrance provides { timeline.floatValue }) {
                        // 夹具原本是随抢课一起下线的 LiquidTaskSurface；本测试要验的是
                        // moduleEntrance 的 ModulateAlpha 不会把阴影裁掉，与具体卡片组件无关，
                        // 故改用最直白的 Surface(shadowElevation) 投一份阴影。
                        Surface(
                            modifier = Modifier.width(240.dp).testTag("shadow-card").moduleEntrance(0),
                            shape = RoundedCornerShape(22.dp),
                            color = Color.White,
                            shadowElevation = 6.dp
                        ) {
                            Text("任务队列", Modifier.padding(16.dp))
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        fun shadowBrightness(): Double {
            val frame = compose.onNodeWithTag("shadow-frame")
            val origin = frame.fetchSemanticsNode().boundsInRoot.topLeft
            val card = compose.onNodeWithTag("shadow-card").fetchSemanticsNode().boundsInRoot
            val bitmap = frame.captureToImage().asAndroidBitmap()
            val x = (card.left - origin.x - 6f * density).toInt()
            val y = (card.center.y - origin.y).toInt()
            return (-3..3).map { android.graphics.Color.red(bitmap.getPixel(x, y + it)) }.average()
        }
        val entering = shadowBrightness()
        compose.runOnIdle { timeline.floatValue = 1f }
        val settled = shadowBrightness()
        assertTrue("The task fixture must cast a visible shadow", settled < 253.0)
        assertTrue("Shadow appeared only after the module's alpha reached one: entering=$entering settled=$settled",
            abs(entering - settled) < 1.5)
    }
}
