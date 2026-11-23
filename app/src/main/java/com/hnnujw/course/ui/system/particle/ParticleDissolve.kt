package com.hnnujw.course.ui.system.particle

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.RectF
import android.view.Window
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 把 [content] 包进「关闭时粒子消散」：触发后先抓下当前画面，交给 [ThanosEffectView]
 * 做 GLES3 粒子消散，**第一帧真的合成上来**之后才隐藏原内容，播完回调 [onFinished]。
 *
 * 这是参考项目 BiliPai `DissolvableVideoCard` 的同构移植（思维、时序、失败路径均一致）：
 *  - 抓图用 Compose [rememberGraphicsLayer] 而不是 `View.draw(Canvas)` —— 保留透明圆角，
 *    也不会把窗口背景与遮罩拍进去；
 *  - 隐藏用 `graphicsLayer { alpha = 0f }` 而不是从组合里移除 —— 移除会触发重新布局，
 *    弹层会在粒子飞的同时抖一下；
 *  - GLES3 不可用 / 抓图超时 / attach 失败都直接 [onFinished]：不做粒子、照常关掉，
 *    绝不把弹层卡在半路。
 */
@Composable
fun DissolvableClose(
    closing: Boolean,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
    reverseSweep: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val contentLayer = rememberGraphicsLayer()
    var bounds by remember { mutableStateOf<RectF?>(null) }
    var recorded by remember { mutableStateOf(false) }
    var hideContent by remember { mutableStateOf(false) }
    var effectView by remember { mutableStateOf<ThanosEffectView?>(null) }
    var completed by remember { mutableStateOf(false) }
    val latestFinished by rememberUpdatedState(onFinished)

    fun dispatchOnce() {
        if (completed) return
        completed = true
        latestFinished()
    }

    fun finishEffect() {
        effectView?.dispose()
        effectView = null
        dispatchOnce()
    }

    LaunchedEffect(closing) {
        if (!closing) {
            effectView?.dispose()
            effectView = null
            hideContent = false
            return@LaunchedEffect
        }
        completed = false
        val window = context.findWindow()
        if (window == null || !isThanosEffectSupported(context)) {
            finishEffect()
            return@LaunchedEffect
        }
        // 等这一帧真的画过（记录层有内容、窗口坐标已算出）再抓，否则抓到的是空白图。
        val ready = withTimeoutOrNull(500L) {
            snapshotFlow { recorded && bounds != null }.first { it }
        }
        if (ready != true) {
            finishEffect()
            return@LaunchedEffect
        }
        val bitmap: Bitmap? = try {
            withTimeoutOrNull(500L) {
                // 复制一份：记录层的 ImageBitmap 归 Compose 所有，而 GL 线程会在自己的
                // 线程上读它、并在结束时 recycle。
                contentLayer.toImageBitmap().asAndroidBitmap().copy(Bitmap.Config.ARGB_8888, false)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: RuntimeException) {
            android.util.Log.w("ThanosEffect", "Cannot capture course sheet", error)
            null
        }
        val windowBounds = bounds
        if (bitmap == null || windowBounds == null) {
            bitmap?.recycle()
            finishEffect()
            return@LaunchedEffect
        }
        effectView = ThanosEffectView.attach(
            window = window,
            bitmap = bitmap,
            windowBounds = windowBounds,
            onFirstFrame = { hideContent = true },
            onComplete = { finishEffect() },
            sweepReversed = reverseSweep,
        )
        if (effectView == null) {
            if (!bitmap.isRecycled) bitmap.recycle()
            finishEffect()
            return@LaunchedEffect
        }
        // 兜底：正常时长约 2.09 秒；超过 5 秒还没回调，说明纹理回调丢了，别把弹层卡死。
        delay(5_000L)
        if (!completed) finishEffect()
    }

    DisposableEffect(Unit) {
        onDispose {
            effectView?.dispose()
            effectView = null
        }
    }

    Box(modifier) {
        Box(
            Modifier
                .graphicsLayer { alpha = if (hideContent) 0f else 1f }
                .drawWithContent {
                    contentLayer.record { this@drawWithContent.drawContent() }
                    drawLayer(contentLayer)
                    recorded = true
                }
                .onGloballyPositioned { coordinates ->
                    val position = coordinates.positionInWindow()
                    bounds = RectF(
                        position.x, position.y,
                        position.x + coordinates.size.width,
                        position.y + coordinates.size.height,
                    )
                }
        ) { content() }
    }
}

/** 往上找宿主 Window：Compose 的 LocalContext 可能被包了若干层 ContextWrapper。 */
internal fun Context.findWindow(): Window? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current.window
        current = current.baseContext
    }
    return null
}
