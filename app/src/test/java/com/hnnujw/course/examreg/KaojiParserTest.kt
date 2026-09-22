package com.hnnujw.course.examreg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 考级项目报名解析器回归（协议样本 2026-09-22 线上抓取，个人信息已脱敏）。
 *
 * 入口页是服务端直出 HTML：卡片按 `xm_block` 切块、批次主键在 `xmMap["index_N"]`、
 * 报名说明在 `bmsm_<id>` 隐藏域。已报名列表是 `?doType=query` 的 JSON 信封。
 */
class KaojiParserTest {

    /** 与线上入口页同构的最小样本（两个项目：一个可报名、一个已报满无按钮）。 */
    private val indexHtml = """
        <form id="ajaxForm" name="ajaxForm" action="/jwglxt/kjgl/kjbm_cxXskjbm.html" method="post">
        <input type="hidden" name="xnm" value="2026" id="xnm"/>
        <input type="hidden" name="xqm" value="3" id="xqm"/>
        <input type="hidden" name="xmlbfl" value="1001" id="xmlbfl"/>
        <h4 class="sl_tit_kbmxm clearfix">2026-2027学年1学期等级考试报名</h4>
        <div class="col-sm-6 xm_block">
          <dl>
            <dt class="row show-grid-15">
              <input type="hidden" name="" value="请携带身份证" id="bmsm_AA11"/>
              <input type="hidden" name="" value="" id="bmhsm_AA11"/>
              <h4 class="col-xs-6">第1批次，普通话测试</h4>
              <span class="col-xs-4 pull-right">还剩余2天</span>
            </dt>
            <dd class="row"><span class="col-xs-12">还剩余人数2886人，费用25.00元整</span></dd>
            <dd class="row"><span class="col-xs-12">开始时间： 2026-09-07 08:00:00  截止时间：2026-09-24 23:00:00</span></dd>
          </dl>
          <div class="panel-footer">
            <input type="hidden" name="xmlbdm" value="XM03"/>
            <input type="hidden" name="bmpc" value="1"/>
            <input type="hidden" name="ybcs" value="0"/>
            <button type="button" class="btn btn-primary btn-sm col-sm-offset-10 btn_xmbm">报名</button>
          </div>
        </div>
        <div class="col-sm-6 xm_block">
          <dl>
            <dt class="row show-grid-15">
              <h4 class="col-xs-6">第2批次，CET4</h4>
            </dt>
            <dd class="row"><span class="col-xs-12">还剩余人数0人，费用30.00元整</span></dd>
            <dd class="row"><span class="col-xs-12">开始时间： 2026-09-08 08:00:00  截止时间：2026-09-20 23:00:00</span></dd>
          </dl>
          <div class="panel-footer">
            <input type="hidden" name="xmlbdm" value="XM01"/>
            <input type="hidden" name="bmpc" value="2"/>
          </div>
        </div>
        </form>
        <script type="text/javascript">
        var xmMap = {};
        xmMap["index_0"] = 'AA11';
        xmMap["index_1"] = 'BB22';
        </script>
    """.trimIndent()

    @Test
    fun `parseIndexPage extracts title, term params and projects`() {
        val page = KaojiParser.parseIndexPage(indexHtml)
        assertNotNull(page)
        page!!
        assertEquals("2026-2027学年1学期等级考试报名", page.title)
        assertEquals("2026", page.xnm)
        assertEquals("3", page.xqm)
        assertEquals("1001", page.xmlbfl)
        assertEquals(2, page.projects.size)
    }

    @Test
    fun `parseIndexPage maps project ids by block order`() {
        val page = KaojiParser.parseIndexPage(indexHtml)!!
        assertEquals("AA11", page.projects[0].id)
        assertEquals("BB22", page.projects[1].id)
    }

    @Test
    fun `parseIndexPage extracts card facts and notice`() {
        val first = KaojiParser.parseIndexPage(indexHtml)!!.projects[0]
        assertEquals("第1批次，普通话测试", first.title)
        assertEquals("还剩余2天", first.remainDaysText)
        assertEquals("还剩余人数2886人", first.remainSeatsText)
        assertEquals("费用25.00元", first.feeText)
        assertEquals("2026-09-07 08:00:00", first.beginTime)
        assertEquals("2026-09-24 23:00:00", first.endTime)
        assertEquals("请携带身份证", first.notice)
        assertEquals("XM03", first.categoryId)
        assertEquals("1", first.batch)
        assertTrue(first.canRegister)
    }

    @Test
    fun `project without register button is not registrable`() {
        val second = KaojiParser.parseIndexPage(indexHtml)!!.projects[1]
        assertFalse(second.canRegister)
    }

    @Test
    fun `parseRegistered reads items envelope`() {
        val body = """
            {"currentPage":1,"items":[
              {"xsbmqk_id":"R1","xmbmsz_id":"BB22","xmlbmc":"全国大学英语四、六级考试","xmmc":"CET4",
               "bmfy":"35.00","bmsj":"2026-03-10 17:05:04","kssj":"2025-05-01 14:38:04","jssj":"2025-05-01 14:38:10",
               "zkzh":"340200252106801","zsbh":"252134020003014","xmcj":"440.00","shjg":"3",
               "sjhm":"13800000000","xnmc":"2025-2026","xqm":"3","xqmmc":"1"}
            ]}
        """.trimIndent()
        val list = KaojiParser.parseRegistered(body)
        assertEquals(1, list.size)
        val r = list[0]
        assertEquals("R1", r.id)
        assertEquals("CET4", r.name)
        assertEquals("全国大学英语四、六级考试", r.category)
        assertEquals("35.00", r.fee)
        assertEquals("340200252106801", r.ticketNo)
        assertEquals("440.00", r.score)
        assertEquals("2025-2026 第1学期", r.term)
        assertEquals("13800000000", r.phone)
    }

    @Test
    fun `parseRegistered tolerates bad json and rows envelope`() {
        assertTrue(KaojiParser.parseRegistered("not json").isEmpty())
        val rows = KaojiParser.parseRegistered("""{"rows":[{"xsbmqk_id":"R2","xmmc":"CET6"}]}""")
        assertEquals(1, rows.size)
        assertEquals("R2", rows[0].id)
        // 缺失字段一律空串，不抛异常
        assertEquals("", rows[0].category)
    }

    @Test
    fun `parseFormPage extracts hidden fields and phone`() {
        val html = """
            <form id="chlidForm" name="chlidForm" action="/jwglxt/kjgl/kjbm_zjBcXskjbm.html" method="post" enctype="multipart/form-data">
            <input type="hidden" name="xmbmsz_id" value="AA11" id="xmbmsz_id"/>
            <input type="hidden" name="oldSjhm" value="13800000000" id="oldSjhm"/>
            <input type="hidden" name="gdbj" value="0" id="gdbj"/>
            <input type="hidden" name="xxdm" value="10381" id="xxdm"/>
            <input type="text" name="sjhm" value="13800000000" id="sjhm"/>
            </form>
        """.trimIndent()
        val snapshot = KaojiParser.parseFormPage(html)
        assertNotNull(snapshot)
        snapshot!!
        assertEquals("AA11", snapshot.fields["xmbmsz_id"])
        assertEquals("10381", snapshot.fields["xxdm"])
        assertEquals("13800000000", snapshot.phone)
        assertEquals("13800000000", snapshot.oldPhone)
    }

    @Test
    fun `hiddenFields handles attribute order variants`() {
        val fields = KaojiParser.hiddenFields(
            """<input value="B" type="hidden" name="b"/><input type="hidden" name="a" value="A"/>"""
        )
        assertEquals("A", fields["a"])
        assertEquals("B", fields["b"])
    }
}
