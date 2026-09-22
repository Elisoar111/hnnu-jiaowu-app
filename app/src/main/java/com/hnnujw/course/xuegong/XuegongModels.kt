package com.hnnujw.course.xuegong

import org.json.JSONArray
import org.json.JSONObject

/**
 * 学工系统（xg.hnnu.edu.cn）请求失败。
 *
 * 与二课的 [com.hnnujw.course.secondclass.SecondClassException] 分开：
 * 两站错误码体系完全不同（学工没有统一 code，靠 HTTP 状态 + `Msg` 文案）。
 */
class XuegongException(
    message: String,
    /** true = 令牌失效，需要重新登录。 */
    val sessionExpired: Boolean = false,
) : Exception(message)

/** 分页信封的公共形状：`{CurrentPage, TotalPages, TotalItems, ItemsPerPage, Items[]}`。 */
data class XuegongPage<T>(
    val items: List<T>,
    val total: Int,
    val currentPage: Int,
    val totalPages: Int,
) {
    val hasMore: Boolean get() = currentPage < totalPages
}

/**
 * 一条日常请假记录。
 *
 * ⚠️ 字段取值一律走**候选键**（[XuegongClient] 的 `pick`）：本站不同构建里同一个
 * 语义出现过两种写法，写死一个键会让整行变空白。认不出来的键进 [extras]，
 * 由详情页兜底展示 —— 这样即使站点改字段名，用户也不会"看不到自己的记录"。
 */
data class XuegongLeaveRecord(
    val id: String,
    /** 请假类型（病假 / 事假 / 离校…）。 */
    val type: String,
    val beginTime: String,
    val endTime: String,
    val days: String,
    val reason: String,
    /** 审批状态文案。 */
    val status: String,
    /** 申请时间。 */
    val applyTime: String,
    /** 未识别的原始字段（键 → 值），详情页按需展示。 */
    val extras: List<Pair<String, String>>,
) {
    val title: String get() = type.ifBlank { "请假记录" }
}

/**
 * 日常请假页：记录 + 校方给的申请开关。
 *
 * 校方允许申请时，页面提供「写请假」在线填表提交（1.2.4 起）；
 * 开关本身只读展示，是否开放由学工系统说了算。
 */
data class XuegongLeavePage(
    val page: XuegongPage<XuegongLeaveRecord>,
    /** 校方是否允许现在申请（`data.CanApply`）。 */
    val canApply: Boolean,
    /** 申请按钮文案（`data.BtnText`，实测为「申请」）。 */
    val applyButtonText: String,
    /** 不允许申请时的原因（`data.CanNotMsg`）。 */
    val applyBlockedReason: String,
    /** 定位打卡要求（`data.TudeInfo`）。 */
    val locationRule: XuegongLocationRule,
)

/** 请假时的定位打卡配置。 */
data class XuegongLocationRule(
    val required: Boolean,
    /** 允许偏离的距离（米），拿不到时为空串。 */
    val rangeDistance: String,
)

/** 一批节假日去向登记（`Config[]` 的一项）。 */
data class XuegongHolidayBatch(
    val id: String,
    val name: String,
    val holidayBegin: String,
    val holidayEnd: String,
    val registerBegin: String,
    val registerEnd: String,
    /** `StatusName`：如「登记中，请提交登记」。 */
    val statusName: String,
    val memo: String,
    /** 校方是否开放登记（`Status == "1"`）。 */
    val open: Boolean,
)

/**
 * 一条节假日去向登记记录。
 *
 * 站点字段名是固定的一套（实测 38 个键），这里挑出真正要展示的，
 * 其余进 [extras]。
 */
data class XuegongWhereaboutsRecord(
    val id: String,
    val holidayId: String,
    val holidayName: String,
    /** 去向类型：离校 / 留校。 */
    val leaveType: String,
    /** `IsRs`：已登记 / 未登记。 */
    val registered: String,
    val beginTime: String,
    val endTime: String,
    val days: String,
    val reason: String,
    /** 去向（省市区合并的 `ComeWhere`）。 */
    val destination: String,
    /** 交通方式。 */
    val vehicle: String,
    /** 紧急联系人姓名。 */
    val contactName: String,
    val contactRelation: String,
    val contactTel: String,
    /** 本人手机。 */
    val studentTel: String,
    /** 家庭所在地。 */
    val homePlace: String,
    /** 登记时间（`InsertDate`）。 */
    val registerTime: String,
    val extras: List<Pair<String, String>>,
) {
    val title: String get() = holidayName.ifBlank { "去向登记" }
}

/** 去向登记页：记录 + 各批次开关。 */
data class XuegongWhereaboutsPage(
    val page: XuegongPage<XuegongWhereaboutsRecord>,
    val batches: List<XuegongHolidayBatch>,
    /** 院系列的表头文案（`CollegeAsName`，实测为「院系」）。 */
    val collegeLabel: String,
)

// ── 提交表单（1.2.5）────────────────────────────────────────────────────
//
// 设计原则：**回传，不重建**。表单骨架（`/DailyLeave/Get`、`/HolidayWhereabouts/Get`）
// 里带着服务端下发的默认 `ApplyInfo`（含 IsEdit / Term / 班级等一堆我们不认识的键），
// 提交时只把用户编辑过的字段覆盖回这份对象，其余原样送回去 —— 不猜字段名、
// 不臆造默认值，跟 H5 表单页的行为完全一致。

/** 下拉字典的一项（站点 `values:[{text,value}]` 形态）。 */
data class XuegongDictOption(
    val value: String,
    val text: String,
) {
    val label: String get() = text.ifBlank { value }
}

/** 字典选项与草稿共用的安全取文本：JSON null / 缺键都归一化成空串（不能用 `optString`）。 */
internal fun JSONObject?.safeText(key: String): String {
    if (this == null) return ""
    val value = opt(key) ?: return ""
    if (value === JSONObject.NULL) return ""
    val rendered = value.toString().trim()
    return if (rendered == "null" || rendered == "undefined") "" else rendered
}

/** 「日常请假」可编辑草稿。字段与必填规则照抄 H5（chunk-808c1020）。 */
data class XuegongLeaveDraft(
    /** 表单骨架的原始 `ApplyInfo`（提交时以此为基础覆盖编辑字段）。 */
    val raw: JSONObject,
    /** 附件上传地址（表单骨架的 `UpFilePath`）。 */
    val upFilePath: String,
    /** 顶部提示（`SetInfo.LeaveNotice`），没有时为空。 */
    val notice: String,
    // ── 编辑字段 ──
    var beginTime: String = "",
    var endTime: String = "",
    /** 请假原因的字典值（写入 `ApplyInfo.LeaveReason`）。 */
    var reason: String = "",
    /** 请假原因的展示文案。 */
    var reasonText: String = "",
    var reasonDetail: String = "",
    /** 是否离校：`"1"` 离校 / `"0"` 不离校（站点用字符串数字）。 */
    var isOut: String = "0",
    var outAddress: String = "",
    var outAddressStreet: String = "",
    var outGoTime: String = "",
    var outBackTime: String = "",
    var outGoVehicle: String = "",
    var outGoVehicleText: String = "",
    var outBackVehicle: String = "",
    var outBackVehicleText: String = "",
    /** 是否已告知监护人：`"1"` / `"0"`。 */
    var isTellGuarder: String = "0",
    var guarderName: String = "",
    var guarderTel: String = "",
    /** 是否结伴同行。 */
    var isCompanion: String = "0",
    var companionName: String = "",
    var companionRelationship: String = "",
    var companionTel: String = "",
    var stuMoveTel: String = "",
    var outContacts: String = "",
    var outContactsRelationship: String = "",
    var outContactsTel: String = "",
) {
    companion object {
        /**
         * 从表单骨架构建草稿：编辑字段取骨架默认值，字典从
         * `LeaveReason` / `OutGoVehicle` / `OutBackVehicle` 的 `values` 提取。
         */
        fun of(form: JSONObject): XuegongLeaveDraft {
            val apply = form.optJSONObject("ApplyInfo") ?: JSONObject()
            fun text(key: String): String = apply.safeText(key)
            fun dict(key: String): List<XuegongDictOption> {
                val node = form.optJSONObject(key) ?: return emptyList()
                val values = node.optJSONArray("values") ?: return emptyList()
                val out = ArrayList<XuegongDictOption>()
                for (i in 0 until values.length()) {
                    val item = values.optJSONObject(i) ?: continue
                    val value = item.opt("value") ?: continue
                    if (value === JSONObject.NULL) continue
                    out.add(XuegongDictOption(value = value.toString(), text = item.safeText("text")))
                }
                return out
            }
            return XuegongLeaveDraft(
                raw = apply,
                upFilePath = form.safeText("UpFilePath"),
                notice = form.optJSONObject("SetInfo").safeText("LeaveNotice"),
                beginTime = text("LeaveBeginTime"),
                endTime = text("LeaveEndTime"),
                reason = text("LeaveReason"),
                reasonDetail = text("LeaveReasonDetail"),
                isOut = text("IsOut").ifBlank { "0" },
                outAddress = text("OutAddress"),
                outAddressStreet = text("OutAddressStreet"),
                outGoTime = text("OutGoTime"),
                outBackTime = text("OutBackTime"),
                outGoVehicle = text("OutGoVehicle"),
                outBackVehicle = text("OutBackVehicle"),
                isTellGuarder = text("IsTellGuarder").ifBlank { "0" },
                guarderName = text("GuarderName"),
                guarderTel = text("GuarderTel"),
                isCompanion = text("IsCompanion").ifBlank { "0" },
                companionName = text("Companion"),
                companionRelationship = text("CompanionRelationship"),
                companionTel = text("CompanionTel"),
                stuMoveTel = text("StuMoveTel"),
                outContacts = text("OutContacts"),
                outContactsRelationship = text("OutContactsRelationship"),
                outContactsTel = text("OutContactsTel"),
            ).also { draft ->
                // 回填字典文案：站点只在选中后才有 text，新建时从 values 里找
                val reasonDict = dict("LeaveReason")
                draft.reasonOptions = reasonDict
                draft.reasonText = reasonDict.firstOrNull { it.value == draft.reason }?.label.orEmpty()
                val goDict = dict("OutGoVehicle")
                draft.outGoOptions = goDict
                draft.outGoVehicleText = goDict.firstOrNull { it.value == draft.outGoVehicle }?.label.orEmpty()
                val backDict = dict("OutBackVehicle")
                draft.outBackOptions = backDict
                draft.outBackVehicleText = backDict.firstOrNull { it.value == draft.outBackVehicle }?.label.orEmpty()
            }
        }
    }

    /** 三个下拉的选项（由 [of] 填充）。 */
    var reasonOptions: List<XuegongDictOption> = emptyList()
    var outGoOptions: List<XuegongDictOption> = emptyList()
    var outBackOptions: List<XuegongDictOption> = emptyList()

    /**
     * 覆盖编辑字段后的 `ApplyInfo`（其余键原样保留）。
     *
     * 请假时长（`LeaveDays` / `LeaveHours`）按 H5 的口径现算：
     * 不足 24 小时记小时，超过记"天 + 余下小时"。
     */
    fun toApplyInfo(): JSONObject {
        val apply = JSONObject(raw.toString())
        fun put(key: String, value: String) {
            if (value.isBlank()) apply.put(key, "") else apply.put(key, value)
        }
        put("LeaveBeginTime", beginTime)
        put("LeaveEndTime", endTime)
        put("LeaveReason", reason)
        put("LeaveReasonDetail", reasonDetail)
        put("IsOut", isOut)
        put("OutAddress", outAddress)
        put("OutAddressStreet", outAddressStreet)
        put("OutGoTime", outGoTime)
        put("OutBackTime", outBackTime)
        put("OutGoVehicle", outGoVehicle)
        put("OutBackVehicle", outBackVehicle)
        put("IsTellGuarder", isTellGuarder)
        put("GuarderName", guarderName)
        put("GuarderTel", guarderTel)
        put("IsCompanion", isCompanion)
        put("Companion", companionName)
        put("CompanionRelationship", companionRelationship)
        put("CompanionTel", companionTel)
        put("StuMoveTel", stuMoveTel)
        put("OutContacts", outContacts)
        put("OutContactsRelationship", outContactsRelationship)
        put("OutContactsTel", outContactsTel)

        // 时长：与 H5 的 computed（K）同口径
        val begin = parseSiteTime(beginTime)
        val end = parseSiteTime(endTime)
        if (begin != null && end != null && end > begin) {
            val hours = (end - begin) / 3600000.0
            if (hours < 24.0) {
                apply.put("LeaveDays", 0)
                apply.put("LeaveHours", trimHours(hours))
            } else {
                apply.put("LeaveDays", hours.toInt())
                apply.put("LeaveHours", trimHours(hours % 24.0))
            }
        } else {
            apply.put("LeaveDays", "")
            apply.put("LeaveHours", "")
        }
        return apply
    }

    /** 共计文案（"1天3小时" / "5小时"），纯展示。 */
    val durationText: String
        get() {
            val begin = parseSiteTime(beginTime) ?: return ""
            val end = parseSiteTime(endTime) ?: return ""
            if (end <= begin) return ""
            val hours = (end - begin) / 3600000.0
            val days = hours.toInt()
            val rest = hours - days
            return when {
                hours < 24.0 -> "${trimHours(hours)}小时"
                else -> buildString {
                    append(days).append("天")
                    if (rest > 0) append(trimHours(rest)).append("小时")
                }
            }
        }

    /** 提交前的本地校验，返回第一条错误；通过时为 null。 */
    fun validate(): String? = when {
        beginTime.isBlank() -> "请选择请假开始时间"
        endTime.isBlank() -> "请选择请假结束时间"
        parseSiteTime(beginTime) == null || parseSiteTime(endTime) == null ->
            "请假时间格式不正确（应为 年/月/日 时:00）"
        (parseSiteTime(endTime) ?: 0L) <= (parseSiteTime(beginTime) ?: 0L) -> "请假结束时间必须大于开始时间"
        reason.isBlank() -> "请选择请假原因"
        reasonDetail.isBlank() -> "请填写详细说明"
        isTellGuarder == "1" && guarderTel.isBlank() -> "已告知监护人时请填写监护人电话"
        isOut == "1" && outAddress.isBlank() -> "离校时请填写外出地点"
        isOut == "1" && outGoTime.isBlank() -> "离校时请选择外出开始时间"
        isOut == "1" && outBackTime.isBlank() -> "离校时请选择外出结束时间"
        isOut == "1" && outGoVehicle.isBlank() -> "离校时请选择外出方式"
        isOut == "1" && outBackVehicle.isBlank() -> "离校时请选择返回方式"
        isCompanion == "1" && companionName.isBlank() -> "结伴同行时请填写同行人姓名"
        else -> null
    }
}

/** 「去向登记」可编辑草稿。字段与必填规则照抄 H5（chunk-3ab3b4f5）。 */
data class XuegongWhereaboutsDraft(
    /** 表单骨架的原始 `ApplyInfo`。 */
    val raw: JSONObject,
    /** 批次信息（骨架 `Config`），仅展示用。 */
    val batchBegin: String = "",
    val batchEnd: String = "",
    val registerBegin: String = "",
    val registerEnd: String = "",
    // ── 编辑字段 ──
    /** 去向类型：`"1"` 离校 / `"2"` 留校。 */
    var leaveType: String = "1",
    var beginTime: String = "",
    var endTime: String = "",
    var stayBeginTime: String = "",
    var stayEndTime: String = "",
    var reason: String = "",
    var outGoVehicle: String = "",
    var outGoVehicleText: String = "",
    var comeWhere: String = "",
    var outAddressStreet: String = "",
    var outNumber: String = "",
    var outContacts: String = "",
    var outContactsRelationship: String = "",
    var outContactsMoveTel: String = "",
    var outContactsTel: String = "",
    var stuMoveTel: String = "",
    var stuTel: String = "",
) {
    companion object {
        fun of(form: JSONObject): XuegongWhereaboutsDraft {
            val apply = form.optJSONObject("ApplyInfo") ?: JSONObject()
            fun text(key: String): String = apply.safeText(key)
            val goDict = form.optJSONObject("OutGoVehicle")
                ?.optJSONArray("values")
                ?.let { array ->
                    (0 until array.length()).mapNotNull { i ->
                        val item = array.optJSONObject(i) ?: return@mapNotNull null
                        val value = item.opt("value") ?: return@mapNotNull null
                        if (value === JSONObject.NULL) null
                        else XuegongDictOption(value = value.toString(), text = item.safeText("text"))
                    }
                }.orEmpty()
            val config = form.optJSONObject("Config") ?: JSONObject()
            return XuegongWhereaboutsDraft(
                raw = apply,
                batchBegin = config.safeText("BeginDate"),
                batchEnd = config.safeText("EndDate"),
                registerBegin = config.safeText("RegBeginDate"),
                registerEnd = config.safeText("RegEndDate"),
                leaveType = text("LeaveType").ifBlank { "1" },
                beginTime = text("LeaveBeginTime"),
                endTime = text("LeaveEndTime"),
                stayBeginTime = text("StayBeginTime"),
                stayEndTime = text("StayEndTime"),
                reason = text("LeaveReason"),
                outGoVehicle = text("OutGoVehicle"),
                comeWhere = text("ComeWhere"),
                outAddressStreet = text("OutAddressStreet"),
                outNumber = text("OutNumber"),
                outContacts = text("OutContacts"),
                outContactsRelationship = text("OutContactsRelationship"),
                outContactsMoveTel = text("OutContactsMoveTel"),
                outContactsTel = text("OutContactsTel"),
                stuMoveTel = text("StuMoveTel"),
                stuTel = text("StuTel"),
            ).also { draft ->
                draft.outGoOptions = goDict
                draft.outGoVehicleText = goDict.firstOrNull { it.value == draft.outGoVehicle }?.label.orEmpty()
            }
        }
    }

    var outGoOptions: List<XuegongDictOption> = emptyList()

    /**
     * 按上一次登记记录回填「记忆字段」（去向类型、交通方式、地点、联系人、电话等）。
     *
     * 只填草稿里还空着的字段（去向类型与交通方式除外 —— 站点骨架里它们通常是空的，
     * 而用户明确要求按上次登记自动填写），**时间一律不回填**：每个批次的登记窗口不同，
     * 照抄上一次的时间几乎必然错。回填后仍需用户检查、手动提交。
     */
    fun applyMemory(record: XuegongWhereaboutsRecord) {
        when {
            record.leaveType == "2" || record.leaveType.contains("留校") -> leaveType = "2"
            record.leaveType == "1" || record.leaveType.contains("离校") -> leaveType = "1"
        }
        if (record.vehicle.isNotBlank() && outGoVehicle.isBlank()) {
            outGoOptions.firstOrNull { it.text == record.vehicle || it.label == record.vehicle }?.let {
                outGoVehicle = it.value
                outGoVehicleText = it.label
            }
        }
        if (comeWhere.isBlank()) comeWhere = record.destination.ifBlank { record.homePlace }
        if (reason.isBlank()) reason = record.reason
        if (outContacts.isBlank()) outContacts = record.contactName
        if (outContactsRelationship.isBlank()) outContactsRelationship = record.contactRelation
        if (outContactsTel.isBlank()) outContactsTel = record.contactTel
        if (stuMoveTel.isBlank()) stuMoveTel = record.studentTel
    }

    /** 覆盖编辑字段后的 `ApplyInfo`。 */
    fun toApplyInfo(): JSONObject {
        val apply = JSONObject(raw.toString())
        fun put(key: String, value: String) = apply.put(key, value)
        put("LeaveType", leaveType)
        put("LeaveBeginTime", beginTime)
        put("LeaveEndTime", endTime)
        put("StayBeginTime", stayBeginTime)
        put("StayEndTime", stayEndTime)
        put("LeaveReason", reason)
        put("OutGoVehicle", outGoVehicle)
        put("ComeWhere", comeWhere)
        put("OutAddressStreet", outAddressStreet)
        put("OutNumber", outNumber)
        put("OutContacts", outContacts)
        put("OutContactsRelationship", outContactsRelationship)
        put("OutContactsMoveTel", outContactsMoveTel)
        put("OutContactsTel", outContactsTel)
        put("StuMoveTel", stuMoveTel)
        put("StuTel", stuTel)

        val begin = parseSiteTime(beginTime)
        val end = parseSiteTime(endTime)
        if (begin != null && end != null && end > begin) {
            val hours = (end - begin) / 3600000.0
            if (hours < 24.0) {
                apply.put("LeaveDays", 0)
                apply.put("LeaveHours", trimHours(hours))
            } else {
                apply.put("LeaveDays", hours.toInt())
                apply.put("LeaveHours", trimHours(hours % 24.0))
            }
        }
        return apply
    }

    val durationText: String
        get() {
            val begin = parseSiteTime(if (leaveType == "2") stayBeginTime else beginTime)
            val end = parseSiteTime(if (leaveType == "2") stayEndTime else endTime)
            if (begin == null || end == null || end <= begin) return ""
            val hours = (end - begin) / 3600000.0
            val days = hours.toInt()
            val rest = hours - days
            return when {
                hours < 24.0 -> "${trimHours(hours)}小时"
                else -> buildString {
                    append(days).append("天")
                    if (rest > 0) append(trimHours(rest)).append("小时")
                }
            }
        }

    /** 离校去向的本地校验（留校只查时间段）。 */
    fun validate(): String? = when {
        leaveType == "1" && comeWhere.isBlank() -> "请填写外出地点"
        leaveType == "1" && outGoVehicle.isBlank() -> "请选择交通方式"
        leaveType == "1" && beginTime.isBlank() -> "请选择离校开始时间"
        leaveType == "1" && endTime.isBlank() -> "请选择离校结束时间"
        leaveType == "2" && stayBeginTime.isBlank() -> "请选择留校开始时间"
        leaveType == "2" && stayEndTime.isBlank() -> "请选择留校结束时间"
        else -> null
    }
}

/** 站点时间 `2026/10/01 08:00` → 毫秒；解析不了返回 null。 */
fun parseSiteTime(value: String): Long? {
    val trimmed = value.trim()
    if (trimmed.isBlank()) return null
    return runCatching {
        java.text.SimpleDateFormat("yyyy/MM/dd HH:mm", java.util.Locale.CHINA).parse(trimmed)?.time
    }.getOrNull()
}

/** `5.0 → "5"`、`2.5 → "2.5"`。 */
private fun trimHours(value: Double): String {
    val asLong = value.toLong()
    return if (value == asLong.toDouble()) asLong.toString() else String.format(java.util.Locale.CHINA, "%.1f", value)
}
