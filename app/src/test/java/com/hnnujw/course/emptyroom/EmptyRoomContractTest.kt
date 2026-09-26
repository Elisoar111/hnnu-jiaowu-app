package com.hnnujw.course.emptyroom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 空闲教室查询契约（正方 `cdjy/cdjy_cxKxcdlb.html`，gnmkdm=N2155）。
 *
 * 这些断言钉的是**从页面与 `jquery.jqgrid.settings.js` 读出来的真实契约**，
 * 不是自造格式：
 * - `zcd`/`jcd` 是位掩码、`xqj` 是逗号列表（三者形态不同，最容易写混）；
 * - 分页参数名是 `queryModel.currentPage` / `queryModel.showCount`
 *   （jqGrid 默认的 `page`/`rows` 会被服务端忽略，表现为"翻页没反应"）；
 * - 隐藏域里带登录人身份，必须原样带回、且**不能**用旧值覆盖本次选的学期。
 */
class EmptyRoomContractTest {

    // ── 位掩码 / 列表 ───────────────────────────────────────────────────

    @Test
    fun `周次位掩码按 2 的幂求和`() {
        assertEquals(0L, weekMask(emptySet()))
        assertEquals(1L, weekMask(setOf(1)))
        assertEquals(2L, weekMask(setOf(2)))
        assertEquals(4L, weekMask(setOf(3)))
        assertEquals(7L, weekMask(setOf(1, 2, 3)))
        // 顺序不影响结果（集合本来无序）
        assertEquals(7L, weekMask(setOf(3, 1, 2)))
    }

    @Test
    fun `节次位掩码与周次同一套算法`() {
        assertEquals(0L, periodMask(emptySet()))
        assertEquals(3L, periodMask(setOf(1, 2)))
        assertEquals(1L + 2L + 4L + 8L, periodMask(setOf(1, 2, 3, 4)))
    }

    @Test
    fun `超出范围的周次与节次被忽略而不是溢出`() {
        // 0 与负数没有对应的 2 的幂；62 之后 Long 也放不下，一并丢掉。
        assertEquals(0L, weekMask(setOf(0, -1, 63)))
        assertEquals(1L, weekMask(setOf(0, 1, 999)))
        assertEquals(0L, periodMask(setOf(0, 100)))
    }

    @Test
    fun `超过 31 的周次不会溢出成负数`() {
        // Int 上 1 shl 31 是负数，JS 的 Math.pow 给的是正数 —— 必须用 Long 才不会
        // 把服务端收到一个负数掩码（那会被解释成"选了完全不同的周次"）。
        assertEquals(2147483648L, weekMask(setOf(32)))
        assertTrue(weekMask(setOf(40)) > 0)
    }

    @Test
    fun `星期是逗号列表而不是位掩码`() {
        assertEquals("1,3,5", weekdayList(setOf(5, 1, 3)))
        assertEquals("1,2,3,4,5,6,7", weekdayList(setOf(7, 6, 5, 4, 3, 2, 1)))
        assertEquals("", weekdayList(emptySet()))
        // 越界的星期要被丢掉，否则服务端会收到 "0" 或 "8"
        assertEquals("1", weekdayList(setOf(0, 1, 8)))
        // 去重
        assertEquals("2", weekdayList(setOf(2, 2)))
    }

    // ── 学期值 ──────────────────────────────────────────────────────────

    @Test
    fun `学期值拆成 xnm 与 xqm`() {
        assertEquals("2026" to "3", termParts("2026-3"))
        assertEquals("2026" to "12", termParts("2026-12"))
        assertEquals("2026" to "16", termParts("2026-16"))
    }

    @Test
    fun `畸形学期值给空而不是猜一个`() {
        assertEquals("" to "", termParts(""))
        assertEquals("" to "", termParts("2026"))
        assertEquals("" to "", termParts("-3"))
        assertEquals("" to "", termParts("abcd-3"))
        assertEquals("" to "", termParts("2026-"))
    }

    // ── 校验 ────────────────────────────────────────────────────────────

    private fun valid() = EmptyRoomQuery(
        term = "2026-3",
        campusId = "1",
        weeks = setOf(3),
        weekdays = setOf(1),
        periods = setOf(1, 2),
    )

    @Test
    fun `条件齐全时校验通过`() {
        assertNull(validateQuery(valid()))
    }

    @Test
    fun `缺任何一项时间条件都拦住`() {
        assertNotNull(validateQuery(valid().copy(weeks = emptySet())))
        assertNotNull(validateQuery(valid().copy(weekdays = emptySet())))
        assertNotNull(validateQuery(valid().copy(periods = emptySet())))
    }

    @Test
    fun `缺校区或学期也拦住`() {
        assertNotNull(validateQuery(valid().copy(campusId = "")))
        assertNotNull(validateQuery(valid().copy(term = "")))
        assertNotNull(validateQuery(valid().copy(term = "2026")))
    }

    @Test
    fun `座位数只有一端时不拦`() {
        // 只填下限或只填上限都是合法用法（服务端对空值本来就是"不限"）
        assertNull(validateQuery(valid().copy(minSeats = "50")))
        assertNull(validateQuery(valid().copy(maxSeats = "50")))
    }

    @Test
    fun `座位数区间倒置拦住`() {
        assertEquals("座位数：起始不能大于结束", validateQuery(valid().copy(minSeats = "50", maxSeats = "10")))
    }

    @Test
    fun `座位数输入中间态不算矛盾`() {
        // 输入框里是"5"（用户想打 50 但还没打完）或非数字时不该拦人
        assertFalse(seatRangeInvalid("", "10"))
        assertFalse(seatRangeInvalid("abc", "10"))
        assertFalse(seatRangeInvalid("50", "abc"))
        assertFalse(seatRangeInvalid("10", "10"))
        assertTrue(seatRangeInvalid("11", "10"))
    }

    // ── 表单字段 ────────────────────────────────────────────────────────

    private val hiddenFixture = mapOf(
        "fwzt" to "cx",
        "xnm" to "2025",
        "xqm" to "12",
        "cdyysqkz" to "0",
        "xqFlag" to "0",
        "syr" to "2505050111",
        "syrxm" to "胡敏翔/2505050111",
        "yuyjsctbj" to "1",
        "jylysfbt" to "",
        "cdjylx" to "",
    )

    private fun fieldsOf(query: EmptyRoomQuery, page: Int = 1) =
        EmptyRoomClient.queryFields(
            condition = query,
            hidden = hiddenFixture,
            remoteParam = "N211205-kxcdlb",
            pageIndex = page,
            pageSize = EmptyRoomClient.PAGE_SIZE,
        ).toMap()

    @Test
    fun `时间条件按位掩码与列表分别提交`() {
        val fields = fieldsOf(valid().copy(weeks = setOf(1, 2, 3), weekdays = setOf(1, 5), periods = setOf(1, 2)))
        assertEquals("7", fields["zcd"])        // 2^0 + 2^1 + 2^2
        assertEquals("1,5", fields["xqj"])      // 列表，不是 17
        assertEquals("3", fields["jcd"])        // 2^0 + 2^1
        assertEquals("0", fields["jyfs"])       // 0 = 按周次/星期/节次
    }

    @Test
    fun `分页参数用 queryModel 前缀`() {
        val fields = fieldsOf(valid(), page = 3)
        assertEquals("3", fields["queryModel.currentPage"])
        assertEquals(EmptyRoomClient.PAGE_SIZE.toString(), fields["queryModel.showCount"])
        assertEquals("cdbh", fields["queryModel.sortName"])
        assertEquals("asc", fields["queryModel.sortOrder"])
        // jqGrid 的默认名一旦被写回来，服务端会忽略分页 —— 这正是要防的回归
        assertFalse(fields.containsKey("page"))
        assertFalse(fields.containsKey("rows"))
    }

    @Test
    fun `本次选的学期覆盖页面隐藏域里的旧学期`() {
        // 隐藏域里是 2025-12（页面渲染时的默认值），本次选的是 2026-3
        val fields = fieldsOf(valid())
        assertEquals("2026", fields["xnm"])
        assertEquals("3", fields["xqm"])
    }

    @Test
    fun `登录人身份原样带回`() {
        // 服务端按 syr 认人。丢了这个字段，查询会变成"以匿名身份查"，
        // 学校侧可能按无权限处理。
        val fields = fieldsOf(valid())
        assertEquals("2505050111", fields["syr"])
        assertEquals("胡敏翔/2505050111", fields["syrxm"])
        assertEquals("cx", fields["fwzt"])
    }

    @Test
    fun `未选的可选项提交空串而不是省略`() {
        val fields = fieldsOf(valid())
        assertEquals("", fields["lh"])
        assertEquals("", fields["cdlb_id"])
        assertEquals("", fields["cdejlb_id"])
        assertEquals("", fields["cdmc"])
        assertEquals("", fields["cd_id"])
        assertEquals("", fields["cdjylx"])
    }

    @Test
    fun `座位数与关键词两端都提交`() {
        val fields = fieldsOf(valid().copy(minSeats = "50", maxSeats = "120", keyword = "物理楼"))
        assertEquals("50", fields["qszws"])
        assertEquals("120", fields["jszws"])
        assertEquals("物理楼", fields["cdmc"])
    }

    @Test
    fun `关键词与座位数前后空格被去掉`() {
        val fields = fieldsOf(valid().copy(minSeats = " 50 ", keyword = " 102 "))
        assertEquals("50", fields["qszws"])
        assertEquals("102", fields["cdmc"])
    }

    @Test
    fun `remoteParam 为空时不提交 zd_fzdm`() {
        val fields = EmptyRoomClient.queryFields(valid(), hiddenFixture, "", 1, 100).toMap()
        assertFalse(fields.containsKey("zd_fzdm"))
    }

    // ── 页面解析 ────────────────────────────────────────────────────────

    /** 真实查询页的关键片段：五个 select + 周次表头（含一个被置灰的周次）。 */
    private val pageHtml = """
        <html><body>
        <form id="searchForm" action="/jwglxt/cdjy/cdjy_cxKxcdlb.html" method="post">
          <input type="hidden" id="fwzt" value="cx" name="fwzt"/>
          <input type="hidden" name="xnm" value="2026" id="xnm"/>
          <input type="hidden" name="xqm" value="3" id="xqm"/>
          <input type="hidden" id="syr" name="syr" value="2505050111"/>
          <input type="hidden" id="syrxm" name="syrxm" value="胡敏翔/2505050111"/>
          <select name="dm" id="dm_cx" class="form-control chosen-select">
            <option value="2026-3" selected="selected">2026-2027-1</option>
          </select>
          <select name="xqh_id" id="xqh_id" class="form-control chosen-select">
            <option value="1" selected="selected">朝阳校区</option>
            <option value="3">应用技术学院</option>
            <option value="4">淮南联大</option>
            <option value="2">泉山校区</option>
          </select>
          <select name="lh" id="lh" class="form-control chosen-select">
            <option value="">全部</option>
            <option value="13">朝阳教学楼</option>
            <option value="15">物理楼</option>
            <option value="wlh">无楼号</option>
          </select>
          <select name="cdlb_id" id="cdlb_id" class="form-control chosen-select">
            <option value="">全部</option>
            <option value="005">普通教室</option>
            <option value="011">多媒体教室</option>
          </select>
          <select name="cdejlb_id" id="cdejlb_id" class="form-control chosen-select">
            <option value="">全部</option>
          </select>
          <table><thead id="selectTR_ZC">
            <tr>
              <th width="40" rowspan="2">周次</th>
              <th width="30" class="selectTH" value="1">1</th>
              <th width="30" class="displaynone" value="2">2</th>
              <th width="30" class="selectTH" value="3">3</th>
              <th width="30" class="selectTH" value="10">10</th>
            </tr>
          </thead></table>
        </form>
        <script>
          // 这段脚本里也有 '#selectTR_ZC .selectTH' 这样的字符串，解析器不能被它带偏
          jQuery("#selectTR_ZC .selectTH.ui-selected").each(function(i,dom){});
        </script>
        </body></html>
    """.trimIndent()

    @Test
    fun `解析出五个下拉与周次`() {
        val filters = EmptyRoomParser.filters(pageHtml)
        assertEquals(listOf("2026-3"), filters.terms.map { it.value })
        assertEquals("2026-2027-1", filters.terms.first().label)
        assertEquals(listOf("1", "3", "4", "2"), filters.campuses.map { it.value })
        assertEquals(listOf("", "13", "15", "wlh"), filters.buildings.map { it.value })
        assertEquals("全部", filters.buildings.first().label)
        assertEquals(listOf("", "005", "011"), filters.categories.map { it.value })
        assertEquals("2026-3", filters.defaultTerm)
        assertFalse(filters.isEmpty)
    }

    @Test
    fun `被置灰的周次不进候选`() {
        // class=displaynone 的周次是服务端按学期进度禁用的，收进来会变成点不动的格子
        assertEquals(listOf(1, 3, 10), EmptyRoomParser.filters(pageHtml).weeks)
    }

    @Test
    fun `隐藏域里带着登录人身份`() {
        val hidden = EmptyRoomParser.hiddenFields(pageHtml)
        assertEquals("2505050111", hidden["syr"])
        assertEquals("胡敏翔/2505050111", hidden["syrxm"])
        assertEquals("cx", hidden["fwzt"])
    }

    @Test
    fun `认不出的页面给出空的筛选项而不是抛异常`() {
        assertEquals(EmptyRoomFilters.EMPTY, EmptyRoomParser.filters(""))
        assertTrue(EmptyRoomParser.filters("<html><body>登录</body></html>").isEmpty)
        assertTrue(EmptyRoomParser.hiddenFields("不是 HTML").isEmpty())
    }

    // ── 结果解析 ────────────────────────────────────────────────────────

    /** 真实响应片段：字段名与噪声字段（date/queryModel/userModel）都照抄。 */
    private val jsonFixture = """
        {
          "currentPage": 1,
          "totalResult": 54,
          "totalPage": 6,
          "showCount": 10,
          "items": [
            {
              "cd_id": "B557D3F3E7EA515DE053A9B046D39767",
              "cdbh": "12102",
              "cdlb_id": "005",
              "cdlbmc": "普通教室",
              "cdmc": "朝阳物理楼-102",
              "date": "二○二六年九月二十六日",
              "jxlmc": "物理楼",
              "kszws1": "55",
              "lch": "1",
              "lh": "15",
              "queryModel": {"currentPage": 1, "showCount": 10},
              "row_id": "1",
              "userModel": {"usable": false},
              "xqh_id": "1",
              "xqmc": "朝阳校区",
              "zws": "121"
            },
            {
              "cd_id": "C11111111111111111111111111111111",
              "cdlbmc": "多媒体教室",
              "cdmc": "朝阳教学楼-105",
              "jxlmc": "朝阳教学楼",
              "kszws1": "0",
              "lch": "3",
              "xqmc": "朝阳校区",
              "zws": 60
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `解析结果行只取场地属性`() {
        val page = EmptyRoomParser.rooms(jsonFixture)
        assertEquals(54, page.total)
        assertEquals(1, page.currentPage)
        assertEquals(6, page.pageTotal)
        assertEquals(2, page.rooms.size)

        val first = page.rooms[0]
        assertEquals("B557D3F3E7EA515DE053A9B046D39767", first.id)
        assertEquals("12102", first.code)
        assertEquals("朝阳物理楼-102", first.name)
        assertEquals("普通教室", first.category)
        assertEquals("物理楼", first.building)
        assertEquals("1", first.floor)
        assertEquals("朝阳校区", first.campus)
        assertEquals(121, first.seats)
        assertEquals(55, first.examSeats)
    }

    @Test
    fun `座位数数字与字符串两种形态都认`() {
        val rooms = EmptyRoomParser.rooms(jsonFixture).rooms
        assertEquals(121, rooms[0].seats)   // 字符串 "121"
        assertEquals(60, rooms[1].seats)    // 数字 60
    }

    @Test
    fun `缺失字段给空值而不是编造`() {
        // 第二行刻意不写 cdbh：学校数据里场地编号可能为空。
        // 这里要的是"缺了就空着"，而不是拿 cd_id 或场地名去顶替。
        val second = EmptyRoomParser.rooms(jsonFixture).rooms[1]
        assertEquals("多媒体教室", second.category)
        assertEquals("", second.code)
        assertEquals("3", second.floor)
        assertEquals(0, second.examSeats) // kszws1 是 "0"
        assertEquals(60, second.seats)
    }

    @Test
    fun `信封的多种包装形态都能解析`() {
        // 同一个网关对不同路由的包装并不一致（二课曾因此静默空列表）
        val rows = """
            {"totalResult":"3","rows":[{"cd_id":"a","cdmc":"A"}]}
        """.trimIndent()
        assertEquals(listOf("a"), EmptyRoomParser.rooms(rows).rooms.map { it.id })

        val nested = """
            {"data":{"list":[{"cd_id":"b","cdmc":"B"}],"totalResult":1}}
        """.trimIndent()
        assertEquals(listOf("b"), EmptyRoomParser.rooms(nested).rooms.map { it.id })

        val deepNested = """
            {"data":{"data":[{"cd_id":"c","cdmc":"C"}]}}
        """.trimIndent()
        assertEquals(listOf("c"), EmptyRoomParser.rooms(deepNested).rooms.map { it.id })

        val topList = """
            {"list":[{"cd_id":"d","cdmc":"D"}]}
        """.trimIndent()
        assertEquals(listOf("d"), EmptyRoomParser.rooms(topList).rooms.map { it.id })
    }

    @Test
    fun `真的没有空教室时总数是 0 而页面不报错`() {
        val page = EmptyRoomParser.rooms("""{"items":[],"totalResult":"0","currentPage":1,"totalPage":0}""")
        assertEquals(0, page.total)
        assertTrue(page.rooms.isEmpty())
    }

    @Test
    fun `非 JSON 给出空页面而不是抛异常`() {
        assertEquals(EmptyRoomParser.RoomPage.EMPTY, EmptyRoomParser.rooms("<html>登录</html>"))
        assertEquals(EmptyRoomParser.RoomPage.EMPTY, EmptyRoomParser.rooms(""))
    }

    @Test
    fun `总数缺失时退化成实际行数`() {
        val page = EmptyRoomParser.rooms("""{"items":[{"cd_id":"a"},{"cd_id":"b"}]}""")
        assertEquals(2, page.total)
    }

    // ── 展示 ────────────────────────────────────────────────────────────

    @Test
    fun `副标题按 楼栋 楼层 类别 拼接并省略空段`() {
        val room = EmptyRoom(
            id = "x", code = "12102", name = "朝阳物理楼-102", category = "普通教室",
            building = "物理楼", floor = "1", campus = "朝阳校区", seats = 121, examSeats = 55,
        )
        assertEquals("物理楼 · 1 层 · 普通教室", room.subtitle)
        assertEquals("121 座", room.seatText)
    }

    @Test
    fun `没有楼栋时退回校区名`() {
        val room = EmptyRoom(
            id = "x", code = "", name = "室外场地", category = "",
            building = "", floor = "", campus = "泉山校区", seats = 0, examSeats = 0,
        )
        assertEquals("泉山校区", room.subtitle)
        // 座位数 0 是"服务端没给"，不能显示"0 座"（会被读成没座位）
        assertEquals("", room.seatText)
    }

    // ── 校区可选项（cdjy/cdjy_cxXqjc.html）──────────────────────────────
    //
    // 契约逐字来自 `js/comp/jwglxt/pkgl/cdjy/kxcdlb.js` 的 `hqjcList()`：
    //   $.getJSON(_path + "/cdjy/cdjy_cxXqjc.html", {xqh_id, xnm, xqm}, function(data){
    //       $.each(data.lhList||[], function(i,rowObj){ rowObj["JXLDM"] / rowObj["JXLMC"] })
    //       $.each(data.jcList||[], function(i,rowObj){ rowObj["JCMC"] / rowObj["RSDJCMC"] })
    //   })
    // 它挂 `#xqh_id` 的 change，并把 `#lh`（楼号）整个清空重建 —— 楼号是校区私有数据。

    private val campusOptionsJson = """
        {
          "jcList": [
            {"JCMC": "1", "RSDJCMC": "第1节", "RSDMC": "上午"},
            {"JCMC": "2", "RSDJCMC": "第2节", "RSDMC": "上午"},
            {"JCMC": "3", "RSDMC": "上午"},
            {"JCMC": "4", "RSDJCMC": "第4节", "RSDMC": "下午"}
          ],
          "lhList": [
            {"JXLDM": "13", "JXLMC": "朝阳教学楼"},
            {"JXLDM": "19", "JXLMC": "朝阳校区校园"},
            {"JXLDM": "wlh", "JXLMC": "无楼号"}
          ]
        }
    """.trimIndent()

    @Test
    fun `解析校区楼号与节次`() {
        val options = EmptyRoomParser.campusOptions(campusOptionsJson)
        assertEquals(listOf("13", "19", "wlh"), options.buildings.map { it.value })
        assertEquals(listOf("朝阳教学楼", "朝阳校区校园", "无楼号"), options.buildings.map { it.label })
        assertEquals(listOf("1", "2", "3", "4"), options.periods.map { it.value })
        // RSDJCMC 是节次显示名；缺了就退回 JCMC 本身（与 kxcdlb.js 的分支一致）
        assertEquals(listOf("第1节", "第2节", "3", "第4节"), options.periods.map { it.label })
    }

    @Test
    fun `校区可选项的键名大小写与数字形态都认`() {
        val json = """{"lhList":[{"jxlDm":"13","jxlMc":"朝阳教学楼"}],"jcList":[{"jcmc":1,"rsdjcmc":"第1节"}]}"""
        val options = EmptyRoomParser.campusOptions(json)
        assertEquals(listOf("13"), options.buildings.map { it.value })
        assertEquals(listOf("朝阳教学楼"), options.buildings.map { it.label })
        assertEquals(listOf("1"), options.periods.map { it.value })
        assertEquals(listOf("第1节"), options.periods.map { it.label })
    }

    @Test
    fun `选项直接给成字符串数组也认`() {
        val options = EmptyRoomParser.campusOptions("""{"lhList":["13","15"]}""")
        assertEquals(listOf("13", "15"), options.buildings.map { it.value })
        assertEquals(listOf("13", "15"), options.buildings.map { it.label })
    }

    @Test
    fun `认不出的校区可选项给空而不是编造`() {
        assertEquals(EmptyRoomCampusOptions.EMPTY, EmptyRoomParser.campusOptions("<html>登录</html>"))
        assertEquals(EmptyRoomCampusOptions.EMPTY, EmptyRoomParser.campusOptions(""))
        assertEquals(EmptyRoomCampusOptions.EMPTY, EmptyRoomParser.campusOptions("""{"items":[]}"""))
        // 键在、但每项都没有可用值 ⇒ 也要丢掉，不能变成一条空选项
        assertTrue(EmptyRoomParser.campusOptions("""{"lhList":[{"foo":"bar"}]}""").buildings.isEmpty())
    }

    @Test
    fun `校区可选项路径由查询页路径同目录推导`() {
        assertEquals("/cdjy/cdjy_cxXqjc.html", EmptyRoomClient.campusOptionsPath("/cdjy/cdjy_cxKxcdlb.html"))
        assertEquals("/x/cdjy/cdjy_cxXqjc.html", EmptyRoomClient.campusOptionsPath("/x/cdjy/cdjy_cxKxcdlb.html"))
        assertEquals("cdjy/cdjy_cxXqjc.html", EmptyRoomClient.campusOptionsPath("cdjy/cdjy_cxKxcdlb.html"))
        // 配置为空时退回学校实际使用的目录，而不是拼出一个裸文件名
        assertEquals("/cdjy/cdjy_cxXqjc.html", EmptyRoomClient.campusOptionsPath(""))
    }

    @Test
    fun `校区可选项请求带 xqh_id xnm xqm 与 gnmkdm`() {
        val fields = EmptyRoomClient.campusOptionsFields("2", "2026-12", "N2155").toMap()
        assertEquals("2", fields["xqh_id"])
        assertEquals("2026", fields["xnm"])
        assertEquals("12", fields["xqm"])
        // 该路由缺 gnmkdm 会直接回「HTTP请求参数gnmkdm不能为空！」
        assertEquals("N2155", fields["gnmkdm"])
    }

    @Test
    fun `query string 用百分号而不是加号表示空格`() {
        // URLEncoder 默认把空格编成 '+'，在 query string 里会被服务端读成字面加号
        assertEquals("k=a%20b", EmptyRoomClient.queryString(listOf("k" to "a b")))
        assertEquals("a=1&b=2", EmptyRoomClient.queryString(listOf("a" to "1", "b" to "2")))
    }

    // ── 校区 → 楼号 / 节次 的归属判据 ───────────────────────────────────
    //
    // 网页在 `#xqh_id` 的 change 里把 `#lh` 清空重建。不跟着换列表就会
    // 拿 A 校区的楼号去查 B 校区：服务端**不报错**，只返回空集，
    // 用户看到的是"这个校区没有空教室" —— 静默错误比缺选项严重得多。

    private val pageBuildings = listOf(EmptyRoomOption("", "全部"), EmptyRoomOption("13", "朝阳教学楼"))
    private val qsBuildings = listOf(EmptyRoomOption("21", "泉山教学楼"))

    @Test
    fun `切到别的校区后用该校区的楼号`() {
        val options = buildingOptionsFor(
            campusId = "2", defaultCampusId = "1",
            pageBuildings = pageBuildings,
            loadedCampusId = "2", loadedBuildings = qsBuildings,
        )
        assertEquals(listOf("", "21"), options.map { it.value })
        assertEquals("全部", options.first().label)
    }

    @Test
    fun `拉到的校区数据与当前校区对不上时只剩全部`() {
        // 拉的是 2 校区（泉山），界面已经切到 4 校区（淮南联大）
        val options = buildingOptionsFor(
            campusId = "4", defaultCampusId = "1",
            pageBuildings = pageBuildings,
            loadedCampusId = "2", loadedBuildings = qsBuildings,
        )
        assertEquals(listOf(""), options.map { it.value })
    }

    @Test
    fun `默认校区在没拉到数据时用页面直出的楼号`() {
        // 页面直出的 `#lh` 只对服务端默认校区有效，此时用它是对的
        val options = buildingOptionsFor(
            campusId = "1", defaultCampusId = "1",
            pageBuildings = pageBuildings,
            loadedCampusId = "", loadedBuildings = emptyList(),
        )
        assertEquals(listOf("", "13"), options.map { it.value })
    }

    @Test
    fun `非默认校区在没拉到数据时不给楼号`() {
        val options = buildingOptionsFor(
            campusId = "2", defaultCampusId = "1",
            pageBuildings = pageBuildings,
            loadedCampusId = "", loadedBuildings = emptyList(),
        )
        assertEquals(listOf(""), options.map { it.value })
    }

    @Test
    fun `校区为空时不给楼号`() {
        val options = buildingOptionsFor(
            campusId = "", defaultCampusId = "1",
            pageBuildings = pageBuildings,
            loadedCampusId = "", loadedBuildings = emptyList(),
        )
        assertEquals(listOf(""), options.map { it.value })
    }

    @Test
    fun `节次按校区给，给不出才退回课表设置的节次数`() {
        val loaded = listOf(EmptyRoomOption("1", "第1节"), EmptyRoomOption("2", "第2节"))
        assertEquals(loaded, periodOptionsFor("2", "2", loaded, 12))
        // 校区对不上 ⇒ 退回 1..fallbackCount，不能把别的校区的节次名挂上来
        assertEquals(
            listOf("1", "2", "3"),
            periodOptionsFor("2", "1", loaded, 3).map { it.value },
        )
        assertEquals(
            listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12"),
            periodOptionsFor("2", "", emptyList(), 12).map { it.value },
        )
    }
}
