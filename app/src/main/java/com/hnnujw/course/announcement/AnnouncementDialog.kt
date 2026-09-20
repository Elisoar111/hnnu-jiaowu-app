package com.hnnujw.course.announcement

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.halilibo.richtext.markdown.Markdown
import com.halilibo.richtext.ui.material3.Material3RichText
import com.hnnujw.course.ui.system.LiquidButton
import com.hnnujw.course.ui.system.LiquidButtonStyle
import com.hnnujw.course.ui.system.SystemDialog
import com.hnnujw.course.ui.system.rememberScreenMetrics
import androidx.compose.foundation.shape.RoundedCornerShape

/**
 * 公告弹窗（玻璃 SystemDialog 版），按公告类型着色。
 */
@Composable
fun AnnouncementDialog(
    announcement: AnnouncementManager.Announcement,
    onDismiss: () -> Unit
) {
    // 根据类型选择颜色和图标
    val (primaryColor, secondaryColor, icon) = when (announcement.type) {
        "warning" -> Triple(Color(0xFFFF9800), Color(0xFFFFF3E0), Icons.Default.Warning)
        "important" -> Triple(Color(0xFFF44336), Color(0xFFFFEBEE), Icons.Default.Campaign)
        else -> Triple(Color(0xFF2196F3), Color(0xFFE3F2FD), Icons.Default.Info)
    }

    // 正文高度必须跟着屏幕走。写死 280dp 时，一张"图标 + 两行标题 + 正文 + 按钮"
    // 的卡片在 16:9 短屏上总高会超过可用高，弹窗底部被裁，用户看不到也点不到
    // 「确认」——公告越重要（标题越长）越容易触发。
    val screen = rememberScreenMetrics()
    val bodyMaxHeight = (screen.usableHeightDp * 0.30f).coerceIn(140.dp, 320.dp)

    SystemDialog(
        onDismissRequest = onDismiss,
        icon = {
            // 顶栏图标原来是 72dp 圆底 + 36dp 图标，比弹窗标题还抢眼。缩到 52/24：
            // 还是能一眼分清类型（重要=红、提醒=橙、通知=蓝），但不再压过标题。
            Surface(
                shape = CircleShape,
                color = secondaryColor,
                shadowElevation = 3.dp,
                modifier = Modifier.size(52.dp)
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = primaryColor
                    )
                }
            }
        },
        title = {
            Text(
                text = announcement.title,
                fontSize = 22.sp,
                fontWeight = FontWeight.Black,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                letterSpacing = 0.5.sp
            )
        },
        confirmButton = {
            LiquidButton(
                onClick = onDismiss,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                style = LiquidButtonStyle.SolidTinted,
                tint = primaryColor,
                shape = com.kyant.shapes.Capsule()
            ) {
                Text(
                    text = "确认",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
            }
        }
    ) {
        // 可滚动的正文内容，高度上限随屏幕可用高自适应（见 bodyMaxHeight）
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = bodyMaxHeight)
                .verticalScroll(rememberScrollState())
        ) {
            if (announcement.contentType == "markdown") {
                Material3RichText(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp)
                ) {
                    Markdown(content = announcement.content)
                }
            } else {
                Text(
                    text = announcement.content,
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Start,
                    lineHeight = 26.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp)
                )
            }
        }
    }
}
