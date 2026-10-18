package com.hnnujw.course.manual

import org.json.JSONObject
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 隐私政策的回归。
 *
 * 隐私政策不是文案，是**承诺**。这里锁的是三类会悄悄失效的东西：
 *  1. 文件能被解析（格式错了界面直接空态，用户看不到任何说明）；
 *  2. 每个权限都在文里被解释（新增权限却忘了写说明 = 未申报采集）；
 *  3. 关键承诺还在（比如"不上传""不含凭据"这类话被删掉）。
 *
 * ⚠️ 这里读的是**仓库里的 assets 文件**而不是 `Context.assets` ——
 * 单测没有 Android 资源环境，而文件路径在 CI 上是稳定的。
 */
class PrivacyPolicyTest {

    private val assetFile: File by lazy {
        // 从工作目录向上找 app/src/main/assets。Gradle 测试的工作目录是模块根（app/），
        // 但也可能是仓库根，所以两处都试。
        val candidates = listOf(
            File("src/main/assets/privacy_policy.json"),
            File("app/src/main/assets/privacy_policy.json"),
        )
        candidates.firstOrNull { it.exists() }
            ?: error("找不到 privacy_policy.json，已试: ${candidates.map { it.absolutePath }}")
    }

    private val json: String by lazy { assetFile.readText() }
    private val doc: JSONObject by lazy { JSONObject(json) }

    /** 把全文拼成一串纯文本，供关键词断言用。 */
    private val plainText: String by lazy {
        val sb = StringBuilder()
        fun walk(o: Any?) {
            when (o) {
                is JSONObject -> o.keys().forEach { walk(o.opt(it)) }
                is org.json.JSONArray -> (0 until o.length()).forEach { walk(o.opt(it)) }
                is String -> sb.append(o).append('\n')
            }
        }
        walk(doc)
        sb.toString()
    }

    @Test
    fun `文件存在且能被解析成手册结构`() {
        assertTrue("标题不该为空", doc.optString("title").isNotEmpty())
        val chapters = doc.optJSONArray("chapters")
        assertNotNull("必须有 chapters 数组", chapters)
        assertTrue("章节不能为空", chapters!!.length() > 0)
    }

    /** 每章必须有标题、至少一个块，且块类型必须是渲染器认识的。 */
    @Test
    fun `每章结构完整且块类型合法`() {
        val known = setOf("h", "p", "ul", "ol", "quote", "table", "hr")
        val chapters = doc.getJSONArray("chapters")
        for (i in 0 until chapters.length()) {
            val ch = chapters.getJSONObject(i)
            assertTrue("第 $i 章缺标题", ch.optString("title").isNotEmpty())
            val blocks = ch.optJSONArray("blocks")
            assertNotNull("第 $i 章没有 blocks", blocks)
            assertTrue("第 $i 章没有内容", blocks!!.length() > 0)
            for (b in 0 until blocks.length()) {
                val type = blocks.getJSONObject(b).optString("t")
                assertTrue("第 $i 章第 $b 块类型未知: $type", type in known)
            }
        }
    }

    /** 段落必须有非空文本，否则界面上是一片空白。 */
    @Test
    fun `段落都有实际内容`() {
        val chapters = doc.getJSONArray("chapters")
        var paragraphs = 0
        for (i in 0 until chapters.length()) {
            val blocks = chapters.getJSONObject(i).getJSONArray("blocks")
            for (b in 0 until blocks.length()) {
                val block = blocks.getJSONObject(b)
                if (block.optString("t") != "p") continue
                paragraphs++
                val spans = block.optJSONArray("s")
                assertNotNull("段落缺 s 数组", spans)
                val text = (0 until spans!!.length())
                    .joinToString("") { spans.getJSONObject(it).optString("x") }
                assertTrue("第 $i 章有一个空段落", text.isNotBlank())
            }
        }
        assertTrue("应有足够多的段落", paragraphs > 20)
    }

    // —— 🔴 权限必须逐条被解释

    /**
     * Manifest 里声明的每个危险/敏感权限，文里都必须能找到对应说明。
     *
     * 这是这份文档最容易失效的地方：日后加一个权限，忘了写说明，
     * 用户看到的政策就与实际行为不符了。
     */
    @Test
    fun `每个敏感权限都在文中被解释`() {
        // 权限 → 文中必须出现的关键词。刻意用中文关键词而不是权限名，
        // 因为正文是给用户看的，不该出现 android.permission.XXX。
        val required = mapOf(
            "POST_NOTIFICATIONS" to "通知",
            "SCHEDULE_EXACT_ALARM" to "精确闹钟",
            "RECEIVE_BOOT_COMPLETED" to "开机自启",
            "CAMERA" to "相机",
            "READ_MEDIA_IMAGES" to "读取图片",
            "REQUEST_INSTALL_PACKAGES" to "安装应用包",
            "REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" to "忽略电池优化",
            "ACCESS_NOTIFICATION_POLICY" to "勿扰模式",
            "WRITE_CALENDAR" to "日历",
        )
        val missing = required.filter { (_, keyword) -> !plainText.contains(keyword) }
        assertTrue("这些权限没有在隐私政策里解释: ${missing.keys}", missing.isEmpty())
    }

    // —— 🔴 关键承诺必须还在

    @Test
    fun `明确声明数据只存在本机`() {
        assertTrue("必须声明数据存在本机", plainText.contains("存在你自己的手机上"))
    }

    @Test
    fun `明确声明没有第三方 SDK`() {
        assertTrue("必须声明无第三方 SDK", plainText.contains("没有第三方 SDK"))
    }

    @Test
    fun `明确声明凭据加密存储`() {
        assertTrue("必须说明密码与 Cookie 加密", plainText.contains("加密"))
        assertTrue("必须提到系统密钥库", plainText.contains("Keystore"))
    }

    @Test
    fun `明确声明备份不含凭据`() {
        assertTrue("必须说明备份不含凭据", plainText.contains("备份里不包含"))
        assertTrue("必须提到备份是明文", plainText.contains("备份是明文的"))
    }

    @Test
    fun `明确声明 AI 请求的去向与范围`() {
        assertTrue("必须写明 AI 只发往 DeepSeek", plainText.contains("api.deepseek.com"))
        assertTrue("必须说明不发成绩学号", plainText.contains("不会把成绩、学号、课表发给 AI"))
    }

    @Test
    fun `明确声明不采集的数据`() {
        assertTrue("必须列出不采集项", plainText.contains("不采集"))
        assertTrue("必须点名设备标识", plainText.contains("IMEI"))
        assertTrue("必须点名地理位置", plainText.contains("地理位置"))
    }

    @Test
    fun `给出联系方式`() {
        assertTrue("必须给出反馈渠道", plainText.contains("联系开发者"))
    }

    /** 政策必须注明更新日期，否则用户无法判断看到的是哪一版。 */
    @Test
    fun `注明版本与更新日期`() {
        assertTrue("必须有版本号", doc.optString("version").isNotEmpty())
        val text = plainText
        assertTrue("必须写更新日期", Regex("""\d{4}-\d{2}-\d{2}""").containsMatchIn(text))
    }
}
