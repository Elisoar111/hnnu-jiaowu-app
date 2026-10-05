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
 *  - meta         杂项键值
 *
 * 🔴 `daily_stats`（每日答题量）已随统计页一起下线（2026-10 用户要求删除统计）。
 * 表**不再创建、不再写入**：它当时只有统计页一个读方，留着就是"只写不读"的黑洞。
 * 老安装里那张表会作为空壳残留（里面还有历史数据），不影响任何功能；
 * 想彻底清掉可以卸载重装，或等一次数据库重建。
 *
 * 🔴 `slashed`（斩题本）已随斩题功能一起删除（2026-10 用户决定彻底清理）：
 * 斩题的 UI 入口此前已经移除，留着表和过滤只会让"以前斩过的题永远抽不出来"
 * 且无入口找回。v5 迁移直接 DROP 这张表 —— 历史被斩的题全部放回练习。
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
                created_at  INTEGER NOT NULL,
                selected    INTEGER NOT NULL DEFAULT 0,
                sort_order  INTEGER NOT NULL DEFAULT 0
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
        // v3 → v4：banks 增加「是否已从题库仓库取出」与「取出后的排序」两列。
        //
        // 🔴 存量行必须**迁移成 selected = 1**：升级前所有题库都是直接显示在首页的，
        // 默认值 0 会让用户升级后打开复习页发现"题库全没了"（数据还在，但一个都不显示）。
        // sort_order 按 rowid 铺一遍，保持升级前的相对顺序。
        if (!hasColumn(db, "banks", "selected")) {
            db.execSQL("ALTER TABLE banks ADD COLUMN selected INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE banks ADD COLUMN sort_order INTEGER NOT NULL DEFAULT 0")
            db.execSQL("UPDATE banks SET selected = 1, sort_order = rowid")
        } else if (!hasColumn(db, "banks", "sort_order")) {
            db.execSQL("ALTER TABLE banks ADD COLUMN sort_order INTEGER NOT NULL DEFAULT 0")
            db.execSQL("UPDATE banks SET sort_order = rowid")
        }
        // v4 → v5：斩题功能彻底下线，DROP slashed 表。斩题的 UI 入口早已移除，
        // 表留着只会让历史被斩的题永远抽不出来、且无入口找回 —— 直接删表，
        // 这些题全部放回练习。DROP IF EXISTS 幂等，老库新库跑一遍都安全。
        db.execSQL("DROP TABLE IF EXISTS slashed")
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

    /**
     * 导入一个题库，返回 bankId。已存在同名同数量的题库时直接复用，避免重复导入堆库。
     *
     * @param selected 是否**同时从题库仓库取出来**（即出现在首页「我的题库」里）。
     *   默认 true —— 用户主动导入/创建一个库，意图就是要用它，导完什么都不显示
     *   反而像失败。内置题库的自动导入走 `selected = false`（见 [BundledBanks]）。
     */
    fun importBank(
        name: String,
        questions: List<ReviewQuestion>,
        selected: Boolean = true
    ): String {
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
                        put("selected", if (selected) 1 else 0)
                        // 新取出的库排到末尾（sort_order 取当前最大值 + 1）。
                        put("sort_order", if (selected) nextSortOrder(db) else 0)
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

    /**
     * 内置题库改名的存量迁移（2026-10 两个内置库启用正式课名，对照表在 [BundledBanks.RENAMED_BANKS]）。
     *
     * 老设备已按旧名导入的库不会因改名被重复导入（自动导入门槛是「一个库都没有」），
     * 但库名会一直停留在旧名 —— 首次取库时在这里一次性改过来。幂等：
     *  - 旧名不存在 → 跳过；
     *  - 新名已被用户自建的库占用 → [renameBank] 的排己查重挡下，跳过，不硬改。
     */
    fun migrateBundledBankNames() {
        BundledBanks.RENAMED_BANKS.forEach { (oldName, newName) ->
            val bankId = readableDatabase.rawQuery(
                "SELECT id FROM banks WHERE name = ? LIMIT 1", arrayOf(oldName)
            ).use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: return@forEach
            renameBank(bankId, newName)
        }
    }

    data class BankSummary(
        val id: String,
        val name: String,
        val questionCount: Int,
        val createdAt: Long,
        /** 错题本里还没掌握的题数。 */
        val pendingWrong: Int,
        /** 是否已从题库仓库取出（= 出现在首页「我的题库」里）。 */
        val selected: Boolean = false,
        /** 取出后的排序权重，越小越靠前。未取出时为 0（无意义）。 */
        val sortOrder: Int = 0
    )

    /**
     * 列出**全部**题库（含未取出的），已取出的排在前面且按 [BankSummary.sortOrder] 升序。
     *
     * 首页用它一次拿全，再在内存里切成「我的题库 / 仓库」两段 —— 题库数量是十几的量级，
     * 多查一次不如一次拿全。ORDER BY 里带上 `created_at DESC` 作兜底：
     * sort_order 全为 0（未取出的行）时仍有稳定顺序。
     */
    fun listBanks(): List<BankSummary> = readableDatabase.rawQuery(
        "SELECT b.id, b.name, b.question_count, b.created_at, " +
            "(SELECT COUNT(*) FROM wrong_book w WHERE w.bank_id = b.id AND w.mastered = 0) AS pending, " +
            "b.selected, b.sort_order " +
            "FROM banks b ORDER BY b.selected DESC, b.sort_order ASC, b.created_at DESC", null
    ).use { c ->
        val out = ArrayList<BankSummary>()
        while (c.moveToNext()) {
            out.add(
                BankSummary(
                    id = c.getString(0),
                    name = c.getString(1),
                    questionCount = c.getInt(2),
                    createdAt = c.getLong(3),
                    pendingWrong = c.getInt(4),
                    selected = c.getInt(5) != 0,
                    sortOrder = c.getInt(6)
                )
            )
        }
        out
    }

    /** 只取一个题库的名字（不存在时返回空串）。详情页/编辑页只需要个标题，犯不着跑 [listBanks] 全表。 */
    fun bankName(bankId: String): String =
        readableDatabase.rawQuery("SELECT name FROM banks WHERE id = ?", arrayOf(bankId)).use { c ->
            if (c.moveToFirst()) c.getString(0) else ""
        }

    // —— 题库仓库：取出 / 移出 / 排序 ——

    /** 下一个可用的排序权重（当前最大值 + 1）。 */
    private fun nextSortOrder(db: SQLiteDatabase): Int =
        db.rawQuery("SELECT COALESCE(MAX(sort_order), 0) + 1 FROM banks WHERE selected = 1", null)
            .use { c -> if (c.moveToFirst()) c.getInt(0) else 1 }

    /**
     * 从仓库取出一个题库（[selected] = true）或移出回仓库（false）。
     *
     * **只改显示状态，绝不删数据** —— 题目、答题记录、错题本、收藏全都原样留着，
     * 所以"移出"是可逆的。取出时排到末尾，符合"刚拿出来的东西放在最后"的直觉。
     */
    fun setBankSelected(bankId: String, selected: Boolean) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("selected", if (selected) 1 else 0)
            // 取出时排到末尾，符合"刚拿出来的东西放在最后"的直觉。
            if (selected) put("sort_order", nextSortOrder(db))
        }
        db.update("banks", values, "id = ?", arrayOf(bankId))
    }

    /**
     * 在「我的题库」里把某个题库上移/下移一位（[delta] = -1 / +1）。
     *
     * 用**相邻两条互换 sort_order** 而不是重排整张表：重排要写 N 行，互换只写 2 行，
     * 且不会因为并发改动而整体错位。
     */
    fun moveBank(bankId: String, delta: Int) {
        if (delta == 0) return
        val ordered = listBanks().filter { it.selected }
        val from = ordered.indexOfFirst { it.id == bankId }
        if (from < 0) return
        val to = (from + delta).coerceIn(0, ordered.lastIndex)
        if (to == from) return
        swapSortOrder(ordered[from].id, ordered[to].id)
    }

    /**
     * 按给定顺序重写「我的题库」的排序（拖拽结束后调用）。
     *
     * 传进来的 id 里不含的题库不动；不在「我的题库」里的 id 会被 `selected = 1` 条件挡掉。
     */
    fun reorderSelectedBanks(orderedIds: List<String>) {
        if (orderedIds.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            orderedIds.forEachIndexed { index, id ->
                db.update(
                    "banks", ContentValues().apply { put("sort_order", index) },
                    "id = ? AND selected = 1", arrayOf(id)
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /**
     * 互换两条题库的 sort_order。
     *
     * 两者的 sort_order 相同（迁移前的老数据、或都还是 0）时互换等于没动，
     * 此时退化成「按当前顺序重排一遍」，至少让这次点击有可见效果。
     */
    private fun swapSortOrder(firstId: String, secondId: String) {
        val db = writableDatabase
        val all = listBanks()
        val first = all.firstOrNull { it.id == firstId } ?: return
        val second = all.firstOrNull { it.id == secondId } ?: return
        if (first.sortOrder == second.sortOrder) {
            val ordered = all.filter { it.selected }.map { it.id }.toMutableList()
            val i = ordered.indexOf(firstId)
            val j = ordered.indexOf(secondId)
            if (i >= 0 && j >= 0) {
                ordered[i] = secondId
                ordered[j] = firstId
                reorderSelectedBanks(ordered)
            }
            return
        }
        db.beginTransaction()
        try {
            db.update(
                "banks", ContentValues().apply { put("sort_order", second.sortOrder) },
                "id = ?", arrayOf(firstId)
            )
            db.update(
                "banks", ContentValues().apply { put("sort_order", first.sortOrder) },
                "id = ?", arrayOf(secondId)
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
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

    // —— 题库管理：重命名 / 逐题编辑 / 批量删题 ——

    /**
     * 重命名题库。重名或不存在返回 false，成功返回 true。
     *
     * **只动 banks.name**：题目靠 `bank_id` 关联，records/wrong_book/favorites 同理，
     * 改名不涉及任何一张表的关联字段，所以题与记录一题都不会丢。
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

    // —— 取题

    /**
     * 「章节 → 题数」，按**章节自然序**排列。
     *
     * 🔴 排序刻意**不在 SQL 里做**（原先的 `ORDER BY chapter` 已删除）：SQLite 是逐字节
     * 比较，而章节名里的编号是中文数字，排出来是「第一章、第七章、第三章…」这种错乱顺序
     * （2026-10 用户报「章节顺序问题」）。改由 [sortChaptersWithCount] 在 Kotlin 层按
     * 解析出的编号排序 —— 见 `ChapterOrder.kt` 的说明。
     */
    fun chaptersWithCount(bankId: String? = null): List<Pair<String, Int>> {
        val sql = StringBuilder(
            "SELECT chapter, COUNT(*) FROM questions"
        )
        val args: Array<String>? = if (bankId == null) null else {
            sql.append(" WHERE bank_id = ?")
            arrayOf(bankId)
        }
        sql.append(" GROUP BY chapter")
        val raw = readableDatabase.rawQuery(sql.toString(), args).use { c ->
            val out = ArrayList<Pair<String, Int>>()
            while (c.moveToNext()) out.add(c.getString(0) to c.getInt(1))
            out
        }
        return sortChaptersWithCount(raw)
    }

    /**
     * 按条件抽题。
     *
     * @param mode 练习模式，见 [ReviewMode]。
     * @param chapters 只抽这些章节的题；**空列表 = 全部章节**（2026-10 起支持多选）。
     * @param limit 抽题上限；0 = 不限（错题本/收藏全量）。
     */
    fun pickQuestions(
        bankId: String? = null,
        chapters: List<String> = emptyList(),
        mode: ReviewMode = ReviewMode.SEQUENTIAL,
        onlyWrong: Boolean = false,
        onlyFavorite: Boolean = false,
        limit: Int = 0,
        /** 只练**今日到期**的错题（间隔复习的「今日待复习」入口）。 */
        dueOnly: Boolean = false
    ): List<ReviewQuestion> {
        val where = ArrayList<String>()
        val args = ArrayList<String>()
        if (bankId != null) { where.add("bank_id = ?"); args.add(bankId) }
        // 多选章节：展开成 `chapter IN (?, ?, …)`。不用 `IN (…)` 拼字面量 —— 章节名里
        // 有引号（内置题库就有「第七章 “三个代表”重要思想」），拼进 SQL 会语法错误。
        val picked = chapters.filter { it.isNotBlank() }
        if (picked.isNotEmpty()) {
            where.add("chapter IN (" + picked.joinToString(", ") { "?" } + ")")
            args.addAll(picked)
        }
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
        val sql = buildString {
            append("SELECT bank_id, qid, type, stem, options, answer, chapter, explanation, source, tags FROM questions")
            if (where.isNotEmpty()) append(" WHERE ").append(where.joinToString(" AND "))
            // 排序只保底 rowid —— 乱序/错题优先的真正排序在 Kotlin 层做
            // （见 QuestionOrder.kt 的历史事故注释：SQL 里任何哈希表达式都依赖
            // TEXT CAST 成数字，qid 非数字开头就全体退化成 0，「乱序」悄悄失效）。
            append(" ORDER BY rowid")
            // LIMIT 只对顺序模式下推：乱序/错题优先必须读全量重排后再截断，
            // 在 SQL 里截了序就错了。
            if (mode == ReviewMode.SEQUENTIAL && limit > 0) append(" LIMIT ").append(limit)
        }
        val arr = args.toTypedArray()
        val rows = readableDatabase.rawQuery(sql, arr).use { c ->
            val out = ArrayList<ReviewQuestion>()
            while (c.moveToNext()) out.add(readQuestion(c))
            out
        }
        return when (mode) {
            ReviewMode.RANDOM -> rows.sortedWith(randomOrderComparator())
            ReviewMode.WRONG_FIRST ->
                rows.sortedWith(wrongFirstOrderComparator(unmasteredWrongKeys()))
            else -> if (limit > 0) rows.take(limit) else rows
        }
    }

    /** 错题本里未掌握题目的复合键（`bankId::qid`），给 [wrongFirstOrderComparator] 用。 */
    private fun unmasteredWrongKeys(): Set<String> =
        readableDatabase.rawQuery(
            "SELECT bank_id, question_id FROM wrong_book WHERE mastered = 0", null
        ).use { c ->
            val out = HashSet<String>()
            while (c.moveToNext()) out.add(c.getString(0) + "::" + c.getString(1))
            out
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
        recordAnswers(listOf(AnswerRecord(bankId, qid, correct)))
    }

    /** 一条待落库的作答。 */
    data class AnswerRecord(val bankId: String, val qid: String, val correct: Boolean)

    /**
     * 批量记作答，**整批一个事务**。
     *
     * 交卷补判（[com.hnnujw.course.review.PracticeSession.judgePending] 返回的一批）
     * 曾经是每题一个协程、一个独立事务 —— 一次补判 N 题就是 N 个事务。这里合并成
     * 一个：整批要么全记上、要么全没记上，失败由调用方提示兜底。
     */
    fun recordAnswers(items: List<AnswerRecord>) {
        if (items.isEmpty()) return
        val db = writableDatabase
        val now = System.currentTimeMillis()
        db.beginTransaction()
        try {
            for (item in items) {
                val cur = stat(item.bankId, item.qid)
                db.insertWithOnConflict(
                    "records", null,
                    ContentValues().apply {
                        put("bank_id", item.bankId)
                        put("question_id", item.qid)
                        put("correct_count", cur.correctCount + if (item.correct) 1 else 0)
                        put("wrong_count", cur.wrongCount + if (item.correct) 0 else 1)
                        put("last_result", if (item.correct) 1 else 0)
                        put("last_time", now)
                    },
                    SQLiteDatabase.CONFLICT_REPLACE
                )
                if (item.correct) {
                    // 只有还在错题本里（未掌握）的题才累加连对；已掌握或从未答错的题不受影响。
                    val entry = wrongEntry(item.bankId, item.qid)
                    if (entry != null && !entry.mastered) {
                        val streak = entry.streak + 1
                        val level = masteryLevelOf(streak, entry.wrongCount)
                        // 答对即"复习过一次"：按新等级排下次到期时间（间隔重复的核心）。
                        val nextDue = dueAt(level, now)
                        db.execSQL(
                            "UPDATE wrong_book SET streak = ?, mastered = ?, due_at = ? " +
                                "WHERE bank_id = ? AND question_id = ?",
                            arrayOf<Any>(streak, if (streak >= MASTER_STREAK) 1 else 0, nextDue, item.bankId, item.qid)
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
                        arrayOf<Any>(item.bankId, item.qid, wrongCount, now, nextDue)
                    )
                }
            }
            // 🔴 这里原本还有一条 `INSERT OR REPLACE INTO daily_stats (day, answered, correct)
            // VALUES (?, 1, ?)`。它随统计页一起删掉了，顺带说明它当时**本来就是坏的**：
            // OR REPLACE 撞主键是整行替换，所以每天的 answered 永远被写成 1 ——
            // 「近 7 天每日答题量」显示的从来不是答题量，而是一排 1。
            // 一个没人能正确解读的指标，比没有指标更糟。
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

    /**
     * 收藏的题（最近收藏在前）。
     *
     * [bankId] 为 null 时跨题库取 —— 与 [listWrongBook] 同一口径。
     * 2026-10 用户要求「收藏的题…可以练习」，[pickQuestions] 的 `onlyFavorite` 早已支持
     * 抽题，缺的只是一个能看清"我收藏了哪些"的入口。
     */
    fun listFavorites(bankId: String? = null): List<ReviewQuestion> {
        val where = if (bankId == null) "" else " WHERE q.bank_id = ?"
        val args = if (bankId == null) emptyArray() else arrayOf(bankId)
        return readableDatabase.rawQuery(
            "SELECT q.bank_id, q.qid, q.type, q.stem, q.options, q.answer, q.chapter, " +
                "q.explanation, q.source, q.tags FROM questions q " +
                "JOIN favorites f ON f.bank_id = q.bank_id AND f.question_id = q.qid$where " +
                "ORDER BY f.created_at DESC",
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

    // —— 错题本列表

    /**
     * 错题本条目：题目本身 + 它在 `wrong_book` 里的掌握数据。
     *
     * [status] / [level] 都是**由库里已有的列现算**的展示层派生值（见 [WrongStatus]
     * 与 [masteryLevelOf]），不落库、不需要迁移 —— 老数据一行都不用动。
     */
    data class WrongBookEntry(
        val question: ReviewQuestion,
        val wrongCount: Int,
        val mastered: Boolean,
        val streak: Int,
        /** 下次到期时刻（毫秒）。`0` = 从未排期，视为立即到期。 */
        val dueAt: Long,
        val lastWrongAt: Long
    ) {
        /** 三态：复习中 / 未掌握 / 已掌握。 */
        val status: WrongStatus get() = wrongStatus(mastered, wrongCount)

        /** 间隔复习等级：新题 / 学习中 / 较熟 / 已掌握。 */
        val level: MasteryLevel get() = masteryLevelOf(streak, wrongCount)
    }

    /**
     * 某题库（[bankId] 为 null 时跨库）的错题本列表，**含已掌握的题**。
     *
     * 刻意**不在 SQL 里过滤掌握状态**：筛选是展示层的事（[WrongStatusFilter]），
     * 在 SQL 里过滤就得为 5 个筛选项各写一条 SQL，三态判据也会被拆到两处；
     * 而错题本的量级是几百行，全量取回再筛没有性能问题。
     */
    fun listWrongBook(bankId: String? = null): List<WrongBookEntry> {
        val where = if (bankId == null) "" else " WHERE q.bank_id = ?"
        val args = if (bankId == null) emptyArray() else arrayOf(bankId)
        return readableDatabase.rawQuery(
            "SELECT q.bank_id, q.qid, q.type, q.stem, q.options, q.answer, q.chapter, " +
                "q.explanation, q.source, q.tags, " +
                "w.wrong_count, w.mastered, w.streak, w.due_at, w.last_wrong_at " +
                "FROM questions q " +
                "JOIN wrong_book w ON w.bank_id = q.bank_id AND w.question_id = q.qid$where " +
                "ORDER BY w.last_wrong_at DESC",
            args
        ).use { c ->
            val out = ArrayList<WrongBookEntry>()
            while (c.moveToNext()) {
                out.add(
                    WrongBookEntry(
                        // readQuestion 只读 0..9 列，所以必须先于下面的 10..14 列取值调用。
                        question = readQuestion(c),
                        wrongCount = c.getInt(10),
                        mastered = c.getInt(11) == 1,
                        streak = c.getInt(12),
                        dueAt = c.getLong(13),
                        lastWrongAt = c.getLong(14)
                    )
                )
            }
            out
        }
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

    // 🔴 `dailyStats(limitDays)` 已随统计页一起删除（2026-10 用户要求「将查看的统计删除」）。
    // 它的唯一调用点就是那个页面，留着就成了一段永远不执行的查询。
    // 每日答题量这个指标目前**没有任何界面消费**，所以连写入也一并去掉了。

    companion object {
        private const val TAG = "ReviewDatabase"
        private const val DB_NAME = "review.db"
        /** v4：banks 增加 selected / sort_order（题库仓库的「取出」与排序）。
         *  v5：斩题功能下线，DROP slashed 表（历史被斩的题全部放回练习）。 */
        private const val DB_VERSION = 5

        /** 错题连续答对多少次算「已掌握」。 */
        const val MASTER_STREAK = 3

        @Volatile
        private var instance: ReviewDatabase? = null

        /** 内置库改名迁移是否已执行过（每进程一次，见 [ReviewDatabase.migrateBundledBankNames]）。 */
        @Volatile
        private var bundledNamesMigrated = false

        fun get(context: Context): ReviewDatabase {
            val db = instance ?: synchronized(this) {
                instance ?: ReviewDatabase(context).also { instance = it }
            }
            // 内置库改名的存量迁移：首次取库时顺手跑一次。幂等、两次 SELECT 量级，
            // runCatching 兜住 —— 迁移失败绝不能影响取库本身。
            if (!bundledNamesMigrated) {
                synchronized(this) {
                    if (!bundledNamesMigrated) {
                        bundledNamesMigrated = true
                        runCatching { db.migrateBundledBankNames() }
                    }
                }
            }
            return db
        }
    }
}

/**
 * 抽题模式（**不是**界面上的"练习模式"）。
 *
 * 2026-10 按用户要求把 [RANDOM] 的显示名从「随机练习」改成「**乱序练习**」——
 * 用户界面上只出现"乱序练习"这个说法，内部枚举名保持 `RANDOM` 不动
 * （它同时是落库值，改名会让历史记录里的模式串对不上）。
 *
 * 注意与 `ReviewPrefs` 的「选项乱序」区分：本枚举管的是**题目顺序**，
 * 选项乱序管的是**同一道题里 A/B/C/D 的排列**，两者互不相干。
 */
enum class ReviewMode(val label: String) {
    SEQUENTIAL("顺序练习"),
    RANDOM("乱序练习"),
    WRONG_FIRST("错题优先");

    companion object {
        fun fromName(name: String?): ReviewMode =
            entries.firstOrNull { it.name == name } ?: SEQUENTIAL
    }
}
