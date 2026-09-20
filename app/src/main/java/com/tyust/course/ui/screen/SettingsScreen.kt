package com.tyust.course.ui.screen

import com.tyust.course.ui.theme.moduleEntrance

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.AssignmentInd
import androidx.compose.material.icons.outlined.Assignment
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ContentPasteSearch
import androidx.compose.material.icons.automirrored.outlined.Login
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.ManageAccounts
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.School
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tyust.course.ui.system.InsetGroupedRow
import com.tyust.course.ui.system.InsetGroupedSection
import com.tyust.course.ui.system.LiquidSwitch
import com.tyust.course.ui.system.PagePadding
import com.tyust.course.ui.system.SectionSpacing
import com.tyust.course.ui.system.SystemCard
import com.tyust.course.ui.system.SystemStatusBadge
import com.tyust.course.ui.system.SystemTone
import com.tyust.course.ui.system.SystemTopBar
import com.tyust.course.ui.theme.SemanticDanger

@Composable
fun SettingsScreen(
    studentName: String,
    studentId: String,
    schoolName: String,
    currentVersion: String = "1.0.0",
    onSchoolSelect: () -> Unit,
    onCookieConfig: () -> Unit,
    onAccountManage: () -> Unit = {},
    savedAccountCount: Int = 0,
    onClearCache: () -> Unit,
    onLogout: () -> Unit,
    onRefreshCookieClick: () -> Unit = {},
    onLogExport: () -> Unit = {},
    onWallpaperSelect: () -> Unit = {},
    wallpaperName: String = "",
    themeName: String = "跟随系统",
    onThemeSelect: () -> Unit = {},
    startupPageName: String = "课表",
    onStartupPageSelect: () -> Unit = {},
    glassEffectEnabled: Boolean = true,
    onGlassEffectChange: (Boolean) -> Unit = {},
    /** 是否展示第二课堂里班级同学的完整排名。 */
    showClassRank: Boolean = true,
    onShowClassRankChange: (Boolean) -> Unit = {},
    /** 消息中心未读数；> 0 时入口显示红点。 */
    messageUnread: Int = 0,
    isSuper: Boolean = false,
    canRefreshCookie: Boolean = false,
    isRefreshingCookie: Boolean = false,
    /** 用户是否设置过自定义头像；true 时显示图片，否则显示姓名首字。 */
    hasAvatar: Boolean = false,
    /** 头像键值变化时刷新（避免 AsyncImage 缓存复用旧图）。 */
    avatarRefreshKey: Int = 0,
    onAvatarClick: () -> Unit = {},
    academicSystemName: String = "",
    /** 第二课堂登录状态摘要；留空时显示默认引导文案。 */
    secondClassSubtitle: String = "",
    onSecondClassLogin: () -> Unit = {},
    onMessageCenter: () -> Unit = {}
) {
    val scrollState = rememberScrollState()
    // 折叠进度随滚动偏移连续变化（约 96px 行程），全程跟手
    val headerCollapse by remember {
        derivedStateOf { (scrollState.value / 96f).coerceIn(0f, 1f) }
    }
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            Box(Modifier.moduleEntrance(0)) {
            SystemTopBar(
                title = "设置",
                collapseFraction = headerCollapse
            )
            }
        }
    ) { padding ->
        // 内容延伸到玻璃顶栏下方，滚动时从顶栏底下穿过（padding 施加在滚动内容内部）
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(
                    start = PagePadding,
                    end = PagePadding,
                    top = padding.calculateTopPadding() + 8.dp,
                    bottom = com.tyust.course.ui.system.LocalAppOverlayBottomInset.current + 24.dp
                ),
            verticalArrangement = Arrangement.spacedBy(SectionSpacing)
        ) {
            SettingsHeader(
                name = studentName,
                studentId = studentId,
                school = schoolName,
                isSuper = isSuper,
                canRefreshCookie = canRefreshCookie,
                isRefreshingCookie = isRefreshingCookie,
                onRefreshCookieClick = onRefreshCookieClick,
                hasAvatar = hasAvatar,
                avatarRefreshKey = avatarRefreshKey,
                onAvatarClick = onAvatarClick
            )

            InsetGroupedSection(Modifier.moduleEntrance(2), header = "账号与教务") {
                // 学校固定为淮南师范学院、教务支持范围与配额说明不再作为可点条目暴露，
                // 这里只保留真正需要用户操作的入口。
                SettingsRow(
                    icon = Icons.Outlined.ManageAccounts,
                    iconTint = Color(0xFF32ADE6),
                    title = "账号管理",
                    subtitle = if (savedAccountCount > 0) {
                        "已保存 $savedAccountCount 个账号 · 可切换或删除"
                    } else {
                        "切换账号、删除已存密码与账号"
                    },
                    onClick = onAccountManage
                )
                SettingsRow(
                    icon = Icons.Outlined.EmojiEvents,
                    iconTint = Color(0xFFFF9F0A),
                    title = "第二课堂",
                    subtitle = secondClassSubtitle.ifBlank { "用教务学号登录成绩单系统" },
                    onClick = onSecondClassLogin
                )
                InsetGroupedRow(
                    icon = Icons.Outlined.AssignmentInd,
                    iconTint = Color(0xFF30B0C7),
                    title = "显示班级同学排名",
                    subtitle = if (showClassRank) {
                        "第二课堂的「班级」榜单会列出全部同学"
                    } else {
                        "已隐藏同学名单，只保留我的排名"
                    },
                    trailing = {
                        LiquidSwitch(
                            checked = showClassRank,
                            onCheckedChange = onShowClassRankChange
                        )
                    }
                )
                SettingsRow(
                    icon = Icons.Outlined.Email,
                    iconTint = Color(0xFF64D2FF),
                    title = "消息中心",
                    subtitle = if (messageUnread > 0) {
                        "有 $messageUnread 条未读消息"
                    } else {
                        "教务通知与站内消息"
                    },
                    badgeCount = messageUnread,
                    onClick = onMessageCenter
                )
                SettingsRow(
                    icon = Icons.AutoMirrored.Outlined.Login,
                    iconTint = Color(0xFF32ADE6),
                    title = "重新登录",
                    subtitle = "退出当前会话并返回登录页（保留已存密码）",
                    onClick = onCookieConfig,
                    showDivider = false
                )
            }

            InsetGroupedSection(Modifier.moduleEntrance(3), header = "外观") {
                SettingsRow(
                    icon = Icons.Outlined.Palette,
                    iconTint = MaterialTheme.colorScheme.primary,
                    title = "主题",
                    subtitle = themeName,
                    onClick = onThemeSelect
                )
                SettingsRow(
                    icon = Icons.Outlined.Palette,
                    iconTint = Color(0xFFBF5AF2),
                    title = "背景",
                    subtitle = wallpaperName.ifBlank { "选择背景色或图片" },
                    onClick = onWallpaperSelect
                )
                InsetGroupedRow(
                    icon = Icons.Outlined.AutoAwesome,
                    iconTint = Color(0xFF5AC8FA),
                    title = "液态玻璃",
                    subtitle = if (glassEffectEnabled) {
                        "折射、色散与跟手形变"
                    } else {
                        "已关闭，改用不透明材质，更省电也更清晰"
                    },
                    showDivider = false,
                    trailing = {
                        LiquidSwitch(
                            checked = glassEffectEnabled,
                            onCheckedChange = onGlassEffectChange
                        )
                    }
                )
            }

            InsetGroupedSection(Modifier.moduleEntrance(3), header = "应用与支持") {
                SettingsRow(
                    icon = Icons.Outlined.Home,
                    iconTint = Color(0xFF5E5CE6),
                    title = "启动首屏",
                    subtitle = "$startupPageName · 下次启动时显示",
                    onClick = onStartupPageSelect
                )
                SettingsRow(
                    icon = Icons.Outlined.ContentPasteSearch,
                    iconTint = Color(0xFF64D2FF),
                    title = "导出日志",
                    subtitle = "导出本地运行日志",
                    onClick = onLogExport,
                    showDivider = false
                )
            }

            InsetGroupedSection(Modifier.moduleEntrance(3), header = "数据与安全") {
                SettingsRow(
                    icon = Icons.Outlined.Delete,
                    iconTint = Color(0xFFFF9F0A),
                    title = "清除缓存",
                    subtitle = "释放本地存储空间",
                    onClick = onClearCache,
                    showDivider = false
                )
            }

            InsetGroupedSection(Modifier.moduleEntrance(3)) {
                InsetGroupedRow(
                    title = "退出登录",
                    icon = Icons.AutoMirrored.Filled.ExitToApp,
                    iconTint = SemanticDanger,
                    titleColor = SemanticDanger,
                    showDivider = false,
                    onClick = onLogout
                )
            }

            Text(
                text = "教务助手 · $currentVersion",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
        }
    }
}

@Composable
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
private fun SettingsHeader(
    name: String,
    studentId: String,
    school: String,
    isSuper: Boolean,
    canRefreshCookie: Boolean,
    isRefreshingCookie: Boolean,
    onRefreshCookieClick: () -> Unit,
    hasAvatar: Boolean = false,
    avatarRefreshKey: Int = 0,
    onAvatarClick: () -> Unit = {}
) {
    // 裸玻璃容器（不走 Surface，避免 elevation 阴影在半透色下泛白）
    val heroShape = RoundedCornerShape(24.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .moduleEntrance(1)
            .clip(heroShape)
            .background(com.tyust.course.ui.system.glassSurfaceColor())
            .border(0.5.dp, com.tyust.course.ui.system.glassBorderColor(), heroShape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onAvatarClick),
                contentAlignment = Alignment.Center
            ) {
                // 头像解码自带刷新语义（见 UserAvatar 的注释）。原先这里用 Coil 读
                // 固定路径，换完头像后同一路径会命中旧缓存 —— 用户看到的就是
                // "换了头像但没变"。avatarRefreshKey 仍然保留，作为立即重读的信号。
                com.tyust.course.ui.system.UserAvatar(
                    refreshKey = avatarRefreshKey,
                    modifier = Modifier.size(40.dp)
                ) {
                    Text(
                        text = name.take(1).ifBlank { "同" },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    text = name.ifBlank { "同学" },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = school.ifBlank { "未选择学校" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (isSuper) {
                SystemStatusBadge(
                    text = "超级用户",
                    tone = SystemTone.Success
                )
            }
            if (canRefreshCookie) {
                Box(
                    modifier = Modifier.clickable(
                        enabled = !isRefreshingCookie,
                        onClick = onRefreshCookieClick
                    )
                ) {
                    SystemStatusBadge(
                        text = if (isRefreshingCookie) "更新中" else "更新 Cookie",
                        tone = SystemTone.Info
                    )
                }
            }
        }
    }
}

/** 设置行：InsetGroupedRow + 彩色图标 chip + chevron（可选未读红点）。 */
@Composable
private fun SettingsRow(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    subtitle: String? = null,
    showDivider: Boolean = true,
    /** > 0 时在 chevron 左侧显示红点（1 位数字）或红底数字（多位数）。 */
    badgeCount: Int = 0,
    onClick: () -> Unit
) {
    InsetGroupedRow(
        title = title,
        subtitle = subtitle,
        icon = icon,
        iconTint = iconTint,
        showDivider = showDivider,
        onClick = onClick,
        trailing = {
            if (badgeCount > 0) {
                UnreadDot(count = badgeCount)
                Spacer(Modifier.width(8.dp))
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }
    )
}

/**
 * 未读红点。个位数只画点，多位数才铺成胶囊写数字 —— 设置行右端空间很窄，
 * 一律写数字会让「99+」这种把标题挤到换行。
 */
@Composable
private fun UnreadDot(count: Int) {
    val label = if (count > 99) "99+" else count.toString()
    val pill = count > 9
    Box(
        modifier = Modifier
            .height(18.dp)
            .then(if (pill) Modifier.widthIn(min = 18.dp) else Modifier.size(9.dp))
            .background(SemanticDanger, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        if (pill) {
            Text(
                text = label,
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 5.dp)
            )
        }
    }
}
