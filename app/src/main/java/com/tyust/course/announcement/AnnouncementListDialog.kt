package com.tyust.course.announcement

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tyust.course.ui.system.SystemDialog
import com.tyust.course.ui.system.SystemPrimaryButton
import com.tyust.course.ui.system.SystemSecondaryButton

/**
 * 公告列表弹窗（「我的」→ 公告）：展示开发者发布过的全部公告，
 * 未读的带红点，点开单条即标记已读。刷新 = 重新拉一次 announcement.json。
 */
@Composable
fun AnnouncementListDialog(
    announcements: List<AnnouncementManager.Announcement>,
    isLoading: Boolean,
    isRead: (String) -> Boolean,
    onRefresh: () -> Unit,
    onOpen: (AnnouncementManager.Announcement) -> Unit,
    onDismiss: () -> Unit
) {
    SystemDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "公告",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        },
        confirmButton = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SystemSecondaryButton(
                    text = if (isLoading) "刷新中…" else "刷新",
                    onClick = onRefresh,
                    enabled = !isLoading,
                    modifier = Modifier.weight(1f)
                )
                SystemPrimaryButton(
                    text = "知道了",
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    ) {
        if (announcements.isEmpty()) {
            Text(
                text = if (isLoading) "正在获取公告…" else "暂无公告",
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 24.dp)
            )
            return@SystemDialog
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 380.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            announcements.forEach { announcement ->
                val read = isRead(announcement.id)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                            shape = RoundedCornerShape(14.dp)
                        )
                        .clickable { onOpen(announcement) }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 类型色点：重要=红 / 警告=橙 / 普通=蓝
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(color = typeColor(announcement.type), shape = CircleShape)
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = announcement.title,
                            fontSize = 15.sp,
                            fontWeight = if (read) FontWeight.Medium else FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = when (announcement.type) {
                                "important" -> "重要"
                                "warning" -> "提醒"
                                else -> "通知"
                            } + " · " + (if (read) "已读" else "未读"),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (!read) {
                        Spacer(Modifier.width(8.dp))
                        // 红点：与消息中心一致的未读标记
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(color = Color(0xFFFF3B30), shape = CircleShape)
                        )
                    }
                }
            }
        }
    }
}

private fun typeColor(type: String): Color = when (type) {
    "important" -> Color(0xFFF44336)
    "warning" -> Color(0xFFFF9800)
    else -> Color(0xFF2196F3)
}
