package com.hnnujw.course.ui.system

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.shape.CircleShape
import com.hnnujw.course.manager.UserAvatarStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 当前用户的头像。
 *
 * ## 为什么不用 Coil
 *
 * 头像文件是一个固定路径（[UserAvatarStore.avatarFile]），**每次更换都会原地覆盖**。
 * Coil 的磁盘/内存缓存是按 key 命名的，同一个路径第二次读会直接命中上一张缓存 ——
 * 于是用户换完头像、回到设置页，看到的还是旧图。旧实现是靠调用方传一个
 * `avatarRefreshKey` 计数器绕开缓存，那个 key 一旦漏传（在另一个页面复用头像时
 * 极容易漏）就复发。
 *
 * 这里自己解码：文件小（512×512 JPEG，约 60KB），解码在 IO 线程、串行一次，
 * 成本远低于一次缓存穿透调试。刷新由 [refreshKey] 驱动 —— 但因为它**同时**
 * 重新读盘，所以传不传都不会显示旧图（传了只是立刻重读）。
 *
 * @param refreshKey 变化即重新读盘。换完头像后由调用方 `++`。
 * @param placeholder 没有头像时的占位内容（通常是姓名首字）。
 */
@Composable
fun UserAvatar(
    refreshKey: Int = 0,
    modifier: Modifier = Modifier,
    placeholder: @Composable () -> Unit
) {
    val context = LocalContext.current
    var bitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    // 文件的存在性是"要不要显示图片"的判据，也必须跟着 refreshKey 重查：
    // 首次设置头像时文件是刚出现的，不重查就还是走占位分支。
    var hasFile by remember { mutableIntStateOf(0) }

    LaunchedEffect(refreshKey) {
        val result = withContext(Dispatchers.IO) {
            val file = UserAvatarStore.avatarFile(context)
            if (!file.exists()) {
                null
            } else {
                runCatching {
                    android.graphics.BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap()
                }.getOrNull()
            }
        }
        bitmap = result
        hasFile = if (result != null) 1 else 0
    }

    val current = bitmap
    if (current != null) {
        Image(
            bitmap = current,
            contentDescription = "头像",
            modifier = modifier.clip(CircleShape),
            contentScale = ContentScale.Crop
        )
    } else {
        Box(
            modifier = modifier
                .clip(CircleShape)
                .background(com.hnnujw.course.ui.theme.NeuPrimary.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            placeholder()
        }
    }
}
