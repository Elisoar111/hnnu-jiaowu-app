package com.hnnujw.course.review

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 复习模块的本地库。用 Android 原生 SQLite（项目未引入 Room，不为一个模块加 KSP）。
 *
 * 表结构照 D:\zhiyu 的 database.dart 迁移而来（表名与列名保持一致，方便对照迁移）：
 *  - banks        导入的题库（新增：那边是单库文件，这里做成多库共存）
 *  - questions    题目，bank_id + qid 为联合主键（不同题库可以有同 id 的题）
 *  - records      每题的对错次数
 *  - wrong_book   错题本（含掌握标记与连对次数）
 *  - favorites    收藏
 *  - daily_stats  每日答题量，统计页用
 *  - meta         杂项键值
 *
 * 隐私：题干内容不进日志，只记 id 和条数。
 */
class ReviewDatabase private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS banks (
                id          TEXT PRIMARY KEY,
                name        TEXT NOT NULL,
                question_count INTEGER NOT NULL DEFAULT 0,
                created_at  INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS questions (
                bank_id     TEXT NOT NULL,
                qid         TEXT NOT NULL,
                type        TEXT NOT NULL,
                stem        TEXT NOT NULL,
                options     TEXT NOT NULL,
                answer      TEXT NOT NULL,
                chapter     TEXT NOT NULL DEFAULT '未分类',
                explanation TEXT NOT NULL DEFAULT '',
                source      TEXT NOT NULL DEFAULT '',
                tags        TEXT NOT NULL DEFAULT '[]',
                PRIMARY KEY (bank_id, qid)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_q_bank ON questions(bank_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_q_chapter ON questions(chapter)")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS records (
                bank_id      TEXT NOT NULL,
                question_id  TEXT NOT NULL,
                correct_count INTEGER NOT NULL DEFAULT 0,
                wrong_count  INTEGER NOT NULL DEFAULT 0,
                last_result  TEXT,
                last_time    INTEGER,
                PRIMARY KEY (bank_id, question_id)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS wrong_book (
                bank_id      TEXT NOT NULL,
                question_id  TEXT NOT NULL,
                wrong_count  INTEGER NOT NULL DEFAULT 1,
                last_wrong_at INTEGER NOT NULL,
                mastered     INTEGER NOT NULL DEFAULT 0,
                streak       INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY (bank_id, question_id)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS favorites (
                bank_id     TEXT NOT NULL,
                question_id TEXT NOT NULL,
                created_at  INTEGER NOT NULL,
                PRIMARY KEY (bank_id, question_id)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS daily_stats (
                day      TEXT PRIMARY KEY,
                answered INTEGER NOT NULL DEFAULT 0,
                correct  INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS meta (
                key   TEXT PRIMARY KEY,
                value TEXT NOT NULL
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // 全部建表语句都是 IF NOT EXISTS，幂等重跑即可；将来加列再按版本补 ALTER。
        onCreate(db)
    }

    // —— 题库

    /** 导入一个题库，返回 bankId。已存在同名同数量的题库时直接复用，避免重复导入堆库。 */
    fun importBank(name: String, questions: List<ReviewQuestion>): String {
        val db = writableDatabase
        val now = System.currentTimeMillis()
        val existing = db.query(
            "banks", arrayOf("id"), "name = ?", arrayOf(name), null, null, null
        ).use { if (it.moveToFirst()) it.getString(0) else null }
        val bankId = existing ?: UUID.randomUUID().toString().replace("-", "").take(16)
        db.beginTransaction()
        try {
            if (existing == null) {
                db.insertWithOnConflict(
                    "banks", null,
                    ContentValues().apply {
                        put("id", bankId)
                        put("name", name)
                        put("question_count", questions.size)
                        put("created_at", now)
                    },
                    SQLiteDatabase.CONFLICT_REPLACE
                )
            }
            // 同名库重导：先清掉旧题（records/wrong_book/favorites 里的旧行一并清，
            // 留着就变成指向不存在题目的孤儿行）。
            if (existing != null) {
                listOf("questions", "records", "wrong_book", "favorites").forEach { t ->
                    db.delete(t, "bank_id = ?", arrayOf(bankId))
                }
                db.update(
                    "banks", ContentValues().apply { put("question_count", questions.size) },
                    "id = ?", arrayOf(bankId)
                )
            }
            questions.forEach { q -> insertQuestion(db, bankId, q, now) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        Log.i(TAG, "importBank: name=$name bankId=$bankId count=${questions.size}")
        return bankId
    }

    private fun insertQuestion(db: SQLiteDatabase, bankId: String, q: ReviewQuestion, now: Long) {
        db.insertWithOnConflict(
            "questions", null,
            ContentValues().apply {
                put("bank_id", bankId)
                put("qid", q.id)
                put("type", q.type)
                put("stem", q.stem)
                put("options", JSONArray(q.options).toString())
                put("answer", q.answer)
                put("chapter", q.chapter)
                put("explanation", q.explanation)
                put("source", q.source)
                put("tags", JSONArray(q.tags).toString())
            },
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    data class BankSummary(
        val id: String,
        val name: String,
        val questionCount: Int,
        val createdAt: Long,
        /** 错题本里还没掌握的题数。 */
        val pendingWrong: Int
    )

    fun listBanks(): List<BankSummary> = readableDatabase.rawQuery(
        "SELECT b.id, b.name, b.question_count, b.created_at, " +
            "(SELECT COUNT(*) FROM wrong_book w WHERE w.bank_id = b.id AND w.mastered = 0) AS pending " +
            "FROM banks b ORDER BY b.created_at DESC", null
    ).use { c ->
        val out = ArrayList<BankSummary>()
        while (c.moveToNext()) {
            out.add(
                BankSummary(
                    id = c.getString(0),
                    name = c.getString(1),
                    questionCount = c.getInt(2),
                    createdAt = c.getLong(3),
                    pendingWrong = c.getInt(4)
                )
            )
        }
        out
    }

    /** 删除题库连带它的题目与全部答题记录。 */
    fun deleteBank(bankId: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            listOf("questions", "records", "wrong_book", "favorites").forEach { t ->
                db.delete(t, "bank_id = ?", arrayOf(bankId))
            }
            db.delete("banks", "id = ?", arrayOf(bankId))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    // —— 取题

    fun chapters(bankId: String? = null): List<String> {
        val sql = StringBuilder("SELECT DISTINCT chapter FROM questions")
        val args: Array<String>? = if (bankId == null) null else {
            sql.append(" WHERE bank_id = ?")
            arrayOf(bankId)
        }
        sql.append(" ORDER BY chapter")
        return readableDatabase.rawQuery(sql.toString(), args).use { c ->
            val out = ArrayList<String>()
            while (c.moveToNext()) out.add(c.getString(0))
            out
        }
    }

    fun chaptersWithCount(bankId: String? = null): List<Pair<String, Int>> {
        val sql = StringBuilder(
            "SELECT chapter, COUNT(*) FROM questions"
        )
        val args: Array<String>? = if (bankId == null) null else {
            sql.append(" WHERE bank_id = ?")
            arrayOf(bankId)
        }
        sql.append(" GROUP BY chapter ORDER BY chapter")
        return readableDatabase.rawQuery(sql.toString(), args).use { c ->
            val out = ArrayList<Pair<String, Int>>()
            while (c.moveToNext()) out.add(c.getString(0) to c.getInt(1))
            out
        }
    }

    /**
     * 按条件抽题。
     *
     * @param mode 练习模式，见 [ReviewMode]。
     * @param limit 抽题上限；0 = 不限（错题本/收藏全量）。
     */
    fun pickQuestions(
        bankId: String? = null,
        chapter: String? = null,
        mode: ReviewMode = ReviewMode.SEQUENTIAL,
        onlyWrong: Boolean = false,
        onlyFavorite: Boolean = false,
        limit: Int = 0
    ): List<ReviewQuestion> {
        val where = ArrayList<String>()
        val args = ArrayList<String>()
        if (bankId != null) { where.add("bank_id = ?"); args.add(bankId) }
        if (chapter != null) { where.add("chapter = ?"); args.add(chapter) }
        if (onlyWrong) where.add("qid IN (SELECT question_id FROM wrong_book WHERE bank_id = questions.bank_id AND mastered = 0)")
        if (onlyFavorite) where.add("qid IN (SELECT question_id FROM favorites WHERE bank_id = questions.bank_id)")
        val sql = buildString {
            append("SELECT bank_id, qid, type, stem, options, answer, chapter, explanation, source, tags FROM questions")
            if (where.isNotEmpty()) append(" WHERE ").append(where.joinToString(" AND "))
            when (mode) {
                // 随机：用 id 作伪随机盐，保证同一天同一题序是稳定的一批（便于"继续上次"）。
                ReviewMode.RANDOM -> append(" ORDER BY (qid || bank_id) * 1103515245 % 2147483647")
                ReviewMode.WRONG_FIRST -> append(
                    " ORDER BY CASE WHEN qid IN (SELECT question_id FROM wrong_book WHERE bank_id = questions.bank_id AND mastered = 0) THEN 0 ELSE 1 END, " +
                        "(qid || bank_id) * 1103515245 % 2147483647"
                )
                else -> append(" ORDER BY rowid")
            }
        }
        val arr = args.toTypedArray()
        return readableDatabase.rawQuery(sql, arr).use { c ->
            val out = ArrayList<ReviewQuestion>()
            while (c.moveToNext() && (limit <= 0 || out.size < limit)) {
                out.add(readQuestion(c))
            }
            out
        }
    }

    fun question(bankId: String, qid: String): ReviewQuestion? =
        readableDatabase.rawQuery(
            "SELECT bank_id, qid, type, stem, options, answer, chapter, explanation, source, tags " +
                "FROM questions WHERE bank_id = ? AND qid = ?",
            arrayOf(bankId, qid)
        ).use { c -> if (c.moveToFirst()) readQuestion(c) else null }

    private fun readQuestion(c: Cursor): ReviewQuestion {
        val options = try {
            val arr = JSONArray(c.getString(4))
            (0 until arr.length()).map { arr.optString(it) }
        } catch (e: Exception) {
            emptyList()
        }
        val tags = try {
            val arr = JSONArray(c.getString(9))
            (0 until arr.length()).map { arr.optString(it) }
        } catch (e: Exception) {
            emptyList()
        }
        return ReviewQuestion(
            id = c.getString(1),
            type = c.getString(2),
            stem = c.getString(3),
            options = options,
            answer = c.getString(5),
            chapter = c.getString(6),
            explanation = c.getString(7),
            source = c.getString(8),
            tags = tags,
            bankId = c.getString(0)
        )
    }

    fun totalCount(bankId: String? = null): Int {
        val sql = if (bankId == null) "SELECT COUNT(*) FROM questions" else "SELECT COUNT(*) FROM questions WHERE bank_id = ?"
        return readableDatabase.rawQuery(sql, bankId?.let { arrayOf(it) }).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    // —— 作答记录

    data class QuestionStat(val correctCount: Int, val wrongCount: Int, val lastResult: Boolean?)

    fun stat(bankId: String, qid: String): QuestionStat =
        readableDatabase.rawQuery(
            "SELECT correct_count, wrong_count, last_result FROM records WHERE bank_id = ? AND question_id = ?",
            arrayOf(bankId, qid)
        ).use { c ->
            if (!c.moveToFirst()) return@use QuestionStat(0, 0, null)
            QuestionStat(
                c.getInt(0),
                c.getInt(1),
                if (c.isNull(2)) null else c.getInt(2) == 1
            )
        }

    /**
     * 记一次作答：更新对错次数、错题本连对、每日统计。
     *
     * 错题本的「掌握」判定照 zhiyu 的口径：答错入册并把连对清零；之后连续答对
     * [MASTER_STREAK] 次标记为已掌握。掌握后答错要重新入册（mastered=0）。
     */
    fun recordAnswer(bankId: String, qid: String, correct: Boolean) {
        val db = writableDatabase
        val now = System.currentTimeMillis()
        db.beginTransaction()
        try {
            val cur = stat(bankId, qid)
            db.insertWithOnConflict(
                "records", null,
                ContentValues().apply {
                    put("bank_id", bankId)
                    put("question_id", qid)
                    put("correct_count", cur.correctCount + if (correct) 1 else 0)
                    put("wrong_count", cur.wrongCount + if (correct) 0 else 1)
                    put("last_result", if (correct) 1 else 0)
                    put("last_time", now)
                },
                SQLiteDatabase.CONFLICT_REPLACE
            )
            if (correct) {
                // 只有还在错题本里（未掌握）的题才累加连对；已掌握或从未答错的题不受影响。
                val entry = wrongEntry(bankId, qid)
                if (entry != null && !entry.mastered) {
                    val streak = entry.streak + 1
                    db.execSQL(
                        "UPDATE wrong_book SET streak = ?, mastered = ? WHERE bank_id = ? AND question_id = ?",
                        arrayOf<Any>(streak, if (streak >= MASTER_STREAK) 1 else 0, bankId, qid)
                    )
                }
            } else {
                db.execSQL(
                    "INSERT OR REPLACE INTO wrong_book " +
                        "(bank_id, question_id, wrong_count, last_wrong_at, mastered, streak) " +
                        "VALUES (?, ?, ?, ?, 0, 0)",
                    arrayOf<Any>(bankId, qid, cur.wrongCount + 1, now)
                )
            }
            db.execSQL(
                "INSERT OR REPLACE INTO daily_stats (day, answered, correct) " +
                    "VALUES (?, 1, ?)",
                arrayOf<Any>(today(), if (correct) 1 else 0)
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private data class WrongEntry(val wrongCount: Int, val streak: Int, val mastered: Boolean)

    private fun wrongEntry(bankId: String, qid: String): WrongEntry? =
        readableDatabase.rawQuery(
            "SELECT wrong_count, streak, mastered FROM wrong_book WHERE bank_id = ? AND question_id = ?",
            arrayOf(bankId, qid)
        ).use { c ->
            if (!c.moveToFirst()) null
            else WrongEntry(c.getInt(0), c.getInt(1), c.getInt(2) == 1)
        }

    fun toggleFavorite(bankId: String, qid: String): Boolean {
        val db = writableDatabase
        val exists = readableDatabase.rawQuery(
            "SELECT 1 FROM favorites WHERE bank_id = ? AND question_id = ?",
            arrayOf(bankId, qid)
        ).use { c -> c.moveToFirst() }
        return if (exists) {
            db.delete("favorites", "bank_id = ? AND question_id = ?", arrayOf(bankId, qid))
            false
        } else {
            db.insertWithOnConflict(
                "favorites", null,
                ContentValues().apply {
                    put("bank_id", bankId)
                    put("question_id", qid)
                    put("created_at", System.currentTimeMillis())
                },
                SQLiteDatabase.CONFLICT_REPLACE
            )
            true
        }
    }

    fun isFavorite(bankId: String, qid: String): Boolean =
        readableDatabase.rawQuery(
            "SELECT 1 FROM favorites WHERE bank_id = ? AND question_id = ?",
            arrayOf(bankId, qid)
        ).use { c -> c.moveToFirst() }

    data class Overview(
        val total: Int,
        val answered: Int,
        val correct: Int,
        val wrongPending: Int,
        val favorites: Int,
        val bankCount: Int
    )

    /**
     * 首页概览统计。
     *
     * 六条查询的 sql 与 args 由 [ReviewSql.overview] 成对生成 —— 以前这里是
     * 手写 `"...$where"` 拼接 + 一个 `args` 复用到所有查询上，两个坑：
     *  1. `bankId == null` 时 where 是空串，`wrong_book` 那条拼出
     *     `SELECT COUNT(*) FROM wrong_book AND mastered = 0`（AND 前无 WHERE）→ SQLiteException；
     *  2. `SELECT COUNT(*) FROM banks` 没有占位符却收到 1 个参数 →
     *     IllegalArgumentException: Cannot bind argument at index。
     * 两条都在「一进复习页」时必现，因为首页 LaunchedEffect 首个就读这里。
     */
    fun overview(bankId: String? = null): Overview {
        val q = ReviewSql.overview(bankId)
        fun scalar(query: ReviewSql.BoundQuery): Int =
            readableDatabase.rawQuery(query.sql, query.argsArray()).use { c ->
                if (c.moveToFirst()) c.getInt(0) else 0
            }
        return Overview(
            total = scalar(q.questions),
            answered = scalar(q.records),
            correct = scalar(q.correctSum),
            wrongPending = scalar(q.wrongPending),
            favorites = scalar(q.favorites),
            bankCount = scalar(q.banks)
        )
    }

    fun dailyStats(limitDays: Int = 30): List<Pair<String, Pair<Int, Int>>> =
        readableDatabase.rawQuery(
            "SELECT day, answered, correct FROM daily_stats ORDER BY day DESC LIMIT ?",
            arrayOf(limitDays.toString())
        ).use { c ->
            val out = ArrayList<Pair<String, Pair<Int, Int>>>()
            while (c.moveToNext()) out.add(c.getString(0) to (c.getInt(1) to c.getInt(2)))
            out.reversed()
        }

    private fun today(): String = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())

    companion object {
        private const val TAG = "ReviewDatabase"
        private const val DB_NAME = "review.db"
        private const val DB_VERSION = 1

        /** 错题连续答对多少次算「已掌握」。 */
        const val MASTER_STREAK = 3

        @Volatile
        private var instance: ReviewDatabase? = null

        fun get(context: Context): ReviewDatabase =
            instance ?: synchronized(this) {
                instance ?: ReviewDatabase(context).also { instance = it }
            }
    }
}

/** 练习模式。 */
enum class ReviewMode(val label: String) {
    SEQUENTIAL("顺序练习"),
    RANDOM("随机练习"),
    WRONG_FIRST("错题优先");

    companion object {
        fun fromName(name: String?): ReviewMode =
            entries.firstOrNull { it.name == name } ?: SEQUENTIAL
    }
}
