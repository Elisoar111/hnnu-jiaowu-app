package com.hnnujw.course.academic

/**
 * 成绩行的稳定身份（用于「有没有新成绩」的比对）。
 *
 * ## 为什么不能用列表下标
 *
 * 原实现是 `gradeIdentity(grade, index)`，把**该行在本次列表里的下标**拼进身份。
 * 教务成绩列表并非追加式：重修、补考、新开课都会**插到中间**。一旦插入一行，
 * 它之后的每一行下标都会 +1，于是它们的身份全部变化 —— 巡检会把整批旧成绩
 * 当成「新成绩」播报一遍。
 *
 * 假设某学期有 30 门课，第 3 门出了补考成绩插进来：后 27 门全部误报。
 * 用户收到的通知是「你有 27 门新成绩」，实际只有 1 门。这比不提醒更糟。
 *
 * ## 现在的口径
 *
 * 身份 = 「课程自身的稳定字段」+「在**同一组稳定字段**内的第几次出现」。
 *
 *  - 稳定字段：学期、课程码、课程名、教学班、课程性质（教务给的原样字段）。
 *  - 出现序号：只在**同组内**计数，不跨组。默认 0。
 *
 * 于是：
 *  - 列表整体/局部位移 → 每行的组内序号不变 → 身份不变 → 不误报；
 *  - 真修了一门（同码同名的第二行）→ 那一行组内序号变 1 → 只有它是新的；
 *  - 两行完全相同的成绩（教务偶尔会重复返回）→ 靠组内序号区分，不会互相顶掉。
 *
 * 长度前缀拼接只为避免字段拼接歧义（`ab` + `c` 与 `a` + `bc` 不能相等）。
 */
object GradeIdentity {

    /**
     * 给一批成绩算出与之一一对应的身份串。
     *
     * @return 与 [grades] 等长的身份列表，`result[i]` 是 `grades[i]` 的身份。
     */
    fun keys(grades: List<AcademicGrade>): List<String> {
        val seen = HashMap<String, Int>(grades.size)
        return grades.map { grade ->
            val base = baseOf(grade)
            val occurrence = seen.getOrDefault(base, 0)
            seen[base] = occurrence + 1
            // 首行不带序号（保持与旧基线的「单行」形态一致，减少升级瞬间的误报），
            // 从第二行起才追加 #1、#2…… 这两者不会与首行冲突。
            if (occurrence == 0) base else base + "#" + occurrence
        }
    }

    /** 单行身份（等价于 [keys] 在「该组只有一行」时的结果）。 */
    fun keyOf(grade: AcademicGrade): String = baseOf(grade)

    /** 课程自身的稳定字段。**不含任何位置信息**。 */
    private fun baseOf(grade: AcademicGrade): String =
        listOf(grade.term, grade.code, grade.name, grade.sectionId, grade.type)
            .joinToString("") { it.length.toString() + ":" + it }
}
