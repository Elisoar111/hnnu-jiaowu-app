package com.hnnujw.course.secondclass

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 积分明细**响应形态归一**的回归测试（按分类 / 按学期两个端点）。
 *
 * ## 为什么这组测试必须存在
 *
 * 症状是「**按分类查看显示没有下发明细**，但按学期正常」。它属于最难发现的一类缺陷：
 *
 * - 接口返回 **HTTP 200**，没有异常、没有错误日志、编译更不会报错；
 * - 解析结果只是"空列表"，界面于是渲染一句"该校没有按分类下发明细"——
 *   这句文案本身是**合理**的（学校确实可能不开），于是缺陷看起来像正常空态；
 * - 真因只是**响应包装形态**与代码假设不一致。
 *
 * 所以这里把每种已知形态都用一个用例钉住。任何形态回归（比如又只认 `data.list`），
 * 对应用例立刻 FAILED —— 不会再有"静默变空"。
 *
 * ## 关键对照
 *
 * 学期维与分类维共用同一套形态归一（[termGroupsArray] / [classifyGroupsArray]）。
 * 两者**必须一致**：如果只有一边放宽，就会出现"切到另一个维度就好了"这种
 * 极难复现、极难定位的现象 —— 正是这次报上来的症状。
 */
class SecondClassPointPayloadTest {

    // ── 构造工具 ─────────────────────────────────────────────────────────

    /** 一条分类维度的记录（带 amount / sourceType / relationId）。 */
    private fun classifyRecord(name: String, hours: Double) = JSONObject()
        .put("name", name)
        .put("hours", hours)
        .put("amount", hours)
        .put("time", 1_772_956_800_000L)
        .put("sourceType", 3)
        .put("relationId", 77266)

    /** 一条学期维度的记录（带 classifyName）。 */
    private fun termRecord(name: String, hours: Double, classify: String) = JSONObject()
        .put("name", name)
        .put("hours", hours)
        .put("classifyName", classify)
        .put("time", 1_772_956_800_000L)

    private fun classifyGroup(name: String, hours: Double) = JSONObject()
        .put("classifyId", 12)
        .put("classifyName", name)
        .put("classifyHours", hours)
        .put("minHours", 10.0)
        .put("hoursRecordList", JSONArray().put(classifyRecord("$name-活动", hours)))

    private fun termGroup(name: String, hours: Double) = JSONObject()
        .put("termName", name)
        .put("termNumber", "1")
        .put("termHours", hours)
        .put("termHoursUnit", "3")
        .put("hoursRecordList", JSONArray().put(termRecord("$name-活动", hours, "文体活动")))

    // ── 形态 1：data 直接是数组（原有实现已支持，锁住不回归）─────────────

    @Test
    fun `分类 data 直接是数组`() {
        val payload = JSONObject().put("data", JSONArray().put(classifyGroup("文体活动", 31.0)))
        val groups = payload.toClassifyGroups()
        assertEquals(1, groups.size)
        assertEquals("文体活动", groups[0].name)
        assertEquals(31.0, groups[0].siteTotal!!, 0.001)
    }

    @Test
    fun `学期 data 直接是数组`() {
        val payload = JSONObject().put("data", JSONArray().put(termGroup("2025-2026", 31.0)))
        val groups = payload.toTermGroups()
        assertEquals(1, groups.size)
        assertEquals("2025-2026", groups[0].name)
    }

    // ── 形态 2：data.list（原有实现已支持）──────────────────────────────

    @Test
    fun `分类 data 包 list`() {
        val payload = JSONObject().put(
            "data",
            JSONObject().put("list", JSONArray().put(classifyGroup("实践实习", 26.5))).put("total", 1),
        )
        val groups = payload.toClassifyGroups()
        assertEquals(1, groups.size)
        assertEquals("实践实习", groups[0].name)
    }

    @Test
    fun `学期 data 包 list`() {
        val payload = JSONObject().put(
            "data",
            JSONObject().put("list", JSONArray().put(termGroup("2025-2026", 26.5))).put("total", 1),
        )
        val groups = payload.toTermGroups()
        assertEquals(1, groups.size)
    }

    // ── 形态 3：data.rows（本次修复补充）────────────────────────────────

    /**
     * `data.rows` 是分页型接口的常见命名。旧实现只认 `list`，
     * 这种形态会**静默变成空列表** → 界面报"没有下发明细"。
     */
    @Test
    fun `分类 data 包 rows`() {
        val payload = JSONObject().put(
            "data",
            JSONObject().put("rows", JSONArray().put(classifyGroup("思想成长", 18.0)))
                .put("total", 1),
        )
        val groups = payload.toClassifyGroups()
        assertEquals("rows 形态必须被识别，否则界面会误报没有明细", 1, groups.size)
        assertEquals("思想成长", groups[0].name)
        assertEquals(18.0, groups[0].siteTotal!!, 0.001)
    }

    @Test
    fun `学期 data 包 rows`() {
        val payload = JSONObject().put(
            "data",
            JSONObject().put("rows", JSONArray().put(termGroup("2025-2026", 18.0))).put("total", 1),
        )
        assertEquals(1, payload.toTermGroups().size)
    }

    // ── 形态 4：双层 data（本次修复补充）────────────────────────────────

    /**
     * 网关把业务体又包一层时会出现 `data.data`。
     *
     * ⚠️ 这是**最可能的真凶**：同一套网关对不同路由的包装并不总是一致，
     * 于是 `by-term-list` 走 `data.list`（正常）、`by-classify-list` 走
     * `data.data`（旧代码取不到 → 恒空）。两者表现差异正好复现用户报的症状。
     */
    @Test
    fun `分类 data 双层时内层是数组`() {
        val payload = JSONObject().put(
            "data",
            JSONObject().put("data", JSONArray().put(classifyGroup("志愿公益", 22.0))),
        )
        val groups = payload.toClassifyGroups()
        assertEquals("双层 data（数组）必须被识别", 1, groups.size)
        assertEquals("志愿公益", groups[0].name)
    }

    @Test
    fun `分类 data 双层时内层包 list`() {
        val payload = JSONObject().put(
            "data",
            JSONObject().put(
                "data",
                JSONObject().put("list", JSONArray().put(classifyGroup("创新创业", 20.0))),
            ),
        )
        val groups = payload.toClassifyGroups()
        assertEquals("data.data.list 必须被识别", 1, groups.size)
        assertEquals("创新创业", groups[0].name)
    }

    @Test
    fun `分类 data 双层时内层包 rows`() {
        val payload = JSONObject().put(
            "data",
            JSONObject().put(
                "data",
                JSONObject().put("rows", JSONArray().put(classifyGroup("工作履历", 12.0))),
            ),
        )
        assertEquals(1, payload.toClassifyGroups().size)
    }

    /** 学期维同样要认双层 —— 两个维度**必须对称**，否则又会出现"换维度就好了"。 */
    @Test
    fun `学期 data 双层时内层是数组`() {
        val payload = JSONObject().put(
            "data",
            JSONObject().put("data", JSONArray().put(termGroup("2025-2026", 20.0))),
        )
        assertEquals("学期维必须与分类维同样宽容", 1, payload.toTermGroups().size)
    }

    @Test
    fun `学期 data 双层时内层包 list`() {
        val payload = JSONObject().put(
            "data",
            JSONObject().put(
                "data",
                JSONObject().put("list", JSONArray().put(termGroup("2025-2026", 20.0))),
            ),
        )
        assertEquals(1, payload.toTermGroups().size)
    }

    // ── 兜底：顶层直接给 list ────────────────────────────────────────────

    @Test
    fun `顶层直接给 list 也能取到`() {
        val payload = JSONObject().put("list", JSONArray().put(classifyGroup("文体活动", 5.0)))
        assertEquals(1, payload.toClassifyGroups().size)
    }

    // ── 真·空数据不能被误判 ──────────────────────────────────────────────

    /** 站点确实没数据时返回空列表（而不是抛异常）。 */
    @Test
    fun `无 data 键返回空列表`() {
        assertTrue(JSONObject().toClassifyGroups().isEmpty())
        assertTrue(JSONObject().toTermGroups().isEmpty())
    }

    @Test
    fun `data 为空数组返回空列表`() {
        val payload = JSONObject().put("data", JSONArray())
        assertTrue(payload.toClassifyGroups().isEmpty())
    }

    @Test
    fun `data 为 null 返回空列表`() {
        val payload = JSONObject().put("data", JSONObject.NULL)
        assertTrue(payload.toClassifyGroups().isEmpty())
        assertTrue(payload.toTermGroups().isEmpty())
    }

    /** 取数组本身的直测：确认各形态的返回值语义。 */
    @Test
    fun `取数组的多形态语义`() {
        assertNull("全都没有时必须是 null", classifyGroupsArray(JSONObject()))
        assertEquals(
            0,
            classifyGroupsArray(JSONObject().put("data", JSONArray()))!!.length(),
        )
        assertEquals(
            1,
            classifyGroupsArray(
                JSONObject().put(
                    "data",
                    JSONObject().put("list", JSONArray().put(JSONObject())),
                ),
            )!!.length(),
        )
    }

    // ── 分组与记录的字段映射 ─────────────────────────────────────────────

    /**
     * 分类分组的字段映射：`classifyName` / `classifyHours` / `minHours`。
     *
     * `minHours` 缺省时是 0.0（"学校没配下限"），有值时原样保留 ——
     * 界面用 `required > 0` 决定要不要画进度条，所以"没配"与"配了 0"必须同形。
     */
    @Test
    fun `分类分组字段映射`() {
        val group = classifyGroupFrom(classifyGroup("文体活动", 31.0))
        assertEquals("文体活动", group.name)
        assertEquals(31.0, group.siteTotal!!, 0.001)
        assertEquals(10.0, group.required, 0.001)
        assertEquals(1, group.records.size)
        assertEquals("文体活动-活动", group.records[0].name)
        assertEquals(31.0, group.records[0].hours, 0.001)
    }

    /** `classifyHours` 是 JSON `null` 时保持 null（= 站点没给小计），不能伪装成 0。 */
    @Test
    fun `分类小计为 null 时保持 null`() {
        val json = JSONObject()
            .put("classifyName", "文体活动")
            .put("classifyHours", JSONObject.NULL)
        val group = classifyGroupFrom(json)
        assertNull("站点没给小计不能让 total 伪装成 0", group.siteTotal)
        assertEquals(0.0, group.detailSum, 0.001)
    }

    /** 学期分组的字段映射：`termName` / `termHours` / `termNumber` / `termHoursUnit`。 */
    @Test
    fun `学期分组字段映射`() {
        val group = termGroupFrom(termGroup("2025-2026", 26.5))
        assertEquals("2025-2026", group.name)
        assertEquals(26.5, group.siteTotal!!, 0.001)
        assertEquals("1", group.termNumber)
        assertEquals("3", group.unit)
        assertEquals("文体活动", group.records[0].classifyName)
    }

    /**
     * 记录的 `amount` / `sourceType` / `relationId` 只有分类维才有。
     *
     * 学期维缺这些字段时必须落到默认值，**不能**拿恒为 `"3"` 的 `identity` 去顶
     * `relationId`（那会让一学期内所有记录 identity 退化成同一个值）。
     */
    @Test
    fun `学期的 identity 不得混入 relationId`() {
        val json = JSONObject()
            .put("name", "某活动")
            .put("hours", 3.0)
            .put("time", 1_772_956_800_000L)
            .put("identity", "3")
        val record = json.toPointRecordInternal()
        assertEquals("", record.relationId)
        assertEquals(0, record.sourceType)
    }

    /** 分类维的 `relationId` 必须原样带上，用于跨维度对齐。 */
    @Test
    fun `分类记录带上 relationId`() {
        val record = classifyRecord("某活动", 3.0).toPointRecordInternal()
        assertEquals("77266", record.relationId)
        assertEquals(3, record.sourceType)
    }

    /** 空壳分组（没名字也没明细）必须被丢掉，否则界面出现空白标题行。 */
    @Test
    fun `空壳分组被丢弃`() {
        val payload = JSONObject().put(
            "data",
            JSONArray()
                .put(JSONObject().put("classifyId", 12).put("classifyName", ""))
                .put(classifyGroup("文体活动", 31.0)),
        )
        val groups = payload.toClassifyGroups()
        assertEquals("只应保留有意义的那一个分组", 1, groups.size)
        assertEquals("文体活动", groups[0].name)
    }

    /** 有名字但没明细的分组要保留（站点只给小计不下发明细是常见情况）。 */
    @Test
    fun `有名字无明细的分组保留`() {
        val payload = JSONObject().put(
            "data",
            JSONArray().put(
                JSONObject().put("classifyName", "文体活动").put("classifyHours", 31.0),
            ),
        )
        val groups = payload.toClassifyGroups()
        assertEquals(1, groups.size)
        assertEquals(31.0, groups[0].siteTotal!!, 0.001)
        assertTrue("没有明细，但小计要在（这正是「有 N 分没有明细」的来源）", groups[0].records.isEmpty())
    }

    /**
     * **两个维度的形态宽容度必须一致**。
     *
     * 这是本次缺陷的一般化断言：对同一份"双层 data"载荷，分类与学期
     * 必须同时取到数据。任一维度放宽而另一个没跟上，就会重现
     * "按分类看不到、按学期看得到"。
     *
     * ⚠️ 夹具必须**同时**带 `classifyName` 与 `termName`：两个维度各自只认
     * 自己的字段名，缺了的一方会因为"分组无名且无明细"被
     * [isMeaningful] 正常丢弃 —— 那是**正确行为**，不是形态识别失败。
     * 用只带一个字段的夹具测这条会得到假失败（本用例初版即如此）。
     */
    @Test
    fun `两个维度的形态宽容度一致`() {
        val shared = JSONObject()
            .put("classifyName", "文体活动")
            .put("termName", "2025-2026")
        val payload = JSONObject().put(
            "data",
            JSONObject().put("data", JSONArray().put(shared)),
        )
        assertEquals(
            "分类维对双层 data 应有结果",
            1,
            payload.toClassifyGroups().size,
        )
        assertEquals(
            "学期维对同一载荷也应有结果（不能一边有数据一边恒空）",
            1,
            payload.toTermGroups().size,
        )
    }
}
