package com.hnnujw.course.widgetboard

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.os.Bundle
import com.hnnujw.course.R

/**
 * 七种桌面卡片组件。
 *
 * 与 App 内组件抽屉（[InAppWidgetRegistry]）**一一对应**：同一种卡片，既能摆在
 * 组件工作台里，也能从工作台「添加到桌面」变成系统组件。两边用同一个 `cardId`
 * 串起来，所以这里多一条卡片、抽屉里就多一条；删掉一条，工作台里的"添加到桌面"
 * 也会跟着消失（[of] 找不到就不显示按钮）。
 *
 * `infoRes` / `labelRes` 指向 `res/xml/card_widget_*_info.xml` 与
 * `values/card_widget_strings.xml` —— 长按桌面时选择器里显示的就是它俩。
 */
enum class CardWidget(
    val cardId: String,
    val infoRes: Int,
    val labelRes: Int,
    val provider: Class<out AppWidgetProvider>,
) {
    NextCourse("schedule.next", R.xml.card_widget_next_info,
        R.string.card_widget_next_name, NextCourseCardWidget::class.java),
    TodaySchedule("schedule.today", R.xml.card_widget_today_info,
        R.string.card_widget_today_name, TodayScheduleCardWidget::class.java),
    TodayTimeline("schedule.timeline", R.xml.card_widget_timeline_info,
        R.string.card_widget_timeline_name, TodayTimelineCardWidget::class.java),
    ExamCountdown("exam.countdown", R.xml.card_widget_exam_info,
        R.string.card_widget_exam_name, ExamCountdownCardWidget::class.java),
    GradesOverview("grades.overview", R.xml.card_widget_grades_info,
        R.string.card_widget_grades_name, GradesOverviewCardWidget::class.java),
    SecondClassOverview("secondclass.overview", R.xml.card_widget_secondclass_info,
        R.string.card_widget_secondclass_name, SecondClassOverviewCardWidget::class.java),
    MessageUnread("message.unread", R.xml.card_widget_message_info,
        R.string.card_widget_message_name, MessageUnreadCardWidget::class.java);

    companion object {
        fun of(cardId: String?): CardWidget? = entries.firstOrNull { it.cardId == cardId }
    }
}

/**
 * 七种卡片共用的 [AppWidgetProvider]。
 *
 * 子类**必须无参**：系统按清单里的类名反射实例化，带参数的构造器会直接崩在加组件那一步。
 * 卡片身份不放在 Provider 上 —— [CardWidgetUpdater.update] 是按组件名去问系统
 * "这一种卡片现在有几个实例"，再逐个重画的，不需要实例自己说清是哪一种。
 *
 * 系统广播（开机 / 改时间等）不在这里接，见 [CardWidgetRefreshReceiver]；边界闹钟也指向
 * 那个 receiver。所以这个类只剩"系统要求组件重画"这一件事。
 */
abstract class CardWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) =
        CardWidgetUpdater.update(context)

    /** 用户拖动改尺寸：块数变了，能放下几行也就变了，必须按新尺寸重画。 */
    override fun onAppWidgetOptionsChanged(
        context: Context, manager: AppWidgetManager, id: Int, options: Bundle,
    ) = CardWidgetUpdater.update(context)

    override fun onDeleted(context: Context, ids: IntArray) = CardWidgetUpdater.update(context)
    override fun onDisabled(context: Context) = CardWidgetUpdater.update(context)
}

class NextCourseCardWidget : CardWidgetProvider()
class TodayScheduleCardWidget : CardWidgetProvider()
class TodayTimelineCardWidget : CardWidgetProvider()
class ExamCountdownCardWidget : CardWidgetProvider()
class GradesOverviewCardWidget : CardWidgetProvider()
class SecondClassOverviewCardWidget : CardWidgetProvider()
class MessageUnreadCardWidget : CardWidgetProvider()
