package com.hnnujw.course.academic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 成绩行身份 [GradeIdentity] 的回归。
 *
 * 守的是「列表位移不得产生误报」这一条 —— 这是成绩订阅最后剩下的一类会
 * **炸一轮通知**的缺陷：教务列表插入一行，后面的每一行下标都会 +1。
 */
class GradeIdentityTest {

    private fun grade(
        term: String = "2025-2026-1",
        code: String = "C001",
        name: String = "高等数学",
        sectionId: String = "01",
        type: String = "必修",
        score: String = "90"
    ) = AcademicGrade(
        name = name,
        score = score,
        credits = "4.0",
        gradePoint = "4.0",
        type = type,
        term = term,
        code = code,
        sectionId = sectionId
    )

    @Test
    fun `稳定字段相同的两行在只有一行时身份相同`() {
        assertEquals(GradeIdentity.keyOf(grade()), GradeIdentity.keys(listOf(grade())).single())
    }

    /** 🔴 核心回归：在列表**中间插入**一行，其余各行的身份必须原样不变。 */
    @Test
    fun `列表中间插入一行不影响其它行的身份`() {
        val a = grade(code = "A", name = "课程A")
        val b = grade(code = "B", name = "课程B")
        val c = grade(code = "C", name = "课程C")

        val before = GradeIdentity.keys(listOf(a, b, c))
        // 在 A 与 B 之间插入一门新课（教务出了新成绩时就是这种形态）
        val inserted = grade(code = "X", name = "课程X")
        val after = GradeIdentity.keys(listOf(a, inserted, b, c))

        assertEquals("A 的身份必须不变", before[0], after[0])
        assertEquals("B 的身份必须不变", before[1], after[2])
        assertEquals("C 的身份必须不变", before[2], after[3])
        assertNotEquals("新插入的那一行必须是新身份", before[0], after[1])
    }

    /** 更狠的一种：整批前插一行。所有旧行下标都 +1，但身份应当全部不变。 */
    @Test
    fun `整批前插一行时全部旧行身份不变`() {
        val old = (1..5).map { grade(code = "C$it", name = "课程$it") }
        val before = GradeIdentity.keys(old)
        val after = GradeIdentity.keys(listOf(grade(code = "NEW", name = "新课")) + old)
        before.forEachIndexed { i, key ->
            assertEquals("第 $i 行身份被下标带偏了", key, after[i + 1])
        }
    }

    /**
     * 同一门课真的出现第二行（重修/补考）时，第二行必须是**新身份**，
     * 否则「补考成绩出来了」这条不会被播报。
     */
    @Test
    fun `同码同名的第二行是独立身份`() {
        val base = grade(code = "C001", name = "高等数学")
        val retake = grade(code = "C001", name = "高等数学")
        val keys = GradeIdentity.keys(listOf(base, retake))
        assertEquals(2, keys.toSet().size)
        assertEquals(GradeIdentity.keyOf(base), keys[0])
    }

    @Test
    fun `两行完全相同也能被区分`() {
        val same = grade()
        val keys = GradeIdentity.keys(listOf(same, same.copy()))
        assertEquals(2, keys.toSet().size)
    }

    @Test
    fun `身份与输入等长且顺序对应`() {
        val list = (1..8).map { grade(code = "C$it", name = "课程$it") }
        assertEquals(list.size, GradeIdentity.keys(list).size)
    }

    @Test
    fun `空列表返回空`() {
        assertTrue(GradeIdentity.keys(emptyList()).isEmpty())
    }

    /** 长度前缀拼接：字段边界必须无歧义（`ab`+`c` 与 `a`+`bc` 不能相等）。 */
    @Test
    fun `字段拼接不会因为边界移动而撞车`() {
        val left = grade(term = "ab", code = "c", name = "n", sectionId = "s", type = "t")
        val right = grade(term = "a", code = "bc", name = "n", sectionId = "s", type = "t")
        assertNotEquals(GradeIdentity.keyOf(left), GradeIdentity.keyOf(right))
    }

    @Test
    fun `学期不同的同一门课是不同身份`() {
        assertNotEquals(
            GradeIdentity.keyOf(grade(term = "2024-2025-2")),
            GradeIdentity.keyOf(grade(term = "2025-2026-1"))
        )
    }
}
