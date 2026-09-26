package com.hnnujw.course.ykt

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 一卡通逐笔流水（账单）解析与展示口径回归。
 *
 * ## 为什么单独建一个测试类
 *
 * 逐笔明细来自**第三套前端** `/campus-card-pc/` 的
 * `GET /berserker-search/search/personal/turnover`，字段口径与 SPA(`/plat/`)
 * 完全不同（字段名、单位、可空性都不同），混在 [YktBalanceTest] 里会被淹没。
 *
 * ## 三条最易错的契约（改动前先看这里）
 * 1. **金额单位是"分"**：`tranamt=1` 是 ¥0.01，不是 ¥1。
 * 2. **`cardBalance` 缺失 ≠ 0**：缺失要给 `null`（界面不显示），给 0 会被读成"钱花光了"。
 * 3. **收支方向只看 `typeFrom`**：`"1"`=收入，其余（含空）=支出。
 */
class YktBillPageTest {

    private fun record(
        orderId: String = "T1",
        time: String = "2026-09-25 12:30:00",
        amount: Long = 100L,
        resume: String = "食堂消费",
        typeFrom: String = "0",
        cardBalance: Long? = null,
    ): JSONObject {
        val obj = JSONObject()
        obj.put("orderId", orderId)
        obj.put("jndatetimeStr", time)
        obj.put("tranamt", amount)
        obj.put("resume", resume)
        obj.put("typeFrom", typeFrom)
        if (cardBalance == null) obj.put("cardBalance", JSONObject.NULL) else obj.put("cardBalance", cardBalance)
        return obj
    }

    private fun page(vararg records: JSONObject, total: Int = records.size, current: Int = 1, pages: Int = 1): JSONObject {
        val data = JSONObject()
        data.put("total", total)
        data.put("current", current)
        data.put("pages", pages)
        data.put("records", JSONArray().apply { records.forEach { put(it) } })
        return JSONObject().put("code", 200).put("data", data)
    }

    // ── 金额单位 ────────────────────────────────────────────────────────

    @Test
    fun `tranamt 是分而不是元`() {
        val parsed = YktBalance.parseBillPage(page(record(amount = 1L)))
        assertEquals(0.01, parsed.records[0].amountYuan, 0.0001)
    }

    @Test
    fun `支出带负号收入带正号`() {
        val parsed = YktBalance.parseBillPage(
            page(record(typeFrom = "0", amount = 2340L), record(typeFrom = "1", amount = 3000L))
        )
        assertEquals("-23.40", parsed.records[0].signedAmount)
        assertEquals("+30.00", parsed.records[1].signedAmount)
    }

    @Test
    fun `typeFrom 非 1 一律按支出`() {
        // 服务端可能给 "0"、"" 或其它值，只有 "1" 才算收入
        val parsed = YktBalance.parseBillPage(
            page(record(typeFrom = "0"), record(typeFrom = ""), record(typeFrom = "2"))
        )
        assertFalse(parsed.records[0].isIncome)
        assertFalse(parsed.records[1].isIncome)
        assertFalse(parsed.records[2].isIncome)
    }

    // ── 可空字段：缺失 ≠ 0 ──────────────────────────────────────────────

    @Test
    fun `cardBalance 缺失时是 null 而不是 0`() {
        val parsed = YktBalance.parseBillPage(page(record(cardBalance = null)))
        assertNull(parsed.records[0].cardBalanceCent)
        assertNull(parsed.records[0].cardBalanceYuan)
    }

    @Test
    fun `cardBalance 为 0 时要保留 0`() {
        val parsed = YktBalance.parseBillPage(page(record(cardBalance = 0L)))
        assertEquals(0L, parsed.records[0].cardBalanceCent)
        assertEquals(0.0, parsed.records[0].cardBalanceYuan!!, 0.0001)
    }

    // ── 分页 ────────────────────────────────────────────────────────────

    @Test
    fun `hasMore 使用服务端的 current 与 pages`() {
        val more = YktBalance.parseBillPage(page(record(), total = 1002, current = 1, pages = 51))
        assertTrue(more.hasMore)
        val last = YktBalance.parseBillPage(page(record(), total = 1002, current = 51, pages = 51))
        assertFalse(last.hasMore)
    }

    @Test
    fun `空 data 与空 records 都给空页而不是崩`() {
        assertEquals(0, YktBalance.parseBillPage(null).records.size)
        assertEquals(0, YktBalance.parseBillPage(JSONObject()).records.size)
        val noRecords = JSONObject().put("data", JSONObject())
        assertTrue(YktBalance.parseBillPage(noRecords).isEmpty)
    }

    // ── 文本归一（服务端会给字面量 "null"）──────────────────────────────

    @Test
    fun `字面量 null 与 JSON null 都归一成空串`() {
        val item = JSONObject()
        item.put("resume", "null")
        item.put("payName", JSONObject.NULL)
        assertEquals("", YktBalance.billText(item, "resume"))
        assertEquals("", YktBalance.billText(item, "payName"))
    }

    // ── 派生展示字段 ────────────────────────────────────────────────────

    @Test
    fun `displayTitle 摘要优先退回支付方式`() {
        val parsed = YktBalance.parseBillPage(
            page(record(resume = ""), record(resume = "网络充值"))
        )
        assertEquals("交易", parsed.records[0].displayTitle)
        assertEquals("网络充值", parsed.records[1].displayTitle)
    }

    @Test
    fun `tradeDate 与 tradeClock 从时间串切分`() {
        val parsed = YktBalance.parseBillPage(page(record(time = "2026-09-25 12:30:00")))
        assertEquals("2026-09-25", parsed.records[0].tradeDate)
        assertEquals("12:30", parsed.records[0].tradeClock)
    }

    @Test
    fun `时间串缺时刻时不崩且日期仍可取`() {
        val parsed = YktBalance.parseBillPage(page(record(time = "2026-09-25")))
        assertEquals("2026-09-25", parsed.records[0].tradeDate)
        assertEquals("", parsed.records[0].tradeClock)
    }
}
