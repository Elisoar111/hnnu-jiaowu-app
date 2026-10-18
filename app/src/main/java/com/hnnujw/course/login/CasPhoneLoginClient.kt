package com.hnnujw.course.login

import android.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 淮南师范学院统一身份认证（xxmh.hnnu.edu.cn CAS）手机号 + 短信验证码登录的原生实现。
 *
 * 复刻网页端 casLoginView.js 的完整链路（AES 密钥/IV 写死在学校前端 JS 里，属公共知识）：
 *  1. GET /cas/login?service=jwglxt/sso/hsssologin —— 拿 execution、SESSION Cookie；
 *  2. POST /cas/user/sendVerificationCode（receiver=AES(手机号), msgType=login）下发短信；
 *  3. POST /cas/phoneLogin（phone=AES(手机号), code=验证码）→ 成功返回 "账号-密码-一次性登录码"；
 *  4. 把三者连同 execution、_eventId=submit POST 回 /cas/login（带 service）
 *     → CAS 302 到教务 SSO 建立会话；
 *  5. 合并 jwgl/jwglxt 两个域名的 Cookie（主站优先、SSO 域补缺）交给既有登录验证流程。
 */
object CasPhoneLoginClient {

    private const val CAS_BASE = "https://xxmh.hnnu.edu.cn/cas/"
    private const val CAS_LOGIN_URL =
        "${CAS_BASE}login?service=https://jwglxt.hnnu.edu.cn/sso/hsssologin&qq_aio_chat_type=2"
    private const val ACADEMIC_HOST = "jwgl.hnnu.edu.cn"
    private const val SSO_HOST = "jwglxt.hnnu.edu.cn"
    private const val UA = "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36"

    /** 学校前端写死的 AES 参数（casLoginView.js / aesEncrypt.js）。 */
    private const val AES_KEY = "1234567890adbcde"
    private const val AES_IV = "1234567890hjlkew"

    data class CasResult(val success: Boolean, val message: String)

    private val cookieStore = LinkedHashMap<String, MutableList<Cookie>>()

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .followRedirects(true)
        .cookieJar(object : CookieJar {
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                synchronized(cookieStore) {
                    val list = cookieStore.getOrPut(url.host) { mutableListOf() }
                    for (cookie in cookies) {
                        list.removeAll { it.name == cookie.name && it.path == cookie.path }
                        list.add(cookie)
                    }
                }
            }

            override fun loadForRequest(url: HttpUrl): List<Cookie> = synchronized(cookieStore) {
                cookieStore[url.host].orEmpty().filter { it.matches(url) }
            }
        })
        .build()

    private var execution = ""
    private var sessionValue = ""

    /**
     * 保护 [execution] / [sessionValue] / [sessionConsumed] / [cookieStore] 这几项
     * 互相耦合的可变状态。
     *
     * 为什么必须加：`sendVerificationCode` 与 `loginWithPhoneCode` 都在
     * `Dispatchers.IO` 上跑，是两个独立的网络调用。UI 上虽然用按钮禁用来防重入，
     * 但那只是**时序上的运气** —— 用户切走再切回、或两处入口同时触发时，
     * 两个协程会真的并发进入 [ensureSession]：
     *
     *  - 两个都判定"需要新会话"，于是各 `reset()` 一次、各拉一次登录页；
     *  - 后完成的那个把 `execution` 覆盖掉，先拿到的验证码就绑在了作废的会话上；
     *  - 更糟的是 CAS 的 `execution` 一次性，混用会让服务端把两次登录串成同一个。
     *
     * 用一把普通锁（而不是协程 Mutex）是有意的：临界区里是**阻塞式** `execute()`
     * 网络调用，本来就跑在 IO 线程上，用 `synchronized` 语义最直接，
     * 也避免了"在 suspend 函数里持 Mutex 做阻塞调用"那种容易写出死锁的写法。
     */
    private val sessionLock = Any()

    /**
     * 当前 execution 是否已被提交消费过（初值 true = 还没有任何可用会话，需要新开一次）。
     *
     * CAS 的 execution 是一次性的：提交回 /cas/login 之后它已经作废，
     * 若下一次登录继续复用同一个 execution 与同一个 SESSION Cookie，
     * 就会把上一个账号的会话（Cookie）带进这一次登录 —— 表现为换账号后
     * 手机验证码登录失败，或直接登进上一个账号。所以提交后必须标记为已消费，
     * 下一次 [ensureSession] 会重新拉登录页并清掉旧 Cookie。
     */
    @Volatile
    private var sessionConsumed = true

    /** 丢弃当前 CAS 会话：清空全部 Cookie 与 execution，下一次 [ensureSession] 重新开局。 */
    fun reset() {
        synchronized(sessionLock) {
            synchronized(cookieStore) { cookieStore.clear() }
            execution = ""
            sessionValue = ""
            sessionConsumed = true
        }
    }

    /**
     * 在一次 CAS 操作期间独占会话。
     *
     * 把「读/建会话 → 用它发请求 → 按结果标记消费」整段放进同一个临界区，
     * 才能真正避免两次登录交错。只锁 [ensureSession] 是不够的：
     * 拿到会话之后到发出请求之间还有窗口，两次操作仍可能拿到同一个 execution 各提交一次。
     */
    private fun <T> withSession(block: () -> T): T = synchronized(sessionLock) { block() }

    /** 发送短信验证码。 */
    suspend fun sendVerificationCode(phone: String): CasResult = withContext(Dispatchers.IO) {
        try {
            val form = FormBody.Builder()
                .add("type", "1")
                .add("receiver", aesEncrypt(phone.trim()))
                .add("msgType", "login")
                .build()
            val json = withSession {
                ensureSession()
                postJson("user/sendVerificationCode", form)
            }
            CasResult(json.optBoolean("success", false), json.optString("message", ""))
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            CasResult(false, "发送验证码失败：${t.message ?: "网络异常"}")
        }
    }

    /**
     * 手机号 + 验证码登录。成功后本会话里已持有教务会话 Cookie，
     * 用 [academicCookieHeader] 取出交给 [com.hnnujw.course.LoginActivity] 的验证流程。
     */
    suspend fun loginWithPhoneCode(phone: String, code: String): CasResult = withContext(Dispatchers.IO) {
        try {
            // 整段（建会话 → 校验验证码 → 提交回 /cas/login）在同一个临界区里跑：
            // 中途放开锁的话，另一个登录协程会插进来 reset() 掉 execution，
            // 这边提交回去的就是一个已作废的一次性凭据。
            withSession { loginWithPhoneCodeLocked(phone, code) }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            CasResult(false, "登录失败：${t.message ?: "网络异常"}")
        }
    }

    /**
     * [loginWithPhoneCode] 的实际流程，**调用方必须持有 [sessionLock]**。
     *
     * 拆成独立函数是因为原流程里有三处 `return@withContext`：把它塞进
     * `withSession { }` 的 lambda 里，那些非局部返回会直接从 lambda 跳出，
     * 语义会变（要么编译不过，要么提前放锁），所以改成普通函数 + 普通 return。
     */
    private fun loginWithPhoneCodeLocked(phone: String, code: String): CasResult {
        ensureSession()
        val encPhone = aesEncrypt(phone.trim())
        val loginJson = postJson(
            "phoneLogin",
            FormBody.Builder().add("phone", encPhone).add("code", code.trim()).build()
        )
        // 失败响应可能没有 success 字段（如「该手机号未绑定」）
        if (!loginJson.optBoolean("success", false)) {
            return CasResult(false, loginJson.optString("message", "验证码校验未通过"))
        }
        val message = loginJson.optString("message", "")
        // 服务端格式是「账号-密码-一次性登录码」，账号/密码本身可能含 '-'，所以只切前两刀
        val parts = message.split("-", limit = 3)
        if (parts.size < 3) {
            return CasResult(false, "统一身份认证返回格式异常，请改用密码登录")
        }
        // 与网页 verLogin 一致：把服务端返回的账号/密码/一次性登录码原样提交回 /cas/login
        val form = FormBody.Builder()
            .add("key", "")
            .add("username", parts[0])
            .add("password", parts[1])
            .add("captcha", parts[2])
            .add("captchaInput", "")
            .add("execution", execution)
            .add("_eventId", "submit")
            .add("geolocation", "")
            .build()
        // 提交即消费：这一刀之后 execution 作废，下次登录必须重开一次会话
        sessionConsumed = true
        client.newCall(
            Request.Builder().url(CAS_LOGIN_URL)
                .header("User-Agent", UA)
                .header("Referer", CAS_LOGIN_URL)
                .post(form)
                .build()
        ).execute().use { resp ->
            val finalUrl = resp.request.url.toString()
            val body = resp.body?.string().orEmpty()
            // CAS 拒绝时会回到登录页本身；成功则 302 落在教务 SSO 域
            if (finalUrl.contains("/cas/login") || body.contains("name=\"execution\"")) {
                return CasResult(false, "统一身份认证登录未完成，请稍后重试或改用密码登录")
            }
        }
        return CasResult(true, "登录成功")
    }

    /** 取合并后的 Cookie 头：教务主站优先，SSO 域名（jwglxt）只补缺。 */
    fun academicCookieHeader(): String = synchronized(cookieStore) {
        val merged = linkedMapOf<String, String>()
        for (host in listOf(ACADEMIC_HOST, SSO_HOST)) {
            cookieStore[host].orEmpty().forEach { cookie ->
                if (!merged.containsKey(cookie.name)) merged[cookie.name] = "${cookie.name}=${cookie.value}"
            }
        }
        merged.values.joinToString("; ")
    }

    /** 请求登录页：拿 execution 与 SESSION Cookie（页面渲染时服务器已 Set-Cookie）。 */
    private fun ensureSession() {
        // 同一轮 发短信 → 提交验证码 之间要复用同一会话（验证码绑定在 SESSION 上），
        // 但已经提交消费过的会话绝不能再用。
        if (!sessionConsumed && execution.isNotBlank()) return
        reset()
        client.newCall(
            Request.Builder().url(CAS_LOGIN_URL).header("User-Agent", UA).get().build()
        ).execute().use { resp ->
            val html = resp.body?.string().orEmpty()
            execution = Regex("""name="execution"[^>]*value="([^"]+)"""").find(html)?.groupValues?.get(1).orEmpty()
            sessionValue = Regex("""id\s*=\s*"sessionValue"\s*>\s*([^<\s]+)""").find(html)?.groupValues?.get(1).orEmpty()
        }
        if (execution.isBlank()) throw IllegalStateException("统一身份认证页面获取失败")
        sessionConsumed = false
        // 网页端会把页面里的 UUID 写进 SESSION Cookie；Set-Cookie 已带时是同一个值，缺失时补上
        if (sessionValue.isNotBlank()) {
            setCookie(CAS_BASE.toHttpUrl(), "SESSION", sessionValue)
        }
    }

    private fun postJson(path: String, form: FormBody): JSONObject {
        client.newCall(
            Request.Builder().url(CAS_BASE + path)
                .header("User-Agent", UA)
                .header("Referer", CAS_LOGIN_URL)
                .header("X-Requested-With", "XMLHttpRequest")
                .post(form)
                .build()
        ).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            return JSONObject(text.ifBlank { "{}" })
        }
    }

    private fun setCookie(url: HttpUrl, name: String, value: String) {
        val cookie = Cookie.Builder().name(name).value(value).hostOnlyDomain(url.host).path("/").build()
        synchronized(cookieStore) {
            val list = cookieStore.getOrPut(url.host) { mutableListOf() }
            list.removeAll { it.name == name }
            list.add(cookie)
        }
    }

    /** AES-128-CBC + ZeroPadding → Base64，与学校前端 aesEncrypt.js 的 encryption() 一致。 */
    private fun aesEncrypt(plain: String): String {
        val data = plain.toByteArray(Charsets.UTF_8)
        val padded = data + ByteArray((16 - data.size % 16) % 16)
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(AES_KEY.toByteArray(Charsets.UTF_8), "AES"),
            IvParameterSpec(AES_IV.toByteArray(Charsets.UTF_8))
        )
        return Base64.encodeToString(cipher.doFinal(padded), Base64.NO_WRAP)
    }
}
