package com.hnnujw.course.review

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [BankManageLogic] 的 JVM 单测。全部是纯函数，不需要 Robolectric。
 *
 * 重点钉住「用户会真的踩到」的语义：校验失败必须能说出**具体原因**
 * （返回枚举而不是 Boolean）。
 */
class BankManageLogicTest {

    // ── validateBankName ────────────────────────────────────────────

    @Test
    fun `name empty is rejected`() {
        assertTrue(BankManageLogic.validateBankName("", emptySet()) is BankManageLogic.NameCheck.Empty)
        assertTrue(BankManageLogic.validateBankName("   ", emptySet()) is BankManageLogic.NameCheck.Empty)
    }

    @Test
    fun `name too long is rejected`() {
        val long = "题".repeat(BankManageLogic.MAX_NAME_LENGTH + 1)
        assertTrue(BankManageLogic.validateBankName(long, emptySet()) is BankManageLogic.NameCheck.TooLong)
    }

    @Test
    fun `name at max length is ok`() {
        val exact = "题".repeat(BankManageLogic.MAX_NAME_LENGTH)
        val r = BankManageLogic.validateBankName(exact, emptySet())
        assertTrue(r is BankManageLogic.NameCheck.Ok)
        assertEquals(exact, (r as BankManageLogic.NameCheck.Ok).trimmed)
    }

    @Test
    fun `duplicate name is rejected`() {
        val existing = setOf("计算机网络", "数据库")
        assertTrue(BankManageLogic.validateBankName("计算机网络", existing) is BankManageLogic.NameCheck.Duplicate)
    }

    @Test
    fun `duplicate ignores surrounding whitespace`() {
        // 「 数据库 」与「数据库」视为同名 —— 磁盘上不该并存两个只差空白的库
        val existing = setOf("数据库")
        assertTrue(BankManageLogic.validateBankName("  数据库 ", existing) is BankManageLogic.NameCheck.Duplicate)
    }

    @Test
    fun `valid name returns trimmed`() {
        val r = BankManageLogic.validateBankName("  操作系统  ", setOf("计算机网络"))
        assertTrue(r is BankManageLogic.NameCheck.Ok)
        assertEquals("操作系统", (r as BankManageLogic.NameCheck.Ok).trimmed)
    }

    @Test
    fun `each failure carries a specific reason`() {
        // 红线：界面要能显示具体原因，而不是所有失败印同一句话
        assertTrue(BankManageLogic.NameCheck.Empty.message.isNotBlank())
        assertTrue(BankManageLogic.NameCheck.TooLong.message.isNotBlank())
        assertTrue(BankManageLogic.NameCheck.Duplicate.message.isNotBlank())
        assertEquals("", BankManageLogic.NameCheck.Ok("x").message)
    }

    // ── deleteQuestionsHint ─────────────────────────────────────────

    @Test
    fun `delete hint is blank for zero`() {
        assertEquals("", BankManageLogic.deleteQuestionsHint(0))
        assertEquals("", BankManageLogic.deleteQuestionsHint(-1))
    }

    @Test
    fun `delete hint mentions count`() {
        assertTrue(BankManageLogic.deleteQuestionsHint(3).contains("3"))
    }

    // ── selectAll / invertSelection ─────────────────────────────────

    @Test
    fun `select all returns every id in order`() {
        val all = listOf("q1", "q2", "q3")
        assertEquals(all, BankManageLogic.selectAll(all, emptySet()))
        // 已选不影响全选结果
        assertEquals(all, BankManageLogic.selectAll(all, setOf("q2")))
    }

    @Test
    fun `select all on empty bank yields empty`() {
        assertEquals(emptyList<String>(), BankManageLogic.selectAll(emptyList(), setOf("q1")))
    }

    @Test
    fun `invert picks exactly the unselected ids`() {
        val all = listOf("q1", "q2", "q3", "q4")
        assertEquals(listOf("q2", "q4"), BankManageLogic.invertSelection(all, setOf("q1", "q3")))
    }

    @Test
    fun `invert keeps all-ids order rather than selection order`() {
        // 用户按 q3→q1 的顺序勾选，反选结果仍按题库顺序，不让列表乱跳
        val all = listOf("q1", "q2", "q3")
        assertEquals(listOf("q2"), BankManageLogic.invertSelection(all, listOf("q3", "q1")))
    }

    @Test
    fun `invert with full selection yields empty and vice versa`() {
        val all = listOf("q1", "q2")
        assertEquals(emptyList<String>(), BankManageLogic.invertSelection(all, all))
        assertEquals(all, BankManageLogic.invertSelection(all, emptyList()))
    }

    @Test
    fun `invert ignores selected ids that no longer exist`() {
        // 选中项里混着已删除的 qid：反选只看「在 allIds 里但没被选中」的
        val all = listOf("q1", "q2")
        assertEquals(listOf("q2"), BankManageLogic.invertSelection(all, setOf("q1", "ghost")))
    }
}
