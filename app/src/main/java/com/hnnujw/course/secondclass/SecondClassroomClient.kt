package com.hnnujw.course.secondclass

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 第二课堂站点的只读客户端。
 *
 * ## 协议要点（实测自 ekta.hnnu.edu.cn）
 *
 * - 请求体/查询串统一是 `params=<编码后的 JSON>`，且是**双重 URL 编码**：
 *   站点前端 `encodeURI(JSON)` 之后又交给 qs 编码一次，服务端也解两次。
 *   只编一层会被拒（`参数格式不正确，参数必须是JSON格式`）。
 * - 站点前端的 `Encrypt` 在该构建里是恒等函数，所以没有签名/加密，照抄编码即可。
 * - 认证靠 `Authorization: <access_token>`，没有 Cookie 依赖。
 *
 * 写接口只做站点**已开放给学生的**报名与申报提交（报名 / 取消报名 / 奖励申报），
 * 序列化与站点 H5 完全一致；组织侧的 sign/switch、sign/repair 等管理通道一律没有。
 */
class SecondClassroomClient(
    baseUrl: String,
    private val schoolCode: String,
    private val client: OkHttpClient = defaultClient(),
) {
    private val base = baseUrl.trim().trimEnd('/')

    // ── 认证 ──────────────────────────────────────────────────────────────

    /**
     * 换取 access_token。凭据错误会抛 [SecondClassException]（sessionExpired=false）。
     *
     * **令牌在统一信封的 `data` 里，不在顶层** —— 本站所有接口都是
     * `{code:0, data:{…}}` 的形状（本文件其余方法也都从 `data` 取数）。
     * 只读顶层 `access_token` 会在登录**已经成功**的情况下误报"缺少 access_token"。
     * 这里按 `data.access_token` → `data.token` → 顶层同名键 的顺序兜底，
     * 并兼容 `data` 直接是字符串的构建。
     */
    suspend fun login(studentId: String, password: String): String {
        val body = JSONObject()
            .put("schoolCode", schoolCode)
            .put("code", studentId)
            .put("password", password)
        val payload = post("/token", body, token = null)
        val data = payload.opt("data")
        val token = (
            when {
                data is JSONObject -> firstToken(data)
                data is String -> data.takeIf { it.isNotBlank() }
                else -> null
            } ?: firstToken(payload)
            ).orEmpty()
        if (token.isBlank()) {
            val where = if (data is JSONObject) {
                "data 字段：" + data.keys().asSequence().joinToString(",")
            } else {
                "顶层字段：" + payload.keys().asSequence().joinToString(",")
            }
            throw SecondClassException("登录返回缺少 access_token（$where）")
        }
        return token
    }
    /**
     * 令牌字段在不同构建/网关下命名不一，按常见命名依次取。
     *
     * 必须用 [text] 而不是 `optString`：后者对 JSON `null` 会返回字符串
     * `"null"`，于是"登录其实失败了"会被当成"拿到了 token 'null'"，
     * 后面的请求再带着一个假令牌去撞 10001。
     */
    private fun firstToken(json: JSONObject): String? =
        sequenceOf("access_token", "token", "accessToken")
            .map { json.text(it) }
            .firstOrNull { it.isNotBlank() }

    // ── 查询 ──────────────────────────────────────────────────────────────

    /** 成绩单表头：姓名/学号/院系/专业/年级 + 总积分 + 学时。 */
    suspend fun profile(token: String): SecondClassProfile {
        val data = get("/student/achievement/detail", null, token).optJSONObject("data") ?: return SecondClassProfile()
        val user = data.optJSONObject("user") ?: JSONObject()
        val myInfo = runCatching { get("/student/user/my-info", null, token).optJSONObject("data") }
            .getOrNull() ?: JSONObject()
        fun text(key: String): String = user.text(key).ifBlank { myInfo.text(key) }
        return SecondClassProfile(
            name = text("name"),
            code = text("code"),
            collegeName = text("collegeName"),
            // 院系 id：只用于活动中心「本院系可报」筛选。两个端点都可能带，谁先给有效值用谁。
            collegeId = user.opt("collegeId").asInt().takeIf { it > 0 }
                ?: myInfo.opt("collegeId").asInt(),
            majorName = text("majorName"),
            grade = text("grade"),
            score = user.optDouble("score", 0.0),
            hours = user.optDouble("hours", 0.0),
            scoreUnit = unitLabel(myInfo.opt("waysConvert").asInt()),
            hourUnit = myInfo.text("hourUnit"),
            avatar = text("avatar"),
            gender = user.opt("gender").asInt(),
        )
    }

    /**
     * 各模块积分状况。站点首页的雷达图用的就是这份数据：
     * `tags[].userValue` 是我的积分，`tags[].minHours` 是学校要求的下限。
     */
    suspend fun modules(token: String): List<SecondClassModule> {
        val data = get("/student/achievement/detail-app", null, token).optJSONObject("data") ?: return emptyList()
        return data.optJSONArray("tags").mapObjects { tag ->
            SecondClassModule(
                name = tag.text("name"),
                mine = tag.optDouble("userValue", 0.0),
                required = tag.optDouble("minHours", 0.0),
                average = tag.optDouble("schoolValue", 0.0),
            )
        }.filter { it.name.isNotBlank() }
    }

    /**
     * 指定层级的**完整**榜单 + 我的名次。
     *
     * ## 为什么要翻页，而不是一次 pageSize=20 了事
     *
     * 站点一次只回 `pageSize` 条，而响应里**没有 `lastPage`**（实测 `data` 只有
     * `total / pageNum / pageSize / list` 四个键）。原先写死 `pageSize = 20`，
     * 班级 37 人时后 17 个人**根本不进列表** —— 用户看到的"班级排名不全"就是它。
     *
     * ## 两个实测到的坑
     *
     * 1. **`rownum` 会并列**：班级 37 人里第 29、35 名各出现两次。所以翻页拼接
     *    不能按名次去重，必须按学号 `code`。
     * 2. **`pageSize` 放大是安全的**：实测 20/50/100/200/1000/5000 都能接受，
     *    `pageSize=50` 起一次就把 37 人全给了。所以这里先用一个较大的页长
     *    （[BOARD_PAGE_SIZE]）一次拿全，只有 `total` 比它还大时才继续翻页。
     *    这样绝大多数请求只打一次网络，同时"多少人都不漏"由循环兜住。
     *
     * 服务端若某天忽略 `pageNum`（一直回第一页），下面靠"本页没有新学号就收手"
     * 退出，不会死循环；[MAX_BOARD_PAGES] 是第二道保险。
     */
    suspend fun rankBoard(
        token: String,
        level: SecondClassRankLevel,
        pageSize: Int = BOARD_PAGE_SIZE,
    ): SecondClassRankBoard {
        val entries = mutableListOf<SecondClassRankEntry>()
        val seen = HashSet<String>()
        var total = 0
        var page = 1
        while (page <= MAX_BOARD_PAGES) {
            val request = JSONObject()
                .put("pageNum", page)
                .put("pageSize", pageSize)
                .put("level", level.level)
            val data = get("/student/achievement/rank", request, token).optJSONObject("data")
            val rows = data?.optJSONArray("list").mapObjects { it.toRankEntry() }
            if (page == 1) total = data?.optInt("total", rows.size) ?: rows.size
            if (rows.isEmpty()) break
            var added = 0
            for (row in rows) {
                if (seen.add(row.identity)) {
                    entries += row
                    added++
                }
            }
            // 本页一个新行都没贡献：说明服务端忽略了 pageNum，继续翻只会重复
            if (added == 0) break
            if (entries.size >= total) break
            // 不满一页说明已经是最后一页
            if (rows.size < pageSize) break
            page++
        }

        // 服务端的 rownum 只能当"顺序提示"，不能当名次显示（见 rerank 的说明）。
        val ranked = rerank(entries)

        val mine = runCatching { myRank(token, level) }.getOrNull()
            ?.let { entry ->
                // 我的名次同样以榜单内算出的名次为准：self/rank 的 rownum 也是服务端值，
                // 会出现"榜单里我排第 24、卡片上却写第 24 名"这种对不上的情况（并列时尤其明显）。
                val inBoard = ranked.firstOrNull { it.code.isNotBlank() && it.code == entry.code }
                    ?: ranked.firstOrNull { it.name == entry.name && it.score == entry.score }
                if (inBoard != null) entry.copy(rank = inBoard.rank) else entry
            }
        return SecondClassRankBoard(level = level, entries = ranked, myRank = mine, total = total)
    }

    /**
     * 用分数重算名次，**并列同名次**。
     *
     * ## 为什么不能用服务端的 `rownum`
     *
     * 实测班级 37 人，服务端给的 `rownum` 最大值只有 **35**：第 29 名和第 35 名
     * 各出现两次（并列），而 36、37 两个名次号被"挤掉"。直接显示就会出现
     * "两个 29 名、两个 35 名，最后一名不是 37"——用户看到的"排序的数字有问题"。
     *
     * 另外 `rownum` 在部分层级是浮点（院系层级实测 `233.0`），也不能直接当整数用。
     *
     * ## 规则
     *
     * 按 `score` 降序：同分者共享同一个名次（竞赛排名法，1/2/2/4），
     * 名次号等于"排名在我前面的人数 + 1"，因此**最大名次不会超过总人数**。
     * 名次相同时按学号稳定排序，保证每次刷新顺序一致。
     */
    private fun rerank(entries: List<SecondClassRankEntry>): List<SecondClassRankEntry> {
        if (entries.isEmpty()) return entries
        val sorted = entries.sortedWith(
            compareByDescending<SecondClassRankEntry> { it.score }.thenBy { it.code }.thenBy { it.name }
        )
        val result = ArrayList<SecondClassRankEntry>(sorted.size)
        var rank = 0
        var previousScore = Double.NaN
        sorted.forEachIndexed { index, entry ->
            if (index == 0 || entry.score != previousScore) rank = index + 1
            previousScore = entry.score
            result += entry.copy(rank = rank)
        }
        return result
    }

    /** 只要我的名次（`/student/achievement/self/rank`）；没进榜时返回 null。 */
    suspend fun myRank(token: String, level: SecondClassRankLevel): SecondClassRankEntry? {
        val data = get("/student/achievement/self/rank", JSONObject().put("level", level.level), token)
            .optJSONObject("data") ?: return null
        if (data.optString("name").isBlank()) return null
        return data.toRankEntry().copy(isSelf = true)
    }

    // ── 积分明细（「分类与学期统计」，1.2.6）──────────────────────────────
    //
    // 两个端点是同一份账的两种切法，用来回答"这一分是哪来的"：
    //   · by-classify-list：按分类分组，每条记录带 amount / sourceType / relationId
    //   · by-term-list    ：按学期分组，每条记录带 classifyName
    // 契约见 docs/adaptation/2026-09-18-hnnu-second-classroom.md §4。

    /**
     * 按分类的积分明细：`GET /student/achievement/by-classify-list`。
     *
     * ## 两个实测到的响应形态
     *
     * 该接口的 `data` **有时是数组、有时是 `{list: [...], total: n}`**（同族的
     * [dataArray] 早就为此写了双分支）。这里沿用同一个口径：不能只认 `list`，
     * 否则一种形态下会静默返回空列表 —— 表现为"分类统计页永远没数据"。
     *
     * ## 分页
     *
     * 站点该端点**不接受分页参数**（实测传 pageNum/pageSize 无效果），一次全给。
     * 因此不传 params，也就不会出现"少了一页"的静默截断。
     */
    suspend fun pointRecordsByClassify(token: String): List<SecondClassPointGroup> =
        get("/student/achievement/by-classify-list", null, token)
            .toClassifyGroups()

    /**
     * 按学期的积分明细：`GET /student/achievement/by-term-list`。
     *
     * 与分类维度不同，这里的记录多带 `classifyName`，而分组自身带 `termNumber`
     * （学期序号）与 `termHoursUnit`（该学期的单位）。站点没给 `termNumber` 时不编造，
     * 保持空串 —— 界面按 `termName` 展示即可。
     */
    suspend fun pointRecordsByTerm(token: String): List<SecondClassPointGroup> =
        get("/student/achievement/by-term-list", null, token)
            .toTermGroups()

    /**
     * 明细记录 → 模型。两个维度的字段取并集，缺的就是默认值。
     *
     * ## 实测字段差异（2505050101 真实响应，已逐字段核对）
     *
     * | 字段 | 分类维度 | 学期维度 |
     * |---|---|---|
     * | `name` / `hours` / `time` / `sourceType` / `type` | 有 | 有 |
     * | `classifyName` | **无**（分组上才有） | 有 |
     * | `amount` | 有 | **无** |
     * | `relationId`（数字，如 77266） | 有 | **无** |
     * | `identity`（字符串，恒为 "3"） | 无 | 有 |
     * | `tagName` | 无 | 有 |
     *
     * ⚠️ **`identity` 不是 `relationId` 的别名，绝不能拿来当记录 id。** 实测学期维
     * 所有 12 条记录的 `identity` 都是同一个值 `"3"`，它是"参与者身份"枚举
     * （3 = 参与者），与具体哪一笔账无关。若把它填进 [SecondClassPointRecord.relationId]，
     * 一学期内所有记录的 [SecondClassPointRecord.identity] 会退化成同一个值 ——
     * 列表 key 重复会直接崩，且"同名不同笔"再也分不开。
     *
     * 跨维度对齐的正确钥匙是 `(name, time, hours)`：实测两维各 12 条，该三元组
     * 交集 12、双向差集均为 0，可完整对齐。
     */
    private fun JSONObject.toPointRecord(): SecondClassPointRecord = pointRecordFrom(
        name = text("name"),
        hours = optDouble("hours", 0.0),
        amount = optDouble("amount", 0.0),
        time = millis("time"),
        classifyName = text("classifyName"),
        sourceType = opt("sourceType").asInt(),
        relationId = text("relationId"),
        identity = text("identity"),
    )

    /** JSON `null` / 缺省 → Kotlin null，用于区分"站点给了 0"和"站点根本没给"。 */
    private fun JSONObject.nullableDouble(key: String): Double? {
        val value = opt(key) ?: return null
        if (value === JSONObject.NULL) return null
        return value.toString().trim().toDoubleOrNull()
    }

    /**
     * 榜单行 → 模型。
     *
     * `avatar` 在站点上常常是 JSON `null`，而 `org.json` 的 `optString` 会把
     * `JSONObject.NULL` 序列化成字符串 **`"null"`** —— 直接拿去给图片库加载就是一个
     * 非法地址。所以文本字段统一过 [text]。
     */
    private fun JSONObject.toRankEntry(): SecondClassRankEntry = SecondClassRankEntry(
        rank = opt("rownum").asInt(),
        name = text("name"),
        majorName = text("majorName"),
        score = optDouble("score", 0.0),
        avatar = text("avatar"),
        gender = opt("gender").asInt(),
        code = text("code"),
    )

    // ── 活动模块 ──────────────────────────────────────────────────────────
    //
    // 契约全部反解自 2026-09-21 的线上构建（app.js + 活动相关 chunk），
    // 详见 docs/adaptation/2026-09-18-hnnu-second-classroom.md。
    // 这里复用同一套 token / `params` 编码 / 统一信封与错误码处理，
    // **不新增任何通道**。
    //
    // 边界（重要）：签到只做"扫码"与"出示我自己的码"两条站点已开放的路径；
    // 组织侧的 sign/switch、sign/repair（一键补签，参数是 userIds 数组）、
    // activity/delete 等一律不调用。

    /**
     * 我的第二课堂用户标识（站点 `localStorage.userId` 的等价物）。
     *
     * 签到、出示签到码、查打卡记录都要它。站点在登录时把 userId 写进 localStorage，
     * 而 `/token` 只回 token，所以这里从 `/student/user/my-info` 里取。
     * 依次尝试 `id` / `userId` / `code`：不同构建的命名不一致，
     * 但只要拿到一个稳定值，`sign/in-out` 与二维码就能自洽。
     */
    suspend fun myUserId(token: String): String {
        val data = runCatching { get("/student/user/my-info", null, token) }.getOrNull()
            ?.optJSONObject("data") ?: return ""
        return sequenceOf("id", "userId", "code")
            .map { data.text(it) }
            .firstOrNull { it.isNotBlank() }
            .orEmpty()
    }

    /** 活动分类字典。站点取 `data` 直接用，故 `data` 本身即数组。 */
    suspend fun activityCategories(token: String): List<SecondClassActivityCategory> =
        get("/dict/activity/classify/list", JSONObject().put("parentId", 0), token)
            .dataArray()
            .mapItems { SecondClassActivityCategory(id = it.text("id"), name = it.text("name")) }
            .filter { it.name.isNotBlank() }

    /**
     * 活动列表（全部活动）。与网页端"全部活动"页一致走 `/page/activity/list`：
     * 比 `/activity/list` 覆盖面全（含未开始的筹备期活动），且每条带
     * `enrollCount` / `peopleLimit` / `isAbleEnroll` / `collegeLimit`，
     * 供"只看未报满 / 本院系可报"两个本地筛选使用
     * （**院系维度只认 `collegeLimit`**，`isAbleEnroll` 是复合判定，见该字段的注释）。
     */
    suspend fun activityList(
        token: String,
        pageNum: Int = 1,
        pageSize: Int = ACTIVITY_PAGE_SIZE,
        keyword: String = "",
        classifyId: String = "",
        sort: SecondClassActivitySort = SecondClassActivitySort.Default,
    ): SecondClassActivityPage {
        val params = JSONObject()
            .put("pageNum", pageNum)
            .put("pageSize", pageSize)
            .put("name", keyword)
            .put("classifyId", classifyId)
            .put("sortType", sort.value)
        return toActivityPage(get("/page/activity/list", params, token), pageNum, pageSize)
    }

    /**
     * 活动详情。
     *
     * @param asParticipant `true` 走 `/activity/detail/participant`（已报名者的视角，
     *   会带回 `applyStatus` / `isSigner` / `signInCount` 等"我的"字段）；
     *   `false` 走 `/activity/detail/non-member`（未报名者视角）。
     *   两个端点的活动本身字段一致，差别只在"我的视角"。
     */
    suspend fun activityDetail(
        token: String,
        activityId: Int,
        asParticipant: Boolean,
    ): SecondClassActivityDetail {
        val path = if (asParticipant) "/activity/detail/participant" else "/activity/detail/non-member"
        val data = get(path, JSONObject().put("id", activityId), token).optJSONObject("data")
            ?: return SecondClassActivityDetail(id = activityId, name = "")
        return data.toActivityDetail(activityId)
    }

    /**
     * 报名需要填写的采集字段。**多数活动返回空数组**，这时报名体就是
     * `{id, personMaterial:[]}`。
     */
    suspend fun activityEnrollFields(
        token: String,
        activityId: Int,
        collectStage: Int = 1,
        isTeam: Int = 0,
    ): List<SecondClassEnrollField> {
        val params = JSONObject()
            .put("activityId", activityId)
            .put("collectStage", collectStage)
            .put("isTeam", isTeam)
        return get("/activity/material/list", params, token).dataArray()
            .mapItems { field ->
                SecondClassEnrollField(
                    // 站点拼写是 filedId / filedName / filedType，照抄
                    key = field.text("filedId").ifBlank { field.text("id") },
                    name = field.text("filedName").ifBlank { field.text("name") },
                    title = field.text("filedValueTitle"),
                    type = field.text("filedType"),
                )
            }
            .filter { it.key.isNotBlank() && it.name.isNotBlank() }
    }

    /**
     * 个人报名。契约（实测反解站点 `collectData.map(...)`）：
     * `{id, personMaterial:[{key: filedId, value: filedValue, filedValueTitle}]}`。
     *
     * **不做任何自动重试**：写接口一旦失败，如实把服务端的 `msg` 交给用户，
     * 由用户自己决定要不要再点一次（自动重试可能重复占名额）。
     */
    suspend fun activityEnroll(
        token: String,
        activityId: Int,
        answers: List<SecondClassEnrollAnswer> = emptyList(),
    ): String {
        val materials = JSONArray()
        answers.forEach { materials.put(it.toJson()) }
        val body = JSONObject().put("id", activityId).put("personMaterial", materials)
        return post("/activity/enroll/person", body, token).optString("msg")
    }

    /**
     * 取消报名：`{id, applyInfo}`。
     *
     * `applyStatus == 2`（报名已通过）时取消要走审核，站点强制要求填理由；
     * 上层据此提示"等待审核"还是"已成功取消"。
     */
    suspend fun activityCancelEnroll(token: String, activityId: Int, reason: String): String =
        post(
            "/activity/enroll/cancel/person",
            JSONObject().put("id", activityId).put("applyInfo", reason),
            token,
        ).optString("msg")

    /**
     * 我的活动（我报名的）。
     *
     * 参数取自站点"我参与的"页：`status` 用逗号串覆盖全部参与态
     * （5,6,7,8,9,10,11,12），`identity=3` 表示"我作为参与者的身份"。
     * 站点在有筛选时会给 `applyStatus`，没有筛选时发空串 —— 这里保持一致。
     */
    /**
     * 「我的活动」。status 取值与网页端"我的活动"页一致：
     * 全部 = `1,3,4,5,6,7,8,9,10,11,12`（网页端**不含 2**，且 11/12 = 已结束）。
     * 不传 identity —— 网页端就没传，多传反而筛掉一部分活动。
     */
    suspend fun myActivities(
        token: String,
        pageNum: Int = 1,
        pageSize: Int = ACTIVITY_PAGE_SIZE,
        statusCsv: String = MY_ACTIVITY_STATUS,
        applyStatus: Int? = null,
    ): SecondClassActivityPage {
        val params = JSONObject()
            .put("pageNum", pageNum)
            .put("pageSize", pageSize)
            .put("status", statusCsv)
            .put("applyStatus", applyStatus ?: "")
        return toActivityPage(get("/activity/my-list", params, token), pageNum, pageSize)
    }

    /** 我取消过的活动，走 `/activity/my-cancel-list`。 */
    suspend fun myCanceledActivities(
        token: String,
        pageNum: Int = 1,
        pageSize: Int = ACTIVITY_PAGE_SIZE,
    ): SecondClassActivityPage {
        val params = JSONObject()
            .put("pageNum", pageNum)
            .put("pageSize", pageSize)
            .put("applyStatus", "")
        return toActivityPage(get("/activity/my-cancel-list", params, token), pageNum, pageSize)
    }

    // ── 申报（站点「申报」页 /service/declare）────────────────────────────

    /**
     * 我的申报记录：`GET /project/request/list1` → `data.list`。
     *
     * 「已申报」页签用：支持按模块（classifyId）与关键词过滤，参数与站点一致。
     * 一次性拉全（`pageSize = 50`）：申报记录天然很少（实测本人 1 条），
     * 为它单独写一套翻页链路不划算。
     */
    suspend fun myApplications(
        token: String,
        pageSize: Int = APPLICATION_PAGE_SIZE,
        classifyId: String = "",
        search: String = "",
    ): List<SecondClassApplication> {
        val params = JSONObject()
            .put("pageNum", 1)
            .put("pageSize", pageSize)
            .put("status", "0,1,2,3")
            .put("classifyId", classifyId)
            .put("search", search)
        return get("/project/request/list1", params, token)
            .optJSONObject("data")
            ?.optJSONArray("list")
            .mapObjects { item ->
                SecondClassApplication(
                    id = item.opt("id").asInt(),
                    projectName = item.text("projectName"),
                    optionName = item.text("optionName"),
                    classifyName = item.text("classifyName"),
                    hours = item.optDouble("hours", 0.0),
                    optionHours = item.optDouble("optionHours", 0.0),
                    status = item.opt("status").asInt(),
                    startTime = item.opt("startTime").asLong(),
                    endTime = item.opt("endTime").asLong(),
                    lastTime = item.opt("ltime").asLong(),
                    remark = item.text("remark"),
                    limitTypeName = item.optJSONObject("projectLimitType")?.text("name").orEmpty(),
                )
            }
            .filter { it.id > 0 || it.projectName.isNotBlank() }
    }

    /**
     * 申报项目分类：`GET /dict/project/choice-sort` → `data.filterArray`。
     *
     * 站点申报页的「分类」下拉（classifyId），首项是 `id = 0` 的「全部」，
     * 这里过滤掉空项、由界面自己补"全部"。
     */
    suspend fun applicationCategories(token: String): List<SecondClassActivityCategory> =
        get("/dict/project/choice-sort", JSONObject(), token)
            .optJSONObject("data")
            ?.optJSONArray("filterArray")
            .mapObjects { item ->
                SecondClassActivityCategory(id = item.text("id"), name = item.text("name"))
            }
            .filter { it.name.isNotBlank() && it.id != "0" && it.name != "全部" }

    /**
     * 申报**类型**：`GET /dict/school/system-para/list?fieldType=honor`。
     *
     * 站点申报页的「类别」下拉（categoryId），对应奖励类型（如"荣誉/奖项/竞赛"等，
     * 具体由学校配置）。用户点名要求"申报要选择类型来申报"—— 就是这个下拉。
     * `data` 本身是数组。
     */
    suspend fun declareTypes(token: String): List<SecondClassActivityCategory> =
        get("/dict/school/system-para/list", JSONObject().put("fieldType", "honor"), token)
            .dataArray()
            .mapItems { item ->
                SecondClassActivityCategory(id = item.text("id"), name = item.text("name"))
            }
            .filter { it.name.isNotBlank() && it.id.isNotBlank() }

    /**
     * 申报项目列表：`GET project/home/page/list`（站点「未申报」页签）。
     *
     * 参数与站点 chunk26 完全一致：search / pageNum / pageSize /
     * sortType（0 最新 / 1 最热）/ classifyId（分类）/ category（类型）。
     */
    suspend fun declareProjects(
        token: String,
        pageNum: Int = 1,
        pageSize: Int = ACTIVITY_PAGE_SIZE,
        search: String = "",
        sortType: Int = 0,
        classifyId: String = "",
        category: String = "",
    ): SecondClassDeclarePage {
        val params = JSONObject()
            .put("search", search)
            .put("pageNum", pageNum)
            .put("pageSize", pageSize)
            .put("sortType", sortType)
            .put("classifyId", classifyId)
            .put("category", category)
        val data = get("/project/home/page/list", params, token).optJSONObject("data")
        val items = data?.optJSONArray("list").mapObjects { it.toDeclareProject() }
        val hasMore = if (data?.has("lastPage") == true) {
            data.bool("lastPage")
        } else {
            items.isNotEmpty() && items.size >= pageSize
        }
        return SecondClassDeclarePage(items = items, hasMore = hasMore)
    }

    private fun JSONObject.toDeclareProject(): SecondClassDeclareProject = SecondClassDeclareProject(
        id = opt("id").asInt(),
        name = text("name"),
        classifyId = text("classifyId"),
        classifyName = text("classifyName"),
        optionsCount = opt("optionsCount").asInt(),
        limitTypeName = optJSONObject("projectLimitType")?.text("name").orEmpty(),
        status = opt("status").asInt(),
        closeApplyRemark = text("closeApplyRemark"),
    )

    /**
     * 申报项目详情：`GET /project/detail/info?id=`。
     *
     * `optionList` 是可选的奖项（档位），`explains` 是站点「填写说明」弹窗的文案。
     */
    suspend fun declareProjectDetail(token: String, projectId: Int): SecondClassDeclareProjectDetail {
        val data = get("/project/detail/info", JSONObject().put("id", projectId), token)
            .optJSONObject("data") ?: return SecondClassDeclareProjectDetail(id = projectId, name = "")
        return SecondClassDeclareProjectDetail(
            id = data.opt("id").asInt().takeIf { it > 0 } ?: projectId,
            name = data.text("name"),
            explains = data.text("explains"),
            options = data.optJSONArray("optionList").mapObjects { option ->
                SecondClassDeclareOption(
                    id = option.opt("id").asInt(),
                    name = option.text("name"),
                    hours = option.optDouble("optionHours", 0.0),
                    awardsValid = option.opt("awardsValid").asInt(),
                )
            },
        )
    }

    /**
     * 上传申报证明材料：`POST import/upload/image`（multipart）。
     *
     * 站点实现（chunk0 的 `iTqn` 模块，schoolId=10381 走**本地直传**分支）：
     * 字段 `file` 放文件、`params` 放 `Encrypt(JSON.stringify({type:4}))` ——
     * 而本站构建里 `Encrypt` 是**恒等函数**（app.js 的 `aNLv` 模块），
     * 所以 `params` 就是字面量 `{"type":4}`。头 `Authorization: <token>` 与其它接口一致。
     * 成功后 `data` 是 url（历史构建里是 `url|…`，取竖线前一段更稳）。
     */
    suspend fun uploadDeclareMaterial(
        token: String,
        bytes: ByteArray,
        fileName: String,
        mimeType: String?,
    ): String {
        val builder = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                fileName,
                bytes.toRequestBody(mimeType?.toMediaType()),
            )
            .addFormDataPart("params", "{\"type\":4}")
        val json = request(base + "/import/upload/image", builder.build(), token)
        // 信封两种形态都吃：data 直接是 url 串，或 data:{url:...}
        val url = (json.optJSONObject("data")?.text("url") ?: json.text("data"))
            .substringBefore("|")
            .trim()
        if (url.isBlank()) throw SecondClassException("上传返回缺少附件地址，请重试")
        return url
    }

    /**
     * 提交申报：`POST /project/apply/add_3_0_1`，载荷即站点 `subData`：
     * `{projectId, optionId, startTime(毫秒), endTime(毫秒), report, joinNumber:1,
     *   relateActivityId, relateActivityName, urls:[{id:0,url,name,size,type}]}`。
     *
     * 站点的前置校验（optionId / report / urls 非空、起止先后、不得早于学期开始）
     * 由上层表单完成；这里不做自动重试，失败把服务端 `msg` 原样交给用户。
     *
     * @return 新申报记录的 id（站点成功响应 `data.id`，用户据此跳"我的申报"）。
     */
    suspend fun submitDeclareApplication(token: String, submission: SecondClassDeclareSubmission): Int {
        val urls = JSONArray()
        submission.materials.forEach { urls.put(it.toJson()) }
        val body = JSONObject()
            .put("projectId", submission.projectId)
            .put("optionId", submission.optionId)
            .put("startTime", submission.startTime)
            .put("endTime", submission.endTime)
            .put("report", submission.report)
            .put("joinNumber", 1)
            .put("relateActivityId", submission.relateActivityId ?: JSONObject.NULL)
            .put("relateActivityName", submission.relateActivityName)
            .put("urls", urls)
        val payload = post("/project/apply/add_3_0_1", body, token)
        return payload.optJSONObject("data")?.opt("id").asInt()
    }

    /** 关联活动搜索（站点申请页 `GET /activity/search/list?searchName=`）。 */
    suspend fun searchRelateActivities(token: String, keyword: String): List<SecondClassActivity> {
        if (keyword.isBlank()) return emptyList()
        return get("/activity/search/list", JSONObject().put("searchName", keyword), token)
            .dataArray()
            .mapItems { it.toActivity() }
    }

    /** 活动通知：`{pageNum, pageSize, activityId}` → `data.list`。 */
    suspend fun activityNotices(
        token: String,
        activityId: Int,
        pageNum: Int = 1,
        pageSize: Int = ACTIVITY_PAGE_SIZE,
    ): List<SecondClassActivityNotice> {
        val params = JSONObject()
            .put("pageNum", pageNum)
            .put("pageSize", pageSize)
            .put("activityId", activityId)
        return get("/activity/noticeList", params, token).dataArray()
            .mapItems { notice ->
                SecondClassActivityNotice(
                    id = notice.text("id"),
                    title = notice.text("title"),
                    content = notice.text("content"),
                    createTime = notice.millis("createTime").takeIf { it > 0 }
                        ?: notice.millis("time"),
                    publisher = notice.text("publisher").ifBlank { notice.text("createName") },
                )
            }
    }

    // ── 消息（站点「消息」页 /message/notice/*）───────────────────────────

    /**
     * 消息列表。`data.list` 每条带 `isRead`（0/1）、`ctime`（毫秒）、
     * `type/subType/typeName/subTypeName`、`content`、`extras.jumpInfo`。
     */
    suspend fun messages(token: String, pageNum: Int = 1, pageSize: Int = ACTIVITY_PAGE_SIZE): SecondClassMessagePage {
        val params = JSONObject().put("pageNum", pageNum).put("pageSize", pageSize)
        val data = get("/message/notice/list", params, token).optJSONObject("data")
        val items = data?.optJSONArray("list").mapObjects { m ->
                SecondClassMessage(
                    id = m.opt("id").asLong(),
                    type = m.opt("type").asInt(),
                    subType = m.opt("subType").asInt(),
                    typeName = m.text("typeName"),
                    subTypeName = m.text("subTypeName"),
                    content = m.text("content"),
                    time = m.optLong("ctime", 0L),
                    isRead = m.optInt("isRead", 1) == 1,
                    jumpInfo = m.optJSONObject("extras")?.opt("jumpInfo")?.toString().orEmpty(),
                )
            }
        val total = data?.optInt("total", -1)?.takeIf { it >= 0 }
            ?: data?.optInt("totalResult", items.size) ?: items.size
        // 站点没给 lastPage 时按 total 推：翻不满一页就是最后一页。
        // ⚠️ 不能写 `optBoolean("lastPage") ?: ...`：org.json 缺键时返回 false（不是 null），
        // 兜底分支永远不执行 → hasMore 恒真 → 列表尾部永远"上拉加载更多"，还会重复追加同一页。
        val lastPage = if (data?.has("lastPage") == true) {
            data.optBoolean("lastPage")
        } else {
            items.isEmpty() || items.size >= total
        }
        return SecondClassMessagePage(items = items, total = total, lastPage = lastPage)
    }

    /** 标记单条消息为已读。 */
    suspend fun messageRead(token: String, messageId: Long) {
        post("/message/notice/read", JSONObject().put("id", messageId), token)
    }

    /** 一键已读（网页端 read-all 的 id 传 0 = 全部消息）。 */
    suspend fun messageReadAll(token: String) {
        post("/message/notice/read-all", JSONObject().put("id", 0), token)
    }

    /** 站内消息未读数。 */
    suspend fun messageUnreadCount(token: String): Int =
        get("/message/notice/un-read-count", null, token)
            .optJSONObject("data")?.optInt("unReadCount", 0) ?: 0

    /** 我的全部签到/签退记录（跨活动），`activity/my-sign-in-out-list`。 */
    suspend fun mySignRecords(
        token: String,
        pageNum: Int = 1,
        pageSize: Int = ACTIVITY_PAGE_SIZE,
    ): List<SecondClassSignRecord> {
        val params = JSONObject().put("pageNum", pageNum).put("pageSize", pageSize)
        return get("/activity/my-sign-in-out-list", params, token).dataArray()
            .mapItems { it.toSignRecord() }
    }

    /** 某个活动里某个人的打卡记录（`POST /activity/sign/one/list`）。 */
    suspend fun activitySignRecords(
        token: String,
        userId: String,
        activityId: Int,
    ): List<SecondClassSignRecord> =
        post(
            "/activity/sign/one/list",
            JSONObject().put("userId", userId).put("activityId", activityId),
            token,
        ).dataArray().mapItems { it.toSignRecord() }

    /**
     * 签到计数与动态码种子。`data` 是计数，顶层的 `timestamp` 是动态二维码 `sp` 的初值。
     */
    suspend fun activitySignCount(token: String, activityId: Int): SecondClassSignCount {
        val payload = get("/activity/sign/count", JSONObject().put("id", activityId), token)
        val data = payload.opt("data")
        val count = if (data is JSONObject) {
            data.opt("count").asInt().takeIf { it > 0 } ?: data.opt("signCount").asInt()
        } else {
            data.asInt()
        }
        return SecondClassSignCount(value = count, timestamp = payload.millis("timestamp"))
    }

    /**
     * 签到 / 签退。契约（实测反解 `window.BackScan` 与站点签到页）：
     * `{id: activityId, userId: <扫码载荷里的> , type, sp}`，`type` 2=签到签退、1=等待签到。
     *
     * 上层必须先过 [SecondClassScanCodec.evaluateSignScan]，**不做无脑转发**。
     */
    suspend fun signInOut(
        token: String,
        activityId: Int,
        userId: String,
        type: Int,
        sp: String,
    ): String =
        post(
            "/activity/sign/in-out",
            JSONObject()
                .put("id", activityId)
                .put("userId", userId)
                .put("type", type)
                .put("sp", sp),
            token,
        ).optString("msg")

    // ── 活动模块：JSON → 模型 ──────────────────────────────────────────────

    /** 统一取 `data` 里的数组：`data` 本身是数组、或 `data.list` 是数组，两种都吃。 */
    private fun JSONObject.dataArray(): JSONArray? =
        (opt("data") as? JSONArray) ?: optJSONObject("data")?.optJSONArray("list")

    private fun toActivityPage(payload: JSONObject, pageNum: Int, pageSize: Int): SecondClassActivityPage {
        val data = payload.optJSONObject("data")
            ?: return SecondClassActivityPage(pageNum = pageNum, pageSize = pageSize)
        val items = data.optJSONArray("list").mapItems { it.toActivity() }
        val effectivePageSize = data.opt("pageSize").asInt().takeIf { it > 0 } ?: pageSize
        return SecondClassActivityPage(
            items = items,
            total = data.opt("total").asInt().takeIf { it > 0 } ?: items.size,
            pageNum = data.opt("pageNum").asInt().takeIf { it > 0 } ?: pageNum,
            pageSize = effectivePageSize,
            // `lastPage` 站点有时不给；缺键时不能当 false（会永远"还有下一页"），
            // 改为按页大小推：本页装满了就认为还有下一页，没装满就是最后一页。
            lastPage = if (data.has("lastPage")) {
                data.bool("lastPage")
            } else {
                items.isEmpty() || items.size < effectivePageSize
            },
        )
    }

    private fun JSONObject.toActivity(): SecondClassActivity = SecondClassActivity(
        id = opt("id").asInt(),
        name = text("name"),
        address = text("address"),
        hours = optDouble("hours", 0.0),
        grantHours = optDouble("grantHours", 0.0),
        organizationName = text("organizationName"),
        startTime = millis("startTime"),
        endTime = millis("endTime"),
        enrollStartTime = millis("enrollStartTime"),
        enrollEndTime = millis("enrollEndTime"),
        logo = text("logo"),
        classifyName = text("classifyName"),
        peopleLimit = opt("peopleLimit").asInt(),
        // 网页端"全部活动"接口（/page/activity/list）用 enrollCount 表示已报名人数
        joinMemberCount = opt("joinMemberCount").asInt().takeIf { it > 0 }
            ?: opt("enrollCount").asInt(),
        applyStatus = opt("applyStatus").asInt(),
        status = opt("status").asInt(),
        // 站点对"没取消"的人也回 cancelStatus=0，0 不算已取消（与详情链路同口径）
        cancelStatus = nullableInt("cancelStatus")?.takeIf { it != 0 },
        introduce = text("introduce"),
        // 缺失（旧接口）按 true 处理，避免"本院系可报"筛选误杀
        isAbleEnroll = if (has("isAbleEnroll")) optInt("isAbleEnroll", 1) == 1 else true,
        // 院系限制："0"/缺省 = 不限院系，其余是逗号分隔的院系 id 列表。
        // 这是「本院系可报」筛选的唯一依据（isAbleEnroll 是复合判定，不能拿来筛院系）。
        collegeLimit = text("collegeLimit"),
    )

    private fun JSONObject.toActivityDetail(fallbackId: Int): SecondClassActivityDetail {
        val manager = optJSONObject("manager")
        return SecondClassActivityDetail(
            id = opt("id").asInt().takeIf { it > 0 } ?: fallbackId,
            name = text("name"),
            address = text("address"),
            introduce = text("introduce"),
            logo = text("logo"),
            classifyName = text("classifyName"),
            organizationName = text("organizationName"),
            managerName = manager?.text("name").orEmpty().ifBlank { text("managerName") },
            contact = text("contact"),
            startTime = millis("startTime"),
            endTime = millis("endTime"),
            enrollStartTime = millis("enrollStartTime"),
            enrollEndTime = millis("enrollEndTime"),
            peopleLimit = opt("peopleLimit").asInt(),
            joinMemberCount = opt("joinMemberCount").asInt(),
            hours = optDouble("hours", 0.0),
            grantHours = optDouble("grantHours", 0.0),
            gradeList = opt("gradeList").asTextList(),
            collegeList = opt("collegeList").asTextList(),
            genderLimit = opt("genderLimit").asInt(),
            applyStatus = opt("applyStatus").asInt(),
            status = opt("status").asInt(),
            cancelStatus = nullableInt("cancelStatus")?.takeIf { it != 0 },
            applyRejectReason = text("applyRejectReason"),
            isManager = bool("isManager"),
            isSigner = bool("isSigner"),
            signSwitch = opt("signSwitch").asInt(),
            signWay = text("signWay"),
            signLimit = opt("signLimit").asInt(),
            signInCount = opt("signInCount").asInt(),
            signOutCount = opt("signOutCount").asInt(),
            latitude = optDouble("latitude", 0.0),
            longitude = optDouble("longitude", 0.0),
            isHaveCollect = bool("isHaveCollect"),
            attachments = optJSONArray("attachment").mapItems { item ->
                SecondClassAttachment(
                    name = item.text("name"),
                    url = item.text("url"),
                    attachmentType = item.opt("attachmentType").asInt(),
                )
            }.filter { it.url.isNotBlank() },
        )
    }

    /**
     * 打卡记录 → 模型。
     *
     * 站点没有公开这些字段名，所以做**宽松解析**：常见的几种命名都试一遍，
     * 剩下认不出来的标量字段原样收进 `extras` 展示 —— 宁可多显示一行，
     * 也不要因为服务端改了字段名就给用户一个空列表。
     */
    private fun JSONObject.toSignRecord(): SecondClassSignRecord {
        val consumed = setOf(
            "id", "activityId", "activityName", "name", "userName", "realName",
            "type", "signType", "handleType", "signInOutType",
            "time", "createTime", "signTime", "date",
            "address", "signAddress", "location",
        )
        val extras = keys().asSequence()
            .filter { it !in consumed }
            .mapNotNull { key -> text(key).takeIf { it.isNotBlank() }?.let { key to it } }
            .toList()
        val rawType = text("type").ifBlank { text("signType") }
            .ifBlank { text("handleType") }
            .ifBlank { text("signInOutType") }
        return SecondClassSignRecord(
            id = text("id"),
            activityId = opt("activityId").asInt(),
            activityName = text("activityName"),
            userName = text("userName").ifBlank { text("realName") }.ifBlank { text("name") },
            typeLabel = signTypeLabel(rawType),
            time = firstPositive(millis("signTime"), millis("createTime"), millis("time"), millis("date")),
            address = text("signAddress").ifBlank { text("address") }.ifBlank { text("location") },
            extras = extras,
        )
    }

    /**
     * 打卡类型的展示文案。
     *
     * 站点记录接口的类型编码没有权威取值表，这里只认最常见的
     * `1=签到 / 2=签退`，其余原样显示原值 —— 编不出对应关系时不硬猜。
     */
    private fun signTypeLabel(raw: String): String = when (raw) {
        "1" -> "签到"
        "2" -> "签退"
        else -> raw
    }

    private fun firstPositive(vararg values: Long): Long = values.firstOrNull { it > 0L } ?: 0L

    /** JSON 布尔 / 数字 / 字符串（"true" / "1"）→ Boolean。 */
    private fun JSONObject.bool(key: String): Boolean {
        val value = opt(key) ?: return false
        if (value === JSONObject.NULL) return false
        return when (value) {
            is Boolean -> value
            is Number -> value.toInt() != 0
            else -> value.toString().equals("true", ignoreCase = true) || value.toString() == "1"
        }
    }

    /** JSON `null` / 缺省 → Kotlin null。**不能直接 `opt(...).asInt()`**：那会把 JSON null 变成 0。 */
    private fun JSONObject.nullableInt(key: String): Int? {
        val value = opt(key) ?: return null
        if (value === JSONObject.NULL) return null
        return value.asInt()
    }

    /**
     * 时间字段 → epoch 毫秒。
     *
     * 站点的活动时间就是数字毫秒（前端 `new Date(x)` 直接相减做倒计时）。
     * 也兼容服务端偶尔回字符串的形态（纯数字串或常见日期格式）。
     */
    private fun JSONObject.millis(key: String): Long {
        val value = opt(key) ?: return 0L
        if (value === JSONObject.NULL) return 0L
        if (value is Number) return value.toLong()
        val raw = value.toString().trim()
        if (raw.isEmpty() || raw == "null") return 0L
        raw.toLongOrNull()?.let { return it }
        for (pattern in TIME_PATTERNS) {
            val parsed = runCatching { java.text.SimpleDateFormat(pattern, java.util.Locale.CHINA).parse(raw)?.time }
                .getOrNull()
            if (parsed != null && parsed > 0L) return parsed
        }
        return 0L
    }

    /** 字符串数组 / 逗号或竖线分隔的字符串 → List&lt;String&gt;。站点两种形态都出现过。 */
    private fun Any?.asTextList(): List<String> = when (this) {
        null, JSONObject.NULL -> emptyList()
        is JSONArray -> (0 until length())
            .mapNotNull { index ->
                opt(index)?.takeIf { it !== JSONObject.NULL }?.toString()?.trim()?.takeIf { it.isNotBlank() }
            }
        else -> toString().split(',', '，', '|').map { it.trim() }.filter { it.isNotBlank() }
    }

    // ── 传输 ──────────────────────────────────────────────────────────────

    private suspend fun get(path: String, params: JSONObject?, token: String?): JSONObject {
        val url = base + path + (params?.let { "?params=" + encodeParams(it.toString()) } ?: "")
        return request(url, null, token)
    }

    private suspend fun post(path: String, body: JSONObject, token: String?): JSONObject {
        // 必须用 addEncoded：FormBody.Builder.add() 会对 value 再做一次百分号编码，
        // 而服务端只解两层 —— 自己编一层再被 OkHttp 编一层就是**三层**，直接回
        // `10002 参数格式不正确，参数必须是JSON格式`（这就是登录一直失败的根因）。
        val form = FormBody.Builder().addEncoded("params", encodeParams(body.toString())).build()
        return request(base + path, form, token)
    }

    private suspend fun request(url: String, form: RequestBody?, token: String?): JSONObject {
        var target = url
        var hops = 0
        while (true) {
            val parsed = target.toHttpUrlOrNull() ?: throw SecondClassException("第二课堂地址无效")
            val builder = Request.Builder().url(parsed).header("User-Agent", USER_AGENT)
            if (token != null) builder.header("Authorization", token)
            if (form != null) builder.post(form) else builder.get()

            val code: Int
            val body: String
            val location: String?
            try {
                execute(builder.build()).use { response ->
                    code = response.code
                    body = response.body?.string().orEmpty()
                    location = response.header("Location")
                }
            } catch (e: IOException) {
                throw SecondClassException("无法连接第二课堂，请检查网络或稍后重试", sessionExpired = false)
            }

            // 网关（openresty）偶尔会把请求 302 到「带显式端口」的同一地址。
            //
            // 这里**必须自己跟随**，不能让 OkHttp 自动跟随：OkHttp 对 301/302/303 会把
            // POST 降级成 GET 并丢掉请求体，服务端随即回
            // `405 Request method 'GET' not supported` —— 用户看到的就是一句
            // "第二课堂登录失败"，而且网关是间歇性重定向，几乎无法复现。
            //
            // 只跟同源（同 scheme + 同 host）的跳转：请求头里带着 access_token，
            // 跨站跳转等于把令牌送给第三方。
            if (code in 300..399 && location != null && hops < MAX_REDIRECTS) {
                val next = parsed.resolve(location)
                    ?: throw SecondClassException("第二课堂返回了无法识别的跳转地址")
                if (next.scheme != parsed.scheme || next.host != parsed.host) {
                    throw SecondClassException("第二课堂返回了跨站跳转，已拒绝")
                }
                // 无条件跟随后重新发起：不再比较 next 是否等于 target。
                // 网关会把「不带端口的 URL」302 到「显式带 :443 的同一个地址」，
                // 两者字符串不同但语义相同，必须照跟；否则会掉到下面的
                // 「HTTP 302 → 暂时不可用」错误分支，表现为登录偶发失败。
                // 真正的自引用死循环由 MAX_REDIRECTS 兜底（跳数耗尽后如实报错）。
                target = next.toString()
                hops++
                continue
            }

            if (code == 401 || code == 403) {
                throw SecondClassException("第二课堂登录已失效，请重新登录", sessionExpired = true)
            }
            if (code !in 200..299) {
                throw SecondClassException("第二课堂暂时不可用（HTTP $code），请稍后重试")
            }
            return parseEnvelope(body)
        }
    }

    /** 统一信封 `{code, data, msg}` 的解析与错误映射。 */
    private fun parseEnvelope(text: String): JSONObject {
        val json = try {
            JSONObject(text)
        } catch (e: Exception) {
            throw SecondClassException("第二课堂返回了无法识别的数据")
        }

        when (json.optInt("code", -1)) {
            0 -> return json
            // 10001 是站点统一的"未登录/登录失效"码，前端据此清 token 并跳登录页
            10001 -> throw SecondClassException("第二课堂登录已失效，请重新登录", sessionExpired = true)
            20002 -> throw SecondClassException("第二课堂账号或密码不正确")
            10007 -> throw SecondClassException("该学校尚未开通第二课堂服务")
            else -> throw SecondClassException(json.optString("msg").ifBlank { "第二课堂请求失败" })
        }
    }

    private suspend fun execute(request: Request): Response = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                if (continuation.isActive) continuation.resume(response) else response.close()
            }
        })
    }

    private inline fun <T> JSONArray?.mapObjects(transform: (JSONObject) -> T): List<T> {
        if (this == null) return emptyList()
        val result = ArrayList<T>(length())
        for (index in 0 until length()) {
            optJSONObject(index)?.let { result.add(transform(it)) }
        }
        return result
    }

    /**
     * 取文本，把 JSON `null` 归一化成空串。
     *
     * `optString` 对 `JSONObject.NULL` 会返回字符串 `"null"`（org.json 的
     * `JSON.toString` 走的是 `String.valueOf`），于是 `avatar = "null"` 这种值会
     * 一路流到图片加载器。所有文本字段都从这里出。
     */
    private fun JSONObject.text(key: String): String {
        val value = opt(key)
        if (value == null || value === JSONObject.NULL) return ""
        val rendered = value.toString().trim()
        return if (rendered == "null" || rendered == "undefined") "" else rendered
    }

    /** 数值字段在站点上时而 `1`、时而 `1.0`（`rownum` 就是浮点），统一取整。 */
    private fun Any?.asInt(): Int = when (this) {
        null -> 0
        is Number -> toDouble().toInt()
        is String -> toDoubleOrNull()?.toInt() ?: 0
        else -> 0
    }

    /** 同 [asInt]，给消息 id（Long 主键）用。 */
    private fun Any?.asLong(): Long = when (this) {
        null -> 0L
        is Number -> toDouble().toLong()
        is String -> toDoubleOrNull()?.toLong() ?: 0L
        else -> 0L
    }

    /** `waysConvert == 3` 时学校按分数展示，其余按学分。与站点 `scoreUnit` 的判定一致。 */
    private fun unitLabel(waysConvert: Int): String = if (waysConvert == 3) "分数" else "学分"

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

        /**
         * 榜单页长。实测站点对上限很宽松（5000 也照收），班级 37 人用 50 就能一次拿全；
         * 取 100 是为了让"一次请求拿完"覆盖到绝大多数班级，同时留出翻页兜底。
         */
        private const val BOARD_PAGE_SIZE = 100

        /** 翻页保险丝。100 × 50 = 5000 行，远超任何真实榜单规模。 */
        private const val MAX_BOARD_PAGES = 50

        /**
         * 同源重定向的最大跳数。网关的 302 只会跳一次（补上 `:443`），
         * 给 3 跳足够，同时挡住相互指向的重定向环。
         */
        private const val MAX_REDIRECTS = 3

        /** 活动列表 / 我的活动的页长。站点默认 10，这里取 20 少翻一半页。 */
        private const val ACTIVITY_PAGE_SIZE = 20

        /** 申报记录一次拉全的页长（申报天然很少，不做翻页）。 */
        private const val APPLICATION_PAGE_SIZE = 50

        /**
         * "我的活动"的 `status` 过滤串。与网页端"我的活动"页完全一致：
         * 全部 = `1,3,4,5,6,7,8,9,10,11,12`（**不含 2**；11/12 = 已结束）。
         * 之前传的 `5,6,7,8,9,10,11,12` 漏了 1/3/4，导致部分已结束/进行中的
         * 活动不出现在"我的"里。
         */
        private const val MY_ACTIVITY_STATUS = "1,3,4,5,6,7,8,9,10,11,12"

        /** `millis()` 兜底解析用的日期格式，按命中概率排序。 */
        private val TIME_PATTERNS = arrayOf(
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd HH:mm",
            "yyyy/MM/dd HH:mm:ss",
            "yyyy-MM-dd",
        )

        /**
         * 站点要求的编码：JSON 整体做**一层**百分号编码。
         *
         * 前端的 `encodeURI(json)` 不编码 `{}"` 等字符，随后被 `qs.stringify`
         * 一次编掉，净效果就是"完整编码一层"。服务端相应解两层 —— 所以
         * **一层和两层都能过**（对一层输入来说第二次解码是空操作），
         * 但**三层一定失败**（`10002 参数格式不正确，参数必须是JSON格式`）。
         *
         * 因此发送时必须保证线上恰好一层：GET 在这里手工编一层；
         * POST 走 `FormBody.addEncoded` 送编好的串，**不能用 `add()`**（它会再编一层）。
         *
         * `+` 换成 `%20` 只为与 `encodeURIComponent` 逐字对齐
         * （org.json 的 `toString()` 不含空格，实际等价）。
         */
        internal fun encodeParams(json: String): String =
            URLEncoder.encode(json, "UTF-8").replace("+", "%20")

        private fun defaultClient() = OkHttpClient.Builder()
            .retryOnConnectionFailure(false)
            // **不要**让 OkHttp 自动跟随重定向：它对 301/302/303 会把 POST 降级成 GET
            // 并丢掉请求体，而本站在网关抖动时会 302 到「带显式端口的同一地址」，
            // 结果就是登录偶发失败（详见 request()）。重定向由 request() 自己处理，
            // 这样可以保留方法、请求体与 Authorization 头。
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}

/**
 * 明细记录的两维字段 → [SecondClassPointRecord]。
 *
 * 提成顶层 `internal` 纯函数是**刻意的**：这条映射踩过一次真实的坑 ——
 * 学期维记录没有 `relationId`，却有一个恒为 `"3"` 的 `identity`
 * （参与者身份枚举，**不是**记录 id）。若写成
 * `relationId = text("relationId").ifBlank { text("identity") }`，
 * 同一学期内所有记录的 [SecondClassPointRecord.identity] 会退化成同一个
 * 值（列表 key 重复会崩，同名不同笔也再分不开），而这是**编译期与运行期
 * 都不报错**的静默缺陷。
 *
 * 放在 client 内部时该逻辑是 `private`，单测碰不到，变异测试已证明
 * 没有任何用例能发现它。提出来后由
 * `SecondClassPointAnalysisTest.identityMustNotLeakIntoRelationId`
 * 直接锁住：`relationId` 只认自己的字段名，`identity` 一律丢弃。
 */
internal fun pointRecordFrom(
    name: String,
    hours: Double,
    amount: Double,
    time: Long,
    classifyName: String,
    sourceType: Int,
    relationId: String,
    /**
     * 站点学期维的 `identity`（参与者身份枚举）。**刻意不参与映射** ——
     * 参数保留只为把"它被显式忽略"写在签名上，避免日后又有人拿它顶替 `relationId`。
     */
    @Suppress("UNUSED_PARAMETER") identity: String,
): SecondClassPointRecord = SecondClassPointRecord(
    name = name,
    hours = hours,
    amount = amount,
    time = time,
    classifyName = classifyName,
    sourceType = sourceType,
    relationId = relationId,
)

// ── 明细响应的形态归一（提为 internal 纯函数，便于单测）──────────────────
//
// 这一层守着一个**静默缺陷**：站点对 `data` 的包装形态不统一，若只认一种，
// 另一种会安静地变成空列表 —— 界面显示"该校没有按分类下发明细"，
// 而接口其实是 200 + 有数据。没有异常、没有日志、编译也不报错。
//
// 因此这里拆成三个纯函数（取数组 / 取对象列表 / 取分组），
// 由 `SecondClassPointPayloadTest` 逐形态钉死。

/**
 * 从明细响应里取**分组数组**，兼容站点实测到的多种包装形态。
 *
 * 已支持的形态（前两种是原有实现，后三种是本次修复补充）：
 *
 * | 形态 | 例 |
 * |---|---|
 * | `data` 直接是数组 | `{"data":[{...}]}` |
 * | `data.list` 是数组 | `{"data":{"list":[{...}]}}` |
 * | **`data.rows` 是数组** | `{"data":{"rows":[{...}]}}` |
 * | **`data.data` 是数组**（双层 data） | `{"data":{"data":[{...}]}}` |
 * | **`data.data.list` 是数组** | `{"data":{"data":{"list":[...]}}}` |
 *
 * ⚠️ 之所以要吃到"双层 `data`"：网关在不同路由下会把业务体再包一层，
 * 而**同一套网关对 `by-term-list` 与 `by-classify-list` 的包装并不总是一致**。
 * 只认 `data.list` 时，学期维正常、分类维恒空 —— 正是
 * "按分类查看显示没有下发明细、按学期却好好的"这个现象的成因。
 *
 * 全部取不到时返回 null（调用方据此得到空列表，与"站点确实没数据"同形，
 * 但至少不会因为包装形态差异而误判）。
 */
internal fun classifyGroupsArray(payload: org.json.JSONObject): org.json.JSONArray? =
    payload.dataArrayMultiShape()

/** 同 [classifyGroupsArray]，学期维共用同一套形态归一。 */
internal fun termGroupsArray(payload: org.json.JSONObject): org.json.JSONArray? =
    payload.dataArrayMultiShape()

/**
 * 明细响应 → 分组数组的多形态取值。
 *
 * 搜索顺序（先浅后深）：`data` → `data.list/data.rows/data.data` →
 * `data.data.list/data.data.rows`。命中即返回，避免同一份数据被取两次。
 */
private fun org.json.JSONObject.dataArrayMultiShape(): org.json.JSONArray? {
    val data = opt("data")

    // 形态 1：data 本身就是数组
    if (data is org.json.JSONArray) return data

    if (data is org.json.JSONObject) {
        // 形态 2/3：data.list 或 data.rows
        data.optJSONArray("list")?.let { return it }
        data.optJSONArray("rows")?.let { return it }
        // 形态 4/5：再深一层的 data（网关把业务体又包了一层）
        val inner = data.opt("data")
        if (inner is org.json.JSONArray) return inner
        if (inner is org.json.JSONObject) {
            inner.optJSONArray("list")?.let { return it }
            inner.optJSONArray("rows")?.let { return it }
        }
    }

    // 兜底：顶层直接给 list / rows（个别构建不裹 data）
    optJSONArray("list")?.let { return it }
    optJSONArray("rows")?.let { return it }
    return null
}

/**
 * 分类维度：分组 JSON → [SecondClassPointGroup]。
 *
 * 与学期维度的**唯一区别**是分组字段名（`classifyName` / `classifyHours` / `minHours`
 * 对 `termName` / `termHours` / 无下限），所以两个映射各自独立，
 * 但都提为 `internal` 以便单测直接喂 JSON。
 *
 * `name` 为空且没有明细的分组会被丢弃 —— 站点偶尔下发空壳分组
 * （只有 id 没名字），留着会在界面上显示一行空白标题。
 */
internal fun classifyGroupFrom(json: org.json.JSONObject): SecondClassPointGroup =
    SecondClassPointGroup(
        name = json.pointText("classifyName"),
        siteTotal = json.pointNullableDouble("classifyHours"),
        required = json.pointNullableDouble("minHours") ?: 0.0,
        records = json.optJSONArray("hoursRecordList").pointMapObjects { it.toPointRecordInternal() },
    )

/** 学期维度：分组 JSON → [SecondClassPointGroup]。 */
internal fun termGroupFrom(json: org.json.JSONObject): SecondClassPointGroup =
    SecondClassPointGroup(
        name = json.pointText("termName"),
        siteTotal = json.pointNullableDouble("termHours"),
        records = json.optJSONArray("hoursRecordList").pointMapObjects { it.toPointRecordInternal() },
        termNumber = json.pointText("termNumber"),
        unit = json.pointText("termHoursUnit"),
    )

/**
 * 分类维度的完整响应 → 分组列表。
 *
 * 把"取数组 → 逐条映射 → 丢空壳"串成一条链，**顺序固定**（尤其是最后那步
 * 过滤必须在映射之后，否则没法判断分组是不是空壳）。
 */
internal fun org.json.JSONObject.toClassifyGroups(): List<SecondClassPointGroup> =
    classifyGroupsArray(this)
        .pointMapObjects { classifyGroupFrom(it) }
        .filter { it.isMeaningful() }

/** 学期维度的完整响应 → 分组列表。 */
internal fun org.json.JSONObject.toTermGroups(): List<SecondClassPointGroup> =
    termGroupsArray(this)
        .pointMapObjects { termGroupFrom(it) }
        .filter { it.isMeaningful() }

/** 分组是否值得保留：有名字或有明细。空壳分组一律丢弃。 */
internal fun SecondClassPointGroup.isMeaningful(): Boolean =
    name.isNotBlank() || records.isNotEmpty()

/** 把任意 JSONArray 元素映射成列表（org.json 可空友好版）。 */
internal inline fun <T> org.json.JSONArray?.pointMapObjects(
    transform: (org.json.JSONObject) -> T,
): List<T> {
    if (this == null) return emptyList()
    val result = ArrayList<T>(length())
    for (index in 0 until length()) {
        optJSONObject(index)?.let { result.add(transform(it)) }
    }
    return result
}

/** 取文本，把 JSON `null` 与字面量 `"null"` 都归一成空串（与 client 内 `text` 同口径）。 */
internal fun org.json.JSONObject.pointText(key: String): String {
    val value = opt(key)
    if (value == null || value === org.json.JSONObject.NULL) return ""
    val rendered = value.toString().trim()
    return if (rendered == "null" || rendered == "undefined") "" else rendered
}

/** JSON `null` / 缺省 → Kotlin null，用于区分"站点给了 0"与"站点根本没给"。 */
internal fun org.json.JSONObject.pointNullableDouble(key: String): Double? {
    val value = opt(key)
    if (value == null || value === org.json.JSONObject.NULL) return null
    return value.toString().trim().toDoubleOrNull()
}

/**
 * 明细记录 JSON → [SecondClassPointRecord]（供 [classifyGroupFrom] / [termGroupFrom] 共用）。
 *
 * 与 client 内私有版 `toPointRecord()` 的差别：这两个辅助函数无法访问 client 的
 * 扩展方法，所以这里用 `internal` 的 [pointText] / [pointNullableDouble] 重述一遍。
 * 语义必须与 `toPointRecord()` **逐字一致** —— 后者仍是线上代码路径，
 * 两者若漂移就会出现"同一个字段在两条链路上解析不同"的隐形缺陷。
 */
internal fun org.json.JSONObject.toPointRecordInternal(): SecondClassPointRecord = pointRecordFrom(
    name = pointText("name"),
    hours = pointNullableDouble("hours") ?: 0.0,
    amount = pointNullableDouble("amount") ?: 0.0,
    time = pointText("time").toLongOrNull() ?: 0L,
    classifyName = pointText("classifyName"),
    sourceType = pointText("sourceType").toDoubleOrNull()?.toInt() ?: 0,
    relationId = pointText("relationId"),
    identity = pointText("identity"),
)
