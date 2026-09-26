package com.hnnujw.course.ykt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 电费低余额提醒的判定回归。
 *
 * 这里钉的是几条最容易写错、又最难在真机上复现的边界：
 * 取不取等号、余额未知怎么办、**低于阈值是否每次都提醒**、回升后能否再响。
 *
 * ⚠️ 2026-09-25 用户明确要求「不限制提醒次数」后，本文件的"去重"用例
 * 已**反转**为"每次都提醒"。变更前它们是 lastNotifiedAmount/active 的门槛，
 * 现在 active 只用于查询与排查，**不再参与是否提醒的判定**。
 */
class YktAlertPolicyTest {

    private val threshold = 20.0

    private val fresh = YktAlertPolicy.AlertState.NONE
    private val alreadyAlerted = YktAlertPolicy.AlertState(active = true, lastNotifiedAmount = 15.0)

    // ── 基本触发 ──────────────────────────────────────────────────────────

    @Test
    fun `余额低于阈值时首次提醒`() {
        val decision = YktAlertPolicy.decide(balance = 15.0, threshold = threshold, previous = fresh)
        assertTrue(decision.shouldNotify)
        assertTrue(decision.nextState.active)
    }

    @Test
    fun `余额高于阈值时不提醒`() {
        val decision = YktAlertPolicy.decide(balance = 50.0, threshold = threshold, previous = fresh)
        assertFalse(decision.shouldNotify)
    }

    /** 等号归属必须钉死：恰好等于阈值**不算**不足，否则 20.00 会莫名报警。 */
    @Test
    fun `余额恰好等于阈值时不提醒`() {
        val decision = YktAlertPolicy.decide(balance = 20.0, threshold = threshold, previous = fresh)
        assertFalse(decision.shouldNotify)
        assertFalse(decision.nextState.active)
    }

    @Test
    fun `余额刚好低于阈值一分钱时提醒`() {
        val decision = YktAlertPolicy.decide(balance = 19.99, threshold = threshold, previous = fresh)
        assertTrue(decision.shouldNotify)
    }

    // ── 不限次数：余额仍低时每次都提醒（用户要求）────────────────────────

    @Test
    fun `已提醒过且余额仍低时仍然提醒`() {
        val decision = YktAlertPolicy.decide(balance = 10.0, threshold = threshold, previous = alreadyAlerted)
        assertTrue(decision.shouldNotify)
        assertTrue(decision.nextState.active)
        assertEquals(10.0, decision.nextState.lastNotifiedAmount!!, 0.001)
    }

    @Test
    fun `余额继续下降也照样提醒`() {
        val decision = YktAlertPolicy.decide(balance = 5.0, threshold = threshold, previous = alreadyAlerted)
        assertTrue(decision.shouldNotify)
        assertEquals(5.0, decision.nextState.lastNotifiedAmount!!, 0.001)
    }

    /** 连续多轮低余额：每一轮都要提醒（这是"不限次数"的核心断言）。 */
    @Test
    fun `连续三次低余额都提醒`() {
        var state = YktAlertPolicy.AlertState.NONE
        listOf(18.0, 12.0, 6.0).forEach { balance ->
            val decision = YktAlertPolicy.decide(balance, threshold, state)
            assertTrue("余额 $balance 应提醒", decision.shouldNotify)
            state = decision.nextState
        }
    }

    // ── 回弹后状态重置 ────────────────────────────────────────────────────

    @Test
    fun `余额回升到阈值以上后重置状态`() {
        val decision = YktAlertPolicy.decide(balance = 100.0, threshold = threshold, previous = alreadyAlerted)
        assertFalse(decision.shouldNotify)
        assertFalse(decision.nextState.active)
    }

    /** 完整周期：低 → 提醒 → 充钱回升 → 再低 → 仍要提醒。 */
    @Test
    fun `充值回升后再次跌破仍会提醒`() {
        val first = YktAlertPolicy.decide(15.0, threshold, fresh)
        assertTrue(first.shouldNotify)

        // 充值后余额充足：状态被重置
        val refilled = YktAlertPolicy.decide(100.0, threshold, first.nextState)
        assertFalse(refilled.shouldNotify)
        assertFalse(refilled.nextState.active)

        // 再次跌破：提醒
        val second = YktAlertPolicy.decide(8.0, threshold, refilled.nextState)
        assertTrue(second.shouldNotify)
    }

    // ── 余额未知 ──────────────────────────────────────────────────────────

    @Test
    fun `余额为 null 时不提醒`() {
        val decision = YktAlertPolicy.decide(balance = null, threshold = threshold, previous = fresh)
        assertFalse(decision.shouldNotify)
    }

    /**
     * 余额未知时**不能清空**已有状态：保留它只为排查（上次提醒时的余额），
     * 因为判定已不看 active，这条不再是"防重复"的门槛。
     */
    @Test
    fun `余额为 null 时保留原有提醒状态`() {
        val decision = YktAlertPolicy.decide(balance = null, threshold = threshold, previous = alreadyAlerted)
        assertTrue(decision.nextState.active)
    }

    @Test
    fun `余额未知后再读到低余额仍会提醒`() {
        val afterNull = YktAlertPolicy.decide(null, threshold, alreadyAlerted)
        val retry = YktAlertPolicy.decide(12.0, threshold, afterNull.nextState)
        assertTrue(retry.shouldNotify)
    }

    // ── 负数余额 ─────────────────────────────────────────────────────────

    @Test
    fun `负余额 欠费 也提醒`() {
        // 电费账户出现负数是"已透支"，比低余额更紧急，绝不能漏
        val decision = YktAlertPolicy.decide(balance = -3.5, threshold = threshold, previous = fresh)
        assertTrue(decision.shouldNotify)
    }

    @Test
    fun `零余额提醒`() {
        val decision = YktAlertPolicy.decide(balance = 0.0, threshold = threshold, previous = fresh)
        assertTrue(decision.shouldNotify)
    }

    // ── 阈值变化 ─────────────────────────────────────────────────────────

    @Test
    fun `提高阈值后原本充足的余额会触发提醒`() {
        val decision = YktAlertPolicy.decide(balance = 30.0, threshold = 50.0, previous = fresh)
        assertTrue(decision.shouldNotify)
    }

    // ── 文案 ─────────────────────────────────────────────────────────────

    @Test
    fun `通知文案包含余额与阈值`() {
        val text = YktAlertPolicy.notificationText(12.3, 20.0)
        assertTrue(text.contains("12.30"))
        assertTrue(text.contains("20.00"))
    }

    @Test
    fun `通知标题非空`() {
        assertTrue(YktAlertPolicy.notificationTitle().isNotBlank())
    }

    // ── 阈值归一化 ───────────────────────────────────────────────────────

    @Test
    fun `阈值越界被夹到边界`() {
        assertEquals(
            YktAlertSettings.MIN_THRESHOLD_YUAN,
            YktAlertSettings.clampThreshold(0.0),
            0.001,
        )
        assertEquals(
            YktAlertSettings.MAX_THRESHOLD_YUAN,
            YktAlertSettings.clampThreshold(99999.0),
            0.001,
        )
    }

    @Test
    fun `阈值非有限值回落到默认值`() {
        assertEquals(
            YktAlertSettings.DEFAULT_THRESHOLD_YUAN,
            YktAlertSettings.clampThreshold(Double.NaN),
            0.001,
        )
        assertEquals(
            YktAlertSettings.DEFAULT_THRESHOLD_YUAN,
            YktAlertSettings.clampThreshold(Double.POSITIVE_INFINITY),
            0.001,
        )
    }

    @Test
    fun `默认阈值为 20 元`() {
        assertEquals(20.0, YktAlertSettings.DEFAULT_THRESHOLD_YUAN, 0.001)
    }
}
