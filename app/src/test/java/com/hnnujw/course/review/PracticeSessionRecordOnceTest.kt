package com.hnnujw.course.review

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「同一题在一次会话里只落库一次」的回归测试。
 *
 * 背景：作答是"判分即落库"的（防进程被杀丢记录），而「再想一下」只撤回内存里的
 * 判定、不会回滚已经写进 `records` / `wrong_book` 的那一条。于是
 * 「确认答案 → 再想一下 → 再确认」会让同一道题被记两遍 —— `wrong_book.streak`
 * 被单次会话刷满，点三次就能把一道题刷成「已掌握」。
 *
 * 判据在 [PracticeSession.markRecorded]：返回 true 才写库。
 */
class PracticeSessionRecordOnceTest {

    private fun q(id: String, answer: String = "A") = ReviewQuestion(
        id = id,
        type = ReviewQuestion.TYPE_SINGLE,
        stem = "题干$id",
        options = listOf("甲", "乙", "丙", "丁"),
        answer = answer,
        chapter = "章",
        bankId = "b1"
    )

    private fun session(vararg items: ReviewQuestion) = PracticeSession(items.toList())

    // —— 落库去重

    @Test
    fun `同一题只允许落库一次`() {
        val s = session(q("1"))
        val question = s.questions[0]
        assertTrue("第一次应当写库", s.markRecorded(question))
        assertFalse("第二次必须被拦下", s.markRecorded(question))
    }

    @Test
    fun `不同题各自可以落库一次`() {
        val s = session(q("1"), q("2"))
        assertTrue(s.markRecorded(s.questions[0]))
        assertTrue(s.markRecorded(s.questions[1]))
        assertFalse(s.markRecorded(s.questions[0]))
    }

    /** 撤回判定**不能**让这题重新获得一次落库机会 —— 这正是重复计数的入口。 */
    @Test
    fun `再想一下之后不会重新记一次`() {
        val s = session(q("1"))
        val question = s.questions[0]
        s.select(question, "A")
        s.judge(question)
        assertTrue(s.markRecorded(question))

        s.unjudge(question)                 // 用户点「再想一下」
        s.select(question, "A")
        s.judge(question)                   // 再确认一次
        assertFalse("同一会话内第二次判分不应再落库", s.markRecorded(question))
    }

    // —— 正确率缓存（性能优化的同时必须保持口径不变）

    @Test
    fun `撤回判定会回退正确计数`() {
        val s = session(q("1"))
        val question = s.questions[0]
        s.select(question, "A")

        assertTrue(s.judge(question))
        assertEquals(1f, s.accuracy, 0.0001f)

        s.unjudge(question)
        assertEquals("撤回后不应再算作已判分", 0, s.judgedCount)
        assertEquals(0f, s.accuracy, 0.0001f)

        assertTrue(s.judge(question))
        assertEquals("重新判分应恢复正常计数", 1f, s.accuracy, 0.0001f)
    }

    @Test
    fun `重复撤回不会把正确计数减成负数`() {
        val s = session(q("1"))
        val question = s.questions[0]
        s.select(question, "A")
        s.judge(question)

        s.unjudge(question)
        s.unjudge(question)   // 重复撤回：应当是无操作
        s.judge(question)

        assertEquals(1, s.judgedCount)
        assertEquals(1f, s.accuracy, 0.0001f)
    }

    @Test
    fun `结果里的对错数量随撤回与重判保持一致`() {
        val s = session(q("1"), q("2"))
        val first = s.questions[0]
        val second = s.questions[1]

        s.select(first, "A"); assertTrue(s.judge(first))
        s.select(second, "B"); assertFalse(s.judge(second))

        var r = s.result()
        assertEquals(1, r.correct)
        assertEquals(1, r.wrong)
        assertEquals(0, r.unjudged)

        // 撤回答错的那题后重判为对
        s.unjudge(second)
        s.select(second, "A")
        assertTrue(s.judge(second))

        r = s.result()
        assertEquals(2, r.correct)
        assertEquals(0, r.wrong)
        assertEquals(0, r.unjudged)
        assertEquals(1f, r.accuracy, 0.0001f)
    }

    // —— 快照：去重集合必须跟着一起存/取

    @Test
    fun `快照会带上已落库标记并在重建后继续生效`() {
        val s = session(q("1"))
        val question = s.questions[0]
        s.select(question, "A")
        s.judge(question)
        s.markRecorded(question)

        val snapshot = s.exportState()

        val restored = session(q("1"))
        restored.restoreState(snapshot)
        restored.select(restored.questions[0], "A")
        restored.judge(restored.questions[0])

        assertFalse("重建后的会话也必须记得这题已落过库", restored.markRecorded(restored.questions[0]))
    }

    /** 老快照没有 recorded 字段：按"已判分的题都记过"回填，宁可少记也不要重记。 */
    @Test
    fun `老格式快照按已判分回填落库标记`() {
        val legacy = """{"answers":[{"k":"b1::1","a":"A"}],"judged":["b1::1"]}"""
        val s = session(q("1"))
        s.restoreState(legacy)
        assertFalse(s.markRecorded(s.questions[0]))
    }

    @Test
    fun `恢复快照后正确率不是从零开始`() {
        val s = session(q("1"))
        val question = s.questions[0]
        s.select(question, "A")
        s.judge(question)
        val snapshot = s.exportState()

        val restored = session(q("1"))
        restored.restoreState(snapshot)
        assertEquals(1f, restored.accuracy, 0.0001f)
        assertEquals(1, restored.result().correct)
    }
}
