package com.hnnujw.course.academic

import org.jsoup.Jsoup

/**
 * 正方教务（jwglxt V9）学生端**功能菜单**。
 *
 * ## 为什么值得单独解析
 *
 * `xtgl/index_initMenu.html` 是服务端直出的整份菜单，每个可用功能长这样：
 *
 * ```html
 * <a onclick="clickMenu('N2510','/kjgl/kjbm_cxXskjbm.html?xmlbfl=1001','考级项目报名','null');">
 * ```
 *
 * 也就是说，**这份页面就是「这个账号到底能进哪些功能」的权威清单**：
 * 既带 `gnmkdm`，也带完整入口路径（含查询参数），还带站点自己的标题。
 * 比我们硬编码模块路径可靠得多 —— 同一所学校换了地址、开了新模块，
 * 或者某个功能只对部分角色开放，这里都会如实反映。
 *
 * 实测（2026-09-25，测试账号）：菜单 26 条，其中考级类只有
 * `/kjgl/kjbm_cxXskjbm.html?xmlbfl=1001` —— **类别代码本来就登记在菜单里**，
 * 所以考级页不必再猜 `xmlbfl`，向菜单要就行。
 *
 * ## 解析口径
 *
 * `onclick` 的实参用单引号包裹；标题是中文功能名（如「考级项目报名」），
 * 不含逗号/括号。为了不被"属性顺序变化 / 引号被写成 HTML 实体 /
 * 菜单被内联进 `<script>`"这几种常见差异打倒：
 * 1. 先按 `[onclick]` 属性取（jsoup 会自动把 `&quot;` 还原成引号）；
 * 2. 取不到再全文扫 `clickMenu(...)`；
 * 3. 实参切分按引号感知的逗号切，不在引号里的逗号才算分隔符。
 */
data class AcademicMenuItem(
    /** 功能代码，如 `N2510`。可能为空（部分条目省略）。 */
    val gnmkdm: String,
    /** 入口路径（含查询参数），如 `/kjgl/kjbm_cxXskjbm.html?xmlbfl=1001`。 */
    val url: String,
    /** 站点自己的标题，如「考级项目报名」。 */
    val title: String,
)

object AcademicMenu {

    /** 菜单页路径（正方 V9）。 */
    const val PATH = "xtgl/index_initMenu.html"

    /** 实参里的括号若出现在引号内不算结束 —— 用引号感知的整体匹配，不用 `[^)]*`。 */
    private val CALL = Regex("""clickMenu\s*\(((?:[^()'"]|'[^']*'|"[^"]*")*)\)""")

    fun parse(html: String): List<AcademicMenuItem> {
        val specs = mutableListOf<String>()
        runCatching {
            Jsoup.parse(html).select("[onclick]").forEach { element ->
                CALL.findAll(element.attr("onclick")).forEach { specs += it.groupValues[1] }
            }
        }
        // 菜单也可能整段内联在 <script> 里（或 onclick 被别的写法包住），兜底全文扫。
        if (specs.isEmpty()) CALL.findAll(html).forEach { specs += it.groupValues[1] }

        return specs
            .map { argumentsOf(it) }
            .filter { it.size >= 2 }
            .map { args ->
                AcademicMenuItem(
                    gnmkdm = args.getOrNull(0)?.trim().orEmpty(),
                    url = args.getOrNull(1)?.trim().orEmpty(),
                    title = args.getOrNull(2)?.trim().orEmpty(),
                )
            }
            .filter { it.url.isNotBlank() }
            .distinctBy { it.gnmkdm to it.url }
    }

    /**
     * 菜单里所有路径含 [keyword] 的条目。
     *
     * 用路径片段而不是精确相等：站点会在同一路径上挂不同查询参数
     * （考级就是 `/kjgl/kjbm_cxXskjbm.html?xmlbfl=1001` / `…=1003` / `…=1004`）。
     */
    fun query(html: String, pathKeyword: String): List<AcademicMenuItem> =
        parse(html).filter { it.url.contains(pathKeyword) }

    /**
     * 从入口路径里取查询参数。路径可能写成相对形式（`/kjgl/…`）或带绝对主机，
     * 这里只做参数匹配，不解析主机。
     */
    fun queryParam(url: String, name: String): String? {
        val query = url.substringAfter('?', "")
        if (query.isBlank()) return null
        return query.split('&')
            .mapNotNull { part ->
                val key = part.substringBefore('=', "")
                if (key != name) null else part.substringAfter('=', "").takeIf { it.isNotBlank() }
            }
            .firstOrNull()
    }

    /**
     * 按引号感知切分实参：只有**不在引号内**的逗号才是分隔符，
     * 引号本身剥掉但保留内部字符（含逗号）。
     */
    private fun argumentsOf(spec: String): List<String> {
        val out = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        for (ch in spec) {
            when {
                quote != null -> if (ch == quote) quote = null else current.append(ch)
                ch == '\'' || ch == '"' -> quote = ch
                ch == ',' -> {
                    out += current.toString().trim()
                    current.clear()
                }
                else -> current.append(ch)
            }
        }
        out += current.toString().trim()
        return out
    }
}
