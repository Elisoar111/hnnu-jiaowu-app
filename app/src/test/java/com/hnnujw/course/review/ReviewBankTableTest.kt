package com.hnnujw.course.review

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 表格题库（Excel / CSV / TSV）解析的回归。
 *
 * 这层最容易出问题的地方不是"解析不出来"，而是**认错列**：把「解析」当成「答案」、
 * 把选项列当成题干 —— 导进去的题看着正常，判分却全错，而且用户完全不知道发生了
 * 什么。所以每个用例都盯住"哪个值落到了哪个字段"。
 */
class ReviewBankTableTest {

    // —— 基本表头

    @Test
    fun `中文表头能被认出`() {
        val rows = listOf(
            listOf("题干", "选项", "答案", "章节", "解析"),
            listOf("1+1=?", "1|2|3", "B", "第一章", "显然")
        )
        val bank = ReviewBankTable.parseRows(rows, "测试")
        assertEquals(1, bank.questions.size)
        val q = bank.questions.single()
        assertEquals("1+1=?", q.stem)
        assertEquals(listOf("1", "2", "3"), q.options)
        assertEquals("B", q.answer)
        assertEquals("第一章", q.chapter)
        assertEquals("显然", q.explanation)
        assertTrue(bank.errors.isEmpty())
    }

    @Test
    fun `英文表头也能认出`() {
        val rows = listOf(
            listOf("stem", "options", "answer", "chapter"),
            listOf("hello?", "a|b", "A", "ch1")
        )
        val bank = ReviewBankTable.parseRows(rows, "en")
        assertEquals(1, bank.questions.size)
        assertEquals("hello?", bank.questions.single().stem)
    }

    /** 表头顺序可以任意，甚至「答案」在「题干」前面。 */
    @Test
    fun `列顺序任意`() {
        val rows = listOf(
            listOf("答案", "解析", "题干", "选项"),
            listOf("C", "因为", "选出正确项", "甲|乙|丙")
        )
        val q = ReviewBankTable.parseRows(rows).questions.single()
        assertEquals("选出正确项", q.stem)
        assertEquals("C", q.answer)
        assertEquals(3, q.options.size)
    }

    // —— 选项的两种摆法

    @Test
    fun `A到D分列时能拼成选项列表`() {
        val rows = listOf(
            listOf("题干", "A", "B", "C", "D", "答案"),
            listOf("哪一个是偶数", "1", "2", "3", "5", "B")
        )
        val q = ReviewBankTable.parseRows(rows).questions.single()
        assertEquals(listOf("1", "2", "3", "5"), q.options)
        assertEquals("B", q.answer)
    }

    @Test
    fun `选项列里的分隔符支持竖线分号与顿号变体`() {
        val rows = listOf(
            listOf("题干", "选项", "答案"),
            listOf("题一", "甲|乙|丙", "A"),
            listOf("题二", "甲；乙；丙", "B"),
            listOf("题三", "甲;乙;丙", "C")
        )
        val bank = ReviewBankTable.parseRows(rows)
        assertEquals(3, bank.questions.size)
        bank.questions.forEach { assertEquals(3, it.options.size) }
    }

    // —— 题型

    @Test
    fun `未写题型时按答案长度推断多选`() {
        val rows = listOf(
            listOf("题干", "选项", "答案"),
            listOf("单选", "甲|乙", "A"),
            listOf("多选", "甲|乙|丙", "AB")
        )
        val bank = ReviewBankTable.parseRows(rows)
        assertEquals(ReviewQuestion.TYPE_SINGLE, bank.questions[0].type)
        assertEquals(ReviewQuestion.TYPE_MULTI, bank.questions[1].type)
    }

    @Test
    fun `显式题型压过推断`() {
        val rows = listOf(
            listOf("题干", "选项", "答案", "题型"),
            listOf("题", "甲|乙|丙", "AB", "多选")
        )
        assertEquals(ReviewQuestion.TYPE_MULTI, ReviewBankTable.parseRows(rows).questions.single().type)
    }

    // —— 脏数据

    @Test
    fun `缺表头时给出可读错误而不是抛异常`() {
        val bank = ReviewBankTable.parseRows(listOf(listOf("随便", "写点", "什么")))
        assertTrue(bank.questions.isEmpty())
        assertTrue(bank.errors.single().contains("表头"))
    }

    @Test
    fun `整行空白静默跳过不算错误`() {
        val rows = listOf(
            listOf("题干", "选项", "答案"),
            listOf("题", "甲|乙", "A"),
            listOf("", "", ""),
            listOf("  ", " ")
        )
        val bank = ReviewBankTable.parseRows(rows)
        assertEquals(1, bank.questions.size)
        assertTrue(bank.errors.isEmpty())
    }

    @Test
    fun `答案超出选项范围的行被跳过并报行号`() {
        val rows = listOf(
            listOf("题干", "选项", "答案"),
            listOf("正常", "甲|乙", "A"),
            listOf("越界", "甲|乙", "D")
        )
        val bank = ReviewBankTable.parseRows(rows)
        assertEquals(1, bank.questions.size)
        assertTrue("错误里要带行号方便用户回去改", bank.errors.single().contains("第 3 行"))
    }

    @Test
    fun `选项少于两个的行被跳过`() {
        val rows = listOf(
            listOf("题干", "选项", "答案"),
            listOf("只有一项", "甲", "A")
        )
        val bank = ReviewBankTable.parseRows(rows)
        assertTrue(bank.questions.isEmpty())
        assertTrue(bank.errors.isNotEmpty())
    }

    @Test
    fun `题干为空的行被跳过`() {
        val rows = listOf(
            listOf("题干", "选项", "答案"),
            listOf("", "甲|乙", "A")
        )
        val bank = ReviewBankTable.parseRows(rows)
        assertTrue(bank.questions.isEmpty())
        assertTrue(bank.errors.single().contains("题干为空"))
    }

    @Test
    fun `缺章节时落到未分类`() {
        val rows = listOf(
            listOf("题干", "选项", "答案"),
            listOf("题", "甲|乙", "A")
        )
        assertEquals("未分类", ReviewBankTable.parseRows(rows).questions.single().chapter)
    }

    @Test
    fun `没有 id 列时生成不重复的 id`() {
        val rows = listOf(
            listOf("题干", "选项", "答案"),
            listOf("题一", "甲|乙", "A"),
            listOf("题二", "甲|乙", "B")
        )
        val ids = ReviewBankTable.parseRows(rows).questions.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    // —— CSV / TSV 文本

    @Test
    fun `逗号分隔的 CSV 能解析`() {
        val text = "题干,选项,答案\n1+1,1|2|3,B"
        val bank = ReviewBankTable.parseDelimited(text)
        assertEquals(1, bank.questions.size)
        assertEquals("B", bank.questions.single().answer)
    }

    @Test
    fun `引号里的逗号与换行不会把一列拆开`() {
        val text = "题干,选项,答案\n\"a,b 哪个对\",\"甲,乙|丙\",A"
        val q = ReviewBankTable.parseDelimited(text).questions.single()
        assertEquals("a,b 哪个对", q.stem)
        assertEquals(listOf("甲,乙", "丙"), q.options)
    }

    @Test
    fun `双引号转义成一个字面引号`() {
        val text = "题干,选项,答案\n\"他说\"\"你好\"\"\",甲|乙,A"
        assertEquals("他说\"你好\"", ReviewBankTable.parseDelimited(text).questions.single().stem)
    }

    @Test
    fun `制表符分隔的 TSV 能自动识别`() {
        val text = "题干\t选项\t答案\n题\t甲|乙\tA"
        assertEquals(1, ReviewBankTable.parseDelimited(text).questions.size)
    }

    @Test
    fun `BOM 会被剥掉`() {
        val text = "\uFEFF题干,选项,答案\n题,甲|乙,A"
        assertEquals(1, ReviewBankTable.parseDelimited(text).questions.size)
    }

    @Test
    fun `CRLF 换行不会留下回车`() {
        val text = "题干,选项,答案\r\n题,甲|乙,A\r\n"
        val q = ReviewBankTable.parseDelimited(text).questions.single()
        assertEquals("题", q.stem)
        assertEquals("A", q.answer)
    }

    @Test
    fun `空文件返回可读错误`() {
        val bank = ReviewBankTable.parseDelimited("   ")
        assertTrue(bank.questions.isEmpty())
        assertTrue(bank.errors.single().contains("为空"))
    }
}
