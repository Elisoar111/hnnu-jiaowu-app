package com.hnnujw.course.review

/**
 * 乱序 / 错题优先抽题的排序纯函数（题目顺序，不是 [OptionShuffle] 的选项顺序）。
 *
 * 历史版本把排序写在 SQL 里：`(qid || bank_id) * 1103515245 % 2147483647`。
 * SQLite 对 TEXT 参与乘法会 CAST 成数字 —— qid/bank_id 非数字开头时整个表达式
 * 变成 0，所有题并列，「乱序练习」悄悄退化成扫描顺序。qid 是原样入库的，
 * 数字与否完全取决于导入数据，用户编辑一次题目就可能踩中。
 *
 * 所以排序挪到 Kotlin 层，要求两条硬性质：
 *  1. **跨进程跨设备稳定** —— 进程被杀后重建会话（ReviewActivity 用同一组参数
 *     重抽同一批题）依赖"重抽即同序"。`String.hashCode` 的算法是 JLS 规格固定的，
 *     满足；
 *  2. **全序确定** —— 哈希可能碰撞，追加 `bankId::qid` 字典序决胜键，保证同一批
 *     题无论抽多少次都是同一个顺序，不存在"看运气的并列"。
 */
fun randomOrderKey(bankId: String, qid: String): Int = "$bankId::$qid".hashCode()

/** 乱序排序比较器：稳定哈希为主，字典序决胜。 */
fun randomOrderComparator(): Comparator<ReviewQuestion> =
    compareBy<ReviewQuestion> { randomOrderKey(it.bankId, it.id) }
        .thenComparator { a, b -> (a.bankId + "::" + a.id).compareTo(b.bankId + "::" + b.id) }

/**
 * 错题优先排序：未掌握的错题排前面，组内按乱序排。
 *
 * [unmasteredWrong] 是错题本里未掌握题目的复合键集合（`bankId::qid`），
 * 由调用方一次小查询取好传入 —— 这里保持纯函数，能直接单测。
 */
fun wrongFirstOrderComparator(unmasteredWrong: Set<String>): Comparator<ReviewQuestion> {
    val random = randomOrderComparator()
    return compareBy<ReviewQuestion> { if ("${it.bankId}::${it.id}" in unmasteredWrong) 0 else 1 }
        .thenComparator { a, b -> random.compare(a, b) }
}
