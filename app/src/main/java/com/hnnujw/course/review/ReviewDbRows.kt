package com.hnnujw.course.review

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray

/**
 * 题目行的**读取与批量回读**（从 `ReviewDatabase` 拆出来的第二块）。
 *
 * 这里只做"Cursor 行 → [ReviewQuestion]"和"按给定顺序把题回读出来"两件事，
 * 不含任何业务规则。单独拎出来的好处是 `ReviewDatabase` 里剩下的都是"做什么"，
 * 而"怎么把一行读成对象"只有这一处定义 —— 列顺序改了就只需要改这一个文件。
 *
 * ⚠️ 列下标（0..9）与各查询里的 `SELECT` 顺序**强耦合**：
 * `bank_id, qid, type, stem, options, answer, chapter, explanation, source, tags`。
 * 新增列必须同步改 [QUESTION_COLUMNS] 与 [readQuestionRow]。
 */
private const val QUESTION_COLUMNS =
    "bank_id, qid, type, stem, options, answer, chapter, explanation, source, tags"

/**
 * 把一行题目读成对象。
 *
 * `options` / `tags` 存的是 JSON 数组文本。解析失败时**退化成空列表而不是抛**：
 * 一条脏数据不该让整页题目打不开（历史上题库 JSON 是人手改过的）。
 */
internal fun readQuestionRow(c: Cursor): ReviewQuestion {
    fun stringArray(column: Int): List<String> = try {
        val arr = JSONArray(c.getString(column))
        (0 until arr.length()).map { arr.optString(it) }
    } catch (e: Exception) {
        emptyList()
    }
    return ReviewQuestion(
        id = c.getString(1),
        type = c.getString(2),
        stem = c.getString(3),
        options = stringArray(4),
        answer = c.getString(5),
        chapter = c.getString(6),
        explanation = c.getString(7),
        source = c.getString(8),
        tags = stringArray(9),
        bankId = c.getString(0)
    )
}

/**
 * 排序用的空壳题：**只带 `bankId` 与 `qid`**。
 *
 * 排序比较器只读 `compositeKey`，其余字段为空不影响结果；这样就不必为了排序
 * 把整库题干读进内存。
 */
internal fun sortingStubQuestion(bankId: String, qid: String): ReviewQuestion = ReviewQuestion(
    id = qid,
    type = "",
    stem = "",
    options = emptyList(),
    answer = "",
    chapter = "",
    bankId = bankId
)

/** 按给定顺序回读完整题目；缺失的（例如排序后被删）直接跳过。 */
internal fun loadQuestionsInOrder(
    db: SQLiteDatabase,
    keys: List<Pair<String, String>>
): List<ReviewQuestion> {
    if (keys.isEmpty()) return emptyList()
    val byKey = HashMap<Pair<String, String>, ReviewQuestion>(keys.size)
    keys.groupBy({ it.first }, { it.second }).forEach { (bankId, qids) ->
        val placeholders = qids.joinToString(", ") { "?" }
        db.rawQuery(
            "SELECT $QUESTION_COLUMNS FROM questions WHERE bank_id = ? AND qid IN ($placeholders)",
            arrayOf(bankId) + qids.toTypedArray()
        ).use { c ->
            while (c.moveToNext()) {
                val q = readQuestionRow(c)
                byKey[q.bankId to q.id] = q
            }
        }
    }
    return keys.mapNotNull { byKey[it] }
}
