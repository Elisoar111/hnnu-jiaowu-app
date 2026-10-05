package com.hnnujw.course.review

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 选项乱序的单元测试。
 *
 * 这里守的核心不变量只有一条：**乱序只改"看起来的顺序"，不改"判分的结果"**。
 * 具体拆成三件事：
 *  1. 关闭时恒等（行为与从前逐字一致）；
 *  2. 开启时是排列（选项文本一个不多一个不少），且同一题在同一轮里顺序稳定；
 *  3. 用户按**显示字母**作答、界面翻回**原始字母**后，判分结果与不打乱时相同。
 */
class OptionShuffleTest {

    private fun q(
        id: String = "1",
        type: String = ReviewQuestion.TYPE_SINGLE,
        answer: String = "A",
        options: List<String> = listOf("甲", "乙", "丙", "丁"),
        bankId: String = "b1"
    ) = ReviewQuestion(
        id = id,
        type = type,
        stem = "题干$id",
        options = options,
        answer = answer,
        chapter = "章",
        bankId = bankId
    )

    // —— 关闭时恒等

    @Test
    fun `关闭乱序时显示序等于原始序`() {
        val question = q()
        val options = displayOptions(question, shuffle = false, seed = 12345L)
        assertEquals(question.options, options.map { it.text })
        options.forEachIndexed { i, opt ->
            assertEquals(('A' + i).toString(), opt.letter)
            assertEquals(opt.letter, opt.originalLetter)
            assertEquals(i, opt.originalIndex)
        }
    }

    @Test
    fun `关闭乱序时换种子也不动`() {
        val question = q()
        assertEquals(
            displayOptions(question, shuffle = false, seed = 1L).map { it.originalIndex },
            displayOptions(question, shuffle = false, seed = 999L).map { it.originalIndex }
        )
    }

    // —— 开启时是排列

    @Test
    fun `开启乱序后选项文本一个不少一个不多`() {
        val question = q()
        val options = displayOptions(question, shuffle = true, seed = 42L)
        assertEquals(question.options.toSet(), options.map { it.text }.toSet())
        assertEquals(question.options.size, options.size)
        // 显示字母仍是从 A 起连续编号，且每个原始下标恰好出现一次。
        assertEquals(listOf("A", "B", "C", "D"), options.map { it.letter })
        assertEquals(setOf(0, 1, 2, 3), options.map { it.originalIndex }.toSet())
    }

    @Test
    fun `同一题在同一轮里顺序稳定`() {
        // 这条不是锦上添花：界面每次重组都会重算 displayOptions，
        // 若每次结果不同，选项会在用户眼皮底下乱跳。
        val question = q()
        val first = displayOptions(question, shuffle = true, seed = 7L).map { it.text }
        repeat(5) {
            assertEquals(first, displayOptions(question, shuffle = true, seed = 7L).map { it.text })
        }
    }

    @Test
    fun `不同会话种子会给出不同排列`() {
        // 不能断言"任意两个种子一定不同"（4 个选项有 24 种排列，必然有碰撞），
        // 只断言"在若干种子里至少出现两种不同排列" —— 否则等于没乱。
        val question = q()
        val permutations = (1L..20L).map { seed ->
            displayOptions(question, shuffle = true, seed = seed).map { it.originalIndex }
        }
        assertTrue("20 个种子只给出 1 种排列，说明随机源没起作用", permutations.toSet().size > 1)
    }

    @Test
    fun `单个选项不打乱`() {
        val question = q(options = listOf("只有一个"))
        val options = displayOptions(question, shuffle = true, seed = 3L)
        assertEquals(listOf(0), options.map { it.originalIndex })
    }

    // —— 判分不受影响（核心不变量）

    @Test
    fun `乱序下按显示字母作答仍能判对_多选`() {
        val question = q(type = ReviewQuestion.TYPE_MULTI, answer = "AC")
        val options = displayOptions(question, shuffle = true, seed = 42L)
        // 用户看到的答案字母是显示字母；界面点选时会翻回原始字母再交给 PracticeSession。
        val displayAnswer = toDisplayAnswer(question.answer, options)
        assertEquals(2, displayAnswer.length)
        val pickedOriginal = displayAnswer.map { ch ->
            options.first { it.letter == ch.toString() }.originalLetter
        }.joinToString("")
        assertEquals(question.answer, pickedOriginal)
        assertTrue(judgeAnswer(question, pickedOriginal))
    }

    @Test
    fun `乱序下按显示字母作答仍能判对_单选`() {
        val question = q(answer = "C")
        val options = displayOptions(question, shuffle = true, seed = 5L)
        val displayAnswer = toDisplayAnswer(question.answer, options)
        assertEquals(1, displayAnswer.length)
        val pickedOriginal = options.first { it.letter == displayAnswer }.originalLetter
        assertEquals("C", pickedOriginal)
        assertTrue(judgeAnswer(question, pickedOriginal))
    }

    @Test
    fun `乱序下选错仍然判错`() {
        val question = q(answer = "A")
        val options = displayOptions(question, shuffle = true, seed = 11L)
        val correctDisplay = toDisplayAnswer(question.answer, options)
        // 挑一个"显示字母 ≠ 正确答案"的选项，翻回原始字母后必须判错。
        val wrong = options.first { it.letter != correctDisplay }
        assertFalse(judgeAnswer(question, wrong.originalLetter))
    }

    @Test
    fun `显示答案只包含显示字母且按序排列`() {
        val question = q(type = ReviewQuestion.TYPE_MULTI, answer = "AD")
        val options = displayOptions(question, shuffle = true, seed = 88L)
        val display = toDisplayAnswer(question.answer, options)
        assertEquals(2, display.length)
        assertEquals(display.toList().sorted(), display.toList())
        display.forEach { ch ->
            assertTrue("显示答案出现了不存在的字母 $ch", options.any { it.letter == ch.toString() })
        }
    }

    @Test
    fun `显示答案忽略超出选项范围的字母`() {
        // 题库里混进 "ABCD" 而只有 2 个选项时，不能崩、也不能造出不存在的字母。
        val question = q(options = listOf("甲", "乙"), answer = "A")
        val options = displayOptions(question, shuffle = true, seed = 2L)
        val display = toDisplayAnswer("AC", options)
        assertEquals(1, display.length)
    }

    // —— 种子

    @Test
    fun `同题同种子得到同一个种子值`() {
        val question = q()
        assertEquals(seedFor(question, 100L), seedFor(question, 100L))
    }

    @Test
    fun `不同题的种子值不同`() {
        // 否则所有题目共用同一个随机序列，排列规律一致，看起来像"没乱"。
        assertNotEquals(seedFor(q(id = "1"), 100L), seedFor(q(id = "2"), 100L))
    }

    @Test
    fun `换会话种子会换掉题目种子值`() {
        val question = q()
        assertNotEquals(seedFor(question, 1L), seedFor(question, 2L))
    }
}
