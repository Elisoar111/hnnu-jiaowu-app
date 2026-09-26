package com.hnnujw.course.schedule

import org.junit.Assert.assertEquals
import org.junit.Test

class CourseOpenDecisionTest {

    private val courses = listOf("network:stable", "custom:abc")

    @Test
    fun noRequestMeansNothingToDo() {
        assertEquals(CourseOpenDecision.None, decideCourseOpen(null, courses, isLoading = false))
    }

    @Test
    fun blankRequestIsTreatedAsNoRequest() {
        assertEquals(CourseOpenDecision.None, decideCourseOpen("   ", courses, isLoading = true))
    }

    @Test
    fun knownCourseOpens() {
        assertEquals(CourseOpenDecision.Open, decideCourseOpen("custom:abc", courses, isLoading = false))
    }

    /**
     * 命中优先于"加载中"：课表已经加载完、后续同步又把 isLoading 置回 true 时，
     * 课程其实就在手上，让它再等一轮会表现为"点两次才开"。
     */
    @Test
    fun aHitWinsOverTheLoadingFlag() {
        assertEquals(CourseOpenDecision.Open, decideCourseOpen("network:stable", courses, isLoading = true))
    }

    @Test
    fun unknownCourseWaitsWhileStillLoading() {
        assertEquals(CourseOpenDecision.Wait, decideCourseOpen("custom:gone", courses, isLoading = true))
    }

    @Test
    fun unknownCourseIsDroppedOnceLoaded() {
        assertEquals(CourseOpenDecision.Drop, decideCourseOpen("custom:gone", courses, isLoading = false))
    }

    /** 空课表 + 已加载完 = 这门课确实不在（换了账号 / 清过缓存），不能一直挂着请求。 */
    @Test
    fun emptyScheduleAfterLoadingDropsTheRequest() {
        assertEquals(CourseOpenDecision.Drop, decideCourseOpen("network:stable", emptyList(), isLoading = false))
    }

    /** 空课表 + 还在加载 = 还没读出来，留着。 */
    @Test
    fun emptyScheduleWhileLoadingWaits() {
        assertEquals(CourseOpenDecision.Wait, decideCourseOpen("network:stable", emptyList(), isLoading = true))
    }
}
