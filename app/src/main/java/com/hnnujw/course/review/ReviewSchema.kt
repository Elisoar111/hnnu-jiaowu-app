package com.hnnujw.course.review

import android.database.sqlite.SQLiteDatabase

/**
 * 题库数据库的**表结构与迁移**（从 `ReviewDatabase` 拆出来的第一块）。
 *
 * 为什么单独一个文件：`ReviewDatabase` 之前同时干着"建表、迁移、CRUD、抽题排序、
 * 错题间隔复习"五件事，一千两百多行，改任何一处都要先滚过另外四件事。表结构是其中
 * 变更频率最低、但**改错代价最高**的一块（错误迁移会直接毁掉用户的答题历史），
 * 单独拎出来便于逐条 review。
 *
 * 🔴 迁移铁律：**幂等 + 绝不删表重建**。每个版本分支都必须能在"已经是新版"的库上
 * 再跑一遍而不报错，因为 `onUpgrade` 会先把 `createSchema` 整个重跑一次。
 */
    internal fun createSchema(db: SQLiteDatabase) {
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

    internal fun upgradeSchema(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // 全部建表语句都是 IF NOT EXISTS，幂等重跑即可；将来加列再按版本补 ALTER。
        createSchema(db)
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
        // v5 → v6：统计页下线后遗留的 daily_stats 空壳。
        // 它的读写代码在 v4 就删干净了，但老安装的库里表还在（还存着历史数据），
        // 属于"只占空间、永远不会再被读到"的黑洞。这里一并清掉。
        db.execSQL("DROP TABLE IF EXISTS daily_stats")
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
