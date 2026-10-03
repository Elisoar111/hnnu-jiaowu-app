package com.hnnujw.course.review

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AI API Key 的本地加密存储。
 *
 * 为什么不用 [com.hnnujw.course.manager.CredentialStore]：那个是**教务账号密码**专用
 * （键名带 `pw_` 前缀、绑 accountKey、还牵连登录态清理），把第三方 AI key 混进去
 * 会让两套生命周期纠缠在一起。算法一致（AES/GCM + AndroidKeyStore），各存各的。
 *
 * 隐私红线：
 *  - 密文落 SharedPreferences，密钥只存在于 AndroidKeyStore（应用沙盒内，不可导出）。
 *  - **key 绝不打日志**（哪怕打的是长度以外的任何片段），异常只记 message。
 *  - 解密失败（常见于换机/系统密钥重置）返回 null，让用户重填，不抛给上层。
 */
object AiKeyStore {
    private const val TAG = "AiKeyStore"
    private const val PREFS = "review_ai_prefs"
    private const val KEY_API = "api_key"
    private const val KEY_MODEL = "model"
    private const val KEY_ALIAS = "zf_review_ai_v1"
    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_LENGTH = 12
    private const val GCM_TAG_BITS = 128

    /** 默认模型。DeepSeek 官方 Chat API 的模型名。 */
    const val DEFAULT_MODEL = "deepseek-chat"

    /**
     * 保存 API Key。返回是否真的写进去了。
     *
     * **不抛异常**：调用点在 Composable 的 onClick 里，抛出去就是闪退。
     * 原来 [encrypt] 失败时 `throw IllegalStateException`，而 AiSettingsScreen
     * 的「保存」按钮没有 runCatching —— 一台 keystore 不可用/已损坏的机器上
     * 点保存必崩。改成与 [decrypt] 一样的「失败返回空值，由界面提示」口径。
     */
    fun saveApiKey(context: Context, value: String): Boolean {
        val v = value.trim()
        if (v.isEmpty()) {
            removeApiKey(context)
            return true
        }
        val cipherText = encrypt(v) ?: return false
        prefs(context).edit().putString(KEY_API, cipherText).apply()
        return true
    }

    fun apiKey(context: Context): String? = prefs(context).getString(KEY_API, null)?.let { decrypt(it) }

    fun hasApiKey(context: Context): Boolean = !apiKey(context).isNullOrBlank()

    fun removeApiKey(context: Context) {
        prefs(context).edit().remove(KEY_API).apply()
    }

    fun model(context: Context): String =
        prefs(context).getString(KEY_MODEL, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_MODEL

    fun setModel(context: Context, value: String) {
        val v = value.trim().ifBlank { DEFAULT_MODEL }
        prefs(context).edit().putString(KEY_MODEL, v).apply()
    }

    fun clearAll(context: Context) {
        prefs(context).edit().clear().apply()
    }

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
