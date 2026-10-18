package com.hnnujw.course.review

/**
 * 复习模块的 SQL 拼装。**纯函数，不碰任何 Android API**，因此能在 JVM 单测里直接断言。
 *
 * 抽出来的原因（2026-11 线上闪退）：
 * [ReviewDatabase.overview] 原来是拿字符串拼 WHERE 的 ——
 * `bankId == null` 时 where 片段是空串，于是拼出
 * `SELECT COUNT(*) FROM wrong_book AND mastered = 0`，
 * AND 前面没有 WHERE，SQLite 直接抛 `SQLiteException: near "AND": syntax error`。
 * 而 overview() 是复习首页 LaunchedEffect 的三连读之一，异常没人接，
 * 于是一进「复习」页就闪退 —— 与用户点没点「顺序练习」无关。
 *
 * 另一条同源事故：`scalar()` 原先对所有查询复用同一个 `args`，
 * 于是 `SELECT COUNT(*) FROM banks`（0 个占位符）也会收到 1 个参数。
 * `SQLiteProgram.bindAllArgsAsStrings` 在 `args.size > 占位符数` 时抛
 * `IllegalArgumentException: Cannot bind argument at index N`，
 * 所以 `overview(bankId = "xxx")` 这条路径本身就是炸的。
 * 现在由 [BoundQuery] 把 sql 与 args 绑在一起生成，杜绝这种错配。
 */
object ReviewSql {

    /** 允许被拼进 SQL 的表名白名单。表名是字符串拼接，必须白名单而不是直接透传。 */
    private val COUNTABLE_TABLES = setOf("questions", "records", "wrong_book", "favorites", "banks")

    /** 允许被 SUM 的列白名单（列名同样不能透传）。 */
    private val COUNTABLE_COLUMNS = setOf("correct_count", "wrong_count")

    /** 错题本「未掌握」条件。[ReviewDatabase.overview] 专用。 */
    const val CONDITION_WRONG_PENDING = "mastered = 0"

    /**
     * 一条带参数的查询。[args] 与 [sql] 里的 `?` **必须**一一对应 ——
     * 由 [overview] 统一构造，调用方拿到的 [argsArray] 可以直接喂给 `rawQuery`。
     */
    data class BoundQuery(val sql: String, val args: List<String>) {
        fun argsArray(): Array<String> = args.toTypedArray()
    }

    /**
     * 拼 WHERE 片段。
     *
     * @param bankId 题库 id；null 表示不按题库过滤。
     * @param conditions 额外条件（如 [CONDITION_WRONG_PENDING]）；空白项会被忽略。
     * @return 以 ` WHERE ` 开头的片段，或空串（无条件时）。**永远不会返回半截 `AND ...`**。
     */
    fun filterClause(bankId: String?, vararg conditions: String?): String {
        val parts = ArrayList<String>(conditions.size + 1)
        if (bankId != null) parts.add("bank_id = ?")
        conditions.forEach { c -> if (!c.isNullOrBlank()) parts.add(c) }
        return if (parts.isEmpty()) "" else " WHERE " + parts.joinToString(" AND ")
    }

    /**
     * 拼 `SELECT COUNT(*) FROM <table> [WHERE ...]`。
     *
     * @throws IllegalArgumentException 表名不在白名单内（防 SQL 注入）。
     */
    fun countSql(table: String, bankId: String?, vararg conditions: String?): String {
        require(table in COUNTABLE_TABLES) { "不支持统计的表: $table" }
        return "SELECT COUNT(*) FROM $table" + filterClause(bankId, *conditions)
    }

    /**
     * 拼 `SELECT COALESCE(SUM(<column>), 0) FROM <table> [WHERE ...]`。
     *
     * 用 COALESCE 包一层：空表时 SUM 返回 NULL，直接 getInt 会拿到 0 但语义上不显式。
     *
     * @throws IllegalArgumentException 表名/列名不在白名单内。
     */
    fun sumSql(column: String, table: String, bankId: String?): String {
        require(table in COUNTABLE_TABLES) { "不支持统计的表: $table" }
        require(column in COUNTABLE_COLUMNS) { "不支持统计的列: $column" }
        return "SELECT COALESCE(SUM($column), 0) FROM $table" + filterClause(bankId)
    }

    /**
     * [ReviewDatabase.overview] 需要的全部标量，**合并成一条 SQL**。
     *
     * 原本这里是六条独立查询（每条一次 cursor 往返），但它们读的是同一批表、
     * 同一个 `bankId` 条件，没有理由分开跑。合并后首页进入时的数据库往返从 6 次降到 1 次。
     *
     * 用「`SELECT (子查询), (子查询), …`」而不是 `UNION ALL`：六个值必须来自同一行，
     * 拆成 UNION 还得按行号认哪个是哪个 —— 下标错位不会报错，只会算错数字。
     *
     * `banks` 是全局表，固定不带 `bank_id` 条件、也不带参数；这条正是当年
     * `Cannot bind argument at index` 的源头，由 [BoundQuery] 的 sql/args 成对生成保证不再犯。
     */
    fun overviewSingle(bankId: String?): BoundQuery {
        val filter = if (bankId == null) "" else " WHERE bank_id = ?"
        val sql = buildString {
            append("SELECT ")
            append("(SELECT COUNT(*) FROM questions").append(filter).append("), ")
            append("(SELECT COUNT(*) FROM records").append(filter).append("), ")
            append("(SELECT COALESCE(SUM(correct_count), 0) FROM records").append(filter).append("), ")
            append("(SELECT COUNT(*) FROM wrong_book").append(filter)
            append(if (bankId == null) " WHERE mastered = 0" else " AND mastered = 0").append("), ")
            append("(SELECT COUNT(*) FROM favorites").append(filter).append("), ")
            append("(SELECT COUNT(*) FROM banks)")
        }
        val args = if (bankId == null) emptyList() else List(5) { bankId }
        return BoundQuery(sql, args)
    }
}
