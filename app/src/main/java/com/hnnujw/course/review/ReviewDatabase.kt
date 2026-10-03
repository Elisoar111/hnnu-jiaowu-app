package com.hnnujw.course.review

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import org.json.JSONArray
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
 *  - slashed      斩题本（一眼会的题，移出后续练习，可恢复）
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
                due_at       INTEGER NOT NULL DEFAULT 0,
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
            CREATE TABLE IF NOT EXISTS slashed (
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
        // v2 → v3：wrong_book 增加 due_at（间隔复习的下次到期时刻）。
        // 🔴 **幂等迁移、绝不删表重建** —— 既有 wrong_book 数据必须原样保住。
        // 先 `PRAGMA table_info` 判列是否存在，避免了在"新建库直接建到最新版本"的路径上
        // 重复 ADD COLUMN（那会抛 duplicate column name）。
        if (!hasColumn(db, "wrong_book", "due_at")) {
            db.execSQL("ALTER TABLE wrong_book ADD COLUMN due_at INTEGER NOT NULL DEFAULT 0")
        }
    }

    /** 某张表是否已经有这一列（用于幂等迁移）。 */
    private fun hasColumn(db: SQLiteDatabase, table: String, column: String): Boolean =
        db.rawQuery("PRAGMA table_info($table)", null).use { c ->
            val nameIdx = c.getColumnIndex("name")
            while (c.moveToNext()) {
                if (nameIdx >= 0 && c.getString(nameIdx) == column) return true
            }
            false
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
                listOf("questions", "records", "wrong_book", "favorites", "slashed").forEach { t ->
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

    /**
     * 是否已存在同名题库。
     *
     * 给「内置题库自动导入」做前置检查用：[importBank] 对同名库是**覆盖语义**
     * （会连带 records / wrong_book / favorites 一起删），所以自动导入前必须先问一句，
     * 否则用户答题途中的一次自动补库就能把记录清空。
     */
    fun bankExists(name: String): Boolean =
        readableDatabase.rawQuery(
            "SELECT 1 FROM banks WHERE name = ? LIMIT 1", arrayOf(name)
        ).use { it.moveToFirst() }

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
            listOf("questions", "records", "wrong_book", "favorites", "slashed").forEach { t ->
                db.delete(t, "bank_id = ?", arrayOf(bankId))
            }
            db.delete("banks", "id = ?", arrayOf(bankId))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    // —— 题库管理：创建 / 重命名 / 逐题编辑 / 批量删题 / 合并 ——

    /**
     * 创建一个空题库。重名返回 null（由调用方先走 [BankManageLogic.validateBankName]）。
     *
     * 这里**再查一次重名**而不是只信上层：上层校验到落库之间隔着用户操作，
     * 而且 DB 层自己也该守住「不产生同名库」这条底线。
     */
    fun createBank(name: String): String? {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return null
        if (bankExists(trimmed)) return null
        val bankId = UUID.randomUUID().toString().replace("-", "").take(16)
        writableDatabase.insertWithOnConflict(
            "banks", null,
            ContentValues().apply {
                put("id", bankId)
                put("name", trimmed)
                put("question_count", 0)
                put("created_at", System.currentTimeMillis())
            },
            SQLiteDatabase.CONFLICT_ABORT
        )
        Log.i(TAG, "createBank: bankId=$bankId")
        return bankId
    }

    /**
     * 重命名题库。重名或不存在返回 false，成功返回 true。
     *
     * **只动 banks.name**：题目靠 `bank_id` 关联，records/wrong_book/favorites 同理，
     * 改名不涉及任何一张表的关联字段，所以题与记录一题都不会丢。
     * 这正是「改名」和「合并」在数据层上的本质区别。
     */
    fun renameBank(bankId: String, newName: String): Boolean {
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) return false
        val db = writableDatabase
        // 排掉自己再判重名，否则「不改名直接保存」会被自己挡下
        val clash = db.rawQuery(
            "SELECT 1 FROM banks WHERE name = ? AND id <> ? LIMIT 1",
            arrayOf(trimmed, bankId)
        ).use { it.moveToFirst() }
        if (clash) return false
        val rows = db.update(
            "banks", ContentValues().apply { put("name", trimmed) },
            "id = ?", arrayOf(bankId)
        )
        if (rows > 0) Log.i(TAG, "renameBank: bankId=$bankId")
        return rows > 0
    }

    /**
     * 逐题编辑。只改该 `(bankId, qid)` 一行的内容，不动答题记录 ——
     * 改的是题干/选项/答案，不是「这道题用户答得怎么样」。
     *
     * `options` / `tags` 用 JSON 数组存（与 [insertQuestion] 同一口径）。
     */
    fun updateQuestion(
        bankId: String,
        qid: String,
        stem: String,
        options: List<String>,
        answer: String,
        analysis: String
    ): Boolean {
        val rows = writableDatabase.update(
            "questions",
            ContentValues().apply {
                put("stem", stem)
                put("options", JSONArray(options).toString())
                put("answer", answer)
                put("explanation", analysis)
            },
            "bank_id = ? AND qid = ?", arrayOf(bankId, qid)
        )
        return rows > 0
    }

    /**
     * 按 qid 读取某库的题目，供编辑页预填。
     *
     * 列顺序必须与 [readQuestion] 一致（`bank_id` 在第 0 列），否则字段会整体错位。
     */
    fun questionById(bankId: String, qid: String): ReviewQuestion? =
        readableDatabase.rawQuery(
            "SELECT bank_id, qid, type, stem, options, answer, chapter, explanation, source, tags " +
                "FROM questions WHERE bank_id = ? AND qid = ? LIMIT 1",
            arrayOf(bankId, qid)
        ).use { c -> if (c.moveToFirst()) readQuestion(c) else null }

    /**
     * 列出某库的全部题目，供逐题编辑页展示。按 qid 稳定排序。
     *
     * 同样保持 [readQuestion] 的列顺序（`bank_id` 在第 0 列）。
     */
    fun listQuestions(bankId: String): List<ReviewQuestion> =
        readableDatabase.rawQuery(
            "SELECT bank_id, qid, type, stem, options, answer, chapter, explanation, source, tags " +
                "FROM questions WHERE bank_id = ? ORDER BY qid",
            arrayOf(bankId)
        ).use { c ->
            val out = ArrayList<ReviewQuestion>()
            while (c.moveToNext()) out.add(readQuestion(c))
            out
        }

    /**
     * 批量删题（按 qid 列表），**同时清掉这些题的 records / wrong_book / favorites**。
     *
     * 不清就是「指向不存在题目的孤儿行」：错题本会显示一道打不开的题、
     * 首页错题计数会永远减不下去。整批放在一个事务里，中途失败不会只删一半。
     */
    fun deleteQuestions(bankId: String, qids: List<String>): Int {
        if (qids.isEmpty()) return 0
        val db = writableDatabase
        var deleted = 0
        db.beginTransaction()
        try {
            for (qid in qids) {
                // whereArgs 签名是 Array<out String>?，不是 execSQL 的 Array<out Any> ——
                // 这里必须让 Kotlin 推断成 Array<String>，写 arrayOf<Any> 编译不过。
                db.delete("questions", "bank_id = ? AND qid = ?", arrayOf(bankId, qid))
                db.delete("records", "bank_id = ? AND question_id = ?", arrayOf(bankId, qid))
                db.delete("wrong_book", "bank_id = ? AND question_id = ?", arrayOf(bankId, qid))
                db.delete("favorites", "bank_id = ? AND question_id = ?", arrayOf(bankId, qid))
                db.delete("slashed", "bank_id = ? AND question_id = ?", arrayOf(bankId, qid))
                deleted++
            }
            refreshQuestionCount(db, bankId)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        Log.i(TAG, "deleteQuestions: bankId=$bankId count=$deleted")
        return deleted
    }

    /** 按 banks 里实际剩余的题目行数刷新 question_count，避免计数与内容漂移。 */
    private fun refreshQuestionCount(db: SQLiteDatabase, bankId: String) {
        val count = db.rawQuery(
            "SELECT COUNT(*) FROM questions WHERE bank_id = ?", arrayOf(bankId)
        ).use { if (it.moveToFirst()) it.getInt(0) else 0 }
        db.update(
            "banks", ContentValues().apply { put("question_count", count) },
            "id = ?", arrayOf(bankId)
        )
    }

    /**
     * 把 [sourceBankIds] 的**题目**合并进 [targetBankId]，返回报告。
     *
     * 合并语义（硬约束，见 [BankManageLogic.planQidRemap] 的注释）：
     * 1. **只搬题，不搬答题记录**。records / wrong_book / favorites 是 `(bank_id, qid)` 维度，
     *    跨库迁移会让两个库同号题的错题本串在一起 —— 用户会看到别人的错题。所以一律不迁移。
     * 2. **qid 冲突重分配**。撞上目标库已有 qid 的源题走 `planQidRemap` 分配新号，
     *    绝不能靠 `INSERT OR REPLACE` 把目标库原题覆盖掉。
     * 3. **整批一个事务**：中途失败不能留下半合并状态（一部分题进了目标库、一部分还在源库）。
     * 4. **不自动删源库**：由 UI 拿 [BankManageLogic.MergeReport] 弹二次确认再调 [deleteBank]。
     *
     * 幂等性：同一源题重复合并会被重分配到不同 qid，所以调用方应在成功后立刻删源库
     * （或至少不要让用户对同一对库连点两次）。这也是要求 UI 防重复点击的原因。
     */
    fun mergeBanks(targetBankId: String, sourceBankIds: List<String>): BankManageLogic.MergeReport {
        val db = writableDatabase
        val sources = sourceBankIds.filter { it.isNotBlank() && it != targetBankId }.distinct()
        if (sources.isEmpty()) return BankManageLogic.MergeReport(0, 0, emptyList())

        val sourceNames = ArrayList<String>(sources.size)
        var moved = 0
        var renamed = 0

        db.beginTransaction()
        try {
            // 目标库现有 qid 集合（含本次已分配的），用于冲突判定
            val taken = HashSet<String>()
            db.rawQuery(
                "SELECT qid FROM questions WHERE bank_id = ?", arrayOf(targetBankId)
            ).use { c -> while (c.moveToNext()) taken.add(c.getString(0)) }

            for (srcId in sources) {
                db.rawQuery(
                    "SELECT name FROM banks WHERE id = ? LIMIT 1", arrayOf(srcId)
                ).use { c -> if (c.moveToFirst()) sourceNames.add(c.getString(0)) }

                // 读源库全部题目（按 qid 稳定排序，保证重分配结果可复现）
                // 列顺序同 [readQuestion]：bank_id 必须在第 0 列
                val incoming = ArrayList<ReviewQuestion>()
                db.rawQuery(
                    "SELECT bank_id, qid, type, stem, options, answer, chapter, explanation, source, tags " +
                        "FROM questions WHERE bank_id = ? ORDER BY qid",
                    arrayOf(srcId)
                ).use { c ->
                    while (c.moveToNext()) incoming.add(readQuestion(c))
                }

                // 目标库已有 qid + 源库内部可能自带的重复，一次性交给纯函数算重映射。
                // 注意：重分配只保证「不与目标库已有冲突」，源库之间的重复由同一张 remap 保证一致。
                val remap = BankManageLogic.planQidRemap(taken, incoming.map { it.id })
                for (q in incoming) {
                    val newQid = remap[q.id] ?: q.id
                    if (newQid != q.id) renamed++
                    taken.add(newQid)
                    insertQuestion(db, targetBankId, q.copy(id = newQid, bankId = targetBankId), System.currentTimeMillis())
                    moved++
                }
            }

            refreshQuestionCount(db, targetBankId)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        Log.i(TAG, "mergeBanks: target=$targetBankId sources=${sources.size} moved=$moved renamed=$renamed")
        return BankManageLogic.MergeReport(
            totalMoved = moved,
            renamedCount = renamed,
            sourceNames = sourceNames
        )
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
        limit: Int = 0,
        /** 是否把已斩的题排除在练习之外。默认排除 —— 斩题的意思就是"别再让我练它"。 */
        excludeSlashed: Boolean = true,
        /** 只练已斩的题（从斩题本开练时用）；与 [excludeSlashed] 互斥，本参数优先。 */
        onlySlashed: Boolean = false,
        /** 只练**今日到期**的错题（间隔复习的「今日待复习」入口）。 */
        dueOnly: Boolean = false
    ): List<ReviewQuestion> {
        val where = ArrayList<String>()
        val args = ArrayList<String>()
        if (bankId != null) { where.add("bank_id = ?"); args.add(bankId) }
        if (chapter != null) { where.add("chapter = ?"); args.add(chapter) }
        if (dueOnly) {
            // 到期口径与 ReviewScheduling.isDue 一致：due_at=0（未排期）也算到期。
            val now = System.currentTimeMillis().toString()
            where.add(
                "qid IN (SELECT question_id FROM wrong_book WHERE bank_id = questions.bank_id " +
                    "AND mastered = 0 AND (due_at = 0 OR due_at <= $now))"
            )
        } else if (onlyWrong) {
            where.add("qid IN (SELECT question_id FROM wrong_book WHERE bank_id = questions.bank_id AND mastered = 0)")
        }
        if (onlyFavorite) where.add("qid IN (SELECT question_id FROM favorites WHERE bank_id = questions.bank_id)")
        if (onlySlashed) {
            where.add("qid IN (SELECT question_id FROM slashed WHERE bank_id = questions.bank_id)")
        } else if (excludeSlashed) {
            where.add("qid NOT IN (SELECT question_id FROM slashed WHERE bank_id = questions.bank_id)")
        }
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
                    val level = masteryLevelOf(streak, entry.wrongCount)
                    // 答对即"复习过一次"：按新等级排下次到期时间（间隔重复的核心）。
                    val nextDue = dueAt(level, now)
                    db.execSQL(
                        "UPDATE wrong_book SET streak = ?, mastered = ?, due_at = ? " +
                            "WHERE bank_id = ? AND question_id = ?",
                        arrayOf<Any>(streak, if (streak >= MASTER_STREAK) 1 else 0, nextDue, bankId, qid)
                    )
                }
            } else {
                // 答错：回炉。错误次数 +1，连对清零，等级回到 Learning，到期时间按新等级重排。
                val wrongCount = cur.wrongCount + 1
                val level = masteryLevelOf(0, wrongCount)
                val nextDue = dueAt(level, now)
                db.execSQL(
                    "INSERT OR REPLACE INTO wrong_book " +
                        "(bank_id, question_id, wrong_count, last_wrong_at, mastered, streak, due_at) " +
                        "VALUES (?, ?, ?, ?, 0, 0, ?)",
                    arrayOf<Any>(bankId, qid, wrongCount, now, nextDue)
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

    // —— 斩题本

    /**
     * 斩一道题（移出后续练习）。幂等：已斩过再斩不会重复写、也不报错。
     *
     * 🔴 **只写 `slashed` 表**，绝不碰 `wrong_book` / `records` / `mastered` ——
     * 「斩题 ≠ 已掌握」，斩是主观跳过，掌握是客观答对，两者不能混（见 [SlashPolicy]）。
     *
     * @return true = 本次真的斩了；false = 之前就已斩（幂等命中）。
     */
    fun slashQuestion(bankId: String, qid: String): Boolean {
        if (isSlashed(bankId, qid)) return false
        writableDatabase.insertWithOnConflict(
            "slashed", null,
            ContentValues().apply {
                put("bank_id", bankId)
                put("question_id", qid)
                put("created_at", System.currentTimeMillis())
            },
            SQLiteDatabase.CONFLICT_REPLACE
        )
        return true
    }

    /** 取消斩题（放回后续练习）。返回 true = 本次真的放回了。 */
    fun unslashQuestion(bankId: String, qid: String): Boolean =
        writableDatabase.delete("slashed", "bank_id = ? AND question_id = ?", arrayOf(bankId, qid)) > 0

    fun isSlashed(bankId: String, qid: String): Boolean =
        readableDatabase.rawQuery(
            "SELECT 1 FROM slashed WHERE bank_id = ? AND question_id = ?",
            arrayOf(bankId, qid)
        ).use { c -> c.moveToFirst() }

    /** 斩题数；[bankId] 为 null 时统计全部题库。 */
    fun slashedCount(bankId: String? = null): Int {
        val sql = if (bankId == null) {
            "SELECT COUNT(*) FROM slashed"
        } else {
            "SELECT COUNT(*) FROM slashed WHERE bank_id = ?"
        }
        val args = if (bankId == null) emptyArray() else arrayOf(bankId)
        return readableDatabase.rawQuery(sql, args).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
    }

    /** 斩题本列表；[bankId] 为 null 时跨题库取。 */
    fun listSlashedQuestions(bankId: String? = null): List<ReviewQuestion> {
        val where = if (bankId == null) "" else " WHERE q.bank_id = ?"
        val args = if (bankId == null) emptyArray() else arrayOf(bankId)
        return readableDatabase.rawQuery(
            "SELECT q.bank_id, q.qid, q.type, q.stem, q.options, q.answer, q.chapter, " +
                "q.explanation, q.source, q.tags FROM questions q " +
                "JOIN slashed s ON s.bank_id = q.bank_id AND s.question_id = q.qid$where " +
                "ORDER BY s.created_at DESC",
            args
        ).use { c ->
            val out = ArrayList<ReviewQuestion>()
            while (c.moveToNext()) out.add(readQuestion(c))
            out
        }
    }

    // —— 间隔复习（今日到期）

    /**
     * 今日到期的错题（未掌握 且 `due_at` 已到）。
     *
     * 到期判据与 [ReviewScheduling.isDue] 一致，但 SQL 里用条件表达：
     *  - `due_at = 0`（从未排期 / 老数据）视为**立即到期**；
     *  - 否则 `now >= due_at` 即到期。
     *
     * 只取**未掌握**的题（已掌握的不该再进复习队列）；[bankId] 为 null 时跨库取。
     */
    fun dueWrongQuestions(bankId: String? = null, now: Long = System.currentTimeMillis()): List<ReviewQuestion> {
        val where = ArrayList<String>()
        val args = ArrayList<String>()
        if (bankId != null) {
            where.add("q.bank_id = ?")
            args.add(bankId)
        }
        where.add("w.mastered = 0")
        where.add("(w.due_at = 0 OR w.due_at <= ?)")
        args.add(now.toString())
        return readableDatabase.rawQuery(
            "SELECT q.bank_id, q.qid, q.type, q.stem, q.options, q.answer, q.chapter, " +
                "q.explanation, q.source, q.tags FROM questions q " +
                "JOIN wrong_book w ON w.bank_id = q.bank_id AND w.question_id = q.qid " +
                "WHERE ${where.joinToString(" AND ")} " +
                "ORDER BY w.last_wrong_at ASC",
            args.toTypedArray()
        ).use { c ->
            val out = ArrayList<ReviewQuestion>()
            while (c.moveToNext()) out.add(readQuestion(c))
            out
        }
    }

    /** 今日到期的错题数（只数不取题，给入口角标用）。 */
    fun dueWrongCount(bankId: String? = null, now: Long = System.currentTimeMillis()): Int {
        val where = ArrayList<String>()
        val args = ArrayList<String>()
        if (bankId != null) {
            where.add("bank_id = ?")
            args.add(bankId)
        }
        where.add("mastered = 0")
        where.add("(due_at = 0 OR due_at <= ?)")
        args.add(now.toString())
        return readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM wrong_book WHERE ${where.joinToString(" AND ")}",
            args.toTypedArray()
        ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
    }

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
        private const val DB_VERSION = 3

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
