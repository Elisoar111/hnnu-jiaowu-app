package com.hnnujw.course.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReminderSettingsTest {

    // ---- 提前量归一 ----

    @Test fun leadMinutesOnlyAcceptsListedValues() {
        LEAD_MINUTE_OPTIONS.forEach { assertEquals(it, sanitizeLeadMinutes(it)) }
        // 越界/脏数据一律回落默认值。提前量直接参与闹钟时刻计算，
        // 放进一个 -1 会让提醒立刻误响，放进 100000 会让它永远不响。
        assertEquals(DEFAULT_LEAD_MINUTES, sanitizeLeadMinutes(-1))
        assertEquals(DEFAULT_LEAD_MINUTES, sanitizeLeadMinutes(7))
        assertEquals(DEFAULT_LEAD_MINUTES, sanitizeLeadMinutes(1441))
        assertEquals(DEFAULT_LEAD_MINUTES, sanitizeLeadMinutes(Int.MAX_VALUE))
        assertEquals(DEFAULT_LEAD_MINUTES, sanitizeLeadMinutes(Int.MIN_VALUE))
    }

    @Test fun leadLabelsReadNaturallyAtZero() {
        assertEquals("上课时", leadMinutesLabel(0))
        assertEquals("15 分钟", leadMinutesLabel(15))
        assertEquals("60 分钟", leadMinutesLabel(60))
        // 选项文案自带「提前」，否则一排纯数字看不出在选什么。
        assertEquals("上课时", leadMinutesOptionLabel(0))
        assertEquals("提前 30 分钟", leadMinutesOptionLabel(30))
        // 两个文案函数都要过归一：脏值显示成默认值，而不是原样透出。
        assertEquals("15 分钟", leadMinutesLabel(7))
        assertEquals("提前 15 分钟", leadMinutesOptionLabel(7))
    }

    // ---- 偏好读写 ----

    @Test fun defaultsKeepRemindersWorkingAfterUpgrade() {
        // 老版本没有这几个键。默认必须是「总开关开、提前 15 分钟、自动模式关」，
        // 否则升级后所有已存在的课程提醒会静默失效。
        val settings = ReminderSettingsStore(MemoryPreferences()).read()
        assertEquals(ReminderSettings(), settings)
        assertTrue(settings.masterEnabled)
        assertEquals(DEFAULT_LEAD_MINUTES, settings.leadMinutes)
        assertEquals(AutoModeKind.Off, settings.autoMode)
    }

    @Test fun writesRoundTripAndReportWhetherAnythingChanged() {
        val store = ReminderSettingsStore(MemoryPreferences())
        assertTrue(store.write(ReminderSettings(masterEnabled = false)))
        assertEquals(false, store.read().masterEnabled)
        // 值没变就不该说自己变了 —— 调用方靠这个返回值决定要不要重新排闹钟。
        assertFalse(store.write(ReminderSettings(masterEnabled = false)))

        assertTrue(store.write(ReminderSettings(masterEnabled = false, leadMinutes = 30)))
        assertEquals(30, store.read().leadMinutes)

        assertTrue(store.write(ReminderSettings(masterEnabled = false, leadMinutes = 30, autoMode = AutoModeKind.AlarmsOnly)))
        assertEquals(AutoModeKind.AlarmsOnly, store.read().autoMode)
        assertFalse(store.write(store.read()))
    }

    @Test fun dirtyLeadIsNormalizedOnWriteSoNothingElseHasToGuardIt() {
        val store = ReminderSettingsStore(MemoryPreferences())
        store.write(ReminderSettings(leadMinutes = 7))
        assertEquals(DEFAULT_LEAD_MINUTES, store.read().leadMinutes)
        // 归一之后再比较：写 7 落到 15，而当前本来就是 15，所以「没变化」。
        assertFalse(store.write(ReminderSettings(leadMinutes = 7)))
    }

    // ---- 自动模式运行态 ----

    @Test fun runtimeRoundTripsAndTreatsOffAsNotApplied() {
        val store = ReminderSettingsStore(MemoryPreferences())
        assertNull(store.readRuntime().applied)
        assertEquals(AutoModeRuntime.UNKNOWN, store.readRuntime().previousInterruptionFilter)

        store.writeRuntime(AutoModeRuntime(AutoModeKind.DoNotDisturb, 2))
        assertEquals(AutoModeKind.DoNotDisturb, store.readRuntime().applied)
        assertEquals(2, store.readRuntime().previousInterruptionFilter)

        // Off 不是"我们施加过的档位"，读回来必须是 null，否则每次 reconcile 都会去还原一次。
        store.writeRuntime(AutoModeRuntime(AutoModeKind.Off, 2))
        assertNull(store.readRuntime().applied)
    }

    @Test fun autoModeKindSurvivesStorageRoundTripAndRejectsGarbage() {
        AutoModeKind.entries.forEach { assertEquals(it, AutoModeKind.fromStorage(it.storageValue)) }
        assertEquals(AutoModeKind.Off, AutoModeKind.fromStorage(null))
        assertEquals(AutoModeKind.Off, AutoModeKind.fromStorage(""))
        assertEquals(AutoModeKind.Off, AutoModeKind.fromStorage("silent"))
        // 旧的 "silent" 档位已删除：存过的设备读回来落到 Off，不会去动系统设置。
        assertEquals(AutoModeKind.Off, AutoModeKind.fromStorage("bogus"))
    }

    @Test fun everyEnabledModeHasItsOwnInterruptionFilter() {
        assertNull(AutoModeKind.Off.interruptionFilter)
        val filters = AutoModeKind.entries.filter { it != AutoModeKind.Off }.map { it.interruptionFilter }
        assertEquals(3, filters.size)
        assertTrue("每个档位都要真的对应一个系统档位", filters.all { it != null })
        // 两档映射到同一个过滤器 = 界面上是两个选项、实际效果一样。
        assertEquals(filters.size, filters.distinct().size)
    }

    @Test fun labelsAndDescriptionsAreDistinctAndNonBlank() {
        val labels = AutoModeKind.entries.map { it.label }
        assertEquals(labels.size, labels.distinct().size)
        assertTrue(AutoModeKind.entries.all { it.label.isNotBlank() && it.description.isNotBlank() })
    }
}
