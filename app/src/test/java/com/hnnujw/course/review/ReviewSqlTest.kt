package com.hnnujw.course.review

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * [ReviewSql] 的回归测试 —— 守 2026-11 那次「点复习入口就闪退」的两条 SQL 事故。
 *
 * 背景：原来的 `overview()` 是手写字符串拼接
 * （`"SELECT COUNT(*) FROM wrong_book$where AND mastered = 0"`），
 * `bankId == null` 时 where 是空串，拼出的是 **AND 前面没有 WHERE** 的非法 SQL，
 * SQLite 抛 `SQLiteException: near "AND": syntax error`。
 * 而 overview() 正是复习首页 LaunchedEffect 的首读之一，异常无人接 → 进页即崩。
 *
 * 另一条同源事故：所有查询复用同一个 `args`，`SELECT COUNT(*) FROM banks`
 * （0 占位符）收到 1 个参数 → `IllegalArgumentException: Cannot bind argument at index`。
 *
 * 纯 JVM 单测跑不了真 SQLite（无 Robolectric），所以这里断言两条**结构不变量**，
 * 它们恰好是这两类事故的充要特征：
 *  1. 语句里不允许出现「没有先出现 WHERE 的 AND」（悬空 AND）；
 *  2. `?` 的个数必须等于绑定参数的个数。
 */
class ReviewSqlTest {

    /** 各种 bankId 取值：null / 空串 / 正常 id / 含引号的脏 id。 */
    private val bankIds = listOf<String?>(null, "", "b1", "bank'1")

    /**
     * 悬空 AND 检测：`FROM <表>` 之后直接跟 `AND`。
     * 这是原来那条 `FROM wrong_book AND mastered = 0` 的特征形状。
     */
    private val danglingAnd = Regex("""\bFROM\s+\w+\s+AND\b""", RegexOption.IGNORE_CASE)

    private fun assertSqlWellFormed(sql: String, args: List<String>) {
        assertTrue("SQL 不应以空白开头: <$sql>", sql == sql.trim())
        assertTrue("SQL 应以 SELECT 开头: <$sql>", sql.startsWith("SELECT "))
        assertFalse("出现悬空 AND（WHERE 缺失）: <$sql>", danglingAnd.containsMatchIn(sql))
        // AND 只能出现在 WHERE 之后
        val whereAt = sql.indexOf(" WHERE ", ignoreCase = true)
        val andAt = Regex("""\bAND\b""", RegexOption.IGNORE_CASE).find(sql)?.range?.first ?: -1
        if (andAt >= 0) {
            assertTrue("AND 出现在 WHERE 之前: <$sql>", whereAt >= 0 && andAt > whereAt)
        }
        // 占位符个数 == 参数个数（SQLiteProgram.bindAllArgsAsStrings 的硬要求）
        assertEquals("占位符与参数个数不匹配: <$sql> args=$args", sql.count { it == '?' }, args.size)
    }

    // —— filterClause

    @Test
    fun `无条件时 filterClause 返回空串而不是半截 AND`() {
        assertEquals("", ReviewSql.filterClause(null))
        assertEquals("", ReviewSql.filterClause(null, null))
        assertEquals("", ReviewSql.filterClause(null, "", "   "))
    }

    @Test
    fun `只有 bankId 时补上 WHERE 前缀`() {
        assertEquals(" WHERE bank_id = ?", ReviewSql.filterClause("b1"))
    }

    @Test
    fun `bankId 与额外条件用 AND 连接且都带 WHERE`() {
        assertEquals(
            " WHERE bank_id = ? AND mastered = 0",
            ReviewSql.filterClause("b1", ReviewSql.CONDITION_WRONG_PENDING)
        )
        // 这条就是原来崩掉的那条 SQL 的 bankId != null 版本
        assertEquals(
            " WHERE mastered = 0",
            ReviewSql.filterClause(null, ReviewSql.CONDITION_WRONG_PENDING)
        )
    }

    // —— countSql

    @Test
    fun `错题本未掌握数在 bankId 为 null 时也必须是合法 SQL`() {
        // 回归：原来是 "SELECT COUNT(*) FROM wrong_book AND mastered = 0"
        val sql = ReviewSql.countSql("wrong_book", null, ReviewSql.CONDITION_WRONG_PENDING)
        assertEquals("SELECT COUNT(*) FROM wrong_book WHERE mastered = 0", sql)
        assertSqlWellFormed(sql, emptyList())
    }

    @Test
    fun `错题本未掌握数在 bankId 非 null 时带两个条件`() {
        val sql = ReviewSql.countSql("wrong_book", "b1", ReviewSql.CONDITION_WRONG_PENDING)
        assertEquals("SELECT COUNT(*) FROM wrong_book WHERE bank_id = ? AND mastered = 0", sql)
        assertSqlWellFormed(sql, listOf("b1"))
    }

    @Test
    fun `所有表与所有 bankId 组合下 countSql 都合法`() {
        val tables = listOf("questions", "records", "wrong_book", "favorites", "banks")
        for (t in tables) {
            for (b in bankIds) {
                val sql = ReviewSql.countSql(t, b)
                assertSqlWellFormed(sql, listOfNotNull(b))
            }
        }
    }

    @Test
    fun `表名不在白名单时抛 IllegalArgumentException`() {
        // 防注入：表名是拼进去的，不能透传
        try {
            ReviewSql.countSql("banks; DROP TABLE questions", null)
            fail("白名单外的表名应当被拒绝")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("banks; DROP TABLE questions"))
        }
    }

    // —— sumSql

    @Test
    fun `sumSql 用 COALESCE 兜住空表`() {
        val sql = ReviewSql.sumSql("correct_count", "records", null)
        assertEquals("SELECT COALESCE(SUM(correct_count), 0) FROM records", sql)
        assertSqlWellFormed(sql, emptyList())
    }

    @Test
    fun `sumSql 带 bankId 时占位符与参数对齐`() {
        val sql = ReviewSql.sumSql("correct_count", "records", "b1")
        assertEquals("SELECT COALESCE(SUM(correct_count), 0) FROM records WHERE bank_id = ?", sql)
        assertSqlWellFormed(sql, listOf("b1"))
    }

    @Test
    fun `列名不在白名单时抛 IllegalArgumentException`() {
        try {
            ReviewSql.sumSql("correct_count) FROM banks --", "records", null)
            fail("白名单外的列名应当被拒绝")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("correct_count) FROM banks --"))
        }
    }


    // —— overviewSingle（review 首页 / 题库详情页概览的实际数据源）

    @Test
    fun `概览合并查询在 bankId 为 null 时合法`() {
        val q = ReviewSql.overviewSingle(null)
        assertSqlWellFormed(q.sql, q.args)
        assertEquals("无条件时不应有任何占位符", 0, q.args.size)
        assertTrue("必须是单条 SELECT", q.sql.startsWith("SELECT "))
    }

    @Test
    fun `概览合并查询在 bankId 非 null 时占位符与参数对齐`() {
        // 回归：原来 banks 那条 0 占位符却收到 1 个参数 → Cannot bind argument at index。
        // 合并后 banks 子查询仍然不带条件，其余五条各一个占位符。
        val q = ReviewSql.overviewSingle("b1")
        assertSqlWellFormed(q.sql, q.args)
        assertEquals(5, q.args.size)
        assertTrue("每个参数都应当是同一个 bankId", q.args.all { it == "b1" })
        assertFalse("banks 是全局表，不该带 bank_id 条件", q.sql.contains("FROM banks WHERE"))
    }

    @Test
    fun `概览合并查询在每种 bankId 取值下都合法`() {
        bankIds.forEach { id ->
            val q = ReviewSql.overviewSingle(id)
            assertSqlWellFormed(q.sql, q.args)
        }
    }

    @Test
    fun `概览合并查询的错题本子查询始终带 mastered 条件`() {
        // 这一条单独再钉一次：它就是当年闪退的那条 SQL
        assertTrue(ReviewSql.overviewSingle(null).sql.contains("FROM wrong_book WHERE mastered = 0"))
        assertTrue(ReviewSql.overviewSingle("b1").sql.contains("FROM wrong_book WHERE bank_id = ? AND mastered = 0"))
    }

    @Test
    fun `脏 bankId 只作为绑定参数出现不进概览 SQL 文本`() {
        val q = ReviewSql.overviewSingle("bank'1")
        assertSqlWellFormed(q.sql, q.args)
        assertFalse("引号泄漏进 SQL 文本: <${q.sql}>", q.sql.contains('\''))
    }

    @Test
    fun `概览合并查询里六个标量各出现一次`() {
        val sql = ReviewSql.overviewSingle("b1").sql
        assertEquals(1, Regex("FROM questions").findAll(sql).count())
        assertEquals(2, Regex("FROM records").findAll(sql).count())   // answered + sum(correct_count)
        assertEquals(1, Regex("FROM wrong_book").findAll(sql).count())
        assertEquals(1, Regex("FROM favorites").findAll(sql).count())
        assertEquals(1, Regex("FROM banks").findAll(sql).count())
    }
}
