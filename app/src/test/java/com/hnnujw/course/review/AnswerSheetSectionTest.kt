package com.hnnujw.course.review

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 答题卡按章节分组的回归。
 *
 * 分组只影响**展示**，但一旦把 `index` 弄错，用户点第 12 格会跳到第 7 题 ——
 * 这类错误不崩不报错，只能靠单测钉住"格子下标与题目下标严格一致"。
 */
class AnswerSheetSectionTest {

    private fun cell(index: Int) = answerSheetCell(
        index = index,
        answered = false,
        judged = false,
        correct = false,
        isCurrent = false
    )

    private fun cells(n: Int) = (0 until n).map { cell(it) }

    @Test
    fun `同一章聚成一段且保持原顺序`() {
        val sections = answerSheetSections(
            chapters = listOf("第一章", "第一章", "第二章"),
            cells = cells(3)
        )
        assertEquals(2, sections.size)
        assertEquals("第一章", sections[0].chapter)
        assertEquals(listOf(0, 1), sections[0].cells.map { it.index })
        assertEquals("第二章", sections[1].chapter)
        assertEquals(listOf(2), sections[1].cells.map { it.index })
    }

    /** 乱序练习会把不同章节的题穿插在一起：分组后仍必须按**首次出现**排段。 */
    @Test
    fun `章节交错时按首次出现排段`() {
        val sections = answerSheetSections(
            chapters = listOf("乙", "甲", "乙", "甲"),
            cells = cells(4)
        )
        assertEquals(listOf("乙", "甲"), sections.map { it.chapter })
        assertEquals(listOf(0, 2), sections[0].cells.map { it.index })
        assertEquals(listOf(1, 3), sections[1].cells.map { it.index })
    }

    @Test
    fun `空章节名也自成一段而不是被丢掉`() {
        val sections = answerSheetSections(
            chapters = listOf("第一章", "", "第一章"),
            cells = cells(3)
        )
        assertEquals(listOf("第一章", ""), sections.map { it.chapter })
        assertEquals(listOf(1), sections[1].cells.map { it.index })
    }

    @Test
    fun `所有格子都必须恰好出现在一个段里`() {
        val chapters = listOf("甲", "乙", "丙", "甲", "乙")
        val list = cells(5)
        val sections = answerSheetSections(chapters, list)
        val flat = sections.flatMap { it.cells }.map { it.index }
        // 分组必然改变展示顺序（这正是它的作用），但**集合与数量**必须保持一致：
        // 少一格 = 有一题在答题卡上点不到；多一格 = 点进去会跳到不存在的题。
        assertEquals("每个下标恰好出现一次", list.map { it.index }.sorted(), flat.sorted())
        assertEquals(list.size, sections.sumOf { it.cells.size })
    }

    @Test
    fun `没有格子时返回空分段`() {
        assertTrue(answerSheetSections(emptyList(), emptyList()).isEmpty())
    }

    /** 章节列表比格子短（防御性）：缺失的按"未分章"处理，不能抛。 */
    @Test
    fun `章节列表短于格子时不抛异常`() {
        val sections = answerSheetSections(chapters = listOf("第一章"), cells = cells(3))
        assertEquals(2, sections.size)
        assertEquals("", sections[1].chapter)
    }
}
