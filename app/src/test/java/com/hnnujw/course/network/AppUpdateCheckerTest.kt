package com.hnnujw.course.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 版本比较与「强制更新」标记的解析。
 *
 * 这几条都是**纯函数**，也正是最容易悄悄改坏的地方：标记漏识别会让一个
 * 「不升就用不了」的版本变成可选更新，标记误识别会让正常版本弹窗关不掉。
 */
class AppUpdateCheckerTest {

    // ── 强制更新标记 ────────────────────────────────────────────────────────

    @Test
    fun `独立一行的方括号标记被识别为强制更新`() {
        val body = "[force-update]\n修好了登录失败的问题"
        assertTrue(AppUpdateChecker.isForceUpdate(body))
    }

    @Test
    fun `标记前后有空格也能识别`() {
        assertTrue(AppUpdateChecker.isForceUpdate("说明第一行\n   [FORCE-UPDATE]  \n说明第二行"))
    }

    @Test
    fun `正文里提到这个词但不独立成行时不算强制更新`() {
        val body = "本次不是 [force-update] 版本，只是普通修复"
        assertFalse(AppUpdateChecker.isForceUpdate(body))
    }

    @Test
    fun `普通更新说明不会被误判`() {
        assertFalse(AppUpdateChecker.isForceUpdate("· 修复了课表在跨周时的显示问题\n· 优化启动速度"))
    }

    @Test
    fun `展示给用户的说明里会剥掉标记行`() {
        val notes = AppUpdateChecker.notesForDisplay("[force-update]\n· 修复了登录失败\n· 优化启动速度")
        assertFalse("标记不该出现在用户可见的说明里: $notes", notes.contains("force-update"))
        assertTrue(notes.contains("修复了登录失败"))
        assertTrue(notes.contains("优化启动速度"))
    }

    // ── 版本比较 ───────────────────────────────────────────────────────────

    @Test
    fun `逐段比数字而不是按字符串比`() {
        // 字符串比较会把 "1.2.10" 判成小于 "1.2.9"，这是最经典的一次误判。
        assertTrue(AppUpdateChecker.isNewer("1.2.10", "1.2.9"))
        assertFalse(AppUpdateChecker.isNewer("1.2.9", "1.2.10"))
    }

    @Test
    fun `段数不同时短的那边按零补`() {
        assertTrue(AppUpdateChecker.isNewer("1.3", "1.2.9"))
        assertFalse(AppUpdateChecker.isNewer("1.2", "1.2.0"))
    }

    @Test
    fun `相同版本不算更新`() {
        assertFalse(AppUpdateChecker.isNewer("1.2.5", "1.2.5"))
    }

    @Test
    fun `带非数字段的版本保守判为不更新`() {
        // beta / rc1 这类 tag 宁可漏报，也不能把预发布版当成正式更新推给所有人。
        assertFalse(AppUpdateChecker.isNewer("1.3.0-beta", "1.2.5"))
        assertFalse(AppUpdateChecker.isNewer("1.3.0", "1.2.5-rc1"))
    }

    // ── 交给更新弹窗的说明（现在由 MarkdownText 渲染，标记要留着）────────────

    @Test
    fun `Markdown 标记原样留给弹窗渲染，不再剥成纯文本`() {
        // 弹窗已经能渲染 Markdown，这里再剥一次等于把写说明的人想表达的层次抹掉。
        val notes = AppUpdateChecker.notesForDisplay("## 更新内容\n\n**重要**：修复了崩溃\n\n- 细节一")
        assertEquals("## 更新内容\n\n**重要**：修复了崩溃\n\n- 细节一", notes)
    }

    @Test
    fun `摘掉标记行后不会留下连续空行`() {
        val notes = AppUpdateChecker.notesForDisplay("第一段\n\n[force-update]\n\n第二段")
        assertFalse("标记不该出现在用户可见的说明里: $notes", notes.contains("force-update"))
        assertEquals("第一段\n\n第二段", notes)
    }
}
