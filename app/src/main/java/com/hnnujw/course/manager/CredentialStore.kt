package com.hnnujw.course.manager

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
 * 账号密码的本地加密存储。
 *
 * 为什么需要它：密码原先只活在 [UserManager] 的内存里（`sessionPasswords`），
 * 进程一死就没了。于是冷启动后 `canAutoRelogin()` 恒为 false，正方的 JSESSIONID
 * 在服务端超时之后没人能悄悄换一张新的 —— 用户看到的就是「登录已过期」横幅，
 * 以及「我的」页「更新 Cookie」报的"没有保存密码"。
 *
 * 实现约束：
 * - **不引新依赖**。用系统自带的 AndroidKeyStore（AES/GCM，API 23 起可用，
 *   本工程 minSdk 24），密钥本身不出 Keystore；prefs 里只有密文。
 * - **一切失败都降级为"没有凭据"**。密钥可能被系统失效（用户改锁屏、恢复出厂、
 *   换设备恢复备份），此时解密抛 `AEADBadTagException`/`KeyPermanentlyInvalidatedException`。
 *   这些都不能往上抛：读密码的调用点在启动路径与网络回调里，抛出去就是崩溃。
 *   一律记日志 + 删掉这条坏数据 + 返回 null，让上层走"请重新登录"。
 */
object CredentialStore {

    private const val TAG = "CredentialStore"

    /** 独立文件，不和 course_selector_prefs 混：那里全是明文业务状态。 */
    private const val PREFS_NAME = "secure_credentials"
    private const val KEY_ALIAS = "zf_account_credential_v1"
    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val IV_LENGTH = 12

    fun save(context: Context, accountKey: String, password: String) {
        if (accountKey.isBlank()) return
        if (password.isEmpty()) {
            remove(context, accountKey)
            return
        }
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, obtainKey())
            val iv = cipher.iv
            val cipherText = cipher.doFinal(password.toByteArray(Charsets.UTF_8))
            // iv 长度固定 12 字节，直接拼在密文前面，读的时候按长度切开
            val payload = ByteArray(iv.size + cipherText.size)
            System.arraycopy(iv, 0, payload, 0, iv.size)
            System.arraycopy(cipherText, 0, payload, iv.size, cipherText.size)
            prefs(context).edit()
                .putString(prefKey(accountKey), Base64.encodeToString(payload, Base64.NO_WRAP))
                .apply()
        } catch (e: Exception) {
            Log.w(TAG, "保存凭据失败: ${e.javaClass.simpleName} ${e.message}")
            remove(context, accountKey)
        }
    }

    /** 读不到、解不开、密钥失效，统一返回 null（并顺手清掉坏数据）。 */
    fun load(context: Context, accountKey: String): String? {
        if (accountKey.isBlank()) return null
        val stored = prefs(context).getString(prefKey(accountKey), null) ?: return null
        return try {
            val payload = Base64.decode(stored, Base64.NO_WRAP)
            if (payload.size <= IV_LENGTH) {
                remove(context, accountKey)
                return null
            }
            val iv = payload.copyOfRange(0, IV_LENGTH)
            val cipherText = payload.copyOfRange(IV_LENGTH, payload.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, obtainKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(cipherText), Charsets.UTF_8).takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            // 密钥被系统失效 / 密文被篡改 / 换了设备恢复备份：这条已经没救了
            Log.w(TAG, "读取凭据失败，按未保存处理: ${e.javaClass.simpleName} ${e.message}")
            remove(context, accountKey)
            null
        }
    }

    fun has(context: Context, accountKey: String): Boolean =
        !load(context, accountKey).isNullOrEmpty()

    fun remove(context: Context, accountKey: String) {
        if (accountKey.isBlank()) return
        try {
            prefs(context).edit().remove(prefKey(accountKey)).apply()
        } catch (e: Exception) {
            Log.w(TAG, "删除凭据失败: ${e.message}")
        }
    }

    // —— 会话 Cookie ——
    //
    // 为什么 Cookie 也要加密：它是**等同于登录态**的凭据。拿着 `JSESSIONID` 的人
    // 可以完整读取学号、成绩、课表、一卡通余额，不需要任何密码。明文放在
    // `course_selector_prefs` 里，root 设备或本地备份都能直接拿走。
    //
    // 复用同一个 Keystore key（不新开别名）：key 的寿命与失效语义一致
    // （用户改锁屏/恢复出厂会让它失效），多开一个别名只会多一份要处理的失效路径。
    // 用不同的 prefKey 前缀把两种凭据分开存，互不影响。

    /**
     * 保存会话 Cookie（加密）。
     *
     * @return 是否写入成功。失败时**不会**保留明文副本 —— 宁可让用户重新登录，
     *   也不要把一张可用的票明文落在磁盘上。
     */
    fun saveSessionCookie(context: Context, accountKey: String, cookie: String): Boolean {
        if (accountKey.isBlank()) return false
        if (cookie.isEmpty()) {
            removeSessionCookie(context, accountKey)
            return true
        }
        return try {
            prefs(context).edit()
                .putString(cookieKey(accountKey), encrypt(cookie))
                .commit()   // 用 commit：调用点在退出/切号路径上，要保证写完
        } catch (e: Exception) {
            Log.w(TAG, "保存会话 Cookie 失败: ${e.javaClass.simpleName} ${e.message}")
            removeSessionCookie(context, accountKey)
            false
        }
    }

    /** 读取会话 Cookie；解不开就返回 null（并按"未登录"处理）。 */
    fun loadSessionCookie(context: Context, accountKey: String): String? {
        if (accountKey.isBlank()) return null
        val stored = prefs(context).getString(cookieKey(accountKey), null) ?: return null
        return decrypt(stored) ?: run {
            removeSessionCookie(context, accountKey)
            null
        }
    }

    fun removeSessionCookie(context: Context, accountKey: String) {
        if (accountKey.isBlank()) return
        try {
            prefs(context).edit().remove(cookieKey(accountKey)).apply()
        } catch (e: Exception) {
            Log.w(TAG, "删除会话 Cookie 失败: ${e.message}")
        }
    }

    /**
     * 老版本明文 Cookie 的迁移：把 `course_selector_prefs` 里的 `saved_cookie`
     * 加密搬进本文件，并删掉明文。
     *
     * 由 [UserManager] 在初始化读到明文时调用。幂等：明文没了就什么也不做。
     *
     * @return 迁移后应当使用的 Cookie（明文优先，其次已加密的那份；都没有返回空串）。
     */
    fun migrateLegacyPlainCookie(context: Context, accountKey: String, plain: String?): String {
        if (accountKey.isBlank()) return ""
        if (!plain.isNullOrEmpty()) {
            // 明文还在：加密存一份，然后由调用方删掉明文那一行。
            saveSessionCookie(context, accountKey, plain)
            return plain
        }
        return loadSessionCookie(context, accountKey).orEmpty()
    }

    /** 加密并 Base64 成可入 prefs 的字符串。 */
    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, obtainKey())
        val iv = cipher.iv
        val cipherText = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val payload = ByteArray(iv.size + cipherText.size)
        System.arraycopy(iv, 0, payload, 0, iv.size)
        System.arraycopy(cipherText, 0, payload, iv.size, cipherText.size)
        return Base64.encodeToString(payload, Base64.NO_WRAP)
    }

    /** 解密；任何异常都返回 null（坏数据由调用方清掉）。 */
    private fun decrypt(stored: String): String? = try {
        val payload = Base64.decode(stored, Base64.NO_WRAP)
        if (payload.size <= IV_LENGTH) null
        else {
            val iv = payload.copyOfRange(0, IV_LENGTH)
            val cipherText = payload.copyOfRange(IV_LENGTH, payload.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, obtainKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(cipherText), Charsets.UTF_8).takeIf { it.isNotEmpty() }
        }
    } catch (e: Exception) {
        Log.w(TAG, "解密失败，按无数据处理: ${e.javaClass.simpleName} ${e.message}")
        null
    }

    fun clearAll(context: Context) {
        try {
            prefs(context).edit().clear().apply()
        } catch (e: Exception) {
            Log.w(TAG, "清空凭据失败: ${e.message}")
        }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * 账号 key 形如 `hnnu::20xxxxxx`，也可能落到中文姓名上。
     * 直接当文件内的键名不稳妥（分隔符、大小写、编码），统一哈希成定长十六进制。
     */
    private fun prefKey(accountKey: String): String = "pw_" + sha256Hex(accountKey)

    /** Cookie 用独立前缀，和密码分开存，互不覆盖。 */
    private fun cookieKey(accountKey: String): String = "ck_" + sha256Hex(accountKey)

    private fun sha256Hex(value: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            sb.append(String.format("%02x", b))
        }
        return sb.toString()
    }

    private fun obtainKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let {
            return it.secretKey
        }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // GCM 的 IV 绝不能复用，交给系统生成
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }
}
