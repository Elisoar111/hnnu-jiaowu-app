package com.hnnujw.course.examreg

import org.json.JSONObject

/**
 * 教务系统「考级项目报名」（正方 V9，gnmkdm=N2510）的数据模型与解析。
 *
 * ## 协议来源（2026-09-22 / 2026-09-25 线上实测，非猜测）
 *
 * 入口页 `GET /jwglxt/kjgl/kjbm_cxXskjbm.html?xmlbfl=1001&gnmkdm=N2510`
 * 是**服务端直出**的项目卡片列表（不是异步 jqGrid），每张卡片一个
 * `div.xm_block`，批次主键 `xmbmsz_id` 写在 `xmMap["index_N"]` 与
 * `bmsm_<id>` 隐藏域里。已报名记录走同路径 `?doType=query&pkey=&xmlbfl=`
 * 的 GET，返回 JSON 信封（`items` 数组）。
 *
 * ⚠️ `xmlbfl`（类别）**不硬编码**：它登记在教务功能菜单里，见 [KaojiCategory]。
 *
 * 同目录下还有两个同族接口：
 * - `kjgl/kjbm_cxGqxm.html?doType=query&pkey=&xmlbfl=` —— 本学期**过期**项目，
 *   信封结构与已报名一致但字段集不同，见 [KaojiExpiredProject]。
 * - `kjgl/kjbm_cxXskjbmjfzt.html?xsbmqk_id=` —— 单条报名记录的**缴费状态**。
 *
 * 报名动作链路（客户端**不主动**调用，仅登记真实用户的显式提交）：
 * ```
 * POST /kjgl/kjbm_cxJcXskjbm.html      {xnm,xqm,xmbmsz_id}  → "0"=可报
 * GET  /kjgl/kjbm_zjXskjbm.html?xmbmsz_id=                  → 报名表单页
 * POST /kjgl/kjbm_zjBcXskjbm.html      (multipart 表单)      → 文本含「成功」
 * POST /kjgl/kjbm_scXskjbm.html?xsbmqk_id=                  → 退报（文本含「成功」）
 * ```
 */

/** 一个可报名的考级项目（批次）。 */
data class KaojiProject(
    /** 批次主键 xmbmsz_id。 */
    val id: String,
    /** 站点原文标题，如「第1批次，普通话测试」。 */
    val title: String,
    /** 项目类别代码（xmlbdm，如 XM03）。 */
    val categoryId: String,
    /** 批次号（bmpc）。 */
    val batch: String,
    /** 报名开始时间（站点原文）。 */
    val beginTime: String,
    /** 报名截止时间（站点原文）。 */
    val endTime: String,
    /** 剩余天数文案（站点原文，如「还剩余2天」），可为空。 */
    val remainDaysText: String,
    /** 剩余名额文案（站点原文，如「还剩余人数2886人」），可为空。 */
    val remainSeatsText: String,
    /** 费用文案（站点原文，如「费用25.00元整」），可为空。 */
    val feeText: String,
    /** 报名说明（bmsm_<id> 隐藏域），可为空。 */
    val notice: String,
    /** 站点在页脚渲染了「报名」按钮才可报名。 */
    val canRegister: Boolean,
)

/** 一条已报名记录（`doType=query` 的 JSON 行）。 */
data class KaojiRegistered(
    /** 报名情况主键 xsbmqk_id。 */
    val id: String,
    val projectId: String,
    /** 类别名（xmlbmc，如「全国大学英语四、六级考试」）。 */
    val category: String,
    /** 项目名（xmmc，如 CET4）。 */
    val name: String,
    /** 报名费（bmfy）。 */
    val fee: String,
    /** 报名时间（bmsj）。 */
    val registeredAt: String,
    /** 报名起止窗口（kssj/jssj）。⚠️ 实测这两个字段是**报名**窗口不是考试时间，界面不展示。 */
    val examBegin: String,
    /** 报名起止窗口的截止段（jssj），口径同 [examBegin]。 */
    val examEnd: String,
    /** 准考证号（zkzh），未生成时为空。 */
    val ticketNo: String,
    /** 证书编号（zsbh），未发放时为空。 */
    val certNo: String,
    /** 成绩（xmcj），未出分时为空。 */
    val score: String,
    /** 学年学期文案（xnmc + xqmmc）。 */
    val term: String,
    /** 报名手机号（sjhm）。 */
    val phone: String,
    /**
     * 缴费状态（sfqr）的**原始值**。
     *
     * 站点自己的口径在 N2510 的 i18n 里：`jfzt_all = "0:未缴;1:已缴"`，
     * 已报名格子的 `sfqr` 列就是按它渲染的（`colModel` 里 `formatter:'select'`）。
     * **空串 = 这一条不涉及缴费**（站点同样留空），不是"未缴"。
     */
    val paymentStatus: String = "",
    /**
     * 审核状态（shjg）的**原始值**。
     *
     * 站点口径（`jwglxt-common` 的 `shzt_all = "1:待审核;2:审核中;3:已通过;4:退回;5:不通过"`）。
     * 实测这个字段**一直都在列表信封里**，而且它是有后果的 ——
     * `xskjbm.js` 的缴费流程写着「`shjg != '3'` ⇒ 该记录未审核通过,不允许缴费!」。
     */
    val auditStatus: String = "",
) {
    /**
     * 缴费状态文案，用**站点自己的字**（未缴 / 已缴），不另造一套说法。
     * 空串 = 不涉及缴费，界面不显示这一行。
     */
    val paymentText: String
        get() = when (paymentStatus) {
            "0" -> "未缴"
            "1" -> "已缴"
            else -> ""
        }

    /**
     * 审核状态文案。**只在异常时才非空** —— 「已通过」是常态，
     * 每行都印一句「审核状态：已通过」是纯噪音；待审核/退回/不通过才需要看见。
     */
    val auditText: String
        get() = when (auditStatus) {
            "1" -> "待审核"
            "2" -> "审核中"
            "4" -> "退回"
            "5" -> "不通过"
            else -> ""
        }

    /**
     * 是否已缴费。服务端口径（`xskjbm.js` 退报分支）：
     * `data.sfqr == '1' || data.sfzfzzt == '1'` ⇒ 「该项目已经缴费或正在缴费，无法退报！」。
     * 列表信封里只有 `sfqr`，所以这里只认它；`sfzfzzt` 由退报请求内的即时查询兜底。
     */
    val paid: Boolean get() = paymentStatus == "1"
}

/**
 * 一个考级类别（`xmlbfl`）。
 *
 * 🔴 类别代码**不硬编码**：它本来就登记在教务功能菜单里
 * （`clickMenu('N2510','/kjgl/kjbm_cxXskjbm.html?xmlbfl=1001','考级项目报名','null')`），
 * 由 `AcademicMenu` 取出来即可。
 *
 * 早先版本把 `xmlbfl` 写死成 `1001`，于是服务端明明还有
 * `1003`「参加大类分流报名」、`1004`「推免报名」两个合法页面（2026-09-25 实测），
 * 用户却永远看不到 —— 这两件事对本科生来说是重量级通知。
 */
data class KaojiCategory(
    val xmlbfl: String,
    /** 站点在菜单里写的标题，如「考级项目报名」。 */
    val title: String,
)

/** 入口页解析结果。 */
data class KaojiPage(
    /** 页头标题，如「2026-2027学年1学期等级考试报名」。 */
    val title: String,
    val xnm: String,
    val xqm: String,
    val xmlbfl: String,
    val projects: List<KaojiProject>,
    val registered: List<KaojiRegistered>,
    /**
     * 站点自己在页面上写的提示语（`.nodata` / `.error_title`），例如
     * 「对不起，当前不属于考级报名阶段」或「无功能权限」。空串 = 站点没给提示。
     * 界面在 projects 为空时优先展示它 —— 比我们猜的文案准确得多。
     */
    val pageNotice: String = "",
    /**
     * 已报名接口自报的总条数（信封里的 `totalCount`）。
     *
     * 🔴 实测该接口**完全忽略所有分页参数**（`rows`/`page`/`limit`/`offset`/
     * `pageSize`/`showCount` + jqGrid 全参，GET/POST 都试过），一次固定只回
     * [REGISTERED_PAGE_LIMIT] 条。所以 `totalCount > registered.size` 时列表是**被截断的**，
     * 界面必须说出来，否则用户会以为记录丢了。
     */
    val registeredTotal: Int = 0,
) {
    /** 已报名列表是否被服务端截断。 */
    val registeredTruncated: Boolean get() = registeredTotal > registered.size
}

/**
 * 「本学期过期项目报名信息」的一条记录（`kjgl/kjbm_cxGqxm.html?doType=query`）。
 *
 * ⚠️ 这个接口的信封**和已报名接口不同**：没有 `xsbmqk_id` / `zkzh` / `zsbh` /
 * `xmcj` / `sjhm`，只描述"这个项目什么时候截止、多少钱"。
 * 所以单独建模型，不硬塞进 [KaojiRegistered]（那样会得到一堆永远为空、
 * 语义还不一样的字段）。
 */
data class KaojiExpiredProject(
    /** 批次主键 xmbmsz_id。 */
    val projectId: String,
    /** 类别名 xmlbmc。 */
    val category: String,
    /** 项目名 xmmc。 */
    val name: String,
    /** 报名费 bmfy。 */
    val fee: String,
    /** 报名开始时间 kssj。 */
    val beginTime: String,
    /** 报名截止时间 jssj。 */
    val endTime: String,
    /** 学年学期文案（xnmc + xqmmc）。 */
    val term: String,
)

object KaojiParser {

    /**
     * 已报名接口一次**固定**返回的条数。
     *
     * ⚠️ 这是服务端行为、不是我们传的参数 —— 实测 `rows`/`page`/`limit`/`offset`/
     * `pageSize`/`pageSizeInt`/`page.count` 与 jqGrid 全套参数（`_search`/`nd`/
     * `sidx`/`sord`）、GET/POST 都试过，一律被忽略。界面用它来解释"为什么只有
     * 这么几条"，**不要**拿它去构造分页请求（那是白费一次往返）。
     */
    const val REGISTERED_PAGE_LIMIT = 15

    /** 抽取 input[type=hidden] 的 name→value（两种属性顺序都认）。 */
    fun hiddenFields(html: String): Map<String, String> {
        val out = mutableMapOf<String, String>()
        Regex("""<input\b[^>]*>""").findAll(html).forEach { m ->
            val tag = m.value
            val name = Regex("""name="([^"]*?)"""").find(tag)?.groupValues?.get(1) ?: return@forEach
            val value = Regex("""value="([^"]*?)"""").find(tag)?.groupValues?.get(1).orEmpty()
            if (name.isNotBlank()) out[name] = value
        }
        return out
    }

    /** jqGrid 信封：`rows` = items/rows 数组，`totalCount` = 服务端自报总数。 */
    data class Envelope(val rows: List<JSONObject>, val totalCount: Int)

    /**
     * 解析 jqGrid JSON 信封。
     *
     * 两种命名都认：新版回 `{"items":[…],"totalCount":N}`，部分构建回
     * `{"rows":[…],"total":N}`；`totalCount` 缺失时退到 `totalResult`，
     * 再缺失就按实际条数算（此时无法判断是否被截断，宁可当作没截断）。
     */
    fun parseEnvelope(body: String): Envelope {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return Envelope(emptyList(), 0)
        val array = root.optJSONArray("items") ?: root.optJSONArray("rows")
        val rows = array?.let { list -> (0 until list.length()).mapNotNull { list.optJSONObject(it) } }.orEmpty()
        val total = listOf("totalCount", "totalResult", "total")
            .firstNotNullOfOrNull { root.optString(it).toIntOrNull() }
            ?: rows.size
        return Envelope(rows, total)
    }

    /** 解析已报名列表 JSON（`?doType=query` 信封：items 或 rows）。 */
    fun parseRegistered(body: String): List<KaojiRegistered> =
        parseEnvelope(body).rows.map(::toRegistered)

    /** 解析「本学期过期项目」JSON（`kjbm_cxGqxm.html?doType=query`，同样是 jqGrid 信封）。 */
    fun parseExpiredProjects(body: String): List<KaojiExpiredProject> =
        parseEnvelope(body).rows.map(::toExpiredProject)

    /** 单行 JSON → [KaojiRegistered]。公开给需要自己遍历信封（取 totalCount）的调用方。 */
    fun toRegistered(o: JSONObject) = KaojiRegistered(
        id = o.safe("xsbmqk_id"),
        projectId = o.safe("xmbmsz_id"),
        category = o.safe("xmlbmc"),
        name = o.safe("xmmc"),
        fee = o.safe("bmfy"),
        registeredAt = o.safe("bmsj"),
        examBegin = o.safe("kssj"),
        examEnd = o.safe("jssj"),
        ticketNo = o.safe("zkzh"),
        certNo = o.safe("zsbh"),
        score = o.safe("xmcj"),
        term = o.safe("xnmc").termWith(o.safe("xqmmc")),
        phone = o.safe("sjhm"),
        // 缴费 / 审核状态**本来就在这个信封里**（站点自己的格子也是从这里取的），
        // 不需要为展示多打一次 kjbm_cxXskjbmjfzt.html。
        paymentStatus = o.safe("sfqr"),
        auditStatus = o.safe("shjg"),
    )

    /** 单行 JSON → [KaojiExpiredProject]。 */
    fun toExpiredProject(o: JSONObject) = KaojiExpiredProject(
        projectId = o.safe("xmbmsz_id"),
        category = o.safe("xmlbmc"),
        name = o.safe("xmmc"),
        fee = o.safe("bmfy"),
        beginTime = o.safe("kssj"),
        endTime = o.safe("jssj"),
        term = o.safe("xnmc").termWith(o.safe("xqmmc")),
    )

    /** 「2025-2026」+「1」→「2025-2026 第1学期」；学期为空时只留学年。 */
    private fun String.termWith(semester: String): String = buildString {
        append(this@termWith)
        if (semester.isNotBlank()) append(" 第").append(semester).append("学期")
    }

    /**
     * 认页依据（命中任一即认定是考级入口页）：入口表单 action 含 `kjbm_cxXskjbm`、
     * 页头标题用了 `sl_tit_kbmxm`、或页面带 `xmlbfl` 隐藏域。
     *
     * 正方在「功能已关闭 / 无权限」时返回的是通用错误页（`.error_title`，如「无功能权限」），
     * 三条一条都不命中，于是仍走 [parseIndexPage] 的 null 分支 —— 由调用方把站点
     * 提示语透出去，比我们自造的文案准确。
     */
    fun looksLikeIndexPage(html: String, hidden: Map<String, String>): Boolean =
        html.contains("kjbm_cxXskjbm") ||
            html.contains("sl_tit_kbmxm") ||
            hidden.containsKey("xmlbfl")

    /**
     * 解析入口页（服务端直出的 HTML）。
     *
     * 只有**连「这是不是考级页」都认不出来**时才返回 null。
     * 页面可识别、但一张卡片都没有，是**合法状态**（当前没有开放批次），
     * 此时返回 projects 为空的 [KaojiPage]，不能当失败。
     *
     * ⚠️ 早期版本写的是 `if (blocks.isEmpty()) return null`，于是「批次都已结束 /
     * 功能被关闭」被界面显示成「读取失败」。2026-09-24 线上实测踩到：用户上一版还能
     * 读到项目，当天晚上就只剩「读取失败」，而教务会话、课表、成绩全都正常 ——
     * 病因就是批次列表空了。**别再退回那个写法。**
     */
    fun parseIndexPage(html: String): KaojiPage? {
        val title = Regex("""class="sl_tit_kbmxm[^"]*"[^>]*>\s*([^<\r\n]+)""")
            .find(html)?.groupValues?.get(1)?.trim().orEmpty()
        val top = hiddenFields(html)
        val xnm = top["xnm"].orEmpty()
        val xqm = top["xqm"].orEmpty()
        val xmlbfl = top["xmlbfl"].orEmpty()

        // xmMap["index_N"] = '<xmbmsz_id>'：卡片顺序 → 批次主键
        val idByIndex = Regex("""xmMap\["index_(\d+)"\]\s*=\s*'([0-9A-Fa-f]+)'""")
            .findAll(html)
            .associate { it.groupValues[1].toInt() to it.groupValues[2] }

        // 卡片按 xm_block 切块；一块都没有时，靠页面特征决定是"空列表"还是"不是这页"
        val blocks = Regex("""<div[^>]*class="[^"]*\bxm_block\b[^"]*"[^>]*>""").findAll(html).toList()
        if (blocks.isEmpty() && !looksLikeIndexPage(html, top)) return null

        val projects = blocks.mapIndexed { index, match ->
            val start = match.range.first
            val end = blocks.getOrNull(index + 1)?.range?.first ?: html.length
            val block = html.substring(start, end)
            val titleText = Regex("""<h4[^>]*>([^<]+)</h4>""")
                .findAll(block)
                .firstOrNull { !it.groupValues[1].contains("学年") }
                ?.groupValues?.get(1)?.trim().orEmpty()
            val noticeId = Regex("""id="bmsm_([0-9A-Fa-f]+)"""").find(block)?.groupValues?.get(1)
                ?: idByIndex[index].orEmpty()
            // bmsm 隐藏域的 value 挂在 name="" 的 input 上，按 id 定位后取 value
            val notice = noticeId.takeIf { it.isNotBlank() }
                ?.let { id -> Regex("""<input[^>]*id="bmsm_${Regex.escape(id)}"[^>]*>""").find(block)?.value }
                ?.let { Regex("""value="([^"]*?)"""").find(it)?.groupValues?.get(1) }
                .orEmpty()
            val remainDays = Regex("""还剩余?\s*(\d+)\s*天""").find(block)?.let { "还剩余${it.groupValues[1]}天" }.orEmpty()
            val remainSeats = Regex("""还剩余人数\s*(\d+)\s*人""").find(block)?.let { "还剩余人数${it.groupValues[1]}人" }.orEmpty()
            val fee = Regex("""费用\s*([\d.]+)\s*元""").find(block)?.let { "费用${it.groupValues[1]}元" }.orEmpty()
            val time = Regex(
                """开始时间[^：:]*[：:]\s*([\d\-/:.\s]+?)\s*截止时间[^：:]*[：:]\s*([\d\-/:.\s]+)"""
            ).find(block)
            val fields = hiddenFields(block)
            KaojiProject(
                id = idByIndex[index].orEmpty().ifBlank { noticeId },
                title = titleText,
                categoryId = fields["xmlbdm"].orEmpty(),
                batch = fields["bmpc"].orEmpty(),
                beginTime = time?.groupValues?.get(1)?.trim().orEmpty(),
                endTime = time?.groupValues?.get(2)?.trim().orEmpty(),
                remainDaysText = remainDays,
                remainSeatsText = remainSeats,
                feeText = fee,
                notice = notice,
                // 站点只有渲染了「报名」按钮的批次才可报名（已报满/已报名的批次没有这个按钮）
                canRegister = Regex("""class="[^"]*\bbtn_xmbm\b[^"]*"""").containsMatchIn(block),
            )
        }
        return KaojiPage(title, xnm, xqm, xmlbfl, projects, emptyList())
    }

    /** 报名表单页（kjbm_zjXskjbm.html）解析：隐藏域 + 可编辑手机号。 */
    data class FormSnapshot(val fields: Map<String, String>, val phone: String, val oldPhone: String)

    fun parseFormPage(html: String): FormSnapshot? {
        if (!html.contains("chlidForm")) return null
        val fields = hiddenFields(html)
        val phone = Regex("""<input[^>]*name="sjhm"[^>]*value="([^"]*?)"""").find(html)?.groupValues?.get(1).orEmpty()
            .ifBlank { Regex("""<input[^>]*value="([^"]*?)"[^>]*name="sjhm"""").find(html)?.groupValues?.get(1).orEmpty() }
        return FormSnapshot(fields, phone, fields["oldSjhm"].orEmpty())
    }

    private fun JSONObject.safe(key: String): String {
        val v = opt(key) ?: return ""
        return if (v == JSONObject.NULL) "" else v.toString()
    }
}
