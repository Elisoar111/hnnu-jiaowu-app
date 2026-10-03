package com.hnnujw.course.review

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [resolvePracticeEntry] 的回归测试。
 *
 * 背景：首页「顺序练习 / 随机练习」原来直接 `banks.first().id`，
 * 按钮 `enabled` 又硬编码 `true`。题库为空而卡片一旦被构造，
 * 点一下就抛 `NoSuchElementException: List is empty`。
 * 判空抽成纯函数后，既能单测，Composable 也只按结果渲染。
 */
class PracticeEntryTest {

    @Test
    fun `空列表返回 NoBank 而不是抛异常`() {
        // 回归：等价于原来的 banks.first() —— 空列表抛 NoSuchElementException
        assertEquals(PracticeEntry.NoBank, resolvePracticeEntry(emptyList()))
    }

    @Test
    fun `单库时返回该库`() {
        val e = resolvePracticeEntry(listOf("b1"))
        assertTrue(e is PracticeEntry.Ready)
        assertEquals("b1", (e as PracticeEntry.Ready).bankId)
    }

    @Test
    fun `多库时取第一个`() {
        val e = resolvePracticeEntry(listOf("b3", "b1", "b2"))
        assertEquals("b3", (e as PracticeEntry.Ready).bankId)
    }

    @Test
    fun `跳过空白 id`() {
        // 脏数据兜底：id 空白时不能拿去当 bank_id 查库
        assertEquals(PracticeEntry.NoBank, resolvePracticeEntry(listOf("", "   ")))
        assertEquals("b2", (resolvePracticeEntry(listOf("", "b2")) as PracticeEntry.Ready).bankId)
    }

    @Test
    fun `全为空白 id 时视为无题库`() {
        assertEquals(PracticeEntry.NoBank, resolvePracticeEntry(listOf("", " ", "\t")))
    }

    @Test
    fun `NoBank 分支没有 bankId 可泄漏`() {
        // 编译期保证：NoBank 是 data object，取 .bankId 会直接编译不过。
        // 这里只断言类型，避免 Composable 侧拿到 NoBank 还去读 bankId。
        assertEquals("NoBank", PracticeEntry.NoBank.toString())
    }
}
