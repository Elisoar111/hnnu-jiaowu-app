package com.tyust.course.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.tyust.course.MainActivity
import com.tyust.course.R

/**
 * 课表小组件的公共骨架。
 *
 * 为什么用 RemoteViews 而不是 Glance：Glance 需要额外引入 androidx.glance 依赖
 * 并整体升级到它的组合树，而这两个小组件的内容用 RemoteViews 的几个 TextView
 * 就能完整表达——不加依赖、不增加 dex 体积、也不引入新的兼容层。
 *
 * 更新时机（三层保障，数据全部来自本地缓存，刷新无网络开销）：
 * 1. `updatePeriodMillis`：系统每 30 分钟兜底刷一次（覆盖跨天、周次变化）；
 * 2. App 侧数据变化后主动调 [WidgetRefresher.updateAll]（课表加载/自定义课程变更）；
 * 3. 开机 / 应用更新 / 改时间 / 改时区广播（这些事件会把周期更新计时清掉）。
 */
abstract class BaseScheduleWidgetProvider() : AppWidgetProvider() {

    /** 各小组件自己把 Snapshot 渲染成 RemoteViews。 */
    abstract fun render(context: Context, snapshot: WidgetSnapshotBuilder.Snapshot?): RemoteViews

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        WidgetRefresher.updateAll(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        // BOOT_COMPLETED / MY_PACKAGE_REPLACED / TIME_SET / TIMEZONE_CHANGED：
        // 这些广播会把系统兜底的周期更新计时重置，收到就立刻刷一遍。
        if (intent.action !in setOf(AppWidgetManager.ACTION_APPWIDGET_UPDATE,
                AppWidgetManager.ACTION_APPWIDGET_OPTIONS_CHANGED, AppWidgetManager.ACTION_APPWIDGET_DELETED)) {
            WidgetRefresher.updateAll(context)
        }
    }
}

/** 4x1「下一节课」小组件。 */
class NextCourseWidgetProvider : BaseScheduleWidgetProvider() {
    override fun render(context: Context, snapshot: WidgetSnapshotBuilder.Snapshot?): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_next_course)
        val next = snapshot?.takeIf { it.notice.isEmpty() }?.next
        if (next == null) {
            views.setTextViewText(R.id.widget_next_label, "下一节课")
            views.setTextViewText(R.id.widget_next_name, snapshot?.notice?.ifBlank { "暂无课程安排" } ?: "暂无课程安排")
            views.setTextViewText(R.id.widget_next_time, "")
            views.setTextViewText(R.id.widget_next_room, "")
            return views
        }
        views.setTextViewText(R.id.widget_next_label, "下一节课 · ${next.dayLabel}")
        views.setTextViewText(R.id.widget_next_name, next.name)
        views.setTextViewText(R.id.widget_next_time, listOf("第 ${next.startPeriod}-${next.endPeriod} 节", next.startTime)
            .filter { it.isNotBlank() }.joinToString(" · "))
        views.setTextViewText(R.id.widget_next_room, next.location.ifBlank { "教室待定" })
        return views
    }
}

/** 2x2「今日课程」小组件。 */
class TodayCoursesWidgetProvider : BaseScheduleWidgetProvider() {
    override fun render(context: Context, snapshot: WidgetSnapshotBuilder.Snapshot?): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_today_courses)
        val snap = snapshot
        if (snap == null || snap.notice.isNotEmpty()) {
            views.setTextViewText(R.id.widget_today_title, "今日课程")
            views.setTextViewText(R.id.widget_today_date, snap?.notice?.ifBlank { "暂无数据" } ?: "暂无数据")
            views.removeAllViews(R.id.widget_today_list)
            return views
        }
        views.setTextViewText(R.id.widget_today_title,
            if (snap.week != null) "今日课程 · 第${snap.week}周" else "今日课程")
        views.setTextViewText(R.id.widget_today_date, snap.dateLabel)

        views.removeAllViews(R.id.widget_today_list)
        val courses = snap.todayCourses
        if (courses.isEmpty()) {
            addRow(context, views, "", "今天没有课，好好休息", "")
            return views
        }
        // 2x2 尺寸放得下 4 行；桌面放大后依然只渲染 4 行（列表滚动留给 App 内）。
        val visible = courses.take(4)
        visible.forEach { course ->
            addRow(context, views,
                listOf(course.startTime, "第${course.startPeriod}节").filter { it.isNotBlank() }.joinToString("\n"),
                course.name,
                course.location)
        }
        if (courses.size > visible.size) {
            addRow(context, views, "", "还有 ${courses.size - visible.size} 节课", "")
        }
        return views
    }

    private fun addRow(context: Context, views: RemoteViews, time: String, name: String, room: String) {
        val row = RemoteViews(context.packageName, R.layout.widget_course_row)
        row.setTextViewText(R.id.widget_row_time, time)
        row.setTextViewText(R.id.widget_row_name, name)
        row.setTextViewText(R.id.widget_row_room, room)
        views.addView(R.id.widget_today_list, row)
    }
}

/** 刷新小组件的唯一入口：构建一次快照，同时推给两种尺寸。 */
object WidgetRefresher {

    /** 小组件点击动作：跳转 MainActivity 并落到课表页。 */
    fun tapIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_OPEN_TAB, com.tyust.course.manager.StartupPage.Schedule.route)
        }
        return PendingIntent.getActivity(context, 0x5157, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun updateAll(context: Context) {
        val app = context.applicationContext
        val manager = AppWidgetManager.getInstance(app) ?: return
        val snapshot = runCatching { WidgetSnapshotBuilder.build(app) }.getOrNull()
        listOf(
            ComponentName(app, NextCourseWidgetProvider::class.java),
            ComponentName(app, TodayCoursesWidgetProvider::class.java)
        ).forEach { component ->
            val provider = when (component.className) {
                NextCourseWidgetProvider::class.java.name -> NextCourseWidgetProvider()
                else -> TodayCoursesWidgetProvider()
            }
            val views = runCatching { provider.render(app, snapshot) }.getOrNull() ?: return@forEach
            val ids = manager.getAppWidgetIds(component)
            ids.forEach { id -> runCatching { manager.updateAppWidget(id, views) } }
        }
    }
}
