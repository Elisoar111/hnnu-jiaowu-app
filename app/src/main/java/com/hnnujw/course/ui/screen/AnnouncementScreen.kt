package com.hnnujw.course.ui.screen

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.halilibo.richtext.markdown.Markdown
import com.halilibo.richtext.ui.material3.Material3RichText
import com.hnnujw.course.announcement.AnnouncementCenter
import com.hnnujw.course.announcement.AnnouncementManager
import com.hnnujw.course.ui.system.GlassPageScaffold
import com.hnnujw.course.ui.system.GlassToaster
import com.hnnujw.course.ui.system.SystemIconButton
import com.hnnujw.course.ui.system.PagePadding
import com.hnnujw.course.ui.system.SystemEmptyState
import com.hnnujw.course.ui.system.SystemLoadingState
import com.hnnujw.course.ui.system.SystemSegmentedControl
import com.hnnujw.course.ui.system.SystemStatusBadge
import com.hnnujw.course.ui.system.SystemTone
import com.hnnujw.course.ui.theme.NeuPrimary
import com.hnnujw.course.ui.theme.SemanticDanger
import com.hnnujw.course.ui.theme.SemanticWarning

/**
 * 公告中心页。交互骨架与 [MessageCenterScreen] 一致：顶栏（返回 + 刷新）→
 * 未读/已读分段控件 → 卡片列表 → 页内详情。
 *
 * 之所以从「设置页里的一个弹窗」改成独立全屏页：公告正文往往很长（迁移说明动辄上千字），
 * 弹窗那点高度只能靠一个小滚动区塞进去，读起来很难受，也没法和消息中心保持一致的体验。
 *
 * 两个分段的口径（版本锁定见 [AnnouncementCenter]）：
 * - 「未读」= 未读 **且适用于当前版本**；
 * - 「已读」= 已读的，加上不适用于当前版本的历史公告（历史公告会在行上打标）。
 *
 * 公告随包内置，没有"刷新"这个动作 —— 想看到新公告只能装新版。
 */
@Composable
fun AnnouncementScreen(onBack: () -> Unit) {
    val context = LocalContext.current

    var detail by remember { mutableStateOf<AnnouncementManager.Announcement?>(null) }
    /** 顶部筛选：0 = 未读（默认）、1 = 已读 / 历史。理由同消息中心：进来是想看还有什么没看。 */
    var filterTab by remember { mutableIntStateOf(0) }

    val loading = AnnouncementCenter.isLoading
    val unreadList = AnnouncementCenter.unread
    val readList = AnnouncementCenter.readOrArchived
    val unread = AnnouncementCenter.unreadCount

    LaunchedEffect(Unit) {
        // 公告随包内置，这里只是把 assets 里的那份读进内存
        AnnouncementCenter.load(context)
    }

    fun open(announcement: AnnouncementManager.Announcement) {
        detail = announcement
        // 打开即已读：红点与「我的」页入口当帧一起消失
        AnnouncementCenter.markRead(context, announcement.id)
    }

    GlassPageScaffold(
        title = if (detail != null) "公告详情" else "公告",
        subtitle = if (detail == null && unread > 0) "未读 $unread" else null,
        onBack = { if (detail != null) detail = null else onBack() },
        actions = {
            if (detail == null) {
                // 一键已读：同消息中心/选课页的 DoneAll 图标，常显、仅点击无长按
                SystemIconButton(Icons.Default.DoneAll, "一键已读", {
                    if (unread > 0) {
                        AnnouncementCenter.markAllRead(context)
                        GlassToaster.show("已全部标为已读")
                    } else {
                        GlassToaster.show("没有未读公告")
                    }
                })
            }
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = PagePadding, vertical = 12.dp)
        ) {
            val current = detail
            when {
                current != null -> AnnouncementDetailView(current)
                loading && unreadList.isEmpty() && readList.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        SystemLoadingState("正在载入公告…")
                    }
                }
                unreadList.isEmpty() && readList.isEmpty() -> {
                    SystemEmptyState(
                        title = "暂无公告",
                        message = "本版没有需要你知道的公告。更新说明与迁移提醒会随版本一起显示在这里。",
                        icon = Icons.Outlined.Campaign
                    )
                }
                else -> {
                    val visible = if (filterTab == 0) unreadList else readList
                    Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        SystemSegmentedControl(
                            options = listOf(
                                if (unreadList.isEmpty()) "未读" else "未读 ${unreadList.size}",
                                if (readList.isEmpty()) "已读" else "已读 ${readList.size}"
                            ),
                            selectedIndex = filterTab,
                            onSelect = { filterTab = it },
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (visible.isEmpty()) {
                            Box(
                                Modifier.fillMaxWidth().padding(top = 48.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    if (filterTab == 0) "暂无未读公告" else "暂无已读公告",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else {
                            visible.forEach { AnnouncementRow(it, ::open) }
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun AnnouncementRow(
    announcement: AnnouncementManager.Announcement,
    onClick: (AnnouncementManager.Announcement) -> Unit
) {
    val archived = AnnouncementCenter.isArchived(announcement)
    // 未读才给红点与加粗；历史公告一律按"已读"的视觉分量渲染 —— 它不是在等你处理的东西
    val unread = AnnouncementCenter.isUnread(announcement)

    Surface(
        modifier = Modifier.fillMaxWidth().clickable { onClick(announcement) },
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (unread) {
                Box(
                    Modifier.size(8.dp).padding(top = 6.dp).clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                )
            } else {
                Spacer(Modifier.width(8.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    announcement.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (unread) FontWeight.SemiBold else FontWeight.Normal,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // 类型色点：重要=红 / 提醒=橙 / 通知=主题色
                    Box(
                        Modifier.size(8.dp).clip(CircleShape)
                            .background(announcementTypeColor(announcement.type))
                    )
                    Text(
                        announcementMeta(announcement),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (announcement.content.isNotBlank()) {
                    Text(
                        // 正文里的换行在摘要里没有意义，压成一行空格再让 maxLines 截断
                        announcement.content.replace('\n', ' ').trim(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (archived) {
                SystemStatusBadge(text = "历史", tone = SystemTone.Neutral)
            }
        }
    }
}

@Composable
private fun AnnouncementDetailView(announcement: AnnouncementManager.Announcement) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            announcement.title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            announcementMeta(announcement),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Surface(
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(Modifier.padding(14.dp)) {
                if (announcement.contentType == "markdown") {
                    Material3RichText(modifier = Modifier.fillMaxWidth()) {
                        Markdown(content = announcement.content)
                    }
                } else {
                    // 显式写 lineHeight：MaterialTheme 的 bodyMedium 行高是给紧凑版式用的，
                    // 大段中文正文按它排会挤在一起。
                    Text(
                        announcement.content,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        lineHeight = 24.sp
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

/** 「重要 · 2026-09-20」这类副标题；历史公告统一标注，避免用户以为它和当前版本有关。 */
private fun announcementMeta(announcement: AnnouncementManager.Announcement): String {
    val parts = mutableListOf<String>()
    if (AnnouncementCenter.isArchived(announcement)) {
        parts.add("历史公告")
    } else {
        parts.add(announcementTypeLabel(announcement.type))
    }
    if (announcement.createdAt.isNotBlank()) parts.add(announcement.createdAt)
    return parts.joinToString(" · ")
}

/** 类型色：重要=危险红 / 提醒=警告橙 / 通知=主题色。NeuPrimary 是 @Composable 属性，所以这里也得是。 */
@Composable
private fun announcementTypeColor(type: String): Color = when (type) {
    "important" -> SemanticDanger
    "warning" -> SemanticWarning
    else -> NeuPrimary
}

private fun announcementTypeLabel(type: String): String = when (type) {
    "important" -> "重要"
    "warning" -> "提醒"
    else -> "通知"
}
