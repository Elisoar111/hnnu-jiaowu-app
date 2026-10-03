package com.hnnujw.course.review

import com.hnnujw.course.review.ReviewDatabase.BankSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [BankManageLogic] 的 JVM 单测。全部是纯函数，不需要 Robolectric。
 *
 * 重点钉两条「用户会真的踩到」的语义：
 * 1. 校验失败必须能说出**具体原因**（返回枚举而不是 Boolean）；
 * 2. 合并时 qid 冲突必须重分配，**不能覆盖目标库原题** —— 这是合并最容易出的 bug。
 */
class BankManageLogicTest {

    private fun bank(id: String, name: String, count: Int = 0) =
        BankSummary(id = id, name = name, questionCount = count, createdAt = 0L, pendingWrong = 0)

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

    // ── resolveMerge ────────────────────────────────────────────────

    @Test
    fun `merge without source is rejected`() {
        val all = listOf(bank("a", "A"), bank("b", "B"))
        assertTrue(BankManageLogic.resolveMerge("a", emptyList(), all) is BankManageLogic.MergeCheck.NoSource)
    }

    @Test
    fun `merge without target is rejected`() {
        val all = listOf(bank("a", "A"), bank("b", "B"))
        assertTrue(BankManageLogic.resolveMerge("", listOf("b"), all) is BankManageLogic.MergeCheck.NoTarget)
    }

    @Test
    fun `merge target missing is rejected`() {
        val all = listOf(bank("a", "A"), bank("b", "B"))
        assertTrue(BankManageLogic.resolveMerge("zzz", listOf("b"), all) is BankManageLogic.MergeCheck.TargetMissing)
    }

    @Test
    fun `merge target in sources alone is SameBank`() {
        val all = listOf(bank("a", "A"), bank("b", "B"))
        assertTrue(BankManageLogic.resolveMerge("a", listOf("a"), all) is BankManageLogic.MergeCheck.SameBank)
    }

    @Test
    fun `merge target among multiple sources is TargetInSources`() {
        val all = listOf(bank("a", "A"), bank("b", "B"), bank("c", "C"))
        val r = BankManageLogic.resolveMerge("a", listOf("a", "b"), all)
        assertTrue(r is BankManageLogic.MergeCheck.TargetInSources)
    }

    @Test
    fun `merge source missing is rejected`() {
        val all = listOf(bank("a", "A"), bank("b", "B"))
        assertTrue(BankManageLogic.resolveMerge("a", listOf("ghost"), all) is BankManageLogic.MergeCheck.SourceMissing)
    }

    @Test
    fun `merge dedupes and drops blank sources`() {
        val all = listOf(bank("a", "A"), bank("b", "B"), bank("c", "C"))
        val r = BankManageLogic.resolveMerge("a", listOf("b", "", "b", "  ", "c"), all)
        assertTrue(r is BankManageLogic.MergeCheck.Ok)
        assertEquals(listOf("b", "c"), (r as BankManageLogic.MergeCheck.Ok).sources)
    }

    @Test
    fun `merge ok keeps target`() {
        val all = listOf(bank("a", "A"), bank("b", "B"))
        val r = BankManageLogic.resolveMerge("a", listOf("b"), all)
        assertTrue(r is BankManageLogic.MergeCheck.Ok)
        assertEquals("a", (r as BankManageLogic.MergeCheck.Ok).target)
    }

    // ── planQidRemap：合并的核心不变量 ───────────────────────────────

    @Test
    fun `no conflict keeps original qid`() {
        // 无冲突必须保持原 qid，避免无意义 rename（用户看到的题号不该乱跳）
        val remap = BankManageLogic.planQidRemap(setOf("1", "2"), listOf("10", "11", "12"))
        assertEquals(mapOf("10" to "10", "11" to "11", "12" to "12"), remap)
    }

    @Test
    fun `conflict reassigns and never collides with target`() {
        // 目标库已有 qid "1"；源题也叫 "1" → 必须改号，且**不能**是 "1"
        val existing = setOf("1", "2")
        val remap = BankManageLogic.planQidRemap(existing, listOf("1"))
        val assigned = remap.getValue("1")
        assertEquals(false, assigned == "1")
        assertEquals(false, assigned in existing)
    }

    @Test
    fun `conflict reassignment is stable and unique`() {
        val existing = setOf("1")
        // 同一批里两次撞 "1" 只会分配一次（同原 qid 共用新号）
        val remap = BankManageLogic.planQidRemap(existing, listOf("1", "1"))
        assertEquals(1, remap.size)
        assertEquals(remap.getValue("1"), remap.getValue("1"))
    }

    @Test
    fun `two different original qids never map to same new id`() {
        // 目标已有 "1" 与 "1_merge1"：源题 "1" 撞车后必须继续往后找，不能与 1_merge1 再撞
        val existing = setOf("1", "1_merge1")
        val remap = BankManageLogic.planQidRemap(existing, listOf("1"))
        val assigned = remap.getValue("1")
        assertEquals(false, assigned in existing)
    }

    @Test
    fun `remap output values are all distinct`() {
        val existing = setOf("q1", "q2")
        val remap = BankManageLogic.planQidRemap(existing, listOf("q1", "q3", "q2", "q4"))
        // 值集合大小 == 键集合大小，说明没有两个源题落到同一个新 qid
        assertEquals(remap.size, remap.values.toSet().size)
        // 且都不与目标冲突
        assertTrue(remap.values.none { it in existing })
    }

    @Test
    fun `empty incoming yields empty remap`() {
        assertEquals(emptyMap<String, String>(), BankManageLogic.planQidRemap(setOf("1"), emptyList()))
    }

    @Test
    fun `duplicate incoming does not produce duplicate allocation`() {
        val remap = BankManageLogic.planQidRemap(emptySet(), listOf("a", "a", "a"))
        assertEquals(1, remap.size)
        assertEquals("a", remap.getValue("a"))
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

    // ── MergeReport ─────────────────────────────────────────────────

    @Test
    fun `merge report hasRenamed reflects renamedCount`() {
        assertTrue(BankManageLogic.MergeReport(5, 2, listOf("A")).hasRenamed)
        assertEquals(false, BankManageLogic.MergeReport(5, 0, listOf("A")).hasRenamed)
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
