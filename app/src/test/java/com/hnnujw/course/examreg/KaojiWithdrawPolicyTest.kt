package com.hnnujw.course.examreg

import com.hnnujw.course.xuegong.parseSiteTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 「能不能退报」判定回归。
 *
 * 这段口径原先长在 Composable 里、还压成了一个 Boolean，于是界面把
 * 「已缴费」也印成「已过报名截止时间，不支持退报」—— 用户会以为是自己错过了时间。
 * 抽成 [KaojiWithdrawPolicy] 之后，四种情形各说各的话，也终于可以单测。
 *
 * 服务端权威规则来自 `xskjbm.js`（实测复刻）：
 * `sfqr == '1' || sfzfzzt == '1'` ⇒ 「该项目已经缴费或正在缴费，无法退报！」。
 */
class KaojiWithdrawPolicyTest {

    private val deadlineText = "2026-09-24 23:00:00"
    private val deadline = parseSiteTime(deadlineText)!!

    private fun project(id: String = "P1", endTime: String = deadlineText) = KaojiProject(
        id = id, title = "第1批次，普通话测试", categoryId = "XM03", batch = "1",
        beginTime = "2026-09-07 08:00:00", endTime = endTime,
        remainDaysText = "", remainSeatsText = "", feeText = "", notice = "", canRegister = true,
    )

    private fun record(
        projectId: String = "P1",
        payment: String = "",
        audit: String = "",
    ) = KaojiRegistered(
        id = "R1", projectId = projectId, category = "普通话测试", name = "普通话测试",
        fee = "25.00", registeredAt = "2026-09-10 10:00:00",
        examBegin = "", examEnd = "", ticketNo = "", certNo = "", score = "", term = "",
        phone = "", paymentStatus = payment, auditStatus = audit,
    )

    @Test
    fun `unpaid record inside the window can be withdrawn`() {
        assertNull(KaojiWithdrawPolicy.blockReason(record(payment = "0"), listOf(project()), deadline - 1_000))
    }

    @Test
    fun `not-applicable payment also allows withdrawal`() {
        // sfqr 缺失 ≠ 未缴：这一条不涉及缴费，当然能退
        assertNull(KaojiWithdrawPolicy.blockReason(record(payment = ""), listOf(project()), deadline - 1_000))
    }

    @Test
    fun `paid record is refused with the real reason, not a deadline excuse`() {
        val reason = KaojiWithdrawPolicy.blockReason(record(payment = "1"), listOf(project()), deadline - 1_000)
        assertEquals("已缴费，不支持退报", reason)
    }

    @Test
    fun `paid record is refused even after the deadline too (reason stays about payment)`() {
        // 两个拦截条件都成立时，说的是"已缴费"—— 这是用户唯一能去处理的那条
        assertEquals(
            "已缴费，不支持退报",
            KaojiWithdrawPolicy.blockReason(record(payment = "1"), listOf(project()), deadline + 1_000),
        )
    }

    @Test
    fun `record past the deadline is refused with the deadline reason`() {
        assertEquals(
            "已过报名截止时间，不支持退报",
            KaojiWithdrawPolicy.blockReason(record(), listOf(project()), deadline + 1_000),
        )
    }

    @Test
    fun `batch no longer in the open list is refused, and says so`() {
        // 往期学期的记录：批次不在当前类别里。以前这种情况也会被印成"已过截止时间"
        assertEquals(
            "该批次已不在本学期开放列表中，不支持退报",
            KaojiWithdrawPolicy.blockReason(record(projectId = "OLD"), listOf(project()), deadline - 1_000),
        )
    }

    @Test
    fun `unparsable deadline lets the request through`() {
        // 解析不出来时宁可多问一次服务端，也别武断地把入口藏掉
        assertNull(KaojiWithdrawPolicy.blockReason(record(), listOf(project(endTime = "")), deadline + 1_000))
        assertNull(
            KaojiWithdrawPolicy.blockReason(
                record(), listOf(project(endTime = "另行通知")), deadline + 1_000
            )
        )
    }

    @Test
    fun `empty project list blocks everything except reporting the batch reason`() {
        // page 还没加载出来时（projects 为空）不该给退报入口
        assertEquals(
            "该批次已不在本学期开放列表中，不支持退报",
            KaojiWithdrawPolicy.blockReason(record(), emptyList(), deadline - 1_000),
        )
    }
}
