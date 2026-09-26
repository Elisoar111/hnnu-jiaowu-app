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
    if (!looksLikeMarkdown(text)) {
        Text(text = text, modifier = modifier, style = base)
        return
    }
    // Material3RichText 内部按 LocalTextStyle / LocalContentColor 取正文样式，
    // 所以字号行高和颜色只能从这里给进去，没有别的入口。
    CompositionLocalProvider(
        LocalTextStyle provides base,
        LocalContentColor provides resolvedColor
    ) {
        Material3RichText(modifier = modifier) {
            Markdown(content = text)
        }
    }
}
