package com.hnnujw.course.widgetboard

import android.content.Context
import com.hnnujw.course.academic.MessageCenterManager
import com.hnnujw.course.manager.GradesCacheManager
import com.hnnujw.course.manager.UserManager
import com.hnnujw.course.schedule.ScheduleRepository
import com.hnnujw.course.schedule.ScheduleSnapshot
import com.hnnujw.course.schedule.ScheduleWidgetState
import com.hnnujw.course.secondclass.SecondClassOverviewCache
import com.hnnujw.course.secondclass.SecondClassroomStore

/** 一次刷新所需的全部东西：卡片数据 + 下一个"状态会变"的时刻（排边界闹钟用）。 */
internal data class CardWidgetSnapshot(val data: WidgetBoardData, val nextChangeAt: Long?)

/**
 * 桌面卡片组件的数据装配：**只读本地缓存，一次都不上网**。
 *
 * 与组件工作台（`ui/route/WidgetBoardRoute`）共用同一批缓存与同一套换算函数
 * （[scheduleCardData] / [examCardData] / [gradesCardData] / [secondClassCardData] /
 * [messagesCardData]），所以桌面上那张卡片和 App 里同一张卡片说的是同一句话。
 *
 * 两点刻意的设计：
 *  - **同步返回**。系统组件的 `onUpdate` 跑在主线程的广播里，拿不到协程作用域；
 *    这里全部走同步读缓存，不在广播里 `runBlocking`（那会卡住主线程）。
 *  - **可用性判据与 App 内完全一致**（会话 token 属于当前账号、账号真在保存列表里）。
 *    少了这一层，切账号的那一瞬间桌面卡片会显示出上一个账号的成绩。
 */
internal object CardWidgetDataLoader {

    /**
     * 当前会话可展示的课表快照；未登录 / 演示模式 / 会话与账号不匹配 → null
     * （卡片显示"登录后查看课表"）。
     */
    fun activeSnapshot(context: Context): ScheduleSnapshot? {
        val user = UserManager.getInstance()
        val session = user.sessionState.state.value
        val school = user.currentSchool ?: return null
        if (user.isDemoMode || (!user.isLoggedIn && !(session.expired && user.hasSavedCookie())) ||
            session.token.accountStorageKey != user.currentAccountStorageKey ||
            user.savedAccounts.none { it.key == user.currentAccountKey }) return null
        return ScheduleRepository(context).snapshot(user.currentAccountStorageKey, school.id)
    }

    /**
     * 一次装齐所有卡片需要的数据。
     *
     * 课表快照只读一次，数据与边界闹钟共用同一份 —— 否则每次刷新要把
     * `schedule_cache` 读两遍（[ScheduleWidgetState.from] 本身还会再算一次，那是纯计算，
     * 留着比给共用的 [scheduleCardData] 加一个只有组件才用的出参划算）。
     *
     * @param now 由调用方传入，保证同一次刷新里 7 张卡片看到的是同一个"现在"。
     */
    fun snapshot(context: Context, now: Long = System.currentTimeMillis()): CardWidgetSnapshot {
        val scheduleSnapshot = activeSnapshot(context)
        return CardWidgetSnapshot(
            data = build(context, scheduleSnapshot, now),
            nextChangeAt = ScheduleWidgetState.from(scheduleSnapshot, now).agenda?.nextChangeAt,
        )
    }

    private fun build(context: Context, scheduleSnapshot: ScheduleSnapshot?, now: Long): WidgetBoardData {
        val accountKey = UserManager.getInstance().currentAccountStorageKey
        val gradesCache = if (accountKey.isBlank()) null else GradesCacheManager.load(context, accountKey)
        val secondClass = if (accountKey.isBlank()) null else SecondClassOverviewCache.load(context, accountKey)
        return WidgetBoardData(
            now = now,
            // 桌面卡片比 App 内卡片高得多（能拉到 4×5），时间轴多备几条；渲染层再按实际高度截。
            schedule = scheduleCardData(scheduleSnapshot, now, timelineLimit = 12),
            exam = examCardData(gradesCache?.exams.orEmpty(), now),
            grades = gradesCardData(gradesCache?.report, gradesCache?.fetchedAt ?: 0L),
            secondClass = secondClassCardData(
                bound = SecondClassroomStore.token(context, accountKey).isNotBlank(),
                modules = secondClass?.modules.orEmpty(),
                hourUnit = secondClass?.hourUnit.orEmpty(),
                updatedAt = secondClass?.updatedAt ?: 0L,
            ),
            messages = messagesCardData(
                if (accountKey.isBlank()) null else MessageCenterManager.readCache(context, accountKey),
            ),
        )
    }
}
