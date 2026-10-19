package com.hnnujw.course.academic

import com.hnnujw.course.model.SchoolConfig

enum class AcademicSystem(val id: String, val serial: Boolean) {
    // 本应用只支持淮南师范学院（正方 jwglxt V9）直登表单，不提供其它教务类型。
    ZF("zf", false);

    companion object { fun fromId(id: String?): AcademicSystem? = values().firstOrNull { it.id == id } }
}

class Credentials(val username: String, val password: String) {
    override fun toString() = "Credentials(redacted)"
}

enum class AcademicStatus {
    SUCCESS, CAPTCHA_REQUIRED, HUMAN_VERIFICATION_REQUIRED, INVALID_CREDENTIALS,
    SESSION_EXPIRED, ROUND_CLOSED, NO_CAPACITY, CONFLICT, ALREADY_SELECTED,
    PAGE_CHANGED, NETWORK_RETRYABLE, RESULT_UNKNOWN, UNSUPPORTED, UNTRUSTED_URL,
    VALIDATION_FAILED, CREDIT_LIMIT
}

data class LoginResult(
    val status: AcademicStatus,
    val studentName: String = "",
    val studentId: String = "",
    val message: String = "",
    val captcha: CaptchaChallenge? = null
)

data class CaptchaChallenge(val image: ByteArray, val fieldName: String, val refreshUrl: String?)

data class AcademicResponse(val code: Int, val url: String, val text: String, val headers: Map<String, List<String>> = emptyMap())

class AcademicException(val status: AcademicStatus, message: String, cause: Throwable? = null) : Exception(message, cause)

data class AcademicSessionKey(val schoolId: String, val accountKey: String)

/**
 * 教务适配器只保留**登录**相关能力。
 *
 * 选课（`loadCourseContext` / `listCourses` / `listSections` / `select` / `selected` / `drop`）
 * 连同 `CourseQuery` / `CourseContext` / `CourseScope` / `CourseOffer` / `CourseSection` /
 * `SelectionTarget` / `SelectedCourse` / `SelectionResult` / `OperationResult` 一并删除
 * （2026-10-19）：那套协议层写完后从未接过界面，也没有任何测试覆盖，
 * 属于"引擎在、功能不在"的死代码。留着它会让人误以为应用有选课能力，
 * 也拖着一整条只服务它的写请求通道（[AcademicHttpTransport] 的 write 分支）。
 *
 * 若日后真要接选课：按当时学校页面的实际表单重写解析与提交，
 * 不要指望这版被删掉的代码还能直接用（它依赖的 DOM 选择器与端点早已无从验证）。
 */
interface AcademicProtocolAdapter {
    suspend fun login(credentials: Credentials): LoginResult
    suspend fun validateSession(): LoginResult
}

internal fun SchoolConfig.academicType(): AcademicSystem = AcademicSystem.fromId(academicSystem)
    ?: AcademicSystem.ZF
