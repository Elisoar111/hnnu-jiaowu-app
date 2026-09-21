package com.hnnujw.course.ui.document

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize

/**
 * 缩放手势状态：双指捏合缩放、拖动平移、双击在「适配」与「放大」之间切换。
 *
 * 图片与 PDF 页共用这一份 —— 两处各自持有实例，互不影响。
 */
internal class ZoomState {
    var scale by mutableFloatStateOf(1f)
        private set
    var offset by mutableStateOf(Offset.Zero)
        private set

    internal var viewport: IntSize = IntSize.Zero

    /** 当前是否处于放大状态（决定外层要不要把滚动交给手势）。 */
    val isZoomed: Boolean get() = scale > 1.01f

    fun transform(zoom: Float, pan: Offset) {
        val next = (scale * zoom).coerceIn(1f, 6f)
        if (next <= 1.01f) {
            scale = 1f
            offset = Offset.Zero
            return
        }
        scale = next
        offset = clamp(offset + pan)
    }

    fun toggle() {
        if (isZoomed) {
            scale = 1f
            offset = Offset.Zero
        } else {
            scale = 2.5f
            offset = Offset.Zero
        }
    }

    private fun clamp(value: Offset): Offset {
        if (viewport == IntSize.Zero) return value
        val maxX = viewport.width * (scale - 1f) / 2f
        val maxY = viewport.height * (scale - 1f) / 2f
        return Offset(
            x = value.x.coerceIn(-maxX, maxX),
            y = value.y.coerceIn(-maxY, maxY),
        )
    }
}

@Composable
internal fun rememberZoomState(): ZoomState = remember { ZoomState() }

/**
 * 缩放修饰符。
 *
 * 用 [graphicsLayer] 做缩放/平移（不动布局），`clipToBounds` 防止内容画到容器外；
 * 平移量按当前缩放比例收敛，避免图被拖出视野后找不回来。
 *
 * [enabled] 为假时只保留视觉变换、不响应手势 —— 滚动列表里双指手势会和
 * 列表滚动打架，图片页整页都是图所以始终开启，PDF 页在未放大时关闭。
 */
internal fun Modifier.zoomable(
    state: ZoomState,
    enabled: Boolean = true,
): Modifier = composed {
    this
        .clipToBounds()
        .onSizeChanged { state.viewport = it }
        .graphicsLayer {
            scaleX = state.scale
            scaleY = state.scale
            translationX = state.offset.x
            translationY = state.offset.y
        }
        .pointerInput(enabled) {
            if (!enabled) return@pointerInput
            detectTransformGestures { _, pan, gestureZoom, _ ->
                state.transform(gestureZoom, pan)
            }
        }
        .pointerInput(enabled) {
            if (!enabled) return@pointerInput
            detectTapGestures(onDoubleTap = { state.toggle() })
        }
}

/** 一个把子内容按缩放状态摆好的容器，省得每个查看器自己叠三层 Modifier。 */
@Composable
internal fun ZoomableBox(
    state: ZoomState,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(modifier = modifier.zoomable(state, enabled)) { content() }
}
