package com.hnnujw.course.manager

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import kotlin.math.max
import kotlin.math.min

/**
 * 「相册 URI → 取景框用的大图 → 裁剪结果」这条链路上的图像处理。
 *
 * ## 为什么头像与背景共用这一份
 *
 * 两条链路原先各有一套：头像是 [UserAvatarStore.prepareSource] 先落一张中间图、
 * 再由 `AvatarCropActivity` 重新解码；背景则是 `WallpaperImageStore.import`
 * 直接把整张原图吃进去（无裁剪）。用户要的是背景也走"选图 → 取景框 → 保存"，
 * 那就该是**一套**实现，否则比例换算、EXIF 处理、降采样上限三件事要维护两份，
 * 迟早只有一份是修过的（这个仓库已经在"两处各写一遍"上栽过好几次）。
 *
 * ## 关键约束：备料图分辨率 = 取景框坐标空间
 *
 * [decodeForCrop] 落出来的这张 Bitmap 同时是"屏幕上显示的那张"和"裁剪坐标的单位"，
 * 所以 [CROP_MAX_EDGE] 只能有一处定义。写死在两个地方的话，
 * 预览里框到的和最终裁出来的就会错位。
 */
object WallpaperCropStore {
    private const val TAG = "WallpaperCropStore"

    /**
     * 取景框用图的长边上限。
     *
     * 这个值同时是**预览分辨率**和**坐标空间单位**，改它必须同时考虑两件事：
     * 太小则裁剪结果发虚（背景要铺满整屏），太大则 OOM。
     * 2160 覆盖得了 1080p 竖屏的整屏铺满，最坏内存约 18MB。
     */
    private const val CROP_MAX_EDGE = 2160

    /** 头像的最终边长。头像只是个小圆圈，512 足够。 */
    internal const val AVATAR_EDGE_PX = 512

    /**
     * 读相册 URI 成一张可裁剪的大图：EXIF 旋转烘进去、长边压到 [CROP_MAX_EDGE]。
     *
     * 必须在 IO 线程调用。失败返回 null（调用方去提示用户）。
     */
    fun decodeForCrop(context: Context, uri: Uri): Bitmap? {
        return try {
            decodeUri(context, uri) ?: run {
                // 直接解不出来（相册给的流不可 seek、临时授权已过期、HEIC 之类），
                // 先把字节落到本地临时文件再解一次 —— 本地文件可 seek 且不依赖 URI 授权。
                Log.w(TAG, "直接解码失败，改走本地副本 $uri")
                decodeViaLocalCopy(context, uri)
            }
        } catch (t: Throwable) {
            // OOM 也在内：一张超大图不该把 App 带走
            Log.w(TAG, "读取待裁剪图片失败", t)
            null
        }
    }

    /** 把 URI 的字节复制到 cacheDir 再解码，解码完删掉副本。 */
    private fun decodeViaLocalCopy(context: Context, uri: Uri): Bitmap? {
        val copy = java.io.File(context.cacheDir, "crop_src_${System.currentTimeMillis()}.bin")
        return try {
            val copied = context.contentResolver.openInputStream(uri)?.use { input ->
                java.io.FileOutputStream(copy).use { output -> input.copyTo(output) }
                true
            } ?: false
            if (!copied || copy.length() <= 0L) {
                Log.w(TAG, "本地副本为空 $uri")
                return null
            }
            // 注意：EXIF 要在【原图】上读，副本是纯字节拷贝、EXIF 完整保留，所以这里
            // 传的是副本的 file:// URI，摆正逻辑照旧生效。
            decodeUri(context, Uri.fromFile(copy))
        } catch (t: Throwable) {
            Log.w(TAG, "本地副本解码失败", t)
            null
        } finally {
            copy.takeIf { it.exists() }?.delete()
        }
    }

    /** 从一个 URI 解出大图：EXIF 摆正 + 长边压到 [CROP_MAX_EDGE]。必须在 IO 线程调用。 */
    private fun decodeUri(context: Context, uri: Uri): Bitmap? {
        val resolver = context.contentResolver
        return try {
            // 量尺寸。**量尺寸这一步的返回值绝对不能接 elvis**：
            // `inJustDecodeBounds = true` 时 `decodeStream` 按契约【必然返回 null】，
            // 尺寸只写进 bounds。若写成 `openInputStream?.use { decodeStream(...) } ?: return null`，
            // elvis 命中的是 decodeStream 的 null 而不是"流没打开"，
            // 于是【任何图都会静默失败】——表现就是"无法读取这张图片"。
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            val measureStream = resolver.openInputStream(uri)
            if (measureStream == null) {
                Log.w(TAG, "量尺寸：打不开 $uri")
                return null
            }
            measureStream.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                Log.w(TAG, "尺寸解析失败 ${bounds.outWidth}x${bounds.outHeight}")
                return null
            }

            // 相册给的流不可 seek，量尺寸和真解码必须各开一次
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= CROP_MAX_EDGE) {
                sample *= 2
            }
            val options = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val decoded = resolver.openInputStream(uri)
                ?.use { BitmapFactory.decodeStream(it, null, options) }
                ?: return null

            val rotated = applyExifRotation(context, uri, decoded)
            val scaled = scaleLongEdge(rotated, CROP_MAX_EDGE)
            // rotated / decoded 可能是同一个对象（无需旋转时直接复用），
            // 回收要按 `!==` 逐级判断，不能无脑 recycle。
            if (scaled !== rotated && rotated !== decoded) rotated.recycle()
            if (scaled !== decoded) decoded.recycle()
            scaled
        } catch (t: Throwable) {
            // OOM 也在内：一张超大图不该把 App 带走
            Log.w(TAG, "读取待裁剪图片失败", t)
            null
        }
    }

    /**
     * 把 [src] 上的一块区域裁出来写进 [target]。
     *
     * [cropW]/[cropH] 由调用方按取景窗口比例算出，这里不再强制正方形 ——
     * 背景取景框是矩形，强制方块会把整张图压扁。
     * 输出尺寸按 [maxOutputEdge] 收口，避免一张 2160 的方图原样落盘。
     *
     * 必须在 IO 线程调用。
     */
    fun writeCropTo(
        src: Bitmap,
        target: File,
        cropX: Int,
        cropY: Int,
        cropW: Int,
        cropH: Int,
        maxOutputEdge: Int = CROP_MAX_EDGE
    ): Boolean = try {
        val x = cropX.coerceIn(0, (src.width - 1).coerceAtLeast(0))
        val y = cropY.coerceIn(0, (src.height - 1).coerceAtLeast(0))
        val w = cropW.coerceIn(1, (src.width - x).coerceAtLeast(1))
        val h = cropH.coerceIn(1, (src.height - y).coerceAtLeast(1))

        var out = Bitmap.createBitmap(src, x, y, w, h)
        val longEdge = max(out.width, out.height)
        if (longEdge > maxOutputEdge) {
            val ratio = maxOutputEdge.toFloat() / longEdge
            val scaled = Bitmap.createScaledBitmap(
                out,
                (out.width * ratio).toInt().coerceAtLeast(1),
                (out.height * ratio).toInt().coerceAtLeast(1),
                true
            )
            if (scaled !== out) {
                out.recycle()
                out = scaled
            }
        }
        target.parentFile?.mkdirs()
        // 先写临时文件再改名：中途被杀不会留下半张坏图覆盖掉可用的旧图
        val tmp = File(target.parentFile, "${target.name}.tmp")
        val written = FileOutputStream(tmp).use { out.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        if (!written) {
            tmp.delete()
            return false
        }
        if (target.exists()) target.delete()
        val moved = tmp.renameTo(target)
        if (!moved) {
            // renameTo 在部分 ROM 上跨挂载点会失败，退回复制
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
        out.recycle()
        true
    } catch (t: Throwable) {
        Log.w(TAG, "裁剪落盘失败", t)
        false
    }

    /** 头像落盘的复用入口：方形 + 512 边长 + EXIF 已在 [decodeForCrop] 处理过。 */
    fun writeAvatar(src: Bitmap, target: File, cropX: Int, cropY: Int, cropW: Int, cropH: Int): Boolean {
        // 头像最终画成圆，非方形会被拉扁，这里再取一遍短边做兜底
        val side = min(cropW, cropH).coerceAtLeast(1)
        return writeCropTo(src, target, cropX, cropY, side, side, maxOutputEdge = AVATAR_EDGE_PX)
    }

    private fun applyExifRotation(context: Context, uri: Uri, src: Bitmap): Bitmap {
        val orientation = runCatching {
            context.contentResolver.openInputStream(uri)?.use(::readOrientation)
                ?: ExifInterface.ORIENTATION_NORMAL
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

        val matrix = android.graphics.Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            else -> return src
        }
        return runCatching {
            Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
        }.getOrDefault(src)
    }

    private fun readOrientation(stream: InputStream): Int = runCatching {
        ExifInterface(stream).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL
        )
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

    /** 长边超过 [maxEdge] 才缩，否则原样返回（调用方按 `!==` 判断是否要回收）。 */
    private fun scaleLongEdge(src: Bitmap, maxEdge: Int): Bitmap {
        val long = max(src.width, src.height)
        if (long <= maxEdge) return src
        val ratio = maxEdge.toFloat() / long
        return runCatching {
            Bitmap.createScaledBitmap(
                src,
                (src.width * ratio).toInt().coerceAtLeast(1),
                (src.height * ratio).toInt().coerceAtLeast(1),
                true
            )
        }.getOrDefault(src)
    }
}
