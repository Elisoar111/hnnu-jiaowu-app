package com.hnnujw.course.review

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AiConfigRules] 的回归测试（网络参数夹取 + 模型名归一化 + 多档案迁移）。
 *
 * 这三件事写错都是"上真机也难察觉"的类型：
 *  - 夹取写错 → 设置页填 5 秒超时、实际还是 120 秒（或填 99999 直接把请求挂死）；
 *  - 归一化写错 → 老配置里的旧模型名原样发出去（官方已把旧名对应的模型下线，
 *    请求会被转到另一个模型并按 Flash 计费），或者反过来把用户从服务端拉到的新
 *    模型名强行改回默认值（"看着保存成功、其实没生效"）；
 *  - 迁移写错 → 老用户升级后 Key 凭空消失，而且他会以为是软件 bug。
 */
class AiConfigRulesTest {

    @Test
    fun `超时夹取：合法值原样、null 与越界回默认`() {
        assertEquals(10, AiConfigRules.clampTimeoutSeconds(10))
        assertEquals(600, AiConfigRules.clampTimeoutSeconds(600))
        assertEquals(120, AiConfigRules.clampTimeoutSeconds(null))
        assertEquals(120, AiConfigRules.clampTimeoutSeconds(9))
        assertEquals(120, AiConfigRules.clampTimeoutSeconds(601))
        assertEquals(120, AiConfigRules.clampTimeoutSeconds(0))
        assertEquals(120, AiConfigRules.clampTimeoutSeconds(-30))
    }

    @Test
    fun `重试夹取：合法值原样、null 与越界回默认`() {
        assertEquals(0, AiConfigRules.clampRetries(0))
        assertEquals(5, AiConfigRules.clampRetries(5))
        assertEquals(2, AiConfigRules.clampRetries(null))
        assertEquals(2, AiConfigRules.clampRetries(-1))
        assertEquals(2, AiConfigRules.clampRetries(6))
    }

    // ── 模型名归一化 ─────────────────────────────────────────────────────

    @Test
    fun `归一化：空值回默认模型`() {
        assertEquals(AiEndpoint.DEFAULT_MODEL, AiConfigRules.normalizeModel(null))
        assertEquals(AiEndpoint.DEFAULT_MODEL, AiConfigRules.normalizeModel(""))
        assertEquals(AiEndpoint.DEFAULT_MODEL, AiConfigRules.normalizeModel("   "))
    }

    @Test
    fun `归一化：现行模型名原样保留`() {
        AiEndpoint.MODELS.forEach { name ->
            assertEquals(name, AiConfigRules.normalizeModel(name))
        }
        // 两端空白要吃掉，否则芯片选中判定会失配。
        assertEquals("deepseek-v4-pro", AiConfigRules.normalizeModel("  deepseek-v4-pro "))
    }

    @Test
    fun `归一化：已下线的旧模型名改写成现行默认名`() {
        // 官方文档：旧名仍可调用，但对应模型已下线，请求由 DeepSeek-V4.1-Flash 提供。
        // 既然服务端反正会落到 Flash 上，本地就改写 —— 否则设置页芯片全不选中，
        // 用户会以为配置坏了。
        listOf("deepseek-chat", "deepseek-reasoner", "deepseek-v4-flash", "deepseek-v4-flash-vision-exp")
            .forEach { stale ->
                assertEquals("$stale 应被改写", AiEndpoint.DEFAULT_MODEL, AiConfigRules.normalizeModel(stale))
            }
    }

    @Test
    fun `归一化：清单外的名字原样保留`() {
        // 🔴 这条是刻意钉住的行为，不是遗漏：设置页的「获取可用模型」会按用户自己的
        // Key 从服务端拉一份**列表外的**名字下来。若读取时强行收敛到预置两项，
        // 用户选中的新模型会在下次进入设置页时被静默改回默认值。
        assertEquals("deepseek-v9-experimental", AiConfigRules.normalizeModel("deepseek-v9-experimental"))
    }

    // ── 多档案 → 单配置迁移 ──────────────────────────────────────────────

    /** 恒等"解密"：单测不碰 AndroidKeyStore，用它覆盖解析逻辑本身。 */
    private val identity: (String) -> String? = { it }

    @Test
    fun `迁移：空或非 JSON 一律返回 null 且不抛`() {
        assertNull(AiConfigRules.migrateProfiles(null, "", identity))
        assertNull(AiConfigRules.migrateProfiles("", "", identity))
        assertNull(AiConfigRules.migrateProfiles("   ", "", identity))
        assertNull(AiConfigRules.migrateProfiles("not json", "", identity))
        assertNull(AiConfigRules.migrateProfiles("""{"a":1}""", "", identity))
        assertNull(AiConfigRules.migrateProfiles("[]", "", identity))
    }

    @Test
    fun `迁移：没有 DeepSeek 档案时返回 null`() {
        // 把一条 OpenAI 的 Key 迁过来却指向 DeepSeek 端点，只会得到一个语义混乱的 401。
        val raw = """
            [{"name":"oai","baseUrl":"https://api.openai.com","model":"gpt-4o","apiKey":"sk-oai"},
             {"name":"local","baseUrl":"http://127.0.0.1:11434","model":"qwen","apiKey":"x"}]
        """.trimIndent()
        assertNull(AiConfigRules.migrateProfiles(raw, "oai", identity))
    }

    @Test
    fun `迁移：取激活的那条 DeepSeek 档案`() {
        val raw = """
            [{"name":"备用","baseUrl":"https://api.deepseek.com","model":"deepseek-chat","apiKey":"sk-a"},
             {"name":"主力","baseUrl":"https://api.deepseek.com","model":"deepseek-reasoner","apiKey":"sk-b"}]
        """.trimIndent()
        val config = AiConfigRules.migrateProfiles(raw, "主力", identity)!!
        assertEquals(AiEndpoint.BASE_URL, config.baseUrl)
        assertEquals("sk-b", config.apiKey)
        // 旧名要在迁移这一步就改写成现行名。
        assertEquals(AiEndpoint.DEFAULT_MODEL, config.model)
        assertTrue(config.isConfigured)
    }

    @Test
    fun `迁移：激活名对不上时取第一条 DeepSeek 档案`() {
        val raw = """
            [{"name":"oai","baseUrl":"https://api.openai.com","model":"gpt-4o","apiKey":"sk-oai"},
             {"name":"备用","baseUrl":"https://api.deepseek.com","model":"deepseek-flash","apiKey":"sk-a"}]
        """.trimIndent()
        // activeName 指向一个已经不存在（或非 DeepSeek）的档案 —— 不能因此迁不出东西。
        val config = AiConfigRules.migrateProfiles(raw, "已被删掉的档案", identity)!!
        assertEquals("sk-a", config.apiKey)
        assertEquals("deepseek-flash", config.model)
    }

    @Test
    fun `迁移：带尾斜杠或路径的 DeepSeek 端点也认`() {
        val raw = """[{"name":"a","baseUrl":"https://api.deepseek.com/","model":"","apiKey":"sk-a"}]"""
        val config = AiConfigRules.migrateProfiles(raw, "a", identity)!!
        assertEquals("sk-a", config.apiKey)
        // 模型为空 → 默认模型（旧档案可能没填过模型）。
        assertEquals(AiEndpoint.DEFAULT_MODEL, config.model)
    }

    @Test
    fun `迁移：Key 解不开时保留模型 只把 Key 置空`() {
        // 换机 / 系统密钥库被重置的典型场景：整份配置丢掉会把模型与网络参数也一起清空，
        // 那是不必要的损失 —— 用户只需要重填一次 Key。
        val raw = """[{"name":"a","baseUrl":"https://api.deepseek.com","model":"deepseek-v4-pro","apiKey":"<密文>"}]"""
        val config = AiConfigRules.migrateProfiles(raw, "a") { null }!!
        assertEquals("", config.apiKey)
        assertEquals("deepseek-v4-pro", config.model)
        assertTrue(!config.isConfigured)
    }

    @Test
    fun `迁移：本来就没存 Key 的档案迁出空 Key 不报错`() {
        val raw = """[{"name":"a","baseUrl":"https://api.deepseek.com","model":"deepseek-flash","apiKey":""}]"""
        val config = AiConfigRules.migrateProfiles(raw, "a", identity)!!
        assertEquals("", config.apiKey)
        assertEquals("deepseek-flash", config.model)
    }
}
