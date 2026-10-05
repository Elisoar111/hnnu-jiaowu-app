package com.hnnujw.course.ykt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 电费低余额提醒的判定回归。
 *
 * 这里钉的是几条最容易写错、又最难在真机上复现的边界：
 * 取不取等号、余额未知怎么办、同余额 24h 节流窗口、余额下降立即再提醒、回升后能否再响。
 */
class YktAlertPolicyTest {

    private val threshold = 20.0
    private val now = 1_000_000_000_000L

    private val fresh = YktAlertPolicy.AlertState.NONE
    private val alreadyAlerted = YktAlertPolicy.AlertState(
        active = true, lastNotifiedAmount = 15.0, lastNotifiedAtMillis = now,
    )

    // ── 基本触发 ──────────────────────────────────────────────────────────

    @Test
    fun `余额低于阈值时首次提醒`() {
        val decision = YktAlertPolicy.decide(balance = 15.0, threshold = threshold, previous = fresh, nowMillis = now)
        assertTrue(decision.shouldNotify)
        assertTrue(decision.nextState.active)
        assertEquals(now, decision.nextState.lastNotifiedAtMillis)
    }

    @Test
    fun `余额高于阈值时不提醒`() {
        val decision = YktAlertPolicy.decide(balance = 50.0, threshold = threshold, previous = fresh, nowMillis = now)
        assertFalse(decision.shouldNotify)
    }

    /** 等号归属必须钉死：恰好等于阈值**不算**不足，否则 20.00 会莫名报警。 */
    @Test
    fun `余额恰好等于阈值时不提醒`() {
        val decision = YktAlertPolicy.decide(balance = 20.0, threshold = threshold, previous = fresh, nowMillis = now)
        assertFalse(decision.shouldNotify)
        assertFalse(decision.nextState.active)
    }

    @Test
    fun `余额刚好低于阈值一分钱时提醒`() {
        val decision = YktAlertPolicy.decide(balance = 19.99, threshold = threshold, previous = fresh, nowMillis = now)
        assertTrue(decision.shouldNotify)
    }

    // ── 同余额 24h 节流（巡检一天四次后防轰炸）────────────────────────────

    @Test
    fun `同一余额在24小时内不重复提醒`() {
        val decision = YktAlertPolicy.decide(
            balance = 15.0, threshold = threshold, previous = alreadyAlerted,
            nowMillis = now + 6 * 60 * 60 * 1000L,  // 6 小时后巡检，余额没变
        )
        assertFalse("余额没变、窗口期内不应再发", decision.shouldNotify)
        assertTrue(decision.nextState.active)
    }

    @Test
    fun `同一余额超过24小时后再次提醒`() {
        val decision = YktAlertPolicy.decide(
            balance = 15.0, threshold = threshold, previous = alreadyAlerted,
            nowMillis = now + YktAlertPolicy.SAME_BALANCE_REPEAT_INTERVAL_MS + 1,
        )
        assertTrue(decision.shouldNotify)
    }

    @Test
    fun `余额继续下降立即再提醒`() {
        // "反复提醒有价值"的核心场景：知道低了但没充，余额还在掉必须立刻知道
        val decision = YktAlertPolicy.decide(
            balance = 5.0, threshold = threshold, previous = alreadyAlerted,
            nowMillis = now + 1_000L,
        )
        assertTrue(decision.shouldNotify)
        assertEquals(5.0, decision.nextState.lastNotifiedAmount!!, 0.001)
        assertEquals(now + 1_000L, decision.nextState.lastNotifiedAtMillis)
    }

    /** 连续多轮下降：每一轮都要提醒（余额在变就不受节流约束）。 */
    @Test
    fun `连续三次下降都提醒`() {
        var state = YktAlertPolicy.AlertState.NONE
        var t = now
        listOf(18.0, 12.0, 6.0).forEach { balance ->
            val decision = YktAlertPolicy.decide(balance, threshold, state, t)
            assertTrue("余额 $balance 应提醒", decision.shouldNotify)
            state = decision.nextState
            t += 6 * 60 * 60 * 1000L
        }
    }

    /** 节流期内不发通知，但提醒时刻的基准必须保持"上次真实提醒"，否则窗口会越滚越长。 */
    @Test
    fun `节流期内状态保留上次提醒时刻`() {
        val decision = YktAlertPolicy.decide(
            balance = 15.0, threshold = threshold, previous = alreadyAlerted,
            nowMillis = now + 3_600_000L,
        )
        assertFalse(decision.shouldNotify)
        assertEquals(now, decision.nextState.lastNotifiedAtMillis)
    }

    @Test
    fun `上次提醒时刻未知时视为久远照常提醒`() {
        // 老版本升级上来：状态里没有时刻（0），不能被误判成"刚提醒过"
        val legacy = YktAlertPolicy.AlertState(active = true, lastNotifiedAmount = 15.0)
        val decision = YktAlertPolicy.decide(15.0, threshold, legacy, now)
        assertTrue(decision.shouldNotify)
    }

    // ── 回弹后状态重置 ────────────────────────────────────────────────────

    @Test
    fun `余额回升到阈值以上后重置状态`() {
        val decision = YktAlertPolicy.decide(balance = 100.0, threshold = threshold, previous = alreadyAlerted, nowMillis = now)
        assertFalse(decision.shouldNotify)
        assertFalse(decision.nextState.active)
    }

    /** 完整周期：低 → 提醒 → 充钱回升 → 再低 → 仍要提醒。 */
    @Test
    fun `充值回升后再次跌破仍会提醒`() {
        val first = YktAlertPolicy.decide(15.0, threshold, fresh, now)
        assertTrue(first.shouldNotify)

        // 充值后余额充足：状态被重置
        val refilled = YktAlertPolicy.decide(100.0, threshold, first.nextState, now + 1)
        assertFalse(refilled.shouldNotify)
        assertFalse(refilled.nextState.active)

        // 再次跌破（哪怕金额与上次相同）：提醒
        val second = YktAlertPolicy.decide(15.0, threshold, refilled.nextState, now + 2)
        assertTrue(second.shouldNotify)
    }

    // ── 余额未知 ──────────────────────────────────────────────────────────

    @Test
    fun `余额为 null 时不提醒`() {
        val decision = YktAlertPolicy.decide(balance = null, threshold = threshold, previous = fresh, nowMillis = now)
        assertFalse(decision.shouldNotify)
    }

    /** 余额未知时**不能清空**已有状态：保留它用于节流判据与排查。 */
    @Test
    fun `余额为 null 时保留原有提醒状态`() {
        val decision = YktAlertPolicy.decide(balance = null, threshold = threshold, previous = alreadyAlerted, nowMillis = now)
        assertTrue(decision.nextState.active)
        assertEquals(now, decision.nextState.lastNotifiedAtMillis)
    }

    @Test
    fun `余额未知后再读到下降的余额仍会提醒`() {
        // 中间经历一轮"余额未知"不能破坏状态：余额继续下降（15 → 12）必须照常提醒
        val afterNull = YktAlertPolicy.decide(null, threshold, alreadyAlerted, now + 1_000L)
        val retry = YktAlertPolicy.decide(12.0, threshold, afterNull.nextState, now + 2_000L)
        assertTrue(retry.shouldNotify)
    }

    // ── 负数余额 ─────────────────────────────────────────────────────────

    @Test
    fun `负余额 欠费 也提醒`() {
        // 电费账户出现负数是"已透支"，比低余额更紧急，绝不能漏
        val decision = YktAlertPolicy.decide(balance = -3.5, threshold = threshold, previous = fresh, nowMillis = now)
        assertTrue(decision.shouldNotify)
    }

    @Test
    fun `零余额提醒`() {
        val decision = YktAlertPolicy.decide(balance = 0.0, threshold = threshold, previous = fresh, nowMillis = now)
        assertTrue(decision.shouldNotify)
    }

    // ── 阈值变化 ─────────────────────────────────────────────────────────

    @Test
    fun `提高阈值后原本充足的余额会触发提醒`() {
        val decision = YktAlertPolicy.decide(balance = 30.0, threshold = 50.0, previous = fresh, nowMillis = now)
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
