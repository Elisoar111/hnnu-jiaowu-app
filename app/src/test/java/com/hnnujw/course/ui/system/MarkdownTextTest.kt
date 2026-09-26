package com.hnnujw.course.ui.system

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MarkdownText] 走哪条路，全看 [looksLikeMarkdown] 这一句判断。
 *
 * 两个方向都有真实代价：
 * - **误判成 Markdown**：一段本来排得好好的中文说明会突然换成富文本的行距与段落间距，
 *   等于悄悄改了全应用的版式——这是更难被发现的那一侧，所以判定只认明确记号；
 * - **漏判成纯文本**：说明里写的加粗和列表会以星号、短横的原样露给用户，白写。
 */
class MarkdownTextTest {

    // ── 纯文本：必须判 false，否则等于改现有版式 ────────────────────────────

    @Test
    fun `整段中文说明判为纯文本`() {
        assertFalse(looksLikeMarkdown("卡内余额、账户总额、宿舍电费与按月消费汇总，全部只读。"))
    }

    @Test
    fun `用中点做的列表不是 Markdown 列表`() {
        // 全应用历史文案都用「· 」当项目符号，不能因为换了个组件就整片变排版。
        assertFalse(looksLikeMarkdown("· 修复了课表在跨周时的显示问题\n· 优化启动速度"))
    }

    @Test
    fun `日期与版本号不是有序列表`() {
        assertFalse(looksLikeMarkdown("2026-09-26 发布\n1.2.6 版本说明"))
    }

    @Test
    fun `单个星号与中文顿号不触发`() {
        assertFalse(looksLikeMarkdown("余额、电费、消费汇总"))
        // 「* 」才算列表；星号后面直接接字只是一次强调的开头，不成语法。
        assertFalse(looksLikeMarkdown("*没有空格"))
    }

    @Test
    fun `空白与空串走纯文本`() {
        assertFalse(looksLikeMarkdown(""))
        assertFalse(looksLikeMarkdown("   \n  \n"))
    }

    @Test
    fun `行中间的减号与数字句点不触发`() {
        // 「第 1-3 节」「共 20.5 元」这类写法在说明里很常见。
        assertFalse(looksLikeMarkdown("可选第 1-3 节，合计 20.5 元"))
    }

    // ── Markdown：必须判 true，否则标记会原样露给用户 ──────────────────────

    @Test
    fun `标题被识别`() {
        assertTrue(looksLikeMarkdown("## 更新内容"))
        assertTrue(looksLikeMarkdown("### 细节"))
    }

    @Test
    fun `无序列表被识别`() {
        assertTrue(looksLikeMarkdown("- 余额查询\n- 电费查询"))
        assertTrue(looksLikeMarkdown("* 余额查询"))
    }

    @Test
    fun `有序列表被识别`() {
        assertTrue(looksLikeMarkdown("1. 先登录\n2. 再查询"))
    }

    @Test
    fun `行内加粗被识别`() {
        assertTrue(looksLikeMarkdown("**选课功能本版起下线**，请改用教务网页"))
    }

    @Test
    fun `行内代码与链接被识别`() {
        assertTrue(looksLikeMarkdown("把 `token` 填进去"))
        assertTrue(looksLikeMarkdown("详见 [发布页](https://example.com)"))
    }

    @Test
    fun `引用与代码块被识别`() {
        assertTrue(looksLikeMarkdown("> 注意：只影响这一次"))
        assertTrue(looksLikeMarkdown("```\nadb install app.apk\n```"))
    }

    @Test
    fun `缩进过的列表也算`() {
        // 说明文字常写在卡片里、带两格缩进。
        assertTrue(looksLikeMarkdown("  - 宿舍电费"))
    }

    @Test
    fun `正文中间出现加粗也算`() {
        // 更新说明里加粗常常出现在段落中部，不在行首。
        assertTrue(looksLikeMarkdown("第一行没有标记\n第二行有 **加粗** 内容"))
    }
}
