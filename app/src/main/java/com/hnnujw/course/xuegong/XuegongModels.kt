package com.hnnujw.course.xuegong

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
 * 本应用**只展示**这份开关状态，不提供任何提交入口
 * （用户明确要求只读；请假仍需回到学工系统官方页面操作）。
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
