package com.hnnujw.course.ui.document

import com.hnnujw.course.document.DocumentKind
import com.hnnujw.course.secondclass.SecondClassAttachment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「站点附件分类 → 渲染器」映射的口径一致性。
 *
 * `SecondClassAttachment.canPreviewInApp`（详情页提示语）与
 * `documentKind().renderableInApp`（查看器分发的渲染器）是**两份独立写的判断**，
 * 必须永远同真同假 —— 否则会出现详情页说"可以预览"、点进去却没有渲染器的死路。
 * 这里把两份判断的笛卡尔积全量对一遍。
 */
class AttachmentViewerMappingTest {

    @Test
    fun mappingStaysConsistentWithCanPreviewInApp() {
        val names = listOf(
            "通知.docx", "旧版通知.doc", "名单.xlsx", "名单.csv", "旧表格.xls",
            "安排.pdf", "海报.png", "照片.jpeg", "扫描件.bmp", "说明.txt",
            "无扩展名", "附件.rar", "压缩包.zip", "文档.7z",
        )
        // 0 与 6 都表示"站点没给类型"，7 是站点不会给的值 —— 一并覆盖
        val types = listOf(0, 1, 2, 3, 4, 5, 6, 7)

        for (name in names) {
            for (type in types) {
                val attachment = SecondClassAttachment(name = name, url = "https://example/x", attachmentType = type)
                assertEquals(
                    "$name / attachmentType=$type",
                    attachment.canPreviewInApp,
                    attachment.documentKind().renderableInApp,
                )
            }
        }
    }

    @Test
    fun sniffedKindAlsoAgreesWithClaim() {
        // 嗅探口径（按内容）与声明口径（按扩展名）也要一致：
        // docx/xlsx 认得出来，doc/xls 认不出来
        assertEquals(DocumentKind.Word, SecondClassAttachment("通知.docx", "https://e/a").documentKind())
        assertEquals(DocumentKind.Excel, SecondClassAttachment("名单.csv", "https://e/a").documentKind())
        assertEquals(DocumentKind.Pdf, SecondClassAttachment("安排.pdf", "https://e/a").documentKind())
        assertEquals(DocumentKind.Image, SecondClassAttachment("海报.png", "https://e/a").documentKind())
        assertEquals(DocumentKind.Text, SecondClassAttachment("说明.txt", "https://e/a").documentKind())
        assertEquals(DocumentKind.Unknown, SecondClassAttachment("旧版通知.doc", "https://e/a").documentKind())
        assertEquals(DocumentKind.Unknown, SecondClassAttachment("旧表格.xls", "https://e/a").documentKind())
    }

    @Test
    fun mimeTypesCoverOfficeFormats() {
        assertEquals("application/msword", mimeTypeOf("a.doc"))
        assertEquals("application/vnd.openxmlformats-officedocument.wordprocessingml.document", mimeTypeOf("a.docx"))
        assertEquals("application/vnd.ms-excel", mimeTypeOf("a.xls"))
        assertEquals("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", mimeTypeOf("a.xlsx"))
        assertEquals("text/csv", mimeTypeOf("a.csv"))
        assertEquals("text/plain", mimeTypeOf("a.txt"))
        assertEquals("application/octet-stream", mimeTypeOf("a.unknownext"))
    }

    @Test
    fun cacheFileNameSanitizesAndKeepsExtension() {
        val name = cacheFileName("https://ekta.example/a/b/活动通知.docx", "活动通知.docx")

        assertTrue("要保留扩展名: $name", name.endsWith(".docx"))
        assertFalse("不能带路径分隔符: $name", name.contains("/"))
        // 不同 URL 要落到不同缓存文件，避免两个附件互相覆盖
        assertNotEquals(name, cacheFileName("https://ekta.example/c.docx", "c.docx"))
        // 同一 URL 重复下载要命中同一个缓存文件
        assertEquals(name, cacheFileName("https://ekta.example/a/b/活动通知.docx", "活动通知.docx"))
    }
}
