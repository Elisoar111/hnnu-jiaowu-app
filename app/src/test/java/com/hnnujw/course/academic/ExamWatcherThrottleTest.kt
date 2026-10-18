package com.hnnujw.course.academic

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 考试列表后台拉取的节流判定。
 *
 * 这几条都属于"写错了也不会报错、只会静默少拉或多拉"的边界：
 * 新装用户拉不到第一次、用户主动刷新被拦、系统改时间后一个未来时间戳
 * 把功能永久冻住。
 */
class ExamWatcherThrottleTest {

    private val interval = 6 * 60 * 60 * 1000L
    private val now = 1_000_000_000_000L

    @Test
    fun `从未拉取过时必须放行`() {
        assertTrue(ExamWatcher.shouldFetch(lastCheckAtMillis = 0L, nowMillis = now, force = false, intervalMs = interval))
    }

    @Test
    fun `窗口内不重复拉取`() {
        assertFalse(
            ExamWatcher.shouldFetch(
                lastCheckAtMillis = now - 5 * 60 * 60 * 1000L,
                nowMillis = now, force = false, intervalMs = interval,
            )
        )
    }

    @Test
    fun `窗口边界到期后放行`() {
        assertTrue(
            ExamWatcher.shouldFetch(
                lastCheckAtMillis = now - interval,
                nowMillis = now, force = false, intervalMs = interval,
            )
        )
    }

    @Test
    fun `force 压过节流`() {
        assertTrue(ExamWatcher.shouldFetch(lastCheckAtMillis = now - 1_000L, nowMillis = now, force = true, intervalMs = interval))
    }

    /** 系统改时间导致上次记录比现在还大：不能因此永久不再拉取。 */
    @Test
    fun `时间倒流按已过期处理`() {
        assertTrue(ExamWatcher.shouldFetch(lastCheckAtMillis = now + 86_400_000L, nowMillis = now, force = false, intervalMs = interval))
    }

    /** 12 小时巡检 + 6 小时节流：正常情况下每轮都会真的拉取，不会被漂移卡掉。 */
    @Test
    fun `十二小时巡检总能越过六小时节流`() {
        val twelveHours = 12 * 60 * 60 * 1000L
        assertTrue(ExamWatcher.shouldFetch(lastCheckAtMillis = now - twelveHours, nowMillis = now, force = false, intervalMs = interval))
    }
}
