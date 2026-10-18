package com.hnnujw.course.review.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.hnnujw.course.review.FloatingBallPosition
import com.hnnujw.course.ui.system.GlassCircleButton
import com.hnnujw.course.ui.system.rememberGlassAccessibilityMode
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 「问 AI」的**液态玻璃悬浮球**（2026-10 用户要求「AI 解答做成类似悬浮球的按钮，
 * 可以拖拽移动，点击弹出提问与查看解答的弹窗」）。
 *
 * 交互三件事：
 *  1. **拖拽**：手指按住可任意拖动，边界夹在容器内（不会拖出屏幕）；
 *  2. **贴边吸附**：松手后水平方向吸附到较近的一侧（弹簧动画，系统开「减弱动效」
 *     时直接落位），垂直方向停在松手处 —— 悬浮球的标准行为；
 *  3. **点击**：打开 AI 讲解弹窗。点击与拖拽互不干扰：拖拽手势里位移超过触摸
 *     斜率会取消点击；未超过斜率的按下-抬起就是一次点击。
 *
 * 材质：复用 [GlassCircleButton]（与顶栏圆钮同一套 `adaptiveGlassChip` 真折射芯片，
 * 无 backdrop 的设备自动退回边缘光，调用方无需关心）。
 *
 * **生成中指示**：`busy` 为 true 时球外圈画一圈进度环 —— 它取代了旧的全局
 * 「AI 正在生成」胶囊：悬浮球永远在屏幕上，收起弹窗后这就是唯一的生成信号，
 * 点球即可回到弹窗。
 *
 * @param onClick 点击球（打开弹窗 / 未配置时去设置页）。
 */
@Composable
internal fun AiFloatingBall(
    busy: Boolean,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val ballSize = 56.dp
    val margin = 16.dp
    val ballSizePx = with(density) { ballSize.toPx() }
    val marginPx = with(density) { margin.toPx() }
    val reduceMotion = rememberGlassAccessibilityMode().reduceMotion
    val scope = rememberCoroutineScope()

    // 容器大小（练习页整屏）。首次拿到时把球放到默认位置：右侧、约 38% 高度处。
    var containerWidthPx by remember { mutableStateOf(0f) }
    var containerHeightPx by remember { mutableStateOf(0f) }
    var placed by remember { mutableStateOf(false) }
    val position = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    // position 恒为 Offset.Zero 意味着还没落位 —— 首帧不渲染球，避免在 (0,0) 闪一下。
    // 落位后 x/y 至少含 marginPx，这个判据是可靠的。
    val visible = position.value != Offset.Zero

    /** 水平吸附目标：球心在左半屏吸左边，否则吸右边。 */
    fun snapTargetX(): Float {
        val maxX = (containerWidthPx - ballSizePx - marginPx).coerceAtLeast(marginPx)
        return if (position.value.x + ballSizePx / 2 < containerWidthPx / 2) marginPx else maxX
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { size ->
                if (size.width <= 0 || size.height <= 0) return@onSizeChanged
                containerWidthPx = size.width.toFloat()
                containerHeightPx = size.height.toFloat()
                if (!placed) {
                    placed = true
                    // 恢复上次的位置：横向按"贴左/贴右"吸附，纵向按存下来的比例。
                    // 存比例而不是像素，见 FloatingBallPosition 的说明 ——
                    // 像素坐标在旋转/换设备/分屏后必然错位。
                    val maxX = (size.width - ballSizePx - marginPx).coerceAtLeast(marginPx)
                    val savedRatio = FloatingBallPosition.ratioY(context)
                    val onRight = FloatingBallPosition.onRight(context)
                    val x = if (onRight) maxX else marginPx
                    val rawY = size.height * savedRatio
                    val maxY = (size.height - ballSizePx - marginPx).coerceAtLeast(marginPx)
                    val y = rawY.coerceIn(marginPx, maxY)
                    scope.launch { position.snapTo(Offset(x, y)) }
                } else {
                    // 容器尺寸变了（旋转 / 分屏 / 多窗口）：把球夹回新的合法范围 ——
                    // 旧坐标在新尺寸下可能已越界，球会悬在屏幕外拖不到。
                    val maxX = (containerWidthPx - ballSizePx - marginPx).coerceAtLeast(marginPx)
                    val maxY = (containerHeightPx - ballSizePx - marginPx).coerceAtLeast(marginPx)
                    val clamped = Offset(
                        position.value.x.coerceIn(marginPx, maxX),
                        position.value.y.coerceIn(marginPx, maxY),
                    )
                    if (clamped != position.value) {
                        scope.launch { position.snapTo(clamped) }
                    }
                }
            }
    ) {
        if (visible) {
            Box(
                modifier = Modifier
                    .offset { IntOffset(position.value.x.roundToInt(), position.value.y.roundToInt()) }
                    .size(ballSize)
                    // 拖拽：整颗球跟手，夹取在落点前做，球拖不出屏幕。
                    .pointerInput(containerWidthPx, containerHeightPx) {
                        detectDragGestures(
                            onDrag = { change, dragAmount ->
                                change.consume()
                                val maxX = (containerWidthPx - ballSizePx - marginPx).coerceAtLeast(marginPx)
                                val maxY = (containerHeightPx - ballSizePx - marginPx).coerceAtLeast(marginPx)
                                val next = Offset(
                                    (position.value.x + dragAmount.x).coerceIn(marginPx, maxX),
                                    (position.value.y + dragAmount.y).coerceIn(marginPx, maxY),
                                )
                                scope.launch { position.snapTo(next) }
                            },
                            onDragEnd = {
                                // 松手即记住位置：吸附是动画，但比例按松手那一刻算，
                                // 这样下次进来球落在用户"放下的地方"而不是动画终点附近。
                                if (containerHeightPx > 0f) {
                                    FloatingBallPosition.save(
                                        context,
                                        ratioY = position.value.y / containerHeightPx,
                                        onRight = position.value.x + ballSizePx / 2 >= containerWidthPx / 2,
                                    )
                                }
                                val targetX = snapTargetX()
                                scope.launch {
                                    if (reduceMotion) {
                                        position.snapTo(Offset(targetX, position.value.y))
                                    } else {
                                        position.animateTo(
                                            Offset(targetX, position.value.y),
                                            spring(
                                                dampingRatio = Spring.DampingRatioNoBouncy,
                                                stiffness = Spring.StiffnessMediumLow,
                                            ),
                                        )
                                    }
                                }
                            },
                            // 手势被打断也贴边，不能停在半空。
                            onDragCancel = {
                                scope.launch { position.snapTo(Offset(snapTargetX(), position.value.y)) }
                            },
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                // 玻璃圆钮本体：与顶栏圆钮同一套芯片材质与按压形变，自带点击。
                //
                // 显示「AI」字样而不是对话气泡图标：气泡在刷题场景里更像"反馈"或
                // "聊天室"，看不出是"问 AI 这道题"。字母直接说清了功能，
                // 也不依赖图标资源的语义。
                GlassCircleButton(
                    onClick = onClick,
                    icon = Icons.AutoMirrored.Filled.Chat,
                    label = "AI",
                    contentDescription = contentDescription,
                    size = ballSize,
                    tint = MaterialTheme.colorScheme.primary
                )
                if (busy) {
                    // 生成中：一圈进度环罩在球上 —— 收起弹窗后唯一的"还在生成"信号。
                    CircularProgressIndicator(
                        modifier = Modifier
                            .size(ballSize + 8.dp)
                            .padding(2.dp),
                        strokeWidth = 2.5.dp,
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = Color.Transparent,
                    )
                }
            }
        }
    }
}
