package com.hnnujw.course.review

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * DeepSeek 的一份配置：Key + 模型 + 网络参数。
 *
 * 为什么不再有「档案」：上一版支持多套档案（官方 API / 中转站 / 本地推理各一套）
 * 并带主备降级，是为了"换服务商不用重打 Key"。2026-10 用户决定只支持 DeepSeek
 * 官方 API 后，多档案失去了存在前提 —— 只剩一个端点，第二条档案没有任何可选内容。
 * 档案列表、激活项、主备降级（`AiFailover`）、档案名校验一并删除（见 git 历史）。
 *
 * [baseUrl] 不是用户可配项（设置页不暴露、也不落盘），保留字段只为单测能指向
 * MockWebServer；生产值恒为 [AiEndpoint.BASE_URL]。
 */
data class AiConfig(
    val baseUrl: String = AiEndpoint.BASE_URL,
    val apiKey: String = "",
    val model: String = AiEndpoint.DEFAULT_MODEL,
    val timeoutSeconds: Int = AiConfigRules.DEFAULT_TIMEOUT_SECONDS,
    val retries: Int = AiConfigRules.DEFAULT_RETRIES
) {
    /** 可以发请求了（三要素齐全）。端点恒定，所以只判 Key 与模型。 */
    val isConfigured: Boolean
        get() = apiKey.isNotBlank() && model.isNotBlank() && baseUrl.isNotBlank()
}

/**
 * AI 配置的本地加密存储（单份配置）。
 *
 * 落盘键与**最早那版单配置存储完全一致**（`api_key` / `model` / `timeout_seconds` /
 * `retries`），这不是巧合而是刻意为之：键名一致 ⇒ 从"最早单配置"升级到本版
 * **无需迁移**，[load] 第一条分支就读得到。真正需要迁移的只有中间那版多档案
 * 格式（`profiles` JSON + `active_profile`），见 [AiConfigRules.migrateProfiles]。
 *
 * 隐私红线（与旧实现一致，一条不放松）：
 *  - 密文落 SharedPreferences，密钥只存在于 AndroidKeyStore（应用沙盒内，不可导出）；
 *  - **Key 绝不打日志**（连片段都不打），异常只记 message；
 *  - 解密失败（换机 / 系统密钥库重置）**不抛**，Key 置空让用户重填；
 *  - 界面永不回显已保存的 Key。
 *
 * ⚠️ 本存储是**设备级**的（不绑账号、不按账号隔离），因此**不接入**
 * `SettingsRoute.deleteAccountEntirely`（那是按账号落盘的存储才需要的）。
 */
object AiConfigStore {
    private const val TAG = "AiConfigStore"

    /** 沿用旧 prefs 文件名 —— 换名字的话老用户的 Key 就找不到了。 */
    private const val PREFS = "review_ai_prefs"

    private const val KEY_API_KEY = "api_key"
    private const val KEY_MODEL = "model"
    private const val KEY_TIMEOUT = "timeout_seconds"
    private const val KEY_RETRIES = "retries"

    /** 中间那版「多档案」的残留键，只在迁移时读一次，迁完即删。 */
    private const val LEGACY_PROFILES = "profiles"
    private const val LEGACY_ACTIVE = "active_profile"

    private const val KEY_ALIAS = "zf_review_ai_v1"
    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_LENGTH = 12
    private const val GCM_TAG_BITS = 128

    // ── 读 ───────────────────────────────────────────────────────────────

    /**
     * 进程内缓存。
     *
     * `load()` 要碰 AndroidKeyStore 解密，单次几毫秒到几十毫秒。而它被读得很频繁：
     * 首页 AI 入口每次进页面读一次、练习页每次 `ON_RESUME` 读一次。
     * 配置是设备级的单份数据，进程存活期间不会在别处被改（改都走本类的 [save]/[clear]），
     * 所以缓存到进程结束是安全的；[save] 与 [clear] 会同步失效。
     */
    @Volatile
    private var cached: AiConfig? = null

    /**
     * 读回配置（不存在时返回一份空配置，不返回 null）。
     *
     * ⚠️ 首次调用要碰 AndroidKeyStore 解密，**必须在 IO 线程调用**；调用方仍应
     * `runCatching` 包一层 —— 真机上密钥库损坏会抛，在协程里裸抛就是崩进程。
     */
    fun load(context: Context): AiConfig {
        cached?.let { return it }
        return synchronized(this) {
            cached ?: readFromDisk(context).also { cached = it }
        }
    }

    private fun readFromDisk(context: Context): AiConfig {
        val prefs = prefs(context)

        // ① 现格式（与最早那版单配置同键名，因此老数据直接命中这条）。
        if (prefs.contains(KEY_API_KEY)) {
            return readCurrent(context)
        }

        // ② 中间那版多档案 → 折算成一份配置。折算结果为空（例如档案都不是
        //    DeepSeek 端点、或 Key 解密失败）时也照样清掉残留键：留着只会每次重算一遍。
        val legacy = prefs.getString(LEGACY_PROFILES, null)
        if (legacy != null) {
            val migrated = AiConfigRules.migrateProfiles(
                raw = legacy,
                activeName = prefs.getString(LEGACY_ACTIVE, "").orEmpty(),
                decrypt = { decrypt(it) }
            )
            prefs.edit().remove(LEGACY_PROFILES).remove(LEGACY_ACTIVE).apply()
            if (migrated != null) {
                save(context, migrated)
                return migrated
            }
        }

        // ③ 从没配过。
        return AiConfig(
            timeoutSeconds = readTimeout(context),
            retries = readRetries(context)
        )
    }

    /** 只判「配好了没」，不把 Key 读进内存给界面用。 */
    fun isConfigured(context: Context): Boolean = load(context).isConfigured

    // ── 写 ───────────────────────────────────────────────────────────────

    /**
     * 保存配置。返回是否真的写进去了。
     *
     * **不抛异常**：调用点在 Composable 的 onClick 里，抛出去就是闪退。
     * 加密失败（keystore 不可用/损坏）返回 false，由界面提示。
     */
    fun save(context: Context, config: AiConfig): Boolean {
        val encrypted = if (config.apiKey.isBlank()) "" else (encrypt(config.apiKey) ?: return false)
        prefs(context).edit()
            .putString(KEY_API_KEY, encrypted)
            .putString(KEY_MODEL, config.model.trim().ifEmpty { AiEndpoint.DEFAULT_MODEL })
            .putInt(KEY_TIMEOUT, AiConfigRules.clampTimeoutSeconds(config.timeoutSeconds))
            .putInt(KEY_RETRIES, AiConfigRules.clampRetries(config.retries))
            // 顺手清掉多档案时代的残留键：用户在这里保存过一次，就没有再回退到旧格式的理由。
            .remove(LEGACY_PROFILES)
            .remove(LEGACY_ACTIVE)
            .apply()
        // 缓存必须跟着更新，否则保存后同进程内仍然读回旧配置。
        cached = config.copy(
            model = AiConfigRules.normalizeModel(config.model),
            timeoutSeconds = AiConfigRules.clampTimeoutSeconds(config.timeoutSeconds),
            retries = AiConfigRules.clampRetries(config.retries)
        )
        return true
    }

    /** 清空配置（Key、模型、网络参数全部复位）。 */
    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
        cached = null
    }

    // ── 内部 ─────────────────────────────────────────────────────────────

    private fun readCurrent(context: Context): AiConfig {
        val prefs = prefs(context)
        return AiConfig(
            baseUrl = AiEndpoint.BASE_URL,
            // 解不开就按"没填 Key"处理，让用户重填 —— 整份配置丢掉会把模型与
            // 网络参数也一起清空，那是不必要的损失。
            apiKey = prefs.getString(KEY_API_KEY, null)?.takeIf { it.isNotBlank() }?.let { decrypt(it) }.orEmpty(),
            model = AiConfigRules.normalizeModel(prefs.getString(KEY_MODEL, null)),
            timeoutSeconds = readTimeout(context),
            retries = readRetries(context)
        )
    }

    private fun readTimeout(context: Context): Int =
        AiConfigRules.clampTimeoutSeconds(
            prefs(context).getInt(KEY_TIMEOUT, AiConfigRules.DEFAULT_TIMEOUT_SECONDS)
        )

    private fun readRetries(context: Context): Int =
        AiConfigRules.clampRetries(
            prefs(context).getInt(KEY_RETRIES, AiConfigRules.DEFAULT_RETRIES)
        )

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 加密。失败返回 null（不抛），原因只记 message，不打明文。 */
    private fun encrypt(plain: String): String? {
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, obtainKey())
            val iv = cipher.iv
            val out = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            // iv 与密文拼一起存，取回来时前 IV_LENGTH 字节是 iv。
            Base64.encodeToString(iv + out, Base64.NO_WRAP)
        } catch (e: Exception) {
            // 不打印明文，只记原因。
            Log.e(TAG, "encrypt failed: ${e.message}")
            null
        }
    }

    private fun decrypt(encoded: String): String? {
        return try {
            val raw = Base64.decode(encoded, Base64.NO_WRAP)
            if (raw.size <= IV_LENGTH) return null
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                obtainKey(),
                GCMParameterSpec(GCM_TAG_BITS, raw, 0, IV_LENGTH)
            )
            String(cipher.doFinal(raw, IV_LENGTH, raw.size - IV_LENGTH), Charsets.UTF_8)
        } catch (e: Exception) {
            // 典型场景：换机 / 系统密钥库被重置，旧密文解不开。
            Log.w(TAG, "decrypt failed (key will be treated as unset): ${e.message}")
            null
        }
    }

    private fun obtainKey(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }
}

/**
 * 配置里**不依赖 Android** 的那部分判据。
 *
 * 抽出来是为了能在 JVM 单测里直接跑：夹取范围、旧模型名改写、多档案迁移这三件事
 * 一旦写错，表现是"设置页填了 5 秒超时、实际还是 120 秒""老配置升级后请求被转到
 * 另一个模型计费""用户升级后 Key 消失"—— 都属于上真机也难察觉的类型。
 */
object AiConfigRules {

    const val DEFAULT_TIMEOUT_SECONDS = 120
    const val TIMEOUT_MIN = 10
    const val TIMEOUT_MAX = 600

    const val DEFAULT_RETRIES = 2
    const val RETRIES_MIN = 0
    const val RETRIES_MAX = 5

    /** 迁移时判定"这条旧档案是不是 DeepSeek 的"。用子串而非严格相等，兼容带尾斜杠/带路径的写法。 */
    private const val DEEPSEEK_HOST = "api.deepseek.com"

    /**
     * 已下线 / 已改名的旧模型名 → 现行名。
     *
     * 官方文档（2026-10）写明：旧名仍可调用，但**对应模型已下线**，请求会由
     * DeepSeek-V4.1-Flash 提供并按 Flash 价格计费。既然服务端反正会把它落到
     * Flash 上，不如在本地就改写成现行名 —— 否则界面上芯片全不选中，用户会以为
     * 配置坏了。统一落到 [AiEndpoint.DEFAULT_MODEL]，与文档描述的实际行为一致。
     */
    private val STALE_MODELS = mapOf(
        "deepseek-chat" to AiEndpoint.DEFAULT_MODEL,
        "deepseek-reasoner" to AiEndpoint.DEFAULT_MODEL,
        "deepseek-v4-flash" to AiEndpoint.DEFAULT_MODEL,
        "deepseek-v4-flash-vision-exp" to AiEndpoint.DEFAULT_MODEL
    )

    /** 超时秒数夹取；null 或越界 → 默认 120。 */
    fun clampTimeoutSeconds(value: Int?): Int =
        if (value != null && value in TIMEOUT_MIN..TIMEOUT_MAX) value else DEFAULT_TIMEOUT_SECONDS

    /** 额外重试次数夹取；null 或越界 → 默认 2。 */
    fun clampRetries(value: Int?): Int =
        if (value != null && value in RETRIES_MIN..RETRIES_MAX) value else DEFAULT_RETRIES

    /**
     * 模型名归一化：空白 → 默认；旧名 → 现行名；**其余原样保留**。
     *
     * 刻意不做"不在 [AiEndpoint.MODELS] 里就退回默认"：模型名会变，而设置页的
     * 「获取可用模型」会按用户自己的 Key 从服务端拉一份**列表外的**名字下来。
     * 若在读取时强行收敛到预置两项，用户从服务端选中的新模型名会在下一次进入
     * 设置页时被静默改回默认值 —— 那是"看着保存成功、其实没生效"的静默错误。
     */
    fun normalizeModel(raw: String?): String {
        val t = raw?.trim().orEmpty()
        if (t.isEmpty()) return AiEndpoint.DEFAULT_MODEL
        return STALE_MODELS[t] ?: t
    }

    /**
     * 中间那版「多档案」JSON → 一份 [AiConfig]。
     *
     * 结构为 `[{"name":…,"baseUrl":…,"model":…,"apiKey":"<密文>"}, …]`，外加一个
     * `active_profile` 记激活名。本版只支持 DeepSeek，因此：
     *  - 只考虑 `baseUrl` 含 `api.deepseek.com` 的档案 —— 把一条 OpenAI 的 Key
     *    迁过来、却指向 DeepSeek 端点，只会得到一个语义混乱的 401；
     *  - 候选里优先取激活名那条，否则取第一条；
     *  - Key 解不开（`decrypt` 返回 null）时保留模型与网络参数、Key 置空，
     *    让用户只重填 Key，而不是整份配置重来。
     *
     * @param decrypt 注入的解密函数（单测传恒等变换即可覆盖解析逻辑）。
     * @return 没有可迁移的 DeepSeek 档案时返回 null。
     */
    fun migrateProfiles(
        raw: String?,
        activeName: String,
        decrypt: (String) -> String?
    ): AiConfig? {
        if (raw.isNullOrBlank()) return null
        val arr = try {
            JSONArray(raw)
        } catch (e: Exception) {
            return null
        }
        var active: JSONObject? = null
        var first: JSONObject? = null
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (!o.optString("baseUrl").contains(DEEPSEEK_HOST)) continue
            if (first == null) first = o
            if (active == null && o.optString("name").trim() == activeName) active = o
        }
        val picked = active ?: first ?: return null
        val encrypted = picked.optString("apiKey")
        return AiConfig(
            baseUrl = AiEndpoint.BASE_URL,
            apiKey = if (encrypted.isBlank()) "" else (decrypt(encrypted) ?: ""),
            model = normalizeModel(picked.optString("model"))
        )
    }
}
