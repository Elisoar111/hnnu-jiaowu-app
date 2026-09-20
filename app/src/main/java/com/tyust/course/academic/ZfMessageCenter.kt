package com.tyust.course.academic

import com.tyust.course.model.SchoolConfig
import org.json.JSONObject

/**
 * 正方（jwglxt V9）消息中心：在已登录会话上抓取消息列表 / 详情。
 *
 * ## 真实协议（实测自淮南师范学院备用入口，非猜测）
 *
 * 消息中心页 `xtgl/index_cxDbsy.html` 用的是 **jqGrid**：HTML 里的
 * `<table id="tabGrid1">` 是空的，数据由 JS 在页面加载后异步取回并填充。
 * 所以**解析静态 HTML 一条也拿不到**——这正是"消息中心一直空白"的根因。
 *
 * 真正的数据接口是同路径 + `doType=query` 的 **POST**：
 *
 * ```
 * POST {base}/xtgl/index_cxDbsy.html?doType=query
 * flag=1&sfyy=1|2&_search=false&nd=<ms>
 * queryModel.showCount=50&queryModel.currentPage=1
 * queryModel.sortName=&queryModel.sortOrder=asc&time=1
 * ```
 *
 * - `flag`：1 = 当前角色，2 = 其他角色。**只取当前角色**（用户明确要求）。
 * - `sfyy`：1 = 待阅，2 = 已阅。两个桶都取、合并，已阅与未阅因此都能显示。
 * - 响应是 JSON：`{ "items": [ … ], "totalResult": N, … }`
 *   （部分构建用 jqGrid 原生的 `rows` / `total` 命名，两种都认。）
 *
 * 行内字段：`xxbt` 标题、`xxnr`/`xxbtjc` 正文、`cjsj` 时间、
 * `sfyd` 是否已读、`ljdz` 详情链接（**常常为空**）、`zjxx` 消息主键。
 *
 * ## 已读状态以"桶"为准
 *
 * `sfyd` 有时缺失、有时被别的字段语义污染；而我们本来就分别向 `sfyy=1`
 * 和 `sfyy=2` 各要了一次，**桶本身就是最可靠的已读信号**。所以行内有
 * 可识别的 `sfyd` 就听它的，没有就按桶归属判定——即使 `sfyd` 全错，
 * 两个桶合起来也不会漏消息，最多是个别行的"已读/未读"标反。
 */
object ZfMessageCenter {

    /** 用户确认的消息中心入口（正方「待办事宜」页）。 */
    private const val DBSY_PATH = "xtgl/index_cxDbsy.html"

    private const val INDEX_PATH = "xtgl/index_initMenu.html"

    /** 当前角色。用户明确要求"不要其它角色"，所以 flag=2 不再请求。 */
    private const val FLAG_CURRENT_ROLE = 1

    /** 待阅。 */
    private const val SFYY_UNREAD = 1

    /** 已阅。 */
    private const val SFYY_READ = 2

    /** 每页条数。班级/个人待办量级很小，50 一页通常一次到底。 */
    private const val PAGE_SIZE = 50

    /** 翻页保险丝：50 × 10 = 500 条，远超个人消息中心的实际规模。 */
    private const val MAX_PAGES = 10

    suspend fun fetchMessages(transport: AcademicHttpTransport, school: SchoolConfig): MessageCenterResult {
        val webFallback = transport.appUrl("$DBSY_PATH?flag=$FLAG_CURRENT_ROLE")

        val unread = loadBucket(transport, SFYY_UNREAD)
        val read = loadBucket(transport, SFYY_READ)

        // 会话失效优先：教务把未登录的 XHR 重定向成了登录页，
        // 这时既解析不到 items，也不该让用户看到"结构变了"。
        if (unread is Bucket.NeedLogin || read is Bucket.NeedLogin) {
            return MessageCenterResult.NeedLogin("登录已失效，请重新登录后查看消息")
        }

        val items = when {
            unread is Bucket.Items && read is Bucket.Items -> unread.list + read.list
            unread is Bucket.Items -> unread.list
            read is Bucket.Items -> read.list
            else -> null
        }
        if (items == null) {
            return MessageCenterResult.Failure("已尝试按正方消息接口读取，但返回的内容无法识别", webFallback)
        }
        // 未读在前、已读在后；组内按时间**倒序**（最新消息排最上方）。
        // 去重键用消息主键：已阅/未阅两个桶在状态刚变时可能同时命中同一条。
        //
        // 时间字段是 `yyyy-MM-dd HH:mm:ss` 这类可字典序比较的格式，直接按字符串
        // 倒序即可；时间缺失或格式异常的行排在组内末尾（用空串触发，稳定排序保证
        // 它们相对顺序不变），而不是污染整个列表。
        val merged = items.distinctBy { it.id }
            .sortedWith(
                compareBy<AcademicMessage> { if (it.read) 1 else 0 }
                    .thenByDescending { sortableTime(it.sendTime) }
            )
        return MessageCenterResult.Success(merged, webFallback)
    }

    /**
     * 排序用的时间键：把 `2024-09-01 08:30:00` / `2024/09/01` / `2024年9月1日`
     * 统一成零填充的 `20240901083000`，使字典序等于时间序。
     *
     * 识别不出来就返回空串——空串在 `thenByDescending` 下排到末尾，
     * 不会因为一条脏数据把整份列表的顺序打乱。
     */
    private fun sortableTime(raw: String): String {
        if (raw.isBlank()) return ""
        val digits = Regex("(\\d+)").findAll(raw).map { it.value }.toList()
        if (digits.size < 3) return ""
        val year = digits[0].padStart(4, '0')
        val month = digits[1].padStart(2, '0')
        val day = digits[2].padStart(2, '0')
        val hour = digits.getOrNull(3)?.padStart(2, '0') ?: "00"
        val minute = digits.getOrNull(4)?.padStart(2, '0') ?: "00"
        val second = digits.getOrNull(5)?.padStart(2, '0') ?: "00"
        // 年月日之外的数字可能是别的东西（如"第 2 号"），长度异常时放弃排序键
        if (year.length > 4 || month.length > 2 || day.length > 2) return ""
        return "$year$month$day$hour$minute$second"
    }

    suspend fun fetchMessageDetail(transport: AcademicHttpTransport, detailUrl: String): AcademicMessageDetail {
        val resp = transport.get(detailUrl)
        val doc = org.jsoup.Jsoup.parse(resp.text, resp.url)
        val title = doc.title().ifBlank {
            doc.select("h1, h2, h3, .title, .bt").firstOrNull()?.text()?.trim().orEmpty()
        }
        val sender = doc.select(".sender, .author, .lyr, [class*=send], [class*=author]")
            .firstOrNull { it.text().isNotBlank() }?.text()?.trim().orEmpty()
            .ifBlank {
                Regex("(?:发件人|发送人|发布人|来源)[：:\\s]*([^\\n\\r]{1,40})").find(resp.text)
                    ?.groupValues?.getOrNull(1)?.trim().orEmpty()
            }
        val time = Regex("(\\d{4}[-/年.]\\d{1,2}[-/月.]\\d{1,2}(?:[\\s日]?\\d{1,2}:\\d{2}(?::\\d{2})?)?)").find(resp.text)
            ?.groupValues?.getOrNull(1)?.trim().orEmpty()
        val content = extractContent(doc)
        return AcademicMessageDetail(title = title, sender = sender, sendTime = time, content = content)
    }

    // ---- 查询 ----

    /**
     * 一个 `sfyy` 桶的读取结果。
     *
     * 分成三态而不是"有/没有"，是因为三种情况对用户的意义完全不同：
     * 登录失效要引导重新登录；真没消息要显示空态；结构变了才该回退到网页。
     */
    private sealed class Bucket {
        data class Items(val list: List<AcademicMessage>) : Bucket()

        /** 会话失效（HTTP 401/403，或响应被重定向成登录页）。 */
        data object NeedLogin : Bucket()

        /** 拿不到可识别的数据（空响应、结构变了）。 */
        data object Unrecognized : Bucket()
    }

    private suspend fun loadBucket(transport: AcademicHttpTransport, sfyy: Int): Bucket {
        val url = transport.appUrl("$DBSY_PATH?doType=query")
        val referer = transport.appUrl(INDEX_PATH)
        val collected = mutableListOf<AcademicMessage>()
        val seen = HashSet<String>()
        var page = 1
        try {
            while (page <= MAX_PAGES) {
                val fields = listOf(
                    "flag" to FLAG_CURRENT_ROLE.toString(),
                    "sfyy" to sfyy.toString(),
                    "_search" to "false",
                    "nd" to System.currentTimeMillis().toString(),
                    "queryModel.showCount" to PAGE_SIZE.toString(),
                    "queryModel.currentPage" to page.toString(),
                    "queryModel.sortName" to "",
                    "queryModel.sortOrder" to "asc",
                    "time" to "1"
                )
                val resp = transport.postForm(url, fields, referer = referer, ajax = true)
                if (AcademicHtml.isLoginPage(resp.text)) {
                    return if (collected.isEmpty()) Bucket.NeedLogin else Bucket.Items(collected)
                }
                val parsed = parseQuery(resp.text, resp.url, defaultRead = sfyy == SFYY_READ)
                if (parsed == null) {
                    // 第一页就解不出来 = 结构变了；已在途中收到过数据就把已有的交出去
                    return if (collected.isEmpty()) Bucket.Unrecognized else Bucket.Items(collected)
                }
                val (pageItems, total) = parsed
                var added = 0
                for (item in pageItems) if (seen.add(item.id)) { collected += item; added++ }
                // 本页没带来新消息：服务端忽略了 currentPage，或最后一页之后仍在回同一页
                if (added == 0) break
                if (collected.size >= total) break
                if (pageItems.size < PAGE_SIZE) break
                page++
            }
        } catch (e: AcademicException) {
            if (e.status == AcademicStatus.SESSION_EXPIRED) return Bucket.NeedLogin
            // 网络抖动等：已有数据就用已有的，否则交给上层报错
            return if (collected.isEmpty()) Bucket.Unrecognized else Bucket.Items(collected)
        }
        return Bucket.Items(collected)
    }

    /**
     * 解析 `doType=query` 的 JSON。
     *
     * @return `(消息列表, 总数)`；响应不是可识别的 JSON 时返回 null。
     */
    private fun parseQuery(body: String, baseUri: String, defaultRead: Boolean): Pair<List<AcademicMessage>, Int>? {
        val trimmed = body.trim()
        if (!trimmed.startsWith("{")) return null
        val json = runCatching { JSONObject(trimmed) }.getOrNull() ?: return null
        val array = json.optJSONArray("items")
            ?: json.optJSONArray("rows")
            ?: return null
        val total = sequenceOf("totalResult", "records", "total")
            .map { json.text(it).toIntOrNull() }
            .firstOrNull { it != null }
            ?: array.length()

        val messages = mutableListOf<AcademicMessage>()
        for (index in 0 until array.length()) {
            val row = array.optJSONObject(index) ?: continue
            val title = row.text("xxbt").ifBlank { row.text("bt") }.ifBlank { row.text("title") }
            if (title.isBlank()) continue
            val sendTime = row.text("cjsj").ifBlank { row.text("fbsj") }.ifBlank { row.text("sj") }
            val content = row.text("xxnr").ifBlank { row.text("xxbtjc") }
            val key = row.text("zjxx").ifBlank { row.text("xxid") }.ifBlank { row.text("id") }
            val id = key.ifBlank { "$title@$sendTime" }
            val link = row.text("ljdz").ifBlank { row.text("lj") }
            val read = when (row.text("sfyd")) {
                "1", "true" -> true
                "0", "false" -> false
                // 没有可信的已读标记时按桶归属：这条是从 sfyy=1 还是 sfyy=2 拿到的
                else -> defaultRead
            }
            messages += AcademicMessage(
                id = id,
                title = title,
                sender = row.text("jsmc").ifBlank { row.text("fqr") }.ifBlank { row.text("lyr") },
                sendTime = sendTime,
                summary = row.text("xxbtjc").ifBlank { content },
                read = read,
                detailUrl = resolveOrEmpty(link, baseUri),
                content = content
            )
        }
        return messages to total
    }

    // ---- 解析辅助 ----

    private fun extractContent(doc: org.jsoup.nodes.Document): String {
        val container = doc.select(
            ".article, .content, .detail, .message-content, .news-content, #content, " +
                ".xw_content, .tw_article, .nr, table"
        ).firstOrNull { it.text().isNotBlank() }
        val raw = (container ?: doc.select("body").firstOrNull())?.text().orEmpty()
        return raw.replace(Regex("\\s+"), " ").trim()
    }

    /** JSON 文本字段：`null` / `undefined` 归一化成空串，不让 "null" 混进正文。 */
    private fun JSONObject.text(key: String): String {
        val value = opt(key) ?: return ""
        if (value === JSONObject.NULL) return ""
        val rendered = value.toString().trim()
        return if (rendered == "null" || rendered == "undefined") "" else rendered
    }

    /** 详情链接：空 / `javascript:` / `#` 一律当作"没有详情页"。 */
    private fun resolveOrEmpty(href: String, baseUri: String): String {
        if (href.isBlank()) return ""
        if (href.startsWith("javascript", true) || href.startsWith("#")) return ""
        return runCatching {
            java.net.URL(java.net.URL(baseUri), href).toString()
        }.getOrDefault("")
    }
}
