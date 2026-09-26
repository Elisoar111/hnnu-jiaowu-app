package com.hnnujw.course.examreg

import com.hnnujw.course.academic.AcademicException
import com.hnnujw.course.academic.AcademicHtml
import com.hnnujw.course.academic.AcademicHttpTransport
import com.hnnujw.course.academic.AcademicMenu
import com.hnnujw.course.academic.AcademicStatus
import com.hnnujw.course.model.SchoolConfig
import okhttp3.MultipartBody

/**
 * 教务系统「考级项目报名」（kjgl/kjbm_*，gnmkdm=N2510）客户端。
 *
 * 全部走 [AcademicHttpTransport] 的已登录会话（与课表/成绩共用 Cookie），
 * 会话失效以「响应是登录页」判定 —— 正方对未登录的 XHR 一律 200 回登录页。
 *
 * 报名/退报是真实的写请求，与网页端操作等价；界面层负责在确认弹窗里
 * 让用户复核报名说明与联系电话后才调用这里。
 */
object KaojiClient {

    /** 入口页（服务端直出项目卡片）。 */
    private const val INDEX_PATH = "kjgl/kjbm_cxXskjbm.html"
    private const val GNMKDM = "N2510"

    /** 本学期过期项目（网页端页头「本学期过期项目报名信息」按钮走到这里）。 */
    private const val EXPIRED_PATH = "kjgl/kjbm_cxGqxm.html"

    /**
     * 兜底类别。仅在**菜单里读不到考级入口**时使用（例如菜单接口临时不可用）。
     * 正常情况下类别由 [loadCategories] 从教务菜单发现 —— 别再写死。
     */
    val DEFAULT_CATEGORY = KaojiCategory(xmlbfl = "1001", title = "考级项目报名")

    // ── 结果模型 ─────────────────────────────────────────────────────────

    sealed class LoadResult {
        data class Success(val page: KaojiPage) : LoadResult()
        data class NeedLogin(val message: String) : LoadResult()
        data class Failure(val message: String) : LoadResult()
    }

    sealed class SubmitResult {
        data class Success(val message: String) : SubmitResult()
        data class Failure(val message: String) : SubmitResult()
        data class NeedLogin(val message: String) : SubmitResult()
    }

    sealed class ExpiredResult {
        data class Success(val projects: List<KaojiExpiredProject>, val totalCount: Int) : ExpiredResult()
        data class NeedLogin(val message: String) : ExpiredResult()
        data class Failure(val message: String) : ExpiredResult()
    }

    // ── 类别发现 ─────────────────────────────────────────────────────────

    /**
     * 从教务功能菜单（[AcademicMenu.PATH]）发现本账号可用的考级类别。
     *
     * 菜单条目形如 `clickMenu('N2510','/kjgl/kjbm_cxXskjbm.html?xmlbfl=1001','考级项目报名','null')`
     * —— 类别代码、入口路径、站点标题都在里面，所以这里**只做发现，不做猜测**：
     * 学生有「大类分流报名 / 推免报名」权限的学校会在菜单里多出对应条目，
     * 我们照收；没有就只显示一条。
     *
     * 读不到（菜单接口异常 / 条目里没带 xmlbfl）时退到 [DEFAULT_CATEGORY]，
     * 保证页面至少能用 —— 这也是早先版本写死 1001 时唯一的行为，不会变差。
     */
    suspend fun loadCategories(transport: AcademicHttpTransport): List<KaojiCategory> =
        runCatching {
            val response = transport.get(transport.appUrl(AcademicMenu.PATH))
            if (AcademicHtml.isLoginPage(response.text)) return@runCatching emptyList()
            AcademicMenu.query(response.text, INDEX_PATH)
                .mapNotNull { item ->
                    val xmlbfl = AcademicMenu.queryParam(item.url, "xmlbfl") ?: return@mapNotNull null
                    KaojiCategory(xmlbfl = xmlbfl, title = item.title.ifBlank { DEFAULT_CATEGORY.title })
                }
                .distinctBy { it.xmlbfl }
        }.getOrDefault(emptyList()).ifEmpty { listOf(DEFAULT_CATEGORY) }

    // ── 读取 ─────────────────────────────────────────────────────────────

    /**
     * 拉取考级报名主页：项目卡片 + 已报名记录。
     * 两个请求都是 GET，与网页浏览等价。
     *
     * ⚠️ 「项目列表为空」是**成功**（当前没有开放批次），不是失败 ——
     * 解析层只在连页面都认不出时才给 null，那时优先把站点自己的提示语
     * （`.nodata` / `.error_title`，如「无功能权限」）透给用户，
     * 比我们自造的「没有返回可识别的页面」有用得多。
     *
     * @param xmlbfl 类别代码，来自 [loadCategories]；默认 [DEFAULT_CATEGORY]。
     */
    suspend fun loadPage(
        transport: AcademicHttpTransport,
        school: SchoolConfig,
        xmlbfl: String = DEFAULT_CATEGORY.xmlbfl,
    ): LoadResult {
        return try {
            val index = transport.get(
                transport.appUrl("$INDEX_PATH?xmlbfl=$xmlbfl&gnmkdm=$GNMKDM"),
                referer = transport.appUrl(AcademicMenu.PATH)
            )
            if (AcademicHtml.isLoginPage(index.text)) {
                return LoadResult.NeedLogin("登录已失效，请重新登录教务账号")
            }
            val pageNotice = AcademicHtml.pageNotice(index.text)
            val page = KaojiParser.parseIndexPage(index.text)
                ?: return LoadResult.Failure(
                    pageNotice.ifBlank { "教务系统没有返回可识别的考级报名页面，可能该功能未开放" }
                )
            val registered = runCatching {
                val grid = transport.get(
                    transport.appUrl("$INDEX_PATH?doType=query&pkey=&xmlbfl=$xmlbfl&_=${System.currentTimeMillis()}"),
                    referer = index.url, ajax = true
                )
                if (AcademicHtml.isLoginPage(grid.text)) {
                    KaojiParser.Envelope(emptyList(), 0)
                } else {
                    KaojiParser.parseEnvelope(grid.text)
                }
            }.getOrDefault(KaojiParser.Envelope(emptyList(), 0))
            LoadResult.Success(
                page.copy(
                    registered = registered.rows.map(KaojiParser::toRegistered),
                    registeredTotal = registered.totalCount,
                    pageNotice = pageNotice,
                )
            )
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

    /**
     * 拉取「本学期过期项目报名信息」，与网页端页头那个信封按钮同一个接口。
     *
     * 与 [loadPage] 共用同一个 jqGrid 信封解析，但**按需调用**（界面上是显式按钮）：
     * 网页端也要点一下才看，没必要每次进页面都多打一个请求。
     */
    suspend fun loadExpired(
        transport: AcademicHttpTransport,
        xmlbfl: String = DEFAULT_CATEGORY.xmlbfl,
    ): ExpiredResult {
        return try {
            val response = transport.get(
                transport.appUrl("$EXPIRED_PATH?doType=query&pkey=&xmlbfl=$xmlbfl&_=${System.currentTimeMillis()}"),
                referer = transport.appUrl("$INDEX_PATH?xmlbfl=$xmlbfl&gnmkdm=$GNMKDM"),
                ajax = true
            )
            if (AcademicHtml.isLoginPage(response.text)) {
                return ExpiredResult.NeedLogin("登录已失效，请重新登录教务账号")
            }
            val envelope = KaojiParser.parseEnvelope(response.text)
            // 信封都解析不出来（不是 JSON）说明不是这个接口该有的响应，
            // 别把它当成"过期项目为 0 条"。
            if (envelope.rows.isEmpty() && envelope.totalCount == 0 && !response.text.trim().startsWith("{")) {
                return ExpiredResult.Failure("教务系统没有返回可识别的过期项目数据")
            }
            ExpiredResult.Success(envelope.rows.map(KaojiParser::toExpiredProject), envelope.totalCount)
        } catch (e: AcademicException) {
            if (e.status == AcademicStatus.SESSION_EXPIRED) {
                ExpiredResult.NeedLogin("登录已失效，请重新登录教务账号")
            } else {
                ExpiredResult.Failure(e.message ?: "加载过期项目失败")
            }
        } catch (e: Exception) {
            ExpiredResult.Failure(e.message ?: "无法连接教务系统，请检查网络或稍后重试")
        }
    }

    // ── 报名 ─────────────────────────────────────────────────────────────

    /**
     * 报名一个批次。流程照站点 JS 复刻：
     * 前置检查（cxJcXskjbm，"0"=可报）→ 拉表单页取服务端权威字段 →
     * multipart 提交到 zjBcXskjbm → 响应文本含「成功」判成。
     *
     * @param phone 考生联系电话；与表单页的 oldSjhm 不一致时站点置 gdbj=1（改过手机）。
     * @param xmlbfl 当前类别，用于复刻网页端的 Referer。
     */
    suspend fun submitRegistration(
        transport: AcademicHttpTransport,
        school: SchoolConfig,
        project: KaojiProject,
        xnm: String,
        xqm: String,
        phone: String,
        xmlbfl: String = DEFAULT_CATEGORY.xmlbfl,
    ): SubmitResult {
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
                referer = transport.appUrl("$INDEX_PATH?xmlbfl=$xmlbfl&gnmkdm=$GNMKDM")
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

    // ── 退报 ─────────────────────────────────────────────────────────────

    /**
     * 退报一条已报名记录。站点口径：已缴费或正在缴费（jfzt 的 sfqr/sfzfzzt）
     * 不允许退报；报名截止后的禁退由界面层判断（不发起请求）。
     */
    suspend fun withdrawRegistration(
        transport: AcademicHttpTransport,
        school: SchoolConfig,
        record: KaojiRegistered,
    ): SubmitResult {
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
