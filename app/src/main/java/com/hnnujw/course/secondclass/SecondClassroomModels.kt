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
