package com.hnnujw.course.review

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 练习会话的单元测试。
 *
 * 这里守的是三条最容易在改动中被破坏的规则：
 *  1. 多选必须全对才算对（[judgeAnswer]）；
 *  2. 判过分之后不能偷偷改答案（否则正确率会被中途篡改）；
 *  3. 正确率只按已判分的题算，没做完不算错题。
 */
class PracticeSessionTest {

    private fun q(id: String, type: String = "single", answer: String = "A") =
        ReviewQuestion(
            id = id,
            type = type,
            stem = "题干$id",
            options = listOf("甲", "乙", "丙", "丁"),
            answer = answer,
            chapter = "章",
            bankId = "b1"
        )

    // —— 判分口径

    @Test
    fun `单选全对才算对`() {
        assertTrue(judgeAnswer(q("1"), "A"))
        assertFalse(judgeAnswer(q("1"), "B"))
    }

    @Test
    fun `多选必须完全一致`() {
        val multi = q("2", ReviewQuestion.TYPE_MULTI, "ABD")
        assertTrue(judgeAnswer(multi, "ABD"))
        assertTrue(judgeAnswer(multi, "DBA")) // 顺序不影响
        assertFalse(judgeAnswer(multi, "AB"))   // 少选
        assertFalse(judgeAnswer(multi, "ABCD")) // 多选
        assertFalse(judgeAnswer(multi, "ABC"))  // 错选
    }

    @Test
    fun `多选乱序作答仍算对`() {
        // 回归：曾经直接比较归一化后的字符串，用户按 D→B→A 顺序点选
        // 就会把"选全了"误判成错。两边都必须排序后再比。
        val multi = q("2", ReviewQuestion.TYPE_MULTI, "ABD")
        val s = PracticeSession(listOf(multi))
        val question = s.questions[0]
        s.select(question, "D")
        s.select(question, "A")
        s.select(question, "B")
        assertEquals("ABD", s.answerOf(question))
        assertTrue(s.judge(question))
    }

    @Test
    fun `空作答一律算错`() {
        assertFalse(judgeAnswer(q("1"), ""))
        assertFalse(judgeAnswer(q("1", ReviewQuestion.TYPE_MULTI, "AB"), ""))
    }

    @Test
    fun `作答小写与含杂字符也能判对`() {
        assertTrue(judgeAnswer(q("1"), "a"))
        assertTrue(judgeAnswer(q("1", ReviewQuestion.TYPE_MULTI, "AB"), "a、b"))
    }

    // —— 选择

    @Test
    fun `单选重复点击同一项是覆盖不是追加`() {
        val s = PracticeSession(listOf(q("1")))
        val question = s.questions[0]
        s.select(question, "B")
        s.select(question, "B")
        assertEquals("B", s.answerOf(question))
    }

    @Test
    fun `多选点击切换该字母并保持有序`() {
        val s = PracticeSession(listOf(q("2", ReviewQuestion.TYPE_MULTI, "ABD")))
        val question = s.questions[0]
        s.select(question, "D")
        s.select(question, "B")
        assertEquals("BD", s.answerOf(question))
        s.select(question, "B")
        assertEquals("D", s.answerOf(question))
    }

    // —— 判分与锁定

    @Test
    fun `未作答不能判分`() {
        val s = PracticeSession(listOf(q("1")))
        val question = s.questions[0]
        assertFalse(s.judge(question))
        assertFalse(s.isJudged(question))
    }

    @Test
    fun `判分后禁止改答案`() {
        val s = PracticeSession(listOf(q("1")))
        val question = s.questions[0]
        s.select(question, "B")
        assertFalse(s.judge(question))
        assertFalse(s.select(question, "A"))
        assertEquals("B", s.answerOf(question))
    }

    @Test
    fun `撤回判定后可以重新作答`() {
        val s = PracticeSession(listOf(q("1")))
        val question = s.questions[0]
        s.select(question, "B")
        s.judge(question)
        s.unjudge(question)
        assertFalse(s.isJudged(question))
        assertTrue(s.select(question, "A"))
        assertTrue(s.judge(question))
    }

    @Test
    fun `重复判分结果稳定`() {
        val s = PracticeSession(listOf(q("1")))
        val question = s.questions[0]
        s.select(question, "A")
        assertTrue(s.judge(question))
        assertTrue(s.judge(question))
        assertEquals(1, s.judgedCount)
    }

    // —— 进度与结果

    @Test
    fun `正确率只按已判分的题计算`() {
        val qs = listOf(q("1"), q("2", answer = "B"), q("3"))
        val s = PracticeSession(qs)
        s.select(qs[0], "A")
        s.judge(qs[0])          // 对
        s.select(qs[1], "A")
        s.judge(qs[1])          // 错
        // 第三题没做
        assertEquals(2, s.judgedCount)
        assertEquals(0.5f, s.accuracy, 0.0001f)
        val r = s.result()
        assertEquals(3, r.total)
        assertEquals(2, r.answered)
        assertEquals(1, r.correct)
        assertEquals(1, r.wrong)
        assertEquals(1, r.unjudged)
    }

    @Test
    fun `全部判完才算结束`() {
        val qs = listOf(q("1"), q("2"))
        val s = PracticeSession(qs)
        assertFalse(s.isFinished)
        qs.forEach { s.select(it, "A"); s.judge(it) }
        assertTrue(s.isFinished)
    }

    @Test
    fun `空题库不视为已完成`() {
        val s = PracticeSession(emptyList())
        assertTrue(s.isEmpty)
        assertFalse(s.isFinished)
        assertEquals(0f, s.accuracy, 0.0001f)
    }

    @Test
    fun `不同题库的同 id 题目互不干扰`() {
        val a = ReviewQuestion("1", ReviewQuestion.TYPE_SINGLE, "s", listOf("甲", "乙"), "A", "c", bankId = "bankA")
        val b = ReviewQuestion("1", ReviewQuestion.TYPE_SINGLE, "s", listOf("甲", "乙"), "B", "c", bankId = "bankB")
        val s = PracticeSession(listOf(a, b))
        s.select(a, "A")
        s.judge(a)
        s.select(b, "B")
        s.judge(b)
        assertEquals(2, s.judgedCount)
        assertEquals(1.0f, s.accuracy, 0.0001f)
    }

    @Test
    fun `清空回到初始状态`() {
        val qs = listOf(q("1"))
        val s = PracticeSession(qs)
        s.select(qs[0], "A")
        s.judge(qs[0])
        s.clear()
        assertEquals(0, s.answeredCount)
        assertEquals(0, s.judgedCount)
        assertEquals("", s.answerOf(qs[0]))
    }

    // —— 交卷补判（2026-10 用户要求：「只选择选项之后提交要判断对错」）

    @Test
    fun `交卷只补判选了没确认的题_没碰过的仍算未作答`() {
        val qs = listOf(q("1"), q("2", answer = "B"), q("3"))
        val s = PracticeSession(qs)
        s.select(qs[0], "A")
        s.judge(qs[0])            // 已确认：对
        s.select(qs[1], "A")      // 只选了：错，等交卷补判
        // qs[2] 从头到尾没碰过

        val newly = s.judgePending()
        assertEquals(listOf(qs[1]), newly)
        assertEquals(2, s.judgedCount)

        val r = s.result()
        assertEquals(3, r.total)
        assertEquals(2, r.answered)
        assertEquals(1, r.correct)
        assertEquals(1, r.wrong)
        // 没碰过的那题仍是未作答 —— 不能因为补判就把它算成错。
        assertEquals(1, r.unjudged)
        assertEquals(0.5f, r.accuracy, 0.0001f)
    }

    @Test
    fun `补判是幂等的_第二次返回空且不重复计数`() {
        val qs = listOf(q("1"))
        val s = PracticeSession(qs)
        s.select(qs[0], "A")
        assertEquals(listOf(qs[0]), s.judgePending())
        assertEquals(emptyList<ReviewQuestion>(), s.judgePending())
        assertEquals(1, s.judgedCount)
    }

    @Test
    fun `补判不动用户点过确认的题_撤回判定仍然有效`() {
        // 用户点过「确认答案」→「再想一下」，此时那题是"选了但未判分"。
        // 交卷补判应当把它判上（它确实选了答案），但**不能**反过来干扰撤回本身。
        val qs = listOf(q("1"))
        val s = PracticeSession(qs)
        s.select(qs[0], "A")
        s.judge(qs[0])
        s.unjudge(qs[0])
        assertFalse(s.isJudged(qs[0]))
        assertEquals(listOf(qs[0]), s.judgePending())
        assertTrue(s.isJudged(qs[0]))
    }

    @Test
    fun `补判跳过空作答_多选取消干净后不算数`() {
        val multi = q("2", ReviewQuestion.TYPE_MULTI, "AB")
        val s = PracticeSession(listOf(multi, q("3")))
        s.select(multi, "A")
        s.select(multi, "A")      // 再点一次取消 → 作答为空
        assertEquals("", s.answerOf(multi))
        assertEquals(emptyList<ReviewQuestion>(), s.judgePending())
        assertEquals(0, s.judgedCount)
    }

    @Test
    fun `补判按出题顺序返回`() {
        val qs = listOf(q("1"), q("2"), q("3"))
        val s = PracticeSession(qs)
        // 倒着选，返回顺序仍应是出题顺序 —— 落库与明细都按这个顺序走。
        s.select(qs[2], "A")
        s.select(qs[1], "A")
        assertEquals(listOf(qs[1], qs[2]), s.judgePending())
    }

    @Test
    fun `背题模式交卷不补判`() {
        // 背题不判分、不计入统计。界面本来就不给提交入口，这里再守一道，
        // 免得以后有人把交卷接到背题上，把"看过一遍"算成"答对了"。
        val qs = listOf(q("1"))
        val s = PracticeSession(qs, memorize = true)
        s.select(qs[0], "A")
        assertEquals(emptyList<ReviewQuestion>(), s.judgePending())
        assertEquals(0, s.judgedCount)
    }

    // —— 自动下一题（2026-10 用户要求「开启自动下一题的按钮」）

    @Test
    fun `自动下一题对单选题生效`() {
        val s = PracticeSession(listOf(q("1")))
        assertTrue(s.canAutoAdvance(s.questions[0], enabled = true))
    }

    @Test
    fun `自动下一题对多选题不生效`() {
        // 多选题要选好几项才完整，程序无从判断"选完了没有" —— 按点击次数猜，
        // 会把只想选一项的用户弹走。所以它必须走「确认答案」或底部「下一题」。
        // 漏了这一条，界面上的表现是"开关没生效"，用户只会以为程序坏了。
        val s = PracticeSession(listOf(q("2", ReviewQuestion.TYPE_MULTI, "AB")))
        assertFalse(s.canAutoAdvance(s.questions[0], enabled = true))
    }

    @Test
    fun `自动下一题开关关着时不跳`() {
        val s = PracticeSession(listOf(q("1")))
        assertFalse(s.canAutoAdvance(s.questions[0], enabled = false))
    }

    @Test
    fun `背题模式不自动跳`() {
        // 背题是直接看答案、选项根本不可点。这里再守一道，免得以后有人把自动
        // 跳题接到背题上，把"翻一页"变成"漏看一页"。
        val s = PracticeSession(listOf(q("1")), memorize = true)
        assertFalse(s.canAutoAdvance(s.questions[0], enabled = true))
    }

    // —— 抗进程死亡：状态快照

    @Test
    fun `快照往返保留作答与判分`() {
        val qs = listOf(q("1"), q("2", ReviewQuestion.TYPE_MULTI, "AB"))
        val s = PracticeSession(qs)
        s.select(qs[0], "A")
        s.judge(qs[0])
        s.select(qs[1], "B")
        s.select(qs[1], "A")

        // 模拟进程被杀后重建出来的"空会话"，把快照灌回去。
        val rebuilt = PracticeSession(qs)
        assertEquals(0, rebuilt.answeredCount)
        rebuilt.restoreState(s.exportState())

        assertEquals(2, rebuilt.answeredCount)
        assertEquals(1, rebuilt.judgedCount)
        assertEquals("A", rebuilt.answerOf(qs[0]))
        assertEquals("AB", rebuilt.answerOf(qs[1]))
        assertTrue(rebuilt.isJudged(qs[0]))
        assertFalse(rebuilt.isJudged(qs[1])) // 只选了没确认
    }

    @Test
    fun `快照里的复合键含题库 id_跨库同号题不串`() {
        // bankId::qid 是复合键。若快照里只存 qid，两个库的同号题会互相覆盖。
        val a = ReviewQuestion("1", ReviewQuestion.TYPE_SINGLE, "s", listOf("甲", "乙"), "A", "c", bankId = "bankA")
        val b = ReviewQuestion("1", ReviewQuestion.TYPE_SINGLE, "s", listOf("甲", "乙"), "A", "c", bankId = "bankB")
        val qs = listOf(a, b)
        val s = PracticeSession(qs)
        s.select(a, "A")
        s.select(b, "B")

        val rebuilt = PracticeSession(qs)
        rebuilt.restoreState(s.exportState())
        assertEquals("A", rebuilt.answerOf(a))
        assertEquals("B", rebuilt.answerOf(b))
    }

    @Test
    fun `恢复会先清空已有状态_不会叠加`() {
        val qs = listOf(q("1"), q("2"))
        val s = PracticeSession(qs)
        s.select(qs[0], "A")
        s.judge(qs[0])

        val other = PracticeSession(qs)
        other.select(qs[1], "B")
        s.restoreState(other.exportState())

        assertEquals("", s.answerOf(qs[0]))
        assertEquals("B", s.answerOf(qs[1]))
        assertEquals(0, s.judgedCount)
    }

    @Test
    fun `快照损坏或为空时安全忽略`() {
        val qs = listOf(q("1"))
        val s = PracticeSession(qs)
        s.select(qs[0], "A")

        s.restoreState("")               // 空串：什么都没发生
        s.restoreState("{ 不是 json")     // 半截 JSON：不能抛异常
        s.restoreState("[]")             // 顶层不是对象：不能抛异常
        assertEquals("A", s.answerOf(qs[0]))
    }
}
