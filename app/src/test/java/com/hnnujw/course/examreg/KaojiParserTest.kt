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

    // ── 2026-09-24 线上事故回归：零卡片 ≠ 读取失败 ────────────────────────
    //
    // 用户上一版还能读到项目，某个晚上开始只剩「读取失败」，而教务会话、课表、成绩
    // 全部正常。病因：批次列表空了，老代码 `if (blocks.isEmpty()) return null` 把
    // 「没有开放批次」判成了「页面认不出来」。下面几条守住"空列表页也是合法页面"。

    @Test
    fun `identifiable page with no card parses as empty page instead of null`() {
        val empty = """
            <form id="ajaxForm" name="ajaxForm" action="/jwglxt/kjgl/kjbm_cxXskjbm.html" method="post">
            <input type="hidden" name="xnm" value="2026" id="xnm"/>
            <input type="hidden" name="xqm" value="3" id="xqm"/>
            <input type="hidden" name="xmlbfl" value="1001" id="xmlbfl"/>
            <h4 class="sl_tit_kbmxm clearfix">2026-2027学年1学期等级考试报名</h4>
            </form>
        """.trimIndent()
        val page = KaojiParser.parseIndexPage(empty)
        assertNotNull(page)
        assertTrue(page!!.projects.isEmpty())
        assertEquals("2026-2027学年1学期等级考试报名", page.title)
        assertEquals("1001", page.xmlbfl)
        assertEquals("2026", page.xnm)
    }

    @Test
    fun `empty page is recognised by form action alone`() {
        val page = KaojiParser.parseIndexPage("""<form action="/jwglxt/kjgl/kjbm_cxXskjbm.html"></form>""")
        assertNotNull(page)
        assertTrue(page!!.projects.isEmpty())
    }

    @Test
    fun `error page without any marker stays unrecognised`() {
        // 正方「无功能权限」这类通用错误页：三条特征一条都不命中 → 仍返回 null，
        // 由 KaojiClient 把页面上的 .error_title 文案透给用户。
        assertNull(KaojiParser.parseIndexPage("<html><body><div class=\"error_title\">无功能权限，</div></body></html>"))
    }

    @Test
    fun `looksLikeIndexPage accepts exactly the three markers`() {
        assertTrue(KaojiParser.looksLikeIndexPage("""<form action="/jwglxt/kjgl/kjbm_cxXskjbm.html">""", emptyMap()))
        assertTrue(KaojiParser.looksLikeIndexPage("""<h4 class="sl_tit_kbmxm">x</h4>""", emptyMap()))
        assertTrue(KaojiParser.looksLikeIndexPage("", mapOf("xmlbfl" to "1001")))
        assertFalse(KaojiParser.looksLikeIndexPage("<html>无功能权限</html>", emptyMap()))
    }

    @Test
    fun `pageNotice defaults to blank for pages built by the parser`() {
        // 解析器自己不填 pageNotice（由 KaojiClient 从 HTML 的 .nodata 取），
        // 默认空串让所有旧调用点无感。
        assertEquals("", KaojiParser.parseIndexPage(indexHtml)!!.pageNotice)
    }

    // ── 线上真实页面（2026-09-25 测试账号实测抓取，个人信息已脱敏）──────────
    //
    // 上面那份 indexHtml 是**手写的最小样本**，只保留了卡片部分，因此它一直绿着，
    // 却没覆盖真实页面的 jqGrid 骨架 —— 这正是「零批次被判成页面认不出」能溜过去的
    // 原因。下面这份是当日线上原文（仅替换姓名/学号/secret/内网地址），
    // 特征：页头在、xmlbfl 在、`div.row` 为空、`xmMap` 为空对象。

    private val liveEmptyPageHtml = """
        <form id="ajaxForm" name="ajaxForm" action="/jwglxt/kjgl/kjbm_cxXskjbm.html" method="post">
        <input type="hidden" name="xnm" value="2026" id="xnm"/>
        <input type="hidden" name="xqm" value="3" id="xqm"/>
        <input type="hidden" name="kj" value="1" id="kj"/>
        <input type="hidden" name="xmcjmxsfkj" value="1" id="xmcjmxsfkj"/>
        <input type="hidden" name="url" value="/jwglxt/kjgl/payment_cxAppPay.html" id="url"/>
        <input type="hidden" name="hdurl" value="" id="hdurl"/>
        <input type="hidden" name="secret" value="00000000000000000000000000000000" id="secret"/>
        <input type="hidden" name="data" value="&lt;?xml version='1.0' encoding='GBK'?&gt;&lt;billinfo&gt;&lt;version&gt;001-01&lt;/version&gt;&lt;orderinfono&gt;2025000000&lt;/orderinfono&gt;&lt;orderinfoname&gt;张三&lt;/orderinfoname&gt;&lt;billdtl&gt;&lt;feeitemid&gt;462&lt;/feeitemid&gt;&lt;amt&gt;100.00&lt;/amt&gt;&lt;/billdtl&gt;" id="data"/>
        <input type="hidden" name="djksbmjf" value="0" id="djksbmjf"/>
        <input type="hidden" name="yhm" value="2025000000" id="yhm"/>
        <input type="hidden" name="xxdm" value="10381" id="xxdm"/>
        <input type="hidden" name="sfxsxmfzcj" value="0" id="sfxsxmfzcj"/>
        <input type="hidden" name="xmlbfl" value="1001" id="xmlbfl"/>
        <input type="hidden" name="zsbh" value="0" id="zsbh"/>
        <h4 class="sl_tit_kbmxm clearfix">
            2026-2027学年1学期等级考试报名
            <a href="#" onclick="cxGqxm()" class="float_r">
                <button type="button" class="btn btn-default"><i class="bigger-100 glyphicon glyphicon-envelope"></i>本学期过期项目报名信息</button>
            </a>
        </h4>
        <div class="row">
        </div>
        <input type="hidden" name="sfxstable" id ="sfxstable">
        <table id="tabGrid"></table>
        <div id="pager"></div>
        <div id="fixWidth"></div>
        </form>
        <script type="text/javascript">
            var xmMap = {};
            var rsMap = {};
            var ybmrsMap = {};
            var xmtbMap = {};
        </script>
    """.trimIndent()

    @Test
    fun `live page with no open batch parses as empty page, not null`() {
        val page = KaojiParser.parseIndexPage(liveEmptyPageHtml)
        assertNotNull(page)
        assertTrue(page!!.projects.isEmpty())
        assertEquals("2026-2027学年1学期等级考试报名", page.title)
        assertEquals("2026", page.xnm)
        assertEquals("3", page.xqm)
        assertEquals("1001", page.xmlbfl)
    }

    @Test
    fun `live empty page is recognised with no site notice`() {
        // 站点对"当前没有开放批次"不给 .nodata / .error_title，页面是干净的。
        assertTrue(KaojiParser.looksLikeIndexPage(liveEmptyPageHtml, KaojiParser.hiddenFields(liveEmptyPageHtml)))
    }

    /** `?doType=query` 的**真实信封**（2026-09-25 实测，姓名/学号/手机号/证号已脱敏）。 */
    private val liveRegisteredBody = """
        {"currentPage":1,"currentResult":0,"entityOrField":false,"items":[
        {"bmfy":"35.00","bmpc":"1","bmsj":"2026-03-10 17:05:04","cjpx":"0","date":"二○二六年九月二十五日",
         "jssj":"2025-05-01 14:38:10","kssj":"2025-05-01 14:38:04","listnav":"false","localeKey":"zh_CN",
         "pageTotal":0,"pageable":true,"queryModel":{"currentPage":1,"pageSize":15,"sorts":[],"totalCount":0},
         "rangeable":true,"row_id":"1","rst":0,"sfkbk":"0","sfkt":"0","shjg":"3","sjhm":"13800000000",
         "spl_id":"KJGL_YY","totalResult":"3","userModel":{"monitor":false,"roleCount":0,"status":0,"usable":false},
         "wxsp":"0","xh_id":"2025000000","xmbmsz_id":"4CB2060CFCDFAD00E063A9B046D3E0DF","xmcj":"440.00",
         "xmdm":"XM31","xmlbdm":"XM01","xmlbmc":"全国大学英语四、六级考试","xmmc":"CET4","xnm":"2025",
         "xnmc":"2025-2026","xqm":"3","xqmmc":"1","xsCount":0,"xsbmqk_id":"4CB2AFB21231D0ABE063A9B046D3EC55",
         "yddg":"0","ydjc":"0","year":"2026","ywfxcj":"0","zjh":"340000000000000000","zjlx":"1",
         "zjlxmc":"居民身份证","zkzh":"340200000000000","zsbh":"250000000000000"}
        ],"limit":15,"offset":0,"pageNo":0,"pageSize":15,"showCount":10,"sorts":[],"totalCount":1}
    """.trimIndent()

    @Test
    fun `live registered envelope with full key set parses`() {
        val list = KaojiParser.parseRegistered(liveRegisteredBody)
        assertEquals(1, list.size)
        val r = list[0]
        assertEquals("4CB2AFB21231D0ABE063A9B046D3EC55", r.id)
        assertEquals("4CB2060CFCDFAD00E063A9B046D3E0DF", r.projectId)
        assertEquals("CET4", r.name)
        assertEquals("全国大学英语四、六级考试", r.category)
        assertEquals("35.00", r.fee)
        assertEquals("2026-03-10 17:05:04", r.registeredAt)
        assertEquals("340200000000000", r.ticketNo)
        assertEquals("250000000000000", r.certNo)
        assertEquals("440.00", r.score)
        assertEquals("2025-2026 第1学期", r.term)
        // 真实信封里还夹着 queryModel / userModel 这类嵌套对象与大量无关字段，
        // 解析器只取白名单键，其余一律忽略，不会把它们当字符串塞进模型。
        assertEquals("13800000000", r.phone)
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

    // ── 条数上限：服务端忽略一切分页参数，界面必须自己说清楚 ────────────────
    //
    // 实测（2026-09-25）：rows/page/limit/offset/pageSize/pageSizeInt/page.count
    // 与 jqGrid 全套参数（_search/nd/sidx/sord），GET/POST 都试过，一律被忽略；
    // 一次固定只回 15 条。所以「服务端自报 totalCount」是唯一能判断是否被截断的依据。

    @Test
    fun `envelope exposes totalCount beyond the returned rows`() {
        val body = """{"items":[{"xsbmqk_id":"R1"},{"xsbmqk_id":"R2"}],"totalCount":37,"pageSize":15}"""
        val envelope = KaojiParser.parseEnvelope(body)
        assertEquals(2, envelope.rows.size)
        assertEquals(37, envelope.totalCount)
    }

    @Test
    fun `envelope falls back to rows and total naming, then to the row count`() {
        assertEquals(
            9,
            KaojiParser.parseEnvelope("""{"rows":[{"xsbmqk_id":"R1"}],"total":9}""").totalCount
        )
        // 连总数都没有时按实际条数算：宁可当作"没截断"，也别虚报一个 0。
        assertEquals(
            1,
            KaojiParser.parseEnvelope("""{"items":[{"xsbmqk_id":"R1"}]}""").totalCount
        )
        assertEquals(0, KaojiParser.parseEnvelope("not json").totalCount)
    }

    @Test
    fun `registered page reports truncation only when the server says so`() {
        val truncated = KaojiPage(
            title = "t", xnm = "2026", xqm = "3", xmlbfl = "1001",
            projects = emptyList(),
            registered = listOf(
                KaojiRegistered("R1", "P1", "c", "n", "", "", "", "", "", "", "", "", "")
            ),
            registeredTotal = 16,
        )
        assertTrue(truncated.registeredTruncated)

        // totalCount 缺失（退化成实际条数）时不能报截断 —— 否则会白吓用户一跳
        val complete = truncated.copy(registeredTotal = 1)
        assertFalse(complete.registeredTruncated)
    }

    /** 「本学期过期项目」的**真实信封**（2026-09-25 实测；结构与已报名同族但字段集不同）。 */
    private val liveExpiredBody = """
        {"currentPage":1,"currentResult":0,"entityOrField":false,"items":[{"bmfy":"25.00","bmpc":"1",
         "cjpx":"0","date":"二○二六年九月二十五日","dateDigit":"2026年9月25日","dateDigitSeparator":"2026-9-25",
         "day":"25","jgpxzd":"1","jssj":"2026-09-24 23:00:00","kfyddg":"0","kfydjc":"0","kssj":"2026-09-07 08:00:00",
         "listnav":"false","localeKey":"zh_CN","month":"9","pageTotal":0,"pageable":true,
         "queryModel":{"currentPage":1,"currentResult":0,"entityOrField":false,"limit":15,"offset":0,"pageNo":0,
          "pageSize":15,"showCount":10,"sorts":[],"totalCount":0,"totalPage":0,"totalResult":0},
         "rangeable":true,"row_id":"1","rst":0,"rszgxz":"5000","sfkbk":"0","sfkt":"0","totalResult":"1",
         "userModel":{"monitor":false,"roleCount":0,"roleKeys":"","roleValues":"","status":0,"usable":false},
         "wxsp":"1","xmbmsz_id":"5AA95650462D105EE063A9B046D3F589","xmdm":"230","xmlbdm":"XM03",
         "xmlbmc":"普通话测试","xmmc":"普通话测试","xnm":"2026","xnmc":"2026-2027","xqm":"3","xqmmc":"1",
         "xsCount":0,"year":"2026","ywfxcj":"0"}],
         "limit":15,"offset":0,"pageNo":0,"pageSize":15,"showCount":10,"sorts":[],"totalCount":1,
         "totalPage":1,"totalResult":1}
    """.trimIndent()

    @Test
    fun `live expired envelope parses into expired projects`() {
        val list = KaojiParser.parseExpiredProjects(liveExpiredBody)
        assertEquals(1, list.size)
        val p = list[0]
        assertEquals("5AA95650462D105EE063A9B046D3F589", p.projectId)
        assertEquals("普通话测试", p.name)
        assertEquals("普通话测试", p.category)
        assertEquals("25.00", p.fee)
        assertEquals("2026-09-07 08:00:00", p.beginTime)
        assertEquals("2026-09-24 23:00:00", p.endTime)
        assertEquals("2026-2027 第1学期", p.term)
        assertEquals(1, KaojiParser.parseEnvelope(liveExpiredBody).totalCount)
    }

    @Test
    fun `expired fields are not silently read as registered records`() {
        // 过期项目**没有** xsbmqk_id / zkzh / zsbh / xmcj 这些字段。用它去喂
        // parseRegistered 会得到一条"看起来像、其实全是空"的记录 —— 这正是当初
        // 决定给过期项目单独建模型的原因，这条测试把这个事实钉住。
        val asRegistered = KaojiParser.parseRegistered(liveExpiredBody)
        assertEquals(1, asRegistered.size)
        assertEquals("", asRegistered[0].id)
        assertEquals("", asRegistered[0].ticketNo)
        assertEquals("", asRegistered[0].score)
        assertEquals("普通话测试", asRegistered[0].name)
    }

    @Test
    fun `parseExpiredProjects tolerates junk`() {
        assertTrue(KaojiParser.parseExpiredProjects("not json").isEmpty())
        assertTrue(KaojiParser.parseExpiredProjects("""{"items":[]}""").isEmpty())
    }

    // ── 缴费状态 / 审核状态：一直在信封里，只是界面没显示 ────────────────────
    //
    // 2026-09-25 实测：已报名列表**每一行都带 shjg（审核状态）**，涉及缴费的行还带
    // sfqr（缴费状态）。站点自己的格子就是从这两个字段渲染的
    // （xskjbm.js: colModel 里 name:'sfqr'，formatter:'select'，
    //  N2510 的 i18n `jfzt_all = "0:未缴;1:已缴"`；审核用 `shzt_all = "1:待审核;…;5:不通过"`）。
    // 所以**不需要**为展示多打一次 kjbm_cxXskjbmjfzt.html。

    @Test
    fun `registered record reads payment and audit status from the envelope`() {
        val body = """{"items":[{"xsbmqk_id":"R1","xmmc":"CET4","shjg":"3","sfqr":"1",
                       "sfzfzzt":"0","sfkbk":"0","sfkt":"1"}],"totalCount":1}"""
        val r = KaojiParser.parseRegistered(body)[0]
        assertEquals("1", r.paymentStatus)
        assertEquals("3", r.auditStatus)
        assertTrue(r.paid)
    }

    @Test
    fun `payment status uses the site's own wording, blank when not applicable`() {
        // 站点 i18n 原文就是「未缴 / 已缴」，不另造「未缴费 / 已缴费」
        assertEquals("未缴", registered(payment = "0").paymentText)
        assertEquals("已缴", registered(payment = "1").paymentText)
        // 空串 = 这一条不涉及缴费（站点同样留空），不能显示成「未缴」误导人
        assertEquals("", registered(payment = "").paymentText)
        assertEquals("", registered(payment = "-1").paymentText)
        assertFalse(registered(payment = "0").paid)
        assertFalse(registered(payment = "").paid)
    }

    @Test
    fun `audit status only surfaces abnormal values`() {
        // 「已通过」是常态：逐条印「审核状态：已通过」是噪音，所以映射成空串
        assertEquals("", registered(audit = "3").auditText)
        assertEquals("", registered(audit = "").auditText)
        assertEquals("待审核", registered(audit = "1").auditText)
        assertEquals("审核中", registered(audit = "2").auditText)
        assertEquals("退回", registered(audit = "4").auditText)
        assertEquals("不通过", registered(audit = "5").auditText)
    }

    /** 真实信封的三行形状（2026-09-25 实测；姓名/学号/手机号已脱敏）。 */
    private val liveRegisteredRowsWithStatus = """
        {"items":[
         {"xsbmqk_id":"A1","xmmc":"CET4","shjg":"3","sfkbk":"0","sfkt":"0"},
         {"xsbmqk_id":"A2","xmmc":"全国大学生英语竞赛C级","shjg":"3","sfqr":"0","sfkbk":"0","sfkt":"1"},
         {"xsbmqk_id":"A3","xmmc":"一级计算机应用基础及WPS Office","shjg":"3","sfqr":"0","sfkbk":"0","sfkt":"1"}
        ],"totalCount":3,"pageSize":15}
    """.trimIndent()

    @Test
    fun `live rows keep the distinction between not-applicable and unpaid`() {
        val rows = KaojiParser.parseRegistered(liveRegisteredRowsWithStatus)
        assertEquals(3, rows.size)
        // CET4 那行没有 sfqr → 不涉及缴费 → 不显示缴费状态行
        assertEquals("", rows[0].paymentText)
        assertFalse(rows[0].paid)
        // 另两行 sfqr=0 → 未缴，是要去办的事
        assertEquals("未缴", rows[1].paymentText)
        assertEquals("未缴", rows[2].paymentText)
        // 三行 shjg 都是 3（已通过）→ 都不该冒出「审核状态」行
        assertTrue(rows.all { it.auditText.isEmpty() })
    }

    private fun registered(payment: String = "", audit: String = "") = KaojiRegistered(
        id = "R", projectId = "P", category = "c", name = "n", fee = "", registeredAt = "",
        examBegin = "", examEnd = "", ticketNo = "", certNo = "", score = "", term = "",
        phone = "", paymentStatus = payment, auditStatus = audit,
    )
}
