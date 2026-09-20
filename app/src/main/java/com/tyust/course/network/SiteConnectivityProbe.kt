package com.tyust.course.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/**
 * 登录页的「站点连通性」探测。
 *
 * 为什么要有它：主站（学校域名）与备用入口（IP 直连）中总有一个可能不通
 * ——校外访问、DNS 被投毒、学校只在内网开 http 入口……用户遇到的就是
 * "一直登录失败"，但看不出是密码错了、Cookie 过期了，还是**根本连不上**。
 * 这里给一个不需要抓包就能自证的按钮。
 *
 * 设计约束：
 * - **独立 OkHttpClient**，绝不复用 CourseApiClient。后者带 CookieJar 与一整套
 *   会话哨兵拦截器（缓存校验、龟速惩罚、Cookie 过期探测），探测请求若走那条链，
 *   一次"测试"就会污染真实会话状态。
 * - 不跟随重定向：教务站对未登录请求常回 302 到登录页，跟随后反而多一次往返。
 * - **任何 HTTP 状态码都算通**：404 / 403 / 302 都证明 TCP+TLS+DNS 这条链路是好的，
 *   只有 IOException 才算不通。
 * - 超时全局 8s（连接 / 读 / 整体），比业务请求短 —— 这是给用户点一下就能出结果的。
 */
object SiteConnectivityProbe {

    private const val TIMEOUT_MS = 8_000L

    /** 与业务请求一致的 UA：有些 WAF 对陌生 UA 直接掐连接，会把"通"误判成"不通"。 */
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Safari/537.36"

    data class Result(
        /** 展示名，如「主站」「备用入口」。 */
        val label: String,
        val url: String,
        val reachable: Boolean,
        val latencyMs: Long,
        /** 可达时是 HTTP 状态码，不可达时是失败原因（短句，可直接上屏）。 */
        val detail: String
    )

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .callTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    /**
     * 探测单个站点。**不抛异常**，一切失败都折叠成 [Result] 里的一句原因。
     *
     * @param path 探测路径，用登录页最有代表性（能通就说明登录流程的入口是活的）。
     */
    suspend fun probe(label: String, protocol: String, host: String, path: String): Result {
        val scheme = protocol.ifBlank { "https" }
        val url = "$scheme://$host$path"
        return withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "*/*")
                .get()
                .build()
            val startedAt = System.currentTimeMillis()
            try {
                client.newCall(request).execute().use { response ->
                    Result(
                        label = label,
                        url = url,
                        reachable = true,
                        latencyMs = System.currentTimeMillis() - startedAt,
                        detail = "HTTP ${response.code}"
                    )
                }
            } catch (e: IOException) {
                Result(
                    label = label,
                    url = url,
                    reachable = false,
                    latencyMs = System.currentTimeMillis() - startedAt,
                    detail = describe(e)
                )
            } catch (e: RuntimeException) {
                // OkHttp 对非法 URL / 被网络安全策略拦下的明文会抛运行时异常，
                // 探测期不能让它冒到 UI 线程去。
                Result(label, url, false, System.currentTimeMillis() - startedAt, describe(e))
            }
        }
    }

    private fun describe(error: Throwable): String {
        // 原因常在 cause 链里（OkHttp 会把 SSL / DNS 失败再包一层 IOException）
        var cause: Throwable? = error
        while (cause != null) {
            when (cause) {
                is UnknownHostException -> return "域名解析失败（DNS）"
                is SocketTimeoutException -> return "连接超时（${TIMEOUT_MS / 1000}s）"
                is SSLException -> return "HTTPS 证书校验未通过"
                is ConnectException -> return "无法建立连接"
            }
            cause = cause.cause
        }
        val message = error.message.orEmpty()
        return when {
            message.contains("CLEARTEXT", ignoreCase = true) -> "明文 HTTP 被本机安全策略拦截"
            message.isNotBlank() -> message.take(48)
            else -> error.javaClass.simpleName
        }
    }
}
