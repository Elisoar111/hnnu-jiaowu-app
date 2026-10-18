package com.hnnujw.course.ykt

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「选满才可发 IEC」的回归 —— 对应 2026-10-18 的线上报错。
 *
 * ## 服务端契约（测试账号对淮师网关实测）
 *
 * `getThirdData(type=IEC)` 只有一种合法形态：**level = 总级数 且各级参数齐全**。
 * 其余全部被第三方（tsm）拒绝，报
 * `获取第三方数据异常了-tsm：查询接口，参数type有误`：
 *
 * | 请求形态 | 结果 |
 * |---|---|
 * | `level=0`（无任何 picks） | ❌ 参数type有误 |
 * | `level=3`（4 级费项选了 3 级） | ❌ 参数type有误 |
 * | `level=4` 但缺 `room` 字段 | ❌ 参数type有误 |
 * | `level=4` 且 4 级全齐 | ✅ 返回 showData 读数 |
 *
 * 旧判据 `isQueryable`（只要求"非空"）挡不住"记忆被 [YktPickReconcile] 截短"
 * 的场景 —— 那正是用户看到报错的路径。这里钉住新判据 [YktSceneSelection.isFullySelected]。
 */
class YktSceneFullySelectedTest {

    /** 4 级费项（淮师电费口径：校区/楼栋/楼层/房间）。 */
    private val full = YktSceneSelection(
        feeItemId = "181",
        picks = listOf(
            ScenePick("campus", "1&本校区", "本校区"),
            ScenePick("building", "011&01号学生公寓A区", "01A"),
            ScenePick("floor", "1&1层", "1层"),
            ScenePick("room", "011101&01A-101", "01A-101"),
        ),
    )

    /** 对账截短后的形态：4 级记忆剩 3 级（丢了房间）。 */
    private val truncated = full.copy(picks = full.picks.take(3))

    // ── isFullySelected：新判据

    @Test
    fun `四级全满才可发IEC`() {
        assertTrue(full.isFullySelected(4))
    }

    @Test
    fun `截短到三级不可发`() {
        assertFalse("3 级 picks 对 4 级期望不可发 —— 这就是线上报错的来源", truncated.isFullySelected(4))
    }

    @Test
    fun `空选择不可发`() {
        assertFalse(YktSceneSelection.EMPTY.isFullySelected(4))
        assertFalse(YktSceneSelection(feeItemId = "181").isFullySelected(4))
    }

    @Test
    fun `费项缺失不可发`() {
        assertFalse(full.copy(feeItemId = "").isFullySelected(4))
    }

    @Test
    fun `级数超出期望也算满（多选无害）`() {
        // 理论上不会发生（picks 不会超过级定义），但判据应放行而不是拒绝。
        assertTrue(full.isFullySelected(3))
    }

    // ── 期望级数未知的退路

    @Test
    fun `期望级数未知时退回旧判据`() {
        // 详情还没拿到：无从判断满不满，此时"非空即发"（让服务端拒绝也好过卡死）。
        assertTrue(full.isFullySelected(null))
        assertTrue(truncated.isFullySelected(null))
        assertFalse(YktSceneSelection.EMPTY.isFullySelected(null))
    }

    @Test
    fun `期望级数非法值按未知处理`() {
        // ≤0 的期望与 null 同义。
        assertTrue(full.isFullySelected(0))
        assertTrue(full.isFullySelected(-1))
    }

    // ── 旧判据 isQueryable 的语义保持不变

    @Test
    fun `isQueryable 语义不变（非空即可发起级联）`() {
        // isQueryable 是"能不能发起 select 取下一级"的判据，仍按旧口径。
        assertFalse(YktSceneSelection.EMPTY.isQueryable)
        assertTrue(truncated.isQueryable)
        assertTrue(full.isQueryable)
    }

    // ── 巡检侧的期望级数存取（纯逻辑部分）

    /**
     * readExpectedLevels 对非法存储值的回落：
     * 这里只测数值解析规则（Robolectric 侧才有 prefs）。
     */
    @Test
    fun `期望级数解析规则`() {
        // 模拟 parse 逻辑：非正数一律视为"未知"。
        fun parseOrNull(raw: String?): Int? =
            raw?.toIntOrNull()?.takeIf { it > 0 }
        assertEquals(4, parseOrNull("4"))
        assertNull(parseOrNull("0"))
        assertNull(parseOrNull("-1"))
        assertNull(parseOrNull("abc"))
        assertNull(parseOrNull(null))
    }
}
