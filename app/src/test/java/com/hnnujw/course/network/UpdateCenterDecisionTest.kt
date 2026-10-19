package com.hnnujw.course.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自动更新检查的**决策逻辑**回归（纯函数，无 Android 依赖）。
 *
 * 对应 2026-10-19 的线上问题：**启动时自动检查从不弹窗，只有手动点「检查更新」才弹**。
 * 根因是 `UpdateCenter.pending`（Compose 状态）被写在 IO 线程上，界面不重组；
 * 这里锁住的是同一段流程里另外两个**容易悄悄改坏**的判据 ——
 * 节流窗口与「以后再说」的压制规则。它们是纯函数，改错了不会报错，
 * 只会表现为"再也不弹"或"每次都弹"。
 */
class UpdateCenterDecisionTest {

    private val sixHours = 6 * 60 * 60 * 1000L

    // ── 节流窗口

    @Test
    fun `首次检查没有历史记录时会执行`() {
        // lastCheck = 0 表示从未查过（全新安装）
        assertTrue(UpdateCenter.shouldAutoCheck(nowMillis = 1_700_000_000_000L, lastCheckMillis = 0L))
    }

    @Test
    fun `刚查过不会重复查`() {
        val now = 1_700_000_000_000L
        assertFalse(UpdateCenter.shouldAutoCheck(now, lastCheckMillis = now - 1_000L))
        assertFalse(UpdateCenter.shouldAutoCheck(now, lastCheckMillis = now - sixHours / 2))
    }

    @Test
    fun `超过窗口会重新查`() {
        val now = 1_700_000_000_000L
        assertTrue(UpdateCenter.shouldAutoCheck(now, lastCheckMillis = now - sixHours))
        assertTrue(UpdateCenter.shouldAutoCheck(now, lastCheckMillis = now - sixHours - 1L))
    }

    @Test
    fun `窗口边界是大于等于`() {
        val now = 1_700_000_000_000L
        // 恰好到点就应该查：用 >= 而不是 >，避免"差 1 毫秒就永远不查"
        assertTrue(UpdateCenter.shouldAutoCheck(now, lastCheckMillis = now - sixHours))
        assertFalse(UpdateCenter.shouldAutoCheck(now, lastCheckMillis = now - sixHours + 1L))
    }

    /** 系统时间被往回调时，差值算出来是负数，不能因此被判定为"刚查过"而永远不查。 */
    @Test
    fun `时钟回拨不应导致永不检查`() {
        val now = 1_700_000_000_000L
        // lastCheck 在"未来"（用户改过系统时间）：差值为负 → 视为需要检查，
        // 否则这台设备会一直卡在"刚查过"的状态里。
        assertTrue(
            "时钟回拨后应允许重新检查",
            UpdateCenter.shouldAutoCheck(now, lastCheckMillis = now + 60_000L)
        )
    }

    // ── 弹窗压制规则

    @Test
    fun `没有跳过记录时新版本会弹窗`() {
        assertTrue(UpdateCenter.shouldPrompt(forceUpdate = false, remoteVersion = "1.4.0", skippedVersion = null))
    }

    @Test
    fun `跳过过的同一版本不再弹`() {
        assertFalse(UpdateCenter.shouldPrompt(forceUpdate = false, remoteVersion = "1.4.0", skippedVersion = "1.4.0"))
    }

    @Test
    fun `跳过旧版本后来更新的版本仍会弹`() {
        assertTrue(UpdateCenter.shouldPrompt(forceUpdate = false, remoteVersion = "1.4.1", skippedVersion = "1.4.0"))
    }

    /** 🔴 强制更新必须无视「以后再说」，否则用户能把强制更新永久压掉。 */
    @Test
    fun `强制更新无视跳过记录`() {
        assertTrue(
            "强制更新不能被『以后再说』压住",
            UpdateCenter.shouldPrompt(forceUpdate = true, remoteVersion = "1.3.1", skippedVersion = "1.3.1")
        )
    }

    @Test
    fun `强制更新在没有跳过记录时也弹`() {
        assertTrue(UpdateCenter.shouldPrompt(forceUpdate = true, remoteVersion = "1.3.1", skippedVersion = null))
    }
}
