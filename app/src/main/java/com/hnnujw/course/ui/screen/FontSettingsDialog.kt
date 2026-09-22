package com.hnnujw.course.ui.screen

import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hnnujw.course.manager.AppFontOption
import com.hnnujw.course.manager.AppearanceSettingsManager
import com.hnnujw.course.manager.CustomFontStore
import com.hnnujw.course.ui.system.GlassToaster
import com.hnnujw.course.ui.system.SystemDialog
import com.hnnujw.course.ui.system.SystemPrimaryButton
import com.hnnujw.course.ui.theme.NeuPrimary
import com.hnnujw.course.ui.theme.PingFangFontFamily

/**
 * 字体选择弹窗。
 *
 * 交互与"主题"弹窗一致：整行可点、选中行尾打勾，选择当帧生效（主题层
 * 监听 [AppearanceSettingsManager.appFont] 重建 Typography）。
 *
 * 「自定义字体」行的点击语义分两段：
 * - 已经导入过字体 → 切到自定义档；
 * - 还没导入 → 直接拉起系统文件选择器（SAF）选 ttf / otf。
 * 无论处于哪个档位，底部的「导入字体文件…」一行都能随时发起导入，
 * 新字体会覆盖旧的（导入即应用）。
 */
@Composable
fun FontSettingsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current

    // 自定义字体的预览字族：按内容版本 remember，重新导入后重读磁盘。
    // null 表示当前没有可用的自定义字体（未导入或文件损坏）。
    val customFamily = remember(AppearanceSettingsManager.customFontVersion) {
        CustomFontStore.loadTypeface(context)?.let { FontFamily(it) }
    }

    val fontPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        // 导入在管理器自己的作用域里异步执行：弹窗中途关掉也不会把导入掐死。
        AppearanceSettingsManager.importCustomFont(
            context = context,
            uri = uri,
            suggestedName = queryFontDisplayName(context, uri)
        ) { ok, message ->
            GlassToaster.show(if (ok) "已应用字体：$message" else message)
        }
    }

    SystemDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "字体",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        },
        confirmButton = {
            SystemPrimaryButton(
                text = "完成",
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth()
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 420.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = "切换立即生效；导入的字体保存在应用内，卸载重装后需重新导入。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
            )

            FontOptionRow(
                label = AppFontOption.System.label,
                selected = AppearanceSettingsManager.appFont == AppFontOption.System,
                preview = "校园助理 Abc 123",
                previewFamily = FontFamily.Default,
                onClick = { AppearanceSettingsManager.updateAppFont(AppFontOption.System) }
            )
            FontOptionRow(
                label = AppFontOption.Apple.label,
                selected = AppearanceSettingsManager.appFont == AppFontOption.Apple,
                preview = "校园助理 Abc 123",
                previewFamily = PingFangFontFamily,
                onClick = { AppearanceSettingsManager.updateAppFont(AppFontOption.Apple) }
            )
            FontOptionRow(
                label = AppFontOption.Custom.label,
                selected = AppearanceSettingsManager.appFont == AppFontOption.Custom,
                preview = if (customFamily != null) {
                    AppearanceSettingsManager.customFontName
                } else {
                    "未导入 · 点击选择手机中的 ttf / otf 字体文件"
                },
                previewFamily = customFamily ?: FontFamily.Default,
                onClick = {
                    if (customFamily != null) {
                        AppearanceSettingsManager.updateAppFont(AppFontOption.Custom)
                    } else {
                        fontPickerLauncher.launch(FONT_MIME_TYPES)
                    }
                }
            )

            // 导入入口单独一行：已导入过自定义字体的用户也能随时换新字体
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .clickable { fontPickerLauncher.launch(FONT_MIME_TYPES) }
                    .padding(horizontal = 8.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Outlined.UploadFile,
                    contentDescription = null,
                    tint = NeuPrimary,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = "导入字体文件…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 10.dp)
                )
            }
        }
    }
}

/** SAF 里能代表字体文件的 MIME。文件管理器口径不一，多给几个常用的；选错文件会被导入校验拦下。 */
private val FONT_MIME_TYPES = arrayOf(
    "font/ttf",
    "font/otf",
    "application/x-font-ttf",
    "application/x-font-otf",
    "application/octet-stream"
)

/** 单个字体选项行：左侧名称 + 预览文字，选中时行尾打勾。 */
@Composable
private fun FontOptionRow(
    label: String,
    selected: Boolean,
    preview: String,
    previewFamily: FontFamily,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
            )
            Text(
                text = preview,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = previewFamily,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        if (selected) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = NeuPrimary,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/** 从 SAF Uri 里问出原始文件名（去掉路径、留扩展名），拿不到就给个中性默认。 */
private fun queryFontDisplayName(context: android.content.Context, uri: android.net.Uri): String {
    val raw = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else ""
            }
    }.getOrNull().orEmpty()
    return raw.substringAfterLast('/').ifBlank { "自定义字体" }
}
