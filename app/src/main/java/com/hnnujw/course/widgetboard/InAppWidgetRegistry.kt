package com.hnnujw.course.widgetboard

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.EventAvailable
import androidx.compose.material.icons.outlined.Mail
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Stars
import androidx.compose.material.icons.outlined.Timeline
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * 一种可以摆到工作台上的卡片。
 *
 * @param sizes 支持的尺寸档位，「+」抽屉与卡片的尺寸菜单都只给这几档 ——
 *        不是所有卡片都值得做大：倒计时做成一格小卡反而更清楚。
 * @param defaultSize 从抽屉里加进来时用的尺寸。
 */
data class InAppWidgetSpec(
    val id: String,
    val title: String,
    val description: String,
    val icon: ImageVector,
    val sizes: List<WidgetSize>,
    val defaultSize: WidgetSize,
)

/**
 * 全部可用的 App 内组件。
 *
 * 每一条都对应 [com.hnnujw.course.ui.screen.WidgetBoardCards] 里的一个渲染分支：
 * 这里只描述"是什么"，具体画法由渲染层按 `sourceId` 分派。新增一种卡片 =
 * 在这里登记一条 + 在渲染层加一个分支，布局存储、抽屉、尺寸菜单都会自动认它。
 */
object InAppWidgetRegistry {

    val all: List<InAppWidgetSpec> = listOf(
        InAppWidgetSpec(
            id = "schedule.next",
            title = "下一节课",
            description = "只突出当前或接下来的一节，小尺寸也看得清",
            icon = Icons.Outlined.Schedule,
            sizes = listOf(WidgetSize.Small, WidgetSize.Wide),
            defaultSize = WidgetSize.Small,
        ),
        InAppWidgetSpec(
            id = "schedule.today",
            title = "今日课表",
            description = "正在上的那节 + 紧接着的下一节，附今日课程数",
            icon = Icons.Outlined.DateRange,
            sizes = listOf(WidgetSize.Small, WidgetSize.Wide, WidgetSize.Medium, WidgetSize.Large),
            defaultSize = WidgetSize.Medium,
        ),
        InAppWidgetSpec(
            id = "schedule.timeline",
            title = "今日时间轴",
            description = "今天全部课程按时间排开，进行中的那节高亮",
            icon = Icons.Outlined.Timeline,
            sizes = listOf(WidgetSize.Medium, WidgetSize.Large),
            defaultSize = WidgetSize.Large,
        ),
        InAppWidgetSpec(
            id = "exam.countdown",
            title = "考试倒计时",
            description = "最近一场考试的天数、时间与考场座位",
            icon = Icons.Outlined.EventAvailable,
            sizes = listOf(WidgetSize.Small, WidgetSize.Wide, WidgetSize.Medium),
            defaultSize = WidgetSize.Wide,
        ),
        InAppWidgetSpec(
            id = "grades.overview",
            title = "成绩概览",
            description = "平均绩点、已修学分与已出成绩门数",
            icon = Icons.Outlined.School,
            sizes = listOf(WidgetSize.Small, WidgetSize.Wide, WidgetSize.Medium),
            defaultSize = WidgetSize.Small,
        ),
        InAppWidgetSpec(
            id = "secondclass.overview",
            title = "第二课堂积分",
            description = "各模块积分进度与全校参考值对比",
            icon = Icons.Outlined.Stars,
            sizes = listOf(WidgetSize.Small, WidgetSize.Wide, WidgetSize.Medium),
            defaultSize = WidgetSize.Small,
        ),
        InAppWidgetSpec(
            id = "message.unread",
            title = "未读消息",
            description = "教务消息中心未读条数与最近一条",
            icon = Icons.Outlined.Mail,
            sizes = listOf(WidgetSize.Small, WidgetSize.Wide),
            defaultSize = WidgetSize.Small,
        ),
    )

    fun find(id: String?): InAppWidgetSpec? = all.firstOrNull { it.id == id }
}
