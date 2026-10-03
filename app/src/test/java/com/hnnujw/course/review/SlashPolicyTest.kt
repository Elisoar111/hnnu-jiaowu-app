package com.hnnujw.course.review

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * 斩题语义的单元测试。
 *
 * 斩题最怕的是"顺手改掌握状态" —— 一旦混了，用户一斩题就被告知"已掌握"，统计立刻失真。
 * 这里把 [checkSlash] 的三态与 [wrongStatusAfterSlash] 的**恒等性**钉死：后者存在的意义
 * 就是让那条不变量有一个可测的落点。
 */
class SlashPolicyTest {

    // ── checkSlash 三态 ─────────────────────────────────────────────

    @Test
    fun `未斩且题存在可以斩`() {
        assertEquals(SlashCheck.Ok, checkSlash(known = true, slashed = false))
    }

    @Test
    fun `已斩过再斩是幂等命中`() {
        // 重复斩不该报错，界面按"已斩"渲染即可 —— 所以给 AlreadySlashed 而不是错误。
        assertEquals(SlashCheck.AlreadySlashed, checkSlash(known = true, slashed = true))
    }

    @Test
    fun `题不存在给未知题`() {
        assertEquals(SlashCheck.UnknownQuestion, checkSlash(known = false, slashed = false))
        assertEquals(SlashCheck.UnknownQuestion, checkSlash(known = false, slashed = true))
    }

    @Test
    fun `未知题优先于已斩判定`() {
        // 题都不在库里了，"是不是已斩"没有意义，必须先报 UnknownQuestion。
        assertEquals(SlashCheck.UnknownQuestion, checkSlash(known = false, slashed = true))
    }

    // ── 斩题不改掌握状态（核心不变量）─────────────────────────────────

    @Test
    fun `斩题不改变掌握状态`() {
        WrongStatus.entries.forEach { status ->
            assertSame(
                "斩题不得改变掌握状态（$status）",
                status,
                wrongStatusAfterSlash(status)
            )
        }
    }

    @Test
    fun `已掌握经斩题后仍是已掌握`() {
        assertEquals(WrongStatus.Mastered, wrongStatusAfterSlash(WrongStatus.Mastered))
    }

    @Test
    fun `未掌握经斩题后仍是未掌握`() {
        assertEquals(WrongStatus.NotMastered, wrongStatusAfterSlash(WrongStatus.NotMastered))
        assertEquals(WrongStatus.Reviewing, wrongStatusAfterSlash(WrongStatus.Reviewing))
    }

    @Test
    fun `恒等函数不因错题次数或连对而变`() {
        // 用 wrongStatus 现算后再过斩题，结果必须与现算一致（端到端口径）。
        assertEquals(
            WrongStatus.NotMastered,
            wrongStatusAfterSlash(wrongStatus(mastered = false, wrongCount = 3))
        )
        assertEquals(
            WrongStatus.Mastered,
            wrongStatusAfterSlash(wrongStatus(mastered = true, wrongCount = 3))
        )
    }
}
