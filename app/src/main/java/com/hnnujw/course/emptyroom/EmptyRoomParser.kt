package com.hnnujw.course.emptyroom

import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup

/**
 * 正方「空闲场地查询」（`cdjy/cdjy_cxKxcdlb.html`，gnmkdm=N2155）的解析层。
 *
 * 全部是纯函数（输入 HTML / JSON 字符串，输出模型），所以可以在单测里
 * 拿真实页面片段钉住 —— 这类解析一旦写错，表现是"筛选条件选了没反应"
 * 或"列表恒为空"，都属于 HTTP 200、无异常、没有日志的那种缺陷。
 */
object EmptyRoomParser {

    /** 一次查询的结果页。 */
    data class RoomPage(
        val rooms: List<EmptyRoom>,
        /** 命中总数（服务端 `totalResult`）。用于"共 N 间"与翻页判据。 */
        val total: Int,
        val currentPage: Int,
        val pageTotal: Int,
    ) {
        companion object { val EMPTY = RoomPage(emptyList(), 0, 1, 0) }
    }

    // ── 查询页（HTML）────────────────────────────────────────────────────

    /**
     * 从查询页里取出全部可选筛选项。
     *
     * 页面上这五个 `<select>` 都是**服务端直出**的（含默认校区的楼栋列表），
     * 不需要额外请求就能拿到；只有「场地二级类别」是 JS 按一级类别动态拉的
     * （`query/query_cxEjjcdlbList.html`），本应用不提供该项，见 [EmptyRoomQuery]。
     */
    fun filters(html: String): EmptyRoomFilters {
        val document = runCatching { Jsoup.parse(html) }.getOrNull() ?: return EmptyRoomFilters.EMPTY
        return EmptyRoomFilters(
            terms = options(document, "#dm_cx"),
            campuses = options(document, "#xqh_id"),
            buildings = options(document, "#lh"),
            categories = options(document, "#cdlb_id"),
            weeks = weeks(document),
        )
    }

    /**
     * 表单隐藏域。
     *
     * ⚠️ 里面含 `syr`（学号）与 `syrxm`（姓名）—— **必须每次从页面现取**，
     * 不能写死、更不能落盘：写死会让所有用户带着同一个身份去查。
     * 页面本身就是"谁登录谁的身份"，我们只是把它原样带回去。
     */
    fun hiddenFields(html: String): Map<String, String> = runCatching {
        Jsoup.parse(html).select("input[type=hidden][name]")
            .associate { it.attr("name") to it.attr("value") }
            .filterKeys { it.isNotBlank() }
    }.getOrDefault(emptyMap())

    /** 取某个 `<select>` 的选项。第一项若是"全部"（value 为空）也照收 —— 它是合法选择。 */
    private fun options(document: org.jsoup.nodes.Document, selector: String): List<EmptyRoomOption> =
        document.select("$selector option").mapNotNull { element ->
            val value = element.attr("value").trim()
            val label = element.text().trim()
            when {
                label.isEmpty() && value.isEmpty() -> null
                // 空 value 的"全部"用 label 兜底，避免界面出现一个空白行
                value.isEmpty() -> EmptyRoomOption("", label)
                else -> EmptyRoomOption(value, label.ifBlank { value })
            }
        }

    /**
     * 周次表头。
     *
     * 只有 `class="selectTH"` 的格子可选；`class="displaynone"` 的是
     * 服务端按学期进度置灰的周次（`zczt == "0"`，见 kxcdlb.js），
     * 收进来会变成一个点不动的格子，所以这里按 class 过滤。
     */
    private fun weeks(document: org.jsoup.nodes.Document): List<Int> =
        document.select("#selectTR_ZC th.selectTH").mapNotNull { it.attr("value").trim().toIntOrNull() }
            .distinct().sorted()

    // ── 校区可选项（JSON）──────────────────────────────────────────────

    /**
     * 解析 `cdjy/cdjy_cxXqjc.html` 的响应：某校区的楼号与节次。
     *
     * 真实契约（逐字来自 `js/comp/jwglxt/pkgl/cdjy/kxcdlb.js` 的 `hqjcList()`）：
     *
     * ```js
     * $.getJSON(_path + "/cdjy/cdjy_cxXqjc.html",
     *           {"xqh_id":…, "xnm":…, "xqm":…}, function(data){
     *     $.each(data.jcList||[], function(i,rowObj){ rowObj["JCMC"] / rowObj["RSDJCMC"] / rowObj["RSDMC"] })
     *     $.each(data.lhList||[], function(i,rowObj){ rowObj["JXLDM"] / rowObj["JXLMC"] })
     * })
     * ```
     *
     * 键名是**大写**的，但同一个网关对不同路由的大小写并不一致，所以两种都认；
     * 认不出时返回空列表（调用方据此提示），**不会**编造楼号。
     */
    fun campusOptions(json: String): EmptyRoomCampusOptions {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return EmptyRoomCampusOptions.EMPTY
        return EmptyRoomCampusOptions(
            buildings = optionList(root.opt("lhList") ?: root.opt("lhlist") ?: root.opt("lh"),
                BUILDING_VALUE_KEYS, BUILDING_LABEL_KEYS),
            periods = optionList(root.opt("jcList") ?: root.opt("jclist") ?: root.opt("jc"),
                PERIOD_VALUE_KEYS, PERIOD_LABEL_KEYS),
        )
    }

    private fun optionList(value: Any?, valueKeys: List<String>, labelKeys: List<String>): List<EmptyRoomOption> {
        val array = value as? JSONArray ?: return emptyList()
        return (0 until array.length()).mapNotNull { index -> option(array.opt(index), valueKeys, labelKeys) }
    }

    private fun option(entry: Any?, valueKeys: List<String>, labelKeys: List<String>): EmptyRoomOption? = when (entry) {
        is JSONObject -> {
            val value = firstText(entry, valueKeys)
            val label = firstText(entry, labelKeys)
            when {
                value.isEmpty() && label.isEmpty() -> null
                // 值缺失时用名兜底：宁可查不到，也不要发一个空值让服务端当"全部"
                value.isEmpty() -> EmptyRoomOption(label, label)
                else -> EmptyRoomOption(value, label.ifBlank { value })
            }
        }
        // 少数路由直接把选项给成字符串数组
        is String -> entry.trim().takeIf { it.isNotEmpty() }?.let { EmptyRoomOption(it, it) }
        else -> null
    }

    /** 按候选键顺序取第一个非空字符串；数字（如 `JCMC` 给 1）也认。 */
    private fun firstText(item: JSONObject, keys: List<String>): String {
        for (key in keys) {
            if (!item.has(key)) continue
            val raw = item.opt(key) ?: continue
            val text = when (raw) {
                is Number -> raw.toInt().toString()
                is String -> raw.trim()
                else -> ""
            }
            if (text.isNotEmpty()) return text
        }
        return ""
    }

    private val BUILDING_VALUE_KEYS = listOf("JXLDM", "jxlDm", "lh", "LH", "lhDm", "value", "id", "dm")
    private val BUILDING_LABEL_KEYS = listOf("JXLMC", "jxlMc", "lhMc", "jxlMcName", "mc", "MC", "name", "label")
    private val PERIOD_VALUE_KEYS = listOf("JCMC", "jcmc", "jc", "JC", "value", "id")
    private val PERIOD_LABEL_KEYS = listOf("RSDJCMC", "rsdjcmc", "JCMC", "jcmc", "mc", "MC", "name", "label")

    // ── 查询结果（JSON）──────────────────────────────────────────────────

    /**
     * 解析查询结果。
     *
     * 服务端信封是 `{items:[…], totalResult:"537", currentPage, totalPage, …}`。
     * [itemsArray] 仍然做了多形态兜底 —— 同一个网关对不同路由的包装并不一致
     *（二课那边就因此踩过一次静默空列表），多认几种形态的代价只是几行代码。
     */
    fun rooms(json: String): RoomPage {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return RoomPage.EMPTY
        val items = itemsArray(root)
        val rooms = items.mapNotNull { item -> runCatching { room(item) }.getOrNull() }
        return RoomPage(
            rooms = rooms,
            total = int(root, "totalResult", "totalCount", "records", "count") ?: rooms.size,
            currentPage = int(root, "currentPage", "pageNo", "page") ?: 1,
            pageTotal = int(root, "totalPage", "pageTotal") ?: 0,
        )
    }

    private fun itemsArray(root: JSONObject): List<JSONObject> {
        val data = root.optJSONObject("data")
        val array: JSONArray? = root.optJSONArray("items")
            ?: root.optJSONArray("rows")
            ?: root.optJSONArray("list")
            ?: data?.optJSONArray("items")
            ?: data?.optJSONArray("rows")
            ?: data?.optJSONArray("list")
            ?: data?.optJSONArray("data")
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { array.optJSONObject(it) }
    }

    /**
     * 单行 → [EmptyRoom]。
     *
     * 只认场地属性字段；服务端在同一行里混进了 `date`/`userModel`/`queryModel`/
     * `pageTotal` 这些**每行都一样的**噪声（它们是 jqGrid 的会话字段被序列化进来了），
     * 一个都不取。
     */
    private fun room(item: JSONObject): EmptyRoom = EmptyRoom(
        id = item.optString("cd_id").trim(),
        code = item.optString("cdbh").trim(),
        name = item.optString("cdmc").trim(),
        category = item.optString("cdlbmc").trim(),
        building = item.optString("jxlmc").trim(),
        floor = item.optString("lch").trim(),
        campus = item.optString("xqmc").trim(),
        seats = number(item, "zws"),
        examSeats = number(item, "kszws1"),
    )

    /** 数值字段可能是数字也可能是字符串（服务端两种都给过），统一取整。 */
    private fun number(item: JSONObject, key: String): Int {
        val raw = item.opt(key)
        return when (raw) {
            is Number -> raw.toInt()
            is String -> raw.trim().toDoubleOrNull()?.toInt() ?: 0
            else -> 0
        }
    }

    private fun int(root: JSONObject, vararg keys: String): Int? {
        for (key in keys) {
            if (!root.has(key)) continue
            when (val value = root.opt(key)) {
                is Number -> return value.toInt()
                is String -> value.trim().toIntOrNull()?.let { return it }
            }
        }
        return null
    }
}
