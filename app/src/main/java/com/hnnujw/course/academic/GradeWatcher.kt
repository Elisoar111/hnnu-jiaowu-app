package com.hnnujw.course.academic

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.hnnujw.course.MainActivity
import com.hnnujw.course.R
import com.hnnujw.course.manager.GradesCacheManager
import com.hnnujw.course.manager.UserManager
import com.hnnujw.course.model.SchoolConfig

/**
 * 成绩订阅：**定期拉取成绩，与本地基线做 diff，有新成绩/分数变化就发系统通知**。
 *
 * 巡检模式与 [MessageCenterNotifier] 同构，且**共用同一颗 12 小时闹钟**
 * （[MessageCheckReceiver] 收到广播后两个巡检一起跑），不新增闹钟。
 *
 * ## diff 判据
 *
 * 成绩行没有稳定主键，参照成绩页 `gradeRowKeys` 的思路，用
 * `term + courseCode + name + sectionId + type`（长度前缀拼接）构造身份，
 * 加上出现序号区分重修等重复行。身份没见过 → 新出成绩；
 * 身份见过但分数变了 → 成绩更新。两者都播报。
 *
 * ## 基线
 *
 * 首次巡检只建基线不播报，否则装上 App 就会被全部历史成绩炸一遍。
 * 基线按账号隔离（与消息巡检同一理由：不需要任何清理动作就不会串号）。
 *
 * ## 定位
 *
 * 闹钟是 `setInexactRepeating`，Doze 下会被推迟——定位是"几小时内知道"，
 * 不是实时推送。会话过期时拉取失败，静默等下一轮，不打扰用户。
 */
object GradeWatcher {

    const val CHANNEL = "grade_updates"

    private const val PREFS = "grade_notify"
    private const val MIN_INTERVAL_MS = 12 * 60 * 60 * 1000L

    /** 通知固定 id：后一次替换前一次，不在通知栏堆一列。 */
    private const val NOTIFICATION_ID = 0x4752

    private fun baselineKey(accountKey: String) = "baseline::$accountKey"
    private fun lastCheckKey(accountKey: String) = "last_check_ms::$accountKey"

    /**
     * 巡检一次。成功时把最新成绩写回 [GradesCacheManager]（与 UI 同一条缓存，
     * 成绩页打开时直接命中最新数据）。
     *
     * @return 是否播报了通知（仅供日志/测试，调用方不需要据此做逻辑）。
     */
    suspend fun check(
        context: Context,
        school: SchoolConfig?,
        accountKey: String,
        force: Boolean = false,
    ): Boolean {
        val user = UserManager.getInstance()
        if (school == null || accountKey.isBlank() || user.isDemoMode || !user.isLoggedIn) return false

        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (!force && now - prefs.getLong(lastCheckKey(accountKey), 0L) < MIN_INTERVAL_MS) return false

        val report = try {
            AcademicStudyBridge.reader(school, accountKey, user.sessionState.token).grades()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Throwable) {
            // 会话过期 / 网络失败：静默等下一轮。时间戳照常推进，
            // 失败立刻重试只会把教务系统打爆。
            prefs.edit().putLong(lastCheckKey(accountKey), now).apply()
            return false
        }
        prefs.edit().putLong(lastCheckKey(accountKey), now).apply()

        val grades = report.grades
        // 🔴 身份不再依赖列表下标：教务列表是插入式的，下标一变后面全部误判为新成绩。
        // 详见 GradeIdentity 的说明。身份与成绩等长且一一对应。
        val identities = GradeIdentity.keys(grades)
        val entries = grades.indices.map { identities[it] to grades[it].score }
        val baseline = prefs.getStringSet(baselineKey(accountKey), emptySet()).orEmpty()
        val hasBaseline = prefs.contains(baselineKey(accountKey))
        val fresh = grades.indices.map { grades[it] to "${identities[it]}|${grades[it].score}" }
            .filter { it.second !in baseline }.map { it.first }

        // 无论是否播报，都把最新成绩写回缓存——成绩页打开时直接渲染最新数据
        GradesCacheManager.saveReport(context, accountKey, report)

        if (hasBaseline && fresh.isNotEmpty()) {
            // ⚠️ 只有**真的送达**才推进基线。
            //
            // 原先是「先 postNotification 再无条件写基线」：`postNotification` 在通知被关闭
            // 时会在 `canNotify` 处静默返回，而基线照样被写成"这些成绩已经播报过"。
            // 后果是用户重新打开通知后**再也收不到这批成绩**——与考前提倡的
            // 「先确认送达、再消费计划」是同一条不变量，这里此前漏了。
            if (!postNotification(context, fresh)) return false
        }
        prefs.edit().putStringSet(baselineKey(accountKey), entries.map { (k, v) -> "$k|$v" }.toSet()).apply()
        return hasBaseline && fresh.isNotEmpty()
    }

    /** 用户已经亲眼看过成绩页时调用：用当前缓存重建基线，不播报。 */
    fun markSeen(context: Context, accountKey: String) {
        if (accountKey.isBlank()) return
        val cached = GradesCacheManager.load(context, accountKey) ?: return
        val grades = cached.report.grades
        val identities = GradeIdentity.keys(grades)
        val entries = grades.indices.map { "${identities[it]}|${grades[it].score}" }.toSet()
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(baselineKey(accountKey), entries)
            .putLong(lastCheckKey(accountKey), System.currentTimeMillis())
            .apply()
    }

    /** 退出登录 / 切换账号时清掉该账号的巡检状态。 */
    fun reset(context: Context, accountKey: String) {
        if (accountKey.isBlank()) return
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(baselineKey(accountKey))
            .remove(lastCheckKey(accountKey))
            .apply()
    }

    /**
     * 成绩行身份已迁到 [GradeIdentity]（不再依赖列表下标，见那里的说明）。
     *
     * 这里保留一个转发，是因为 `GradeWatcher` 内部还有几处按单行取身份的地方，
     * 而把「批量算 + 组内序号」的口径留在一处才不会再次走向下标的坑。
     */
    private fun gradeIdentity(grade: AcademicGrade): String = GradeIdentity.keyOf(grade)

    /**
     * 发通知，返回**是否真的发出去了**。
     *
     * 返回值必须被调用方用来决定要不要推进基线：通知被关闭 / 渠道被禁用时这里会
     * 提前返回 false，此时若仍把成绩记成"已播报"，用户之后打开通知也补不回来。
     */
    private fun postNotification(context: Context, fresh: List<AcademicGrade>): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        if (Build.VERSION.SDK_INT >= 26 && manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "成绩推送", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "教务系统发布新成绩或成绩变化时提醒"
                }
            )
        }
        if (!canNotify(context)) return false

        val open = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_OPEN_TAB, com.hnnujw.course.manager.StartupPage.Grades.route)
        }
        val content = PendingIntent.getActivity(
            context, 0x4752, open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val first = fresh.first()
        val headline = "《${first.name}》${first.score} 分"
        val text = if (fresh.size == 1) headline else "$headline 等 ${fresh.size} 门课程"
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_course_reminder)
            .setContentTitle(if (fresh.size == 1) "新成绩发布" else "新成绩发布 · ${fresh.size} 门")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(content)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        return try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
            true
        } catch (_: SecurityException) {
            // 通知权限在发的一瞬间被撤销：当作没送达，基线不推进，下一轮会再试。
            false
        }
    }

    private fun canNotify(context: Context): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT < 33) return true
        return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }
}
