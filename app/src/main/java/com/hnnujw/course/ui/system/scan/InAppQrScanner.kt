package com.hnnujw.course.ui.system.scan

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.hnnujw.course.ui.system.SystemDialog
import com.hnnujw.course.ui.system.SystemPrimaryButton
import com.hnnujw.course.ui.system.SystemSecondaryButton
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.BarcodeView
import com.journeyapps.barcodescanner.CameraPreview
import com.journeyapps.barcodescanner.DefaultDecoderFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 应用内扫码器 —— **居中弹窗**形态，不跳 zxing 的
 * [com.journeyapps.barcodescanner.CaptureActivity]，也不是全屏黑底页。
 *
 * 为什么要自己做：`ScanContract` 外跳的库自带页（白底 + 系统 ActionBar + 方框取景）
 * 和本应用整体观感完全不搭，且是独立窗口、进入时状态栏会闪。这里用同一个库已经带进来
 * 的 [BarcodeView]（零新依赖）包一层，外壳走应用统一的玻璃弹窗 [SystemDialog]，
 * 卡片里是「标题 → 说明 → 圆角相机预览 + 四角角标 → 相册识别/手电筒 → 提示 → 关闭」。
 *
 * 关键实现取舍：
 * - **TextureView 而不是 SurfaceView**：`setUseTextureView(true)`。SurfaceView 在
 *   Compose 的裁剪/圆角/动画下会出现"洞"（它在窗口里是独立图层），预览要做圆角就必须走
 *   TextureView。
 * - **解码交给库**：`decodeContinuous {}` 每帧在库自己的 IO 线程上解，回调里只做
 *   "取字符串 + 交给上层"，不在回调里做任何耗时操作。
 * - **识别后延迟 [RESULT_DELAY_MS] 再回调**：二维码刚被识别的那一帧取景框里还是花的，
 *   立刻关闭会让人误以为"闪退"，延迟一下让用户看到"识别到了"。
 * - **权限自己申请**：清单里 CAMERA 早就有（原来依赖 CaptureActivity 申请），应用内扫码
 *   必须自己走一次运行时授权；用户拒绝时不把人踢走，卡片里给解释 + 「重新授权」+ 相册退路。
 * - **相册识别走本地解码**：选图后降到 [MAX_DECODE_SIDE] 以内再交给 zxing core 的
 *   [MultiFormatReader]，还要把四个方向都试一遍（手机拍的照片常被 EXIF 旋转过，
 *   像素本身没转，只按显示方向读会解不出来）。
 */
private const val RESULT_DELAY_MS = 140L

/** 四角角标长度（dp）。 */
private val CORNER_LEN = 22.dp

/** 角标线宽（dp）。 */
private val CORNER_STROKE = 4.dp

/** 角标离预览边缘的内缩（dp）。 */
private val CORNER_INSET = 14.dp

/** 相册图片解码前的最大边长：再大就降采样，避免一张 5000px 的照片直接把堆打爆。 */
private const val MAX_DECODE_SIDE = 1600

/**
 * 弹窗式应用内扫码。
 *
 * @param onCodeScanned 识别成功回调，在主线程；回调后本组件**不会**自己关闭，
 *   由调用方决定何时收起（通常先关掉再走业务逻辑，避免"扫码弹窗压着结果弹窗"）。
 * @param subtitle 标题下的一句副标题，不需要就传 null。
 * @param singleShot 是否只交付一次。签到场景是 true；需要连扫时传 false。
 */
@Composable
fun InAppQrScanner(
    onDismiss: () -> Unit,
    onCodeScanned: (String) -> Unit,
    title: String = "扫一扫",
    subtitle: String? = null,
    hint: String = "将二维码对准取景框，保持完整、清晰",
    singleShot: Boolean = true,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    // 只在"第一次问过之后才知道用户是拒绝还是没问过"，用来区分提示文案
    var askedOnce by remember { mutableStateOf(false) }
    var torchOn by remember { mutableStateOf(false) }
    var cameraError by remember { mutableStateOf<String?>(null) }
    // 相册识别的失败提示 / 权限被拒提示，都走这一行小字
    var notice by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var delivered by remember { mutableStateOf(false) }
    var scanned by remember { mutableStateOf<String?>(null) }

    // 唯一出口：相机命中与相册识别都从这里交付，singleShot 的"只交付一次"也在这里判
    fun deliver(text: String) {
        if (text.isBlank()) return
        if (singleShot && delivered) return
        delivered = true
        onCodeScanned(text)
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { ok ->
        granted = ok
        askedOnce = true
        if (!ok) notice = "没有相机权限，可以先用「相册识别」"
    }

    LaunchedEffect(Unit) {
        if (!granted) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    // 相机命中后不立刻回调，留一点时间让用户看到取景框里的"命中"效果
    LaunchedEffect(scanned) {
        val text = scanned ?: return@LaunchedEffect
        delay(RESULT_DELAY_MS)
        deliver(text)
    }

    // 相册识别：GetContent 不需要任何存储权限，选完自己解
    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        notice = null
        busy = true
        scope.launch {
            val text = withContext(Dispatchers.IO) { decodeQrFromUri(context, uri) }
            busy = false
            if (text.isNullOrBlank()) {
                notice = "这张图里没找到二维码，换一张试试"
            } else {
                deliver(text)
            }
        }
    }

    SystemDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = title,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        },
        dismissButton = {
            SystemSecondaryButton(
                text = "关闭",
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth()
            )
        },
    ) {
        if (!subtitle.isNullOrBlank()) {
            Text(
                text = subtitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
                modifier = Modifier.padding(bottom = 12.dp),
            )
        }

        // 相机预览：正方形 + 圆角，四角角标画在里面
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(18.dp))
                .background(Color.Black),
        ) {
            if (granted) {
                CameraLayer(
                    torchOn = torchOn,
                    onScanned = { text ->
                        cameraError = null
                        scanned = text
                    },
                    onError = { message -> cameraError = message },
                )
            } else {
                PermissionLayer(
                    askedOnce = askedOnce,
                    onRequest = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                )
            }

            // 取景角标 + 扫描线：纯装饰，不影响解码（库解整帧）
            CornerBrackets()

            cameraError?.let { message ->
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = 20.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.Black.copy(alpha = 0.66f))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    Text(
                        text = message.ifBlank { "相机启动失败，请检查是否有其它应用正在使用摄像头" },
                        color = Color.White,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            if (busy) {
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.Black.copy(alpha = 0.66f))
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    Text(text = "正在识别…", color = Color.White, fontSize = 13.sp)
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // 两个并排胶囊：相册识别 / 手电筒
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SystemSecondaryButton(
                text = "相册识别",
                onClick = { galleryLauncher.launch("image/*") },
                modifier = Modifier.weight(1f),
                enabled = !busy,
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Filled.PhotoLibrary,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                },
            )
            val torchIcon: @Composable () -> Unit = {
                Icon(
                    imageVector = if (torchOn) Icons.Filled.FlashOn else Icons.Filled.FlashOff,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
            }
            if (torchOn) {
                SystemPrimaryButton(
                    text = "手电筒已开",
                    onClick = { torchOn = false },
                    modifier = Modifier.weight(1f),
                    enabled = granted,
                    leadingIcon = torchIcon,
                )
            } else {
                SystemSecondaryButton(
                    text = "手电筒",
                    onClick = { torchOn = true },
                    modifier = Modifier.weight(1f),
                    enabled = granted,
                    leadingIcon = torchIcon,
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        Text(
            text = notice ?: hint,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * 相机层：把 [BarcodeView] 塞进 Compose，并和生命周期对齐。
 *
 * 相机必须在离开页面时**同步**关掉（`pauseAndWait()`），否则回到上一页还能听见
 * 相机马达声，而且再进来时会 `Camera already in use`。
 */
@Composable
private fun CameraLayer(
    torchOn: Boolean,
    onScanned: (String) -> Unit,
    onError: (String?) -> Unit,
) {
    var barcodeView by remember { mutableStateOf<BarcodeView?>(null) }

    // 手电筒开关跟着状态走
    LaunchedEffect(torchOn, barcodeView) {
        val view = barcodeView ?: return@LaunchedEffect
        runCatching { view.setTorch(torchOn) }
    }

    // view 一到位就开相机。**不能放在 AndroidView 的 update 里**：update 每次重组都会跑，
    // 而 resume() 会走 initCamera()，重复调用等于反复重开摄像头。
    LaunchedEffect(barcodeView) {
        val view = barcodeView ?: return@LaunchedEffect
        runCatching { view.resume() }
    }

    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> {
                    // 从相册选图回来 / 切回前台都会走这里；barcodeView 还没建好时是空操作
                    runCatching { barcodeView?.resume() }
                }
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> {
                    runCatching { barcodeView?.pause() }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            // 同步关相机：异步 pause() 在退出页面时可能来不及，会留下持有的 Camera
            runCatching { barcodeView?.pauseAndWait() }
        }
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            BarcodeView(ctx).apply {
                // TextureView：能在 Compose 里正常裁剪与动画（SurfaceView 会在窗口里挖洞）
                setUseTextureView(true)
                // 只解二维码：签到码都是 QR，其它格式纯属误报来源
                decoderFactory = DefaultDecoderFactory(listOf(BarcodeFormat.QR_CODE))
                // 反色也能解：有些屏幕截图/深色底签到码是白码黑底
                cameraSettings.isScanInverted = true
                cameraSettings.isAutoFocusEnabled = true
                // 相机起不来（被别的应用占用、硬件异常）时给一句人话，而不是黑屏
                addStateListener(object : CameraPreview.StateListener {
                    override fun previewSized() = Unit
                    override fun previewStarted() = Unit
                    override fun previewStopped() = Unit
                    override fun cameraClosed() = Unit
                    override fun cameraError(e: Exception) {
                        onError(e.message)
                    }
                })
                decodeContinuous { result: BarcodeResult ->
                    val text = result.text
                    if (text.isNullOrBlank()) return@decodeContinuous
                    onScanned(text)
                }
            }
        },
        update = { view -> barcodeView = view },
    )
}

/**
 * 没给相机权限时占在预览框里的说明。
 *
 * 不直接把人踢回上一页：用户可能只是手滑点了"拒绝"，留在这里能一键再申请，
 * 也告诉他"还可以从相册识别"这条退路。
 */
@Composable
private fun PermissionLayer(
    askedOnce: Boolean,
    onRequest: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 22.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "需要摄像头权限",
            color = Color.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = if (askedOnce) {
                "扫码需要打开摄像头。系统没弹授权框的话，可到「设置 → 应用 → 权限」里手动打开；也可以先用相册识别。"
            } else {
                "扫码需要打开摄像头。也可以直接用相册里的二维码图片识别。"
            },
            color = Color.White.copy(alpha = 0.72f),
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))
        TextButton(onClick = onRequest) {
            Text("重新授权", color = Color(0xFF64D2FF), fontSize = 14.sp)
        }
    }
}

/**
 * 取景角标：预览框内缩 [CORNER_INSET] 的四只白角 + 一条上下往复的扫描线。
 *
 * 只是装饰，不参与解码；全部按当前盒子尺寸现算，预览框多大角标就多大，不会变形。
 */
@Composable
private fun CornerBrackets() {
    val transition = rememberInfiniteTransition(label = "qrViewfinder")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2200),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "qrScanLine",
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .drawBehind {
                val inset = CORNER_INSET.toPx()
                val left = inset
                val top = inset
                val right = (size.width - inset).coerceAtLeast(left)
                val bottom = (size.height - inset).coerceAtLeast(top)
                val stroke = CORNER_STROKE.toPx()
                val corner = CORNER_LEN.toPx().coerceAtMost(minOf(right - left, bottom - top) / 2f)
                val white = Color.White

                // 左上
                drawLine(white, Offset(left, top), Offset(left + corner, top), stroke, StrokeCap.Round)
                drawLine(white, Offset(left, top), Offset(left, top + corner), stroke, StrokeCap.Round)
                // 右上
                drawLine(white, Offset(right - corner, top), Offset(right, top), stroke, StrokeCap.Round)
                drawLine(white, Offset(right, top), Offset(right, top + corner), stroke, StrokeCap.Round)
                // 左下
                drawLine(white, Offset(left, bottom - corner), Offset(left, bottom), stroke, StrokeCap.Round)
                drawLine(white, Offset(left, bottom), Offset(left + corner, bottom), stroke, StrokeCap.Round)
                // 右下
                drawLine(white, Offset(right - corner, bottom), Offset(right, bottom), stroke, StrokeCap.Round)
                drawLine(white, Offset(right, bottom - corner), Offset(right, bottom), stroke, StrokeCap.Round)

                // 扫描线：只在框内走，两端留出角标的空间
                val lineY = top + corner + (bottom - top - corner * 2f) * progress
                drawLine(
                    Color(0xFF64D2FF),
                    Offset(left + stroke * 1.5f, lineY),
                    Offset(right - stroke * 1.5f, lineY),
                    strokeWidth = 2.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            },
    )
}

/**
 * 从相册里挑的一张图里解出二维码。
 *
 * 两步走：先 `inJustDecodeBounds` 读尺寸算降采样倍数（相册原图动辄 4000~5000px，
 * 直接 decode 会 OOM），再按 [MAX_DECODE_SIDE] 以内真正解码；解不出来时把像素旋转
 * 90° 再试，四个方向试完仍失败才返回 null。
 */
private fun decodeQrFromUri(context: Context, uri: Uri): String? {
    val resolver = context.contentResolver
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    runCatching {
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    // 长边压到 MAX_DECODE_SIDE 以内：`inSampleSize` 只能取 2 的幂，所以要循环减半
    var sample = 1
    while (bounds.outWidth / sample > MAX_DECODE_SIDE || bounds.outHeight / sample > MAX_DECODE_SIDE) {
        sample *= 2
    }

    val bitmap: Bitmap = runCatching {
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
    }.getOrNull() ?: return null

    return try {
        decodeQrFromBitmap(bitmap)
    } finally {
        runCatching { bitmap.recycle() }
    }
}

/** 拿一张已解码的位图去找二维码，四个方向都试。 */
private fun decodeQrFromBitmap(bitmap: Bitmap): String? {
    val width = bitmap.width
    val height = bitmap.height
    if (width <= 0 || height <= 0) return null

    val pixels = IntArray(width * height)
    bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

    val hints = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        DecodeHintType.TRY_HARDER to true,
    )
    val reader = MultiFormatReader().apply { setHints(hints) }

    var current = pixels
    var currentWidth = width
    var currentHeight = height
    for (round in 0 until 4) {
        val text = runCatching {
            val source = RGBLuminanceSource(currentWidth, currentHeight, current)
            reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
        }.getOrNull()
        if (!text.isNullOrBlank()) return text
        if (round == 3) break
        // 顺时针转 90°：(x, y) -> (height-1-y, x)
        val rotated = IntArray(currentWidth * currentHeight)
        for (y in 0 until currentHeight) {
            for (x in 0 until currentWidth) {
                rotated[x * currentHeight + (currentHeight - 1 - y)] = current[y * currentWidth + x]
            }
        }
        current = rotated
        val swap = currentWidth
        currentWidth = currentHeight
        currentHeight = swap
    }
    return null
}