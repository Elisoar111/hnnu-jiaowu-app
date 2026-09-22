package com.hnnujw.course.schedule

import org.junit.Assert.*
import org.junit.Test

class ScheduleWeekPagerSyncTest {
    private val calendar = "2026-2027-1|2026-09-07"

    @Test fun initialPagerPageCannotOverwriteTheWeekCalculatedFromTheStartDate() {
        val sync = ScheduleWeekPagerSync(3, calendar)
        assertNull(sync.settledUnit(0))
        assertEquals(2, sync.requestPage(3, calendar))
        assertNull(sync.settledUnit(2))
        assertNull(sync.requestPage(3, calendar))
    }

    @Test fun restoringABrowsedWeekAlignsThePagerBeforeReportingAnyUserChange() {
        val sync = ScheduleWeekPagerSync(8, calendar)
        assertNull(sync.settledUnit(0))
        assertEquals(7, sync.requestPage(8, calendar))
        assertNull(sync.settledUnit(7))
        assertEquals(9, sync.settledUnit(8))
        assertNull(sync.settledUnit(8))
    }

    @Test fun reportingAUserSwipeNeverBecomesANewCalendarJump() {
        val sync = ScheduleWeekPagerSync(3, calendar)
        sync.settledUnit(2)
        assertEquals(4, sync.settledUnit(3))
        assertEquals(5, sync.settledUnit(4))
        // The parent can acknowledge a previous page after the next gesture has started.
        assertNull(sync.requestPage(4, calendar))
        assertNull(sync.requestPage(5, calendar))
        assertEquals(6, sync.settledUnit(5))
    }

    @Test fun savingADateOverridesAnOldSettledPageEvenWhenTheRequestedWeekIsUnchanged() {
        val sync = ScheduleWeekPagerSync(3, calendar)
        sync.settledUnit(2)
        val changed = "2026-2027-1|2026-08-31"
        assertEquals(2, sync.requestPage(3, changed))
        assertNull(sync.settledUnit(3))
        assertEquals(2, sync.requestPage(3, changed))
        assertNull(sync.settledUnit(2))
        assertNull(sync.requestPage(3, changed))
    }

    @Test fun aCalendarArrivingAfterTheInitialPageAndSemesterChangesAlwaysSelectTheirOwnWeek() {
        val sync = ScheduleWeekPagerSync(1, "")
        sync.settledUnit(0)
        assertEquals(2, sync.requestPage(3, calendar))
        assertNull(sync.settledUnit(0))
        sync.settledUnit(2)
        assertEquals(0, sync.requestPage(1, "2026-2027-2|2027-03-01"))
        sync.settledUnit(0)
        assertEquals(2, sync.requestPage(3, calendar))
    }

    @Test fun callersWithoutACalendarKeyCanStillRequestWeeksAndAcknowledgeSwipes() {
        val sync = ScheduleWeekPagerSync(1, null)
        sync.settledUnit(0)
        assertEquals(4, sync.requestPage(5, null))
        sync.settledUnit(4)
        assertEquals(6, sync.settledUnit(5))
        assertNull(sync.requestPage(6, null))
        assertEquals(1, sync.requestPage(2, null))
    }
}
