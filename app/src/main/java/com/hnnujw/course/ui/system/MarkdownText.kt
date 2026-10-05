package com.hnnujw.course.ui.system

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import com.halilibo.richtext.markdown.Markdown
import com.halilibo.richtext.ui.material3.Material3RichText

/**
 * 一段中文说明的显示：含 Markdown 标记时按富文本渲染，不含时退回普通 [Text]。
 *
 * 为什么要先判一次：**全应用绝大多数说明都是纯中文**，走富文本排版后行距、
 * 段落间距、字号都会和原来不一样（richtext 自带一套段落/标题样式），
 * 直接在所有地方无差别替换等于把现有版式全部改一遍。所以这里只在**确实出现
 * Markdown 语法**时才切到 `Material3RichText`，纯文本路径用的就是普通 Text，
 * 参数一一对应，视觉和改动前完全一致。
 *
 * 判定只认明确的语法记号，宁可漏判也不误判（误判 = 好端端的说明突然换排版）。
 */

private val MD_HEADING = Regex("""^[ \t]{0,3}#{1,6}([ \t]|$)""")
private val MD_BULLET = Regex("""^[ \t]{0,3}[-*+]([ \t]|$)""")
private val MD_ORDERED = Regex("""^[ \t]{0,3}\d+[.)]([ \t]|$)""")
private val MD_QUOTE = Regex("""^[ \t]{0,3}>([ \t]|$)""")
private val MD_FENCE = Regex("""^[ \t]{0,3}```""")
private val MD_RULE = Regex("""^[ \t]{0,3}(-{3,}|\*{3,}|_{3,})[ \t]*$""")
// 行内记号。加粗要求 `**` 后紧跟非空白，避免把「* * *」这类装饰当成排版指令。
private val MD_BOLD = Regex("""\*\*[^*\s]([^*]*[^*\s])?\*\*""")
private val MD_CODE = Regex("""`[^`\n]+`""")
private val MD_LINK = Regex("""\[[^\]\n]*\]\([^)\n]*\)""")

/** 文案里是否出现了 Markdown 语法；用于决定走富文本还是普通 Text。 */
internal fun looksLikeMarkdown(text: String): Boolean {
    if (text.isBlank()) return false
    for (line in text.lineSequence()) {
        if (MD_HEADING.containsMatchIn(line)) return true
        if (MD_BULLET.containsMatchIn(line)) return true
        if (MD_ORDERED.containsMatchIn(line)) return true
        if (MD_QUOTE.containsMatchIn(line)) return true
        if (MD_FENCE.containsMatchIn(line)) return true
        if (MD_RULE.containsMatchIn(line)) return true
    }
    return MD_BOLD.containsMatchIn(text) || MD_CODE.containsMatchIn(text) || MD_LINK.containsMatchIn(text)
}

/**
 * 清掉**不构成合法强调的星号**，并把代码片段原样保护起来。
 *
 * 为什么需要这一层：`looksLikeMarkdown` 只认"成对的"加粗（`**x**`）。于是
 * **不成对的星号会原样落到用户眼前** —— 2026-10 用户报「软件里 md 的残留 `****`」。
 * 三种来源都会造成它：
 *  1. 模型（或作者）写出了空加粗 `****`。commonmark 的可配对规则不认它，
 *     直接渲染成字面的四个星号；
 *  2. **流式输出中途**：`**一、解析：这道题` 这样的半截文本，收尾的 `**` 还没到，
 *     整段被判定成"不是 Markdown"而走纯文本分支，星号全露出来；
 *  3. 模型把 `**` 当装饰打了一半就换行。
 *
 * 两条铁律：
 *  - **代码片段里的星号是内容，不是标记**：行内 `` `code` `` 与围栏 ``` ``` ``` 整段
 *    原样抄过去（`**kwargs`、`a ** b` 这种写法不能被改掉）；
 *  - **宁可少删也不误删**：只有"孤立的标记正好落在文本首尾"（流式的典型形态）与
 *    "空加粗"才删。中间那些孤立标记不动 —— 否则 `2**3`（幂）会被改成 `23`，
 *    这比留着星号更糟。
 */
internal fun sanitizeMarkdown(text: String): String {
    if (text.isEmpty() || !text.contains('*')) return text
    val out = StringBuilder(text.length)
    var inFence = false
    text.split('\n').forEachIndexed { index, line ->
        if (index > 0) out.append('\n')
        // 围栏行本身与围栏内部整段跳过（代码块里的星号是内容）。
        if (line.trimStart().startsWith("```")) {
            inFence = !inFence
            out.append(line)
            return@forEachIndexed
        }
        if (inFence) {
            out.append(line)
            return@forEachIndexed
        }
        out.append(sanitizeProseLine(line))
    }
    return out.toString()
}

/** 按行内代码把一行切成"代码 / 非代码"两种片段，只对非代码片段动星号。 */
private fun sanitizeProseLine(line: String): String {
    if (!line.contains('*')) return line
    val out = StringBuilder(line.length)
    var i = 0
    while (i < line.length) {
        if (line[i] == '`') {
            val end = line.indexOf('`', i + 1)
            // 收尾反引号还没到（同样是流式中途）：剩下的全当代码，原样抄。
            if (end < 0) {
                out.append(line, i, line.length)
                return out.toString()
            }
            out.append(line, i, end + 1)
            i = end + 1
        } else {
            var j = i
            while (j < line.length && line[j] != '`') j++
            out.append(pairUpBoldMarkers(line.substring(i, j)))
            i = j
        }
    }
    return out.toString()
}

/**
 * 把一段非代码文本里的加粗标记"成对化"。
 *
 * - 标记数为偶数：两两配对，其中**内容为空**的（`****`、`** **`）整对删掉；
 * - 标记数为奇数：先把前面配掉，剩下那一个**只有落在片段首尾才删**
 *   （流式输出里露出来的就是这个），落在中间的原样保留。
 */
private fun pairUpBoldMarkers(text: String): String {
    if (!text.contains("**")) return text
    val markers = ArrayList<Int>(4)
    var i = 0
    while (i < text.length - 1) {
        if (text[i] == '*' && text[i + 1] == '*') {
            markers.add(i)
            i += 2
        } else {
            i++
        }
    }
    if (markers.isEmpty()) return text

    val drop = BooleanArray(text.length)
    fun mark(at: Int) {
        if (at in drop.indices) drop[at] = true
        if (at + 1 in drop.indices) drop[at + 1] = true
    }

    val paired = markers.size - markers.size % 2
    var k = 0
    while (k + 1 < paired) {
        val open = markers[k]
        val close = markers[k + 1]
        // `****`（close 紧跟 open）与 `** **`（中间只有空白）都是空加粗。
        if (close == open + 2 || text.substring(open + 2, close).isBlank()) {
            mark(open)
            mark(close)
        }
        k += 2
    }
    if (paired < markers.size) {
        val dangling = markers.last()
        if (dangling == 0 || dangling == text.length - 2) mark(dangling)
    }

    val out = StringBuilder(text.length)
    for (idx in text.indices) if (!drop[idx]) out.append(text[idx])
    return out.toString()
}

/**
 * 渲染一段说明文字。
 *
 * @param style 基准排版（默认 bodyMedium），[fontSize] / [lineHeight] / [color] 覆盖在它之上。
 * @param color 不指定时跟随当前内容色。
 */
@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    lineHeight: TextUnit = TextUnit.Unspecified,
    fontWeight: FontWeight? = null
) {
    val resolvedColor = if (color == Color.Unspecified) style.color else color
    val base = style.merge(
        TextStyle(color = resolvedColor, fontSize = fontSize, lineHeight = lineHeight, fontWeight = fontWeight)
    )
    // 🔴 先清洗再判定：不成对的星号会被 commonmark 原样渲染成字面字符（用户报的
    // 「md 残留 `****`」），而它们本来也不是排版意图。清洗是纯字符串变换，
    // 不含标记时**逐字返回原文**，所以纯文本路径的视觉与改动前完全一致。
    val clean = sanitizeMarkdown(text)
    if (!looksLikeMarkdown(clean)) {
        Text(text = clean, modifier = modifier, style = base)
        return
    }
    // Material3RichText 内部按 LocalTextStyle / LocalContentColor 取正文样式，
    // 所以字号行高和颜色只能从这里给进去，没有别的入口。
    CompositionLocalProvider(
        LocalTextStyle provides base,
        LocalContentColor provides resolvedColor
    ) {
        Material3RichText(modifier = modifier) {
            Markdown(content = clean)
        }
    }
}
