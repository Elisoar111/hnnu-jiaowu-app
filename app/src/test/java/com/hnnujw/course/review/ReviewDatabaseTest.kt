package com.hnnujw.course.review

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [ReviewDatabase] 的真机 SQLite 回归测试（Robolectric 跑真实建表与查询）。
 *
 * 为什么需要它：这一层此前**零测试**。纯函数单测（`ReviewSql` / `WrongBookLogic`）只能证明
 * "拼出来的 SQL 长得对"，证明不了"它真的能执行、结果真的对"。而这里出错的形态全是
 * "不崩、只是数据错了"：连对计数差一、到期时间算反、抽题 LIMIT 被忽略、概览口径漂移。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReviewDatabaseTest {

    private lateinit var context: Context
    private lateinit var db: ReviewDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // 每条用例一个干净的库：先丢弃单例，再删掉磁盘文件。
        ReviewDatabase.resetInstanceForTest()
        context.deleteDatabase("review.db")
        db = ReviewDatabase.get(context)
    }

    @After
    fun tearDown() {
        ReviewDatabase.resetInstanceForTest()
        context.deleteDatabase("review.db")
    }

    // —— 造数

    private fun q(
        id: String,
        answer: String = "A",
        type: String = ReviewQuestion.TYPE_SINGLE,
        chapter: String = "第一章"
    ) = ReviewQuestion(
        id = id,
        type = type,
        stem = "题干$id",
        options = listOf("甲", "乙", "丙", "丁"),
        answer = answer,
        chapter = chapter,
        explanation = "解析$id",
        bankId = ""   // 由 importBank 覆盖
    )

    private fun importBank(id: String = "bank1", count: Int = 3, chapter: String = "第一章"): String {
        val questions = (1..count).map { q("q$it", chapter = chapter) }
        return db.importBank("测试题库$id", questions)
    }

    // —— 题库 CRUD

    @Test
    fun `导入题库后题目数与章节统计正确`() {
        val bankId = importBank(count = 3)
        assertEquals(1, db.listBanks().size)
        assertEquals(3, db.totalCount(bankId))
        assertEquals(listOf("第一章" to 3), db.chaptersWithCount(bankId))
        assertEquals("测试题库bank1", db.bankName(bankId))
    }

    @Test
    fun `同名题库重导会覆盖并清掉旧记录`() {
        val bankId = importBank(count = 2)
        db.recordAnswers(listOf(ReviewDatabase.AnswerRecord(bankId, "q1", correct = false)))
        assertEquals(1, db.overview(bankId).wrongPending)

        db.importBank("测试题库bank1", listOf(q("q1")))

        assertEquals(1, db.totalCount(bankId))
        assertEquals("重导必须清掉指向旧题的错题行", 0, db.overview(bankId).wrongPending)
    }

    @Test
    fun `删除题库连带清掉记录与错题本`() {
        val bankId = importBank(count = 2)
        db.recordAnswers(listOf(ReviewDatabase.AnswerRecord(bankId, "q1", correct = false)))

        db.deleteBank(bankId)

        assertTrue(db.listBanks().isEmpty())
        assertEquals(0, db.totalCount())
        assertEquals(0, db.overview().wrongPending)
    }

    @Test
    fun `批量删题同时清掉记录错题与收藏`() {
        val bankId = importBank(count = 3)
        db.recordAnswers(listOf(ReviewDatabase.AnswerRecord(bankId, "q1", correct = false)))
        db.toggleFavorite(bankId, "q1")
        db.toggleFavorite(bankId, "q2")

        val deleted = db.deleteQuestions(bankId, listOf("q1"))

        assertEquals(1, deleted)
        assertEquals(2, db.totalCount(bankId))
        assertEquals("题目没了，错题本不能留着孤儿行", 0, db.overview(bankId).wrongPending)
        assertEquals(1, db.overview(bankId).favorites)
        // question_count 必须跟着刷新，否则首页会显示 3 题、进去只有 2 题
        assertEquals(2, db.listBanks().single().questionCount)
    }

    // —— 作答落库与错题本

    @Test
    fun `答错会入错题本并立即可复习`() {
        val bankId = importBank(count = 1)
        db.recordAnswers(listOf(ReviewDatabase.AnswerRecord(bankId, "q1", correct = false)))

        val entry = db.listWrongBook(bankId).single()
        assertEquals(1, entry.wrongCount)
        assertEquals(0, entry.streak)
        assertFalse(entry.mastered)
        // due_at = 0 视为立即到期：新入册的题必须马上能被「今日待复习」抽到
        assertEquals(0L, entry.dueAt)
        assertEquals(1, db.dueWrongCount(bankId))
        assertEquals(1, db.dueWrongQuestions(bankId).size)
    }

    @Test
    fun `连续答对三次标记为已掌握并退出到期队列`() {
        val bankId = importBank(count = 1)
        db.recordAnswers(listOf(ReviewDatabase.AnswerRecord(bankId, "q1", correct = false)))
        repeat(3) {
            db.recordAnswers(listOf(ReviewDatabase.AnswerRecord(bankId, "q1", correct = true)))
        }

        val entry = db.listWrongBook(bankId).single()
        assertEquals(3, entry.streak)
        assertTrue(entry.mastered)
        assertEquals("已掌握的不该再进复习队列", 0, db.dueWrongCount(bankId))
        assertEquals(0, db.pickQuestions(bankId = bankId, onlyWrong = true).size)
    }

    @Test
    fun `掌握后再答错要重新入册并清零连对`() {
        val bankId = importBank(count = 1)
        db.recordAnswers(listOf(ReviewDatabase.AnswerRecord(bankId, "q1", correct = false)))
        repeat(3) { db.recordAnswers(listOf(ReviewDatabase.AnswerRecord(bankId, "q1", correct = true))) }
        assertTrue(db.listWrongBook(bankId).single().mastered)

        db.recordAnswers(listOf(ReviewDatabase.AnswerRecord(bankId, "q1", correct = false)))

        val entry = db.listWrongBook(bankId).single()
        assertFalse(entry.mastered)
        assertEquals(0, entry.streak)
        assertEquals(2, entry.wrongCount)
    }

    @Test
    fun `批量作答一次事务写多题`() {
        val bankId = importBank(count = 3)
        db.recordAnswers(
            listOf(
                ReviewDatabase.AnswerRecord(bankId, "q1", correct = true),
                ReviewDatabase.AnswerRecord(bankId, "q2", correct = false),
                ReviewDatabase.AnswerRecord(bankId, "q3", correct = false)
            )
        )

        val ov = db.overview(bankId)
        assertEquals(3, ov.answered)
        assertEquals(1, ov.correct)
        assertEquals(2, ov.wrongPending)
    }

    @Test
    fun `答对不计入错题本`() {
        val bankId = importBank(count = 1)
        db.recordAnswers(listOf(ReviewDatabase.AnswerRecord(bankId, "q1", correct = true)))
        assertTrue(db.listWrongBook(bankId).isEmpty())
        assertEquals(1, db.overview(bankId).correct)
    }

    // —— 抽题

    @Test
    fun `顺序模式按 rowid 稳定返回`() {
        val bankId = importBank(count = 3)
        val first = db.pickQuestions(bankId = bankId, mode = ReviewMode.SEQUENTIAL).map { it.id }
        val second = db.pickQuestions(bankId = bankId, mode = ReviewMode.SEQUENTIAL).map { it.id }
        assertEquals(listOf("q1", "q2", "q3"), first)
        assertEquals("重抽必须同序（进程重建靠它恢复同一批题）", first, second)
    }

    @Test
    fun `乱序模式也必须是稳定顺序`() {
        val bankId = importBank(count = 10)
        val first = db.pickQuestions(bankId = bankId, mode = ReviewMode.RANDOM).map { it.id }
        val second = db.pickQuestions(bankId = bankId, mode = ReviewMode.RANDOM).map { it.id }
        assertEquals(first, second)
        assertEquals(10, first.size)
        assertEquals("乱序不应改变集合", first.toSet(), (1..10).map { "q$it" }.toSet())
    }

    @Test
    fun `乱序与错题优先的 limit 必须真的生效`() {
        val bankId = importBank(count = 10)
        assertEquals(3, db.pickQuestions(bankId = bankId, mode = ReviewMode.RANDOM, limit = 3).size)
        assertEquals(3, db.pickQuestions(bankId = bankId, mode = ReviewMode.WRONG_FIRST, limit = 3).size)
        assertEquals(3, db.pickQuestions(bankId = bankId, mode = ReviewMode.SEQUENTIAL, limit = 3).size)
    }

    @Test
    fun `错题优先把未掌握的错题排在最前`() {
        val bankId = importBank(count = 3)
        db.recordAnswers(listOf(ReviewDatabase.AnswerRecord(bankId, "q3", correct = false)))

        val ordered = db.pickQuestions(bankId = bankId, mode = ReviewMode.WRONG_FIRST).map { it.id }
        assertEquals("q3", ordered.first())
    }

    @Test
    fun `多选章节按 IN 过滤且章节名里的引号不会炸 SQL`() {
        val bankId = db.importBank(
            "引号题库",
            listOf(
                q("a1", chapter = "第一章"),
                q("a2", chapter = "第七章 “三个代表”重要思想"),
                q("a3", chapter = "第三章")
            )
        )

        val picked = db.pickQuestions(
            bankId = bankId,
            chapters = listOf("第一章", "第七章 “三个代表”重要思想")
        ).map { it.id }

        assertEquals(listOf("a1", "a2"), picked.sorted())
    }

    @Test
    fun `空章节列表等于不过滤`() {
        val bankId = importBank(count = 4)
        assertEquals(4, db.pickQuestions(bankId = bankId, chapters = emptyList()).size)
    }

    @Test
    fun `只练今日到期的错题`() {
        val bankId = importBank(count = 2)
        db.recordAnswers(
            listOf(
                ReviewDatabase.AnswerRecord(bankId, "q1", correct = false),
                ReviewDatabase.AnswerRecord(bankId, "q2", correct = false)
            )
        )
        // 把 q1 的到期时间推到未来
        db.writableDatabase.execSQL(
            "UPDATE wrong_book SET due_at = ? WHERE bank_id = ? AND question_id = ?",
            arrayOf<Any>(System.currentTimeMillis() + 86_400_000L, bankId, "q1")
        )

        val due = db.pickQuestions(bankId = bankId, dueOnly = true).map { it.id }
        assertEquals(listOf("q2"), due)
        assertEquals(1, db.dueWrongCount(bankId))
    }

    // —— 收藏

    @Test
    fun `收藏是切换语义且能按题库列出`() {
        val bankId = importBank(count = 2)
        assertTrue(db.toggleFavorite(bankId, "q1"))
        assertTrue(db.isFavorite(bankId, "q1"))
        assertEquals(1, db.listFavorites(bankId).size)

        assertFalse(db.toggleFavorite(bankId, "q1"))
        assertFalse(db.isFavorite(bankId, "q1"))
        assertTrue(db.listFavorites(bankId).isEmpty())
    }

    // —— 概览

    @Test
    fun `概览在全库与单库两种口径下都正确`() {
        val bankA = importBank(id = "a", count = 2)
        importBank(id = "b", count = 3)
        db.recordAnswers(
            listOf(
                ReviewDatabase.AnswerRecord(bankA, "q1", correct = true),
                ReviewDatabase.AnswerRecord(bankA, "q2", correct = false)
            )
        )
        db.toggleFavorite(bankA, "q1")

        val single = db.overview(bankA)
        assertEquals(2, single.total)
        assertEquals(2, single.answered)
        assertEquals(1, single.correct)
        assertEquals(1, single.wrongPending)
        assertEquals(1, single.favorites)

        val all = db.overview()
        assertEquals(5, all.total)
        assertEquals(2, all.answered)
        assertEquals("banks 是全局表，两种口径都应当是 2 个库", 2, all.bankCount)
    }

    // —— 题库仓库的取出 / 排序

    @Test
    fun `移出题库只改显示状态不删数据`() {
        val bankId = importBank(count = 2)
        db.recordAnswers(listOf(ReviewDatabase.AnswerRecord(bankId, "q1", correct = false)))

        db.setBankSelected(bankId, false)

        val summary = db.listBanks().single()
        assertFalse(summary.selected)
        assertEquals("题目与记录都必须还在", 2, db.totalCount(bankId))
        assertEquals(1, db.overview(bankId).wrongPending)

        db.setBankSelected(bankId, true)
        assertTrue(db.listBanks().single().selected)
    }

    @Test
    fun `按给定顺序重排已取出的题库`() {
        val a = importBank(id = "a", count = 1)
        val b = importBank(id = "b", count = 1)
        val c = importBank(id = "c", count = 1)

        db.reorderSelectedBanks(listOf(c, a, b))

        assertEquals(listOf(c, a, b), db.listBanks().filter { it.selected }.map { it.id })
    }

    @Test
    fun `上移下移只交换相邻两条`() {
        val a = importBank(id = "a", count = 1)
        val b = importBank(id = "b", count = 1)
        val c = importBank(id = "c", count = 1)

        db.moveBank(c, -1)

        assertEquals(listOf(a, c, b), db.listBanks().filter { it.selected }.map { it.id })
        // 已经在最前面的再上移应当无副作用
        db.moveBank(a, -1)
        assertEquals(listOf(a, c, b), db.listBanks().filter { it.selected }.map { it.id })
    }

    // —— 改名与逐题编辑

    @Test
    fun `重名改题库会被拒绝`() {
        val a = importBank(id = "a", count = 1)
        importBank(id = "b", count = 1)

        assertFalse(db.renameBank(a, "测试题库b"))
        assertTrue(db.renameBank(a, "新名字"))
        assertEquals("新名字", db.bankName(a))
        // 改成自己原来的名字应当成功（排己查重）
        assertTrue(db.renameBank(a, "新名字"))
    }

    @Test
    fun `逐题编辑只改内容不动答题记录`() {
        val bankId = importBank(count = 1)
        db.recordAnswers(listOf(ReviewDatabase.AnswerRecord(bankId, "q1", correct = false)))

        assertTrue(db.updateQuestion(bankId, "q1", stem = "新题干", options = listOf("甲", "乙"), answer = "B", analysis = "新解析"))

        val q = db.question(bankId, "q1")!!
        assertEquals("新题干", q.stem)
        assertEquals("B", q.answer)
        assertEquals(2, q.options.size)
        assertEquals("答题记录不该被内容编辑清掉", 1, db.overview(bankId).wrongPending)
    }

    @Test
    fun `按 id 读不到的题返回 null`() {
        val bankId = importBank(count = 1)
        assertNull(db.question(bankId, "不存在"))
        assertNull(db.questionById(bankId, "不存在"))
    }

    // —— 坏数据

    @Test
    fun `删掉题库后再抽题返回空而不是抛异常`() {
        val bankId = importBank(count = 2)
        db.deleteBank(bankId)
        assertTrue(db.pickQuestions(bankId = bankId).isEmpty())
        assertTrue(db.listWrongBook(bankId).isEmpty())
        assertEquals(0, db.totalCount(bankId))
    }
}
