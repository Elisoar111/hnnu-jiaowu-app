package com.hnnujw.course.examreg

import com.hnnujw.course.academic.AcademicException
import com.hnnujw.course.academic.AcademicHtml
import com.hnnujw.course.academic.AcademicHttpTransport
import com.hnnujw.course.academic.AcademicStatus
import com.hnnujw.course.manager.UserManager
import com.hnnujw.course.model.SchoolConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * 教务系统「考级项目报名」（kjgl/kjbm_*，gnmkdm=N2510）客户端。
 *
 * 全部走 [AcademicHttpTransport] 的已登录会话（与课表/成绩共用 Cookie），
 * 会话失效以「响应是登录页」判定 —— 正方对未登录的 XHR 一律 200 回登录页。
 *
 * ## 测试账号保护（硬性要求）
 *
 * [TEST_ACCOUNTS] 里的账号（当前只有 2505050111）**永远不会**发出报名/退报
 * 这类写请求：[submitRegistration] / [withdrawRegistration] 在入口处直接返回
 * 模拟结果，连前置检查 POST 也不发。读接口（入口页 / 已报名列表 / 报名表单页）
 * 不受限 —— 它们是幂等 GET，与网页浏览等价。
 */
object KaojiClient {

    /** 入口页（服务端直出项目卡片）。 */
    private const val INDEX_PATH = "kjgl/kjbm_cxXskjbm.html"
    private const val GNMKDM = "N2510"
    private const val XMLBFL = "1001"

    /** 这些账号只读演示：报名/退报一律本地模拟，不触碰教务系统。 */
    private val TEST_ACCOUNTS = setOf("2505050111")

    /** 学号命中测试账号（currentAccountKey 是复合键，学号要看 studentId）。 */
    fun isTestAccount(): Boolean = UserManager.getInstance().studentId.trim() in TEST_ACCOUNTS

    // ── 结果模型 ─────────────────────────────────────────────────────────

    sealed class LoadResult {
        data class Success(val page: KaojiPage) : LoadResult()
        data class NeedLogin(val message: String) : LoadResult()
        data class Failure(val message: String) : LoadResult()
    }

    sealed class SubmitResult {
        /** 测试账号的本地模拟成功：没有发生任何网络写请求。 */
        data class Simulated(val message: String) : SubmitResult()
        data class Success(val message: String) : SubmitResult()
        data class Failure(val message: String) : SubmitResult()
        data class NeedLogin(val message: String) : SubmitResult()
    }

    // ── 读取 ─────────────────────────────────────────────────────────────

    /**
     * 拉取考级报名主页：项目卡片 + 已报名记录。
     * 两个请求都是 GET，与网页浏览等价，不受测试账号限制。
     */
    suspend fun loadPage(transport: AcademicHttpTransport, school: SchoolConfig): LoadResult {
        return try {
            val index = transport.get(
                transport.appUrl("$INDEX_PATH?xmlbfl=$XMLBFL&gnmkdm=$GNMKDM"),
                referer = transport.appUrl("xsMain/newindex.html")
            )
            if (AcademicHtml.isLoginPage(index.text)) {
                return LoadResult.NeedLogin("登录已失效，请重新登录教务账号")
            }
            val page = KaojiParser.parseIndexPage(index.text)
                ?: return LoadResult.Failure("教务系统没有返回可识别的考级报名页面，可能该功能未开放")
            val registered = runCatching {
                val grid = transport.get(
                    transport.appUrl("$INDEX_PATH?doType=query&pkey=&xmlbfl=$XMLBFL&_=${System.currentTimeMillis()}"),
                    referer = index.url, ajax = true
                )
                if (AcademicHtml.isLoginPage(grid.text)) emptyList() else KaojiParser.parseRegistered(grid.text)
            }.getOrDefault(emptyList())
            LoadResult.Success(page.copy(registered = registered))
        } catch (e: AcademicException) {
            if (e.status == AcademicStatus.SESSION_EXPIRED) {
                LoadResult.NeedLogin("登录已失效，请重新登录教务账号")
            } else {
                LoadResult.Failure(e.message ?: "加载考级报名信息失败")
            }
        } catch (e: Exception) {
            LoadResult.Failure(e.message ?: "无法连接教务系统，请检查网络或稍后重试")
        }
    }

    // ── 报名（受测试账号保护）────────────────────────────────────────────

    /**
     * 报名一个批次。流程照站点 JS 复刻：
     * 前置检查（cxJcXskjbm，"0"=可报）→ 拉表单页取服务端权威字段 →
     * multipart 提交到 zjBcXskjbm → 响应文本含「成功」判成。
     *
     * @param phone 考生联系电话；与表单页的 oldSjhm 不一致时站点置 gdbj=1（改过手机）。
     */
    suspend fun submitRegistration(
        transport: AcademicHttpTransport,
        school: SchoolConfig,
        project: KaojiProject,
        xnm: String,
        xqm: String,
        phone: String,
    ): SubmitResult {
        // 🔒 测试账号：任何写请求都不发（连前置检查也不发），本地直接模拟成功。
        if (isTestAccount()) {
            return SubmitResult.Simulated("测试账号演示：已模拟报名「${project.title}」，未向教务系统提交任何数据")
        }
        return try {
            val check = transport.postForm(
                transport.appUrl("kjgl/kjbm_cxJcXskjbm.html"),
                listOf("xnm" to xnm, "xqm" to xqm, "xmbmsz_id" to project.id),
                ajax = true
            )
            if (AcademicHtml.isLoginPage(check.text)) return SubmitResult.NeedLogin("登录已失效，请重新登录教务账号")
            if (check.text.trim() != "0") {
                return SubmitResult.Failure(check.text.trim().ifBlank { "当前不满足该批次的报名条件" })
            }

            val form = transport.get(
                transport.appUrl("kjgl/kjbm_zjXskjbm.html?xmbmsz_id=${project.id}"),
                referer = transport.appUrl("$INDEX_PATH?xmlbfl=$XMLBFL&gnmkdm=$GNMKDM")
            )
            if (AcademicHtml.isLoginPage(form.text)) return SubmitResult.NeedLogin("登录已失效，请重新登录教务账号")
            val snapshot = KaojiParser.parseFormPage(form.text)
                ?: return SubmitResult.Failure("报名表单结构与预期不符，无法安全提交，请到教务系统网页端操作")
            if (snapshot.fields["xmbmsz_id"] != project.id) {
                return SubmitResult.Failure("报名表单与所选批次不一致，已取消提交")
            }

            val trimmedPhone = phone.trim()
            if (trimmedPhone.isEmpty()) return SubmitResult.Failure("请填写联系电话")
            val fields = snapshot.fields.toMutableMap()
            fields["sjhm"] = trimmedPhone
            fields["gdbj"] = if (snapshot.oldPhone.isNotBlank() && snapshot.oldPhone != trimmedPhone) "1" else fields["gdbj"].orEmpty().ifBlank { "0" }

            val multipart = MultipartBody.Builder().setType(MultipartBody.FORM).apply {
                fields.forEach { (name, value) ->
                    addFormDataPart(name, value)
                }
            }.build()
            val save = transport.postBody(
                transport.appUrl("kjgl/kjbm_zjBcXskjbm.html"),
                multipart,
                referer = form.url
            )
            if (AcademicHtml.isLoginPage(save.text)) return SubmitResult.NeedLogin("登录已失效，请重新登录教务账号")
            when {
                save.text.contains("成功") -> SubmitResult.Success("报名成功")
                save.text.contains("失败") -> SubmitResult.Failure("报名失败，请稍后重试或到教务系统网页端操作")
                else -> SubmitResult.Failure(save.text.trim().lineSequence().firstOrNull().orEmpty()
                    .ifBlank { "教务系统返回了无法识别的结果，请到教务系统网页端确认报名状态" })
            }
        } catch (e: AcademicException) {
            when (e.status) {
                AcademicStatus.SESSION_EXPIRED -> SubmitResult.NeedLogin("登录已失效，请重新登录教务账号")
                AcademicStatus.RESULT_UNKNOWN -> SubmitResult.Failure("提交中断，结果尚未确认：请先刷新已报名列表再决定是否重报")
                else -> SubmitResult.Failure(e.message ?: "报名提交失败，请稍后重试")
            }
        } catch (e: Exception) {
            SubmitResult.Failure(e.message ?: "报名提交失败，请检查网络后重试")
        }
    }

    // ── 退报（受测试账号保护）────────────────────────────────────────────

    /**
     * 退报一条已报名记录。站点口径：已缴费或正在缴费（jfzt 的 sfqr/sfzfzzt）
     * 不允许退报。测试账号直接本地模拟，不发任何 POST。
     */
    suspend fun withdrawRegistration(
        transport: AcademicHttpTransport,
        school: SchoolConfig,
        record: KaojiRegistered,
    ): SubmitResult {
        if (isTestAccount()) {
            return SubmitResult.Simulated("测试账号演示：已模拟退报「${record.name}」，未向教务系统提交任何数据")
        }
        return try {
            val status = transport.postForm(
                transport.appUrl("kjgl/kjbm_cxXskjbmjfzt.html?xsbmqk_id=${record.id}"),
                emptyList(),
                ajax = true
            )
            if (AcademicHtml.isLoginPage(status.text)) return SubmitResult.NeedLogin("登录已失效，请重新登录教务账号")
            val json = runCatching { org.json.JSONObject(status.text) }.getOrNull()
            if (json != null && (json.optString("sfqr") == "1" || json.optString("sfzfzzt") == "1")) {
                return SubmitResult.Failure("该项目已缴费或正在缴费，无法退报")
            }
            val del = transport.postForm(
                transport.appUrl("kjgl/kjbm_scXskjbm.html?xsbmqk_id=${record.id}"),
                emptyList(),
                ajax = true
            )
            if (AcademicHtml.isLoginPage(del.text)) return SubmitResult.NeedLogin("登录已失效，请重新登录教务账号")
            when {
                del.text.contains("成功") -> SubmitResult.Success("退报成功")
                del.text.contains("失败") -> SubmitResult.Failure("退报失败，请稍后重试或到教务系统网页端操作")
                else -> SubmitResult.Failure(del.text.trim().lineSequence().firstOrNull().orEmpty()
                    .ifBlank { "教务系统返回了无法识别的结果，请到教务系统网页端确认" })
            }
        } catch (e: AcademicException) {
            when (e.status) {
                AcademicStatus.SESSION_EXPIRED -> SubmitResult.NeedLogin("登录已失效，请重新登录教务账号")
                AcademicStatus.RESULT_UNKNOWN -> SubmitResult.Failure("提交中断，结果尚未确认：请先刷新已报名列表")
                else -> SubmitResult.Failure(e.message ?: "退报失败，请稍后重试")
            }
        } catch (e: Exception) {
            SubmitResult.Failure(e.message ?: "退报失败，请检查网络后重试")
        }
    }
}
