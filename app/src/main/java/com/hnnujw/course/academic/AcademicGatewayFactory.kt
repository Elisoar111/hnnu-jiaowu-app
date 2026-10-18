package com.hnnujw.course.academic

import com.hnnujw.course.model.SchoolConfig
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 教务适配器工厂。
 *
 * 本应用只支持淮南师范学院（正方 jwglxt V9），因此不再需要多教务系统探测：
 * 适配器固定为 [ZfAcademicAdapter]，登录入口固定为正方直登页。
 */
object AcademicGatewayFactory {
    private val sessions = AcademicSessionStore()

    /** 恒为 true：淮南师范学院始终走适配器流程。 */
    fun supports(@Suppress("UNUSED_PARAMETER") school: SchoolConfig): Boolean = true

    /** 恒为 true：适配器固定为正方，不存在“未选择”的中间态。 */
    fun hasSelectedAdapter(@Suppress("UNUSED_PARAMETER") school: SchoolConfig): Boolean = true

    fun create(school: SchoolConfig, accountStorageKey: String): AcademicProtocolAdapter {
        val session = sessions.session(school.id, accountStorageKey, school.getFullBasePath())
        val transport = AcademicHttpTransport(school, session)
        return ZfAcademicAdapter(school, session, transport)
    }

    fun createStudy(school: SchoolConfig, accountStorageKey: String): AcademicStudyAdapter {
        val session = sessions.session(school.id, accountStorageKey, school.fullBasePath)
        return AcademicStudyReader(school, session, AcademicHttpTransport(school, session))
    }

    fun invalidate(school: SchoolConfig, accountStorageKey: String) {
        sessions.invalidate(school.id, accountStorageKey)
    }

    /**
     * 复用当前账号已有的教务会话（同一 baseUrl 下返回同一个 [AcademicSession]，因此共享 Cookie），
     * 供消息中心等"在已登录会话上做额外请求"的场景使用，无需重新登录。
     */
    fun transportFor(school: SchoolConfig, accountStorageKey: String): AcademicHttpTransport {
        val session = sessions.session(school.id, accountStorageKey, school.getFullBasePath())
        return AcademicHttpTransport(school, session)
    }

    /** Import the configured school's Cookie header from the login browser. */
    fun importCookie(school: SchoolConfig, accountStorageKey: String, header: String, replace: Boolean = true, username: String = "") {
        val session = if (replace) sessions.replace(school.id, accountStorageKey, school.fullBasePath)
            else sessions.session(school.id, accountStorageKey, school.fullBasePath)
        if (username.isNotBlank()) session.username = username
        if (!replace && session.cookieHeader().isNotBlank()) return
        val url = (school.getFullBasePath().trimEnd('/') + "/").toHttpUrlOrNull() ?: return
        val parsed = header.split(';').mapNotNull { part ->
            val separator = part.indexOf('=')
            if (separator <= 0) return@mapNotNull null
            runCatching { Cookie.Builder().name(part.substring(0, separator).trim())
                .value(part.substring(separator + 1).trim()).hostOnlyDomain(url.host).path(url.encodedPath)
                .apply { if (url.isHttps) secure() }.build() }.getOrNull()
        }
        session.cookies.saveFromResponse(url, parsed)
    }

    fun accountKey(school: SchoolConfig, username: String): String =
        (school.id + "::" + username.trim()).replace(Regex("[^A-Za-z0-9_.-]"), "_")

    /**
     * 读当前内存会话**正在用**的 Cookie 头（回写成持久化用，见 AcademicCookieSync）。
     *
     * 取不到会话（例如进程刚起、还没 importCookie）时返回空串，**不会顺手新建一个**：
     * `session(...)` 本身会建会话，在"只是想读一下"的路径上调用它等于制造一个空会话，
     * 后续请求会拿它发出去、全部 401。
     */
    fun cookieHeaderFor(school: SchoolConfig, accountStorageKey: String): String {
        val key = AcademicSessionKey(school.id, accountStorageKey)
        val session = sessions.snapshot(key) ?: return ""
        if (session.retired || session.baseUrl != school.getFullBasePath()) return ""
        return session.cookieHeader()
    }

    /** 淮南师范学院登录页：正方直登表单 /jwglxt/xtgl/login_slogin.html。 */
    fun loginUrl(school: SchoolConfig): String =
        school.fullBasePath.trimEnd('/') + "/xtgl/login_slogin.html"
}
