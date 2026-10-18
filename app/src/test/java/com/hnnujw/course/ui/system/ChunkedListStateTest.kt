package com.hnnujw.course.ui.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ChunkedListState] 的回归。
 *
 * 这类"渲染截断"的状态机有两个必须守住的性质，任何一个错了用户都会以为数据丢了：
 *  1. `visibleCount` **永远不超过**总条数（否则 `take(n)` 会拿到全部，截断失效）；
 *  2. `hasMore` 与实际未渲染数量一致（说"还有 N 项"就必须真的有 N 项）。
 *
 * 它不依赖 Compose 运行时 —— `mutableIntStateOf` 是 Compose 的普通状态对象，
 * 在 JVM 单测里可以直接读写（不需要 composable 环境）。
 */
class ChunkedListStateTest {

    private fun state(total: Int, pageSize: Int = 40) = ChunkedListState(total, pageSize)

    @Test
    fun `初始只放一页`() {
        val s = state(total = 100, pageSize = 40)
        assertEquals(40, s.visibleCount)
        assertTrue(s.hasMore)
        assertEquals(60, s.remaining)
    }

    @Test
    fun `总数少于一页时全部显示`() {
        val s = state(total = 7, pageSize = 40)
        assertEquals(7, s.visibleCount)
        assertFalse(s.hasMore)
        assertEquals(0, s.remaining)
    }

    @Test
    fun `显示更多按页递增`() {
        val s = state(total = 100, pageSize = 40)
        s.showMore()
        assertEquals(80, s.visibleCount)
        assertEquals(20, s.remaining)
        s.showMore()
        assertEquals("不能超过总数", 100, s.visibleCount)
        assertFalse(s.hasMore)
    }

    @Test
    fun `全部显示一次到底`() {
        val s = state(total = 1000, pageSize = 40)
        s.showAll()
        assertEquals(1000, s.visibleCount)
        assertFalse(s.hasMore)
    }

    /** 「加载更多」拿回新一页时：总条数变大，但不该把已展开的收回去。 */
    @Test
    fun `总条数增大不会收回已展开的部分`() {
        val s = state(total = 100, pageSize = 40)
        s.showMore()
        assertEquals(80, s.visibleCount)
        s.syncTotal(200)
        assertEquals("已展开的 80 条必须保留", 80, s.visibleCount)
        // 只是"还有更多"变多了
        assertEquals(120, s.remaining)
    }

    /** 切筛选条件时数据变少：可见数量要跟着压下来，不能凭空多出来。 */
    @Test
    fun `总条数变少时可见数量跟着压缩`() {
        val s = state(total = 100, pageSize = 40)
        s.showMore()
        assertEquals(80, s.visibleCount)
        s.syncTotal(10)
        assertEquals(10, s.visibleCount)
        assertFalse(s.hasMore)
    }

    /** 首次从"空"变成"有数据"（查询结果回来）时至少要放一页出来。 */
    @Test
    fun `从零变成有数据时自动放出一页`() {
        val s = state(total = 0, pageSize = 40)
        assertEquals(0, s.visibleCount)
        assertFalse(s.hasMore)
        s.syncTotal(100)
        assertEquals(40, s.visibleCount)
        assertTrue(s.hasMore)
    }

    @Test
    fun `总数为零时不显示更多`() {
        val s = state(total = 0)
        assertEquals(0, s.visibleCount)
        assertFalse(s.hasMore)
        assertEquals(0, s.remaining)
    }

    /** 🔴 核心不变量：可见数量永远不超过总数。 */
    @Test
    fun `可见数量永不超过总数`() {
        for (total in listOf(0, 1, 5, 39, 40, 41, 100, 1000)) {
            val s = state(total = total, pageSize = 40)
            repeat(30) { s.showMore() }
            assertTrue("total=$total 时 visibleCount=${s.visibleCount} 超了", s.visibleCount <= total)
            assertFalse("展开到底后不该再 hasMore（total=$total）", s.hasMore)
        }
    }

    /** hasMore 与 remaining 必须一致：说"还有 N 项"就必须真的有 N 项。 */
    @Test
    fun `hasMore 与 remaining 始终一致`() {
        val s = state(total = 137, pageSize = 40)
        while (true) {
            assertEquals(s.visibleCount < s.total, s.hasMore)
            assertEquals(s.total - s.visibleCount, s.remaining)
            if (!s.hasMore) break
            s.showMore()
        }
    }

    @Test
    fun `同步相同的总数是无操作`() {
        val s = state(total = 100, pageSize = 40)
        s.showMore()
        val before = s.visibleCount
        s.syncTotal(100)
        assertEquals(before, s.visibleCount)
    }
}
