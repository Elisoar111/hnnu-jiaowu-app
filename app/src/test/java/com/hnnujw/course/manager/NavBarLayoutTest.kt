package com.hnnujw.course.manager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [NavBarLayout] 的 JVM 单测：全部是纯数据变换，不需要 Robolectric。
 *
 * 重点钉的是「用户改完底栏之后还能不能用」——布局归一容错、移动边界、
 * 以及「我的」不可移除（否则用户把自己锁在设置外面）。
 */
class NavBarLayoutTest {

    private val all = listOf(
        StartupPage.Schedule,
        StartupPage.Xuegong,
        StartupPage.Grades,
        StartupPage.SecondClass,
        StartupPage.Settings
    )

    // ── resolve ─────────────────────────────────────────────────────

    @Test
    fun `resolve null returns default`() {
        assertEquals(NavBarLayout.default, NavBarLayout.resolve(null))
    }

    @Test
    fun `resolve empty list falls back to default`() {
        assertEquals(NavBarLayout.default, NavBarLayout.resolve(emptyList()))
    }

    @Test
    fun `resolve single item falls back to default`() {
        // 只有 1 项：底栏的滑块动画不成形，整体回退默认
        assertEquals(NavBarLayout.default, NavBarLayout.resolve(listOf("grades")))
    }

    @Test
    fun `resolve without settings falls back to default`() {
        // 少了「我的」，用户就再也进不去设置改回来 → 整体回退默认
        val stored = listOf("schedule", "xuegong", "grades", "secondclass")
        assertEquals(NavBarLayout.default, NavBarLayout.resolve(stored))
    }

    @Test
    fun `resolve drops unknown routes`() {
        val stored = listOf("schedule", "nope", "grades", "settings", "ghost")
        assertEquals(
            listOf(StartupPage.Schedule, StartupPage.Grades, StartupPage.Settings),
            NavBarLayout.resolve(stored)
        )
    }

    @Test
    fun `resolve deduplicates keeping first position`() {
        val stored = listOf("grades", "schedule", "grades", "settings")
        assertEquals(
            listOf(StartupPage.Grades, StartupPage.Schedule, StartupPage.Settings),
            NavBarLayout.resolve(stored)
        )
    }

    @Test
    fun `resolve keeps user order`() {
        val stored = listOf("grades", "settings", "schedule", "xuegong")
        assertEquals(
            listOf(StartupPage.Grades, StartupPage.Settings, StartupPage.Schedule, StartupPage.Xuegong),
            NavBarLayout.resolve(stored)
        )
    }

    @Test
    fun `resolve is case sensitive on route`() {
        // route 是程序写死的常量，不认识大小写变体；大写一律当未知项丢弃
        val stored = listOf("Schedule", "grades", "settings")
        assertEquals(
            listOf(StartupPage.Grades, StartupPage.Settings),
            NavBarLayout.resolve(stored)
        )
    }

    // ── move ────────────────────────────────────────────────────────

    @Test
    fun `move swaps adjacent items`() {
        val moved = NavBarLayout.move(all, 0, 1)
        assertEquals(
            listOf(StartupPage.Xuegong, StartupPage.Schedule, StartupPage.Grades, StartupPage.SecondClass, StartupPage.Settings),
            moved
        )
    }

    @Test
    fun `move from equals to returns original`() {
        assertEquals(all, NavBarLayout.move(all, 2, 2))
    }

    @Test
    fun `move out of bounds returns original`() {
        assertEquals(all, NavBarLayout.move(all, -1, 2))
        assertEquals(all, NavBarLayout.move(all, 0, 5))
        assertEquals(all, NavBarLayout.move(all, 9, 9))
    }

    @Test
    fun `move first up returns null`() {
        assertNull(NavBarLayout.moveUp(all, 0))
    }

    @Test
    fun `move last down returns null`() {
        assertNull(NavBarLayout.moveDown(all, all.lastIndex))
    }

    @Test
    fun `move up a middle item`() {
        val moved = NavBarLayout.moveUp(all, 3)
        assertEquals(
            listOf(StartupPage.Schedule, StartupPage.Xuegong, StartupPage.SecondClass, StartupPage.Grades, StartupPage.Settings),
            moved
        )
    }

    @Test
    fun `move down a middle item`() {
        val moved = NavBarLayout.moveDown(all, 1)
        assertEquals(
            listOf(StartupPage.Schedule, StartupPage.Grades, StartupPage.Xuegong, StartupPage.SecondClass, StartupPage.Settings),
            moved
        )
    }

    @Test
    fun `move up out of range returns null`() {
        assertNull(NavBarLayout.moveUp(all, all.size))
        assertNull(NavBarLayout.moveUp(all, -3))
    }

    // ── canRemove ───────────────────────────────────────────────────

    @Test
    fun `canRemove settings is refused`() {
        assertTrue(NavBarLayout.canRemove(all, StartupPage.Settings) is NavBarLayout.RemoveCheck.CannotRemoveSettings)
    }

    @Test
    fun `canRemove when two items left is refused`() {
        val two = listOf(StartupPage.Schedule, StartupPage.Settings)
        assertTrue(NavBarLayout.canRemove(two, StartupPage.Schedule) is NavBarLayout.RemoveCheck.TooFewItems)
    }

    @Test
    fun `canRemove absent page`() {
        val two = listOf(StartupPage.Schedule, StartupPage.Settings)
        assertTrue(NavBarLayout.canRemove(two, StartupPage.Grades) is NavBarLayout.RemoveCheck.NotPresent)
    }

    @Test
    fun `canRemove normal page is ok`() {
        assertTrue(NavBarLayout.canRemove(all, StartupPage.Grades) is NavBarLayout.RemoveCheck.Ok)
    }

    @Test
    fun `canRemove settings takes priority over too few`() {
        // 只剩两项且其中一项是「我的」：报「不可移除我的」而不是「项数太少」，
        // 因为前者才是用户真正要理解的那条约束。
        val two = listOf(StartupPage.Schedule, StartupPage.Settings)
        assertTrue(NavBarLayout.canRemove(two, StartupPage.Settings) is NavBarLayout.RemoveCheck.CannotRemoveSettings)
    }

    @Test
    fun `default layout has five pages and contains settings`() {
        assertEquals(5, NavBarLayout.default.size)
        assertTrue(StartupPage.Settings in NavBarLayout.default)
    }
}
