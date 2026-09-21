package com.hnnujw.course.secondclass

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.InvertedLuminanceSource
import com.google.zxing.MultiFormatReader
import com.google.zxing.ReaderException
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer

/**
 * 从相册图片里解出二维码文本（「图片导入扫码」的后端）。
 *
 * 大图先按边长采样到 1024px 以内再解码，防 OOM；EXIF 方向先摆正。
 * 解码策略按成功率叠加（截图类二维码失败几乎都出在二值化上）：
 * 1. 0°/90°/180°/270° 四个方向各试一次（二维码没有方向性）；
 * 2. 每个方向先 [HybridBinarizer]（zxing 默认，适合照片），失败换
 *    [GlobalHistogramBinarizer]（对大块留白/纯色背景的**截图**更稳），
 *    再失败换反色（部分截图二维码是"白码黑底"）；
 * 3. 全部失败再把图缩到 512px 重跑一轮——采样插值偶尔会把小码的模块糊掉，
 *    缩小反而能解出来。
 * 识别不出返回 null，调用方提示重试。
 */
fun decodeQrFromImage(context: Context, uri: Uri): String? {
    val resolver = context.contentResolver
    // ⚠️ `inJustDecodeBounds = true` 时 `decodeStream` 按契约【必然返回 null】，尺寸只写进 bounds。
    // 它的返回值不能进 elvis —— 写成 `openInputStream(...)?.use { decodeStream(...) } ?: return null`
    // 时，elvis 命中的是 decodeStream 的 null 而不是"流没打开"，于是【任何图都在这里直接返回 null】，
    // 一步都没真正解码，表现就是"选完图提示识别不到二维码"。
    // （同一个坑 WallpaperCropStore / WallpaperImageStore 都踩过并留了注释，这里别再犯。）
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    val measureStream = resolver.openInputStream(uri) ?: return null
    measureStream.use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    var sample = 1
    while (bounds.outWidth / (sample * 2) >= 1024 || bounds.outHeight / (sample * 2) >= 1024) {
        sample *= 2
    }
    // 这一次才是真解码，返回值就是一个 Bitmap —— elvis 在这里才是有意义的
    val bitmap = resolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
    } ?: return null

    val upright = applyExifRotation(context, uri, bitmap)
    return decodeQrBitmap(upright)
}

/** 按 EXIF 方向摆正图片；没有方向信息或旋转失败时原样返回。 */
private fun applyExifRotation(context: Context, uri: Uri, bitmap: Bitmap): Bitmap = runCatching {
    val orientation = context.contentResolver.openInputStream(uri)?.use { stream ->
        ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    } ?: ExifInterface.ORIENTATION_NORMAL
    val degrees = when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90f
        ExifInterface.ORIENTATION_ROTATE_180 -> 180f
        ExifInterface.ORIENTATION_ROTATE_270 -> 270f
        else -> 0f
    }
    if (degrees == 0f) bitmap else Bitmap.createBitmap(
        bitmap, 0, 0, bitmap.width, bitmap.height,
        Matrix().apply { postRotate(degrees) }, true
    )
}.getOrDefault(bitmap)

/** 对一张图按「4 方向 × 3 种二值化/反色」穷举解码；用 [MultiFormatReader.decodeWithState] 复用 reader。 */
private fun MultiFormatReader.exhaustiveDecode(source: Bitmap): String? {
    var current = source
    repeat(4) {
        val pixels = IntArray(current.width * current.height)
        current.getPixels(pixels, 0, current.width, 0, 0, current.width, current.height)
        val luminance = RGBLuminanceSource(current.width, current.height, pixels)
        // Hybrid → Global → 反色(Hybrid)，覆盖照片 / 截图 / 白码黑底三类主流形态
        for (factory in listOf(
            { b: com.google.zxing.LuminanceSource -> HybridBinarizer(b) },
            { b: com.google.zxing.LuminanceSource -> GlobalHistogramBinarizer(b) },
            { b: com.google.zxing.LuminanceSource -> HybridBinarizer(InvertedLuminanceSource(b)) },
        )) {
            // 防御性收口，不是为了修某个已知现象：MultiFormatReader.decodeInternal 内部会把
            // ReaderException（含 ChecksumException / FormatException）吞掉、统一抛
            // NotFoundException，所以按 ReaderException 兜住与原来等价、但少一层对库内部实现的依赖。
            // 真正要顺手治的是另一个隐患：decodeWithState 是平台类型，理论上可以回 null，
            // 原来的 `.text` 会直接 NPE 并把整轮「4 方向 × 3 二值化」穷举打断。
            val text = try {
                decodeWithState(BinaryBitmap(factory(luminance)))?.text
            } catch (e: ReaderException) {
                null
            }
            if (!text.isNullOrBlank()) return text
            reset()
        }
        // 这一轮没解出来：转 90° 再试（倒着拍/横着扫的码没有方向性）
        current = Bitmap.createBitmap(
            current, 0, 0, current.width, current.height,
            Matrix().apply { postRotate(90f) }, true
        )
    }
    return null
}

fun decodeQrBitmap(bitmap: Bitmap): String? {
    val width = bitmap.width
    val height = bitmap.height
    if (width <= 0 || height <= 0) return null
    val reader = MultiFormatReader().apply {
        setHints(
            mapOf(
                DecodeHintType.TRY_HARDER to true,
                DecodeHintType.POSSIBLE_FORMATS to listOf(com.google.zxing.BarcodeFormat.QR_CODE),
            )
        )
    }
    try {
        reader.exhaustiveDecode(bitmap)?.let { return it }
        // 缩小重试：1024px 采样后模块粘连/插值失真的小码，缩到 512 反而能解
        val maxSide = maxOf(width, height)
        if (maxSide > 512) {
            val scale = 512f / maxSide
            val small = Bitmap.createScaledBitmap(
                bitmap,
                (width * scale).toInt().coerceAtLeast(1),
                (height * scale).toInt().coerceAtLeast(1),
                true,
            )
            reader.exhaustiveDecode(small)?.let { return it }
        }
        return null
    } finally {
        reader.reset()
    }
}
