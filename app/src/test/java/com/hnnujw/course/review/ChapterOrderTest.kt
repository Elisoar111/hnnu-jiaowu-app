package com.hnnujw.course.review

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 章节自然序排序的回归测试。
 *
 * 这里钉住的是 2026-10 用户报的「章节顺序问题」：内置题库的章节名是**中文数字**
 * （`第一章` … `第十章`），SQLite 的 `ORDER BY chapter` 是逐字节比较，排出来是
 * 「第一章、第七章、第三章、第九章、第二章、第五章、第八章、第六章、第十章、第四章」。
 *
 * 两个最容易写错的地方各有一组用例：
 *  - **中文数字必须真解析成数值**（不是把字面量排一下就算）；
 *  - **只有"数字位置"上的数字才算编号**，否则「马克思主义基本原理」会因为含「一」
 *    被判成第 1 章，前置章节就会插进正文章节中间。
 */
class ChapterOrderTest {

    // ── 编号解析：中文数字 ────────────────────────────────────────────

    @Test
    fun `中文数字解析成数值`() {
        assertEquals(1.0, chapterNumber("第一章"))
        assertEquals(2.0, chapterNumber("第二章"))
        assertEquals(9.0, chapterNumber("第九章"))
        assertEquals(10.0, chapterNumber("第十章"))
        assertEquals(11.0, chapterNumber("第十一章"))
        assertEquals(20.0, chapterNumber("第二十章"))
        assertEquals(23.0, chapterNumber("第二十三章"))
    }

    @Test
    fun `中文数字带后缀标题也认`() {
        assertEquals(1.0, chapterNumber("第一章 毛泽东思想及其历史地位"))
        assertEquals(7.0, chapterNumber("第七章 “三个代表”重要思想"))
    }

    @Test
    fun `串首的中文数字算编号`() {
        assertEquals(1.0, chapterNumber("一、绪论"))
        assertEquals(3.0, chapterNumber("三 函数"))
    }

    // ── 编号解析：阿拉伯数字 ──────────────────────────────────────────

    @Test
    fun `阿拉伯数字解析成数值`() {
        assertEquals(1.0, chapterNumber("第1章"))
        assertEquals(10.0, chapterNumber("第10章"))
        assertEquals(2.0, chapterNumber("2 函数"))
    }

    @Test
    fun `小节号按小数解析`() {
        assertEquals(1.1, chapterNumber("1.1 绪论"))
        assertEquals(2.3, chapterNumber("2.3 极限"))
    }

    @Test
    fun `第与数字之间允许空格`() {
        assertEquals(10.0, chapterNumber("第 10 章"))
    }

    // ── 编号解析：不该被误判的情况 ────────────────────────────────────

    @Test
    fun `词里的数字不算编号`() {
        // 三条真实会踩的路径：
        //  ① 不含数字（马克思主义基本原理 / 中国特色社会主义理论体系）；
        //  ② 数字在词中（「第七章 “三个代表”重要思想」里的「三」）；
        //  ③ **以数字字开头但那个字是词的一部分** —— 这一条最初漏了，
        //     导致「三个代表重要思想」被判成第 3 章（单测当场抓到）。
        assertNull(chapterNumber("马克思主义基本原理"))
        assertNull(chapterNumber("中国特色社会主义理论体系"))
        assertNull(chapterNumber("三个代表重要思想"))
        assertNull(chapterNumber("一元函数微分学"))
        assertNull(chapterNumber("一般现在时"))
        assertNull(chapterNumber("第三方支付"))
        // 词中的数字：前面没有「第」、也不在串首。
        assertEquals(7.0, chapterNumber("第七章 “三个代表”重要思想"))
    }

    @Test
    fun `数字后面必须是终止符才算编号`() {
        // 终止符 = 串尾 / 标点空白 / 编号量词。
        assertEquals(1.0, chapterNumber("一"))          // 串尾
        assertEquals(1.0, chapterNumber("一、绪论"))     // 顿号
        assertEquals(3.0, chapterNumber("三 函数"))      // 空格
        assertEquals(1.0, chapterNumber("第一章绪论"))   // 量词紧邻（无空格）
        assertEquals(1.0, chapterNumber("第一节"))       // 量词
        assertEquals(2.0, chapterNumber("第2部分"))      // 双字量词
        assertNull(chapterNumber("一元函数微分学"))      // 「元」既非标点也非量词
    }

    @Test
    fun `没有数字的章节名返回 null`() {
        assertNull(chapterNumber("导论"))
        assertNull(chapterNumber("绪论"))
        assertNull(chapterNumber("邓小平理论"))
        assertNull(chapterNumber(""))
        assertNull(chapterNumber("   "))
    }

    // ── 排序 ─────────────────────────────────────────────────────────

    @Test
    fun `内置题库的章节按 1 到 10 排`() {
        // 这是 mao_ze_lishi.json 的真实章节名，也是用户报错的那一份。
        val raw = listOf(
            "第十章", "第一章", "第三章", "第二章", "第五章",
            "第七章", "第九章", "第四章", "第六章", "第八章", "绪论"
        )
        assertEquals(
            listOf("绪论", "第一章", "第二章", "第三章", "第四章", "第五章",
                "第六章", "第七章", "第八章", "第九章", "第十章"),
            sortChapters(raw)
        )
    }

    @Test
    fun `无编号的章节排在第一章之前`() {
        // 中文教材里「导论 / 绪论 / 前言」都是前置内容。
        assertEquals(listOf("导论", "第一章", "第二章"), sortChapters(listOf("第二章", "导论", "第一章")))
    }

    @Test
    fun `编号相同的章节按名称定序`() {
        val raw = listOf("第七章 科学发展观", "第七章 “三个代表”重要思想")
        // 名称按字符序：引号（U+201C）小于「科」（U+79D1）。
        assertEquals(
            listOf("第七章 “三个代表”重要思想", "第七章 科学发展观"),
            sortChapters(raw)
        )
    }

    @Test
    fun `排序不改原列表`() {
        val raw = listOf("第二章", "第一章")
        val sorted = sortChapters(raw)
        assertEquals(listOf("第二章", "第一章"), raw)
        assertEquals(listOf("第一章", "第二章"), sorted)
    }

    @Test
    fun `章节题数列表排序后题数跟着章节走`() {
        val raw = listOf("第十章" to 3, "第一章" to 20, "第二章" to 5)
        assertEquals(
            listOf("第一章" to 20, "第二章" to 5, "第十章" to 3),
            sortChaptersWithCount(raw)
        )
    }

    @Test
    fun `排序键可比较且相等键稳定`() {
        assertTrue(chapterSortKey("第一章") < chapterSortKey("第二章"))
        assertTrue(chapterSortKey("第九章") < chapterSortKey("第十章"))
        assertTrue(chapterSortKey("导论") < chapterSortKey("第一章"))
        assertEquals(0, chapterSortKey("第一章").compareTo(chapterSortKey("第一章")))
    }
}
