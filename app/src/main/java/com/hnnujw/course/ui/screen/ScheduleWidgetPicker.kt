package com.hnnujw.course.ui.screen

import android.widget.FrameLayout
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.hnnujw.course.schedule.ScheduleCourseRecord
import com.hnnujw.course.schedule.ScheduleDates
import com.hnnujw.course.schedule.ScheduleSnapshot
import com.hnnujw.course.schedule.ScheduleTimeBase
import com.hnnujw.course.schedule.ScheduleWidgetRenderer
import com.hnnujw.course.schedule.ScheduleWidgetState
import com.hnnujw.course.schedule.ScheduleWidgetStyle
import com.hnnujw.course.schedule.ScheduleWidgetUpdater
import com.hnnujw.course.ui.system.SystemDialog
import com.hnnujw.course.ui.system.SystemPrimaryButton
import java.util.Calendar

/**
 * 「桌面组件」选择弹窗：三种样式的实时预览（示例课程）+ 各自的添加按钮。
 * 预览用真实渲染器画出 RemoteViews，所见即所得；Android 8+ 直接拉起
 * 系统的「添加到桌面」确认，低版本提示去桌面长按添加。
 */
@Composable
fun ScheduleWidgetPicker(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val preview = remember {
        val monday = ScheduleDates.mondayOfWeek(System.currentTimeMillis())
        val now = (monday.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 8); set(Calendar.MINUTE, 10)
        }.timeInMillis
        val base = ScheduleTimeBase(
            ScheduleTimeBase.dateFromMillis(monday.timeInMillis),
            mapOf(1 to "08:00", 2 to "10:00", 3 to "14:00"),
            mapOf(1 to "09:40", 2 to "11:40", 3 to "15:40"))
        val courses = listOf(
            ScheduleCourseRecord("sample-1", "高等数学", "", "博学楼 A205", 1, 1, 1, "1-16周"),
            ScheduleCourseRecord("sample-2", "计算机网络", "", "明理楼 B302", 1, 2, 2, "1-16周"),
            ScheduleCourseRecord("sample-3", "大学英语", "", "博学楼 A102", 1, 3, 3, "1-16周"))
        ScheduleWidgetState.from(ScheduleSnapshot("", "", "", courses, base, now, true), now)
    }
    SystemDialog(onDismissRequest = onDismiss, title = { Text("选择桌面组件") }) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 520.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(14.dp)
        ) {
            Text(
                "样式预览 · 示例课程。添加后显示当前账号的本地课表，随浅深色切换。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            for (style in ScheduleWidgetStyle.entries) {
                Text(style.title, style = MaterialTheme.typography.titleMedium)
                Text(style.description, style = MaterialTheme.typography.bodySmall)
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val width = maxWidth.value.toInt()
                    val height = if (style == ScheduleWidgetStyle.Timeline) 240 else 150
                    AndroidView(
                        factory = { ctx -> FrameLayout(ctx) },
                        modifier = Modifier.fillMaxWidth().height(height.dp),
                        update = { host ->
                            host.removeAllViews()
                            host.addView(
                                ScheduleWidgetRenderer.views(host.context, preview, width, height, style)
                                    .apply(host.context, host)
                            )
                            // 预览卡不该响应点击；只有显式的「添加」按钮生效。
                            fun disable(view: android.view.View) {
                                view.isClickable = false
                                if (view is android.view.ViewGroup) {
                                    for (i in 0 until view.childCount) disable(view.getChildAt(i))
                                }
                            }
                            disable(host)
                        })
                }
                SystemPrimaryButton(
                    text = "添加${style.title}",
                    onClick = { ScheduleWidgetUpdater.requestPin(context, style); onDismiss() },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}
