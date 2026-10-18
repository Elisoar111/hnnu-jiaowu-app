package com.hnnujw.course.ykt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 一卡通级联记忆对账的回归。
 *
 * 守的是「拿别人的房间号查电费」这类错位 —— 学校改了级定义但级数没变时，
 * 旧的 (code,value) 会被原样传给接口。原实现只比级数，正好挡不住这一种。
 */
class YktPickReconcileTest {

    private fun pick(code: String, value: String = "v_$code", name: String = code, level: Int = 0) =
        ScenePick(code = code, value = value, name = name, level = level)

    // —— 正常对齐

    @Test
    fun `级定义没变时原样保留`() {
        val remembered = listOf(pick("build"), pick("unit"), pick("room"))
        val levels = listOf("build", "unit", "room")
        assertEquals(remembered, YktPickReconcile.reconcile(remembered, levels))
    }

    @Test
    fun `只选了前几级也能原样保留`() {
        val remembered = listOf(pick("build"), pick("unit"))
        val levels = listOf("build", "unit", "room")
        assertEquals(remembered, YktPickReconcile.reconcile(remembered, levels))
    }

    // —— 级数变化

    @Test
    fun `当前不需要选任何级时清空`() {
        val remembered = listOf(pick("build"))
        assertTrue(YktPickReconcile.reconcile(remembered, emptyList()).isEmpty())
    }

    @Test
    fun `级数变少时截断到新的级数`() {
        val remembered = listOf(pick("build"), pick("unit"), pick("room"))
        val levels = listOf("build", "unit")
        assertEquals(remembered.take(2), YktPickReconcile.reconcile(remembered, levels))
    }

    @Test
    fun `级数变多时保留原有各级`() {
        val remembered = listOf(pick("build"), pick("unit"))
        val levels = listOf("build", "unit", "room")
        assertEquals(remembered, YktPickReconcile.reconcile(remembered, levels))
    }

    // —— 🔴 语义错位（原实现漏掉的那一类）

    /** 级数相同、但第一级的含义变了：必须整条作废，不能套用。 */
    @Test
    fun `级数相同但 code 不同时从第一位就截断`() {
        val remembered = listOf(pick("build"), pick("unit"), pick("room"))
        val levels = listOf("park", "build", "room")
        assertTrue(YktPickReconcile.reconcile(remembered, levels).isEmpty())
    }

    /** 前几级还对得上、后面变了：保留对得上的前缀。 */
    @Test
    fun `中途 code 变化时保留前缀`() {
        val remembered = listOf(pick("build"), pick("unit"), pick("room"))
        val levels = listOf("build", "floor", "room")
        assertEquals(remembered.take(1), YktPickReconcile.reconcile(remembered, levels))
    }

    @Test
    fun `hasSemanticDrift 能识别级数相同的错位`() {
        val remembered = listOf(pick("build"), pick("unit"), pick("room"))
        assertTrue(YktPickReconcile.hasSemanticDrift(remembered, listOf("park", "build", "room")))
        assertTrue(YktPickReconcile.hasSemanticDrift(remembered, listOf("build", "floor", "room")))
    }

    @Test
    fun `hasSemanticDrift 对级数变化不判定为漂移`() {
        // 级数变化是另一条规则（截断），不算"语义漂移"
        val remembered = listOf(pick("build"), pick("unit"), pick("room"))
        assertFalse(YktPickReconcile.hasSemanticDrift(remembered, listOf("build", "unit")))
        assertFalse(YktPickReconcile.hasSemanticDrift(remembered, listOf("build", "unit", "room", "bed")))
    }

    @Test
    fun `级定义一致时不算漂移`() {
        val remembered = listOf(pick("build"), pick("unit"))
        assertFalse(YktPickReconcile.hasSemanticDrift(remembered, listOf("build", "unit")))
    }

    @Test
    fun `空输入不崩也不算漂移`() {
        assertTrue(YktPickReconcile.reconcile(emptyList(), listOf("build")).isEmpty())
        assertFalse(YktPickReconcile.hasSemanticDrift(emptyList(), listOf("build")))
        assertFalse(YktPickReconcile.hasSemanticDrift(listOf(pick("build")), emptyList()))
    }

    /** 对账结果永远不超过当前级数，也永远是原列表的前缀。 */
    @Test
    fun `结果必须是原列表的前缀且不超长`() {
        val remembered = listOf(pick("a"), pick("b"), pick("c"))
        val cases = listOf(
            listOf("a", "b", "c"),
            listOf("a", "b"),
            listOf("a"),
            listOf("a", "b", "c", "d"),
            listOf("x", "y", "z"),
            emptyList()
        )
        for (levels in cases) {
            val result = YktPickReconcile.reconcile(remembered, levels)
            assertTrue("结果超长: $levels", result.size <= minOf(remembered.size, levels.size))
            assertEquals("结果必须是前缀", remembered.take(result.size), result)
        }
    }
}
