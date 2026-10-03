package com.hnnujw.course.review

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 题库解析与校验的单元测试。
 *
 * 参照 D:\zhiyu 的 question.dart 行为对齐：同样的输入必须得到同样的题目与同样的
 * 错误描述，否则"从知隅导出的题库在这边导入失败"这种问题会很难查。
 */
class ReviewQuestionTest {

    private fun bankJson(vararg questions: JSONObject) = JSONObject().apply {
        put("version", 2)
        put("name", "测试题库")
        put("questions", JSONArray().apply { questions.forEach { put(it) } })
    }.toString()

    private fun q(
        id: String = "1",
        type: String = "single",
        stem: String = "题干",
        options: List<String> = listOf("甲", "乙", "丙", "丁"),
        answer: String = "A",
        chapter: String = "第一章"
    ) = JSONObject().apply {
        put("id", id)
        put("type", type)
        put("stem", stem)
        put("options", JSONArray(options))
        put("answer", answer)
        put("chapter", chapter)
    }

    // —— 归一化

    @Test
    fun `答案归一化只保留大写字母`() {
        assertEquals("AC", normalizeAnswer("a、c"))
        assertEquals("ABD", normalizeAnswer("ABD"))
        // 题库里混入整句答案时只取字母，不把脏数据当答案
        assertEquals("AB", normalizeAnswer("正确答案：AB（多选）"))
        assertEquals("", normalizeAnswer("无"))
    }

    @Test
    fun `题型归一化兼容多种写法`() {
        assertEquals(ReviewQuestion.TYPE_SINGLE, normalizeType("single"))
        assertEquals(ReviewQuestion.TYPE_SINGLE, normalizeType("单选题"))
        assertEquals(ReviewQuestion.TYPE_SINGLE, normalizeType("单项选择"))
        assertEquals(ReviewQuestion.TYPE_SINGLE, normalizeType("1"))
        assertEquals(ReviewQuestion.TYPE_MULTI, normalizeType("multi"))
        assertEquals(ReviewQuestion.TYPE_MULTI, normalizeType("多选题"))
        assertEquals(ReviewQuestion.TYPE_MULTI, normalizeType("多项选择"))
        assertEquals(ReviewQuestion.TYPE_MULTI, normalizeType("2"))
        assertNull(normalizeType("判断题"))
    }

    // —— 解析

    @Test
    fun `解析标准结构并读取库名`() {
        val bank = parseJsonBank(bankJson(q(), q(id = "2", answer = "C")))
        assertTrue(bank.isValid)
        assertEquals("测试题库", bank.name)
        assertEquals(2, bank.questions.size)
        assertEquals("C", bank.questions[1].answer)
    }

    @Test
    fun `解析兼容纯中文顶层键`() {
        val content = """
            {"题目库":"中文键名","题目":[{"id":"1","题型":"单选","题干":"题干",
            "选项":["甲","乙"],"答案":"B","章节":"章"}]}
        """.trimIndent()
        val bank = parseJsonBank(content)
        assertTrue(bank.isValid)
        assertEquals("中文键名", bank.name)
        assertEquals(1, bank.questions.size)
        assertEquals("B", bank.questions.single().answer)
    }

    @Test
    fun `解析顶层为数组时用兜底名`() {
        val content = JSONArray().apply { put(q()) }.toString()
        val bank = parseJsonBank(content, fallbackName = "我的题库")
        assertEquals("我的题库", bank.name)
        assertEquals(1, bank.questions.size)
    }

    @Test
    fun `解析兼容中文键名`() {
        val content = """
            {"题目库":"中文键","题目":[{"id":"9","题型":"多选","题干":"中文题干",
            "选项":["一","二","三"],"答案":"AC","章节":"第二章","解析":"中文解析","来源":"教材"}]}
        """.trimIndent()
        val bank = parseJsonBank(content)
        assertTrue(bank.isValid)
        val question = bank.questions.single()
        assertEquals("中文题干", question.stem)
        assertEquals("AC", question.answer)
        assertEquals("第二章", question.chapter)
        assertEquals("中文解析", question.explanation)
        assertEquals("教材", question.source)
        assertTrue(question.isMulti)
    }

    @Test
    fun `解析兼容管道符分隔的选项`() {
        val content = """
            {"questions":[{"id":"1","stem":"s","options":"甲|乙|丙|丁","answer":"B"}]}
        """.trimIndent()
        val bank = parseJsonBank(content)
        assertTrue(bank.isValid)
        assertEquals(listOf("甲", "乙", "丙", "丁"), bank.questions.single().options)
    }

    @Test
    fun `非法 JSON 返回错误而不是抛异常`() {
        val bank = parseJsonBank("{ 这不是 json")
        assertFalse(bank.isValid)
        assertTrue(bank.questions.isEmpty())
    }

    @Test
    fun `空文件给出明确原因`() {
        val bank = parseJsonBank("   ")
        assertFalse(bank.isValid)
        assertEquals("文件内容为空", bank.errors.single())
    }

    @Test
    fun `缺 questions 键视为空题库`() {
        val bank = parseJsonBank("""{"name":"空的"}""")
        assertFalse(bank.isValid)
        assertEquals("题库里没有可用的题目", bank.errors.single())
    }

    // —— 校验

    @Test
    fun `坏题被丢弃并报告序号`() {
        val content = bankJson(q(id = "1"), q(id = "2", answer = "Z"))
        val bank = parseJsonBank(content)
        assertEquals(1, bank.questions.size)
        assertEquals(1, bank.errors.size)
        assertTrue(bank.errors.single().contains("第 2 条"))
        assertTrue(bank.errors.single().contains("id=2"))
    }

    @Test
    fun `校验拦截各类坏题`() {
        assertTrue(q(stem = " ").let { ReviewQuestion.fromJson(it).validate() }.isNotEmpty())
        assertTrue(
            q(options = listOf("只有一个")).let { ReviewQuestion.fromJson(it).validate() }
                .any { it.contains("选项少于 2") }
        )
        assertTrue(
            q(answer = "A").let { ReviewQuestion("x", "unknown", "s", listOf("甲", "乙"), "A", "c").validate() }
                .any { it.contains("题型不合法") }
        )
        // 单选给了多个答案
        assertTrue(
            ReviewQuestion("x", ReviewQuestion.TYPE_SINGLE, "s", listOf("甲", "乙", "丙"), "AB", "c")
                .validate().any { it.contains("单选题答案包含多个选项") }
        )
        // 多选只给一个答案
        assertTrue(
            ReviewQuestion("x", ReviewQuestion.TYPE_MULTI, "s", listOf("甲", "乙", "丙"), "A", "c")
                .validate().any { it.contains("至少包含 2") }
        )
        // 答案超出选项范围
        assertTrue(
            ReviewQuestion("x", ReviewQuestion.TYPE_SINGLE, "s", listOf("甲", "乙"), "C", "c")
                .validate().any { it.contains("超出选项范围") }
        )
    }

    @Test
    fun `正常题目校验通过`() {
        assertTrue(
            ReviewQuestion("x", ReviewQuestion.TYPE_MULTI, "s", listOf("甲", "乙", "丙", "丁"), "ABD", "c")
                .validate().isEmpty()
        )
    }

    // —— 往返

    @Test
    fun `题目序列化后可原样解析回来`() {
        val original = ReviewQuestion(
            id = "12", type = ReviewQuestion.TYPE_MULTI, stem = "题干", options = listOf("a", "b", "c"),
            answer = "AB", chapter = "章", explanation = "解析", source = "出处", tags = listOf("t1", "t2")
        )
        val again = ReviewQuestion.fromJson(original.toJson())
        assertEquals(original, again)
    }

    // —— AI 提示词

    @Test
    fun `提示词包含题干选项与正确答案`() {
        val question = ReviewQuestion(
            id = "1", type = ReviewQuestion.TYPE_SINGLE, stem = "中国的首都是？",
            options = listOf("北京", "上海"), answer = "A", chapter = "地理"
        )
        val prompt = AiChatClient.buildQuestionPrompt(question)
        assertTrue(prompt.contains("中国的首都是？"))
        assertTrue(prompt.contains("A. 北京"))
        assertTrue(prompt.contains("B. 上海"))
        assertTrue(prompt.contains("正确答案：A"))
        assertTrue(prompt.contains("地理"))
        // 没作答时问的是讲解思路
        assertTrue(prompt.contains("解题思路"))
    }

    @Test
    fun `答错时提示词点明要分析错因`() {
        val question = ReviewQuestion(
            id = "1", type = ReviewQuestion.TYPE_SINGLE, stem = "题干",
            options = listOf("甲", "乙"), answer = "A", chapter = ""
        )
        val prompt = AiChatClient.buildQuestionPrompt(question, userAnswer = "B")
        assertTrue(prompt.contains("我的作答：B"))
        assertTrue(prompt.contains("回答错误"))
        assertTrue(prompt.contains("为什么会选错"))
    }

    @Test
    fun `答对时提示词不含错因分析`() {
        val question = ReviewQuestion(
            id = "1", type = ReviewQuestion.TYPE_SINGLE, stem = "题干",
            options = listOf("甲", "乙"), answer = "A", chapter = ""
        )
        val prompt = AiChatClient.buildQuestionPrompt(question, userAnswer = "A")
        assertTrue(prompt.contains("回答正确"))
        assertFalse(prompt.contains("为什么会选错"))
    }
}
