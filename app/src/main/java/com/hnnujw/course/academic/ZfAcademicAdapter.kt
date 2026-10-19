package com.hnnujw.course.academic

import com.hnnujw.course.model.SchoolConfig
import org.jsoup.Jsoup
import org.json.JSONObject

/**
 * 正方教务（jwglxt V9）适配器。
 *
 * 本应用只服务淮南师范学院：该校使用直登表单 + RSA 密码加密，没有统一认证入口，
 * 因此这里只实现直登表单登录。
 */
internal class ZfAcademicAdapter(school: SchoolConfig, session: AcademicSession, transport: AcademicHttpTransport) :
    BaseAcademicAdapter(school, session, transport, AcademicSystem.ZF), AcademicCaptchaLogin {

    override suspend fun login(credentials: Credentials): LoginResult = serial {
        clearLoginState()
        session.invalidate()
        session.username = credentials.username
        loginViaForm(credentials)
    }

    // ---- 直登表单登录 ----

    private suspend fun loginViaForm(credentials: Credentials): LoginResult = serial {
        session.username = credentials.username
        val page = transport.get(transport.appUrl("xtgl/login_slogin.html?time=${System.currentTimeMillis()}"))
        val document = Jsoup.parse(page.text, page.url)
        val hidden = AcademicHtml.hiddenFields(document).toMutableMap()
        if (hidden["csrftoken"].isNullOrBlank()) return@serial LoginResult(AcademicStatus.PAGE_CHANGED, message = "Missing csrftoken")
        val captchaImage = document.select("img").firstOrNull { it.attr("src").contains("yzm", true) || it.attr("src").contains("captcha", true) }
        if (captchaImage != null) return@serial LoginResult(AcademicStatus.HUMAN_VERIFICATION_REQUIRED, message = "Complete the school's captcha in WebView")
        val password = if (hidden["mmsfjm"] == "1") {
            val key = transport.get(transport.appUrl("xtgl/login_getPublicKey.html?time=${System.currentTimeMillis()}&_=${System.currentTimeMillis()}"))
            val json = JSONObject(key.text)
            AcademicCrypto.rsaBase64(json.optString("modulus"), json.optString("exponent"), credentials.password)
        } else credentials.password
        hidden["yhm"] = credentials.username
        hidden["mm"] = password
        hidden["language"] = hidden["language"].orEmpty().ifBlank { "zh_CN" }
        val result = transport.postForm(transport.appUrl("xtgl/login_slogin.html"), hidden.toList(), page.url)
        transport.get(transport.appUrl("xtgl/index_initMenu.html"))
        val identity = validateIdentity()
        when {
            identity != null -> LoginResult(AcademicStatus.SUCCESS, identity.first, identity.second)
            result.text.contains("密码错误") || result.text.contains("用户名或密码") -> LoginResult(AcademicStatus.INVALID_CREDENTIALS, message = "Invalid credentials")
            else -> LoginResult(AcademicStatus.VALIDATION_FAILED, message = "Login response could not be verified")
        }
    }

    // 直登表单在需要人机验证时会要求走 WebView，不存在应用内验证码会话。
    override suspend fun submitCaptcha(code: String): LoginResult = serial {
        LoginResult(AcademicStatus.SESSION_EXPIRED, message = "登录会话已失效，请重新登录")
    }

    override suspend fun refreshCaptcha(): CaptchaChallenge? = serial { null }

    override fun clearLoginState() {
    }

    private suspend fun validateIdentity(): Pair<String, String>? {
        val response = transport.get(transport.appUrl("xtgl/index_cxYhxxIndex.html?gnmkdm=index"))
        var identity = parseName(Jsoup.parse(response.text, response.url))
        if (identity.second.isBlank()) identity = zfMenuIdentityFallback(identity)
        return identity.takeIf { it.first.isNotBlank() || it.second.isNotBlank() }
    }


    // 选课相关方法（loadCourseContext / listCourses / listSections / select /
    // selected / drop）连同其专用解析与写请求通道一并删除（2026-10-19）。
    // 删除理由详见 AcademicModels.kt 里 AcademicProtocolAdapter 的注释：
    // 这套协议层从未接入界面、零测试覆盖，属于死代码。
}
