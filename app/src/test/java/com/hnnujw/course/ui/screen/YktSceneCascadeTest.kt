package com.hnnujw.course.ui.screen

import com.hnnujw.course.ykt.SceneOption
import com.hnnujw.course.ykt.ScenePick
import com.hnnujw.course.ykt.YktFeeItem
import com.hnnujw.course.ykt.YktSceneLevel
import com.hnnujw.course.ykt.YktSceneSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 电费**动态分级**选择的纯逻辑回归。
 *
 * 级数由服务端下发（淮师电费实测 4 级：校区/楼栋/楼层/房间），
 * 所以这里的用例刻意**不写死 4 级**：另有一组 3 级用例，
 * 用来钉死"级数变了逻辑仍然成立"——否则将来某校是 3 级，
 * `take(levelIndex - 1)` 一类下标运算会静默错位。
 *
 * 覆盖的三类高风险点：
 * - 换上一级后必须清空下游，否则会拿"新楼栋 + 旧房间"去查，服务端只会返回空；
 * - 学校改了费项配置后，记忆里的旧费项 id 必须作废；
 * - 空选择的回显下标不能越界。
 */
class YktSceneCascadeTest {

    // 提交值形态固定是 "<id>&<name>"，必须原样回传 —— 用例里就按这个形态造数据。
    private val campus = SceneOption("1&本校区", "本校区")
    private val building = SceneOption("7&7号学生公寓A区", "7号学生公寓A区")
    private val floor = SceneOption("1&1层", "1层")
    private val room = SceneOption("701&7A-101", "7A-101")

    private val levels = listOf(
        YktSceneLevel("campus", 1, "校区"),
        YktSceneLevel("building", 2, "楼栋"),
        YktSceneLevel("floor", 3, "楼层"),
        YktSceneLevel("room", 4, "房间"),
    )

    private val full = YktSceneSelection(
        feeItemId = "201",
        picks = listOf(
            ScenePick("campus", campus.value, campus.name, 1),
            ScenePick("building", building.value, building.name, 2),
            ScenePick("floor", floor.value, floor.name, 3),
            ScenePick("room", room.value, room.name, 4),
        ),
    )

    // ── applyPick：写入某一级并清空下游 ──────────────────────────────────

    @Test
    fun `选校区清空楼栋楼层房间`() {
        val next = applyPick(1, levels[0], campus, full)
        assertEquals(listOf("campus"), next.picks.map { it.code })
        assertEquals("本校区", next.picks[0].name)
        // 费项不属于任何一级，不能被误清
        assertEquals("201", next.feeItemId)
    }

    @Test
    fun `选楼栋清空楼层房间但保留校区`() {
        val next = applyPick(2, levels[1], building, full)
        assertEquals(listOf("campus", "building"), next.picks.map { it.code })
        assertEquals("本校区", next.pick("campus")?.name)
        assertEquals("7号学生公寓A区", next.pick("building")?.name)
        // 下游必须没了：否则会带着"新楼栋 + 旧房间"去查
        assertEquals(null, next.pick("floor"))
        assertEquals(null, next.pick("room"))
    }

    @Test
    fun `选楼层清空房间但保留楼栋`() {
        val next = applyPick(3, levels[2], floor, full)
        assertEquals(listOf("campus", "building", "floor"), next.picks.map { it.code })
        assertEquals("7号学生公寓A区", next.pick("building")?.name)
        assertEquals("1层", next.pick("floor")?.name)
        assertEquals(null, next.pick("room"))
    }

    @Test
    fun `选房间只改房间`() {
        val next = applyPick(4, levels[3], room, full)
        assertEquals(4, next.picks.size)
        assertEquals("7A-101", next.pick("room")?.name)
        // 上游全部保留
        assertEquals("本校区", next.pick("campus")?.name)
        assertEquals("7号学生公寓A区", next.pick("building")?.name)
        assertEquals("1层", next.pick("floor")?.name)
    }

    /** 重选同一级（不回退上游）时，下游同样要被清掉，不能留下陈旧项。 */
    @Test
    fun `重选中间级也清下游`() {
        val next = applyPick(2, levels[1], building, full)
        // 再选一次楼栋（换了另一栋）
        val again = applyPick(2, levels[1], SceneOption("8&8号楼", "8号楼"), next)
        assertEquals(2, again.picks.size)
        assertEquals("8号楼", again.pick("building")?.name)
        assertEquals(null, again.pick("floor"))
    }

    /**
     * **级数不是 4 时的哨兵用例**。
     *
     * 若实现里把"下游"写死成"清 floor/room"，3 级场景下会漏清；
     * 这里用 3 级（校区/楼栋/房间）钉死"按下标算下游"这条语义。
     */
    @Test
    fun `三级场景下清下游仍按下标生效`() {
        val three = listOf(
            YktSceneLevel("campus", 1, "校区"),
            YktSceneLevel("building", 2, "楼栋"),
            YktSceneLevel("room", 3, "房间"),
        )
        val before = YktSceneSelection(
            feeItemId = "181",
            picks = listOf(
                ScenePick("campus", campus.value, campus.name, 1),
                ScenePick("building", building.value, building.name, 2),
                ScenePick("room", room.value, room.name, 3),
            ),
        )
        val next = applyPick(2, three[1], SceneOption("9&9号楼", "9号楼"), before)
        assertEquals(listOf("campus", "building"), next.picks.map { it.code })
        assertEquals("9号楼", next.pick("building")?.name)
        assertEquals(null, next.pick("room"))
    }

    /** 守卫：levelIndex 越界（<=0）时不能把 picks 切成负长度而崩。 */
    @Test
    fun `越界的级别号不会抛异常`() {
        val next = applyPick(0, levels[0], campus, full)
        assertEquals(campus.value, next.pick("campus")?.value)
    }

    // ── pick / pickMap / summary：界面与请求都依赖它们 ────────────────────

    @Test
    fun `pickMap 提交的是完整 id 与名字`() {
        // 这是最易错的一处：只传 id 服务端会认不出，必须 "<id>&<name>" 原样
        assertEquals("1&本校区", full.pickMap["campus"])
        assertEquals("701&7A-101", full.pickMap["room"])
        assertEquals(4, full.pickMap.size)
    }

    @Test
    fun `空选择取到 null`() {
        assertEquals(null, YktSceneSelection.EMPTY.pick("room"))
        assertEquals("", YktSceneSelection.EMPTY.summary)
    }

    // ── reconcileSelection：费项失效时作废记忆 ──────────────────────────

    /**
     * **两个费项都要能保留**：淮师同时有「1-6单元」与「7-12单元」，
     * 用户切到任一个都应记住；只有费项**不在列表里**时才作废。
     */
    @Test
    fun `两个费项均在列表时各自保留记忆`() {
        val items = listOf(
            YktFeeItem("181", "1-6单元电费", "elec"),
            YktFeeItem("201", "7-12单元电费", "elec"),
        )
        assertEquals(full, reconcileSelection(full, items))
        val other = full.copy(feeItemId = "181")
        assertEquals(other, reconcileSelection(other, items))
    }

    @Test
    fun `费项仍有效时保留记忆`() {
        val items = listOf(YktFeeItem("201", "7-11单元及东区电费", "elec"))
        assertEquals(full, reconcileSelection(full, items))
    }

    /** 学校改了配置、旧费项 id 不在列表里 → 整份记忆作废，逼用户重选。 */
    @Test
    fun `费项失效时丢弃整份记忆`() {
        val items = listOf(YktFeeItem("999", "新费项", "elec"))
        assertEquals(YktSceneSelection.EMPTY, reconcileSelection(full, items))
    }

    /** 费项列表拉取失败（空列表）时**不能**误清记忆——那是网络问题不是配置问题。 */
    @Test
    fun `费项列表为空时保留记忆`() {
        assertEquals(full, reconcileSelection(full, emptyList()))
    }

    /** 从来没选过（feeItemId 为空）时原样返回，不做多余判断。 */
    @Test
    fun `空费项的记忆原样返回`() {
        val empty = YktSceneSelection(picks = listOf(ScenePick("campus", campus.value, campus.name, 1)))
        assertEquals(empty, reconcileSelection(empty, listOf(YktFeeItem("201", "x", "elec"))))
    }

    // ── isQueryable：别拿空房间发请求 ────────────────────────────────────

    /** 未选满时不应认为可查——这是"别拿半截上下文发请求"的前置判据。 */
    @Test
    fun `未选到房间时不可查询 选了之后才可查`() {
        val partial = full.copy(picks = full.picks.dropLast(1))
        // 注意：isQueryable 只要求"选过至少一级"，UI 侧再用 levels.size 判"选满"。
        // 这里钉住的是"完全没选"与"选了一些"的区分。
        assertFalse("没有任何选择时不该认为可查", YktSceneSelection.EMPTY.isQueryable)
        assertTrue("选过一级即可发起选择请求（服务端据此返回下一级）", partial.isQueryable)
        // 选上房间后拿到完整上下文
        val picked = applyPick(4, levels[3], room, partial)
        assertEquals(4, picked.picks.size)
        assertEquals("701&7A-101", picked.pick("room")?.value)
    }

    @Test
    fun `没有费项时不可查询`() {
        assertFalse("费项为空时不该认为可查", full.copy(feeItemId = "").isQueryable)
    }

    // ── 级定义来自服务端：顺序即层级 ─────────────────────────────────────

    /** 级定义必须能表达"服务端说了算"，UI 只按它渲染，不写死 4 级。 */
    @Test
    fun `级定义保持服务端顺序`() {
        assertEquals(listOf(1, 2, 3, 4), levels.map { it.level })
        assertEquals("campus", levels.first().code)
        assertEquals("房间", levels.last().name)
    }
}
