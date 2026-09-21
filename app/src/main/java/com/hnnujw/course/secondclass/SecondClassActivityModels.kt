package com.hnnujw.course.secondclass

import org.json.JSONArray
import org.json.JSONObject

/**
 * 第二课堂「活动」模块数据模型。
 *
 * 与成绩单模型分开一个文件：活动模块的字段数量与语义都独立于成绩单，
 * 混在一起会让 [SecondClassroomModels] 变成杂物间。
 *
 * ## 字段来源（全部为 2026-09-21 线上构建反解，非猜测）
 *
 * - 列表：`GET /activity/list` → `data.{list,total,lastPage}`
 * - 详情：`GET /activity/detail/participant`（已报名）/ `GET /activity/detail/non-member`（未报名）
 * - 分类：`GET /dict/activity/classify/list{parentId:0}` → `data` 即数组
 * - 我的活动：`GET /activity/my-list` → `data.{list,total,lastPage}`
 * - 报名：`POST /activity/enroll/person {id, personMaterial:[{key,value,filedValueTitle}]}`
 * - 取消报名：`POST /activity/enroll/cancel/person {id, applyInfo}`
 * - 打卡记录：`POST /activity/sign/one/list {userId, activityId}`、`GET /activity/my-sign-in-out-list`
 * - 签到/签退：`POST /activity/sign/in-out {id, userId, type, sp}`，`type` 2=签到签退，1=等待签到
 *
 * 站点时间字段是 **epoch 毫秒数**（前端直接 `new Date(x)` 再相减做倒计时），
 * 所以这里统一用 [Long]，不要当字符串解析。
 */

// ── 活动分类字典 ──────────────────────────────────────────────────────────

/** `GET /dict/activity/classify/list` 的一项。`id` 在站点上是数字，这里统一按字符串保存。 */
data class SecondClassActivityCategory(
    val id: String,
    val name: String,
)

// ── 活动列表排序 ──────────────────────────────────────────────────────────

/**
 * 列表排序方式。取值来自站点下拉框（`sortType`）：
 * 空串=默认排序，1=最新发布时间，2=最近活动时间，3=学时由高到低。
 */
enum class SecondClassActivitySort(val value: String, val label: String) {
    Default("", "默认排序"),
    Latest("1", "最新发布"),
    Recent("2", "最近活动"),
    HoursDesc("3", "学时最高"),
}

// ── 报名状态（客户端归一）────────────────────────────────────────────────

/**
 * 我在某个活动里的报名状态。
 *
 * 站点用两个字段表达：[SecondClassActivity.applyStatus] 与 `cancelStatus`。
 * - `applyStatus`：1=待审核，2=已通过，3=被驳回（文案实测："报名待审核"/"报名已通过"/"报名被驳回"）
 * - `cancelStatus != null`：已取消（覆盖 `applyStatus`）
 *
 * **未知取值一律归到 [Unknown]，且界面按"不可报名"处理** —— 宁可不给按钮，
 * 也不要让用户点下去才从服务端拿到一个失败。
 */
enum class SecondClassEnrollState(val label: String) {
    /** 未报名（未报名者视角的详情里 applyStatus 为 0/缺省）。 */
    NotEnrolled("未报名"),
    Pending("报名审核中"),
    Approved("报名已通过"),
    Rejected("报名被驳回"),
    Canceled("已取消报名"),
    Unknown("状态未知"),
    ;

    /** 是否处于"已报名成功"的状态（决定能不能扫码签到/取消报名）。 */
    val isEnrolled: Boolean get() = this == Approved
}

// ── 活动阶段（按时间算，不依赖服务端的 status 枚举）──────────────────────

/**
 * 活动的展示阶段。
 *
 * 站点把阶段塞在数字 `status` 里（实测出现 5/6/7/8/9/10/11/12 等），
 * 但**没有一个权威的取值表**。与其猜一组数字，这里按活动起止时间现算 ——
 * 时间字段是确定的，算出来的阶段也一定和用户看到的月份一致。
 */
enum class SecondClassActivityPhase(val label: String) {
    NotStarted("待开始"),
    Running("进行中"),
    Ended("已结束"),
    Unknown(""),
    ;

    companion object {
        /**
         * @param now 当前时间（毫秒），显式传入以便单测固定时间。
         */
        fun of(startTime: Long, endTime: Long, now: Long): SecondClassActivityPhase = when {
            startTime <= 0L && endTime <= 0L -> Unknown
            now < startTime -> NotStarted
            endTime > 0L && now > endTime -> Ended
            else -> Running
        }
    }
}

// ── 活动（列表项 / 我的活动项）────────────────────────────────────────────

/** 活动列表里的一条。列表接口与「我的活动」接口返回的形状一致。 */
data class SecondClassActivity(
    val id: Int,
    val name: String,
    val address: String = "",
    val hours: Double = 0.0,
    /** 已发放的学时（积分）。详情/我的活动接口返回；列表缺省按 0，展示时回退到 [hours]。 */
    val grantHours: Double = 0.0,
    val organizationName: String = "",
    val startTime: Long = 0L,
    val endTime: Long = 0L,
    val enrollStartTime: Long = 0L,
    val enrollEndTime: Long = 0L,
    /** 活动封面。站点上可能是相对路径或 aliyun 地址，交给图片库自行处理，失败走占位。 */
    val logo: String = "",
    val classifyName: String = "",
    val peopleLimit: Int = 0,
    val joinMemberCount: Int = 0,
    /** 见 [SecondClassEnrollState]。 */
    val applyStatus: Int = 0,
    /** 服务端阶段码，仅用于透传展示，界面阶段以 [phaseOf] 为准。 */
    val status: Int = 0,
    /** 非 null 表示已取消报名。 */
    val cancelStatus: Int? = null,
    val introduce: String = "",
    /**
     * 服务端给的复合「可报名」判定（`/page/activity/list` 返回）。
     *
     * ⚠️ **不要拿它当「本院系可报」用**。2026-09-21 用真实账号只读拉取做过对照：
     * `collegeLimit="0"`（对全校开放）的活动里，既有 `isAbleEnroll=1` 也有 `isAbleEnroll=0`
     * —— 它把年级 / 诚信分 / 名额 / 报名时间等条件一起揉进去了。用它当院系筛选，
     * 会把「不限院系、只是名额已满或年级不符」的活动整片误杀。
     *
     * 按院系筛选请用 [collegeLimit] + [enrollableForCollege]。
     */
    val isAbleEnroll: Boolean = true,
    /**
     * 院系限制，活动的**原始配置**（`/page/activity/list` 返回）。
     *
     * 取值实测两种形态：
     * - `"0"` 或空串 = **不限院系**（对应站点建活动表单里的 `collegeLimitTemp="0"`），全校都能报；
     * - 其余 = **逗号分隔的院系 id 列表**，只有 id 命中列表里的院系才可报。
     *
     * 与 [isAbleEnroll] 的区别：这个字段只表达「院系」这一个维度，是筛选的唯一依据。
     * 旧接口没有此字段时为空串 —— 按「不限院系」放行（宁多显示，不把列表筛空）。
     */
    val collegeLimit: String = "",
) {
    /** 我在这个活动里的报名状态。 */
    val enrollState: SecondClassEnrollState get() = enrollStateOf(applyStatus, cancelStatus)

    /** 名额进度；[peopleLimit] 为 0（不限）时返回 null，界面不画进度条。 */
    val quotaProgress: Float?
        get() = if (peopleLimit > 0) (joinMemberCount.toFloat() / peopleLimit).coerceIn(0f, 1f) else null

    /** 报名人数是否已满（peopleLimit 为 0 表示不限名额，永远不算满）。 */
    val isFull: Boolean
        get() = peopleLimit > 0 && joinMemberCount >= peopleLimit

    /** 网页端「我的活动」把 status 11/12 归为已结束，其余为进行中。 */
    val endedByStatus: Boolean
        get() = status == 11 || status == 12

    /** 已到手的学时（积分）：优先用服务端发的 [grantHours]，没发过就按活动标称 [hours]。 */
    val earnedHours: Double
        get() = if (grantHours > 0) grantHours else hours

    fun phaseOf(now: Long): SecondClassActivityPhase = SecondClassActivityPhase.of(startTime, endTime, now)

    /**
     * 报名窗口是否**已经截止**。`enrollEndTime` 缺省（0）表示不限期，视作未截止。
     *
     * 列表要据此把已经报不了名的活动剔除 —— 窗口关了还摆在列表上只会误导（点进去也报不了名）。
     * 与详情模型的 enrollmentOpenOf 的区别：这里只关心"截止"这一头，
     * "还没开始报名"的活动仍要留在列表里（详情页会显示禁用的「报名未开始」）。
     */
    fun enrollmentEndedOf(now: Long): Boolean = enrollEndTime > 0L && now > enrollEndTime

    /**
     * 这个活动是否对「我所在院系」开放报名（活动中心「本院系可报」筛选的唯一判据）。
     *
     * 语义严格照抄站点后端（`collegeLimit` 是活动的原始院系配置）：
     * - **不限院系**（`""` / `"0"`）→ 一律放行；
     * - **有院系列表** → 我所在的院系 id 命中列表才放行。
     *
     * 两个刻意的兜底，都是「宁可多显示、不可筛空」：
     * - [myCollegeId] <= 0（还没拿到本人院系，比如二课没绑定 / 接口失败）→ 一律放行，
     *   否则开关一开整个列表会变空，用户以为是「真没活动」；
     * - 列表里的项解析不出数字 → 跳过该项，不影响其它项判定。
     *
     * @param myCollegeId 本人院系 id，来自二课 `/student/achievement/detail` 的 `user.collegeId`。
     */
    fun enrollableForCollege(myCollegeId: Int): Boolean {
        val raw = collegeLimit.trim()
        if (raw.isEmpty() || raw == "0") return true
        if (myCollegeId <= 0) return true
        return raw.split(',').any { it.trim().toIntOrNull() == myCollegeId }
    }
}

/** 「消息」页的一条站内消息（`/message/notice/list`）。 */
data class SecondClassMessage(
    val id: Long = 0L,
    val type: Int = 0,
    val subType: Int = 0,
    val typeName: String = "",
    val subTypeName: String = "",
    val content: String = "",
    /** 毫秒时间戳；解析失败为 0。 */
    val time: Long = 0L,
    /** 0 = 未读，1 = 已读。 */
    val isRead: Boolean = false,
    /** 跳转目标（活动 id 等），当前仅用于展示，不自动跳页。 */
    val jumpInfo: String = "",
)

/** 一页站内消息（`/message/notice/list`）。 */
data class SecondClassMessagePage(
    val items: List<SecondClassMessage> = emptyList(),
    val total: Int = 0,
    val lastPage: Boolean = false,
) {
    val hasMore: Boolean get() = !lastPage && items.isNotEmpty()
}

/** 一页活动。站点列表接口的 `total` 是过滤后的总数，`lastPage` 为真表示没有下一页。 */
data class SecondClassActivityPage(
    val items: List<SecondClassActivity> = emptyList(),
    val total: Int = 0,
    val pageNum: Int = 1,
    val pageSize: Int = 20,
    val lastPage: Boolean = false,
) {
    /** 是否还能继续翻页。 */
    val hasMore: Boolean get() = !lastPage && items.isNotEmpty() && (total <= 0 || items.size < total)
}

// ── 活动详情 ──────────────────────────────────────────────────────────────

/**
 * 活动详情。字段取自 `/activity/detail/participant` 与 `/activity/detail/non-member`。
 *
 * 两个端点的差别是「我」的视角字段（`applyStatus` / `isManager` / `isSigner` /
 * `signInCount` …），活动本身的字段一致。
 */
data class SecondClassActivityDetail(
    val id: Int,
    val name: String,
    val address: String = "",
    val introduce: String = "",
    val logo: String = "",
    val classifyName: String = "",
    val organizationName: String = "",
    val managerName: String = "",
    val contact: String = "",
    val startTime: Long = 0L,
    val endTime: Long = 0L,
    val enrollStartTime: Long = 0L,
    val enrollEndTime: Long = 0L,
    val peopleLimit: Int = 0,
    val joinMemberCount: Int = 0,
    val hours: Double = 0.0,
    val grantHours: Double = 0.0,
    val gradeList: List<String> = emptyList(),
    val collegeList: List<String> = emptyList(),
    val genderLimit: Int = 0,
    val applyStatus: Int = 0,
    val status: Int = 0,
    val cancelStatus: Int? = null,
    /** 报名被驳回时的理由。 */
    val applyRejectReason: String = "",
    /** 我是否是本活动的管理者（决定是否展示组织侧信息，**不含任何组织侧写操作**）。 */
    val isManager: Boolean = false,
    /** 我是否是本活动的签到员。 */
    val isSigner: Boolean = false,
    /** 是否开启了签到。0 = 未开启。 */
    val signSwitch: Int = 0,
    /**
     * 已启用的签到方式，**字符串位掩码**（实测站点用 `signWay.indexOf("n")` 判断）：
     * 含 `"1"` / `"2"` = 支持二维码/扫码签到签退；含 `"3"` = 支持定位打卡。
     */
    val signWay: String = "",
    /** 报名人数限制方式：0=不限，1=个人，2=团队。 */
    val signLimit: Int = 0,
    val signInCount: Int = 0,
    val signOutCount: Int = 0,
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    /** 活动是否配置了需要采集的材料。 */
    val isHaveCollect: Boolean = false,
    /** 活动附件（通知、报名表、说明文档…）。站点字段是 `attachment[]`。 */
    val attachments: List<SecondClassAttachment> = emptyList(),
) {
    val enrollState: SecondClassEnrollState get() = enrollStateOf(applyStatus, cancelStatus)

    fun phaseOf(now: Long): SecondClassActivityPhase = SecondClassActivityPhase.of(startTime, endTime, now)

    /**
     * 是否支持"二维码 / 扫码"这一路签到。
     *
     * 对齐站点条件：`signSwitch != 0` 且 `signWay` 含 `"1"` 或 `"2"`
     * （站点用完全相同的判断决定是否显示「扫一扫」与「二维码」两个入口）。
     */
    val supportsCodeSign: Boolean
        get() = signSwitch != 0 && (signWay.contains("1") || signWay.contains("2"))

    /** 是否只支持定位打卡（`signWay` 含 `"3"`）。App 不做定位打卡，只做引导。 */
    val supportsLocationSign: Boolean get() = signSwitch != 0 && signWay.contains("3")

    /** 名额进度；[peopleLimit] 为 0 时返回 null。 */
    val quotaProgress: Float?
        get() = if (peopleLimit > 0) (joinMemberCount.toFloat() / peopleLimit).coerceIn(0f, 1f) else null

    /** 报名窗口是否还开着；两个时间都缺省时按"不限"处理。 */
    fun enrollmentOpenOf(now: Long): Boolean = when {
        enrollStartTime > 0L && now < enrollStartTime -> false
        enrollEndTime > 0L && now > enrollEndTime -> false
        else -> true
    }
}

// ── 报名材料字段 ──────────────────────────────────────────────────────────

/**
 * 报名需要填写的采集字段，来自 `GET /activity/material/list`。
 *
 * 站点字段名是 `filedId` / `filedName` / `filedType` / `filedValueTitle`
 * （"filed" 是服务端自己的拼写，照抄以免对不上）。
 * **绝大多数活动该接口返回空数组** —— 那样的报名体就是 `{id, personMaterial:[]}`。
 */
data class SecondClassEnrollField(
    /** 提交时的 `key`，对应站点的 `filedId`。 */
    val key: String,
    /** 字段显示名，对应 `filedName`。 */
    val name: String,
    /** 提交时回显的标题，对应 `filedValueTitle`。 */
    val title: String = "",
    /** 输入类型（站点是 `filedType`，常见为 text 类）。 */
    val type: String = "",
    /** 是否必填。站点未给必填位，按"有值的都传、空的不传"处理，故默认 false。 */
    val required: Boolean = false,
) {
    /** 是否是多行文本（按类型名粗判，仅影响输入框行数）。 */
    val multiline: Boolean get() = type.contains("area", ignoreCase = true) || type.contains("text")
}

/** 一条报名答案，可直接序列化成站点要求的 `personMaterial` 元素。 */
data class SecondClassEnrollAnswer(
    val key: String,
    val value: String,
    val title: String,
) {
    /**
     * 站点原样字段名（实测反解 `collectData.map(...)`）：
     * `{key: filedId, value: filedValue, filedValueTitle: filedValueTitle}`。
     * 注意是 `value` 而不是 `filedValue` —— 这是站点的实际契约，不要"修正"。
     */
    fun toJson(): JSONObject = JSONObject()
        .put("key", key)
        .put("value", value)
        .put("filedValueTitle", title)
}

// ── 打卡记录 ──────────────────────────────────────────────────────────────

/**
 * 一条签到/签退记录。
 *
 * 记录接口的字段名站点没有文档，这里做**宽松解析**：常见命名都试一遍，
 * 实在认不出来的标量字段收进 [extras] 原样展示 —— 宁可丑一点，
 * 也不要因为字段改名而给用户一个空白列表。
 */
data class SecondClassSignRecord(
    val id: String = "",
    val activityId: Int = 0,
    val activityName: String = "",
    val userName: String = "",
    /** "签到" / "签退" / 其它服务端原文。 */
    val typeLabel: String = "",
    /** epoch 毫秒；0 表示服务端没给可解析的时间。 */
    val time: Long = 0L,
    val address: String = "",
    /** 未能映射的字段，按原样 key → value 展示。 */
    val extras: List<Pair<String, String>> = emptyList(),
)

// ── 签到计数（二维码的 sp 种子）─────────────────────────────────────────────

/**
 * `GET /activity/sign/count` 的返回。
 *
 * 站点用它做两件事：`data` 作为"签到人数"展示，`timestamp` 作为**动态二维码的 `sp` 初值**
 * （之后每 10 秒 `timestamp += 10000`，即 `sp` 是一个毫秒时间戳）。
 * 少了这个种子，动态码就与签到员那端的计数对不上。
 */
data class SecondClassSignCount(
    val value: Int = 0,
    val timestamp: Long = 0L,
)

// ── 活动通知 ──────────────────────────────────────────────────────────────

/** 活动通知，来自 `GET /activity/noticeList` 的 `data.list`。 */
data class SecondClassActivityNotice(
    val id: String = "",
    val title: String = "",
    val content: String = "",
    val createTime: Long = 0L,
    val publisher: String = "",
)

// ── 附件 ──────────────────────────────────────────────────────────────────

/** 附件的粗分类。数值来自站点的 `attachmentType`（其前端按它挑图标）。 */
enum class SecondClassAttachmentKind(val label: String) {
    Image("图片"),
    Word("Word 文档"),
    Excel("Excel 表格"),
    Pdf("PDF 文档"),
    Text("文本"),
    Unknown("文件");

    companion object {
        /**
         * @param type 站点的 `attachmentType`：1=图片，3=Word，4=Excel，5=PDF，6=未知。
         * @param extension 小写扩展名，作为站点没给类型时的兜底（站点自己也用后缀判 txt）。
         */
        fun of(type: Int, extension: String): SecondClassAttachmentKind = when {
            type == 1 -> Image
            type == 3 -> Word
            type == 4 -> Excel
            type == 5 -> Pdf
            extension in IMAGE_EXTENSIONS -> Image
            extension in WORD_EXTENSIONS -> Word
            extension in SHEET_EXTENSIONS -> Excel
            extension == "pdf" -> Pdf
            extension == "txt" -> Text
            else -> Unknown
        }

        private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp")
        private val WORD_EXTENSIONS = setOf("doc", "docx")
        private val SHEET_EXTENSIONS = setOf("xls", "xlsx", "csv")
    }
}

/**
 * 活动附件。站点字段实测为 `{name, url, attachmentType}`，`url` 是**直链**
 * （站点自己就是 `window.open(url)`），所以下载**不需要**带 Authorization 头。
 */
data class SecondClassAttachment(
    val name: String,
    val url: String,
    val attachmentType: Int = 0,
) {
    /** 小写扩展名，取不到时为空串。 */
    val extension: String get() = name.substringAfterLast('.', "").lowercase()

    val kind: SecondClassAttachmentKind get() = SecondClassAttachmentKind.of(attachmentType, extension)

    /**
     * 能否用内置查看器渲染。
     *
     * 只有 OOXML（`.docx` / `.xlsx`）与 PDF / 图片 / 文本可以：它们分别是
     * ZIP+XML 与位图流，用 JDK 自带的 `java.util.zip` / `XmlPullParser` / `PdfRenderer`
     * 就能读，**不需要任何第三方库**。
     *
     * 旧版 `.doc` / `.xls` 是 OLE2 复合文档 + BIFF/Word97 二进制，需要一整个
     * 解析器（Apache POI 在 Android 上还要拖进 xmlbeans），代价远大于收益 ——
     * 一律引导用户用系统里的 WPS / Office 打开。
     */
    val canPreviewInApp: Boolean
        get() = when (kind) {
            SecondClassAttachmentKind.Image,
            SecondClassAttachmentKind.Pdf,
            SecondClassAttachmentKind.Text,
            -> true

            SecondClassAttachmentKind.Word -> extension == "docx"
            SecondClassAttachmentKind.Excel -> extension == "xlsx" || extension == "csv"
            SecondClassAttachmentKind.Unknown -> false
        }

    /** 内置查看器打不开时给用户的解释。能用内置查看器时为空串。 */
    val unsupportedReason: String
        get() = when {
            canPreviewInApp -> ""
            kind == SecondClassAttachmentKind.Word ->
                "旧版 .doc 是二进制格式，内置查看器只支持 .docx，请用 WPS / Office 打开"
            kind == SecondClassAttachmentKind.Excel ->
                "旧版 .xls 是二进制格式，内置查看器只支持 .xlsx，请用 WPS / Office 打开"
            extension.isBlank() -> "这个附件没有扩展名，无法判断格式"
            else -> "内置查看器暂不支持 .$extension，请用其它应用打开"
        }
}

// ── 扫码载荷 ──────────────────────────────────────────────────────────────

/**
 * 扫到的码解析结果。
 *
 * ## 站点实际格式（2026-09-21 线上构建反解）
 *
 * 签到码是**明文**的（站点 `Encrypts`/`Decrypts` 都是恒等函数
 * `function(t){return t}`，见 app.js 模块 `6Y3k`），形如：
 *
 * ```
 * qutuo://waitSign?activityId=7358&userId=12345&sp=1787823794093?yiban=yiban_scan_result
 * ```
 *
 * 末尾 `?yiban=yiban_scan_result` 是易班扫码器回传时的固定后缀，必须先剥掉。
 *
 * 组织者码（网页版）形如
 * `https://ekta.hnnu.edu.cn/?sourceName=schActivityCode@Xj&activityid=7358`，
 * 学生扫它只是"进入活动页"，不产生签到场次。
 */
sealed interface SecondClassScanPayload {
    /**
     * 签到码。`waitSign == false` 表示正式签到/签退码（提交 `type=2`），
     * `waitSign == true` 表示等待签到码（提交 `type=1`）。
     */
    data class SignCode(
        val activityId: Int,
        val userId: String,
        val sp: String,
        val waitSign: Boolean,
    ) : SecondClassScanPayload {
        /** 站点提交用的 `type`：`indexOf("sign") > -1 ? 2 : 1`。 */
        val submitType: Int get() = if (waitSign) 1 else 2
    }

    /** 组织者展示的活动码 → 打开活动详情。 */
    data class ActivityCode(val activityId: Int) : SecondClassScanPayload

    /** 组织码（`joinOrganization`）→ 本 App 不处理，仅识别。 */
    data class OrganizationCode(val id: String) : SecondClassScanPayload

    /** 不认识的普通文本。 */
    data class Text(val raw: String) : SecondClassScanPayload
}

/**
 * 扫码载荷的解析与生成。**纯函数**，不碰网络，便于单测固定每种形态。
 */
object SecondClassScanCodec {

    /** 易班扫码器附加的后缀，站点在解析前会先 `replace` 掉。 */
    private const val YIBAN_SUFFIX = "yiban=yiban_scan_result"

    /** 站点自己的深链协议。 */
    const val SCHEME = "qutuo"

    /** 检查「我的签到码」时使用的协议前缀。 */
    const val WAIT_SIGN_PREFIX = "qutuo://waitSign?"

    /**
     * 解析扫到的原始字符串。
     *
     * 顺序与站点一致：先剥易班后缀，再按 `?` 后面的 query 取参数。
     * 站点用 `i.split("?")[1]` 取第一段 query，这里更宽容：把所有 query 段合并后再取值，
     * 这样 `...&sp=1?yiban=...` 这种"后缀粘在最后一个参数上"也不会把 sp 弄脏。
     */
    fun parse(raw: String): SecondClassScanPayload {
        val cleaned = raw.trim().removeSuffix("?" + YIBAN_SUFFIX).removeSuffix(YIBAN_SUFFIX)
        if (cleaned.isEmpty()) return SecondClassScanPayload.Text(raw)

        // 站点码：qutuo://sign?activityId=..&userId=..&sp=..
        if (cleaned.contains("$SCHEME://")) {
            val query = parseQuery(cleaned)
            val activityId = query["activityId"]?.toIntOrNull()
            if (activityId != null && activityId > 0) {
                if (cleaned.contains("joinOrganization")) {
                    return SecondClassScanPayload.OrganizationCode(query["id"].orEmpty())
                }
                return SecondClassScanPayload.SignCode(
                    activityId = activityId,
                    userId = query["userId"].orEmpty(),
                    sp = query["sp"].orEmpty(),
                    waitSign = cleaned.contains("waitSign"),
                )
            }
            query["id"]?.takeIf { it.isNotBlank() }?.let {
                return SecondClassScanPayload.OrganizationCode(it)
            }
            return SecondClassScanPayload.Text(raw)
        }

        // 组织者网页码：https://<host>/?sourceName=..&activityid=..  或 ?activityid=..&id=..
        val query = parseQuery(cleaned)
        val activityId = (query["activityid"] ?: query["activityId"])?.toIntOrNull()
        if (activityId != null && activityId > 0) return SecondClassScanPayload.ActivityCode(activityId)
        query["id"]?.takeIf { it.isNotBlank() }?.let {
            if (cleaned.contains("joinOrganization")) return SecondClassScanPayload.OrganizationCode(it)
        }
        return SecondClassScanPayload.Text(raw)
    }

    /**
     * 生成"我的签到码"，内容与站点 `getNormalQrcode` 完全一致：
     * `qutuo://waitSign?activityId=<id>&userId=<uid>&sp=<时间戳>?yiban=yiban_scan_result`
     */
    fun buildWaitSignCode(activityId: Int, userId: String, sp: Long): String =
        "$WAIT_SIGN_PREFIX" + "activityId=$activityId&userId=$userId&sp=$sp" + "?" + YIBAN_SUFFIX

    /**
     * 扫码提交前的判定结果。
     *
     * 把"能不能提交"做成纯函数是本模块最重要的设计：写操作一旦点下去就无法撤回，
     * 而判断依据（码内 userId、我是否已报名、我是不是签到员）全都来自网络数据，
     * 必须先在一个可单测的地方定死。
     */
    sealed interface SignScanDecision {
        /** 可以提交。[type] 即 `/activity/sign/in-out` 的 `type`。 */
        data class Allow(val type: Int, val note: String) : SignScanDecision

        /** 拒绝，[reason] 直接展示给用户。 */
        data class Blocked(val reason: String) : SignScanDecision
    }

    /**
     * 判断这次扫码能否提交签到。
     *
     * ## 站点真实流程（决定了这里的规则，不要凭直觉改）
     *
     * 活动详情页里 `openQrcode` 分两种码：
     *
     * - `isadminQrcode == true`（**签到员**）→ `qutuo://sign?...&userId=<签到员>`，
     *   页面上给的提示是"扫描上方二维码就可签到签退" —— 即**学生扫签到员出示的码**。
     *   这类码里 `userId` 是**签到员的**，提交 `type=2`，服务端按请求者的 token 记账。
     * - `isadminQrcode == false`（普通学生）→ `qutuo://waitSign?...&userId=<我自己>`，
     *   是**我的码，给签到员扫**。这类码里 `userId` 就是码主人的身份，提交 `type=1`。
     *
     * 所以"`userId` 不等于我就一律拒绝"是错的 —— 那会把最主流的
     * "学生扫签到员的码"整条路径堵死。真正需要拦的是**type=1 且码不是我的**：
     * 那是"我替同学提交他自己的码"，也就是代签。
     *
     * @param enrolled 我是否已报名该活动（`applyStatus == 2`）。
     * @param isSigner 我是否是该活动的签到员（详情里的 `isSigner`）。
     */
    fun evaluateSignScan(
        payload: SecondClassScanPayload,
        myUserId: String,
        enrolled: Boolean,
        isSigner: Boolean,
    ): SignScanDecision = when (payload) {
        is SecondClassScanPayload.ActivityCode ->
            SignScanDecision.Blocked("这是活动二维码，请在活动详情页里签到")

        is SecondClassScanPayload.OrganizationCode ->
            SignScanDecision.Blocked("这是组织码，App 暂不支持")

        is SecondClassScanPayload.Text ->
            SignScanDecision.Blocked("无法识别的二维码，请确认扫的是活动签到码")

        is SecondClassScanPayload.SignCode -> when {
            payload.sp.isBlank() ->
                SignScanDecision.Blocked("签到码缺少校验参数，请让组织者刷新二维码后重试")

            !enrolled ->
                SignScanDecision.Blocked("你还没有成功报名这个活动，无法签到")

            // type=2：签到员出示的码。学生扫它签到/签退，是站点设计的主路径。
            !payload.waitSign ->
                SignScanDecision.Allow(payload.submitType, "签到 / 签退")

            // type=1：这是某个学生自己的码。只有当我是本活动签到员时才代为提交。
            payload.userId == myUserId ->
                SignScanDecision.Allow(payload.submitType, "确认本人签到")

            isSigner ->
                SignScanDecision.Allow(payload.submitType, "你是本活动签到员，为该同学补签")

            else ->
                SignScanDecision.Blocked("这是同学的签到码，代他人签到违反考勤规定，已阻止")
        }
    }
}

// ── 共享的工具 ────────────────────────────────────────────────────────────

/**
 * `applyStatus` + `cancelStatus` → [SecondClassEnrollState]。
 *
 * 站点实测映射：1=报名待审核，2=报名已通过，3=报名被驳回；
 * `cancelStatus` 非 null 时一律是"已取消"。
 * 未知取值 → [SecondClassEnrollState.Unknown]（界面据此禁用报名按钮）。
 */
internal fun enrollStateOf(applyStatus: Int, cancelStatus: Int?): SecondClassEnrollState {
    if (cancelStatus != null) return SecondClassEnrollState.Canceled
    return when (applyStatus) {
        0 -> SecondClassEnrollState.NotEnrolled
        1 -> SecondClassEnrollState.Pending
        2 -> SecondClassEnrollState.Approved
        3 -> SecondClassEnrollState.Rejected
        else -> SecondClassEnrollState.Unknown
    }
}

/**
 * 合并「未报名视角」与「已报名视角」两份活动详情。
 *
 * `participant` 端点对**没有报名记录**的用户会回一份缺省的报名状态
 * （实测表现为 `applyStatus=3` 且没有驳回原因），直接采信会让每个活动
 * 都顶着"已驳回"角标。因此只有 participant 端点给出**可信证据**时才
 * 采纳它的"我的"字段：
 * - `applyStatus` 1（待审核）/ 2（已通过）直接采信；
 * - 3（驳回）仅在 `applyRejectReason` 非空时采信 —— 真驳回站点都带理由；
 * - 其余取值一律按未报名处理；
 * - `cancelStatus` 只有非 0 才算"已取消"（0 视为缺省值）。
 * 活动本身字段（名称/时间/附件…）以 non-member 那份为准，不做覆盖。
 */
internal fun SecondClassActivityDetail.withParticipantView(mine: SecondClassActivityDetail?): SecondClassActivityDetail {
    if (mine == null) return this
    val cancel = mine.cancelStatus?.takeIf { it != 0 }
    val status = if (cancel != null) {
        mine.applyStatus
    } else {
        when (mine.applyStatus) {
            1, 2 -> mine.applyStatus
            3 -> if (mine.applyRejectReason.isNotBlank()) 3 else 0
            else -> 0
        }
    }
    return copy(
        applyStatus = status,
        cancelStatus = cancel,
        applyRejectReason = if (status == 3) mine.applyRejectReason else "",
        isManager = mine.isManager,
        isSigner = mine.isSigner,
        signInCount = mine.signInCount,
        signOutCount = mine.signOutCount,
    )
}

/**
 * 把活动 `logo` 解析成可直接加载的封面 URL。
 *
 * 站点可能回**绝对地址**（阿里云 OSS），也可能回以 `/` 开头的**站内相对路径**；
 * 相对路径用 API 根地址推断站点源（`scheme://host`）补全。取不到时返回空串，
 * 界面据此走占位图标。
 */
fun resolveActivityCoverUrl(logo: String, apiBaseUrl: String): String {
    val raw = logo.trim()
    if (raw.isEmpty() || raw.equals("null", true)) return ""
    if (raw.startsWith("http://", true) || raw.startsWith("https://", true)) return raw
    val base = apiBaseUrl.trim().trimEnd('/')
    val schemeEnd = base.indexOf("://")
    if (schemeEnd <= 0) return raw
    val hostEnd = base.indexOf('/', schemeEnd + 3)
    val origin = if (hostEnd < 0) base else base.substring(0, hostEnd)
    return if (raw.startsWith("/")) origin + raw else "$origin/$raw"
}

/**
 * 从任意 URL 形态里取出 query 参数。
 *
 * 不能用 `android.net.Uri`：这是纯 Kotlin 工具，且要能在 JVM 单测里跑。
 * 同时把 `qutuo://waitSign?a=1&b=2` 这种"非标准 scheme"也覆盖到
 * （标准 `URLDecoder` 不认它）。
 */
private fun parseQuery(text: String): Map<String, String> {
    val result = LinkedHashMap<String, String>()
    val question = text.indexOf('?')
    val body = if (question >= 0) text.substring(question + 1) else return result
    // 去掉易班后缀可能带来的第二段 query
    body.split('?').forEach { segment ->
        segment.split('&').forEach { pair ->
            if (pair.isEmpty()) return@forEach
            val eq = pair.indexOf('=')
            val key = if (eq >= 0) pair.substring(0, eq) else pair
            val value = if (eq >= 0) pair.substring(eq + 1) else ""
            val decodedKey = decodeLoose(key)
            if (decodedKey.isBlank() || result.containsKey(decodedKey)) return@forEach
            result[decodedKey] = decodeLoose(value)
        }
    }
    return result
}

/**
 * 百分号解码，失败时原样返回。
 *
 * 不用 `URLDecoder.decode` 的原因：它会把 `+` 解成空格，
 * 而这里的值（`schActivityCode@Xj`、时间戳）里 `+` 不该被改写；
 * 且它对非法转义会抛异常，扫码内容不可信，不能让它崩。
 */
private fun decodeLoose(text: String): String {
    if (!text.contains('%')) return text
    val out = StringBuilder(text.length)
    var i = 0
    while (i < text.length) {
        val c = text[i]
        if (c == '%' && i + 2 < text.length) {
            val hex = text.substring(i + 1, i + 3)
            val code = hex.toIntOrNull(16)
            if (code != null) {
                out.append(code.toChar())
                i += 3
                continue
            }
        }
        out.append(c)
        i++
    }
    return out.toString()
}

/** 把任意 JSONArray 元素映射成列表（沿用成绩单模块的容错风格）。 */
internal inline fun <T> JSONArray?.mapItems(transform: (JSONObject) -> T): List<T> {
    if (this == null) return emptyList()
    val list = ArrayList<T>(length())
    for (i in 0 until length()) optJSONObject(i)?.let { list += transform(it) }
    return list
}
