package com.tyust.course.ui.screen

import android.content.Context
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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tyust.course.academic.AcademicMessage
import com.tyust.course.academic.AcademicMessageDetail
import com.tyust.course.academic.MessageCenterManager
import com.tyust.course.academic.MessageCenterNotifier
import com.tyust.course.academic.MessageCenterResult
import com.tyust.course.model.SchoolConfig
import com.tyust.course.ui.system.GlassPageScaffold
import com.tyust.course.ui.system.PagePadding
import com.tyust.course.ui.system.SystemIconButton
import com.tyust.course.ui.system.SystemPrimaryButton
import com.tyust.course.ui.system.SystemSegmentedControl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 列表页的错误态：需要登录 / 解析失败（可回退网页）。 */
private sealed class MessageError {
    abstract val message: String
    data class NeedLogin(override val message: String) : MessageError()
    data class Failure(override val message: String, val webUrl: String) : MessageError()
}

/** 详情页状态。 */
private sealed class DetailState {
    data object Loading : DetailState()
    data class Content(val detail: AcademicMessageDetail) : DetailState()
    data class Error(val message: String) : DetailState()
}

@Composable
fun MessageCenterScreen(
    school: SchoolConfig?,
    accountKey: String,
    onOpenWeb: (String) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var messages by remember { mutableStateOf<List<AcademicMessage>>(emptyList()) }
    var error by remember { mutableStateOf<MessageError?>(null) }
    var loading by remember { mutableStateOf(true) }
    var detail by remember { mutableStateOf<DetailState?>(null) }
    /**
     * 顶部筛选：0 = 未读（默认，在左）、1 = 已读。
     *
     * 为什么默认停在未读：进消息中心的人是想看**还有什么没看**，
     * 已读那一堆是"查旧账"时才翻的，不该抢默认位。
     */
    var filterTab by remember { mutableIntStateOf(0) }

    // 未读数走全局 state：在详情里读掉一条之后，「我的」页入口的红点也当帧消失
    val unread = MessageCenterManager.unread

    suspend fun reload() {
        loading = true
        error = null
        val result = MessageCenterManager.load(context, school, accountKey)
        withContext(Dispatchers.Main) {
            when (result) {
                is MessageCenterResult.Success -> {
                    messages = result.messages
                    // 用户已经看到这批消息了：只更新播报基线，不要再弹一次系统通知
                    MessageCenterNotifier.markSeen(context, accountKey, result.messages)
                }
                is MessageCenterResult.NeedLogin -> {
                    error = MessageError.NeedLogin(result.message)
                    MessageCenterManager.publishUnread(messages)
                }
                is MessageCenterResult.Failure -> {
                    error = MessageError.Failure(result.message, result.webUrl)
                    MessageCenterManager.publishUnread(messages)
                }
            }
            loading = false
        }
    }

    fun openMessage(message: AcademicMessage) {
        // 乐观标记已读：写回缓存 + 刷新未读计数
        messages = messages.map { if (it.id == message.id) it.copy(read = true) else it }
        MessageCenterManager.writeCache(context, accountKey, messages)
        MessageCenterManager.publishUnread(messages)
        // 列表接口里已经带了正文（正方 `xxnr`）：这种消息没有独立详情页，
        // 就地展示即可，不必再打一次网络，也不会撞上"无法加载消息详情"。
        if (message.detailUrl.isBlank()) {
            detail = DetailState.Content(
                AcademicMessageDetail(
                    title = message.title,
                    sender = message.sender,
                    sendTime = message.sendTime,
                    content = message.content.ifBlank { message.summary }
                )
            )
            return
        }
        detail = DetailState.Loading
        scope.launch(Dispatchers.IO) {
            val loaded = MessageCenterManager.loadDetail(school, accountKey, message.detailUrl)
            withContext(Dispatchers.Main) {
                detail = if (loaded != null && loaded.content.isNotBlank()) {
                    DetailState.Content(loaded)
                } else {
                    // 详情页取不到（链接过期 / 结构变了）时，退回列表里带的正文，
                    // 而不是给用户一句"无法加载"
                    DetailState.Content(
                        AcademicMessageDetail(
                            title = loaded?.title?.ifBlank { message.title } ?: message.title,
                            sender = message.sender,
                            sendTime = message.sendTime,
                            content = message.content.ifBlank { message.summary }
                        )
                    )
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        // 离线优先：先展示缓存，再刷新
        MessageCenterManager.readCache(context, accountKey)?.let { cached ->
            MessageCenterManager.publishUnread(cached)
            withContext(Dispatchers.Main) { messages = cached; loading = false }
        }
        reload()
    }

    GlassPageScaffold(
        title = if (detail != null) "消息详情" else "消息中心",
        subtitle = if (detail == null && unread > 0) "未读 $unread" else null,
        onBack = {
            if (detail != null) detail = null else onBack()
        },
        actions = {
            if (detail == null) {
                SystemIconButton(Icons.Default.Refresh, "刷新", {
                    scope.launch(Dispatchers.IO) { reload() }
                })
            }
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = PagePadding, vertical = 12.dp)
        ) {
            when (val d = detail) {
                is DetailState.Loading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                is DetailState.Content -> DetailView(d.detail)
                is DetailState.Error -> ErrorView(d.message, webUrl = school?.getFullBasePath()?.trimEnd('/').orEmpty(), onOpenWeb)
                null -> when {
                    loading && messages.isEmpty() && error == null -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                    error != null -> {
                        val e = error!!
                        ErrorView(
                            message = e.message,
                            webUrl = if (e is MessageError.Failure) e.webUrl else school?.getFullBasePath()?.trimEnd('/').orEmpty(),
                            onOpenWeb = onOpenWeb
                        )
                    }
                    messages.isEmpty() -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("暂无消息", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    else -> {
                        // 排序：组内按时间倒序（最新在最上）。
                        // 列表来源（缓存 / 网络）可能没排过序，这里再排一次，
                        // 保证"最新消息在最上方"在任何数据来源下都成立。
                        val ordered = messages.sortedByDescending { it.sendTime }
                        val unreadList = ordered.filter { !it.read }
                        val readList = ordered.filter { it.read }
                        val visible = if (filterTab == 0) unreadList else readList
                        Column(
                            Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            // 未读 / 已读 切换。走全站统一的液态玻璃分段控件：
                            // 轨道是 Navigation 档材质、指示片是 Interactive 档，
                            // 33+ 走真折射、31/32 走离屏折射、更低版本退回模糊 + 色散。
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
                                        if (filterTab == 0) "暂无未读消息" else "暂无已读消息",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            } else {
                                visible.forEach { MessageRow(it, ::openMessage) }
                            }
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailView(detail: AcademicMessageDetail) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (detail.title.isNotBlank()) {
            Text(detail.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
        }
        val meta = listOfNotNull(
            detail.sender.takeIf { it.isNotBlank() },
            detail.sendTime.takeIf { it.isNotBlank() }
        ).joinToString(" · ")
        if (meta.isNotBlank()) {
            Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Surface(
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                detail.content.ifBlank { "（无正文内容）" },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(14.dp)
            )
        }
    }
}

@Composable
private fun MessageRow(message: AcademicMessage, onClick: (AcademicMessage) -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = { onClick(message) }),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (message.read) Spacer(Modifier.width(8.dp)) else Box(
                Modifier.size(8.dp).padding(top = 6.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary)
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    message.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (message.read) FontWeight.Normal else FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                val sub = listOfNotNull(
                    message.sender.takeIf { it.isNotBlank() },
                    message.sendTime.takeIf { it.isNotBlank() }
                ).joinToString(" · ")
                if (sub.isNotBlank()) {
                    Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (message.summary.isNotBlank()) {
                    Text(
                        message.summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun ErrorView(message: String, webUrl: String, onOpenWeb: (String) -> Unit) {
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(48.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (webUrl.isNotBlank()) {
            SystemPrimaryButton("在教务网页查看", { onOpenWeb(webUrl) }, Modifier.fillMaxWidth(0.8f))
        }
    }
}

// 供入口角标复用：读取未读数（不触发网络）。
fun messageCenterUnread(context: Context, accountKey: String): Int {
    MessageCenterManager.refreshUnreadFromCache(context, accountKey)
    return MessageCenterManager.unread
}
