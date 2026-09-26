package com.hnnujw.course.widgetboard

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.hnnujw.course.manager.UserManager
import com.hnnujw.course.ui.system.GlassToaster
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 桌面卡片的刷新与"添加到桌面"。
 *
 * 刷新只有三个触发源，都**不需要网络、不需要登录态**：
 *  1. 缓存变化：课表/成绩/二课积分等 prefs 被写入 → 去抖 100ms 后重画；
 *  2. 会话变化：登录、退出、切账号 → 立刻重画（否则卡片会显示上一个账号的成绩）；
 *  3. 边界闹钟：下一节课开始/结束时叫一次（不精确、免唤醒），把"正在上课/下一节"翻过来。
 * 另外系统在开机、应用更新、改时间时区日期后会发广播，由 [CardWidgetRefreshReceiver] 接手。
 *
 * 这里没有"每分钟轮询"：课表状态只在节次边界变，精确到分钟的刷新纯属白耗电。
 */
object CardWidgetUpdater {

    private const val TAG = "CardWidgetUpdater"

    const val ACTION_REFRESH = "com.hnnujw.course.action.REFRESH_CARD_WIDGET"

    /** 系统把组件钉上桌面后的回调，见 [pinCallback]。 */
    const val ACTION_PINNED = "com.hnnujw.course.action.CARD_WIDGET_PINNED"

    /**
     * 会让桌面卡片失真的缓存。少一个的后果很具体：
     * 少了 `academic_grades_cache`，出成绩之后桌面上的成绩卡一直停在旧数字；
     * 少了 `appearance_settings`，切深浅色后卡片底色和文字颜色会对不上。
     */
    private val OBSERVED_PREFS = listOf(
        "schedule_cache", "schedule_settings", "course_reminders", "course_selector_prefs",
        "appearance_settings", "academic_grades_cache", "second_class_overview",
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    private var started = false
    private var appContext: Context? = null
    private val observed = mutableListOf<Pair<SharedPreferences, SharedPreferences.OnSharedPreferenceChangeListener>>()

    /** 去抖后的重画：一次同步会连着改好几个 key，去抖之后只画一次。 */
    private val debouncedRefresh = Runnable { appContext?.let { update(it) } }

    /** 常驻监听。只应在进程启动时调一次（[com.hnnujw.course.CourseApplication]）。 */
    fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        appContext = app
        for (name in OBSERVED_PREFS) {
            val prefs = app.getSharedPreferences(name, Context.MODE_PRIVATE)
            val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> refreshSoon(app) }
            prefs.registerOnSharedPreferenceChangeListener(listener)
            observed += prefs to listener
        }
        scope.launch { UserManager.getInstance().sessionState.state.collect { update(app) } }
    }

    /**
     * 去抖之后重画一次。
     *
     * prefs 监听走这里；**不用 SharedPreferences 的缓存也走这里** —— 消息中心的列表
     * 存在 `cacheDir` 的 JSON 文件里（见 `MessageCenterManager.cacheFile`），
     * 而 [OBSERVED_PREFS] 只能看见 prefs。少了这个入口，桌面上的「未读消息」卡就只剩
     * 系统按 `updatePeriodMillis` 给的 30 分钟兜底周期，收到新消息后半小时才变。
     * 所以 `MessageCenterManager.writeCache` 写完文件会显式喊它一声。
     */
    fun refreshSoon(context: Context) {
        appContext = context.applicationContext
        handler.removeCallbacks(debouncedRefresh)
        handler.postDelayed(debouncedRefresh, 100)
    }

    /** 重画所有已放上桌面的卡片。没有任何实例时会顺手把边界闹钟撤掉。 */
    fun update(context: Context) {
        val app = context.applicationContext
        val manager = AppWidgetManager.getInstance(app)
        val live = CardWidget.entries.mapNotNull { widget ->
            val ids = manager.getAppWidgetIds(ComponentName(app, widget.provider))
            if (ids.isEmpty()) null else widget to ids
        }
        if (live.isEmpty()) {
            cancel(app)
            return
        }
        val now = System.currentTimeMillis()
        // 一次装配喂给所有卡片：同一次刷新里，课表卡说"正在上课"时，
        // 时间轴卡不会把它标成"已结束"。
        val snapshot = CardWidgetDataLoader.snapshot(app, now)
        for ((widget, ids) in live) {
            for (id in ids) {
                // 必须把整份 options 交给渲染层：只看 MIN 宽高等于"无论用户拖多大都只画紧凑版"，
                // 大尺寸上该出现的教室、更多行、汇总全都没有。见 CardWidgetRenderer.responsiveViews。
                manager.updateAppWidget(
                    id,
                    CardWidgetRenderer.responsiveViews(app, widget.cardId, snapshot.data, manager.getAppWidgetOptions(id)),
                )
            }
        }
        cancel(app)
        snapshot.nextChangeAt?.let { next ->
            app.getSystemService(AlarmManager::class.java)
                .set(AlarmManager.RTC, next.coerceAtLeast(now + 1_000), alarm(app))
        }
    }

    /**
     * 「添加到桌面」。
     *
     * Android 8 起由系统弹确认框，**确认之后组件才真正落到桌面上**，所以这里只负责
     * 把请求交出去，成败由用户在系统框里的选择决定。这也是要传 [pinCallback] 的原因：
     * 确认那一刻立刻重画一次，刚放上去的卡片就不会先是空的。
     *
     * ⚠️ ColorOS 上这条通道是"发得出去、落不下来"：请求确实送到了系统，确认页也拉得起来，
     * 但实测它 `resumed` 55ms 后就被自行关掉（`AddItemActivity` 直接 finish，无异常日志），
     * 卡片始终进不了桌面。所以这里**不能**只提示"在弹出的提示里点添加" —— 那个框在 ColorOS
     * 上根本来不及点。能不能加成功，用户手上只有桌面自己那条路径：
     * 长按桌面 →「卡片」→ 卡片中心 → 一直滑到最底部 →「插件」→ 选本应用。
     */
    fun requestPin(context: Context, cardId: String) {
        val widget = CardWidget.of(cardId) ?: return
        val app = context.applicationContext
        val manager = AppWidgetManager.getInstance(app)
        val supported = Build.VERSION.SDK_INT >= 26 && manager.isRequestPinAppWidgetSupported
        if (supported) {
            val ok = try {
                manager.requestPinAppWidget(
                    ComponentName(app, widget.provider), null, pinCallback(app),
                )
            } catch (t: Throwable) {
                Log.e(TAG, "requestPin $cardId 抛异常", t)
                false
            }
            // 用 error 级而不是 debug：release 的 proguard-android-optimize 会把 Log.d 整条删掉，
            // 而这条恰恰是国产 ROM 上唯一能事后还原现场的东西 —— 例如 ColorOS 会返回 true，
            // 却把随后的系统确认页在几十毫秒内关掉（实测 resumed 55ms 即被 finish，
            // 约 335ms 后整个 AddItemActivity 被移除），只有这行日志能证明"请求确实发出去了"。
            Log.e(TAG, "requestPin $cardId supported=$supported -> $ok")
            // 提示语里带上手工路径的兜底：ColorOS 上确认框会秒退，只报"点添加"等于没说。
            GlassToaster.show("若没弹出确认框，请长按桌面 →「卡片」→ 卡片中心 → 滑到最底部 →「插件」")
        } else {
            Log.e(TAG, "requestPin $cardId: 桌面不支持一键添加")
            GlassToaster.show("当前桌面不支持一键添加，请长按桌面 →「卡片」→ 卡片中心 → 滑到最底部 →「插件」")
        }
    }

    /**
     * 系统把组件钉上桌面后的回调。
     *
     * 只用来"立刻重画一次"，不承担交互反馈：ColorOS 上它可能压根不回调，把提示挂在
     * 这里会变成"有时有、有时没有"。
     */
    private fun pinCallback(context: Context) = PendingIntent.getBroadcast(
        context, 0,
        Intent(context, CardWidgetRefreshReceiver::class.java).setAction(ACTION_PINNED),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** 这张卡片现在桌面上有没有。工作台用它把按钮显示成「添加到桌面 / 已在桌面」。 */
    fun isOnHomeScreen(context: Context, cardId: String): Boolean {
        val widget = CardWidget.of(cardId) ?: return false
        val app = context.applicationContext
        return AppWidgetManager.getInstance(app)
            .getAppWidgetIds(ComponentName(app, widget.provider)).isNotEmpty()
    }

    private fun alarm(context: Context) = PendingIntent.getBroadcast(
        context, 0,
        Intent(context, CardWidgetRefreshReceiver::class.java).setAction(ACTION_REFRESH),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun cancel(context: Context) {
        context.getSystemService(AlarmManager::class.java).cancel(alarm(context))
    }
}
