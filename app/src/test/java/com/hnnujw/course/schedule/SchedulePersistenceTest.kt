package com.hnnujw.course.schedule

import com.hnnujw.course.manager.AppThemeMode
import com.hnnujw.course.manager.AppThemePreferences
import com.hnnujw.course.manager.ScheduleSettingsManager
import org.junit.Assert.*
import org.junit.Test

class SchedulePersistenceTest {
    @Test fun semesterStartDatePublishesOnlyChangesAndSurvivesReload() {
        val prefs = MemoryPreferences()
        val manager = ScheduleSettingsManager(prefs)
        val initialRevision = manager.revision
        manager.semesterStartDate = 1_778_457_600_000L
        assertTrue(manager.revision > initialRevision)
        assertEquals(manager.semesterStartDate, ScheduleSettingsManager(prefs).semesterStartDate)
        val changedRevision = manager.revision
        manager.semesterStartDate = manager.semesterStartDate
        assertEquals(changedRevision, manager.revision)
    }

    @Test fun periodCountChangesPublishRevisionSoBackNavigationCannotLeaveAStaleGrid() {
        val manager = ScheduleSettingsManager(MemoryPreferences())
        val previous = manager.revision
        manager.periodCount = 16
        assertEquals(16, manager.periodCount)
        assertTrue(manager.revision > previous)
    }
    @Test fun themeRoundTripsIndependentlyFromWallpaperAndInvalidTypesFallBack() {
        val prefs = MemoryPreferences()
        prefs.edit().putString("wallpaper", "Aurora").putFloat("image_dim", 0.37f).apply()
        assertEquals(AppThemeMode.System, AppThemePreferences(prefs).read())
        AppThemeMode.entries.forEach {
            AppThemePreferences(prefs).write(it)
            assertEquals(it, AppThemePreferences(prefs).read())
        }
        prefs.edit().putString("theme_mode", "obsolete").apply()
        assertEquals(AppThemeMode.System, AppThemePreferences(prefs).read())
        prefs.edit().putBoolean("theme_mode", true).apply()
        assertEquals(AppThemeMode.System, AppThemePreferences(prefs).read())
        assertEquals("Aurora", prefs.getString("wallpaper", null))
        assertEquals(0.37f, prefs.getFloat("image_dim", 0f), 0f)
    }

    @Test fun editingDeletionUndoAndReloadKeepIdsAndAccountIsolation() {
        val prefs = MemoryPreferences()
        val manager = ScheduleSettingsManager(prefs)
        val original = ScheduleSettingsManager.CustomCourse("existing-id", "数学", "A101", "", 1, 1, 2, "1-16周")
        manager.addCustomCourse(original, "account-a")
        manager.addCustomCourse(original.copy(name = "英语"), "account-b")
        manager.updateCustomCourse(original.copy(name = "高数", teacher = "老师"), "account-a")
        assertEquals("existing-id", manager.getCustomCourses("account-a").single().id)
        assertEquals("英语", manager.getCustomCourses("account-b").single().name)
        val deleted = manager.getCustomCourses("account-a").single()
        manager.removeCustomCourse(deleted.id, "account-a")
        assertTrue(manager.getCustomCourses("account-a").isEmpty())
        manager.updateCustomCourse(deleted, "account-a")
        assertEquals(deleted, ScheduleSettingsManager(prefs).getCustomCourses("account-a").single())
    }

    @Test fun legacyMissingAndDuplicateIdsAreRepairedOnlyOnce() {
        val prefs = MemoryPreferences()
        val legacy = """[{"id":"keep","name":"数学","day":1,"startPeriod":1,"endPeriod":2},
            {"id":"keep","name":"英语","day":2,"startPeriod":1,"endPeriod":2},
            {"name":"体育","day":3,"startPeriod":1,"endPeriod":2}]"""
        prefs.edit().putString("custom_courses", legacy).apply()
        val first = ScheduleSettingsManager(prefs).getCustomCourses("account-a")
        assertEquals("keep", first.first().id)
        assertEquals(3, first.map { it.id }.toSet().size)
        assertTrue(first.all { it.id.isNotBlank() })
        assertEquals(first, ScheduleSettingsManager(prefs).getCustomCourses("account-a"))
        assertTrue(ScheduleSettingsManager(prefs).getCustomCourses("account-b").isEmpty())
        assertFalse(prefs.contains("custom_courses"))
    }

    @Test fun semesterCalendarsAreIsolatedAndLegacyDateMigratesToOnlyOneCurrentTerm() {
        val prefs = MemoryPreferences()
        val store = ScheduleCalendarStore(prefs)
        val base = ScheduleTimeBase("2026-09-07", mapOf(1 to "08:00"))
        assertTrue(store.migrateLegacy("a", "2026-2027-1", base))
        assertFalse(ScheduleCalendarStore(prefs).migrateLegacy("a", "2026-2027-2", base))
        assertNull(store.read("a", "2026-2027-2"))
        assertNull(store.read("b", "2026-2027-1"))
        val next = base.copy(firstWeekDate = "2027-03-01")
        assertTrue(store.write("a", "2026-2027-2", next))
        assertEquals(base, store.read("a", "2026-2027-1"))
        assertEquals(next, ScheduleCalendarStore(prefs).read("a", "2026-2027-2"))
    }

    /**
     * 存储层自己归一化账号键，而不是依赖调用方传对形态。
     *
     * 项目里有两种账号键且**永不相等**：`currentAccountKey` = `hnnu::2024001`，
     * `currentAccountStorageKey` = `hnnu__2024001`。按账号落盘的存储层若只做字符串拼接，
     * 调用方一旦传错形态就会"写一个键、读另一个键" —— 数据被静默劈成两份，用户无从察觉
     * （本项目已因同类问题出现过"二课卡片恒显示未绑定"）。
     *
     * 当前所有调用方传的都是 storage key，归一化对它们是**空操作**（`_` 属于允许字符），
     * 所以这条测试同时钉住了"加固不改变现有行为"。
     */
    @Test fun customCoursesAreStoredUnderTheSameSlotWhateverKeyShapeCallersUse() {
        val prefs = MemoryPreferences()
        val manager = ScheduleSettingsManager(prefs)
        val rawKey = "hnnu::2024001"
        val storageKey = "hnnu__2024001"

        manager.addCustomCourse(
            ScheduleSettingsManager.CustomCourse("c1", "数学", "A101", "", 1, 1, 2, "1-16周"),
            rawKey,
        )

        // 换成另一种形态读，必须读到同一份数据
        assertEquals(1, manager.getCustomCourses(storageKey).size)
        assertEquals("数学", manager.getCustomCourses(storageKey).single().name)
        assertEquals(1, ScheduleSettingsManager(prefs).getCustomCourses(rawKey).size)
        assertEquals(1, ScheduleSettingsManager(prefs).getCustomCourses(storageKey).size)
    }
}
