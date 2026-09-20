package com.tyust.course.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.PlaylistAddCheck
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tyust.course.manager.StartupPage
import com.tyust.course.ui.system.SystemDialog
import com.tyust.course.ui.system.SystemPrimaryButton
import com.tyust.course.ui.theme.NeuPrimary

private data class OnboardingEntry(
    val icon: ImageVector,
    val title: String,
    val description: String,
    val target: StartupPage
)

/**
 * 首次启动引导（液态玻璃弹框）。
 *
 * 它叠加在主界面之上（见 `MainScreen` 的挂载点），跑在【登录之后】。
 * 介绍到哪个页面，点那条就直接跳到哪个页面并结束引导；「开始使用」只是关闭弹框。
 * 玻璃材质由 [SystemDialog] 提供，与「我的」页各弹窗同一套视觉。
 */
@Composable
fun OnboardingDialog(
    onNavigate: (StartupPage) -> Unit,
    onFinish: () -> Unit
) {
    val entries = remember {
        listOf(
            OnboardingEntry(
                icon = Icons.Outlined.CalendarMonth,
                title = "课表与成绩",
                description = "按周查看课表、导出日历；成绩自动算 GPA",
                target = StartupPage.Schedule
            ),
            OnboardingEntry(
                icon = Icons.AutoMirrored.Outlined.PlaylistAddCheck,
                title = "选课",
                description = "队列与定时任务按规则自动尝试，捡漏不落下",
                target = StartupPage.Grab
            ),
            OnboardingEntry(
                icon = Icons.Outlined.EmojiEvents,
                title = "第二课堂",
                description = "学分与各模块完成情况一目了然，排行榜分层查看",
                target = StartupPage.SecondClass
            ),
            OnboardingEntry(
                icon = Icons.Outlined.Shield,
                title = "账号与设置",
                description = "密码仅加密存本机，主题、背景与提醒都在这里",
                target = StartupPage.Settings
            )
        )
    }

    SystemDialog(
        onDismissRequest = onFinish,
        title = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                androidx.compose.foundation.Image(
                    painter = androidx.compose.ui.res.painterResource(com.tyust.course.R.mipmap.ic_launcher),
                    contentDescription = null,
                    modifier = Modifier.size(44.dp)
                )
                Text(
                    text = "欢迎使用教务助理",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = "介绍到哪个页面，点一下就带你去那个页面",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        },
        confirmButton = {
            SystemPrimaryButton(
                text = "开始使用",
                onClick = onFinish,
                modifier = Modifier.fillMaxWidth()
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 420.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            entries.forEach { entry ->
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onNavigate(entry.target) },
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = NeuPrimary.copy(alpha = 0.12f)
                        ) {
                            Icon(
                                imageVector = entry.icon,
                                contentDescription = null,
                                tint = NeuPrimary,
                                modifier = Modifier.padding(8.dp).size(22.dp)
                            )
                        }
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = entry.title,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = entry.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                lineHeight = 17.sp
                            )
                        }
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "以后可以随时在「我的」→ 用户手册 里回顾全部功能。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 18.sp
            )
        }
    }
}
