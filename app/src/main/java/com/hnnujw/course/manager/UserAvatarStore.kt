package com.hnnujw.course.manager

import android.content.Context
import java.io.File

/**
 * 用户自定义头像的落盘位置。
 *
 * ## 这里只剩"路径"，图像处理已经整体搬到 [WallpaperCropStore]
 *
 * 头像与自定义背景现在走**同一条**链路：
 *
 * ```
 * 相册 URI → ImageCropActivity（取景框调整）→ WallpaperCropStore.writeCropTo → 目标文件
 * ```
 *
 * 也就是说，原先这里那一整套"备料"（`prepareSource`：降采样 + EXIF + 落中间图）
 * 与"裁剪落盘"（`importAvatar`）都成了重复实现 —— 同一件事在 `WallpaperCropStore`
 * 里已经有一份更完整的（比例可配、输出尺寸可配）。留着两份的直接后果是
 * "修了一处、另一处还是旧的"，而这个仓库已经因此栽过好几次。
 *
 * 所以本对象现在只负责一件事：**头像文件在哪**。
 */
object UserAvatarStore {
    private const val AVATAR_NAME = "user_avatar.jpg"

    /** 头像成品文件。裁剪页直接把结果写到这里。 */
    fun avatarFile(context: Context): File = File(context.filesDir, AVATAR_NAME)

    fun hasAvatar(context: Context): Boolean = avatarFile(context).exists()
}
