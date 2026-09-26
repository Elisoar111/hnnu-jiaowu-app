package com.hnnujw.course.secondclass

/**
 * 第二课堂（共青团成绩单系统）数据模型。
 *
 * 站点与教务系统不同域、不同会话：教务用 Cookie + 直登表单，第二课堂用
 * `POST /token` 换 access_token，之后所有请求带 `Authorization` 头。
 * 因此这里的数据结构刻意不复用 [com.hnnujw.course.academic] 的任何类型。
 */

/** 排行榜层级。`level` 是站点前端写死的取值，不要自行推断。 */
enum class SecondClassRankLevel(val level: Int, val label: String) {
    Classmates(10, "班级"),
    Major(9, "专业"),
    College(8, "院系"),
    School(6, "全校");

    companion object {
        /** 与站点下拉顺序一致：班级 → 专业 → 院系 → 全校。 */
        val all: List<SecondClassRankLevel> get() = listOf(Classmates, Major, College, School)

        fun ofLevel(level: Int): SecondClassRankLevel? = entries.firstOrNull { it.level == level }
    }
}

/** 成绩单表头信息，来自 `/student/achievement/detail` 的 `user`。 */
data class SecondClassProfile(
    val name: String = "",
    val code: String = "",
    val collegeName: String = "",
    /**
     * 本人所在院系的 id。
     *
     * 用途只有一处：活动中心「本院系可报」筛选（比对活动的 `collegeLimit` 院系列表）。
     * 取不到时为 0，此时筛选**一律放行**而不是全筛掉 —— 见
     * [SecondClassActivity.enrollableForCollege] 的说明。
     */
    val collegeId: Int = 0,
    val majorName: String = "",
    val grade: String = "",
    /** 总积分。单位见 [scoreUnit]（学校可能配成"学分"或"分数"）。 */
    val score: Double = 0.0,
    /** 学时。单位见 [hourUnit]。 */
    val hours: Double = 0.0,
    val scoreUnit: String = "",
    val hourUnit: String = "",
    val avatar: String = "",
    val gender: Int = 0,
) {
    val hasIdentity: Boolean get() = name.isNotBlank() || code.isNotBlank()
}

/** 单个模块（分类）的积分状况。 */
data class SecondClassModule(
    val name: String,
    val mine: Double,
    /** 学校要求的最低值；0 表示该校没配下限。 */
    val required: Double,
    /** 全校参考值，用于对比。 */
    val average: Double,
) {
    /** 0..1；没有要求时按"无上限"处理，返回 null 让界面不画进度条。 */
    val progress: Float? get() = if (required > 0.0) (mine / required).toFloat().coerceIn(0f, 1f) else null
}

/** 排行榜里的一行。 */
data class SecondClassRankEntry(
    /**
     * 名次，**由客户端按分数重算**（`SecondClassroomClient.rerank`）：同分并列、
     * 名次号 = 排在我前面的人数 + 1，最大值不会超过总人数。
     *
     * 不要把它换成服务端的 `rownum` —— 那个值并列时会**跳号**
     * （实测班级 37 人最大只到 35），而且部分层级是浮点。
     */
    val rank: Int,
    val name: String,
    val majorName: String,
    val score: Double,
    val avatar: String = "",
    val gender: Int = 0,
    val isSelf: Boolean = false,
    /**
     * 学号。站点榜单里**评分并列**时 `rownum` 会重复（实测班级 37 人里
     * 第 29、35 名各出现两次），所以去重与列表 key 都不能用名次，
     * 只能用学号。旧数据没有这一项时为空串。
     */
    val code: String = "",
) {
    /** 稳定的行标识：优先学号，退化到「名次-姓名」。 */
    val identity: String get() = code.ifBlank { "$rank-$name" }
}

/** 某一层级的榜单。 */
data class SecondClassRankBoard(
    val level: SecondClassRankLevel,
    val entries: List<SecondClassRankEntry> = emptyList(),
    /** 我的名次；没进榜时为 null。 */
    val myRank: SecondClassRankEntry? = null,
    val total: Int = 0,
)

/** 页面一次性需要的全部数据。任何一段失败都只让该段为空，不影响其余部分。 */
data class SecondClassSnapshot(
    val profile: SecondClassProfile = SecondClassProfile(),
    val modules: List<SecondClassModule> = emptyList(),
    val boards: Map<SecondClassRankLevel, SecondClassRankBoard> = emptyMap(),
)

// ── 积分明细（「分类与学期统计」，1.2.6）──────────────────────────────────
//
// 「每一分有迹可循」= 总积分必须能拆回一条条来源记录。
// 站点把同一份账按两个维度各切一遍：
//   · 按分类  `/student/achievement/by-classify-list`
//   · 按学期  `/student/achievement/by-term-list`
// 两条链路的记录**字段不完全一致**（学期维度多带 `classifyName`，分类维度多带
// `amount` / `sourceType` / `relationId`），所以统一收敛到 [SecondClassPointRecord]，
// 缺什么就是空值 —— 而不是为两个维度各写一套模型 + 两套界面。

/**
 * 一条积分来源记录（一笔"账"）。
 *
 * 两个维度共用的最小公倍数：分类维度提供 `amount` / `sourceType`，学期维度提供
 * `classifyName`。所以同一条记录在两个 Tab 下的信息量可以不同，
 * **但 `hours` 与 `time` 一定都在** —— 它们是"这一分从哪来、什么时候到账"的判据。
 */
data class SecondClassPointRecord(
    /** 来源名称（活动名 / 申报项目名）。 */
    val name: String,
    /** 本笔记账的积分（站点 `hours`）。 */
    val hours: Double,
    /** 折算前的原始值（站点 `amount`）；分类维度才有，缺省时取 0 表示"站点没给"。 */
    val amount: Double = 0.0,
    /** 记账时间（epoch 毫秒）；站点没给时为 0。 */
    val time: Long = 0,
    /** 所属分类（学期维度才有；分类维度由外层分组补上）。 */
    val classifyName: String = "",
    /**
     * 来源类型码（站点 `sourceType`）。
     *
     * ⚠️ 站点没有下发类型文案，也**没有**权威取值表，所以这里只保留原值，
     * **不在界面上展示** —— 见 [sourceLabel]。
     */
    val sourceType: Int = 0,
    /** 关联实体 id（站点 `relationId`），用于同一笔记录在两个维度间对齐。 */
    val relationId: String = "",
) {
    /**
     * 来源类型码的**裸文案**（形如 `来源 3`）。
     *
     * ⚠️ **刻意不在界面展示**（[com.hnnujw.course.ui.screen.SecondClassPointStatsContent]
     * 的记录行已移除该字段）。原因：站点不给类型文案、也没有取值表，渲染出来就是
     * 一行行读不懂的数字 —— 实测大量记录的 `sourceType` 恰好是 `3`，用户看到的就是
     * "每个数字后面都有个 3"，纯噪声，还会把有用的分类/时间挤掉。
     *
     * 保留本属性只为了**可诊断性**（日志/调试时仍能看到原值），
     * 并把"为什么不显示"这个决策留在代码里，避免日后又被加回界面。
     */
    val sourceLabel: String get() = when (sourceType) {
        0 -> ""
        else -> "来源 $sourceType"
    }

    /**
     * 稳定标识：用于列表 key 与"同一笔账"的跨维度比对（[SecondClassPointLedger.reconcile]）。
     *
     * 必须把 `hours` 也算进去：同名活动可能被记两次账（不同档位 / 不同学期），
     * 只按名字去重会把真实的两笔合成一笔，那反而让账"对不上"。
     */
    val identity: String
        get() = listOf(name.trim(), hourKey(hours), time.toString(), relationId).joinToString("|")

    /** 小数比较的容差键：去掉浮点尾差，避免 3.0 与 3.0000001 被当成两条。 */
    private fun hourKey(value: Double): String =
        String.format(java.util.Locale.ROOT, "%.2f", value)
}

/**
 * 一个分组（一个分类 / 一个学期）的积分小计。
 *
 * [total] 有两个来源，优先级：**站点小计 > 本地记录求和**。
 * 站点给的小计是权威值（它还会算上没下发记录的账），本地求和只是兜底。
 * 两者不一致时由 [SecondClassPointLedger.reconcile] 标出来 —— 那正是
 * "有账对不上"的信号，应该让用户看见，而不是悄悄用本地值盖掉。
 */
data class SecondClassPointGroup(
    /** 分组名（分类名 / 学期名）。 */
    val name: String,
    /** 站点的 `classifyHours` / `termHours`；站点没给时为 null。 */
    val siteTotal: Double? = null,
    /** 学校要求的下限（分类维度有 `minHours`；学期维度没有）。 */
    val required: Double = 0.0,
    /** 明细记录。 */
    val records: List<SecondClassPointRecord> = emptyList(),
    /** 学期维度才有：学期序号（`termNumber`）。 */
    val termNumber: String = "",
    /** 学期维度才有：该学期积分的展示单位（`termHoursUnit`）。 */
    val unit: String = "",
) {
    /** 本地明细求和。[records] 为空时是 0 —— 调用方要看 [hasRecords] 再决定显示什么。 */
    val detailSum: Double get() = records.sumOf { it.hours }

    /** 展示用合计：站点小计优先，缺失才退回本地求和。 */
    val total: Double get() = siteTotal ?: detailSum

    val hasRecords: Boolean get() = records.isNotEmpty()

    /**
     * 站点小计与本地明细是否对得上（差 > 0.005 视为不一致）。
     * 站点没给小计时无从比较，返回 true（不误报）。
     */
    val reconciled: Boolean
        get() = siteTotal == null || kotlin.math.abs(siteTotal - detailSum) <= 0.005
}

/**
 * 一次「分类与学期统计」加载的完整结果。
 *
 * 两个维度是同一份账的两种切法，所以**必须一起加载**：
 * 只有一个维度时 [SecondClassPointLedger.reconcile]（跨维度对账）就没法做，
 * 而"每一分有迹可循"的核心正是这次对账。
 */
data class SecondClassPointLedger(
    val byClassify: List<SecondClassPointGroup> = emptyList(),
    val byTerm: List<SecondClassPointGroup> = emptyList(),
    /** 站点表头的总积分；用来和两个维度的小计再对一次。 */
    val profileScore: Double = 0.0,
    /** 分页游标：还有更多记录时由界面上的「继续加载」触发。 */
    val termHasMore: Boolean = false,
    val classifyHasMore: Boolean = false,
) {
    val isEmpty: Boolean get() = byClassify.isEmpty() && byTerm.isEmpty()

    /** 两个维度各自的合计（用于对账卡片）。 */
    val classifyTotal: Double get() = byClassify.sumOf { it.total }
    val termTotal: Double get() = byTerm.sumOf { it.total }

    /** 记录总条数 —— "有迹可循"的最直观指标。 */
    val recordCount: Int get() = (byClassify.sumOf { it.records.size }) + (byTerm.sumOf { it.records.size })

    companion object {
        /**
         * 跨维度对账。
         *
         * 判据是**分类维度的小计** vs **学期维度的小计**，因为它们是同一份账的两种
         * 切法，正常必然相等。差 > [TOLERANCE] 说明其中一边漏了记录（站点常见：
         * 学期维度不含补录的历史账），此时界面应该如实提示，而不是随便挑一个显示。
         *
         * 任一维度为空时不做判断（数据没拉全，比了也是错的）。
         */
        const val TOLERANCE: Double = 0.005

        fun reconcile(ledger: SecondClassPointLedger): Boolean {
            if (ledger.byClassify.isEmpty() || ledger.byTerm.isEmpty()) return true
            return kotlin.math.abs(ledger.classifyTotal - ledger.termTotal) <= TOLERANCE
        }
    }
}

/**
 * 一条「我的申报」记录（`/project/request/list1`）。
 *
 * 字段名取自实测响应。⚠️ **`status` 只有原始状态码，站点没有下发任何状态文案**
 * —— 实测该账号唯一一条记录是 `status = 2`，且传 `status=2` 能把它筛出来。
 * 在没有各状态的真实样本之前不臆造「已通过 / 已驳回」这种结论：
 * [statusLabel] 如实显示数字，等拿到样本再补映射。
 */
data class SecondClassApplication(
    val id: Int,
    /** 申报项目名（实测如「B类赛事受到校级及以上表彰」）。 */
    val projectName: String,
    /** 申报的具体档位（实测如「校级三等奖」）。 */
    val optionName: String,
    /** 所属模块（思想政治素养 / 创新创业能力 …）。 */
    val classifyName: String,
    /** 认定学时。实测与 [optionHours] 同值。 */
    val hours: Double,
    val optionHours: Double,
    /** 原始状态码，见类注释。 */
    val status: Int,
    val startTime: Long,
    val endTime: Long,
    /** 最近一次操作时间（站点的 `ltime`）。 */
    val lastTime: Long,
    val remark: String,
    /** 必修 / 选修（`projectLimitType.name`）。 */
    val limitTypeName: String,
) {
    val statusLabel: String get() = if (status > 0) "状态 $status" else "未知"

    /** 列表 key：同一项目可能有多档，只有 id 唯一。 */
    val identity: String get() = id.toString()
}

/** 第二课堂站点的业务异常。 [sessionExpired] 为真时上层应清掉本地 token 并提示重新登录。 */
class SecondClassException(
    message: String,
    val sessionExpired: Boolean = false,
) : Exception(message)

// ── 奖励申报（/service/declare，1.2.5）──────────────────────────────────

/**
 * 申报项目（未申报列表项，`/project/home/page/list`）。
 *
 * 站点每行展示：项目名 + 所属模块（classifyName）+ 「共 N 个奖项」，
 * 点「立即申请」进入填报页。
 */
data class SecondClassDeclareProject(
    val id: Int,
    val name: String,
    val classifyId: String = "",
    val classifyName: String = "",
    /** 该项目下可选的奖项（档位）数量。 */
    val optionsCount: Int = 0,
    /** 申报限制类型（`projectLimitType.name`：必修 / 选修…）。 */
    val limitTypeName: String = "",
    /** 状态：站点行内按钮据此显示「立即申请」或「已截止」。 */
    val status: Int = 0,
    /** 截止时的说明（站点 confirm 弹窗里给用户看的那句）。 */
    val closeApplyRemark: String = "",
) {
    /** 稳定的列表 key。 */
    val identity: String get() = id.toString()

    /** true = 站点标了「已截止」（status == 2，与 chunk26 的判定一致）。 */
    val closed: Boolean get() = status == 2
}

/** 申报项目下的一个奖项（档位），`/project/detail/info` 的 `optionList` 项。 */
data class SecondClassDeclareOption(
    val id: Int,
    val name: String,
    /** 认定学时。 */
    val hours: Double = 0.0,
    /**
     * 申请次数限制标识：0 = 不限；1 = 本学期已满；2 = 本学年已满。
     * 站点选中该项时直接 toast「本学期申请已达最大次数」—— 这里照搬该判定。
     */
    val awardsValid: Int = 0,
) {
    val label: String get() = buildString {
        append(name)
        if (hours > 0) append("（").append(hours).append("学时）")
    }
}

/** 申报项目详情（`/project/detail/info`）。 */
data class SecondClassDeclareProjectDetail(
    val id: Int,
    val name: String,
    /** 填写说明（站点「填写说明」弹窗的内容）。 */
    val explains: String = "",
    val options: List<SecondClassDeclareOption> = emptyList(),
) {
    val optionLabel: String get() = if (options.isEmpty()) "无" else options.size.toString()
}

/** 已上传的证明材料（提交载荷 `subData.urls[]` 的一项，字段与站点完全一致）。 */
data class SecondClassDeclareMaterial(
    val url: String,
    val name: String,
    val size: Long = 0,
    /** 站点的类型码：1 图片 / 3 文档 / 4 表格 / 5 PDF / 6 其它。 */
    val type: Int = 1,
) {
    fun toJson(): org.json.JSONObject = org.json.JSONObject()
        .put("id", 0)
        .put("url", url)
        .put("name", name)
        .put("size", size)
        .put("type", type)
}

/** 申报项目列表的一页（`/project/home/page/list` 的 `data`）。 */
data class SecondClassDeclarePage(
    val items: List<SecondClassDeclareProject> = emptyList(),
    val hasMore: Boolean = false,
)

/** 一次申报提交的载荷（`POST /project/apply/add_3_0_1` 的 `subData`）。 */
data class SecondClassDeclareSubmission(
    val projectId: Int,
    /** 选中的奖项（档位）id。 */
    val optionId: Int,
    /** 项目开始日期（epoch 毫秒，站点传 `new Date(...).getTime()`）。 */
    val startTime: Long,
    /** 项目结束日期（epoch 毫秒）。 */
    val endTime: Long,
    /** 总结报告。 */
    val report: String,
    /** 关联活动（可选；站点默认 null）。 */
    val relateActivityId: Int? = null,
    val relateActivityName: String = "",
    /** 证明材料（站点校验：必须至少 1 份）。 */
    val materials: List<SecondClassDeclareMaterial> = emptyList(),
)
