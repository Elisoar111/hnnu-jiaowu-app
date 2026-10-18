package com.hnnujw.course.academic

import android.content.Context
import com.hnnujw.course.manager.GradesCacheManager
import com.hnnujw.course.manager.UserManager
import com.hnnujw.course.model.SchoolConfig
import com.hnnujw.course.schedule.ExamReminderScheduler
import com.hnnujw.course.schedule.ScheduleCacheStore

/**
 * 考试列表的后台拉取。
 *
 * ## 为什么需要它
 *
 * 考前提醒此前**只在用户打开过「成绩 → 考试」页之后才会排**：`ExamReminderScheduler.reconcile`
 * 的调用点只有考试 Tab 加载和开机广播（而开机广播读的是本地缓存）。于是从不点开那个 Tab
 * 的用户，本地永远没有考试数据，考前提醒也就永远不会出现 —— 功能"存在但静默不生效"。
 *
 * 这里把它挂到已有的 12 小时消息巡检上（[MessageCheckReceiver]），不新增闹钟：
 * 巡检顺带拉一次考试列表，落缓存并重排提醒。
 *
 * ## 预算与顺序
 *
 * 广播的 `goAsync` 预算约 10 秒，消息巡检本身可能就要吃掉大半（2 桶 × 最多 10 页）。
 * 所以：
 *  - 本模块在接收器里**排在最前面**，并带 2.5 秒上限 —— 考试列表通常只有一页，
 *    正常 1 秒内返回；排在消息巡检之后会被饿死，等于又变成"静默不生效"。
 *  - 失败/超时都不抛给调用方（[check] 返回 false），后续步骤照常进行。
 *
 * ## 下限与节流
 *
 * 6 小时内不重复拉取（除非 `force`）。消息巡检是 12 小时一次，节流取一半是为了
 * 抵消"闹钟抖动导致 11h59m 时被判为窗口内而整轮跳过"的漂移，同时又不会把学校
 * 的考试接口打成每 12 小时两次以上。
 *
 * ## 与 UI 的关系
 *
 * 写入的是 [GradesCacheManager] 的同一份考试缓存，成绩页打开时直接命中；
 * 页面自己判 `isStale` 时才刷新，不会和这里打架。
 */
object ExamWatcher {

    private const val PREFS = "exam_watch"
    private const val MIN_INTERVAL_MS = 6 * 60 * 60 * 1000L

    private fun lastCheckKey(accountKey: String) = "last_check_ms::$accountKey"

    /**
     * 是否该真的发请求。抽成纯函数是为了钉住两个都"错了也不报错"的边界：
     *  - `force` 必须压过节流（用户在页面上主动刷新时不能还被拦）；
     *  - 从未拉取过（last = 0）必须放行，否则新装用户永远等不到第一次。
     *
     * 时间倒流（系统改时间后 last 比 now 还大）按"已过期"处理：
     * 宁可多拉一次，也不要因为一个未来时间戳把考试提醒永久冻住。
     */
    internal fun shouldFetch(
        lastCheckAtMillis: Long,
        nowMillis: Long,
        force: Boolean,
        intervalMs: Long = MIN_INTERVAL_MS,
    ): Boolean {
        if (force) return true
        if (lastCheckAtMillis <= 0L) return true
        if (nowMillis < lastCheckAtMillis) return true
        return nowMillis - lastCheckAtMillis >= intervalMs
    }

    /**
     * 拉一次考试列表并重排考前提醒。
     *
     * @return 是否真的发起了网络请求并成功写入缓存（仅供日志/测试，调用方不需要据此分支）
     */
    suspend fun check(
        context: Context,
        school: SchoolConfig?,
        accountKey: String,
        force: Boolean = false,
    ): Boolean {
        val app = context.applicationContext
        val user = UserManager.getInstance()
        if (school == null || accountKey.isBlank() || user.isDemoMode || !user.isLoggedIn) return false

        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val last = prefs.getLong(lastCheckKey(accountKey), 0L)
        if (!shouldFetch(last, now, force)) {
            // 节流命中：不拉网络，但仍然按本地缓存重排一次提醒。
            // 开机 / 应用更新后闹钟会被系统清空，重排这件事不能因为节流而漏掉。
            reconcileFromCache(app, accountKey)
            return false
        }

        // 时间戳先推进：失败也等下一轮，立刻重试只会把教务系统打爆。
        prefs.edit().putLong(lastCheckKey(accountKey), now).apply()

        val term = runCatching {
            ScheduleCacheStore(app.getSharedPreferences("schedule_cache", Context.MODE_PRIVATE))
                .currentTerm(accountKey, school.id)
        }.getOrNull() ?: AcademicStudyReader.calendarTerm()

        val exams = try {
            withTimeoutOrNullCompat {
                AcademicStudyBridge.reader(school, accountKey).exams(term).map(AcademicStudyBridge::exam)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Throwable) {
            // 会话过期 / 网络失败 / 页面改版：静默等下一轮，不打扰用户。
            reconcileFromCache(app, accountKey)
            return false
        } ?: run {
            reconcileFromCache(app, accountKey)
            return false
        }

        GradesCacheManager.saveExams(app, accountKey, exams)
        ExamReminderScheduler.reconcile(app, exams, accountKey)
        return true
    }

    /** 用本地缓存重排提醒；缓存缺失时什么也不做（第一次仍要靠 UI 或下一轮网络拉取）。 */
    private fun reconcileFromCache(context: Context, accountKey: String) {
        val cached = GradesCacheManager.load(context, accountKey) ?: return
        if (cached.examsFetchedAt <= 0L) return
        ExamReminderScheduler.reconcile(context, cached.exams, accountKey)
    }

    /** 独立于外部超时的兜底上限：单次考试请求不应该拖住整轮巡检。 */
    private suspend fun <T> withTimeoutOrNullCompat(block: suspend () -> T): T? =
        kotlinx.coroutines.withTimeoutOrNull(2_500L) { block() }
}
