package com.tyust.course.academic

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI
import java.nio.charset.Charset


object SystemDetector {
    fun classify(html: String): AcademicSystem? {
        val lower = html.lowercase()
        // 本应用只支持淮南师范学院的正方教务，探测只需识别正方直登页。
        return when {
            lower.contains("login_getpublickey") || (lower.contains("csrftoken") && lower.contains("xtgl")) -> AcademicSystem.ZF
            else -> null
        }
    }
}

object AcademicHtml {
    fun isEnabledControl(element: Element): Boolean =
        (listOf(element) + element.parents()).none {
            it.hasAttr("disabled") || it.hasAttr("hidden") || it.attr("aria-disabled").equals("true", true) ||
                Regex("""(?:^|;)\s*(?:display\s*:\s*none|visibility\s*:\s*hidden)\s*(?:!important)?\s*(?:;|$)""", RegexOption.IGNORE_CASE)
                    .containsMatchIn(it.attr("style"))
        } && !element.attr("type").equals("hidden", true)

    fun isLoginPage(html: String): Boolean {
        val document = Jsoup.parse(html)
        return document.select("input[type=password], input[name=mm], input[name=TextBox2]").isNotEmpty() ||
            Regex("(?:top\\.|window\\.|parent\\.)?location(?:\\.href)?\\s*=\\s*['\"][^'\"]*(?:login_slogin|default2\\.aspx|LoginToXk)", RegexOption.IGNORE_CASE).containsMatchIn(html)
    }
    fun parse(html: String, baseUrl: String, charset: Charset = Charsets.UTF_8) =
        Jsoup.parse(html, baseUrl)

    fun hiddenFields(document: org.jsoup.nodes.Document): Map<String, String> =
        document.select("input[type=hidden][name]").associate { it.attr("name") to it.attr("value") }

    /**
     * 页面上的提示语。正方把这类文案放在两种容器里：
     * - `.nodata`：选课首页的"对不起，当前不属于选课阶段，如有需要，请与管理员联系！"
     * - `.error_title`：错误页的"无功能权限，"
     *
     * 取出来当提示语，比"无法识别的列表"有用得多。
     */
    fun pageNotice(html: String): String = runCatching {
        Jsoup.parse(html).select(".nodata, .error_title, .errorTitle, .error-title, #error_title")
            .firstOrNull()?.text()?.replace('\u00A0', ' ')?.trim().orEmpty()
    }.getOrDefault("")

    fun controlValue(element: Element): String = when {
        element.tagName() == "option" && !element.hasAttr("value") -> element.text()
        element.tagName() == "input" && element.attr("type").lowercase() in setOf("checkbox", "radio") && !element.hasAttr("value") -> "on"
        else -> element.attr("value")
    }

    fun formFields(form: Element, clickedSubmit: Pair<String, String>? = null): List<Pair<String, String>> {
        val result = mutableListOf<Pair<String, String>>()
        for (element in form.select("input[name], button[name], select[name], textarea[name]")) {
            val name = element.attr("name")
            if (name.isBlank() || element.hasAttr("disabled")) continue
            when (element.tagName()) {
                "select" -> {
                    val selected = element.select("option[selected]").ifEmpty {
                        if (!element.hasAttr("multiple") && (element.attr("size").toIntOrNull() ?: 1) <= 1)
                            element.select("option").filter { !it.hasAttr("disabled") && it.parent()?.hasAttr("disabled") != true }.take(1)
                        else emptyList()
                    }.filter { !it.hasAttr("disabled") && it.parent()?.hasAttr("disabled") != true }
                    selected.forEach { result += name to controlValue(it) }
                }
                "textarea" -> result += name to element.text()
                else -> {
                    val type = element.attr("type").lowercase().ifBlank { if (element.tagName() == "button") "submit" else "text" }
                    if (type == "submit" || type == "button") {
                        if (clickedSubmit?.first == name && clickedSubmit.second == element.attr("value")) result += name to element.attr("value")
                    } else if ((type == "checkbox" || type == "radio") && !element.hasAttr("checked")) {
                        continue
                    } else if (type != "file" && type != "reset") {
                        result += name to controlValue(element)
                    }
                }
            }
        }
        return result
    }

    fun action(form: Element, baseUrl: String): String = URI(baseUrl).resolve(form.attr("action").ifBlank { baseUrl }).toString()

    /** Reads an explicitly assigned form action without executing school JavaScript. */
    fun queryFormAction(form: Element, html: String, baseUrl: String): String? {
        val declared = form.attr("action").takeIf(String::isNotBlank)
        val name = form.attr("name").ifBlank { form.id() }
        val assigned = if (name.isBlank()) null else Regex(
            """document\.forms\s*\[\s*['"]""" + Regex.escape(name) +
                """['"]\s*\]\.action\s*=\s*['"]([^'"]+)['"]"""
        ).find(html)?.groupValues?.get(1)
        return (declared ?: assigned)?.let { URI(baseUrl).resolve(it).toString() }
    }

    fun firstScriptValue(html: String, name: String): String? {
        val regex = Regex("(?:var\\s+)?${Regex.escape(name)}\\s*=\\s*['\\\"]([^'\\\"]+)['\\\"]")
        return regex.find(html)?.groupValues?.getOrNull(1)
    }

    fun scriptUrls(document: org.jsoup.nodes.Document, baseUrl: String): List<String> =
        document.select("script[src]").mapNotNull { it.absUrl("src").takeIf(String::isNotBlank) }
}
