package com.hnnujw.course.academic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 教务功能菜单解析回归。
 *
 * 菜单（`xtgl/index_initMenu.html`）是「这个账号到底能进哪些功能」的权威清单，
 * 考级页的类别代码（`xmlbfl`）就是从它这里发现的 —— 所以解析必须稳。
 *
 * 样本形状照 2026-09-25 线上实测（个人信息已脱敏）。
 */
class AcademicMenuParserTest {

    /** 线上菜单的真实片段形状：`<li><a onclick="clickMenu('…','…','…','null'); return false;">`。 */
    private val liveMenuFragment = """
        <div class="nav-list">
        <ul>
          <li><a tabindex="-1" onclick="clickMenu('N1056','/cxbm/cxbm_cxXscxbmIndex.html','重修报名','null'); return false;" href="javascript:void(0);" rel="noopener noreferrer" target="_blank">重修报名</a></li>
          <li><a tabindex="-1" onclick="clickMenu('N2510','/kjgl/kjbm_cxXskjbm.html?xmlbfl=1001','考级项目报名','null'); return false;" href="javascript:void(0);" rel="noopener noreferrer" target="_blank">考级项目报名</a></li>
          <li><a tabindex="-1" onclick="clickMenu('N2511','/jxrwbmgl/jxrwxmbm_cxJxrwxmbmIndex.html','教学项目报名','null'); return false;" href="javascript:void(0);" rel="noopener noreferrer" target="_blank">教学项目报名</a></li>
          <li><a tabindex="-1" onclick="clickMenu('N401605','/xspjgl/xspj_cxXspjIndex.html?doType=details','学生评价','null'); return false;" href="javascript:void(0);" rel="noopener noreferrer" target="_blank">学生评价</a></li>
        </ul>
        </div>
    """.trimIndent()

    @Test
    fun `parses live menu entries with gnmkdm url and title`() {
        val items = AcademicMenu.parse(liveMenuFragment)
        assertEquals(4, items.size)
        val kaoji = items.first { it.gnmkdm == "N2510" }
        assertEquals("/kjgl/kjbm_cxXskjbm.html?xmlbfl=1001", kaoji.url)
        assertEquals("考级项目报名", kaoji.title)
    }

    @Test
    fun `query keeps only entries whose path contains the keyword`() {
        val kaoji = AcademicMenu.query(liveMenuFragment, "kjbm_cxXskjbm.html")
        assertEquals(1, kaoji.size)
        assertEquals("N2510", kaoji[0].gnmkdm)
        // 教学项目报名是另一个模块，路径不含该关键字，不能被误收
        assertTrue(kaoji.none { it.gnmkdm == "N2511" })
    }

    @Test
    fun `query is not fooled by a similarly named module`() {
        // jxrwbmgl/jxrwxmbm_cxJxrwxmbmIndex.html 与 kjbm 撞了 bm 两个字母
        assertTrue(AcademicMenu.query(liveMenuFragment, "jxrwxmbm_cxJxrwxmbmIndex.html").size == 1)
        assertTrue(AcademicMenu.query(liveMenuFragment, "kjbm_cxXskjbm").none { it.gnmkdm == "N2511" })
    }

    @Test
    fun `queryParam reads xmlbfl and ignores others`() {
        assertEquals("1001", AcademicMenu.queryParam("/kjgl/kjbm_cxXskjbm.html?xmlbfl=1001", "xmlbfl"))
        assertEquals("1004", AcademicMenu.queryParam("/kjgl/kjbm_cxXskjbm.html?gnmkdm=N2510&xmlbfl=1004", "xmlbfl"))
        assertEquals("1003", AcademicMenu.queryParam("/jwglxt/kjgl/kjbm_cxXskjbm.html?xmlbfl=1003&_=1", "xmlbfl"))
        assertNull(AcademicMenu.queryParam("/cxbm/cxbm_cxXscxbmIndex.html", "xmlbfl"))
        // 值为空时视为没有
        assertNull(AcademicMenu.queryParam("/kjgl/kjbm_cxXskjbm.html?xmlbfl=", "xmlbfl"))
    }

    @Test
    fun `tolerates double quotes and html-escaped quotes`() {
        // 有的构建把实参写成双引号，甚至整段 onclick 用 &quot; 转义
        val html = """
            <li><a onclick="clickMenu(&quot;N2510&quot;,&quot;/kjgl/kjbm_cxXskjbm.html?xmlbfl=1003&quot;,&quot;大类分流报名&quot;,&quot;null&quot;)">x</a></li>
        """.trimIndent()
        val items = AcademicMenu.parse(html)
        assertEquals(1, items.size)
        assertEquals("N2510", items[0].gnmkdm)
        assertEquals("1003", AcademicMenu.queryParam(items[0].url, "xmlbfl"))
        assertEquals("大类分流报名", items[0].title)
    }

    @Test
    fun `falls back to scanning the whole document when no onclick attribute carries the menu`() {
        // 菜单整段内联在 <script> 里的构建（没有 onclick 属性可挂）
        val html = """
            <html><body><script>
            var menu = [];
            menu.push("clickMenu('N2510','/kjgl/kjbm_cxXskjbm.html?xmlbfl=1004','推免报名','null')");
            </script></body></html>
        """.trimIndent()
        val items = AcademicMenu.parse(html)
        assertEquals(1, items.size)
        assertEquals("推免报名", items[0].title)
        assertEquals("1004", AcademicMenu.queryParam(items[0].url, "xmlbfl"))
    }

    @Test
    fun `titles containing commas or parentheses do not break the argument split`() {
        // 引号内的逗号/括号不是分隔符 —— 用 [^)]* 之类的懒正则就会被切坏
        val html = """<a onclick="clickMenu('N9','/a/b.html','报名(补),说明','null')">x</a>"""
        val items = AcademicMenu.parse(html)
        assertEquals(1, items.size)
        assertEquals("报名(补),说明", items[0].title)
        assertEquals("/a/b.html", items[0].url)
    }

    @Test
    fun `degrades to empty list on junk input`() {
        assertTrue(AcademicMenu.parse("").isEmpty())
        assertTrue(AcademicMenu.parse("<html><body>无功能权限</body></html>").isEmpty())
        // 参数不全（只有一个）时丢弃，不要造出半截条目
        assertTrue(AcademicMenu.parse("""<a onclick="clickMenu('N1')">x</a>""").isEmpty())
    }

    @Test
    fun `deduplicates identical entries`() {
        val html = """<a onclick="clickMenu('N2510','/kjgl/kjbm_cxXskjbm.html?xmlbfl=1001','考级项目报名','null')">a</a>""" +
            """<a onclick="clickMenu('N2510','/kjgl/kjbm_cxXskjbm.html?xmlbfl=1001','考级项目报名','null')">b</a>"""
        assertEquals(1, AcademicMenu.parse(html).size)
    }
}
