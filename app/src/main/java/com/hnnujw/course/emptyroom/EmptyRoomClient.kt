package com.hnnujw.course.emptyroom

import com.hnnujw.course.academic.AcademicHtml
import com.hnnujw.course.academic.AcademicHttpTransport
import com.hnnujw.course.model.SchoolConfig
import java.net.URLEncoder

/**
 * 正方「空闲场地查询」（`cdjy/cdjy_cxKxcdlb.html`，gnmkdm=N2155）客户端。
 *
 * 全程复用已登录的教务会话（[AcademicHttpTransport] 与课表/成绩共用 Cookie），
 * **纯只读**：只发查询页 GET 与 `doType=query` 的查询 POST。
 * 网页上的「场地借用申请」（`cdjy_cxYycdlb.html`、`bcOrTjCd`）一个都不碰 ——
 * 那是向学校提交借用申请，属于写操作，本应用不代提交。
 *
 * ## 查询契约（来自页面与 `jquery.jqgrid.settings.js`，非猜测）
 *
 * - 入口页：`GET cdjy/cdjy_cxKxcdlb.html?gnmkdm=N2155`，五个 `<select>` 与周次表头
 *   都是服务端直出，隐藏域里带当前登录人身份（`syr`/`syrxm`）。
 * - 查询：`POST cdjy/cdjy_cxKxcdlb.html?doType=query`，表单字段见 [queryFields]。
 * - 分页参数名**不是** `page`/`rows`，而是 `queryModel.currentPage` /
 *   `queryModel.showCount` —— 这是 `jqGrid4.6/jquery.jqgrid.settings.js` 里
 *   `prmNames` 显式改过的名字。按 jqGrid 默认名发参数会被服务端忽略，
 *   表现为"翻页没反应、永远是同一批数据"。
 * - 响应：`{items:[…], totalResult, currentPage, totalPage}`。
 */
object EmptyRoomClient {

    /** 每页条数。网页默认 15、上限 100，这里取上限以减少往返。 */
    const val PAGE_SIZE = 100

    /** 单次查询最多翻多少页，防服务端忽略分页时把界面拖死。 */
    const val MAX_PAGES = 20

    sealed class PageResult {
        data class Success(val filters: EmptyRoomFilters, val hidden: Map<String, String>) : PageResult()
        data class NeedLogin(val message: String) : PageResult()
        data class Failure(val message: String) : PageResult()
    }

    sealed class QueryResult {
        data class Success(val page: EmptyRoomParser.RoomPage) : QueryResult()
        data class NeedLogin(val message: String) : QueryResult()
        data class Failure(val message: String) : QueryResult()
    }

    sealed class CampusResult {
        data class Success(val options: EmptyRoomCampusOptions) : CampusResult()
        data class NeedLogin(val message: String) : CampusResult()
        data class Failure(val message: String) : CampusResult()
    }

    /**
     * 拉取**指定校区**的楼号与节次（`cdjy/cdjy_cxXqjc.html`）。
     *
     * 为什么必须单独请求：查询页里的 `#lh` 是服务端按**默认校区**渲染的，网页在
     * `#xqh_id` 的 change 里会把 `#lh` 清空重建（`kxcdlb.js` 的 `hqjcList()`）。
     * 不跟着换列表，就会拿朝阳校区的楼号去查泉山校区 —— 服务端不报错，
     * 只是返回空集，用户会以为"这个校区没有空教室"。
     *
     * 返回 [CampusResult.Failure] 时调用方**必须**把楼号退回"全部"并说明原因，
     * 绝不能继续显示上一个校区的楼号。
     */
    suspend fun campusOptions(
        transport: AcademicHttpTransport,
        school: SchoolConfig,
        campusId: String,
        term: String,
    ): CampusResult = try {
        val referer = transport.appUrl("${school.emptyRoomPath}?gnmkdm=${school.emptyRoomGnmkdm}")
        val url = transport.appUrl(
            campusOptionsPath(school.emptyRoomPath) + "?" +
                queryString(campusOptionsFields(campusId, term, school.emptyRoomGnmkdm))
        )
        val response = transport.get(url, referer = referer, ajax = true)
        when {
            AcademicHtml.isLoginPage(response.text) ->
                CampusResult.NeedLogin("登录已失效，请重新登录教务账号")

            response.code !in 200..299 ->
                CampusResult.Failure("读取楼号失败（HTTP ${response.code}），请稍后重试")

            else -> {
                val options = EmptyRoomParser.campusOptions(response.text)
                if (options.buildings.isEmpty() && options.periods.isEmpty()) {
                    // 认不出信封 ⇒ 是"读取失败"，不是"这个校区没有楼号"。
                    // 两者文案必须分开：前者让用户重试，后者才该说"没有"。
                    CampusResult.Failure(
                        AcademicHtml.pageNotice(response.text)
                            .ifBlank { "教务系统没有返回该校区的楼号，可先按「全部」查询" }
                    )
                } else {
                    CampusResult.Success(options)
                }
            }
        }
    } catch (e: Exception) {
        CampusResult.Failure(e.message ?: "读取楼号失败，请检查网络后重试")
    }

    /**
     * 由查询页路径推出同目录的校区可选项路径。
     *
     * `/cdjy/cdjy_cxKxcdlb.html` → `/cdjy/cdjy_cxXqjc.html`。
     * 不写死 `/cdjy/`：`emptyRoomPath` 是学校配置项，别的学校可能挂在别的目录下。
     */
    internal fun campusOptionsPath(emptyRoomPath: String): String {
        val directory = emptyRoomPath.substringBeforeLast('/', "")
        return if (directory.isEmpty()) DEFAULT_CAMPUS_OPTIONS_PATH else "$directory/cdjy_cxXqjc.html"
    }

    /**
     * 校区可选项的请求参数。顺序与网页 `hqjcList()` 一致（`xqh_id`/`xnm`/`xqm`），
     * 另外补上 `gnmkdm` —— 该路由缺了它服务端会直接回 `HTTP请求参数gnmkdm不能为空！`。
     */
    internal fun campusOptionsFields(campusId: String, term: String, gnmkdm: String): List<Pair<String, String>> {
        val (xnm, xqm) = termParts(term)
        return listOf(
            "xqh_id" to campusId,
            "xnm" to xnm,
            "xqm" to xqm,
            "gnmkdm" to gnmkdm,
        )
    }

    /** 表单式的 query string（空格转 `%20` 而不是 `+`，与 jQuery `$.getJSON` 一致）。 */
    internal fun queryString(fields: Iterable<Pair<String, String>>): String =
        fields.joinToString("&") { (key, value) ->
            val encoded = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
            "$key=$encoded"
        }

    private const val DEFAULT_CAMPUS_OPTIONS_PATH = "/cdjy/cdjy_cxXqjc.html"

    /** 拉取查询页，拿到可选项与表单隐藏域。 */
    suspend fun loadPage(transport: AcademicHttpTransport, school: SchoolConfig): PageResult = try {
        val url = transport.appUrl("${school.emptyRoomPath}?gnmkdm=${school.emptyRoomGnmkdm}")
        val response = transport.get(url)
        when {
            AcademicHtml.isLoginPage(response.text) ->
                PageResult.NeedLogin("登录已失效，请重新登录教务账号")

            response.code !in 200..299 ->
                PageResult.Failure("教务系统暂时无法访问（HTTP ${response.code}），请稍后重试")

            else -> {
                val filters = EmptyRoomParser.filters(response.text)
                if (filters.isEmpty) {
                    PageResult.Failure(
                        AcademicHtml.pageNotice(response.text)
                            .ifBlank { "教务系统没有返回空闲场地查询页，可能该功能未对本账号开放" }
                    )
                } else {
                    PageResult.Success(filters, EmptyRoomParser.hiddenFields(response.text))
                }
            }
        }
    } catch (e: Exception) {
        PageResult.Failure(e.message ?: "无法连接教务系统，请检查网络后重试")
    }

    /**
     * 执行一次查询（[pageIndex] 从 1 开始）。
     *
     * [hidden] 必须是**本次会话刚取回**的那一份：里面有服务端下发的登录人身份
     *（`syr`/`syrxm`）与若干开关（`fwzt`/`cduyjsctbj` 等），缓存旧值会在
     * 换账号或换学期后悄悄查错范围。
     */
    suspend fun query(
        transport: AcademicHttpTransport,
        school: SchoolConfig,
        hidden: Map<String, String>,
        condition: EmptyRoomQuery,
        pageIndex: Int = 1,
    ): QueryResult = try {
        val referer = transport.appUrl("${school.emptyRoomPath}?gnmkdm=${school.emptyRoomGnmkdm}")
        val url = transport.appUrl("${school.emptyRoomPath}?doType=query")
        val response = transport.postForm(
            url,
            queryFields(
                condition = condition,
                hidden = hidden,
                remoteParam = school.emptyRoomRemoteParam,
                pageIndex = pageIndex.coerceAtLeast(1),
                pageSize = PAGE_SIZE,
            ),
            referer = referer,
            ajax = true,
        )
        when {
            AcademicHtml.isLoginPage(response.text) ->
                QueryResult.NeedLogin("登录已失效，请重新登录教务账号")

            response.code !in 200..299 ->
                QueryResult.Failure("查询失败（HTTP ${response.code}），请稍后重试")

            else -> {
                val page = EmptyRoomParser.rooms(response.text)
                // 认不出信封时 rooms() 会给出全空的 RoomPage —— 那是"解析失败"，
                // 不是"这段时间没有空教室"。两者必须分开：前者要让用户重试，
                // 后者要给一句"换个时间试试"的空态。
                if (page.total == 0 && page.rooms.isEmpty() && !looksLikeEnvelope(response.text)) {
                    QueryResult.Failure(
                        AcademicHtml.pageNotice(response.text).ifBlank { "教务系统返回了无法识别的数据，请稍后重试" }
                    )
                } else {
                    QueryResult.Success(page)
                }
            }
        }
    } catch (e: Exception) {
        QueryResult.Failure(e.message ?: "查询失败，请检查网络后重试")
    }

    /**
     * 响应文本是不是一个查询信封。
     *
     * 只在 `total == 0 且 rooms 为空` 时才用来区分「真的没有空教室」与
     * 「服务端给了登录页/错误页」。判据是信封里那几个固定键里至少出现一个，
     * 因为 `totalResult` 本身完全可能是 0。
     */
    private fun looksLikeEnvelope(text: String): Boolean =
        ENVELOPE_KEYS.any { text.contains("\"$it\"") }

    private val ENVELOPE_KEYS = listOf("items", "totalResult", "currentPage", "totalPage")

    /**
     * 组装查询表单字段。
     *
     * 顺序与内容对齐网页：**页面隐藏域原样带上**（只覆盖 `xnm`/`xqm`），
     * 再叠加本次筛选条件与 jqGrid 分页参数。
     *
     * `zcd`/`jcd` 是位掩码、`xqj` 是逗号列表 —— 三者形态不同，别统一。
     * `jyfs=0` 表示"按周次+星期+节次"这一种时间口径；另两种（按日期时段、
     * 连续/间断借用）是借用申请的输入方式，查询用不到。
     */
    internal fun queryFields(
        condition: EmptyRoomQuery,
        hidden: Map<String, String>,
        remoteParam: String,
        pageIndex: Int,
        pageSize: Int,
    ): List<Pair<String, String>> {
        val (xnm, xqm) = termParts(condition.term)
        val fields = LinkedHashMap<String, String>()
        hidden.forEach { (key, value) -> if (key.isNotBlank()) fields[key] = value }
        fields["xnm"] = xnm
        fields["xqm"] = xqm
        fields["xqh_id"] = condition.campusId
        fields["lh"] = condition.buildingId
        fields["cdlb_id"] = condition.categoryId
        fields["cdejlb_id"] = ""
        fields["qszws"] = condition.minSeats.trim()
        fields["jszws"] = condition.maxSeats.trim()
        fields["cdmc"] = condition.keyword.trim()
        fields["cd_id"] = ""
        fields["jyfs"] = "0"
        fields["cdjylx"] = ""
        fields["zcd"] = weekMask(condition.weeks).toString()
        fields["xqj"] = weekdayList(condition.weekdays)
        fields["jcd"] = periodMask(condition.periods).toString()
        if (remoteParam.isNotBlank()) fields["zd_fzdm"] = remoteParam
        fields["queryModel.showCount"] = pageSize.toString()
        fields["queryModel.currentPage"] = pageIndex.toString()
        fields["queryModel.sortName"] = "cdbh"
        fields["queryModel.sortOrder"] = "asc"
        return fields.map { it.key to it.value }
    }
}
