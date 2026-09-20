package com.hnnujw.course.announcement

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 公告逻辑测试。
 *
 * 这组测试的存在本身就是一条教训：公告功能曾经**一个测试都没有**，于是
 * "未读判定写反"这种一行就能杀死的 bug 混了过去 —— 269 个用例全绿，用户装上新版
 * 却一条公告都看不到（`showOnce = true` 的公告被判成永远已读）。凡是"用户可见的行为
 * 由一段纯逻辑决定"的地方，就该有这种粒度的测试。
 */
class AnnouncementLogicTest {

    /** 当前发布版本的 versionCode，与 app/version.properties 一致。 */
    private val currentCode = 10202

    // ── 「未读」判定：这里是出过事的地方 ─────────────────────────────────

    @Test
    fun `showOnce 的公告未读时算未读`() {
        val a = announcement(id = "a", showOnce = true, min = currentCode, max = currentCode)
        assertTrue(
            "版本命中且没读过，必须算未读 —— 否则启动弹窗不弹、红点不亮",
            a.isUnreadIn(readIds = emptySet(), versionCode = currentCode)
        )
    }

    @Test
    fun `showOnce 的公告读过后不再算未读`() {
        val a = announcement(id = "a", showOnce = true, min = currentCode, max = currentCode)
        assertFalse(a.isUnreadIn(readIds = setOf("a"), versionCode = currentCode))
    }

    @Test
    fun `showOnce false 的公告读过后仍然算未读`() {
        val a = announcement(id = "a", showOnce = false, min = currentCode, max = currentCode)
        assertTrue(
            "showOnce = false 的语义是「每次都要显示」，已读记录不该压住它",
            a.isUnreadIn(readIds = setOf("a"), versionCode = currentCode)
        )
    }

    @Test
    fun `版本不命中时不算未读`() {
        val a = announcement(id = "a", showOnce = true, min = currentCode, max = currentCode)
        // 没读过，但当前版本不在范围内
        assertFalse(a.isUnreadIn(readIds = emptySet(), versionCode = currentCode + 1))
    }

    @Test
    fun `别人的已读记录不影响本条`() {
        val a = announcement(id = "a", showOnce = true, min = currentCode, max = currentCode)
        assertTrue(a.isUnreadIn(readIds = setOf("b", "c"), versionCode = currentCode))
    }

    // ── 版本范围 ──────────────────────────────────────────────────────

    @Test
    fun `没有版本字段的公告按历史归档`() {
        val a = announcement(id = "old")
        assertFalse(
            "没声明版本范围 = 写给当时那个版本的历史公告，对新版本一律不适用",
            a.appliesTo(currentCode)
        )
    }

    @Test
    fun `版本范围包含两端边界`() {
        val a = announcement(id = "a", min = 100, max = 200)
        assertTrue(a.appliesTo(100))
        assertTrue(a.appliesTo(150))
        assertTrue(a.appliesTo(200))
    }

    @Test
    fun `版本低于下限或高于上限都不适用`() {
        val a = announcement(id = "a", min = 100, max = 200)
        assertFalse(a.appliesTo(99))
        assertFalse(a.appliesTo(201))
    }

    @Test
    fun `下限为 0 表示不限下限`() {
        // 迁移公告就是这种：写给"还没升级的所有旧版本"
        val a = announcement(id = "migration", min = 0, max = 91)
        assertTrue(a.appliesTo(91))
        assertTrue(a.appliesTo(1))
        assertFalse(a.appliesTo(92))
    }

    @Test
    fun `上限为 0 表示不限上限`() {
        val a = announcement(id = "a", min = 100, max = 0)
        assertTrue(a.appliesTo(100))
        assertTrue(a.appliesTo(999_999))
        assertFalse(a.appliesTo(99))
    }

    // ── 解析 ──────────────────────────────────────────────────────────

    @Test
    fun `解析 announcements 数组格式`() {
        val json = """
            {"announcements":[
              {"id":"x","title":"标题","content":"正文","type":"important",
               "created_at":"2026-09-20","minVersionCode":10202,"maxVersionCode":10202}
            ]}
        """.trimIndent()
        val list = AnnouncementManager.parseAnnouncements(json)
        assertNotNull(list)
        assertEquals(1, list!!.size)
        val a = list.first()
        assertEquals("x", a.id)
        assertEquals("important", a.type)
        assertEquals("2026-09-20", a.createdAt)
        assertEquals(10202, a.minVersionCode)
        assertEquals(10202, a.maxVersionCode)
        assertTrue(a.hasVersionRange)
        assertEquals(true, a.showOnce)  // 不写就是 true
    }

    @Test
    fun `createdAt 与 created_at 两种写法都认`() {
        val camel = """{"id":"x","title":"t","content":"c","createdAt":"2026-01-01"}"""
        val snake = """{"id":"x","title":"t","content":"c","created_at":"2026-01-01"}"""
        assertEquals("2026-01-01", AnnouncementManager.parseAnnouncements(camel)!!.first().createdAt)
        assertEquals("2026-01-01", AnnouncementManager.parseAnnouncements(snake)!!.first().createdAt)
    }

    @Test
    fun `缺 id 或标题或正文的条目被跳过`() {
        val json = """{"announcements":[
            {"id":"","title":"t","content":"c"},
            {"id":"x","title":"","content":"c"},
            {"id":"x","title":"t","content":""},
            {"id":"good","title":"t","content":"c","minVersionCode":1}
        ]}"""
        val list = AnnouncementManager.parseAnnouncements(json)!!
        assertEquals(1, list.size)
        assertEquals("good", list.first().id)
    }

    @Test
    fun `顶层数组与单条格式也支持`() {
        assertEquals(1, AnnouncementManager.parseAnnouncements("""[{"id":"a","title":"t","content":"c"}]""")!!.size)
        assertEquals(1, AnnouncementManager.parseAnnouncements("""{"id":"a","title":"t","content":"c"}""")!!.size)
    }

    @Test
    fun `非法内容返回 null 而不是抛异常`() {
        assertNull(AnnouncementManager.parseAnnouncements("not json at all"))
        assertNull(AnnouncementManager.parseAnnouncements("{ broken"))
    }

    // ── 内置文件（真正决定用户看到什么的那份数据）──────────────────────

    @Test
    fun `本版必须有一条覆盖当前 versionCode 的公告`() {
        val props = versionProperties()
        val code = orFail(props["VERSION_CODE"]?.toIntOrNull(), "app/version.properties 里读不到 VERSION_CODE")
        val name = orFail(props["VERSION_NAME"], "app/version.properties 里读不到 VERSION_NAME")
        val list = orFail(bundled(), "assets/announcement.json 解析失败")

        val applying = list.filter { it.appliesTo(code) }
        assertTrue(
            "assets/announcement.json 里没有任何一条公告适用于当前版本 " +
                "(versionCode=$code)。每发一版都要给这一版补一条公告，否则用户装上新版看不到更新说明。",
            applying.isNotEmpty()
        )

        // 而且要有一条明确写着"本版"的（id 里带版本号），避免只是被别的公告顺带覆盖
        val tag = "v" + name.replace('.', '_')
        val own = list.firstOrNull { it.id.contains(tag) }
        assertNotNull("没有找到 id 含 $tag 的本版公告（现有 id：${list.map { it.id }}）", own)
        assertTrue(
            "本版公告 ${own!!.id} 的版本范围（${own.minVersionCode}–${own.maxVersionCode}）" +
                "不覆盖当前 versionCode=$code —— 版本号写错就会静默不显示",
            own.appliesTo(code)
        )
    }

    @Test
    fun `内置公告字段合法`() {
        val list = orFail(bundled(), "assets/announcement.json 解析失败")
        list.forEach { a ->
            assertTrue("公告 id 不能为空", a.id.isNotBlank())
            assertTrue("公告 ${a.id} 标题不能为空", a.title.isNotBlank())
            assertTrue("公告 ${a.id} 正文不能为空", a.content.isNotBlank())
            assertTrue("公告 ${a.id} 的类型只能是 info/warning/important", a.type in setOf("info", "warning", "important"))
            if (a.hasVersionRange && a.minVersionCode != 0 && a.maxVersionCode != 0) {
                assertTrue(
                    "公告 ${a.id} 的版本下限 ${a.minVersionCode} 大于上限 ${a.maxVersionCode}，永远不会命中任何版本",
                    a.minVersionCode <= a.maxVersionCode
                )
            }
        }
    }

    @Test
    fun `已读记录编解码可往返`() {
        val records = listOf(
            AnnouncementManager.ReadRecord("a", 100L),
            AnnouncementManager.ReadRecord("b", 200L)
        )
        val encoded = AnnouncementManager.encodeReadRecords(records)
        assertEquals(records, AnnouncementManager.parseReadRecords(encoded))
        // 没有时间戳的旧格式也要能读出来（补 0，于是会被优先裁剪）
        assertEquals(
            listOf(AnnouncementManager.ReadRecord("legacy", 0L)),
            AnnouncementManager.parseReadRecords("legacy")
        )
    }

    // ── 工具 ──────────────────────────────────────────────────────────

    private fun announcement(
        id: String,
        showOnce: Boolean = true,
        min: Int? = null,
        max: Int? = null
    ) = AnnouncementManager.Announcement(
        id = id,
        title = "标题",
        content = "正文",
        type = "info",
        showOnce = showOnce,
        createdAt = "2026-09-20",
        minVersionCode = min ?: 0,
        maxVersionCode = max ?: 0,
        hasVersionRange = min != null || max != null
    )

    /** JUnit 的 fail() 返回 Unit，不能用在 elvis 右侧；这里显式抛断言错误。 */
    private fun <T> orFail(value: T?, message: String): T =
        value ?: throw AssertionError(message)

    /** Gradle 单测的工作目录是模块目录（app/），但这里向上找一遍，换个跑法也不会挂。 */
    private fun moduleDir(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            if (File(dir, "version.properties").isFile) return dir
            dir = dir.parentFile
        }
        error("找不到 app/version.properties（当前工作目录 ${System.getProperty("user.dir")}）")
    }

    private fun versionProperties(): Map<String, String> =
        File(moduleDir(), "version.properties").readLines()
            .mapNotNull { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("#")) return@mapNotNull null
                val idx = trimmed.indexOf('=')
                if (idx <= 0) null else trimmed.substring(0, idx).trim() to trimmed.substring(idx + 1).trim()
            }
            .toMap()

    private fun bundled(): List<AnnouncementManager.Announcement>? =
        AnnouncementManager.parseAnnouncements(
            File(moduleDir(), "src/main/assets/announcement.json").readText(Charsets.UTF_8)
        )
}
