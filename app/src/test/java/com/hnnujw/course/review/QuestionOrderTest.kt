package com.hnnujw.course.review

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 乱序 / 错题优先排序的确定性单测。
 *
 * 进程被杀重建会话依赖"同一批题重抽即同序"——这里钉住稳定与确定两条性质。
 */
class QuestionOrderTest {

    private fun q(bankId: String, id: String) = ReviewQuestion(
        id = id, type = "single", stem = "s", options = listOf("A", "B"),
        answer = "A", chapter = "", explanation = "", source = "", tags = emptyList(),
        bankId = bankId
    )

    @Test
    fun `hashCode 算法按 JLS 规格，跨进程稳定`() {
        // String.hashCode 的多项式算法是 Java 语言规格固定的，钉几个已知值，
        // 防止有人换掉哈希函数破坏"重抽即同序"。
        assertEquals(97, "a".hashCode())
        assertEquals("a".hashCode() * 31 + 'b'.code, "ab".hashCode())
    }

    @Test
    fun `乱序排序确定可重现`() {
        val questions = (1..50).map { q("bank", "q$it") }.asReversed()
        val first = questions.sortedWith(randomOrderComparator())
        // 同一批题排两次结果必须一致（重建会话的确定性依据）。
        val second = questions.sortedWith(randomOrderComparator())
        assertEquals(first.map { it.id }, second.map { it.id })
        // 乱序真的乱：与导入顺序不同（50 题哈希恰好保持原序的概率可忽略）。
        assertTrue(first.map { it.id } != questions.map { it.id })
    }

    @Test
    fun `乱序排序与 qid 形态无关`() {
        // 历史事故：SQL 哈希依赖 TEXT CAST 成数字，非数字 qid 全体退化成 0。
        // 用一组长短、数字字母混合的异质 qid，乱序必须照常生效且确定。
        // ⚠️ 不能用 ab1..ab30 这种连续编号：多项式哈希对"同前缀+递增数字"恰好保序，
        // 断言"乱序 ≠ 原序"会翻车。
        val ids = listOf("ab5", "zz1", "mc7", "ab19", "q3", "zz28", "k11", "ab2", "f30", "zz15", "p8", "ab22")
        val questions = ids.map { q("bank", it) }
        val sorted = questions.sortedWith(randomOrderComparator())
        assertEquals(sorted.map { it.id }, questions.sortedWith(randomOrderComparator()).map { it.id })
        assertTrue(sorted.map { it.id } != questions.map { it.id })
    }

    @Test
    fun `错题优先 - 未掌握的排前面且组内乱序`() {
        val wrong = setOf("bank::q3", "bank::q7", "bank::q1")
        val questions = (1..20).map { q("bank", "q$it") }
        val sorted = questions.sortedWith(wrongFirstOrderComparator(wrong))
        assertEquals(setOf("q1", "q3", "q7"), sorted.take(3).map { it.id }.toSet())
        // 排两次一致。
        assertEquals(sorted.map { it.id }, questions.sortedWith(wrongFirstOrderComparator(wrong)).map { it.id })
    }

    @Test
    fun `错题优先 - 空集合退化为纯乱序`() {
        val questions = (1..20).map { q("bank", "q$it") }
        val sorted = questions.sortedWith(wrongFirstOrderComparator(emptySet()))
        assertEquals(questions.sortedWith(randomOrderComparator()).map { it.id }, sorted.map { it.id })
    }
}
