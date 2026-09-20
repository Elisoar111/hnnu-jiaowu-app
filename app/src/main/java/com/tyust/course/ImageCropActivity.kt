package com.tyust.course

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tyust.course.manager.UserAvatarStore
import com.tyust.course.manager.WallpaperCropStore
import com.tyust.course.ui.system.SystemPrimaryButton
import com.tyust.course.ui.system.SystemSecondaryButton
import com.tyust.course.ui.theme.CourseSelectorTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 通用「选图 → 取景框裁剪 → 落盘」的一步。
 *
 * ## 为什么要抽成一个 Activity，而不是两个各写一遍
 *
 * 头像与自定义背景原先是两条完全独立的导入路径：头像有裁剪页（[AvatarCropActivity]）、
 * 背景直接把整张原图吃进去（见 `AppearanceSettingsManager.importImageWallpaper`）。
 * 用户要的是**背景也走同一套**：相册选图 → 带取景框调整 → 保存。
 *
 * 两条路径合并后，本轮之后只剩这一个裁剪页：
 *
 * - 收相册 URI（[EXTRA_SOURCE_URI]），页内自己备料、自己解码，
 *   调用方不需要先落一份中间图；
 * - 收"要裁成什么比例"（[EXTRA_ASPECT_RATIO]，0 = 自由/整图）；
 * - 确认后**不在这里落盘**，而是把裁剪结果交回调用方（见 [EXTRA_OUTPUT_PATH]），
 *   由调用方按自己的落盘策略处理。
 *
 * ## 收 URI 而不是收文件路径
 *
 * 旧的头像裁剪页收的是本地文件路径，于是调用方必须先跑一遍
 * `UserAvatarStore.prepareSource` 备料。这一步现在挪进来了：备料、解码、显示
 * 用的是同一张 Bitmap，不再有"备料图是一张、裁剪页解出来是另一张"的错位空间
 * （旧实现里 `SOURCE_MAX_EDGE` 必须同时在两处保持一致，是个隐性耦合）。
 *
 * 相册 URI 的临时授权只保证在本 Activity 存活期间有效，所以备料必须在这里做完。
 */
class ImageCropActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uriString = intent.getStringExtra(EXTRA_SOURCE_URI)
        val uri = uriString?.takeIf { it.isNotBlank() }?.let(Uri::parse)
        val aspectRatio = intent.getFloatExtra(EXTRA_ASPECT_RATIO, 0f)
        val outputPath = intent.getStringExtra(EXTRA_OUTPUT_PATH)
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "调整图片"

        setContent {
            CourseSelectorTheme {
                CropHost(
                    uri = uri,
                    title = title,
                    aspectRatio = aspectRatio,
                    outputPath = outputPath,
                    onDone = { ok ->
                        setResult(if (ok) Activity.RESULT_OK else Activity.RESULT_CANCELED)
                        finish()
                    }
                )
            }
        }
    }

    companion object {
        const val EXTRA_SOURCE_URI = "source_uri"
        const val EXTRA_ASPECT_RATIO = "aspect_ratio"
        const val EXTRA_OUTPUT_PATH = "output_path"
        const val EXTRA_TITLE = "title"

        /**
         * @param aspectRatio 取景框宽高比。0 或负数 = 方形（头像的默认）；
         *        传宽/高（如屏幕的 w/h）即自由比例的背景取景框。
         */
        fun newIntent(
            context: android.content.Context,
            source: Uri,
            outputPath: String,
            aspectRatio: Float = 0f,
            title: String = "调整图片"
        ): Intent = Intent(context, ImageCropActivity::class.java)
            .putExtra(EXTRA_SOURCE_URI, source.toString())
            .putExtra(EXTRA_ASPECT_RATIO, aspectRatio)
            .putExtra(EXTRA_OUTPUT_PATH, outputPath)
            .putExtra(EXTRA_TITLE, title)
            // 相册给的是临时读授权，把授权显式带进裁剪页：同进程内通常本来就有效，
            // 但部分 ROM 的授权是按 Activity 记的，不带这一位会 SecurityException，
            // 被 catch 掉后就是一句"无法读取这张图片"。
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}

@Composable
private fun CropHost(
    uri: Uri?,
    title: String,
    aspectRatio: Float,
    outputPath: String?,
    onDone: (Boolean) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var loading by remember { mutableStateOf(true) }

    // 备料 + 解码都在 IO 线程：一张 4000×3000 的手机照片全尺寸进内存要 48MB，
    // 主线程解会直接 OOM（旧实现就是这么炸的，异常被吞后只剩一句"无法读取图片"）。
    LaunchedEffect(uri) {
        bitmap = withContext(Dispatchers.IO) {
            uri?.let { WallpaperCropStore.decodeForCrop(context, it) }
        }
        loading = false
    }

    val current = bitmap
    if (loading) {
        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            Text("正在读取图片…", color = Color.White.copy(alpha = 0.8f), fontSize = 14.sp)
        }
        return
    }
    if (current == null || outputPath == null) {
        Box(
            modifier = Modifier.fillMaxSize().background(Color.Black).systemBarsPadding(),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    if (outputPath == null) "裁剪页启动参数缺失" else "无法读取这张图片",
                    color = Color.White,
                    fontSize = 16.sp
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    if (outputPath == null) "请重新进入设置页再试一次。" else "请返回重新选一张。",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 13.sp
                )
                Spacer(Modifier.height(16.dp))
                SystemSecondaryButton(text = "关闭", onClick = { onDone(false) })
            }
        }
        return
    }

    // 落盘需要 IO 线程，所以这里起一个 scope —— 不能在 onClick 里直接 withContext
    val scope = rememberCoroutineScope()
    CropFrame(
        title = title,
        sourceBitmap = current,
        aspectRatio = if (aspectRatio > 0f) aspectRatio else 1f,
        onCancel = { onDone(false) },
        onConfirm = { cropX, cropY, cropW, cropH ->
            scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    WallpaperCropStore.writeCropTo(current, java.io.File(outputPath), cropX, cropY, cropW, cropH)
                }
                onDone(ok)
            }
        }
    )
}

/**
 * 取景框本体：整屏显示原图，中间一个透光的矩形窗口，拖动/双指缩放调整构图。
 *
 * [aspectRatio] 是窗口的 宽/高。方形传 1f；背景取景框传屏幕的宽高比，
 * 于是"所见即所得"——窗口里框到的区域比例与最终背景一致。
 */
@Composable
private fun CropFrame(
    title: String,
    sourceBitmap: Bitmap,
    aspectRatio: Float,
    onCancel: () -> Unit,
    onConfirm: (cropX: Int, cropY: Int, cropW: Int, cropH: Int) -> Unit
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    // 取景窗口的像素尺寸。方形按旧头像页的 280dp；非方形按屏宽留边反推高度，
    // 两种形状共用同一段换算逻辑，不再各写一套。
    val density = LocalDensity.current
    val screenWidthPx = with(density) { 360.dp.toPx() }
    val boxWidthPx: Float = if (aspectRatio > 1.02f || aspectRatio < 0.98f) {
        // 非方形：以屏宽的 86% 为窗口宽，高度由比例推出
        screenWidthPx * 0.86f
    } else {
        with(density) { 280.dp.toPx() }
    }
    val boxHeightPx: Float = boxWidthPx / aspectRatio.coerceAtLeast(0.05f)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .systemBarsPadding()
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { canvasSize = it }
                .pointerInput(sourceBitmap) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(0.5f, 6f)
                        offsetX += pan.x
                        offsetY += pan.y
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            // "cover"：先把图放大到至少盖满取景窗口，四周才不会露黑。
            val coverScale = maxOf(
                boxWidthPx / sourceBitmap.width,
                boxHeightPx / sourceBitmap.height
            )
            val displayScale = coverScale * scale
            Image(
                bitmap = sourceBitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = displayScale,
                        scaleY = displayScale,
                        translationX = offsetX,
                        translationY = offsetY
                    )
            )
        }

        // 挖空遮罩：整屏压暗，中心开一个透光窗口
        if (canvasSize.width > 0 && canvasSize.height > 0) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val left = (size.width - boxWidthPx) / 2f
                val top = (size.height - boxHeightPx) / 2f
                val window = Rect(left, top, left + boxWidthPx, top + boxHeightPx)
                val radius = with(density) { 14.dp.toPx() }
                //
                // 窗口必须用 even-odd 路径"挖"出来，**不能用 BlendMode.Clear**：
                // Compose 的 Canvas 默认不在独立 layer 上合成，Clear 会把这块
                // 直接清成黑色而不是透明 —— 表现就是中间一个黑方块把图片盖住，
                // 用户看到的是"一个正方形黑框"，而不是自己的照片。
                //
                // even-odd：外圈整屏矩形 + 内圈窗口矩形，重叠区按奇偶规则不填充，
                // 于是窗口天然透光，不需要任何混合模式。
                val shade = Path().apply {
                    addRect(Rect(0f, 0f, size.width, size.height))
                    addRoundRect(RoundRect(window, radius, radius))
                    fillType = PathFillType.EvenOdd
                }
                drawPath(shade, Color(0x99000000))
                // 窗口轮廓：细白描边，只起指示作用，别压过照片本身
                drawRoundRect(
                    color = Color.White,
                    topLeft = window.topLeft,
                    size = window.size,
                    cornerRadius = CornerRadius(radius, radius),
                    style = Stroke(width = with(density) { 1.5.dp.toPx() })
                )
            }
        }

        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "$title · 拖动移动，双指缩放",
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 13.sp,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SystemSecondaryButton(
                    text = "取消",
                    onClick = onCancel,
                    modifier = Modifier.weight(1f)
                )
                SystemPrimaryButton(
                    text = "保存",
                    onClick = {
                        if (canvasSize.width == 0 || canvasSize.height == 0) return@SystemPrimaryButton
                        val coverScale = maxOf(
                            boxWidthPx / sourceBitmap.width,
                            boxHeightPx / sourceBitmap.height
                        )
                        val displayScale = coverScale * scale
                        if (displayScale <= 0f) return@SystemPrimaryButton
                        // 显示尺寸 = 原图 × displayScale；图以 center 对齐 + translation
                        val displayedW = sourceBitmap.width * displayScale
                        val displayedH = sourceBitmap.height * displayScale
                        val imgX = (canvasSize.width - displayedW) / 2f + offsetX
                        val imgY = (canvasSize.height - displayedH) / 2f + offsetY
                        val cropScreenX = (canvasSize.width - boxWidthPx) / 2f
                        val cropScreenY = (canvasSize.height - boxHeightPx) / 2f
                        // 取景窗口左上角换算回原图坐标
                        val cropInImgX = (cropScreenX - imgX).coerceAtLeast(0f)
                        val cropInImgY = (cropScreenY - imgY).coerceAtLeast(0f)
                        val cropInImgW = minOf(
                            boxWidthPx,
                            (displayedW - cropInImgX).coerceAtLeast(1f)
                        )
                        val cropInImgH = minOf(
                            boxHeightPx,
                            (displayedH - cropInImgY).coerceAtLeast(1f)
                        )
                        onConfirm(
                            (cropInImgX / displayScale).toInt().coerceAtLeast(0),
                            (cropInImgY / displayScale).toInt().coerceAtLeast(0),
                            (cropInImgW / displayScale).toInt().coerceIn(1, sourceBitmap.width),
                            (cropInImgH / displayScale).toInt().coerceIn(1, sourceBitmap.height)
                        )
                    },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}
