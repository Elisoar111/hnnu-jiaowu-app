package com.hnnujw.course.examreg

import org.json.JSONObject

/**
 * 教务系统「考级项目报名」（正方 V9，gnmkdm=N2510）的数据模型与解析。
 *
 * ## 协议来源（2026-09-22 线上实测，非猜测）
 *
 * 入口页 `GET /jwglxt/kjgl/kjbm_cxXskjbm.html?xmlbfl=1001&gnmkdm=N2510`
 * 是**服务端直出**的项目卡片列表（不是异步 jqGrid），每张卡片一个
 * `div.xm_block`，批次主键 `xmbmsz_id` 写在 `xmMap["index_N"]` 与
 * `bmsm_<id>` 隐藏域里。已报名记录走同路径 `?doType=query&pkey=&xmlbfl=`
 * 的 GET，返回 JSON 信封（`items` 数组）。
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
    /** 考试开始（kssj）。 */
    val examBegin: String,
    /** 考试结束（jssj）。 */
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
) {
    /** 已缴费或正在缴费（此时不允许退报，口径照站点 JS）。 */
    val paidOrPaying: Boolean
        get() = false
}

/** 入口页解析结果。 */
data class KaojiPage(
    /** 页头标题，如「2026-2027学年1学期等级考试报名」。 */
    val title: String,
    val xnm: String,
    val xqm: String,
    val xmlbfl: String,
    val projects: List<KaojiProject>,
    val registered: List<KaojiRegistered>,
)

object KaojiParser {

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

    /** 解析入口页（服务端直出的 HTML）；识别不出任何项目时返回 null。 */
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

        // 卡片按 xm_block 切块
        val blocks = Regex("""<div[^>]*class="[^"]*\bxm_block\b[^"]*"[^>]*>""").findAll(html).toList()
        if (blocks.isEmpty()) return null

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

    /** 解析已报名列表 JSON（`?doType=query` 信封：items 或 rows）。 */
    fun parseRegistered(body: String): List<KaojiRegistered> {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return emptyList()
        val arr = root.optJSONArray("items") ?: root.optJSONArray("rows") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            KaojiRegistered(
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
                term = buildString {
                    append(o.safe("xnmc"))
                    val xqmName = o.safe("xqmmc")
                    if (xqmName.isNotBlank()) append(" 第").append(xqmName).append("学期")
                },
                phone = o.safe("sjhm"),
            )
        }
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
