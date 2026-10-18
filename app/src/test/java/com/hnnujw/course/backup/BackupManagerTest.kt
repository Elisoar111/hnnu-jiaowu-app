package com.hnnujw.course.backup

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 备份边界的数据分类回归。
 *
 * 这里的核心不是"能不能备份成功"，而是**哪些东西绝不能进备份文件**。
 * 备份是明文 zip，用户可以随手发到群里、传网盘 —— 凭据一旦进去就是泄露。
 *
 * ⚠️ 这个类只测纯逻辑（文件名、边界集合）。真正的 zip 读写需要 Android 的
 * `Context`/`dataDir`，由 Robolectric 用例覆盖（见 BackupManagerIoTest，若存在）。
 * 这里锁的是**分类决策**，那才是会随重构悄悄失效的部分。
 */
class BackupManagerTest {

    @Test
    fun `备份文件名带时间戳且是 zip`() {
        val name = BackupManager.suggestedFileName(1_700_000_000_000L)
        assertTrue("必须以 .zip 结尾: $name", name.endsWith(".zip"))
        assertTrue("必须含中文标识: $name", name.contains("备份"))
        // 时间戳精确到分钟，形如 20231114-2213
        assertTrue("必须含时间戳: $name", Regex("""\d{8}-\d{4}""").containsMatchIn(name))
    }

    @Test
    fun `不同时间的文件名不同（避免互相覆盖）`() {
        val a = BackupManager.suggestedFileName(1_700_000_000_000L)
        val b = BackupManager.suggestedFileName(1_700_000_060_000L)
        assertFalse("不同分钟必须不同名", a == b)
    }

    /** 🔴 凭据绝不能出现在恢复白名单里。 */
    @Test
    fun `凭据类 prefs 不在恢复白名单`() {
        // 通过反射读私有集合：这些集合本身就承载着"边界在哪"的决策，
        // 用反射测它们比在文档里写一句"记得别加"可靠得多。
        val included = privateSetOf("INCLUDED_PREFS")
        val excluded = privateSetOf("EXCLUDED_PREFS")
        assertFalse("凭据不能进白名单", "secure_credentials" in included)
        assertFalse("AI Key 不能进白名单", "review_ai_prefs" in included)
        assertTrue("凭据必须在排除名单", "secure_credentials" in excluded)
        assertTrue("AI Key 必须在排除名单", "review_ai_prefs" in excluded)
    }

    /** 两条名单不能互相包含：那会让"白名单优先"还是"黑名单优先"变成隐式依赖。 */
    @Test
    fun `白名单与排除名单不重叠`() {
        val included = privateSetOf("INCLUDED_PREFS")
        val excluded = privateSetOf("EXCLUDED_PREFS")
        val overlap = included intersect excluded
        assertTrue("两个集合不应有交集，实际: $overlap", overlap.isEmpty())
    }

    /** 提醒计划与设备上已注册的闹钟绑定，换机照搬会导致"以为排了实际没排"。 */
    @Test
    fun `提醒计划不在备份范围`() {
        val included = privateSetOf("INCLUDED_PREFS")
        assertFalse("course_reminders 不该备份", "course_reminders" in included)
        assertFalse("exam_reminders 不该备份", "exam_reminders" in included)
    }

    /** 用户资产必须备份得上。 */
    @Test
    fun `用户资产在备份白名单`() {
        val included = privateSetOf("INCLUDED_PREFS")
        listOf(
            "app_prefs",              // 设置 + 账号列表（不含 Cookie）
            "schedule_settings",      // 自定义课程
            "review_prefs",           // 练习设置
            "review_practice_resume", // 未完成进度
        ).forEach { name ->
            assertTrue("$name 应当备份（它是用户资产）", name in included)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun privateSetOf(field: String): Set<String> {
        val f = BackupManager::class.java.getDeclaredField(field)
        f.isAccessible = true
        return f.get(BackupManager) as Set<String>
    }
}
