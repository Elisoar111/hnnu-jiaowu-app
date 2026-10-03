package com.hnnujw.course.review

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 题库详情页纯逻辑的回归测试。
 *
 * 覆盖三块容易差一位/越界的映射：
 *  - 「全部章节」(null) + 章节列表 → 选项列表；
 *  - 选中下标 → 传给 pickQuestions 的 chapter；
 *  - 练习模式 ↔ 分段控件下标的互转（保证 pickQuestions 拿到正确模式）。
 */
class BankDetailLogicTest {

    private val chapters = listOf("绪论" to 10, "第一章" to 20, "第二章" to 5)

    @Test
    fun `全部章节是首项且 value 为 null`() {
        val options = chapterOptions(chapters)
        assertEquals(ALL_CHAPTERS_LABEL, options.first().label)
        assertNull(options.first().value)
    }

    @Test
    fun `全部章节的题数是各章之和`() {
        val options = chapterOptions(chapters)
        assertEquals(35, options.first().count)
    }

    @Test
    fun `章节顺序与入参一致`() {
        val options = chapterOptions(chapters)
        assertEquals(
            listOf("绪论", "第一章", "第二章"),
            options.drop(1).map { it.value }
        )
        assertEquals(listOf(10, 20, 5), options.drop(1).map { it.count })
    }

    @Test
    fun `空章节只留全部章节一项`() {
        val options = chapterOptions(emptyList())
        assertEquals(1, options.size)
        assertNull(options.first().value)
        assertEquals(0, options.first().count)
    }

    @Test
    fun `选全部章节时 chapter 传 null`() {
        val options = chapterOptions(chapters)
        assertNull(resolveSelectedChapter(options, 0))
    }

    @Test
    fun `选具体章节时 chapter 传该章名`() {
        val options = chapterOptions(chapters)
        assertEquals("第一章", resolveSelectedChapter(options, 2))
    }

    @Test
    fun `下标越界时回退到 null 全部章节`() {
        val options = chapterOptions(chapters)
        assertNull(resolveSelectedChapter(options, -1))
        assertNull(resolveSelectedChapter(options, 99))
        assertNull(resolveSelectedChapter(emptyList(), 5))
    }

    @Test
    fun `三个练习模式往返映射一致`() {
        ReviewMode.entries.forEach { mode ->
            assertEquals(mode, modeAt(modeIndex(mode)))
        }
    }

    @Test
    fun `modeAt 越界回退到顺序练习`() {
        assertEquals(ReviewMode.SEQUENTIAL, modeAt(-1))
        assertEquals(ReviewMode.SEQUENTIAL, modeAt(ReviewMode.entries.size))
    }

    @Test
    fun `modeIndex 覆盖全部模式且互不相同`() {
        val indices = ReviewMode.entries.map { modeIndex(it) }
        assertEquals(ReviewMode.entries.size, indices.toSet().size)
        assertTrue(indices.all { it >= 0 })
    }

    @Test
    fun `正确率正常换算并四舍五入`() {
        assertEquals(50, accuracyPercent(correct = 1, answered = 2))
        assertEquals(67, accuracyPercent(correct = 2, answered = 3))
        assertEquals(100, accuracyPercent(correct = 5, answered = 5))
    }

    @Test
    fun `未作答时正确率为零而不是 NaN`() {
        assertEquals(0, accuracyPercent(correct = 0, answered = 0))
        assertEquals(0, accuracyPercent(correct = 3, answered = -1))
    }

    @Test
    fun `答对数超出已答数时被夹到范围`() {
        // 脏数据兜底：不允许出现 >100%。
        assertEquals(100, accuracyPercent(correct = 99, answered = 10))
    }
}
