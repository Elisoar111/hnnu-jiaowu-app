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

    /** 淮南师范学院登录页：正方直登表单 /jwglxt/xtgl/login_slogin.html。 */
    fun loginUrl(school: SchoolConfig): String =
        school.fullBasePath.trimEnd('/') + "/xtgl/login_slogin.html"
}
