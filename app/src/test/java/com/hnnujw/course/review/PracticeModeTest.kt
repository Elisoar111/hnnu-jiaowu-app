package com.hnnujw.course.review

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「练习模式 → 开练参数」的纯函数测试。
 *
 * 这段映射是**点了「开始练习」之后真正生效的东西**：模式选对了但映射错了，
 * 界面看起来完全正常，用户只会发现"我明明选了考试，怎么不限时"。
 * 所以四条映射逐条钉住，另外把"时长只对考试生效"这条边界单独测。
 */
class PracticeModeTest {

    @Test
    fun `四档模式都在界面上有唯一文案`() {
        val labels = PracticeModeOption.entries.map { it.label }
        assertEquals(4, labels.size)
        assertEquals(labels.distinct(), labels)
        assertTrue(labels.all { it.isNotBlank() })
    }

    @Test
    fun `只有考试需要选时长`() {
        assertTrue(PracticeModeOption.EXAM.needsDuration)
        assertFalse(PracticeModeOption.SEQUENTIAL.needsDuration)
        assertFalse(PracticeModeOption.SHUFFLED.needsDuration)
        assertFalse(PracticeModeOption.MEMORIZE.needsDuration)
    }

    @Test
    fun `顺序练习按顺序抽题且不限时`() {
        val p = resolvePracticeParams(PracticeModeOption.SEQUENTIAL, 30)
        assertEquals(ReviewMode.SEQUENTIAL, p.mode)
        assertFalse(p.memorize)
        assertEquals(0, p.durationSeconds)
    }

    @Test
    fun `乱序练习走 RANDOM 且不限时`() {
        val p = resolvePracticeParams(PracticeModeOption.SHUFFLED, 30)
        assertEquals(ReviewMode.RANDOM, p.mode)
        assertFalse(p.memorize)
        assertEquals(0, p.durationSeconds)
    }

    @Test
    fun `考试按顺序抽题并把分钟换算成秒`() {
        val p = resolvePracticeParams(PracticeModeOption.EXAM, 45)
        assertEquals(ReviewMode.SEQUENTIAL, p.mode)
        assertFalse(p.memorize)
        assertEquals(45 * 60, p.durationSeconds)
    }

    @Test
    fun `背题模式标记 memorize 且不限时`() {
        val p = resolvePracticeParams(PracticeModeOption.MEMORIZE, 30)
        assertTrue(p.memorize)
        assertEquals(0, p.durationSeconds)
    }

    @Test
    fun `非考试模式不会因为界面残留的时长值变成限时考试`() {
        // 用户在「考试」里把时长调到 120，再切回「顺序练习」—— 界面上时长行会消失，
        // 但 durationMinutes 这个变量还在。映射必须把它吞掉，否则顺序练习会突然开始倒计时。
        for (mode in PracticeModeOption.entries.filter { !it.needsDuration }) {
            assertEquals("$mode 不该带上时长", 0, resolvePracticeParams(mode, 120).durationSeconds)
        }
    }

    @Test
    fun `考试时长非法时不产生负数`() {
        assertEquals(0, resolvePracticeParams(PracticeModeOption.EXAM, 0).durationSeconds)
        assertEquals(0, resolvePracticeParams(PracticeModeOption.EXAM, -5).durationSeconds)
    }

    @Test
    fun `每个模式都给出一句非空说明`() {
        PracticeModeOption.entries.forEach { mode ->
            assertTrue("$mode 的说明为空", mode.hint(30).isNotBlank())
        }
        // 考试的说明里要带上当前时长，否则用户不知道自己在考多久。
        assertTrue(PracticeModeOption.EXAM.hint(45).contains("45 分钟"))
    }
}
