package com.tyust.course.login

import com.tyust.course.model.SchoolConfig

interface PasswordLoginGateway {
    fun login(
        school: SchoolConfig,
        username: String,
        password: String,
        callback: PasswordLoginCallback
    )

    fun submitCaptcha(captchaCode: String, callback: PasswordLoginCallback)

    fun refreshCaptcha(callback: (ByteArray?) -> Unit)

    fun clearSensitiveState()
}

object PasswordLoginGatewayFactory {
    // 本应用只支持淮南师范学院（正方 jwglxt），密码登录固定走教务适配器的直登实现。
    fun create(school: SchoolConfig): PasswordLoginGateway =
        if (com.tyust.course.academic.AcademicGatewayFactory.supports(school))
            com.tyust.course.academic.AcademicPasswordLoginGateway(school)
        else PasswordLoginManager()
}
